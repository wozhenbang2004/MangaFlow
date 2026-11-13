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
import dev.mangaflow.data.ReviewRepository
import dev.mangaflow.data.ReviewPage
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
    private val reviews: ReviewRepository,
    parentScope: CoroutineScope,
) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private var generation = 0L
    private var refreshJob: Job? = null
    private var pageJob: Job? = null
    private var started = false

    // A merged chapter may display a different edition while still having a confirmed
    // Copy counterpart. The displayed ID owns this UI; only the native UUID reaches Copy.
    private val copyVariant = chapter.variants.firstOrNull { it.sourceId == "copy" }
    private val copyChapter = copyVariant?.let { variant ->
        chapter.copy(id = variant.chapterId, title = variant.title, sourceId = "copy",
            sourceMangaId = variant.mangaId, sourceChapterId = variant.chapterId)
    } ?: chapter.takeIf { it.sourceId == "copy" && !it.id.startsWith("@source") }
    private val copyManga = copyChapter?.let { native ->
        val nativeId = native.sourceMangaId.ifBlank { manga.sourceMangaId.ifBlank { manga.id } }
        manga.copy(id = nativeId, sourceId = "copy", sourceMangaId = nativeId)
    }

    var sources by mutableStateOf(initialSources())
        private set
    var refreshing by mutableStateOf(false)
        private set
    var paging by mutableStateOf(false)
        private set

    val hasMore: Boolean get() = sources.any(::canLoadMore)

    val totalCount: Int? get() {
        if (sources.any { !it.loaded || it.source.status == ReviewStatus.UNAVAILABLE || it.source.reviewsError != null }) return null
        val ready = sources.filter { it.source.status == ReviewStatus.READY }
        if (ready.isEmpty() || ready.any { it.source.reviewCount == null }) return null
        return ready.sumOf { it.source.reviewCount!!.toLong() }.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    private fun initialSources(): List<ReaderDiscussionSource> {
        val native = copyChapter
        val cached = native?.let(chapters::cachedChapterComments)
        val copySources = if (native == null) emptyList() else listOf(ReaderDiscussionSource(ReviewSource(
            id = "copy", name = "拷贝", status = ReviewStatus.READY,
            url = cached?.items?.firstOrNull()?.url ?: manga.sourceUrl,
            matchedTitle = native.title, subjectId = chapter.id,
            reviews = cached?.items.orEmpty().filter { it.kind == ReviewKind.CHAPTER }, reviewCount = cached?.total,
            nextPage = if (cached == null) 0 else cached.nextPage,
        ), loaded = cached != null))
        return copySources + reviews.chapterProviderIds.map { id -> ReaderDiscussionSource(ReviewSource(
                id = id, name = "Dantotsu", status = ReviewStatus.NO_MATCH, url = "https://api.dantotsu.app",
                matchedTitle = chapter.title, subjectId = chapter.id,
            )) }
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
            check(current.source.subjectId == chapter.id) { "章节评论来源不匹配" }
            if (current.source.id != "copy" && page == 0) {
                val incoming = reviews.chapterSources(manga, chapter).firstOrNull { it.id == current.source.id }
                    ?: throw IllegalStateException("评论来源暂不可用")
                currentCoroutineContext().ensureActive()
                if (version != generation) return
                check(incoming.subjectId == chapter.id) { "章节评论归属不一致" }
                updateSource(sourceKey) { state ->
                    val retained = if (incoming.status == ReviewStatus.UNAVAILABLE && state.source.reviews.isNotEmpty())
                        state.source.copy(reviewsError = incoming.message)
                    else incoming.copy(reviews = incoming.reviews.filter { it.kind == ReviewKind.CHAPTER })
                    state.copy(source = retained, loaded = true, busy = false,
                        failedPage = if (incoming.status == ReviewStatus.UNAVAILABLE || incoming.reviewsError != null) 0 else null)
                }
                return
            }
            val result = if (current.source.id == "copy") {
                val native = copyChapter ?: throw IllegalStateException("本话没有已确认的拷贝章节")
                val owner = copyManga ?: throw IllegalStateException("本话没有已确认的拷贝作品")
                chapters.chapterComments(owner, native, page, forceRefresh)
            } else reviews.chapterPage(manga, chapter, current.source, page)
            currentCoroutineContext().ensureActive()
            if (version != generation) return
            updateSource(sourceKey) { state ->
                state.copy(loaded = true, busy = false, failedPage = null,
                    source = state.source.copy(
                        reviews = (if (page == 0) result.items else state.source.reviews + result.items)
                            .filter { it.kind == ReviewKind.CHAPTER }.distinctBy(MangaReview::id),
                        reviewCount = result.total ?: state.source.reviewCount,
                        nextPage = result.nextPage, reviewsError = result.error,
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

    suspend fun replies(source: ReviewSource, review: MangaReview, page: Int): ReviewPage {
        if (source.subjectId != chapter.id || review.kind != ReviewKind.CHAPTER)
            throw IllegalStateException("这条评论不属于当前章节")
        return reviews.chapterReplies(manga, chapter, source, review, page)
    }

    fun dispose() {
        generation++
        lifetime.cancel()
    }
}
