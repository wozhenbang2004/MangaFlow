package dev.mangaflow

import dev.mangaflow.data.chapterSourceMangaId
import androidx.lifecycle.ViewModel
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
import dev.mangaflow.data.MangaTag
import dev.mangaflow.data.SearchTarget
import dev.mangaflow.data.CopySearchTags
import dev.mangaflow.data.MangaDetail
import dev.mangaflow.data.MangaMetric
import dev.mangaflow.data.MangaMetricsRepository
import dev.mangaflow.data.ReviewRepository
import dev.mangaflow.data.ReviewProviders
import dev.mangaflow.data.ReviewSource
import dev.mangaflow.data.ReviewStatus
import dev.mangaflow.data.ReviewPage
import dev.mangaflow.data.Page
import dev.mangaflow.data.MangaReview
import dev.mangaflow.data.foregroundRequest
import dev.mangaflow.data.SourceException
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.data.UnifiedMangaRepository
import dev.mangaflow.data.resolveChapter
import dev.mangaflow.data.sourceDisplayName
import dev.mangaflow.data.bodySourceSearchTerms
import dev.mangaflow.data.normalizedBodySourceTitle
import dev.mangaflow.data.SourceDiscoveryResult
import dev.mangaflow.data.SourceDiscoveryStatus
import dev.mangaflow.data.FederatedSearchSource
import dev.mangaflow.data.FederatedSearchGroup
import dev.mangaflow.data.FederatedSearchGrouper
import dev.mangaflow.data.sources.BaoziBodySource
import dev.mangaflow.data.sources.KomiicBodySource
import dev.mangaflow.data.sources.MangaDexBodySource
import dev.mangaflow.data.sources.ManhuaguiBodySource
import dev.mangaflow.data.sources.MangaBzBodySource
import dev.mangaflow.storage.UnifiedChapterStore
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.DownloadStore
import dev.mangaflow.storage.DownloadStore.Companion.LOCAL_SOURCE_URL
import dev.mangaflow.storage.LibraryStore
import dev.mangaflow.storage.PageCollection
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.storage.ReaderCatalogStore
import dev.mangaflow.storage.BrowseCache
import dev.mangaflow.storage.BrowseSnapshot
import dev.mangaflow.storage.BodySourceRegistry
import dev.mangaflow.update.UpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import dev.mangaflow.platform.ConcurrentSet
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.applicationContext
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.platform.storage.preferences
import kotlin.concurrent.Volatile

data class BrowseState(
    val query: String = "",
    val searchTarget: SearchTarget = SearchTarget.TITLE,
    val selectedTags: List<MangaTag> = emptyList(),
    val availableTags: List<MangaTag> = CopySearchTags,
    val tagsLoading: Boolean = false,
    val tagsError: String? = null,
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
    val sourceMatches: Map<String, List<Manga>> = emptyMap(),
    val sourceChoiceIds: Set<String> = emptySet(),
    val searchSources: Map<String, FederatedSearchSource> = emptyMap(),
    val sourceNextPages: Map<String, Int> = emptyMap(),
    val sourceScope: String = "",
) {
    val isSearch: Boolean get() = searchTarget != SearchTarget.TITLE || query.isNotBlank() || selectedTags.isNotEmpty()
    val isFederatedSearch: Boolean get() = searchTarget == SearchTarget.TITLE && query.isNotBlank() && selectedTags.isEmpty()
    val cacheKey: String get() = when {
        isSearch -> {
            val keyword = if (searchTarget == SearchTarget.TAG) "" else query.trim()
            "${if (isFederatedSearch) "search-v7-scoped:$sourceScope" else "search-v3-heat"}:${searchTarget.name}:${keyword.length}:$keyword:${selectedTags.map { it.path }.distinct().sorted().joinToString(",")}"
        }
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

data class ChapterSourceOperation(val loading: Boolean = false, val error: String? = null)

data class SourceResumeChoice(val workId: String, val chapter: Chapter, val variant: ChapterVariant,
    val fromPageIndex: Int)

data class SourceManagementState(
    val work: Manga,
    val query: String = work.title,
    val sourceId: String = "komiic",
    val sources: List<Pair<String, String>> = emptyList(),
    val linked: List<Manga> = emptyList(),
    val preferredSource: String = "copy",
    val candidates: List<Manga> = emptyList(),
    val loading: Boolean = false,
    val connecting: Boolean = false,
    val error: String? = null,
    val discovery: List<SourceDiscoveryResult> = emptyList(),
    val scanning: Boolean = false,
    val domainOptions: List<String> = emptyList(),
    val sourceDomain: String = "",
)

data class AuthorWorksState(
    val manga: Manga,
    val name: String,
    val items: List<Manga> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val page: Int = 0,
    val failedPage: Int? = null,
    val error: String? = null,
)

data class ReadingSession(
    val manga: Manga,
    val chapters: List<Chapter>,
    val chapterId: String,
    val chaptersLoading: Boolean = false,
    val chaptersError: String? = null,
    val collection: PageCollection? = null,
    val startAtBeginning: Boolean = false,
    val initialPageIndex: Int? = null,
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

class MangaViewModel(context: PlatformContext) : ViewModel() {
    private val application = applicationContext(context)
    private val copyRepository = CopyMangaRepository(identityProvider = { CopyAnonymousIdentityStore.load(application) })
    private val sourcePreferences = preferences(application, "mangaflow_library")
    private val komiicSource = KomiicBodySource {
        sourcePreferences.getString("body_source_komiic_domain", KomiicBodySource.BASE_URLS.first())
            ?.takeIf { it in KomiicBodySource.BASE_URLS } ?: KomiicBodySource.BASE_URLS.first()
    }
    val unifiedChapters = UnifiedChapterStore(application)
    val repository = UnifiedMangaRepository(copyRepository, unifiedChapters,
        bodySources = listOf(BaoziBodySource(), ManhuaguiBodySource(), MangaBzBodySource(), komiicSource, MangaDexBodySource()),
        catalogStore = ReaderCatalogStore(application))
    val bodySources = BodySourceRegistry(application, repository.sources)
    val library = LibraryStore(application)
    val downloads = DownloadStore.get(application)
    val updates = UpdateManager.get(application)
    val community = CopyCommunityRepository(copyRepository)
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
    private var knownSearchTags = CopySearchTags
    private var tagLookupJob: Job? = null
    private var tagLookupRequest = 0L
    private var pendingTagLookup: Pair<Manga, String>? = null
    private val _detail = MutableStateFlow<DetailState?>(null)
    val detail = _detail.asStateFlow()
    private val _authorWorks = MutableStateFlow<AuthorWorksState?>(null)
    val authorWorks = _authorWorks.asStateFlow()
    private var authorJob: Job? = null
    private var authorRequest = 0L
    private val _reading = MutableStateFlow<ReadingSession?>(null)
    val reading = _reading.asStateFlow()
    private val _chapterSourceOperation = MutableStateFlow(ChapterSourceOperation())
    val chapterSourceOperation = _chapterSourceOperation.asStateFlow()
    private val _sourceResumeChoice = MutableStateFlow<SourceResumeChoice?>(null)
    val sourceResumeChoice = _sourceResumeChoice.asStateFlow()
    private val _sourceResumePolicy = MutableStateFlow(sourcePreferences.getString("source_resume_policy", "ask")
        ?.takeIf { it in setOf("ask", "same_page", "source_record") } ?: "ask")
    val sourceResumePolicy = _sourceResumePolicy.asStateFlow()
    private var sourceResumeEpoch = 0L
    private var sourceResumeFromReader = false
    private val _sourceManagement = MutableStateFlow<SourceManagementState?>(null)
    val sourceManagement = _sourceManagement.asStateFlow()
    private var sourceReadJob: Job? = null
    private var sourceReadRequest = 0L
    private var sourceManagementJob: Job? = null
    private var sourceManagementRequest = 0L
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
    private val reviewOverrides = ConcurrentSet<String>()
    private val preparingDownloads = mutableSetOf<String>()
    private val downloadPreparationSlots = Semaphore(2)
    private var accountJob: Job? = null
    private var accountRequestId = 0L
    private var websiteLoginAttempt: Long? = null
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
    private var projectionRequestId = 0L
    private val projectionSaveLock = Mutex()

    init {
        repository.setEnabledSources(bodySources.enabledSourceIds)
        showBrowse(_browse.value)
        restoreAccount()
        viewModelScope.launch {
            bodySources.entries.collect {
                val enabled = bodySources.enabledSourceIds
                if (enabled != repository.enabledSources()) {
                    withContext(Dispatchers.Default) { repository.setEnabledSources(enabled) }
                    val current = _browse.value
                    if (current.isFederatedSearch) showBrowse(current.copy(
                        items = emptyList(), sourceMatches = emptyMap(), sourceChoiceIds = emptySet(),
                        searchSources = emptyMap(), sourceNextPages = emptyMap(), hasMore = false,
                        loading = true, loadingMore = false, refreshing = false, error = null,
                        failedPage = null, page = 0, updatedAt = 0,
                    ))
                }
            }
        }
        viewModelScope.launch {
            combine(unifiedChapters.revision, repository.catalogRevision) { settings, catalogs -> settings to catalogs }
                .drop(1).collectLatest { refreshUnifiedProjection() }
        }
        viewModelScope.launch { repository.sourceDiscovery.collect { refreshSourceOverview() } }
        viewModelScope.launch { unifiedChapters.persistenceError.collect { error ->
            if (error != null) messages.send(error)
        } }
    }

    fun flushPendingStorage() {
        viewModelScope.launch {
            if (!library.flushPendingWrites()) notifyStorageFailure()
        }
    }

    fun notifyStorageFailure() {
        messages.trySend("本地记录尚未保存，请检查可用存储空间后再关闭应用。")
    }

    fun setQuery(query: String) {
        if (query == _browse.value.query) return
        cancelTagLookup()
        val previous = _browse.value
        if (previous.searchTarget == SearchTarget.TAG) {
            _browse.update { it.copy(query = query) }
            return
        }
        showBrowse(BrowseState(query = query, mode = previous.mode, period = previous.period,
            audience = previous.audience, searchTarget = previous.searchTarget, selectedTags = previous.selectedTags),
            debounce = query.isNotBlank() && previous.searchTarget != SearchTarget.TAG)
    }

    fun setSearchTarget(target: SearchTarget) {
        val previous = _browse.value
        if (previous.searchTarget == target) return
        cancelTagLookup()
        showBrowse(BrowseState(query = previous.query, searchTarget = target, selectedTags = previous.selectedTags,
            mode = previous.mode, period = previous.period, audience = previous.audience))
    }

    fun submitSearch() {
        val previous = _browse.value
        if (previous.searchTarget != SearchTarget.TAG || previous.query.isBlank()) {
            refresh()
            return
        }
        val tag = dev.mangaflow.data.copyTagForName(previous.query, knownSearchTags)
        if (tag == null) {
            _browse.update { it.copy(tagsLoading = false, tagsError = "请选择一个匹配的标签后搜索作品。") }
            return
        }
        cancelTagLookup()
        showBrowse(previous.copy(query = "", selectedTags = (previous.selectedTags + tag).distinctBy(MangaTag::path),
            items = emptyList(), page = 0, error = null, tagsError = null, tagsLoading = false))
    }

    fun toggleSearchTag(tag: MangaTag) {
        if (tag.name.isBlank() || tag.path.isBlank()) return
        cancelTagLookup()
        val previous = _browse.value
        val selected = if (previous.selectedTags.any { it.path == tag.path })
            previous.selectedTags.filterNot { it.path == tag.path } else previous.selectedTags + tag
        knownSearchTags = (knownSearchTags + tag).distinctBy(MangaTag::path)
        showBrowse(BrowseState(query = previous.query, searchTarget = previous.searchTarget, selectedTags = selected,
            mode = previous.mode, period = previous.period, audience = previous.audience))
    }

    fun clearSearch() {
        cancelTagLookup()
        val previous = _browse.value
        showBrowse(BrowseState(mode = previous.mode, period = previous.period, audience = previous.audience))
    }

    fun openTagSearch(manga: Manga, name: String) {
        cancelTagLookup()
        val previous = _browse.value
        if (manga.sourceId == "copy") knownSearchTags =
            (knownSearchTags + manga.tagPaths.map { MangaTag(it.key, it.value) }).distinctBy(MangaTag::path)
        val tag = manga.tagPaths[name]?.takeIf { manga.sourceId == "copy" }?.let { MangaTag(name, it) }
            ?: dev.mangaflow.data.copyTagForName(name, knownSearchTags)
        val selection = BrowseState(query = if (tag == null) name else "",
            searchTarget = SearchTarget.TAG, selectedTags = listOfNotNull(tag),
            tagsLoading = tag == null,
            mode = previous.mode, period = previous.period, audience = previous.audience)
        showBrowse(selection)
        if (tag != null) return
        pendingTagLookup = manga to name
        _browse.update { it.copy(tagsLoading = true) }
        val request = ++tagLookupRequest
        tagLookupJob = viewModelScope.launch {
            try {
                val tags = dev.mangaflow.data.foregroundRequest("标签索引") { repository.searchTagsFor(manga) }
                if (request != tagLookupRequest) return@launch
                knownSearchTags = (knownSearchTags + tags).distinctBy(MangaTag::path)
                val resolved = dev.mangaflow.data.copyTagForName(name, tags)
                    ?: throw SourceException("来源没有返回这个标签的检索索引。")
                pendingTagLookup = null
                showBrowse(selection.copy(query = "", selectedTags = listOf(resolved)))
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: Exception) {
                if (request == tagLookupRequest) _browse.update { it.copy(tagsLoading = false,
                    tagsError = failure.message ?: "标签暂时无法读取，请重试。") }
            }
        }
    }

    fun retrySearchTags() {
        pendingTagLookup?.let { (manga, tag) -> openTagSearch(manga, tag); return }
        _browse.update { it.copy(availableTags = knownSearchTags, tagsLoading = false, tagsError = null) }
    }

    private fun cancelTagLookup() {
        tagLookupRequest++
        tagLookupJob?.cancel()
        pendingTagLookup = null
        _browse.update { it.copy(tagsLoading = false, tagsError = null) }
    }

    fun selectDiscovery(mode: DiscoveryMode, period: RankPeriod = _browse.value.period,
        audience: RankAudience = _browse.value.audience, clearQuery: Boolean = false) {
        val previous = _browse.value
        val query = if (clearQuery) "" else previous.query
        val tags = if (clearQuery) emptyList() else previous.selectedTags
        if (previous.mode == mode && previous.period == period && previous.audience == audience &&
            previous.query == query && previous.selectedTags == tags) return
        if (clearQuery) cancelTagLookup()
        showBrowse(BrowseState(query = query, mode = mode, period = period, audience = audience,
            searchTarget = previous.searchTarget, selectedTags = tags))
    }

    /** The search backdrop survives screen disposal and Activity recreation with this ViewModel. */
    fun browseBackground(selection: BrowseState): BrowseState {
        if (!selection.isSearch) return selection
        val background = BrowseState(mode = selection.mode, period = selection.period, audience = selection.audience)
        return retainedBrowse?.takeIf { it.cacheKey == background.cacheKey }
            ?: browseCache.cached(background.cacheKey)?.restore(background)
            ?: background
    }

    private fun showBrowse(selection: BrowseState, debounce: Boolean = false) {
        val previous = _browse.value
        if (!previous.isSearch) retainedBrowse = previous
        browseJob?.cancel()
        val requestId = ++browseRequestId
        val generation = browseCache.generation
        val configured = selection.copy(availableTags = knownSearchTags,
            sourceScope = repository.enabledSources().sorted().joinToString(","))
        val memory = browseCache.cached(configured.cacheKey)
        _browse.value = memory?.restore(configured) ?: configured
        browseJob = viewModelScope.launch {
            val snapshot = memory ?: browseCache.read(configured.cacheKey, generation)
            if (requestId != browseRequestId) return@launch
            if (snapshot != null) {
                _browse.update { current ->
                    if (requestId != browseRequestId) current else {
                        // TAG text filters the already available directory, not this
                        // result set. Disk hydration must not overwrite newer typing.
                        val selectionWithCurrentFilter = if (configured.searchTarget == SearchTarget.TAG &&
                            current.cacheKey == configured.cacheKey) configured.copy(query = current.query)
                        else configured
                        snapshot.restore(selectionWithCurrentFilter)
                    }
                }
                // Fresh selections need no request. Stale rankings refresh their complete
                // loaded range atomically, so a saved deep position never sees page zero alone.
                if (isFresh(snapshot.updatedAt, configured) ||
                    snapshot.page > 0 && (configured.isSearch ||
                        configured.mode != DiscoveryMode.RANKING)) return@launch
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
        browseJob = viewModelScope.launch { loadBrowse(state, 0, requestId, generation, forceRefresh = true) }
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
        if (state.isFederatedSearch && state.searchSources.values.any { it.error != null }) {
            val pages = state.searchSources.values.filter { it.error != null }
                .associate { it.sourceId to (it.failedPage ?: 0) }
            val requestId = ++browseRequestId
            browseJob?.cancel()
            _browse.update { it.copy(loadingMore = true) }
            browseJob = viewModelScope.launch { loadFederatedBrowse(state, state.page, requestId,
                browseCache.generation, pages, retain = true, forceRefresh = true) }
            return
        }
        val page = state.failedPage ?: return
        _browse.update { it.copy(loading = page == 0 && it.items.isEmpty(),
            refreshing = page == 0 && it.items.isNotEmpty(), loadingMore = page > 0, error = null, failedPage = null) }
        val requestId = ++browseRequestId
        val generation = browseCache.generation
        browseJob = viewModelScope.launch { loadBrowse(state, page, requestId, generation, forceRefresh = true) }
    }

    private suspend fun loadBrowse(state: BrowseState, page: Int, requestId: Long, generation: Long,
        forceRefresh: Boolean = false) {
        if (state.isFederatedSearch) {
            loadFederatedBrowse(state, page, requestId, generation, forceRefresh = forceRefresh)
            return
        }
        try {
            val (result, replacement, loadedPage) = foregroundRequest("漫画列表") {
                val query = state.query.trim()
                var result = if (!state.isSearch) community.browse(state.mode, state.period, state.audience, page)
                    else repository.search(query, state.searchTarget, state.selectedTags, page)
                val replacement = result.items.toMutableList()
                var loadedPage = result.nextPage?.minus(1) ?: page
                if (!state.isSearch && state.mode == DiscoveryMode.RANKING && page == 0 && state.items.isNotEmpty()) {
                    // Never combine a new first page with old ranking pages. Keep the old list
                    // visible until the previously loaded range (or the new source end) is ready.
                    while (loadedPage < state.page && result.hasMore) {
                        if (requestId != browseRequestId || generation != browseCache.generation) throw CancellationException("Superseded browse")
                        result = community.browse(state.mode, state.period, state.audience, ++loadedPage)
                        replacement += result.items
                    }
                }
                Triple(result, replacement, loadedPage)
            }
            if (requestId != browseRequestId || generation != browseCache.generation) return
            _browse.update { current ->
                if (requestId != browseRequestId) current else current.copy(
                    items = (if (page == 0) replacement else current.items + replacement).distinctBy(Manga::id)
                        .let { items -> if (state.isSearch) items.sortedByDescending {
                            it.totalPopularity ?: it.popularity ?: -1L
                        } else items },
                    loading = false, refreshing = false, loadingMore = false, error = null, failedPage = null,
                    hasMore = result.hasMore, page = loadedPage,
                    updatedAt = if (page == 0 || current.updatedAt <= 0) wallTimeMillis() else current.updatedAt,
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

    private fun BrowseSnapshot.restore(selection: BrowseState): BrowseState {
        val incomplete = selection.isFederatedSearch && items.isEmpty() && searchSources.values.any { it.error != null }
        return selection.copy(
            items = items, page = page, hasMore = hasMore, updatedAt = updatedAt,
            loading = false, loadingMore = false, refreshing = false,
            error = if (incomplete) "部分来源暂时不可用，可重试；其他来源没有返回匹配作品。" else null,
            failedPage = page.takeIf { incomplete },
            sourceMatches = sourceMatches, sourceNextPages = sourceNextPages, sourceChoiceIds = sourceChoiceIds,
            searchSources = searchSources,
        )
    }

    private suspend fun loadFederatedBrowse(state: BrowseState, page: Int, requestId: Long, generation: Long,
        requestedPages: Map<String, Int>? = if (page == 0) null else state.sourceNextPages,
        retain: Boolean = page > 0, forceRefresh: Boolean = false) {
        val previousHits = if (retain) state.sourceMatches.values.flatten().ifEmpty { state.items } else emptyList()
        val grouper = withContext(Dispatchers.Default) { FederatedSearchGrouper(state.query) }
        var groupedHits: List<Manga>? = null
        var groups: List<FederatedSearchGroup> = emptyList()
        try {
            repository.searchAll(state.query.trim(), requestedPages, forceRefresh = forceRefresh) { partial ->
                if (requestId != browseRequestId || generation != browseCache.generation) return@searchAll
                val hits = (previousHits + partial.hits).distinctBy(Manga::id)
                // Source callbacks are serialized by the repository. Keep the mutable derivation
                // cache outside StateFlow.update, whose CAS lambda may be evaluated again.
                if (hits != groupedHits) {
                    groups = grouper.group(hits, _browse.value.sourceMatches)
                    groupedHits = hits
                }
                val groupedItems = groups.map { it.manga }
                val groupedMatches = groups.associate { it.manga.id to it.matches }
                val choiceIds = groups.filter { it.needsChoice }.map { it.manga.id }.toSet()
                val noHits = groupedItems.isEmpty()
                val statuses = (if (retain) state.searchSources else emptyMap()) + partial.sources
                val pending = statuses.values.any { it.loading }
                val next = statuses.values.filter { it.error == null && it.hasMore && it.nextPage != null }
                    .associate { it.sourceId to it.nextPage!! }
                val failedEmpty = !pending && noHits && statuses.values.any { it.error != null }
                val keepExisting = pending && noHits
                val updatedAt = if (!pending) wallTimeMillis() else null
                _browse.update { current ->
                    if (current.cacheKey != state.cacheKey || requestId != browseRequestId ||
                        generation != browseCache.generation) current else {
                        current.copy(items = if (keepExisting) current.items else groupedItems,
                            sourceMatches = if (keepExisting) current.sourceMatches else groupedMatches,
                            sourceChoiceIds = if (keepExisting) current.sourceChoiceIds else choiceIds,
                            searchSources = statuses, sourceNextPages = next, hasMore = next.isNotEmpty(),
                            loading = pending && noHits && current.items.isEmpty(),
                            refreshing = pending && page == 0 && current.items.isNotEmpty(),
                            loadingMore = pending && retain, page = page,
                            error = if (failedEmpty)
                                "部分来源暂时不可用，可重试；其他来源没有返回匹配作品。" else null,
                            failedPage = if (failedEmpty) page else null,
                            updatedAt = updatedAt ?: current.updatedAt)
                    }
                }
            }
            if (requestId != browseRequestId || generation != browseCache.generation) return
            val current = _browse.value
            val snapshot = BrowseSnapshot(current.cacheKey, current.items, current.page, current.hasMore, current.updatedAt,
                sourceMatches = current.sourceMatches, sourceNextPages = current.sourceNextPages,
                sourceChoiceIds = current.sourceChoiceIds, searchSources = current.searchSources)
            browseCache.remember(snapshot, generation)
            browseCache.write(snapshot, generation)
        } catch (cancel: CancellationException) { throw cancel
        } catch (failure: Exception) {
            _browse.update { if (requestId != browseRequestId) it else it.copy(loading = false,
                refreshing = false, loadingMore = false, error = failure.message ?: "来源搜索失败，请重试。", failedPage = page) }
        }
    }

    private fun isFresh(updatedAt: Long, state: BrowseState): Boolean {
        val ttl = when {
            state.isSearch || state.mode != DiscoveryMode.RANKING -> 15 * 60_000L
            state.period == RankPeriod.DAY || state.period == RankPeriod.WEEK -> 30 * 60_000L
            else -> 60 * 60_000L
        }
        return wallTimeMillis() - updatedAt in 0 until ttl
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
            if (current[mangaId] == values) return@update current
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

    fun openAuthor(manga: Manga, name: String) {
        closeAuthor()
        if (name !in manga.authors) return
        _authorWorks.value = AuthorWorksState(manga, name)
        loadAuthor(0)
    }

    fun closeAuthor() { authorRequest++; authorJob?.cancel(); _authorWorks.value = null }
    fun moreAuthor() {
        val current = _authorWorks.value ?: return
        if (current.hasMore && !current.loading && !current.loadingMore && current.error == null) loadAuthor(current.page + 1)
    }
    fun retryAuthor() { loadAuthor(_authorWorks.value?.failedPage ?: 0) }
    private fun loadAuthor(page: Int) {
        val current = _authorWorks.value ?: return
        authorJob?.cancel()
        val request = ++authorRequest
        _authorWorks.value = current.copy(loading = page == 0, loadingMore = page > 0, error = null, failedPage = null)
        authorJob = viewModelScope.launch {
            try {
                val result = foregroundRequest("作者作品") { repository.byAuthor(current.manga, current.name, page) }
                if (request != authorRequest) return@launch
                _authorWorks.value = current.copy(items = (if (page > 0) current.items + result.items else result.items).distinctBy { it.id },
                    loading = false, loadingMore = false, hasMore = result.hasMore, page = page, error = null, failedPage = null)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) {
                if (request == authorRequest) _authorWorks.value = current.copy(loading = false, loadingMore = false,
                    failedPage = page, error = error.message ?: "作者作品暂时无法读取，请重试。")
            }
        }
    }

    fun openManga(manga: Manga, continueChapterId: String? = null, forceRefresh: Boolean = false) {
        cancelSourceResumeChoice()
        closeAuthor()
        if (manga.sourceUrl == LOCAL_SOURCE_URL) {
            val chapters = downloadedChapters(manga.id)
            val chapter = continueChapterId?.let { id -> chapters.firstOrNull { it.id == id } }
                ?: chapters.firstOrNull()
            if (chapter != null) openSavedChapter(manga, chapter.id, chapter.title, chapter)
            else viewModelScope.launch { messages.send("本地章节不存在，请重新导入。") }
            return
        }
        cancelLibraryReading()
        detailJob?.cancel()
        reviewJob?.cancel()
        reviewPageJobs.values.toList().forEach { it.cancel() }
        reviewPageJobs.clear()
        reviewOverrides.clear()
        val requestId = ++detailRequestId
        val previous = _detail.value?.takeIf { it.manga.id == manga.id }
        val cached = repository.cachedDetail(manga.id)
        val retained = previous?.detail?.takeIf { it.chapters.isNotEmpty() }
            ?: cached
        val initialManga = (retained?.manga ?: previous?.manga ?: manga).let {
            val totalHeat = it.totalHeat() ?: manga.totalHeat()
            it.copy(popularity = totalHeat, totalPopularity = totalHeat, rank = null)
        }
        val initialChapters = retainedChapters(retained?.chapters.orEmpty(), downloadedChapters(manga.id))
        if (cached == null) repository.retainWork(MangaDetail(initialManga, initialChapters))
        val reuseCatalog = cached != null && cached.chapters.isNotEmpty() && !forceRefresh &&
            (manga.latestChapter.isBlank() || manga.latestChapter == cached.manga.latestChapter) &&
            (continueChapterId == null || initialChapters.any { it.id == continueChapterId })
        _detail.value = DetailState(initialManga,
            detail = initialChapters.takeIf { it.isNotEmpty() }?.let { MangaDetail(initialManga, it) },
            loading = !reuseCatalog)
        repository.discoverSources(initialManga, sourceAliases(initialManga))
        loadReviews(initialManga, requestId)
        if (reuseCatalog) {
            if (continueChapterId != null) {
                val chapter = findSourceChapter(initialChapters, continueChapterId)
                if (chapter != null) readChapter(chapter)
            }
            return
        }
        detailJob = viewModelScope.launch {
            try {
                if (retained == null) {
                    // A cold open can show the last complete catalog while the source refreshes.
                    val stored = readerCatalogs.read(manga.id)
                    if (requestId != detailRequestId) return@launch
                    if (stored != null) repository.retainWork(stored)
                    if (stored != null) _detail.update { current ->
                        if (current?.manga?.id != manga.id || requestId != detailRequestId) current
                        else current.copy(detail = MangaDetail(current.manga,
                            retainedChapters(stored.chapters, downloadedChapters(manga.id))))
                    }
                }
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
                repository.discoverSources(loaded.manga, sourceAliases(loaded.manga))
                recordDetailMetrics(manga.id, requestId)
                if (continueChapterId != null) {
                    val chapter = findSourceChapter(loaded.chapters, continueChapterId)
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
                        val chapters = retainedChapters(current.detail?.chapters.orEmpty(), offline)
                        current.copy(loading = false, error = failure.message ?: "暂时无法读取线上目录",
                            detail = chapters.takeIf { it.isNotEmpty() }?.let { MangaDetail(current.manga, it) })
                    }
                }
                if (requestId == detailRequestId && continueChapterId != null) {
                    _detail.value?.detail?.chapters?.let { findSourceChapter(it, continueChapterId) }?.let(::readChapter)
                }
            }
        }
    }

    private fun loadReviews(manga: Manga, requestId: Long) {
        reviewJob = viewModelScope.launch {
            try {
                coroutineScope {
                    launch {
                        val copyWork = if (manga.sourceId == "copy") manga else
                            unifiedChapters.linkedSources(manga.id).firstOrNull { it.manga.sourceId == "copy" }?.manga
                        if (copyWork == null) return@launch
                        val source = try { community.overview(copyWork) }
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
                _detail.value?.takeIf { it.manga.id == manga.id && requestId == detailRequestId &&
                    (_reading.value == null || _reading.value?.manga?.id == manga.id) }?.let {
                    repository.discoverSources(it.manga, sourceAliases(it.manga))
                }
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

    private fun findSourceChapter(chapters: List<Chapter>, id: String): Chapter? =
        chapters.firstOrNull { it.id == id } ?: chapters.firstNotNullOfOrNull { chapter ->
            chapter.variants.firstOrNull { it.storageId == id }?.let { resolveChapter(chapter, it) }
        }

    private fun retainedChapters(primary: List<Chapter>, extra: List<Chapter>): List<Chapter> =
        (primary + extra.filter { findSourceChapter(primary, it.id) == null })
            .distinctBy { it.logicalId.ifBlank { it.id } }.sortedBy(Chapter::index)

    private fun selectedChapters(chapters: List<Chapter>, selected: Chapter): List<Chapter> {
        val logical = selected.logicalId.ifBlank { selected.id }
        val group = chapters.indexOfFirst { it.logicalId.ifBlank { it.id } == logical ||
            it.variants.any { variant -> variant.storageId == selected.id } }
        return if (group >= 0) chapters.mapIndexed { index, chapter ->
            if (index == group) selected.copy(logicalId = chapter.logicalId.ifBlank { chapter.id }, variants = chapter.variants, index = chapter.index) else chapter
        } else (chapters + selected).distinctBy(Chapter::id).sortedBy(Chapter::index)
    }

    /** Reproject the view; choosing a source never writes a reading event or copies a page cursor. */
    private suspend fun refreshUnifiedProjection() {
        val request = ++projectionRequestId
        val detailEpoch = detailRequestId
        val readingEpoch = readingRequestId
        val detailWorkId = _detail.value?.manga?.id
        val readingWorkId = _reading.value?.manga?.id
        val settingsRevision = unifiedChapters.revision.value
        val catalogRevision = repository.catalogRevision.value
        val workIds = listOfNotNull(detailWorkId, readingWorkId).distinct()
        val catalogs = withContext(Dispatchers.Default) {
            workIds.associateWith { repository.cachedDetail(it) }
        }
        fun currentProjection(): Boolean = request == projectionRequestId &&
            settingsRevision == unifiedChapters.revision.value && catalogRevision == repository.catalogRevision.value
        if (!currentProjection()) return
        var changedCatalog: MangaDetail? = null
        val currentDetail = _detail.value
        if (detailWorkId != null && detailRequestId == detailEpoch &&
            currentDetail != null && currentDetail.manga.id == detailWorkId) {
            catalogs[detailWorkId]?.let { catalog ->
                if (currentDetail.detail != catalog) {
                    _detail.value = currentDetail.copy(detail = catalog)
                    changedCatalog = catalog
                }
            }
        }
        refreshSourceOverview()
        val session = _reading.value
        if (readingWorkId != null && readingRequestId == readingEpoch &&
            session != null && session.manga.id == readingWorkId) {
            catalogs[readingWorkId]?.let { catalog ->
                // Read the visible source now: the reader can turn pages/chapters while
                // a background projection is being built, without a new reading epoch.
                val visible = visibleReading?.takeIf { it.first.id == session.manga.id }?.second
                    ?: findSourceChapter(session.chapters, session.chapterId)
                val projected = if (visible != null) selectedChapters(catalog.chapters, visible) else catalog.chapters
                if (session.chapters != projected) _reading.value = session.copy(chapters = projected)
            }
        }
        changedCatalog?.let { catalog ->
            projectionSaveLock.withLock {
                if (currentProjection() && detailRequestId == detailEpoch &&
                    _detail.value?.manga?.id == catalog.manga.id && _detail.value?.detail == catalog)
                    readerCatalogs.save(catalog)
            }
        }
    }

    fun manageSources() {
        val work = _detail.value?.manga ?: _reading.value?.manga ?: return
        if (work.sourceUrl == LOCAL_SOURCE_URL) return
        closeSourceManagement()
        val available = repository.sources.filter { it.id != work.sourceId }
        val first = available.firstOrNull()?.id.orEmpty()
        _sourceManagement.value = SourceManagementState(
            work = work,
            sourceId = first,
            sources = available.map { it.id to it.name },
            linked = unifiedChapters.linkedSources(work.id).map { it.manga },
            preferredSource = unifiedChapters.settings(work.id).preferredSources.firstOrNull() ?: work.sourceId,
            domainOptions = sourceDomainOptions(first), sourceDomain = sourceDomain(first),
        )
        refreshSourceOverview()
    }

    private fun sourceAliases(work: Manga): List<String> =
        (reviews.cachedSources(work) + _detail.value?.takeIf { it.manga.id == work.id }?.reviews.orEmpty())
            .filter { it.status == ReviewStatus.READY }
            .flatMap { it.aliases + it.matchedTitle }.filter(String::isNotBlank).distinct()

    private fun sourceDomainOptions(sourceId: String): List<String> =
        if (sourceId == "komiic") KomiicBodySource.BASE_URLS else emptyList()

    private fun sourceDomain(sourceId: String): String =
        if (sourceId == "komiic") komiicSource.info.homeUrl else ""

    private fun refreshSourceOverview() {
        val state = _sourceManagement.value ?: return
        val work = state.work
        val settings = unifiedChapters.settings(work.id)
        val discovered = repository.sourceDiscovery.value[work.id].orEmpty()
        val chapters = _detail.value?.takeIf { it.manga.id == work.id }?.detail?.chapters
            ?: _reading.value?.takeIf { it.manga.id == work.id }?.chapters.orEmpty()
        val results = repository.sources.map { source ->
            val linked = source.id == work.sourceId || settings.links.any { it.manga.sourceId == source.id }
            val existingChapters = chapters.count { chapter -> chapter.variants.any { it.sourceId == source.id } ||
                chapter.variants.isEmpty() && chapter.sourceId == source.id }
            val discovery = discovered[source.id]
            val linkedFailure = settings.links.firstOrNull { it.manga.sourceId == source.id }?.let {
                repository.sourceErrors.value[work.id]?.get(it.manga.id)
            }
            val primaryFailure = _detail.value?.takeIf { source.id == work.sourceId && it.manga.id == work.id }?.error
            when {
                source.id in settings.excludedSources -> SourceDiscoveryResult(source.id, source.name,
                    SourceDiscoveryStatus.DISABLED, message = "已取消关联，可手动重新添加")
                primaryFailure != null -> SourceDiscoveryResult(source.id, source.name, SourceDiscoveryStatus.FAILED,
                    chapters = existingChapters, message = primaryFailure)
                linkedFailure != null -> SourceDiscoveryResult(source.id, source.name, SourceDiscoveryStatus.FAILED,
                    chapters = existingChapters, message = "$linkedFailure 已保留上次目录。")
                discovery?.status in listOf(SourceDiscoveryStatus.SEARCHING, SourceDiscoveryStatus.FAILED) ||
                    !linked && discovery?.status == SourceDiscoveryStatus.NEEDS_CONFIRMATION -> discovery!!.copy(chapters = existingChapters)
                linked -> SourceDiscoveryResult(source.id, source.name, SourceDiscoveryStatus.LINKED, chapters = existingChapters)
                else -> discovered[source.id] ?: SourceDiscoveryResult(source.id, source.name,
                    SourceDiscoveryStatus.NO_MATCH, message = "尚未检查")
            }
        }
        _sourceManagement.update { if (it?.work?.id == work.id) it.copy(
            linked = settings.links.map { link -> link.manga },
            preferredSource = settings.preferredSources.firstOrNull() ?: work.sourceId,
            discovery = results, scanning = discovered.values.any { result -> result.status == SourceDiscoveryStatus.SEARCHING },
            domainOptions = sourceDomainOptions(it.sourceId), sourceDomain = sourceDomain(it.sourceId)) else it }
    }

    fun retrySourceDiscovery() {
        val work = _sourceManagement.value?.work ?: _detail.value?.manga ?: return
        if (_detail.value?.takeIf { it.manga.id == work.id }?.error != null) retryDetail()
        repository.discoverSources(work, sourceAliases(work), force = true)
        viewModelScope.launch {
            try { repository.refreshLinked(work.id); refreshUnifiedProjection() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { messages.send(failure.message ?: "来源刷新失败，已保留上次目录。") }
        }
    }

    fun chooseBodySource(sourceId: String) {
        val state = _sourceManagement.value ?: return
        if (state.connecting || state.sources.none { it.first == sourceId }) return
        sourceManagementRequest++
        sourceManagementJob?.cancel()
        val candidates = repository.sourceDiscovery.value[state.work.id]?.get(sourceId)?.candidates.orEmpty()
        _sourceManagement.value = state.copy(sourceId = sourceId, candidates = candidates,
            loading = false, error = null, domainOptions = sourceDomainOptions(sourceId), sourceDomain = sourceDomain(sourceId))
    }

    fun chooseSourceDomain(domain: String) {
        val state = _sourceManagement.value ?: return
        if (state.connecting || state.sourceId != "komiic" || domain !in KomiicBodySource.BASE_URLS) return
        val request = sourceManagementRequest
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(sourcePreferences.edit().putString("body_source_komiic_domain", domain).commit()) {
                        "来源线路未能保存，请检查存储空间。"
                    }
                }
                repository.invalidatePageCache("komiic")
                if (request != sourceManagementRequest || _sourceManagement.value?.work?.id != state.work.id ||
                    _sourceManagement.value?.sourceId != "komiic") return@launch
                refreshSourceOverview()
                searchBodySource("komiic", state.query)
            } catch (cancel: CancellationException) { throw cancel
            } catch (failure: Exception) { messages.send(failure.message ?: "线路切换失败，请重试。") }
        }
    }

    fun closeSourceManagement() {
        sourceManagementRequest++
        sourceManagementJob?.cancel()
        sourceManagementJob = null
        _sourceManagement.value = null
    }

    fun searchBodySource(sourceId: String, query: String) {
        val state = _sourceManagement.value ?: return
        if (state.connecting || state.sources.none { it.first == sourceId }) return
        sourceManagementJob?.cancel()
        val request = ++sourceManagementRequest
        _sourceManagement.value = state.copy(sourceId = sourceId, query = query, loading = true,
            candidates = emptyList(), error = null, domainOptions = sourceDomainOptions(sourceId),
            sourceDomain = sourceDomain(sourceId))
        if (query.isBlank()) {
            _sourceManagement.update { it?.copy(loading = false, error = "请输入作品名或别名。") }
            return
        }
        sourceManagementJob = viewModelScope.launch {
            try {
                val aliases = if (normalizedBodySourceTitle(query) == normalizedBodySourceTitle(state.work.title))
                    sourceAliases(state.work) else emptyList()
                val result = foregroundRequest("来源搜索") { withContext(Dispatchers.IO) {
                    var found = dev.mangaflow.data.MangaPage(emptyList(), false)
                    for (term in bodySourceSearchTerms(query, aliases)) {
                        found = repository.searchSource(sourceId, term)
                        if (found.items.isNotEmpty()) break
                    }
                    found
                } }
                if (request == sourceManagementRequest) _sourceManagement.update { it?.copy(
                    candidates = result.items, loading = false,
                    error = if (result.items.isEmpty()) "已查询书名及可用别名，未找到匹配作品。" else null) }
            } catch (cancel: CancellationException) { throw cancel
            } catch (failure: Exception) {
                if (request == sourceManagementRequest) _sourceManagement.update { it?.copy(
                    loading = false, error = failure.message ?: "来源搜索失败，请重试。") }
            }
        }
    }

    fun attachBodySource(manga: Manga, sameChapterSystem: Boolean, edition: String) {
        val state = _sourceManagement.value ?: return
        if (state.connecting) return
        sourceManagementJob?.cancel()
        val request = ++sourceManagementRequest
        _sourceManagement.value = state.copy(connecting = true, loading = false, error = null)
        sourceManagementJob = viewModelScope.launch {
            try {
                val catalog = foregroundRequest("来源目录") { repository.detailSource(manga.sourceId, manga.id) }
                if (request != sourceManagementRequest) return@launch
                require(catalog.chapters.isNotEmpty()) { "这个版本暂时没有可读章节。" }
                withContext(Dispatchers.IO) {
                    unifiedChapters.attach(state.work.id, catalog.manga,
                        language = repository.sources.firstOrNull { it.id == manga.sourceId }?.language.orEmpty(),
                        edition = edition, sameChapterSystem = sameChapterSystem)
                }
                if (request != sourceManagementRequest) return@launch
                refreshUnifiedProjection()
                _sourceManagement.update { it?.copy(connecting = false,
                    linked = unifiedChapters.linkedSources(state.work.id).map { link -> link.manga }) }
                messages.send("已关联正文版本；原阅读记录与下载保留。")
            } catch (cancel: CancellationException) { throw cancel
            } catch (failure: Exception) {
                if (request == sourceManagementRequest) _sourceManagement.update { it?.copy(
                    connecting = false, error = failure.message ?: "关联失败，请重试。") }
            }
        }
    }

    fun detachBodySource(manga: Manga) {
        val state = _sourceManagement.value ?: return
        changeUnified {
            unifiedChapters.detach(state.work.id, manga.id)
            _sourceManagement.update { it?.copy(linked = unifiedChapters.linkedSources(state.work.id).map { link -> link.manga }) }
        }
    }

    private fun changeUnified(action: () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                refreshUnifiedProjection()
            } catch (cancel: CancellationException) { throw cancel
            } catch (failure: Exception) { messages.send(failure.message ?: "来源设置未能保存，请重试。") }
        }
    }

    fun preferBodySource(sourceId: String) {
        val workId = _sourceManagement.value?.work?.id ?: _reading.value?.manga?.id ?: _detail.value?.manga?.id ?: return
        changeUnified {
            unifiedChapters.preferSource(workId, sourceId)
            _sourceManagement.update { it?.copy(preferredSource = sourceId) }
        }
    }

    fun clearChapterSource(chapter: Chapter) {
        val workId = _reading.value?.manga?.id ?: _detail.value?.manga?.id ?: return
        changeUnified { unifiedChapters.clearChoice(workId, chapter.logicalId.ifBlank { chapter.id }) }
    }

    fun splitChapterSource(chapter: Chapter, variant: ChapterVariant) {
        val workId = _detail.value?.manga?.id ?: return
        changeUnified { unifiedChapters.split(workId, variant.storageId) }
    }

    fun relinkChapterSource(chapter: Chapter, variant: ChapterVariant, target: Chapter) {
        val workId = _detail.value?.manga?.id ?: return
        changeUnified { unifiedChapters.associate(workId, variant.storageId, target.logicalId.ifBlank { target.id }) }
    }

    fun cancelSourceSelection() {
        sourceReadRequest++
        sourceReadJob?.cancel()
        sourceReadJob = null
        _chapterSourceOperation.value = ChapterSourceOperation()
    }

    fun readChapterVariant(chapter: Chapter, variant: ChapterVariant) {
        val workId = _detail.value?.manga?.id ?: _reading.value?.manga?.id ?: return
        requestSourceVariant(chapter, variant, library.progressFor(workId, chapter.id)?.pageIndex)
    }

    fun readReaderChapterVariant(chapter: Chapter, variant: ChapterVariant, pageIndex: Int) {
        requestSourceVariant(chapter, variant, pageIndex.coerceAtLeast(0))
    }

    private fun requestSourceVariant(chapter: Chapter, variant: ChapterVariant, fromPageIndex: Int?) {
        if (chapter.variants.none { it.storageId == variant.storageId }) return
        val work = _reading.value?.manga ?: _detail.value?.manga ?: return
        _sourceResumeChoice.value = null
        cancelSourceSelection()
        val page = fromPageIndex?.takeIf { it > 0 && chapter.id != variant.storageId }
        if (page != null && _sourceResumePolicy.value == "ask") {
            sourceResumeFromReader = _reading.value != null
            sourceResumeEpoch = if (sourceResumeFromReader) readingRequestId else detailRequestId
            _sourceResumeChoice.value = SourceResumeChoice(work.id, chapter, variant, page)
        } else prepareReadChapterVariant(chapter, variant,
            page?.takeIf { _sourceResumePolicy.value == "same_page" })
    }

    fun cancelSourceResumeChoice() {
        _sourceResumeChoice.value = null
        cancelSourceSelection()
    }

    fun setSourceResumePolicy(policy: String) {
        if (policy !in setOf("ask", "same_page", "source_record")) return
        viewModelScope.launch {
            try { saveSourceResumePolicy(policy) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { messages.send(failure.message ?: "续读选择未能保存，请重试。") }
        }
    }

    private suspend fun saveSourceResumePolicy(policy: String) = withContext(Dispatchers.IO) {
        check(sourcePreferences.edit().putString("source_resume_policy", policy).commit()) { "续读选择未能保存，请检查存储空间。" }
        _sourceResumePolicy.value = policy
    }

    fun confirmSourceResumeChoice(samePage: Boolean, remember: Boolean) {
        val choice = _sourceResumeChoice.value ?: return
        val epoch = sourceResumeEpoch
        val reader = sourceResumeFromReader
        _sourceResumeChoice.value = null
        viewModelScope.launch {
            if (remember) try { saveSourceResumePolicy(if (samePage) "same_page" else "source_record") }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { messages.send(failure.message ?: "这次继续阅读，未能记住选择。") }
            val valid = if (reader) readingRequestId == epoch && _reading.value?.manga?.id == choice.workId
                else detailRequestId == epoch && _detail.value?.manga?.id == choice.workId && _reading.value == null
            if (valid) prepareReadChapterVariant(choice.chapter, choice.variant,
                choice.fromPageIndex.takeIf { samePage })
        }
    }

    private fun prepareReadChapterVariant(chapter: Chapter, variant: ChapterVariant, samePageIndex: Int?) {
        if (chapter.variants.none { it.storageId == variant.storageId }) return
        val session = _reading.value
        val detail = _detail.value
        val work = session?.manga ?: detail?.manga ?: return
        val catalog = session?.chapters ?: detail?.detail?.chapters ?: return
        val readingEpoch = readingRequestId
        val detailEpoch = detailRequestId
        cancelSourceSelection()
        val request = sourceReadRequest
        val selected = resolveChapter(chapter, variant)
        _chapterSourceOperation.value = ChapterSourceOperation(loading = true)
        sourceReadJob = viewModelScope.launch {
            try {
                val pages = downloads.storedPages(work.id, selected.id) ?: foregroundRequest("准备本章来源") {
                    repository.pages(chapterSourceMangaId(work.id, selected), selected.id)
                }
                require(pages.isNotEmpty()) { "这个来源暂时没有可读图片。" }
                if (request != sourceReadRequest) return@launch
                if (session != null && (readingEpoch != readingRequestId || _reading.value?.manga?.id != work.id)) return@launch
                if (session == null && (detailEpoch != detailRequestId || _detail.value?.manga?.id != work.id)) return@launch
                withContext(Dispatchers.IO) {
                    unifiedChapters.choose(work.id, selected.logicalId.ifBlank { selected.id }, selected.id)
                }
                if (request != sourceReadRequest) return@launch
                _chapterSourceOperation.value = ChapterSourceOperation()
                val initialPage = samePageIndex?.coerceIn(0, pages.lastIndex)
                beginReading(ReadingSession(work, selectedChapters(catalog, selected), selected.id,
                    initialPageIndex = initialPage))
                messages.send(if (initialPage != null) "已切换到 ${variant.sourceName}，尝试第${initialPage + 1}页，内容可能不对应。"
                else if (library.progressFor(work.id, selected.id) != null)
                    "已切换到 ${variant.sourceName}，恢复该版本的位置。"
                else "已切换到 ${variant.sourceName}，从本话开头阅读。")
            } catch (cancel: CancellationException) { throw cancel
            } catch (failure: Exception) {
                if (request == sourceReadRequest) {
                    val error = failure.message ?: "本章来源未能打开，请重试。"
                    _chapterSourceOperation.value = ChapterSourceOperation(error = error)
                    messages.send(error)
                }
            }
        }
    }

    fun retryDetail() {
        val work = _detail.value?.manga ?: return
        openManga(work, forceRefresh = true)
        viewModelScope.launch {
            try { repository.refreshLinked(work.id); refreshUnifiedProjection() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { messages.send(failure.message ?: "正文来源刷新失败，可继续阅读已保存章节。") }
        }
    }

    fun closeDetail() {
        cancelSourceResumeChoice()
        cancelSourceSelection()
        closeSourceManagement()
        closeAuthor()
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
        if (findSourceChapter(chapters, chapter.id) == null) return
        val needsCatalog = current.loading || current.error != null || current.detail == null
        beginReading(ReadingSession(current.manga, selectedChapters(chapters, chapter), chapter.id, chaptersLoading = needsCatalog))
        if (needsCatalog) loadReadingCatalog()
    }

    fun restartChapter(chapter: Chapter) {
        val current = _detail.value ?: return
        val chapters = current.detail?.chapters ?: downloadedChapters(current.manga.id)
        if (chapters.none { it.id == chapter.id }) return
        val needsCatalog = current.loading || current.error != null || current.detail == null
        beginReading(ReadingSession(current.manga, chapters, chapter.id,
            chaptersLoading = needsCatalog, startAtBeginning = true))
        if (needsCatalog) loadReadingCatalog()
    }

    fun openDownload(entry: DownloadEntry) {
        if (entry.manga.sourceUrl == LOCAL_SOURCE_URL && entry.status != DownloadStatus.COMPLETED) {
            viewModelScope.launch { messages.send("本地文件已损坏，请删除后重新导入。") }
            return
        }
        openSavedChapter(entry.manga, entry.chapter.id, entry.chapter.title, entry.chapter)
    }

    fun continueReading(progress: ReadingProgress) {
        openSavedChapter(progress.manga, progress.chapterId, progress.chapterTitle)
    }

    fun openPageCollection(collection: PageCollection) {
        openSavedChapter(collection.manga, collection.chapter.id, collection.chapter.title,
            fallback = collection.chapter, collection = collection)
    }

    fun readFromLibrary(manga: Manga) {
        if (_libraryOpening.value?.let { it.manga.id == manga.id && it.loading } == true) return
        cancelLibraryReading()
        library.progressFor(manga.id)?.takeIf { library.ready.value && it.chapterId.isNotBlank() }?.let {
            continueReading(it)
            return
        }
        val requestId = libraryOpenRequestId
        _libraryOpening.value = LibraryOpenState(manga)
        val request = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                library.awaitReady()
                if (requestId != libraryOpenRequestId) return@launch
                library.progressFor(manga.id)?.takeIf { it.chapterId.isNotBlank() }?.let {
                    libraryOpenJob = null
                    _libraryOpening.value = null
                    continueReading(it)
                    return@launch
                }
                if (manga.sourceUrl == LOCAL_SOURCE_URL) {
                    downloads.awaitReady()
                    val chapters = downloadedChapters(manga.id)
                    val first = chapters.firstOrNull()
                        ?: throw IllegalStateException("本地章节不存在或已损坏，请重新导入。")
                    if (requestId != libraryOpenRequestId) return@launch
                    libraryOpenJob = null
                    _libraryOpening.value = null
                    closeDetail()
                    beginReading(ReadingSession(manga, chapters, first.id))
                    return@launch
                }
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
        if (session.collection == null && session.manga.sourceUrl != LOCAL_SOURCE_URL) {
            if (repository.cachedSourceDetail(session.manga.id) == null)
                repository.retainWork(MangaDetail(session.manga, session.chapters))
            repository.discoverSources(session.manga, sourceAliases(session.manga))
        }
    }

    private fun openSavedChapter(
        manga: Manga, chapterId: String, chapterTitle: String, fallback: Chapter? = null,
        collection: PageCollection? = null,
    ) {
        val local = manga.sourceUrl == LOCAL_SOURCE_URL
        val cached = if (local) null else repository.cachedDetail(manga.id)
        val offline = if (local) downloadedChapters(manga.id) else knownDownloadChapters(manga.id)
        val chapter = cached?.chapters?.let { findSourceChapter(it, chapterId) }
            ?: offline.firstOrNull { it.id == chapterId }
            ?: fallback
            ?: Chapter(chapterId, chapterTitle.ifBlank { "继续阅读" }, 0)
        val chapters = selectedChapters(retainedChapters(cached?.chapters.orEmpty(), offline), chapter)
        val hasCurrentCatalog = cached?.chapters?.let { findSourceChapter(it, chapterId) != null } == true
        val needsCatalog = if (local) offline.isEmpty() else !hasCurrentCatalog
        closeDetail()
        // Reader restores the saved page/offset and checks local files itself.
        // Neither download-store initialization nor a directory request blocks
        // this navigation. A fresh catalog is useful only for adjacent chapters.
        beginReading(ReadingSession(cached?.manga ?: manga, chapters, chapter.id,
            chaptersLoading = needsCatalog, collection = collection))
        if (needsCatalog) loadReadingCatalog()
    }

    fun retryReadingChapters() {
        if (_reading.value?.chaptersLoading != false) return
        loadReadingCatalog(forceRefresh = true)
    }

    private fun loadReadingCatalog(forceRefresh: Boolean = false) {
        val session = _reading.value ?: return
        val requestId = readingRequestId
        readingCatalogJob?.cancel()
        if (session.manga.sourceUrl == LOCAL_SOURCE_URL) {
            readingCatalogJob = viewModelScope.launch {
                downloads.awaitReady()
                if (requestId != readingRequestId) return@launch
                val chapters = downloadedChapters(session.manga.id)
                _reading.update { current ->
                    if (current?.manga?.id != session.manga.id) current
                    else current.copy(
                        chapters = chapters,
                        chapterId = current.chapterId.takeIf { selected -> chapters.any { it.id == selected } }
                            ?: chapters.firstOrNull()?.id ?: current.chapterId,
                        chaptersLoading = false,
                        chaptersError = if (chapters.isEmpty()) "本地章节不存在，请重新导入。" else null,
                    )
                }
            }
            return
        }
        _reading.update { it?.copy(chaptersLoading = true, chaptersError = null) }
        val pendingDetail = detailJob?.takeIf {
            !forceRefresh && it.isActive && _detail.value?.manga?.id == session.manga.id
        }
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
                // Entering the reader while detail refreshes shares that catalog work.
                // Only the background directory waits; current chapter images load independently.
                pendingDetail?.join()
                if (requestId != readingRequestId) return@launch
                if (pendingDetail != null && detailJob === pendingDetail) {
                    _detail.value?.takeIf { it.manga.id == session.manga.id }?.error?.let { failure ->
                        throw IllegalStateException(failure)
                    }
                }
                val cached = if (forceRefresh) null else repository.cachedDetail(session.manga.id)
                    ?.takeIf { findSourceChapter(it.chapters, session.chapterId) != null }
                // Downloaded chapters are immediately usable, but they are not
                // a complete directory for continuing into the next chapter.
                val loaded = cached ?: repository.detail(session.manga.id)
                readerCatalogs.save(loaded)
                if (requestId != readingRequestId) return@launch
                repository.discoverSources(loaded.manga, sourceAliases(loaded.manga))
                _reading.update { current ->
                    if (current?.manga?.id != session.manga.id) current
                    else current.copy(
                        manga = loaded.manga,
                        chapters = selectedChapters(retainedChapters(loaded.chapters, current.chapters + offline),
                            visibleReading?.takeIf { it.first.id == session.manga.id }?.second
                                ?: findSourceChapter(current.chapters, current.chapterId)
                                ?: Chapter(current.chapterId, "继续阅读", 0)),
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

    suspend fun volumeCover(mangaId: String, chapter: Chapter, forceRefresh: Boolean): Page {
        // This accessor only reads saved metadata/files; it does not use resolveReaderPage(),
        // whose job is to schedule a real reading transfer.
        val stored = downloads.coverPage(mangaId, chapter.id, preferLocal = !forceRefresh)
        if (stored != null && (!forceRefresh || stored.url.startsWith("file:"))) return stored
        return repository.coverPage(chapterSourceMangaId(mangaId, chapter), chapter.id, forceRefresh)
    }

    /** Only a visible chapter can request its immediate same-group successor. */
    fun prefetchNextChapter(manga: Manga, chapter: Chapter) {
        if (manga.sourceUrl == LOCAL_SOURCE_URL) return
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
                repository.pages(chapterSourceMangaId(manga.id, chapter), chapter.id)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Optional next-chapter work has no current-reader error state.
                // SourceHttp still retains any source-wide rate-limit pause.
            }
        }
    }

    fun retryDownload(entry: DownloadEntry) {
        if (entry.manga.sourceUrl == LOCAL_SOURCE_URL) {
            viewModelScope.launch { messages.send("本地文件已损坏，请删除后重新导入。") }
            return
        }
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
                val pages = repository.pages(chapterSourceMangaId(entry.manga.id, entry.chapter), entry.chapter.id, forceRefresh = true)
                val refreshed = downloads.refreshReadingDownload(entry.manga, entry.chapter, pages,
                    repository.imageHeaders(chapterSourceMangaId(entry.manga.id, entry.chapter), entry.chapter.id), expectedEntry = expected)
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
        cancelSourceResumeChoice()
        cancelSourceSelection()
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

    fun beginWebsiteLogin(): Long {
        websiteLoginAttempt = null
        accountJob?.cancel()
        cancelAccountRequests()
        accountRequestId++
        return accountRepository.beginWebsiteLogin().also { websiteLoginAttempt = it }
    }

    fun cancelWebsiteLogin(expected: Long) {
        if (websiteLoginAttempt != expected) return
        websiteLoginAttempt = null
        accountRepository.cancelWebsiteLogin(expected)
    }

    fun loginWithWebsiteToken(expected: Long, token: String) {
        if (websiteLoginAttempt != expected || expected != accountRepository.sessionVersion) return
        websiteLoginAttempt = null
        authenticate { accountRepository.loginWithWebsiteToken(expected, token) }
    }

    private fun restoreAccount() = authenticate(restoring = true) { accountRepository.restore() }

    private fun authenticate(restoring: Boolean = false, action: suspend () -> AccountProfile?) {
        val browserAttempt = websiteLoginAttempt
        websiteLoginAttempt = null
        browserAttempt?.let(accountRepository::cancelWebsiteLogin)
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
                        error = accountError(failure, "登录暂时未完成，请稍后重试。"),
                        canRetryConnection = accountRepository.canRetryLogin || (restoring && !accountRepository.hasPendingLogin))
                }
            }
        }
    }

    fun logout() {
        websiteLoginAttempt = null
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
        else if (accountRepository.hasPendingLogin || _account.value.profile == null) restoreAccount()
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
        if (!library.ready.value) {
            viewModelScope.launch { library.awaitReady(); toggleFavorite(manga) }
            return
        }
        library.toggleFavorite(manga)
        if (_account.value.profile != null && !_account.value.busy) {
            setCloudFavorite(manga, library.favorites.value.any { it.id == manga.id })
        }
    }

    fun removeFavorite(manga: Manga) {
        if (!library.ready.value) {
            viewModelScope.launch { library.awaitReady(); removeFavorite(manga) }
            return
        }
        if (_libraryOpening.value?.manga?.id == manga.id) cancelLibraryReading()
        if (!library.isFavorite(manga.id)) return
        library.removeFavorite(manga.id)
        if (_account.value.profile != null && !_account.value.busy) {
            setCloudFavorite(manga, false)
        }
    }

    fun removeHistory(mangaId: String) {
        if (_libraryOpening.value?.manga?.id == mangaId) cancelLibraryReading()
        library.removeHistory(mangaId)
    }

    fun setCloudFavorite(manga: Manga, favorite: Boolean) {
        if (manga.sourceId != "copy" || SourceKeys.isEncoded(manga.id)) return
        val state = _account.value
        if (state.profile == null || state.busy) return
        val version = accountRepository.sessionVersion
        // Coalesce only the desired state. Never cancel or replay an in-flight account POST.
        pendingFavoriteSync[manga.id] = manga to favorite
        if (favoriteSyncJobs[manga.id]?.isActive == true) return
        _account.update { it.copy(syncingFavorites = it.syncingFavorites + manga.id,
            syncMessage = "正在同步收藏…", canRetrySync = hasPendingSync()) }
        val request = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                while (isCurrentAccount(version)) {
                    val desired = pendingFavoriteSync[manga.id] ?: break
                    val confirmed = foregroundRequest("云端收藏同步", 45_000L) {
                        val uuid = repository.comicUuid(manga.id)
                        if (!isCurrentAccount(version)) throw CancellationException("Account changed")
                        accountRepository.setFavorite(manga.id, uuid, desired.second)
                    }
                    if (!isCurrentAccount(version)) return@launch
                    if (!confirmed) throw SourceException("来源尚未确认收藏同步，请稍后重试。")
                    if (pendingFavoriteSync[manga.id]?.second == desired.second) {
                        pendingFavoriteSync.remove(manga.id)
                        _account.update { it.copy(syncMessage = if (desired.second) "已加入云端收藏" else "已从云端收藏移除",
                            canRetrySync = hasPendingSync()) }
                        break
                    }
                }
                loadCloudFavorites(refresh = true)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (isCurrentAccount(version)) {
                    val message = accountError(failure, "云端收藏同步失败，请稍后重试。")
                    _account.update { it.copy(syncMessage = "$message 本地书架不受影响。", canRetrySync = true) }
                    messages.send("$message 本地书架不受影响。")
                }
            } finally {
                if (isCurrentAccount(version)) _account.update { it.copy(syncingFavorites = it.syncingFavorites - manga.id) }
                if (favoriteSyncJobs[manga.id] === currentCoroutineContext()[Job]) favoriteSyncJobs.remove(manga.id)
            }
        }
        favoriteSyncJobs[manga.id] = request
        request.start()
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
        if (chapter.sourceId != "copy" || SourceKeys.isEncoded(chapter.id) || manga.sourceId != "copy") return
        if (manga.sourceUrl == LOCAL_SOURCE_URL) return
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
        enqueueChapterDownload(manga, chapter)
    }

    fun downloadChapterVariant(manga: Manga, chapter: Chapter, variant: ChapterVariant) {
        if (chapter.variants.none { it == variant }) return
        enqueueChapterDownload(manga, resolveChapter(chapter, variant))
    }

    private fun enqueueChapterDownload(manga: Manga, chapter: Chapter) {
        val key = "${manga.id}/${chapter.id}"
        if (!preparingDownloads.add(key)) return
        viewModelScope.launch {
            try {
                // A bulk selection still uses the ordinary queue, with bounded source preparation.
                downloadPreparationSlots.withPermit {
                    messages.send("正在准备 ${chapter.title}")
                    val pages = dev.mangaflow.data.foregroundRequest("下载章节图片") {
                        repository.pages(chapterSourceMangaId(manga.id, chapter), chapter.id)
                    }
                    downloads.enqueue(manga, chapter, pages, repository.imageHeaders(chapterSourceMangaId(manga.id, chapter), chapter.id))
                    messages.send("已加入下载，可在下载页查看进度")
                }
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

    override fun onCleared() {
        repository.close()
        unifiedChapters.close()
        super.onCleared()
    }
}
