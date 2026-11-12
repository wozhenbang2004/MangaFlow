package dev.mangaflow

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mangaflow.account.AccountProfile
import dev.mangaflow.account.CloudHistoryEntry
import dev.mangaflow.account.CopyAccountRepository
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.CopyAnonymousIdentityStore
import dev.mangaflow.data.CopyMangaRepository
import dev.mangaflow.data.CopyCommunityRepository
import dev.mangaflow.data.DiscoveryMode
import dev.mangaflow.data.RankPeriod
import dev.mangaflow.data.RankAudience
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaDetail
import dev.mangaflow.data.MangaMetric
import dev.mangaflow.data.MangaMetricsRepository
import dev.mangaflow.data.ReviewRepository
import dev.mangaflow.data.ReviewProviders
import dev.mangaflow.data.ReviewSource
import dev.mangaflow.data.ReviewStatus
import dev.mangaflow.data.ReviewPage
import dev.mangaflow.data.MangaReview
import dev.mangaflow.data.SourceException
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.DownloadStore
import dev.mangaflow.storage.LibraryStore
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.storage.ReaderCatalogStore
import dev.mangaflow.storage.BrowseCache
import dev.mangaflow.storage.BrowseSnapshot
import dev.mangaflow.update.UpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class BrowseState(
    val query: String = "",
    val mode: DiscoveryMode = DiscoveryMode.RECOMMENDED,
    val period: RankPeriod = RankPeriod.WEEK,
    val audience: RankAudience = RankAudience.MALE,
    val items: List<Manga> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    val failedPage: Int? = null,
    val page: Int = 0,
    val refreshing: Boolean = false,
    val updatedAt: Long = 0,
) {
    val cacheKey: String get() = when {
        query.isNotBlank() -> "search:${query.trim()}"
        mode == DiscoveryMode.RANKING -> "ranking:${period.name}:${audience.name}"
        else -> "discover:${mode.name}"
    }
}

data class DetailState(
    val manga: Manga,
    val detail: MangaDetail? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val reviews: List<ReviewSource> = emptyList(),
    val reviewsLoading: Boolean = true,
)

data class ReadingSession(
    val manga: Manga,
    val chapters: List<Chapter>,
    val chapterId: String,
    val chaptersLoading: Boolean = false,
    val chaptersError: String? = null,
)

data class LibraryOpenState(
    val manga: Manga,
    val loading: Boolean = true,
    val error: String? = null,
)

data class AccountUiState(
    val profile: AccountProfile? = null,
    val busy: Boolean = true,
    val error: String? = null,
    val canRetryConnection: Boolean = false,
    val favorites: List<Manga> = emptyList(),
    val favoritesLoading: Boolean = false,
    val favoritesHasMore: Boolean = false,
    val favoritesError: String? = null,
    val history: List<CloudHistoryEntry> = emptyList(),
    val historyLoading: Boolean = false,
    val historyHasMore: Boolean = false,
    val historyError: String? = null,
    val syncMessage: String? = null,
    val canRetrySync: Boolean = false,
    val syncingFavorites: Set<String> = emptySet(),
)

class MangaViewModel(application: Application) : AndroidViewModel(application) {
    val repository = CopyMangaRepository(identityProvider = { CopyAnonymousIdentityStore.load(application) })
    val library = LibraryStore(application)
    val downloads = DownloadStore.get(application)
    val updates = UpdateManager.get(application)
    val community = CopyCommunityRepository(repository)
    val reviews = ReviewRepository(application)
    private val readerCatalogs = ReaderCatalogStore(application)
    private val browseCache = BrowseCache(application)
    private val metricsRepository = MangaMetricsRepository(application, reviews, community)
    private val _metrics = MutableStateFlow<Map<String, List<MangaMetric>>>(emptyMap())
    val metrics = _metrics.asStateFlow()
    private val accountRepository = CopyAccountRepository(application)
    private val _account = MutableStateFlow(AccountUiState())
    val account = _account.asStateFlow()
    private val _browse = MutableStateFlow(BrowseState())
    val browse = _browse.asStateFlow()
    private var retainedBrowse: BrowseState? = null
    private val _detail = MutableStateFlow<DetailState?>(null)
    val detail = _detail.asStateFlow()
    private val _reading = MutableStateFlow<ReadingSession?>(null)
    val reading = _reading.asStateFlow()
    private val _libraryOpening = MutableStateFlow<LibraryOpenState?>(null)
    val libraryOpening = _libraryOpening.asStateFlow()
    private val _refreshingDownloads = MutableStateFlow<Set<String>>(emptySet())
    val refreshingDownloads = _refreshingDownloads.asStateFlow()
    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()
    private var browseJob: Job? = null
    private val metricJobs = mutableMapOf<String, Job>()
    private data class MetricReference(val manga: Manga, val count: Int)
    private val metricReferences = mutableMapOf<String, MetricReference>()
    private val metricReleaseJobs = mutableMapOf<String, Job>()
    private val metricWrites = mutableSetOf<Job>()
    private var clearingMetadata = false
    @Volatile private var metricsRequestId = 0L
    private var detailJob: Job? = null
    private var reviewJob: Job? = null
    private var readingCatalogJob: Job? = null
    private var libraryOpenJob: Job? = null
    private var libraryOpenRequestId = 0L
    private var chapterPrefetchJob: Job? = null
    private var chapterPrefetchKey: String? = null
    private var readingRequestId = 0L
    @Volatile private var detailRequestId = 0L
    @Volatile private var browseRequestId = 0L
    private val reviewPageJobs = mutableMapOf<String, Job>()
    private val reviewOverrides = ConcurrentHashMap.newKeySet<String>()
    private val preparingDownloads = mutableSetOf<String>()
    private var accountJob: Job? = null
    private var accountRequestId = 0L
    private var accountCleanupFailed = false
    private var cloudFavoritesJob: Job? = null
    private var cloudHistoryJob: Job? = null
    private var chapterSyncJob: Job? = null
    private val favoriteSyncJobs = mutableMapOf<String, Job>()
    private val pendingFavoriteSync = mutableMapOf<String, Pair<Manga, Boolean>>()
    private var pendingChapterSync: Pair<Manga, Chapter>? = null
    private var cloudFavoritesPage = -1
    private var cloudHistoryPage = -1
    private var cloudFavoritesFailedPage: Int? = null
    private var cloudHistoryFailedPage: Int? = null
    private var lastChapterSync: String? = null
    private var visibleReading: Pair<Manga, Chapter>? = null

    init { showBrowse(_browse.value); restoreAccount() }

    fun setQuery(query: String) {
        if (query == _browse.value.query) return
        val previous = _browse.value
        showBrowse(BrowseState(query = query, mode = previous.mode, period = previous.period,
            audience = previous.audience), debounce = query.isNotBlank())
    }

    fun selectDiscovery(mode: DiscoveryMode, period: RankPeriod = _browse.value.period,
        audience: RankAudience = _browse.value.audience, clearQuery: Boolean = false) {
        val previous = _browse.value
        val query = if (clearQuery) "" else previous.query
        if (previous.mode == mode && previous.period == period && previous.audience == audience && previous.query == query) return
        showBrowse(BrowseState(query = query, mode = mode, period = period, audience = audience))
    }

    /** The search backdrop survives screen disposal and Activity recreation with this ViewModel. */
    fun browseBackground(selection: BrowseState): BrowseState {
        if (selection.query.isBlank()) return selection
        val background = BrowseState(mode = selection.mode, period = selection.period, audience = selection.audience)
        return retainedBrowse?.takeIf { it.cacheKey == background.cacheKey }
            ?: browseCache.cached(background.cacheKey)?.restore(background)
            ?: background
    }

    private fun showBrowse(selection: BrowseState, debounce: Boolean = false) {
        val previous = _browse.value
        if (previous.query.isBlank()) retainedBrowse = previous
        browseJob?.cancel()
        val requestId = ++browseRequestId
        val generation = browseCache.generation
        val memory = browseCache.cached(selection.cacheKey)
        _browse.value = memory?.restore(selection) ?: selection
        browseJob = viewModelScope.launch {
            val snapshot = memory ?: browseCache.read(selection.cacheKey, generation)
            if (requestId != browseRequestId) return@launch
            if (snapshot != null) {
                _browse.value = snapshot.restore(selection)
                // Revalidating page zero must not truncate a restored multi-page list or
                // mix a newly ordered ranking with older pages. Refresh stays explicit there.
                if (snapshot.page > 0 || isFresh(snapshot.updatedAt, selection)) return@launch
            }
            if (debounce) delay(450)
            if (requestId != browseRequestId) return@launch
            _browse.update { it.copy(loading = snapshot == null, refreshing = snapshot != null) }
            loadBrowse(_browse.value, 0, requestId, generation)
        }
    }

    fun refresh() {
        reloadRetainedMetrics(forceRefresh = true)
        if (browseJob?.isActive == true && (_browse.value.loading || _browse.value.refreshing)) return
        browseJob?.cancel()
        val requestId = ++browseRequestId
        val state = _browse.value
        val generation = browseCache.generation
        _browse.value = state.copy(loading = state.items.isEmpty(), refreshing = state.items.isNotEmpty(),
            error = null, failedPage = null, loadingMore = false)
        browseJob = viewModelScope.launch { loadBrowse(state, 0, requestId, generation) }
    }

    fun loadMore() {
        val state = _browse.value
        if (state.loading || state.refreshing || state.loadingMore || !state.hasMore || state.error != null) return
        _browse.update { it.copy(loadingMore = true, error = null, failedPage = null) }
        val requestId = ++browseRequestId
        val generation = browseCache.generation
        browseJob = viewModelScope.launch { loadBrowse(state, state.page + 1, requestId, generation) }
    }

    fun retryBrowse() {
        val state = _browse.value
        if (state.loading || state.refreshing || state.loadingMore) return
        val page = state.failedPage ?: return
        _browse.update { it.copy(loading = page == 0 && it.items.isEmpty(),
            refreshing = page == 0 && it.items.isNotEmpty(), loadingMore = page > 0, error = null, failedPage = null) }
        val requestId = ++browseRequestId
        val generation = browseCache.generation
        browseJob = viewModelScope.launch { loadBrowse(state, page, requestId, generation) }
    }

    private suspend fun loadBrowse(state: BrowseState, page: Int, requestId: Long, generation: Long) {
        try {
            val query = state.query.trim()
            val result = if (query.isBlank()) community.browse(state.mode, state.period, state.audience, page)
                else repository.search(query, page)
            if (requestId != browseRequestId || generation != browseCache.generation) return
            _browse.update { current ->
                if (requestId != browseRequestId) current else current.copy(
                    items = (if (page == 0) result.items else current.items + result.items).distinctBy(Manga::id),
                    loading = false, refreshing = false, loadingMore = false, error = null, failedPage = null,
                    hasMore = result.hasMore, page = page,
                    updatedAt = if (page == 0 || current.updatedAt <= 0) System.currentTimeMillis() else current.updatedAt,
                )
            }
            val current = _browse.value
            if (requestId == browseRequestId && current.cacheKey == state.cacheKey) {
                val snapshot = BrowseSnapshot(current.cacheKey, current.items, current.page, current.hasMore, current.updatedAt)
                browseCache.remember(snapshot, generation)
                browseCache.write(snapshot, generation)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            _browse.update { if (requestId != browseRequestId) it else it.copy(loading = false, refreshing = false, loadingMore = false,
                error = failure.message ?: "暂时无法读取漫画", failedPage = page) }
        }
    }

    private fun BrowseSnapshot.restore(selection: BrowseState) = selection.copy(
        items = items, page = page, hasMore = hasMore, updatedAt = updatedAt,
        loading = false, loadingMore = false, refreshing = false, error = null, failedPage = null,
    )

    private fun isFresh(updatedAt: Long, state: BrowseState): Boolean {
        val ttl = when {
            state.query.isNotBlank() || state.mode != DiscoveryMode.RANKING -> 15 * 60_000L
            state.period == RankPeriod.DAY || state.period == RankPeriod.WEEK -> 30 * 60_000L
            else -> 60 * 60_000L
        }
        return System.currentTimeMillis() - updatedAt in 0 until ttl
    }

    suspend fun browseCacheSize(): Long = browseCache.sizeBytes()

    suspend fun clearBrowseCache() {
        browseJob?.cancel()
        browseRequestId++
        _browse.update { it.copy(loading = false, refreshing = false, loadingMore = false,
            error = null, failedPage = null, updatedAt = 0) }
        browseCache.clear()
    }

    /** Called for visible manga only; source matching never blocks a list or its images. */
    fun loadMetrics(manga: Manga, forceRefresh: Boolean = false) {
        metricReferences[manga.id]?.let { metricReferences[manga.id] = it.copy(manga = manga) }
        if (clearingMetadata) return
        metricsRepository.cached(manga.id).takeIf { it.isNotEmpty() }?.let { cached ->
            publishMetrics(manga.id, cached)
        }
        if (!forceRefresh && metricJobs[manga.id]?.isActive == true) return
        metricJobs.remove(manga.id)?.cancel()
        val requestId = metricsRequestId
        val request = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val job = coroutineContext[Job] ?: return@launch
            try {
                metricsRepository.lookup(manga, forceRefresh = forceRefresh) {
                    // Providers publish from IO, but ownership changes stay on Main. Read
                    // the latest snapshot there so an older callback cannot hide a new source.
                    viewModelScope.launch {
                        if (!clearingMetadata && requestId == metricsRequestId &&
                            metricJobs[manga.id] === job && job.isActive) {
                            publishMetrics(manga.id, metricsRepository.cached(manga.id))
                        }
                    }
                }
                if (!clearingMetadata && requestId == metricsRequestId &&
                    metricJobs[manga.id] === job && job.isActive) {
                    publishMetrics(manga.id, metricsRepository.cached(manga.id))
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Missing metrics do not hide a manga; the detail page retains source errors.
            } finally {
                if (metricJobs[manga.id] === job) metricJobs.remove(manga.id)
            }
        }
        metricJobs[manga.id] = request
        request.start()
    }

    fun retainMetrics(manga: Manga) {
        metricReleaseJobs.remove(manga.id)?.cancel()
        metricReferences[manga.id] = MetricReference(manga, (metricReferences[manga.id]?.count ?: 0) + 1)
        loadMetrics(manga)
    }

    fun releaseMetrics(mangaId: String) {
        val reference = metricReferences[mangaId] ?: return
        if (reference.count <= 0) return
        if (reference.count > 1) {
            metricReferences[mangaId] = reference.copy(count = reference.count - 1)
            return
        }
        metricReferences[mangaId] = reference.copy(count = 0)
        metricReleaseJobs.remove(mangaId)?.cancel()
        if (clearingMetadata) {
            metricReferences.remove(mangaId)
            return
        }
        val release = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val job = coroutineContext[Job] ?: return@launch
            try {
                // Leave room for the next tab/route's debounced subscriber to take over.
                delay(600)
                if (metricReferences[mangaId]?.count == 0) {
                    metricReferences.remove(mangaId)
                    metricJobs.remove(mangaId)?.cancel()
                }
            } finally {
                if (metricReleaseJobs[mangaId] === job) metricReleaseJobs.remove(mangaId)
            }
        }
        metricReleaseJobs[mangaId] = release
        release.start()
    }

    private fun reloadRetainedMetrics(forceRefresh: Boolean) {
        val retained = metricReferences.values.filter { it.count > 0 }.map { it.manga }
        retained.forEach { loadMetrics(it, forceRefresh = forceRefresh) }
    }

    private fun publishMetrics(mangaId: String, values: List<MangaMetric>) {
        _metrics.update { current ->
            LinkedHashMap(current).apply {
                remove(mangaId)
                put(mangaId, values)
                while (size > 240) remove(keys.first())
            }
        }
    }

    suspend fun metadataCacheSize(): Long = metricsRepository.cacheSizeBytes() + readerCatalogs.sizeBytes()

    suspend fun clearMetadataCache(): Unit = withContext(Dispatchers.Main.immediate) {
        if (clearingMetadata) return@withContext
        clearingMetadata = true
        reviewJob?.cancel()
        reviewPageJobs.values.toList().forEach { it.cancel() }
        reviewPageJobs.clear()
        reviewOverrides.clear()
        _detail.update { current -> current?.copy(reviewsLoading = false,
            reviews = current.reviews.map { it.copy(loadingMore = false) }) }
        metricsRequestId++
        val jobs = metricJobs.values.toList() + metricWrites.toList() + metricReleaseJobs.values.toList()
        metricJobs.clear()
        metricWrites.clear()
        metricReleaseJobs.clear()
        metricReferences.entries.removeAll { it.value.count <= 0 }
        jobs.forEach { it.cancel() }
        _metrics.value = emptyMap()
        try {
            jobs.joinAll()
            metricsRepository.clearCache()
            readerCatalogs.clear()
        } finally {
            clearingMetadata = false
            reloadRetainedMetrics(forceRefresh = true)
        }
    }

    private fun recordDetailMetrics(mangaId: String, detailId: Long) {
        if (clearingMetadata) return
        val requestId = metricsRequestId
        viewModelScope.launch {
            if (clearingMetadata || requestId != metricsRequestId) return@launch
            val current = _detail.value?.takeIf { it.manga.id == mangaId && detailId == detailRequestId }
                ?: return@launch
            val job = coroutineContext[Job] ?: return@launch
            metricWrites.add(job)
            try {
                metricsRepository.recordSources(current.manga, current.reviews)
                if (!clearingMetadata && requestId == metricsRequestId && job.isActive) {
                    publishMetrics(mangaId, metricsRepository.cached(mangaId))
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Review content remains available even if optional metadata cannot be cached.
            } finally {
                metricWrites.remove(job)
            }
        }
    }

    fun openManga(manga: Manga, continueChapterId: String? = null) {
        cancelLibraryReading()
        detailJob?.cancel()
        reviewJob?.cancel()
        reviewPageJobs.values.toList().forEach { it.cancel() }
        reviewPageJobs.clear()
        reviewOverrides.clear()
        val requestId = ++detailRequestId
        val previous = _detail.value?.takeIf { it.manga.id == manga.id }
        val retained = previous?.detail?.takeIf { it.chapters.isNotEmpty() }
            ?: repository.cachedDetail(manga.id)
        val initialManga = (retained?.manga ?: previous?.manga ?: manga).let {
            val totalHeat = it.totalHeat() ?: manga.totalHeat()
            it.copy(popularity = totalHeat, totalPopularity = totalHeat, rank = null)
        }
        val initialChapters = (retained?.chapters.orEmpty() + downloadedChapters(manga.id))
            .distinctBy(Chapter::id).sortedBy(Chapter::index)
        _detail.value = DetailState(initialManga,
            detail = initialChapters.takeIf { it.isNotEmpty() }?.let { MangaDetail(initialManga, it) })
        loadReviews(initialManga, requestId)
        detailJob = viewModelScope.launch {
            try {
                val loaded = repository.detail(manga.id) { metadata ->
                    _detail.update { current ->
                        if (current?.manga?.id != manga.id || requestId != detailRequestId) current
                        else {
                            val totalHeat = metadata.totalHeat() ?: current.manga.totalHeat()
                            val updatedManga = metadata.copy(popularity = totalHeat, totalPopularity = totalHeat, rank = null)
                            current.copy(manga = updatedManga, detail = current.detail?.copy(manga = updatedManga),
                                reviews = current.reviews.map { source -> if (source.id == "copy")
                                    source.copy(popularity = totalHeat ?: source.popularity) else source })
                        }
                    }
                }
                _detail.update { current ->
                    if (requestId != detailRequestId || current?.manga?.id != manga.id) current
                    else {
                        val totalHeat = loaded.manga.totalHeat() ?: current.manga.totalHeat()
                        val updatedManga = loaded.manga.copy(popularity = totalHeat, totalPopularity = totalHeat, rank = null)
                        current.copy(manga = updatedManga, detail = loaded.copy(manga = updatedManga), loading = false)
                    }
                }
                readerCatalogs.save(loaded)
                if (requestId != detailRequestId) return@launch
                recordDetailMetrics(manga.id, requestId)
                if (continueChapterId != null) {
                    val chapter = loaded.chapters.firstOrNull { it.id == continueChapterId }
                    if (chapter != null) readChapter(chapter)
                    else messages.send("阅读记录中的章节不在当前目录中，请从目录选择章节。")
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                val offline = downloadedChapters(manga.id)
                _detail.update { current ->
                    if (current?.manga?.id != manga.id || requestId != detailRequestId) current
                    else {
                        val chapters = (current.detail?.chapters.orEmpty() + offline)
                            .distinctBy(Chapter::id).sortedBy(Chapter::index)
                        current.copy(loading = false, error = failure.message ?: "暂时无法读取线上目录",
                            detail = chapters.takeIf { it.isNotEmpty() }?.let { MangaDetail(current.manga, it) })
                    }
                }
                if (requestId == detailRequestId && continueChapterId != null) {
                    _detail.value?.detail?.chapters?.firstOrNull { it.id == continueChapterId }?.let(::readChapter)
                }
            }
        }
    }

    private fun loadReviews(manga: Manga, requestId: Long) {
        reviewJob = viewModelScope.launch {
            try {
                coroutineScope {
                    launch {
                        val source = try { community.overview(manga) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (failure: Exception) { ReviewSource("copy", "拷贝", ReviewStatus.UNAVAILABLE,
                            manga.sourceUrl, popularity = manga.popularity,
                            message = failure.message ?: "评论暂时无法读取") }
                        if ("copy" !in reviewOverrides) publishReview(manga.id, requestId, source)
                    }
                    launch {
                        try { reviews.lookup(manga) {
                            if (it.id !in reviewOverrides) publishReview(manga.id, requestId, it)
                        } }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) {
                            ReviewProviders.all.forEach { provider ->
                                val id = provider.id
                                val name = provider.name
                                if (id !in reviewOverrides && _detail.value?.reviews?.none { it.id == id } == true)
                                    publishReview(manga.id, requestId, ReviewSource(id, name, ReviewStatus.UNAVAILABLE,
                                        provider.homeUrl,
                                        message = "$name 暂时无法读取", hasWorkReviews = provider.hasWorkReviews))
                            }
                        }
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } finally {
                _detail.update { if (it?.manga?.id == manga.id && requestId == detailRequestId)
                    it.copy(reviewsLoading = false) else it }
            }
        }
    }

    private fun publishReview(mangaId: String, requestId: Long, source: ReviewSource) {
        _detail.update { current ->
            if (current?.manga?.id != mangaId || requestId != detailRequestId) current
            else current.copy(reviews = (current.reviews.filterNot { it.id == source.id } +
                if (source.id == "copy") source.copy(popularity = current.manga.totalHeat() ?: source.popularity) else source)
                .sortedBy { ReviewProviders.order(it.id) })
        }
        recordDetailMetrics(mangaId, requestId)
    }

    private fun Manga.totalHeat(): Long? = totalPopularity ?: popularity.takeIf { rank == null }

    fun moreReviews(sourceId: String) {
        val state = _detail.value ?: return
        val source = state.reviews.firstOrNull { it.id == sourceId } ?: return
        val page = source.nextPage ?: return
        if (!source.hasWorkReviews) return
        if (source.loadingMore || reviewPageJobs[sourceId]?.isActive == true) return
        val requestId = detailRequestId
        val association = reviews.identityKey(state.manga)
        reviewOverrides.add(sourceId)
        publishReview(state.manga.id, requestId, source.copy(loadingMore = true, reviewsError = null))
        reviewPageJobs[sourceId] = viewModelScope.launch {
            try {
                val result = if (sourceId == "copy") community.comments(state.manga, source, page)
                    else reviews.loadMore(state.manga, source, page)
                if (sourceId != "copy" && association != reviews.identityKey(state.manga)) return@launch
                publishReview(state.manga.id, requestId, source.copy(
                    reviews = (source.reviews + result.items).distinctBy(MangaReview::id),
                    reviewCount = result.total ?: source.reviewCount, nextPage = result.nextPage,
                    loadingMore = false, reviewsError = result.error))
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (sourceId != "copy" && association != reviews.identityKey(state.manga)) return@launch
                publishReview(state.manga.id, requestId, source.copy(loadingMore = false,
                    reviewsError = failure.message ?: "评论加载失败，请重试"))
            } finally {
                finishReviewRequest(state.manga.id, requestId, sourceId, coroutineContext[Job])
            }
        }
    }

    fun retryReviews(sourceId: String) {
        val state = _detail.value ?: return
        val source = state.reviews.firstOrNull { it.id == sourceId } ?: return
        if (source.loadingMore || reviewPageJobs[sourceId]?.isActive == true) return
        if (source.hasWorkReviews && source.reviews.isNotEmpty() && source.nextPage != null && source.reviewsError != null) {
            moreReviews(sourceId)
            return
        }
        refreshReviewSource(sourceId)
    }

    private fun finishReviewRequest(mangaId: String, requestId: Long, sourceId: String, job: Job?) {
        if (job == null || reviewPageJobs[sourceId] !== job) return
        reviewPageJobs.remove(sourceId)
        // Cancellation can clear only its own loading flag, never republish old content.
        _detail.update { current ->
            if (current?.manga?.id != mangaId || requestId != detailRequestId) current
            else current.copy(reviews = current.reviews.map {
                if (it.id == sourceId) it.copy(loadingMore = false) else it
            })
        }
    }

    fun matchReviews(sourceId: String, subjectId: String) = refreshReviewSource(sourceId, subjectId)

    fun relinkReviews(sourceId: String) = refreshReviewSource(sourceId, relink = true)

    private fun refreshReviewSource(sourceId: String, subjectId: String? = null, relink: Boolean = false) {
        val state = _detail.value ?: return
        val source = state.reviews.firstOrNull { it.id == sourceId } ?: return
        if (reviewPageJobs[sourceId]?.isActive == true) return
        val requestId = detailRequestId
        val association = reviews.identityKey(state.manga)
        reviewOverrides.add(sourceId)
        publishReview(state.manga.id, requestId, source.copy(loadingMore = true, reviewsError = null))
        reviewPageJobs[sourceId] = viewModelScope.launch {
            try {
                val result = when {
                    sourceId == "copy" -> community.overview(state.manga, forceRefresh = true)
                    relink -> reviews.relink(state.manga, sourceId)
                    subjectId != null -> reviews.choose(state.manga, sourceId, subjectId)
                    source.hasWorkReviews && source.subjectId.isNotBlank() && source.status == ReviewStatus.READY -> {
                        val page = reviews.loadMore(state.manga, source, 0)
                        val retained = if (page.error == null) page.items
                            else (page.items + source.reviews).distinctBy(MangaReview::id)
                        source.copy(reviews = retained, reviewCount = page.total ?: source.reviewCount, nextPage = page.nextPage,
                            loadingMore = false, reviewsError = page.error)
                    }
                    else -> reviews.lookupSource(state.manga, sourceId)
                }
                if (sourceId != "copy" && subjectId == null && !relink &&
                    association != reviews.identityKey(state.manga)) return@launch
                publishReview(state.manga.id, requestId, result)
                if ((result.status == ReviewStatus.READY || relink) && requestId == detailRequestId &&
                    _detail.value?.manga?.id == state.manga.id) {
                    val recover = linkedSetOf<String>()
                    if (subjectId != null || relink) {
                        // Confirming an association invalidates old repository callbacks.
                        // Resume interrupted/absent providers under the new identity.
                        reviewPageJobs.toMap().forEach { (id, job) ->
                            if (id != sourceId && id != "copy" && job.isActive) {
                                job.cancel()
                                reviewPageJobs.remove(id)
                                recover.add(id)
                            }
                        }
                        ReviewProviders.all.filter { provider ->
                            _detail.value?.reviews.orEmpty().none { it.id == provider.id }
                        }.forEach { provider ->
                            publishReview(state.manga.id, requestId, ReviewSource(provider.id, provider.name,
                                ReviewStatus.NO_MATCH, provider.homeUrl, loadingMore = true,
                                hasWorkReviews = provider.hasWorkReviews))
                            recover.add(provider.id)
                        }
                    }
                    if (sourceId == "mangabaka" && result.status == ReviewStatus.READY) {
                        _detail.value?.reviews.orEmpty().filter {
                            it.id in result.externalIds && it.status != ReviewStatus.READY
                        }.forEach { recover.add(it.id) }
                    }
                    recover.forEach { refreshReviewSource(it) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (sourceId != "copy" && subjectId == null && !relink &&
                    association != reviews.identityKey(state.manga)) return@launch
                publishReview(state.manga.id, requestId, source.copy(loadingMore = false,
                    reviewsError = failure.message ?: "评论加载失败，请重试"))
            } finally {
                finishReviewRequest(state.manga.id, requestId, sourceId, coroutineContext[Job])
            }
        }
    }

    suspend fun reviewReplies(source: ReviewSource, review: MangaReview, page: Int): ReviewPage {
        val manga = _detail.value?.manga ?: return ReviewPage(emptyList())
        return if (source.id == "copy") community.replies(manga, source, review, page)
            else reviews.replies(manga, source, review, page)
    }

    fun retryDetail() { _detail.value?.manga?.let { openManga(it) } }

    fun closeDetail() {
        detailJob?.cancel()
        reviewJob?.cancel()
        reviewPageJobs.values.toList().forEach { it.cancel() }
        reviewPageJobs.clear()
        detailRequestId++
        _detail.value = null
    }

    fun readChapter(chapter: Chapter) {
        val current = _detail.value ?: return
        val chapters = current.detail?.chapters ?: downloadedChapters(current.manga.id)
        if (chapters.none { it.id == chapter.id }) return
        val needsCatalog = current.loading || current.error != null || current.detail == null
        beginReading(ReadingSession(current.manga, chapters, chapter.id, chaptersLoading = needsCatalog))
        if (needsCatalog) loadReadingCatalog()
    }

    fun openDownload(entry: DownloadEntry) {
        openSavedChapter(entry.manga, entry.chapter.id, entry.chapter.title, entry.chapter)
    }

    fun continueReading(progress: ReadingProgress) {
        openSavedChapter(progress.manga, progress.chapterId, progress.chapterTitle)
    }

    fun readFromLibrary(manga: Manga) {
        if (_libraryOpening.value?.let { it.manga.id == manga.id && it.loading } == true) return
        cancelLibraryReading()
        library.progressFor(manga.id)?.takeIf { it.chapterId.isNotBlank() }?.let {
            continueReading(it)
            return
        }
        val requestId = libraryOpenRequestId
        _libraryOpening.value = LibraryOpenState(manga)
        val request = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val memory = repository.cachedDetail(manga.id)?.takeIf { it.chapters.isNotEmpty() }
                val saved = if (memory == null) readerCatalogs.read(manga.id) else null
                if (requestId != libraryOpenRequestId) return@launch
                // Only a full directory establishes the first chapter; a download may start midway.
                val catalog = memory ?: saved ?: repository.detail(manga.id)
                if (requestId != libraryOpenRequestId) return@launch
                val first = catalog.chapters.minByOrNull(Chapter::index)?.takeIf { it.id.isNotBlank() }
                    ?: throw IllegalStateException("暂时没有可读章节，请稍后重试。")
                if (memory == null && saved == null) readerCatalogs.save(catalog)
                if (requestId != libraryOpenRequestId) return@launch
                // Detach before beginReading invalidates other navigation intents; do not cancel this job.
                libraryOpenJob = null
                _libraryOpening.value = null
                closeDetail()
                beginReading(ReadingSession(catalog.manga, catalog.chapters, first.id,
                    chaptersLoading = saved != null))
                if (saved != null) loadReadingCatalog()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (requestId == libraryOpenRequestId) {
                    _libraryOpening.value = LibraryOpenState(manga, loading = false,
                        error = failure.message ?: "暂时无法打开这本漫画，请重试。")
                }
            }
        }
        libraryOpenJob = request
        request.start()
    }

    fun retryLibraryReading() {
        _libraryOpening.value?.takeUnless { it.loading }?.manga?.let(::readFromLibrary)
    }

    fun cancelLibraryReading() {
        libraryOpenRequestId++
        libraryOpenJob?.cancel()
        libraryOpenJob = null
        _libraryOpening.value = null
    }

    private fun beginReading(session: ReadingSession) {
        cancelLibraryReading()
        readingCatalogJob?.cancel()
        chapterPrefetchJob?.cancel()
        chapterPrefetchKey = null
        readingRequestId++
        visibleReading = null
        _reading.value = session
    }

    private fun openSavedChapter(manga: Manga, chapterId: String, chapterTitle: String, fallback: Chapter? = null) {
        val cached = repository.cachedDetail(manga.id)
        val offline = knownDownloadChapters(manga.id)
        val chapter = cached?.chapters?.firstOrNull { it.id == chapterId }
            ?: offline.firstOrNull { it.id == chapterId }
            ?: fallback
            ?: Chapter(chapterId, chapterTitle.ifBlank { "继续阅读" }, 0)
        val chapters = (cached?.chapters.orEmpty() + offline + chapter).distinctBy(Chapter::id)
            .sortedBy(Chapter::index)
        val hasCurrentCatalog = cached?.chapters?.any { it.id == chapterId } == true
        closeDetail()
        // Reader restores the saved page/offset and checks local files itself.
        // Neither download-store initialization nor a directory request blocks
        // this navigation. A fresh catalog is useful only for adjacent chapters.
        beginReading(ReadingSession(cached?.manga ?: manga, chapters, chapterId,
            chaptersLoading = !hasCurrentCatalog))
        if (!hasCurrentCatalog) loadReadingCatalog()
    }

    fun retryReadingChapters() {
        if (_reading.value?.chaptersLoading != false) return
        loadReadingCatalog(forceRefresh = true)
    }

    private fun loadReadingCatalog(forceRefresh: Boolean = false) {
        val session = _reading.value ?: return
        val requestId = readingRequestId
        readingCatalogJob?.cancel()
        _reading.update { it?.copy(chaptersLoading = true, chaptersError = null) }
        readingCatalogJob = viewModelScope.launch {
            try {
                downloads.awaitReady()
                val offline = knownDownloadChapters(session.manga.id)
                val saved = readerCatalogs.read(session.manga.id)
                if (requestId != readingRequestId) return@launch
                _reading.update { current ->
                    if (current?.manga?.id != session.manga.id) current
                    else current.copy(
                        manga = if (current.manga.sourceUuid.isBlank()) current.manga.copy(
                            sourceUuid = saved?.manga?.sourceUuid.orEmpty()) else current.manga,
                        chapters = (saved?.chapters.orEmpty() + offline + current.chapters).distinctBy(Chapter::id)
                            .sortedBy(Chapter::index),
                    )
                }
                val cached = if (forceRefresh) null else repository.cachedDetail(session.manga.id)
                    ?.takeIf { it.chapters.any { chapter -> chapter.id == session.chapterId } }
                // Downloaded chapters are immediately usable, but they are not
                // a complete directory for continuing into the next chapter.
                val loaded = cached ?: repository.detail(session.manga.id)
                readerCatalogs.save(loaded)
                if (requestId != readingRequestId) return@launch
                _reading.update { current ->
                    if (current?.manga?.id != session.manga.id) current
                    else current.copy(
                        manga = loaded.manga,
                        chapters = (loaded.chapters + current.chapters + offline).distinctBy(Chapter::id)
                            .sortedBy(Chapter::index),
                        chaptersLoading = false, chaptersError = null,
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (requestId == readingRequestId) {
                    _reading.update { current ->
                        if (current?.manga?.id != session.manga.id) current
                        else current.copy(chaptersLoading = false,
                            chaptersError = failure.message ?: "目录暂时无法补全，可继续阅读当前章节。")
                    }
                }
            }
        }
    }

    /** Only a visible chapter can request its immediate same-group successor. */
    fun prefetchNextChapter(manga: Manga, chapter: Chapter) {
        val session = _reading.value ?: return
        val visible = visibleReading ?: return
        if (session.manga.id != manga.id || visible.first.id != manga.id) return
        val current = session.chapters.firstOrNull { it.id == visible.second.id } ?: return
        val siblings = session.chapters.filter { it.group == current.group }.sortedBy(Chapter::index)
        val next = siblings.getOrNull(siblings.indexOfFirst { it.id == current.id } + 1) ?: return
        if (next.id != chapter.id) return
        val alreadyDownloaded = downloads.entries.value.any {
            it.manga.id == manga.id && it.chapter.id == chapter.id && it.status == DownloadStatus.COMPLETED
        }
        if (alreadyDownloaded) return
        val key = "${manga.id}/${chapter.id}"
        if (chapterPrefetchKey == key && chapterPrefetchJob?.isActive == true) return
        chapterPrefetchJob?.cancel()
        chapterPrefetchKey = key
        chapterPrefetchJob = viewModelScope.launch {
            try {
                repository.pages(manga.id, chapter.id)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Optional next-chapter work has no current-reader error state.
                // SourceHttp still retains any source-wide rate-limit pause.
            }
        }
    }

    fun retryDownload(entry: DownloadEntry) {
        val expected = downloads.entryFor(entry.manga.id, entry.chapter.id) ?: return
        if (!expected.needsSourceRefresh) {
            downloads.retry(expected.id)
            return
        }
        val key = "${entry.manga.id}/${entry.chapter.id}"
        if (!preparingDownloads.add(key)) return
        _refreshingDownloads.update { it + entry.id }
        viewModelScope.launch {
            try {
                val pages = repository.pages(entry.manga.id, entry.chapter.id, forceRefresh = true)
                val refreshed = downloads.refreshReadingDownload(entry.manga, entry.chapter, pages,
                    repository.imageHeaders(entry.manga.id, entry.chapter.id), expectedEntry = expected)
                if (refreshed) messages.send("图片地址已刷新，继续下载")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                messages.send(failure.message ?: "暂时无法刷新章节图片，请稍后重试")
            } finally {
                preparingDownloads.remove(key)
                _refreshingDownloads.update { it - entry.id }
            }
        }
    }

    fun closeReader() {
        cancelLibraryReading()
        readingRequestId++
        readingCatalogJob?.cancel()
        chapterPrefetchJob?.cancel()
        chapterPrefetchKey = null
        _reading.value = null
        visibleReading = null
        chapterSyncJob?.cancel()
        lastChapterSync = null
    }

    fun loginWithCookie(input: String) = authenticate { accountRepository.loginWithCookie(input) }

    fun login(username: String, password: String) = authenticate { accountRepository.login(username, password) }

    private fun restoreAccount() = authenticate(restoring = true) { accountRepository.restore() }

    private fun authenticate(restoring: Boolean = false, action: suspend () -> AccountProfile?) {
        accountJob?.cancel()
        cancelAccountRequests()
        val requestId = ++accountRequestId
        accountCleanupFailed = false
        cloudFavoritesPage = -1
        cloudHistoryPage = -1
        cloudFavoritesFailedPage = null
        cloudHistoryFailedPage = null
        _account.value = AccountUiState(busy = true)
        accountJob = viewModelScope.launch {
            try {
                val profile = action()
                if (requestId != accountRequestId) return@launch
                _account.value = AccountUiState(profile = profile, busy = false)
                cloudFavoritesPage = -1
                cloudHistoryPage = -1
                if (profile != null) {
                    loadCloudFavorites(refresh = true)
                    loadCloudHistory(refresh = true)
                    visibleReading?.let { (manga, chapter) -> onChapterVisible(manga, chapter) }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (requestId == accountRequestId) {
                    _account.value = AccountUiState(profile = accountRepository.profile.value, busy = false,
                        error = accountError(failure, "登录暂时未完成，请稍后重试。"), canRetryConnection = restoring)
                }
            }
        }
    }

    fun logout() {
        accountRequestId++
        accountJob?.cancel()
        cancelAccountRequests()
        cloudFavoritesPage = -1
        cloudHistoryPage = -1
        cloudFavoritesFailedPage = null
        cloudHistoryFailedPage = null
        try {
            accountRepository.logout()
            accountCleanupFailed = false
            _account.value = AccountUiState(busy = false)
        } catch (failure: Exception) {
            accountCleanupFailed = true
            _account.value = AccountUiState(busy = false,
                error = accountError(failure, "账号已退出，但设备凭据清理未完成，请重试。"), canRetryConnection = true)
        }
    }

    fun refreshAccount() {
        if (_account.value.busy) return
        if (accountCleanupFailed) logout()
        else if (_account.value.profile == null) restoreAccount()
        else {
            _account.update { it.copy(error = null) }
            loadCloudFavorites(refresh = true)
            loadCloudHistory(refresh = true)
        }
    }

    fun moreCloudFavorites() = loadCloudFavorites(refresh = false, retryPage = cloudFavoritesFailedPage)

    private fun loadCloudFavorites(refresh: Boolean, retryPage: Int? = null) {
        val state = _account.value
        if (state.profile == null || state.busy || (!refresh && (state.favoritesLoading ||
            (retryPage == null && !state.favoritesHasMore && cloudFavoritesPage >= 0)))) return
        cloudFavoritesJob?.cancel()
        val version = accountRepository.sessionVersion
        val page = retryPage ?: if (refresh) 0 else cloudFavoritesPage + 1
        cloudFavoritesFailedPage = null
        _account.update { it.copy(favoritesLoading = true, favoritesError = null) }
        cloudFavoritesJob = viewModelScope.launch {
            try {
                val result = accountRepository.favorites(page)
                if (!isCurrentAccount(version)) return@launch
                cloudFavoritesPage = page
                _account.update { it.copy(
                    favorites = (if (page == 0) result.items else it.favorites + result.items).distinctBy(Manga::id),
                    favoritesLoading = false, favoritesHasMore = result.hasMore, favoritesError = null,
                ) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (isCurrentAccount(version)) {
                    cloudFavoritesFailedPage = page
                    _account.update { it.copy(favoritesLoading = false,
                        favoritesError = accountError(failure, "云端收藏暂时无法读取，请重试。")) }
                }
            }
        }
    }

    fun moreCloudHistory() = loadCloudHistory(refresh = false, retryPage = cloudHistoryFailedPage)

    private fun loadCloudHistory(refresh: Boolean, retryPage: Int? = null) {
        val state = _account.value
        if (state.profile == null || state.busy || (!refresh && (state.historyLoading ||
            (retryPage == null && !state.historyHasMore && cloudHistoryPage >= 0)))) return
        cloudHistoryJob?.cancel()
        val version = accountRepository.sessionVersion
        val page = retryPage ?: if (refresh) 0 else cloudHistoryPage + 1
        cloudHistoryFailedPage = null
        _account.update { it.copy(historyLoading = true, historyError = null) }
        cloudHistoryJob = viewModelScope.launch {
            try {
                val result = accountRepository.history(page)
                if (!isCurrentAccount(version)) return@launch
                cloudHistoryPage = page
                _account.update { it.copy(
                    history = (if (page == 0) result.items else it.history + result.items).distinctBy { row -> row.manga.id },
                    historyLoading = false, historyHasMore = result.hasMore, historyError = null,
                ) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (isCurrentAccount(version)) {
                    cloudHistoryFailedPage = page
                    _account.update { it.copy(historyLoading = false,
                        historyError = accountError(failure, "云端记录暂时无法读取，请重试。")) }
                }
            }
        }
    }

    fun continueCloudHistory(entry: CloudHistoryEntry) {
        if (entry.chapterId.isBlank()) openManga(entry.manga)
        else openSavedChapter(entry.manga, entry.chapterId, entry.chapterTitle)
    }

    fun toggleFavorite(manga: Manga) {
        if (manga.id in _account.value.syncingFavorites) return
        library.toggleFavorite(manga)
        if (_account.value.profile != null && !_account.value.busy) {
            setCloudFavorite(manga, library.favorites.value.any { it.id == manga.id })
        }
    }

    fun setCloudFavorite(manga: Manga, favorite: Boolean) {
        val state = _account.value
        if (state.profile == null || state.busy || manga.id in state.syncingFavorites) return
        val version = accountRepository.sessionVersion
        pendingFavoriteSync.remove(manga.id)
        _account.update { it.copy(syncingFavorites = it.syncingFavorites + manga.id,
            syncMessage = "正在同步收藏…", canRetrySync = hasPendingSync()) }
        favoriteSyncJobs[manga.id] = viewModelScope.launch {
            try {
                val uuid = repository.comicUuid(manga.id)
                if (!isCurrentAccount(version)) return@launch
                val confirmed = accountRepository.setFavorite(manga.id, uuid, favorite)
                if (!isCurrentAccount(version)) return@launch
                if (!confirmed) throw SourceException("来源尚未确认收藏同步，请稍后重试。")
                _account.update { it.copy(syncMessage = if (favorite) "已加入云端收藏" else "已从云端收藏移除") }
                loadCloudFavorites(refresh = true)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (isCurrentAccount(version)) {
                    val message = accountError(failure, "云端收藏同步失败，请稍后重试。")
                    pendingFavoriteSync[manga.id] = manga to favorite
                    _account.update { it.copy(syncMessage = "$message 本地书架不受影响。", canRetrySync = true) }
                    messages.send("$message 本地书架不受影响。")
                }
            } finally {
                if (isCurrentAccount(version)) _account.update { it.copy(syncingFavorites = it.syncingFavorites - manga.id) }
                if (accountRepository.sessionVersion == version) favoriteSyncJobs.remove(manga.id)
            }
        }
    }

    fun retryAccountSync() {
        if (_account.value.profile == null || _account.value.busy) return
        pendingFavoriteSync.values.toList().forEach { (manga, favorite) -> setCloudFavorite(manga, favorite) }
        pendingChapterSync?.let { (manga, chapter) ->
            if (chapterSyncJob?.isActive != true) syncChapterProgress(manga, chapter, accountRepository.sessionVersion)
        }
    }

    /** Called after a visible image succeeds; public reading never awaits this side channel. */
    fun onChapterVisible(manga: Manga, chapter: Chapter) {
        if (_reading.value?.manga?.id != manga.id) return
        visibleReading = manga to chapter
        if (_account.value.profile == null || _account.value.busy) return
        val version = accountRepository.sessionVersion
        val key = "$version/${manga.id}/${chapter.id}"
        if (lastChapterSync == key) return
        lastChapterSync = key
        syncChapterProgress(manga, chapter, version)
    }

    private fun syncChapterProgress(manga: Manga, chapter: Chapter, version: Long) {
        chapterSyncJob?.cancel()
        pendingChapterSync = null
        _account.update { it.copy(canRetrySync = hasPendingSync()) }
        chapterSyncJob = viewModelScope.launch {
            try {
                delay(700)
                if (!isCurrentAccount(version)) return@launch
                accountRepository.syncChapter(manga.id, chapter.id)
                if (!isCurrentAccount(version)) return@launch
                _account.update { it.copy(
                    syncMessage = if (pendingFavoriteSync.isEmpty()) "云端记录已同步至 ${chapter.title}；页内位置保存在本机。" else it.syncMessage,
                    canRetrySync = hasPendingSync(),
                ) }
                loadCloudHistory(refresh = true)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (isCurrentAccount(version)) {
                    pendingChapterSync = manga to chapter
                    _account.update { it.copy(syncMessage = accountError(failure, "云端章节记录暂未同步。") +
                        " 本机阅读进度已保留。", canRetrySync = true) }
                }
            }
        }
    }

    private fun isCurrentAccount(version: Long) = accountRepository.sessionVersion == version &&
        _account.value.profile != null && !_account.value.busy

    private fun hasPendingSync() = pendingFavoriteSync.isNotEmpty() || pendingChapterSync != null

    private fun cancelAccountRequests() {
        cloudFavoritesJob?.cancel()
        cloudHistoryJob?.cancel()
        chapterSyncJob?.cancel()
        favoriteSyncJobs.values.toList().forEach { it.cancel() }
        favoriteSyncJobs.clear()
        pendingFavoriteSync.clear()
        pendingChapterSync = null
        lastChapterSync = null
    }

    private fun accountError(failure: Exception, fallback: String) =
        (failure as? SourceException)?.message ?: fallback

    fun downloadChapter(chapter: Chapter) {
        val manga = _detail.value?.manga ?: return
        val key = "${manga.id}/${chapter.id}"
        if (!preparingDownloads.add(key)) return
        viewModelScope.launch {
            try {
                messages.send("正在准备 ${chapter.title}")
                val pages = repository.pages(manga.id, chapter.id)
                downloads.enqueue(manga, chapter, pages, repository.imageHeaders(manga.id, chapter.id))
                messages.send("已加入下载，可在下载页查看进度")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                messages.send(failure.message ?: "未能加入下载，请重试")
            } finally {
                preparingDownloads.remove(key)
            }
        }
    }

    private fun downloadedChapters(mangaId: String): List<Chapter> = downloads.entries.value
        .filter { it.manga.id == mangaId && it.status == DownloadStatus.COMPLETED }
        .map(DownloadEntry::chapter).distinctBy(Chapter::id).sortedBy(Chapter::index)

    private fun knownDownloadChapters(mangaId: String): List<Chapter> = downloads.entries.value
        .filter { it.manga.id == mangaId }
        .map(DownloadEntry::chapter).distinctBy(Chapter::id).sortedBy(Chapter::index)
}
