package dev.mangaflow.reader

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.CopyCommunityRepository
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaReview
import dev.mangaflow.data.ReviewKind
import dev.mangaflow.data.ReviewSource
import dev.mangaflow.data.ReviewStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Every source is bound to the exact chapter represented by subjectId. */
internal data class ReaderDiscussionSource(
    val source: ReviewSource,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val failedPage: Int? = null,
) {
    val key: String get() = "${source.id}:chapter:${source.subjectId}"
}

internal data class ReaderDiscussionRow(val source: ReaderDiscussionSource, val review: MangaReview) {
    val key: String get() = "${source.key}:${review.id}"
}

internal fun mixedDiscussionRows(sources: List<ReaderDiscussionSource>, chapterId: String): List<ReaderDiscussionRow> =
    sources.asSequence().filter {
        it.source.status == ReviewStatus.READY && it.source.subjectId == chapterId
    }
        .flatMap { source ->
            source.source.reviews.asSequence()
                .filter { it.kind == ReviewKind.CHAPTER }
                .map { ReaderDiscussionRow(source, it) }
        }
        .distinctBy(ReaderDiscussionRow::key)
        .sortedByDescending { discussionDateKey(it.review.date) }
        .toList()

private fun discussionDateKey(date: String): String {
    val value = date.trim().replace('T', ' ')
    return if (value.length >= 10 && value[4] == '-' && value[7] == '-' &&
        value.take(4).all(Char::isDigit)) value.take(19) else ""
}

/**
 * One state owner per book/chapter. All state publication is on the composing scope,
 * and generation checks discard cancelled refreshes.
 */
@Stable
internal class ChapterDiscussionState(
    private val manga: Manga,
    private val chapter: Chapter,
    private val chapters: CopyCommunityRepository,
    parentScope: CoroutineScope,
) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private var generation = 0L
    private var refreshJob: Job? = null
    private var pageJob: Job? = null
    private var started = false

    var sources by mutableStateOf(initialSources())
        private set
    var refreshing by mutableStateOf(false)
        private set
    var paging by mutableStateOf(false)
        private set

    val hasMore: Boolean get() = sources.any(::canLoadMore)

    private fun initialSources(): List<ReaderDiscussionSource> {
        val cached = chapters.cachedChapterComments(chapter)
        val chapterSource = ReviewSource(
            id = "copy", name = "读者", status = ReviewStatus.READY,
            url = cached?.items?.firstOrNull()?.url ?: manga.sourceUrl,
            matchedTitle = chapter.title, subjectId = chapter.id,
            reviews = cached?.items.orEmpty().filter { it.kind == ReviewKind.CHAPTER }, reviewCount = cached?.total,
            nextPage = if (cached == null) 0 else cached.nextPage,
        )
        return listOf(ReaderDiscussionSource(chapterSource, loaded = cached != null))
    }

    /** The chapter end and toolbar share one lifetime, but neither starts work until displayed. */
    fun ensureLoaded() {
        if (!started) refresh()
    }

    fun refresh(forceRefresh: Boolean = false) {
        started = true
        val version = ++generation
        refreshJob?.cancel()
        pageJob?.cancel()
        paging = false
        refreshing = true
        sources = sources.map { it.copy(busy = true, failedPage = null,
            source = it.source.copy(reviewsError = null)) }
        refreshJob = scope.launch {
            try {
                coroutineScope {
                    sources.forEach { source ->
                        launch { fetchPage(source.key, 0, version, forceRefresh) }
                    }
                }
            } finally {
                if (version == generation) {
                    refreshing = false
                    sources = sources.map { it.copy(busy = false) }
                }
            }
        }
    }

    fun loadNext() {
        if (refreshing || paging) return
        val next = sources.filter(::canLoadMore).minByOrNull { it.source.reviews.size } ?: return
        loadPage(next.key, next.source.nextPage ?: return)
    }

    fun retry(sourceKey: String) {
        if (refreshing || paging) return
        val current = sources.firstOrNull { it.key == sourceKey } ?: return
        loadPage(sourceKey, current.failedPage ?: 0, forceRefresh = true)
    }

    private fun loadPage(sourceKey: String, page: Int, forceRefresh: Boolean = false) {
        val version = generation
        paging = true
        updateSource(sourceKey) { it.copy(busy = true, failedPage = null,
            source = it.source.copy(reviewsError = null)) }
        pageJob = scope.launch {
            try { fetchPage(sourceKey, page, version, forceRefresh) }
            finally {
                if (version == generation) {
                    paging = false
                    updateSource(sourceKey) { it.copy(busy = false) }
                }
            }
        }
    }

    private suspend fun fetchPage(sourceKey: String, page: Int, version: Long, forceRefresh: Boolean) {
        val current = sources.firstOrNull { it.key == sourceKey } ?: return
        try {
            check(current.source.id == "copy" && current.source.subjectId == chapter.id) { "章节评论来源不匹配" }
            val result = chapters.chapterComments(manga, chapter, page, forceRefresh)
            currentCoroutineContext().ensureActive()
            if (version != generation) return
            updateSource(sourceKey) { state ->
                state.copy(loaded = true, busy = false, failedPage = null,
                    source = state.source.copy(
                        reviews = (if (page == 0) result.items else state.source.reviews + result.items)
                            .filter { it.kind == ReviewKind.CHAPTER }.distinctBy(MangaReview::id),
                        reviewCount = result.total ?: state.source.reviewCount,
                        nextPage = result.nextPage, reviewsError = null,
                    ))
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            if (version == generation) updateSource(sourceKey) {
                it.copy(busy = false, loaded = true, failedPage = page,
                    source = it.source.copy(reviewsError = failure.message ?: "评论暂时无法读取"))
            }
        }
    }

    private fun updateSource(sourceKey: String, transform: (ReaderDiscussionSource) -> ReaderDiscussionSource) {
        sources = sources.map { if (it.key == sourceKey) transform(it) else it }
    }

    private fun canLoadMore(source: ReaderDiscussionSource): Boolean = source.loaded && !source.busy &&
        source.source.status == ReviewStatus.READY && source.source.reviewsError == null && source.source.nextPage != null

    fun dispose() {
        generation++
        lifetime.cancel()
    }
}
