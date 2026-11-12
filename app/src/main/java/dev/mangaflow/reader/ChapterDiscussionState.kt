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
import dev.mangaflow.data.ReviewPage
import dev.mangaflow.data.ReviewRepository
import dev.mangaflow.data.ReviewSource
import dev.mangaflow.data.ReviewStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Chapter identity and work identity stay separate even though their rows share one list. */
internal data class ReaderDiscussionSource(
    val source: ReviewSource,
    val isChapter: Boolean,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val failedPage: Int? = null,
)

internal data class ReaderDiscussionRow(val source: ReaderDiscussionSource, val review: MangaReview) {
    val key: String get() = "${source.source.id}:${source.source.subjectId}:${review.kind}:${review.id}"
}

internal fun mixedDiscussionRows(sources: List<ReaderDiscussionSource>): List<ReaderDiscussionRow> =
    sources.asSequence().filter { it.source.status == ReviewStatus.READY }
        .flatMap { source ->
            source.source.reviews.asSequence()
                .filter { (it.kind == ReviewKind.CHAPTER) == source.isChapter }
                .map { ReaderDiscussionRow(source, it) }
        }
        .distinctBy(ReaderDiscussionRow::key)
        .sortedWith(compareByDescending<ReaderDiscussionRow> { discussionDateKey(it.review.date) }
            .thenBy { if (it.source.isChapter) 0 else 1 })
        .toList()

private fun discussionDateKey(date: String): String {
    val value = date.trim().replace('T', ' ')
    return if (value.length >= 10 && value[4] == '-' && value[7] == '-' &&
        value.take(4).all(Char::isDigit)) value.take(19) else ""
}

/**
 * One state owner per book/chapter. All state publication is on the composing scope;
 * IO callbacks arrive through a channel, and generation checks discard cancelled refreshes.
 */
@Stable
internal class ChapterDiscussionState(
    private val manga: Manga,
    private val chapter: Chapter,
    private val chapters: CopyCommunityRepository,
    private val works: ReviewRepository,
    parentScope: CoroutineScope,
) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private var generation = 0L
    private var refreshJob: Job? = null
    private var pageJob: Job? = null

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
            id = "copy", name = "拷贝", status = ReviewStatus.READY,
            url = cached?.items?.firstOrNull()?.url ?: manga.sourceUrl,
            matchedTitle = chapter.title, subjectId = chapter.id,
            reviews = cached?.items.orEmpty(), reviewCount = cached?.total,
            nextPage = if (cached == null) 0 else cached.nextPage,
        )
        val cachedWorks = works.cachedSources(manga).associateBy(ReviewSource::id)
        return listOf(ReaderDiscussionSource(chapterSource, isChapter = true, loaded = cached != null)) +
            listOf("bangumi" to "Bangumi", "anilist" to "AniList").map { (id, name) ->
                val source = cachedWorks[id]
                ReaderDiscussionSource(source ?: ReviewSource(id, name, ReviewStatus.UNAVAILABLE, ""),
                    isChapter = false, loaded = source != null)
            }
    }

    fun refresh(forceRefresh: Boolean = false) {
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
                    launch { fetchPage("copy", 0, version, forceRefresh) }
                    launch { refreshWorks(version, forceRefresh) }
                }
            } finally {
                if (version == generation) {
                    refreshing = false
                    sources = sources.map { it.copy(busy = false) }
                }
            }
        }
    }

    private suspend fun refreshWorks(version: Long, forceRefresh: Boolean) = coroutineScope {
        val updates = Channel<ReviewSource>(Channel.UNLIMITED)
        val collector = launch {
            for (source in updates) {
                if (version == generation) publishSource(source)
            }
        }
        try {
            works.lookup(manga, forceRefresh) { updates.trySend(it) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            if (version == generation) {
                sources = sources.map { current ->
                    if (current.isChapter) current else current.copy(busy = false, loaded = true,
                        source = current.source.copy(reviewsError = failure.message ?: "评论暂时无法读取"),
                        failedPage = 0)
                }
            }
        } finally {
            updates.close()
            collector.join()
        }
    }

    private fun publishSource(source: ReviewSource) {
        sources = sources.map { current ->
            if (current.source.id != source.id || current.isChapter) current else {
                val previous = current.source
                val retained = when {
                    source.status == ReviewStatus.UNAVAILABLE && previous.status == ReviewStatus.READY ->
                        previous.copy(reviewsError = source.message.ifBlank { "评论暂时无法读取" })
                    source.status == ReviewStatus.READY && source.subjectId == previous.subjectId &&
                        source.reviewsError != null && previous.reviews.isNotEmpty() ->
                        source.copy(reviews = previous.reviews, nextPage = previous.nextPage)
                    else -> source
                }
                current.copy(source = retained, loaded = true, busy = false,
                    failedPage = if (retained.reviewsError == null) null else 0)
            }
        }
    }

    fun loadNext() {
        if (refreshing || paging) return
        val next = sources.filter(::canLoadMore)
            .minWithOrNull(compareBy<ReaderDiscussionSource> { it.source.reviews.size }
                .thenBy { if (it.isChapter) 0 else 1 }) ?: return
        loadPage(next.source.id, next.source.nextPage ?: return)
    }

    fun retry(sourceId: String) {
        if (refreshing || paging) return
        val current = sources.firstOrNull { it.source.id == sourceId } ?: return
        if (!current.isChapter && (current.source.status != ReviewStatus.READY || current.source.subjectId.isBlank())) {
            refresh(forceRefresh = true)
        } else {
            loadPage(sourceId, current.failedPage ?: 0, forceRefresh = true)
        }
    }

    private fun loadPage(sourceId: String, page: Int, forceRefresh: Boolean = false) {
        val version = generation
        paging = true
        updateSource(sourceId) { it.copy(busy = true, failedPage = null,
            source = it.source.copy(reviewsError = null)) }
        pageJob = scope.launch {
            try { fetchPage(sourceId, page, version, forceRefresh) }
            finally {
                if (version == generation) {
                    paging = false
                    updateSource(sourceId) { it.copy(busy = false) }
                }
            }
        }
    }

    private suspend fun fetchPage(sourceId: String, page: Int, version: Long, forceRefresh: Boolean) {
        val current = sources.firstOrNull { it.source.id == sourceId } ?: return
        try {
            val result: ReviewPage = if (current.isChapter) chapters.chapterComments(manga, chapter, page, forceRefresh)
                else works.loadMore(manga, current.source, page)
            currentCoroutineContext().ensureActive()
            if (version != generation) return
            updateSource(sourceId) { state ->
                state.copy(loaded = true, busy = false, failedPage = null,
                    source = state.source.copy(
                        reviews = (if (page == 0) result.items else state.source.reviews + result.items)
                            .distinctBy(MangaReview::id),
                        reviewCount = result.total ?: state.source.reviewCount,
                        nextPage = result.nextPage, reviewsError = null,
                    ))
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            if (version == generation) updateSource(sourceId) {
                it.copy(busy = false, loaded = true, failedPage = page,
                    source = it.source.copy(reviewsError = failure.message ?: "评论暂时无法读取"))
            }
        }
    }

    private fun updateSource(sourceId: String, transform: (ReaderDiscussionSource) -> ReaderDiscussionSource) {
        sources = sources.map { if (it.source.id == sourceId) transform(it) else it }
    }

    private fun canLoadMore(source: ReaderDiscussionSource): Boolean = source.loaded && !source.busy &&
        source.source.status == ReviewStatus.READY && source.source.reviewsError == null && source.source.nextPage != null

    fun dispose() {
        generation++
        lifetime.cancel()
    }
}
