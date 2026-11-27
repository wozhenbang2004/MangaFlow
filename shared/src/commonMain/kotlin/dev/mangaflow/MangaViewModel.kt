package dev.mangaflow

import dev.mangaflow.data.chapterSourceMangaId
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.experience.ContentCacheKind
import dev.mangaflow.experience.contentCachePreferences
import dev.mangaflow.experience.PrefetchByteBudget
import dev.mangaflow.experience.PrefetchBudgetExceeded
import dev.mangaflow.experience.PrefetchStage
import dev.mangaflow.experience.prefetchOriginalPageResult
import dev.mangaflow.experience.inspectReaderPreparedArtifacts
import dev.mangaflow.experience.ReaderPreparedArtifacts
import dev.mangaflow.experience.readerPageCacheKey
import dev.mangaflow.experience.ExperienceAdmissionDeferred
import dev.mangaflow.data.ReaderSourceStandbyController
import dev.mangaflow.data.ReaderSourceStandbyAvailability
import dev.mangaflow.data.ReaderSourceStandbyAvailabilityPhase
import dev.mangaflow.data.ReaderNextChapterPreparation
import dev.mangaflow.data.ReaderNextChapterPreparationPhase
import dev.mangaflow.reader.prepareReaderSourcePage
import dev.mangaflow.reader.readerManifestFingerprint
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.data.approximateReaderPage
import dev.mangaflow.data.plugins.prefetchScriptPage
import dev.mangaflow.data.sources.prefetchJmPage
import dev.mangaflow.data.sources.JmImageRefreshRequired
import dev.mangaflow.data.plugins.ScriptImageRefreshRequired
import dev.mangaflow.data.plugins.sourceImageRevisions
import dev.mangaflow.data.plugins.scriptSourceImageEpoch
import dev.mangaflow.experience.PrefetchWork
import dev.mangaflow.experience.withPrefetchImageInactivity
import dev.mangaflow.experience.PrefetchImageInactivityException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mangaflow.account.AccountProfile
import dev.mangaflow.account.CloudHistoryEntry
import dev.mangaflow.account.CopyLoginRoute
import dev.mangaflow.account.CopyShelfMetadata
import dev.mangaflow.storage.SavedResumeTarget
import dev.mangaflow.ui.CopyProgressHintState
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.ChapterSemantics
import dev.mangaflow.data.DiscoveryMode
import dev.mangaflow.data.RankPeriod
import dev.mangaflow.data.RankAudience
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaPage
import dev.mangaflow.data.SourceHttp
import dev.mangaflow.data.MangaTag
import dev.mangaflow.data.SearchTarget
import dev.mangaflow.data.CopySearchTags
import dev.mangaflow.data.MangaDetail
import dev.mangaflow.data.MangaMetric
import dev.mangaflow.data.ReviewProviders
import dev.mangaflow.data.ReviewSource
import dev.mangaflow.data.ReviewStatus
import dev.mangaflow.data.ReviewPage
import dev.mangaflow.data.Page
import dev.mangaflow.data.MangaReview
import dev.mangaflow.data.foregroundRequest
import dev.mangaflow.data.SourceException
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.identity
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.data.SourceMangaRef
import dev.mangaflow.data.UnifiedMangaRepository
import dev.mangaflow.data.resolveChapter
import dev.mangaflow.data.sourceDisplayName
import dev.mangaflow.data.bodySourceSearchTerms
import dev.mangaflow.data.normalizedBodySourceTitle
import dev.mangaflow.data.SiteRankedWork
import dev.mangaflow.data.rankedCandidateRelevant
import dev.mangaflow.data.SourceDiscoveryIdentity
import dev.mangaflow.data.SourceDiscoveryResult
import dev.mangaflow.data.SourceDiscoveryStatus
import dev.mangaflow.data.FederatedSearchSource
import dev.mangaflow.data.FederatedSearchGroup
import dev.mangaflow.data.FederatedSearchGrouper
import dev.mangaflow.data.MangaGenreDirectory
import dev.mangaflow.data.MangaTagKind
import dev.mangaflow.data.combineOrdinarySearchTags
import dev.mangaflow.data.federatedMangaIdentity
import dev.mangaflow.data.sources.KomiicBodySource
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadCommandResult
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.DownloadStorageException
import dev.mangaflow.storage.DownloadStore
import dev.mangaflow.storage.DownloadStore.Companion.LOCAL_SOURCE_URL
import dev.mangaflow.storage.LibraryStore
import dev.mangaflow.storage.PageCollection
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.storage.BrowseSnapshot
import dev.mangaflow.json.JSONArray
import dev.mangaflow.update.UpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
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
    val fromCache: Boolean = false,
    val sourceMatches: Map<String, List<Manga>> = emptyMap(),
    val sourceChoiceIds: Set<String> = emptySet(),
    val searchSources: Map<String, FederatedSearchSource> = emptyMap(),
    val sourceNextPages: Map<String, Int> = emptyMap(),
    val sourceScope: String = "",
    /** Already confirmed readable versions of TAG hits. Only sourceMatches contains actual search hits. */
    val alignedTagSources: Map<String, List<Manga>> = emptyMap(),
) {
    val isSearch: Boolean get() = searchTarget != SearchTarget.TITLE || query.isNotBlank() || selectedTags.isNotEmpty()
    val isFederatedSearch: Boolean get() = searchTarget != SearchTarget.TRANSLATION_GROUP &&
        (query.isNotBlank() || selectedTags.isNotEmpty())
    val cacheKey: String get() = when {
        isSearch -> {
            val keyword = if (searchTarget == SearchTarget.TAG) "" else query.trim()
            val prefix = when {
                isFederatedSearch -> "search-v12-fields:$sourceScope"
                searchTarget == SearchTarget.TRANSLATION_GROUP -> "copy-translation-v1:$sourceScope"
                else -> "search-v3-heat"
            }
            val tags = selectedTags.sortedBy(MangaTag::path).joinToString("|") { tag ->
                val native = tag.additionalSearchPaths().entries.sortedBy { it.key }
                    .joinToString(",") { (source, path) -> "$source:${path.length}:$path" }
                "${tag.path.length}:${tag.path}:${native.length}:$native"
            }
            "$prefix:${searchTarget.name}:${keyword.length}:$keyword:$tags"
        }
        mode == DiscoveryMode.RANKING -> "ranking:${period.name}:${audience.name}"
        else -> "discover:${mode.name}"
    }
}

private const val COPY_GROUP_RECENTS_KEY = "copy_group_search_recent"
private const val COPY_GROUP_RECENTS_LIMIT = 8

private fun String.validCopyGroupTerm(): Boolean = length in 1..120 && none { it.isISOControl() }

private data class TagSearchIdentities(val sourceScope: String, val cacheKey: String, val groups: Map<String, List<Manga>>)

private fun MangaTag.additionalSearchPaths(): Map<String, String> =
    providerPaths.filter { (source, native) -> source != "copy" || native != path }

private fun BrowseState.tagSearchIdentities(): TagSearchIdentities? {
    if (searchTarget != SearchTarget.TAG || !isFederatedSearch || items.isEmpty()) return null
    return TagSearchIdentities(sourceScope, cacheKey, items.associate { manga ->
        manga.id to sourceMatches[manga.id].orEmpty().ifEmpty { listOf(manga) }
    })
}

data class DetailState(
    val manga: Manga,
    val detail: MangaDetail? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val reviews: List<ReviewSource> = emptyList(),
    val reviewsLoading: Boolean = true,
    val rankedWork: SiteRankedWork? = null,
    val rankedSources: List<RankedReadingSource> = emptyList(),
    val rankedSourcesLoading: Boolean = false,
    val rankedSourcesError: String? = null,
    val rankedLinkedSource: Manga? = null,
    val rankedOpeningSourceId: String? = null,
    val rankedSourceError: String? = null,
    val rankedSourceWork: SiteRankedWork? = null,
    val rankedSourcePreview: RankedSourcePreview? = null,
)

data class ChapterSourceOperation(val loading: Boolean = false, val error: String? = null,
    val targetSource: String = "")

data class SourceResumeChoice(val workId: String, val chapter: Chapter, val variant: ChapterVariant,
    val fromPageIndex: Int, val fromPageCount: Int = 0)

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
    val chapters: List<Chapter> = emptyList(),
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
    val savedCredentialPresent: Boolean = false,
    val autoRestorePending: Boolean = false,
    val favorites: List<Manga> = emptyList(),
    val favoritesLoading: Boolean = false,
    val favoritesRefreshing: Boolean = false,
    val favoritesHasMore: Boolean = false,
    val favoritesError: String? = null,
    val history: List<CloudHistoryEntry> = emptyList(),
    val historyLoading: Boolean = false,
    val historyRefreshing: Boolean = false,
    val historyHasMore: Boolean = false,
    val historyError: String? = null,
    val syncMessage: String? = null,
    val canRetrySync: Boolean = false,
    val syncingFavorites: Set<String> = emptySet(),
)

/** Remote shelf presentation only; these values are never imported into local reading history. */
data class CopyShelfRemoteState(
    val owner: String? = null,
    val items: Map<String, CopyShelfMetadata> = emptyMap(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
)

private class RankedSourceIdentityMismatch : IllegalArgumentException("来源资料与所选作品不一致。")

class MangaViewModel(context: PlatformContext) : ViewModel() {
    private val application = applicationContext(context)
    internal val services = MangaApplicationServices.get(application)
    private val sourcePreferences = preferences(application, "mangaflow_library")
    private val komiicSource = services.komiicSource
    val picacgSource = services.picacgSource
    val unifiedChapters = services.unifiedChapters
    val repository = services.repository
    val copyExplore = CopyExploreController(repository.copy, viewModelScope) { "copy" in repository.enabledSources() }
    private var copyExploreSourceScope = repository.sourceConfigurationKey(adult = false)
    val bodySources = services.bodySources
    val sourcePlugins = services.sourcePlugins
    internal val starterSources = services.starterSources
    val library = services.library
    val downloads = services.downloads
    val updates = services.updates
    val community = services.community
    val reviews = services.reviews
    private val siteRankingsRepository = services.siteRankingsRepository
    val siteRankings = SiteRankingsController(siteRankingsRepository, viewModelScope)
    private var rankedWorkJob: Job? = null
    private var rankedWorkRequest = 0L
    private val readerCatalogs = services.readerCatalogs
    private val browseCache = services.browseCache
    private val metricsRepository = services.metricsRepository
    private val _metrics = MutableStateFlow<Map<String, List<MangaMetric>>>(emptyMap())
    val metrics = _metrics.asStateFlow()
    private val accountRepository = services.accountRepository
    val copyShelfSync = services.copyShelfSync
    private val _account = MutableStateFlow(AccountUiState())
    val account = _account.asStateFlow()
    private val _copyProgress = MutableStateFlow<CopyProgressHintState?>(null)
    val copyProgress = _copyProgress.asStateFlow()
    private var copyProgressJob: Job? = null
    private var copyProgressRequest = 0L
    private val _copyShelfRemote = MutableStateFlow(CopyShelfRemoteState())
    val copyShelfRemote = _copyShelfRemote.asStateFlow()
    private var copyShelfRemoteJob: Job? = null
    private var copyShelfRemoteRequest = 0L
    private val _browse = MutableStateFlow(BrowseState())
    val browse = _browse.asStateFlow()
    private val copyGroupPreferences = preferences(application, "mangaflow_search_choices")
    private val _recentCopyGroupTerms = MutableStateFlow(runCatching {
        val encoded = copyGroupPreferences.getString(COPY_GROUP_RECENTS_KEY).orEmpty()
        if (encoded.length !in 2..6000) emptyList() else {
            val rows = JSONArray(encoded)
            List(minOf(rows.length(), COPY_GROUP_RECENTS_LIMIT)) { rows.opt(it) as? String }
                .filterNotNull().map(String::trim).filter { it.validCopyGroupTerm() }.distinct()
        }
    }.getOrDefault(emptyList()))
    internal val recentCopyGroupTerms = _recentCopyGroupTerms.asStateFlow()
    private var confirmedCopyGroupKey: String? = null
    private var retainedBrowse: BrowseState? = null
    private var knownSearchTags = CopySearchTags
    private var genreDirectory = MangaGenreDirectory(emptyList())
    private var genreDirectoryScope = ""
    private var genreDirectoryUpdatedAt = 0L
    private var genreDirectoryJob: Job? = null
    private var genreDirectoryRequest = 0L
    private var genreDirectoryLoading = false
    private var genreDirectoryError: String? = null
    private var tagLookupJob: Job? = null
    private var tagLookupRequest = 0L
    private var pendingTagLookup: Pair<Manga, String>? = null
    private data class SearchTagDirectory(
        val themes: List<MangaTag>, val loading: Boolean, val error: String?, val updatedAt: Long,
    )
    private val _detail = MutableStateFlow<DetailState?>(null)
    val detail = _detail.asStateFlow()
    private val _authorWorks = MutableStateFlow<AuthorWorksState?>(null)
    val authorWorks = _authorWorks.asStateFlow()
    private var authorJob: Job? = null
    private var authorRequest = 0L
    private val _reading = MutableStateFlow<ReadingSession?>(null)
    val reading = _reading.asStateFlow()
    private val _nextChapterPreparation = MutableStateFlow<ReaderNextChapterPreparation?>(null)
    val nextChapterPreparation = _nextChapterPreparation.asStateFlow()
    private val readerStandbys = ReaderSourceStandbyController(application, repository, viewModelScope)
    val readerSourceStandbys = readerStandbys.state
    val readerSourceStandbyAvailability = readerStandbys.availability
    private val _chapterSourceOperation = MutableStateFlow(ChapterSourceOperation())
    val chapterSourceOperation = _chapterSourceOperation.asStateFlow()
    private val _sourceResumeChoice = MutableStateFlow<SourceResumeChoice?>(null)
    val sourceResumeChoice = _sourceResumeChoice.asStateFlow()
    private val _sourceResumePolicy = MutableStateFlow(sourcePreferences.getString("source_resume_policy", "same_page")
        ?.takeIf { it in setOf("ask", "same_page", "source_record") } ?: "ask")
    val sourceResumePolicy = _sourceResumePolicy.asStateFlow()
    private var sourceResumeEpoch = 0L
    private var sourceResumeFromReader = false
    private val _sourceManagement = MutableStateFlow<SourceManagementState?>(null)
    val sourceManagement = _sourceManagement.asStateFlow()
    private var sourceReadJob: Job? = null
    private var sourceReadRequest = 0L
    private var failedSourcePage: Pair<String, String>? = null
    private val automaticSourceSwitches = mutableMapOf<String, Long>()
    private data class ReaderSourceUndo(
        val workId: String, val chapter: Chapter, val variant: ChapterVariant,
        val nativePage: Int, val manifest: String, val sourceEpoch: String,
        val afterChapterId: String, val afterReadingEpoch: Long,
    )
    private var readerSourceUndo: ReaderSourceUndo? = null
    private var readerNativeCursor: Triple<String, String, Int>? = null
    private val _readerSourceSwitchUndoAvailable = MutableStateFlow(false)
    val readerSourceSwitchUndoAvailable = _readerSourceSwitchUndoAvailable.asStateFlow()
    private var sourceManagementJob: Job? = null
    @Volatile private var sourceManagementRequest = 0L
    private val _libraryOpening = MutableStateFlow<LibraryOpenState?>(null)
    val libraryOpening = _libraryOpening.asStateFlow()
    val refreshingDownloads = services.preparations.refreshingDownloads
    val chapterPreparations = services.preparations.state
    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()
    private var browseJob: Job? = null
    private class PreparedDiscoveryPage(val result: Deferred<MangaPage>, val matches: () -> Boolean,
        val sameSource: () -> Boolean)
    private var preparedDiscoveryPage: PreparedDiscoveryPage? = null
    private var lastDiscoveryPreparation = -1L
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
    private var libraryOpenRetry: (() -> Unit)? = null
    private var libraryOpenRequestId = 0L
    private var chapterPrefetchJob: Job? = null
    private var chapterPrefetchKey: String? = null
    private var chapterPrefetchRequest = 0L
    private data class NextChapterIntent(val epoch: Long, val workId: String, val currentId: String,
        val currentOwner: String, val targetId: String, val targetOwner: String)
    private var chapterPrefetchIntent: NextChapterIntent? = null
    private var chapterPrefetchImageRevision = 0L
    private var chapterPrefetchImageRestarts = 0
    // A cancelled replacement may itself still be waiting for an older native task.
    // Keep every retiring generation until its real cleanup has completed.
    private val retiringChapterPrefetches = mutableSetOf<Job>()
    private var readingRequestId = 0L
    @Volatile private var detailRequestId = 0L
    @Volatile private var browseRequestId = 0L
    private val reviewPageJobs = mutableMapOf<String, Job>()
    private val reviewOverrides = ConcurrentSet<String>()
    private var accountJob: Job? = null
    private var accountRestoreRetryJob: Job? = null
    private var accountRestoreFailures = 0
    private var accountRequestId = 0L
    private var websiteLoginAttempt: Long? = null
    private var accountCleanupFailed = false
    private var cloudFavoritesJob: Job? = null
    private var cloudHistoryJob: Job? = null
    private var cloudFavoritesRequestId = 0L
    private var cloudHistoryRequestId = 0L
    private var cloudFavoritesPage = -1
    private var cloudHistoryPage = -1
    private var cloudFavoritesFailedPage: Int? = null
    private var cloudHistoryFailedPage: Int? = null
    private var visibleReading: Pair<Manga, Chapter>? = null
    private var projectionRequestId = 0L
    private val projectionSaveLock = Mutex()

    init {
        showBrowse(_browse.value)
        restoreAccount()
        viewModelScope.launch {
            copyExplore.state.map { SearchTagDirectory(it.themes, it.directoryLoading, it.themeError,
                it.directoryUpdatedAt) }.distinctUntilChanged().collect { directory ->
                // Native labels/counts replace older values without losing detail-returned identifiers.
                knownSearchTags = (knownSearchTags + directory.themes).associateBy(MangaTag::path).values.toList()
                _browse.update { current -> current.withSearchTagDirectory() }
                if (directory.updatedAt > 0) launch { refreshNewTagBindings(repository.ordinaryGenreScope()) }
            }
        }
        viewModelScope.launch {
            services.sourceConfiguration.drop(1).collect { scope ->
                val ordinaryScope = repository.sourceConfigurationKey(adult = false)
                if (scope == services.sourceConfiguration.value && scope == repository.sourceConfigurationKey() &&
                    ordinaryScope != copyExploreSourceScope) {
                    copyExploreSourceScope = ordinaryScope
                    invalidateCopySearchDirectory()
                }
                val current = _browse.value
                cancelReaderSourceStandbys()
                val intent = chapterPrefetchIntent
                cancelNextChapterPrefetch(clearIntent = false)
                if (services.sourceConfiguration.value == scope && repository.sourceConfigurationKey() == scope)
                    resumeNextChapterIntent(intent)
                listOfNotNull(_detail.value?.manga, _reading.value?.manga)
                    .distinctBy { it.id }.filter { isAdultSource(it.sourceId) }.forEach { discoverWorkSources(it) }
                if ((current.isFederatedSearch || current.searchTarget == SearchTarget.TRANSLATION_GROUP) &&
                    current.sourceScope != ordinaryScope) showBrowse(current.copy(
                    items = emptyList(), sourceMatches = emptyMap(), sourceChoiceIds = emptySet(),
                    searchSources = emptyMap(), sourceNextPages = emptyMap(), hasMore = false,
                    loading = true, loadingMore = false, refreshing = false, error = null,
                    failedPage = null, page = 0, updatedAt = 0,
                ))
            }
        }
        viewModelScope.launch {
            sourceImageRevisions.collect { revisions ->
                val intent = chapterPrefetchIntent ?: return@collect
                val source = SourceKeys.decodeChapter(intent.targetOwner, intent.targetId).sourceId
                if (!source.startsWith("js_") && source != "jmcomic") return@collect
                val revision = revisions[source] ?: 0L
                if (revision != (sourceImageRevisions.value[source] ?: 0L) ||
                    revision == chapterPrefetchImageRevision) return@collect
                resumeNextChapterIntent(intent)
            }
        }
        viewModelScope.launch {
            services.sourceError.collect { error -> if (error != null) messages.send(error) }
        }
        viewModelScope.launch {
            services.readingOutbox.state.collect { state ->
                _account.update { current ->
                    if (current.profile?.username != state.owner) current
                    else current.copy(
                        syncMessage = state.error ?: state.message ?: current.syncMessage,
                        canRetrySync = state.pending > 0 && !state.running,
                    )
                }
            }
        }
        viewModelScope.launch {
            services.preparations.persistenceError.collect { error -> if (error != null) messages.send(error) }
        }
        viewModelScope.launch {
            library.backupRestored.drop(1).collect {
                cancelReaderSourceStandbys()
                clearCopyProgress()
                try {
                    _sourceResumePolicy.value = sourcePreferences.getString("source_resume_policy", "same_page")
                        ?.takeIf { it in setOf("ask", "same_page", "source_record") } ?: "ask"
                } catch (cancel: CancellationException) { throw cancel
                } catch (failure: Exception) {
                    messages.send(failure.message ?: "漫画源设置未能刷新，请重新打开应用。")
                }
            }
        }
        viewModelScope.launch {
            combine(unifiedChapters.revision, repository.catalogRevision) { settings, catalogs -> settings to catalogs }
                .drop(1).collectLatest { refreshUnifiedProjection() }
        }
        viewModelScope.launch { repository.sourceDiscovery.collect { refreshSourceOverview() } }
        viewModelScope.launch { observeAlignedTagSources() }
        viewModelScope.launch { unifiedChapters.persistenceError.collect { error ->
            if (error != null) messages.send(error)
        } }
    }

    fun flushPendingStorage() {
        services.scope.launch {
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
            _browse.update { it.copy(query = query).withSearchTagDirectory() }
            return
        }
        showBrowse(BrowseState(query = query, mode = previous.mode, period = previous.period,
            audience = previous.audience, searchTarget = previous.searchTarget, selectedTags = previous.selectedTags),
            debounce = query.isNotBlank() && previous.searchTarget != SearchTarget.TAG)
    }

    fun setSearchTarget(target: SearchTarget) {
        val previous = _browse.value
        if (previous.searchTarget == target) {
            if (target == SearchTarget.TAG) loadSearchTagDirectories()
            return
        }
        cancelTagLookup()
        showBrowse(BrowseState(query = previous.query, searchTarget = target, selectedTags = previous.selectedTags,
            mode = previous.mode, period = previous.period, audience = previous.audience))
    }

    fun submitSearch() {
        val previous = _browse.value
        if (previous.searchTarget == SearchTarget.TRANSLATION_GROUP && previous.query.isNotBlank()) {
            confirmedCopyGroupKey = previous.cacheKey
            rememberConfirmedCopyGroupResult(previous, previous.error == null && previous.items.any { it.sourceId == "copy" })
            refresh()
            return
        }
        if (previous.searchTarget != SearchTarget.TAG || previous.query.isBlank()) {
            refresh()
            return
        }
        // Unknown labels are still useful to native TAG endpoints such as JM.
        // Only Copy's known paths are ever passed to its path-based tag API.
        val label = previous.query.trim()
        val tag = searchTagForName(label) ?: MangaTag(label, "label:" + normalizedBodySourceTitle(label))
        if (tag.name.isBlank() || tag.name.length > 120) return
        cancelTagLookup()
        showBrowse(previous.copy(query = "", selectedTags = (previous.selectedTags + tag).distinctBy(MangaTag::path),
            items = emptyList(), page = 0, error = null, tagsError = null, tagsLoading = false))
    }

    internal fun chooseRecentCopyGroupTerm(term: String) {
        val previous = _browse.value
        if (previous.searchTarget != SearchTarget.TRANSLATION_GROUP || term !in _recentCopyGroupTerms.value ||
            "copy" !in repository.enabledSources()) return
        if (previous.query == term && previous.selectedTags.isEmpty()) {
            if (previous.error == null && previous.items.any { it.sourceId == "copy" }) rememberCopyGroupTerm(previous)
            else submitSearch()
            return
        }
        cancelTagLookup()
        showBrowse(BrowseState(query = term, searchTarget = SearchTarget.TRANSLATION_GROUP,
            mode = previous.mode, period = previous.period, audience = previous.audience), confirmCopyGroupTerm = true)
    }

    internal fun noteCopyGroupResultOpened(manga: Manga) {
        if (manga.sourceId != "copy") return
        val current = _browse.value
        if (current.searchTarget == SearchTarget.TRANSLATION_GROUP && current.items.any { it.id == manga.id && it.sourceId == "copy" })
            rememberCopyGroupTerm(current)
    }

    internal fun clearRecentCopyGroupTerms() {
        confirmedCopyGroupKey = null
        saveRecentCopyGroupTerms(emptyList())
    }

    private fun rememberConfirmedCopyGroupResult(state: BrowseState, hasCopyMatch: Boolean) {
        if (hasCopyMatch && confirmedCopyGroupKey == state.cacheKey) {
            confirmedCopyGroupKey = null
            rememberCopyGroupTerm(state)
        }
    }

    private fun rememberCopyGroupTerm(state: BrowseState) {
        val term = state.query.trim()
        if (state.searchTarget != SearchTarget.TRANSLATION_GROUP || state.selectedTags.isNotEmpty() ||
            !term.validCopyGroupTerm() || "copy" !in repository.enabledSources() ||
            state.sourceScope != repository.sourceConfigurationKey(adult = false)) return
        saveRecentCopyGroupTerms((listOf(term) + _recentCopyGroupTerms.value.filterNot { it == term })
            .take(COPY_GROUP_RECENTS_LIMIT))
    }

    private fun saveRecentCopyGroupTerms(next: List<String>) {
        if (next == _recentCopyGroupTerms.value) return
        try {
            val editor = copyGroupPreferences.edit()
            if (next.isEmpty()) editor.remove(COPY_GROUP_RECENTS_KEY)
            else editor.putString(COPY_GROUP_RECENTS_KEY, JSONArray(next).toString())
            editor.apply()
            _recentCopyGroupTerms.value = next
        } catch (_: Exception) {
            messages.trySend("汉化组搜索记录未能保存。")
        }
    }

    fun toggleSearchTag(tag: MangaTag) {
        if (tag.name.isBlank() || tag.path.isBlank()) return
        cancelTagLookup()
        val previous = _browse.value
        val selected = if (previous.selectedTags.any { it.path == tag.path })
            previous.selectedTags.filterNot { it.path == tag.path } else previous.selectedTags + tag
        if (!tag.path.startsWith("label:"))
            knownSearchTags = (knownSearchTags + tag).distinctBy(MangaTag::path)
        showBrowse(BrowseState(query = previous.query, searchTarget = previous.searchTarget, selectedTags = selected,
            mode = previous.mode, period = previous.period, audience = previous.audience))
    }

    // One suspended selection, not a second results store. The existing browse cache owns pages.
    private var retainedSearchSelection: BrowseState? = null

    fun suspendBrowseSearch() {
        val previous = _browse.value
        if (previous.isSearch || previous.query.isNotBlank() || previous.searchTarget == SearchTarget.TAG) {
            retainedSearchSelection = BrowseState(query = previous.query, searchTarget = previous.searchTarget,
                selectedTags = previous.selectedTags, mode = previous.mode, period = previous.period,
                audience = previous.audience)
        }
        cancelTagLookup()
        showBrowse(BrowseState(mode = previous.mode, period = previous.period, audience = previous.audience))
    }

    fun resumeBrowseSearch() {
        val selection = retainedSearchSelection ?: return
        retainedSearchSelection = null
        showBrowse(selection)
    }

    fun clearSearch() {
        retainedSearchSelection = null
        cancelTagLookup()
        val previous = _browse.value
        showBrowse(BrowseState(mode = previous.mode, period = previous.period, audience = previous.audience))
    }

    fun openTagSearch(manga: Manga, name: String) {
        cancelTagLookup()
        val previous = _browse.value
        if (manga.sourceId == "copy") knownSearchTags =
            (knownSearchTags + manga.tagPaths.map { MangaTag(it.key, it.value) }).distinctBy(MangaTag::path)
        val known = searchTagForName(name)
        val own = manga.tagPaths[name]?.takeIf(String::isNotBlank)
        val tag = when (manga.sourceId) {
            "copy" -> own?.let { path -> known?.takeIf { it.path == path } ?: MangaTag(name, path) }
                ?: known?.takeUnless { it.path.startsWith("label:") }
            "mangadex", "komiic" -> {
                val label = known ?: MangaTag(name, "label:" + normalizedBodySourceTitle(name))
                if (own == null) label else label.copy(kind = MangaTagKind.GENRE,
                    providerPaths = label.providerPaths + (manga.sourceId to own))
            }
            else -> known ?: MangaTag(name, "label:" + normalizedBodySourceTitle(name))
        }
        val selection = BrowseState(query = if (tag == null) name else "",
            searchTarget = SearchTarget.TAG, selectedTags = listOfNotNull(tag),
            tagsLoading = tag == null,
            mode = previous.mode, period = previous.period, audience = previous.audience)
        if (tag == null) pendingTagLookup = manga to name
        showBrowse(selection)
        if (tag != null) {
            return
        }
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
        loadSearchTagDirectories(forceRefresh = true)
    }

    private fun searchTagForName(name: String): MangaTag? {
        val choices = _browse.value.withSearchTagDirectory().availableTags
        val key = normalizedBodySourceTitle(name)
        val copy = choices.filter { "copy" in it.providerPaths }
        return dev.mangaflow.data.copyTagForName(name, copy) ?: choices.singleOrNull { tag ->
            normalizedBodySourceTitle(tag.name) == key ||
                tag.aliases.any { normalizedBodySourceTitle(it) == key }
        }
    }

    private fun BrowseState.withSearchTagDirectory(): BrowseState {
        val directory = copyExplore.state.value
        val ownsStatus = searchTarget == SearchTarget.TAG && pendingTagLookup == null
        val copyEnabled = "copy" in repository.enabledSources()
        val ownsGenres = genreDirectoryScope.isNotBlank() && genreDirectoryScope == repository.ordinaryGenreScope()
        val genres = genreDirectory.tags.takeIf { ownsGenres }.orEmpty()
        val directoryErrors = listOfNotNull(directory.themeError.takeIf { copyEnabled }, genreDirectoryError.takeIf { ownsGenres })
        return copy(availableTags = combineOrdinarySearchTags(knownSearchTags, genres, copyEnabled),
            tagsLoading = if (ownsStatus) (ownsGenres && genreDirectoryLoading) ||
                (copyEnabled && directory.directoryLoading) else tagsLoading,
            tagsError = if (ownsStatus) directoryErrors.takeIf { it.isNotEmpty() }?.joinToString(" · ") else tagsError)
    }

    private fun loadSearchTagDirectories(forceRefresh: Boolean = false) {
        if ("copy" in repository.enabledSources()) {
            if (forceRefresh) copyExplore.refreshDirectory()
            else copyExplore.ensureDirectory()
        }
        val scope = repository.ordinaryGenreScope()
        if (genreDirectoryScope != scope) {
            resetGenreDirectory()
            genreDirectoryScope = scope
        }
        if (!forceRefresh && (genreDirectoryJob?.isActive == true || wallTimeMillis() - genreDirectoryUpdatedAt in
                0 until contentCachePreferences(application).freshMillis(ContentCacheKind.DISCOVERY))) {
            _browse.update { it.withSearchTagDirectory() }
            return
        }
        genreDirectoryJob?.cancel()
        val request = ++genreDirectoryRequest
        genreDirectoryLoading = true
        genreDirectoryError = null
        _browse.update { it.withSearchTagDirectory() }
        genreDirectoryJob = viewModelScope.launch {
            fun ownsRequest() = request == genreDirectoryRequest && repository.ordinaryGenreScope() == scope
            try {
                val result = repository.ordinarySearchGenres(forceRefresh)
                if (!ownsRequest()) return@launch
                genreDirectory = result
                genreDirectoryUpdatedAt = wallTimeMillis()
                genreDirectoryError = result.errors.takeIf { it.isNotEmpty() }?.entries
                    ?.joinToString(" · ") { (source, message) -> "${sourceDisplayName(source)}：$message" }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (ownsRequest()) {
                    genreDirectoryError = failure.message ?: "多来源题材暂时无法读取。"
                }
            } finally {
                if (ownsRequest()) {
                    genreDirectoryLoading = false
                    _browse.update { it.withSearchTagDirectory() }
                }
            }
            if (ownsRequest()) refreshNewTagBindings(scope)
        }
    }

    private fun BrowseState.withCurrentTagBindings(): BrowseState {
        if (searchTarget != SearchTarget.TAG || selectedTags.isEmpty()) return this
        val choices = availableTags.associateBy(MangaTag::path)
        return copy(selectedTags = selectedTags.map { selected ->
            val observed = choices[selected.path] ?: availableTags.singleOrNull { tag ->
                selected.providerPaths.any { (source, native) -> tag.providerPaths[source] == native }
            } ?: return@map selected
            observed.copy(path = selected.path, providerPaths = observed.providerPaths + selected.providerPaths)
        })
    }

    /** Add newly known native filters to the still-current selection, rechecking only changed lanes. */
    private suspend fun refreshNewTagBindings(scope: String) {
        val original = _browse.value
        if (original.withCurrentTagBindings().cacheKey == original.cacheKey) return
        while (original.cacheKey == _browse.value.cacheKey) {
            val active = browseJob?.takeIf { it.isActive } ?: break
            active.join()
        }
        val current = _browse.value
        if (current.cacheKey != original.cacheKey || scope != repository.ordinaryGenreScope()) return
        val refreshed = current.withCurrentTagBindings()
        if (refreshed.cacheKey == current.cacheKey) return
        val affected = current.selectedTags.zip(refreshed.selectedTags).flatMap { (old, new) ->
            val before = old.additionalSearchPaths()
            val after = new.additionalSearchPaths()
            (before.keys + after.keys).filter { before[it] != after[it] }
        }.toSet()
        if (affected.isEmpty()) return
        val request = ++browseRequestId
        val generation = browseCache.generation
        _browse.value = refreshed.copy(loading = current.items.isEmpty(), refreshing = current.items.isNotEmpty(),
            error = null, failedPage = null)
        browseJob = viewModelScope.launch {
            refreshFederatedRange(refreshed, request, generation, forceRefresh = false, onlySources = affected)
        }
    }

    private fun resetGenreDirectory() {
        ++genreDirectoryRequest
        genreDirectoryJob?.cancel()
        genreDirectory = MangaGenreDirectory(emptyList())
        genreDirectoryScope = ""
        genreDirectoryUpdatedAt = 0L
        genreDirectoryLoading = false
        genreDirectoryError = null
    }

    /** Invalidation retires old work and errors; only a visible user action starts a new request. */
    private fun invalidateCopySearchDirectory() {
        cancelTagLookup()
        knownSearchTags = CopySearchTags
        copyExplore.clearCache()
        resetGenreDirectory()
        _browse.update { it.withSearchTagDirectory() }
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

    private fun showBrowse(selection: BrowseState, debounce: Boolean = false, confirmCopyGroupTerm: Boolean = false) {
        val previous = _browse.value
        if (!previous.isSearch) retainedBrowse = previous
        browseJob?.cancel()
        val requestId = ++browseRequestId
        val generation = browseCache.generation
        val configured = selection.copy(sourceScope = repository.sourceConfigurationKey(adult = false),
            alignedTagSources = emptyMap()).withSearchTagDirectory().withCurrentTagBindings()
        // Carry confirmation through this one selection only; live typing/search-mode changes retire it.
        confirmedCopyGroupKey = configured.cacheKey.takeIf { confirmCopyGroupTerm }
        val memory = browseCache.cached(configured.cacheKey)
        _browse.value = memory?.restore(configured) ?: configured
        if (configured.searchTarget == SearchTarget.TAG) loadSearchTagDirectories()
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
                        snapshot.restore(selectionWithCurrentFilter).copy(
                            tagsLoading = current.tagsLoading, tagsError = current.tagsError,
                        ).withSearchTagDirectory()
                    }
                }
                if (confirmCopyGroupTerm) rememberConfirmedCopyGroupResult(configured, snapshot.items.any { it.sourceId == "copy" })
                // Fresh selections need no request. Stale rankings refresh their complete
                // loaded range atomically, so a saved deep position never sees page zero alone.
                if (isFresh(snapshot.updatedAt, configured) && snapshot.searchSources.values.none { it.error != null }) return@launch
            }
            if (debounce) delay(450)
            if (requestId != browseRequestId) return@launch
            _browse.update { it.copy(loading = snapshot == null, refreshing = snapshot != null) }
            loadBrowse(_browse.value, 0, requestId, generation)
        }
    }

    /** Direct tag membership, pagination and BrowseCache keep their upstream results.
     * Supplement those visible cards with exact links already proven by the existing work owner. */
    private suspend fun observeAlignedTagSources() {
        val identities = _browse.map { it.tagSearchIdentities() }.distinctUntilChanged()
        combine(identities, unifiedChapters.revision, repository.sourceDiscovery,
            repository.sourceErrors, services.sourceConfiguration) { selection, _, discovery, failures, configuration ->
            val aligned = if (selection == null || selection.sourceScope != repository.sourceConfigurationKey(adult = false)) emptyMap()
            else withContext(Dispatchers.IO) {
                val eligible = repository.enabledSources()
                val direct = selection.groups.values.flatten()
                val alreadyShown = direct.mapTo(mutableSetOf(), ::federatedMangaIdentity)
                val saved = try {
                    unifiedChapters.alignedSearchLinks(direct.mapTo(mutableSetOf(), Manga::id))
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    emptyMap()
                }
                buildMap {
                    for ((id, group) in selection.groups) {
                        val versions = group.flatMap { work ->
                            val states = discovery[work.id].orEmpty()
                            val errors = failures[work.id].orEmpty()
                            saved[work.id].orEmpty().filter { version ->
                                val source = version.sourceId
                                source in eligible && version.id !in errors &&
                                    states[source]?.status.let { it == null || it == SourceDiscoveryStatus.LINKED } &&
                                    federatedMangaIdentity(version) !in alreadyShown
                            }
                        }.distinctBy(::federatedMangaIdentity)
                        if (versions.isNotEmpty()) put(id, versions)
                    }
                }
            }
            Triple(selection, configuration, aligned)
        }.collect { (selection, configuration, aligned) ->
            _browse.update { current ->
                if (configuration == services.sourceConfiguration.value && current.tagSearchIdentities() == selection)
                    current.copy(alignedTagSources = aligned)
                else current
            }
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

    /** A visible mobile feed may stage one page. Only the normal release/footer action publishes it. */
    internal suspend fun prepareDiscoveryPage(expected: BrowseState) {
        if (expected.isSearch || expected.items.isEmpty() || !expected.hasMore) return
        val request = browseRequestId
        val generation = browseCache.generation
        val route = DownloadRoutingStore.sourceRevision("copy")
        val configuration = services.sourceConfiguration.value
        val sameSource = { configuration == services.sourceConfiguration.value &&
            route == DownloadRoutingStore.sourceRevision("copy") }
        val matches = {
            val current = _browse.value
            request == browseRequestId && generation == browseCache.generation && sameSource() &&
                !current.isSearch && current.cacheKey == expected.cacheKey && current.page == expected.page &&
                current.hasMore && !current.loading && !current.refreshing && !current.loadingMore &&
                current.error == null && current.failedPage == null
        }
        if (!matches() || lastDiscoveryPreparation == request) return
        var owned: PreparedDiscoveryPage? = null
        try {
            val ready = withTimeoutOrNull(12_000L) {
                ExperiencePolicyStore.get(application).withBrowseWarmupAdmission("copy", estimatedBytes = SourceHttp.MAX_TEXT_BYTES) {
                    if (!matches() || lastDiscoveryPreparation == request) return@withBrowseWarmupAdmission false
                    preparedDiscoveryPage?.result?.cancel()
                    lastDiscoveryPreparation = request
                    val result = viewModelScope.async(start = CoroutineStart.LAZY) {
                        foregroundRequest("漫画列表") {
                            community.browse(expected.mode, expected.period, expected.audience, expected.page + 1)
                        }
                    }
                    val slot = PreparedDiscoveryPage(result, matches, sameSource)
                    owned = slot
                    preparedDiscoveryPage = slot
                    result.await()
                    true
                }
            }
            if (ready == true) delay(30_000L)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // A speculative failure leaves the current list and the normal demand path intact.
        } finally {
            if (owned != null && preparedDiscoveryPage === owned) {
                preparedDiscoveryPage = null
                owned?.result?.cancel()
            }
        }
    }

    fun loadMore() {
        val state = _browse.value
        if (state.loading || state.refreshing || state.loadingMore || !state.hasMore || state.error != null) return
        val staged = preparedDiscoveryPage
        preparedDiscoveryPage = null
        val prepared = staged?.takeIf { it.matches() && !it.result.isCancelled }
        if (prepared == null) staged?.result?.cancel()
        _browse.update { it.copy(loadingMore = true, error = null, failedPage = null) }
        val requestId = ++browseRequestId
        val generation = browseCache.generation
        browseJob = viewModelScope.launch {
            try { loadBrowse(state, state.page + 1, requestId, generation, prepared = prepared) }
            finally { prepared?.result?.cancel() }
        }
    }

    fun retryBrowse() {
        val state = _browse.value
        if (state.loading || state.refreshing || state.loadingMore) return
        if (state.isFederatedSearch && state.page > 0 && state.fromCache && state.searchSources.values.any { it.error != null }) {
            val failedSources = state.searchSources.values.filter { it.error != null }.map { it.sourceId }.toSet()
            val requestId = ++browseRequestId
            val generation = browseCache.generation
            browseJob?.cancel()
            _browse.update { it.copy(refreshing = true, error = null) }
            browseJob = viewModelScope.launch {
                refreshFederatedRange(state, requestId, generation, forceRefresh = true, onlySources = failedSources)
            }
            return
        }
        if (state.isFederatedSearch && state.failedPage != 0 && state.searchSources.values.any { it.error != null }) {
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
        forceRefresh: Boolean = false, prepared: PreparedDiscoveryPage? = null) {
        if (state.isFederatedSearch) {
            if (page == 0 && state.page > 0 && state.items.isNotEmpty()) {
                refreshFederatedRange(state, requestId, generation, forceRefresh)
                return
            }
            loadFederatedBrowse(state, page, requestId, generation, forceRefresh = forceRefresh)
            return
        }
        try {
            val (result, replacement, loadedPage) = foregroundRequest("漫画列表") {
                val query = state.query.trim()
                var result = when {
                    prepared != null -> prepared.result.await().also {
                        if (!prepared.sameSource()) throw SourceException("来源或线路已变更，请重新加载。")
                    }
                    !state.isSearch -> community.browse(state.mode, state.period, state.audience, page)
                    else -> repository.search(query, state.searchTarget, state.selectedTags, page)
                }
                val replacement = result.items.toMutableList()
                var loadedPage = result.nextPage?.minus(1) ?: page
                if (page == 0 && state.items.isNotEmpty()) {
                    // Never combine a new first page with old ranking pages. Keep the old list
                    // visible until the previously loaded range (or the new source end) is ready.
                    while (loadedPage < state.page && result.hasMore) {
                        if (requestId != browseRequestId || generation != browseCache.generation) throw CancellationException("Superseded browse")
                        val nextPage = ++loadedPage
                        result = if (!state.isSearch) community.browse(state.mode, state.period, state.audience, nextPage)
                            else repository.search(query, state.searchTarget, state.selectedTags, nextPage)
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
                    fromCache = false,
                    hasMore = result.hasMore, page = loadedPage,
                    updatedAt = if (page == 0 || current.updatedAt <= 0) wallTimeMillis() else current.updatedAt,
                )
            }
            val current = _browse.value
            if (requestId == browseRequestId && current.cacheKey == state.cacheKey) {
                if (current.searchTarget == SearchTarget.TRANSLATION_GROUP)
                    rememberConfirmedCopyGroupResult(current, replacement.any { it.sourceId == "copy" })
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
            items = items, page = page, hasMore = hasMore, updatedAt = updatedAt, fromCache = true,
            loading = false, loadingMore = false, refreshing = false,
            error = if (incomplete) "部分来源暂时不可用，可重试；其他来源没有返回匹配作品。" else null,
            failedPage = page.takeIf { incomplete },
            sourceMatches = sourceMatches, sourceNextPages = sourceNextPages, sourceChoiceIds = sourceChoiceIds,
            searchSources = searchSources,
        )
    }

    /** Revalidate a restored deep result as one range; never splice fresh page zero into old pages. */
    private suspend fun refreshFederatedRange(state: BrowseState, requestId: Long, generation: Long,
        forceRefresh: Boolean, onlySources: Set<String>? = null) {
        fun current() = requestId == browseRequestId && generation == browseCache.generation &&
            _browse.value.cacheKey == state.cacheKey
        try {
            val hits = linkedMapOf<Pair<String, String>, Manga>()
            val sources = state.searchSources.filterKeys { onlySources != null && it !in onlySources }.toMutableMap()
            val failed = mutableSetOf<String>()
            val queried = mutableSetOf<String>()
            var requested: Map<String, Int>? = onlySources?.associateWith { 0 }
            var loadedRound = 0
            for (round in 0..state.page) {
                if (!current()) throw CancellationException("Superseded browse")
                val result = repository.searchAll(state.query.trim(), requested, forceRefresh = forceRefresh,
                    target = state.searchTarget, selectedTags = state.selectedTags)
                if (!current()) throw CancellationException("Superseded browse")
                queried += result.sources.keys
                failed += result.sources.values.filter { it.error != null }.map { it.sourceId }
                result.hits.forEach { hits[dev.mangaflow.data.federatedMangaIdentity(it)] = it }
                sources.putAll(result.sources)
                loadedRound = round
                requested = result.sources.values.filter { it.error == null && it.hasMore && it.nextPage != null }
                    .associate { it.sourceId to it.nextPage!! }
                if (requested.isEmpty()) break
            }
            val oldHits = state.sourceMatches.values.flatten().ifEmpty { state.items }
            val retained = oldHits.filter {
                val source = dev.mangaflow.data.federatedMangaIdentity(it).first
                source in failed || source !in queried
            }
            // A failed source keeps its previous complete range. Healthy sources replace
            // their own whole ranges, so one unavailable site cannot freeze every other site.
            val replacements = hits.values.filter {
                val source = dev.mangaflow.data.federatedMangaIdentity(it).first
                source !in failed || retained.none { old -> dev.mangaflow.data.federatedMangaIdentity(old).first == source }
            }
            val groups = withContext(Dispatchers.Default) {
                FederatedSearchGrouper(state.query, exactTitleFirst = state.searchTarget == SearchTarget.TITLE)
                    .group((retained + replacements).distinctBy(::federatedMangaIdentity), state.sourceMatches)
            }
            if (!current()) return
            val next = sources.values.filter { it.error == null && it.hasMore && it.nextPage != null }
                .associate { it.sourceId to it.nextPage!! }
            val refreshed = _browse.value.copy(items = groups.map { it.manga },
                sourceMatches = groups.associate { it.manga.id to it.matches },
                sourceChoiceIds = groups.filter { it.needsChoice }.map { it.manga.id }.toSet(),
                searchSources = sources.toMap(), sourceNextPages = next, hasMore = next.isNotEmpty(),
                page = if (onlySources == null) loadedRound else maxOf(state.page, loadedRound),
                loading = false, refreshing = false, loadingMore = false,
                error = if (failed.isEmpty()) null else "部分来源更新失败，保留这些来源的旧列表：" +
                    sources.values.filter { it.sourceId in failed }.joinToString("；") { it.sourceName + "：" + it.error },
                failedPage = if (failed.isEmpty()) null else 0, updatedAt = wallTimeMillis(), fromCache = retained.isNotEmpty())
            _browse.value = refreshed
            val snapshot = BrowseSnapshot(refreshed.cacheKey, refreshed.items, refreshed.page, refreshed.hasMore,
                refreshed.updatedAt, refreshed.sourceMatches, refreshed.sourceNextPages,
                refreshed.sourceChoiceIds, refreshed.searchSources)
            browseCache.remember(snapshot, generation)
            browseCache.write(snapshot, generation)
        } catch (cancel: CancellationException) { throw cancel
        } catch (failure: Exception) {
            if (current()) _browse.update { it.copy(loading = false, refreshing = false, loadingMore = false,
                error = "更新未完成，保留此前列表。" + (failure.message ?: "请重试。"), failedPage = 0,
                fromCache = it.items.isNotEmpty()) }
        }
    }

    private suspend fun loadFederatedBrowse(state: BrowseState, page: Int, requestId: Long, generation: Long,
        requestedPages: Map<String, Int>? = if (page == 0) null else state.sourceNextPages,
        retain: Boolean = page > 0, forceRefresh: Boolean = false) {
        val previousHits = if (retain) state.sourceMatches.values.flatten().ifEmpty { state.items } else emptyList()
        val grouper = withContext(Dispatchers.Default) {
            FederatedSearchGrouper(state.query, exactTitleFirst = state.searchTarget == SearchTarget.TITLE)
        }
        var groupedHits: List<Manga>? = null
        var groups: List<FederatedSearchGroup> = emptyList()
        try {
            repository.searchAll(state.query.trim(), requestedPages, forceRefresh = forceRefresh,
                target = state.searchTarget, selectedTags = state.selectedTags) { partial ->
                if (requestId != browseRequestId || generation != browseCache.generation) return@searchAll
                val hits = (previousHits + partial.hits).distinctBy(::federatedMangaIdentity)
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
                            fromCache = if (keepExisting) current.fromCache else false,
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
        val ttl = contentCachePreferences(application).freshMillis(when {
            state.isSearch -> ContentCacheKind.SEARCH
            state.mode == DiscoveryMode.RANKING -> ContentCacheKind.RANKING
            else -> ContentCacheKind.DISCOVERY
        })
        return wallTimeMillis() - updatedAt in 0 until ttl
    }

    suspend fun browseCacheSize(): Long = browseCache.sizeBytes() + siteRankingsRepository.cacheSizeBytes()

    suspend fun clearBrowseCache() {
        browseJob?.cancel()
        browseRequestId++
        invalidateCopySearchDirectory()
        _browse.update { it.copy(loading = false, refreshing = false, loadingMore = false,
            error = null, failedPage = null, updatedAt = 0) }
        browseCache.clear()
        siteRankings.clearCache()
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
                            publishMetrics(manga.id, metricsRepository.latestMetrics(manga.id))
                        }
                    }
                }
                if (!clearingMetadata && requestId == metricsRequestId &&
                    metricJobs[manga.id] === job && job.isActive) {
                    publishMetrics(manga.id, metricsRepository.latestMetrics(manga.id))
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
        _detail.value?.takeIf { it.manga.id == mangaId &&
            (_reading.value == null || _reading.value?.manga?.id == mangaId) }?.let {
            discoverWorkSources(it.manga, metadataOnly = true)
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
        if (_detail.value?.rankedWork != null) return
        if (clearingMetadata || _detail.value?.manga?.let { isAdultSource(it.sourceId) } == true) return
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

    /** Native catalogue records never enter Copy's body/catalogue requests. */
    fun openRankedWork(work: SiteRankedWork) = openRankedWork(work, false)

    fun inspectRankedWork(work: SiteRankedWork) = openRankedWork(work, true)

    private fun rankedSourceEnabled(work: SiteRankedWork, manga: Manga): Boolean =
        siteRankingsRepository.acceptsSource(work, manga) && bodySources.entries.value.any {
            it.source.id == manga.sourceId && it.enabled && it.isInstalled && !it.adult && !it.removed
        }

    private fun openRankedWork(work: SiteRankedWork, chooseSource: Boolean) {
        // A catalogue route owns no old reader, chapter request or source overlay.
        closeReader()
        closeDetail()
        val token = ++rankedWorkRequest
        _detail.value = DetailState(work.manga, loading = false, reviewsLoading = false,
            rankedWork = work, rankedSourcesLoading = true)
        rankedWorkJob = viewModelScope.launch {
            val saved = siteRankings.cachedLink(work) ?: siteRankingsRepository.linked(work)
            if (!rankedWorkCurrent(token, work)) return@launch
            _detail.update { it?.copy(rankedLinkedSource = saved) }
            if (!chooseSource && saved != null && rankedSourceEnabled(work, saved)) {
                _detail.update { it?.copy(rankedSources = listOf(RankedReadingSource(saved, true)),
                    rankedSourcesLoading = false) }
                if (connectRankedSource(work, saved, token, confirmed = saved)) return@launch
                if (!rankedWorkCurrent(token, work)) return@launch
            }
            val enriched = try { withTimeout(8_000L) { siteRankingsRepository.enrich(work) } }
            catch (cancel: CancellationException) {
                if (cancel is kotlinx.coroutines.TimeoutCancellationException) work else throw cancel
            }
            catch (_: Exception) { work }
            if (!rankedWorkCurrent(token, work)) return@launch
            _detail.update { current -> if (current?.rankedWork?.key == work.key)
                current.copy(manga = enriched.manga, rankedWork = enriched, rankedSourcesLoading = true) else current }
            findRankedSources(enriched, enriched.manga.title.take(120), token)
        }
    }

    fun searchRankedSources(workKey: String, query: String) {
        val work = _detail.value?.rankedWork?.takeIf { it.key == workKey } ?: return
        val title = query.trim()
        if (title.length !in 1..120) return
        val token = ++rankedWorkRequest
        rankedWorkJob?.cancel()
        _detail.update { it?.copy(rankedSources = emptyList(), rankedSourcesLoading = true,
            rankedSourcesError = null, rankedOpeningSourceId = null, rankedSourceError = null, rankedSourcePreview = null) }
        rankedWorkJob = viewModelScope.launch { findRankedSources(work, title, token) }
    }

    private fun rankedWorkCurrent(token: Long, work: SiteRankedWork): Boolean =
        token == rankedWorkRequest && _detail.value?.rankedWork?.key == work.key

    private suspend fun findRankedSources(work: SiteRankedWork, query: String, token: Long) {
        val enabled = bodySources.enabledSourceIds
        val sources = bodySources.entries.value.filter {
            it.source.id in enabled && it.isInstalled && !it.adult && !it.removed
        }
        if (sources.isEmpty()) {
            if (rankedWorkCurrent(token, work)) _detail.update { it?.copy(rankedSourcesLoading = false,
                rankedSourcesError = "请先在设置中启用阅读来源。") }
            return
        }
        val slots = Semaphore(2)
        val failures = mutableListOf<String>()
        val failureLock = Mutex()
        withContext(Dispatchers.IO) {
            supervisorScope {
                sources.map { source -> async {
                    slots.withPermit {
                        if (!rankedWorkCurrent(token, work) || source.source.id !in bodySources.enabledSourceIds)
                            return@withPermit
                        // Keep successful earlier attempts even when a later alias times out.
                        val collected = mutableListOf<Manga>()
                        try {
                            withTimeout(8_000L) {
                                val terms = bodySourceSearchTerms(query, work.manga.alternativeTitles).take(3)
                                for (term in terms) {
                                    val found = repository.searchSource(source.source.id, term, 0).items
                                    collected.addAll(found)
                                    val queryIdentity = work.manga.copy(title = term, alternativeTitles = emptyList())
                                    if (found.any {
                                            SourceDiscoveryIdentity.titleMatches(work.manga, it, work.manga.alternativeTitles) ||
                                                SourceDiscoveryIdentity.titleMatches(queryIdentity, it, emptyList())
                                        }) break
                                }
                            }
                        } catch (cancel: CancellationException) {
                            if (cancel !is kotlinx.coroutines.TimeoutCancellationException) throw cancel
                            failureLock.withLock { failures.add(source.source.name + if (collected.isNotEmpty()) "搜索超时，可继续选择已找到的结果" else "搜索超时，请重试") }
                        } catch (failure: Exception) {
                            val reason = (failure as? SourceException)?.message?.take(160)
                                ?: "暂时无法完成搜索"
                            failureLock.withLock { failures.add(source.source.name + "：" + reason) }
                        }
                        if (!rankedWorkCurrent(token, work) || source.source.id !in bodySources.enabledSourceIds)
                            return@withPermit
                        val candidates = collected.distinctBy(Manga::id)
                            .filter { it.sourceId == source.source.id && siteRankingsRepository.acceptsSource(work, it) }
                            .sortedByDescending {
                                SourceDiscoveryIdentity.titleMatches(work.manga, it, work.manga.alternativeTitles)
                            }.take(12).map { it.copy(sourceName = source.source.name) }
                        val exact = candidates.filter {
                            SourceDiscoveryIdentity.workMatches(work.manga, it,
                                work.manga.alternativeTitles, sameChapterSystem = false)
                        }
                        // Several editions remain explicit choices; query spelling alone is never identity proof.
                        val confirmed = exact.singleOrNull()?.id
                        val rows = candidates.map { RankedReadingSource(it, it.id == confirmed,
                            it.id == confirmed || rankedCandidateRelevant(work, query, it)) }
                        if (rows.isNotEmpty()) _detail.update { current ->
                            if (current?.rankedWork?.key != work.key || token != rankedWorkRequest) current
                            else current.copy(rankedSources = (current.rankedSources + rows).distinctBy { it.manga.id })
                        }
                    }
                } }.forEach { it.await() }
            }
        }
        if (!rankedWorkCurrent(token, work)) return
        _detail.update { it?.copy(rankedSourcesLoading = false,
            rankedSourcesError = failures.takeIf { list -> list.isNotEmpty() }?.joinToString("；")) }
    }

    /** Work identity is supplied by the displayed pane, never inferred from a newer global route. */
    fun useRankedSource(workKey: String, manga: Manga) {
        val state = _detail.value ?: return
        val work = state.rankedWork?.takeIf { it.key == workKey } ?: return
        if (state.rankedOpeningSourceId != null) return
        val selected = state.rankedSources.firstOrNull {
            it.manga.id == manga.id && it.manga.sourceId == manga.sourceId
        }?.manga ?: state.rankedLinkedSource?.takeIf { it.id == manga.id && it.sourceId == manga.sourceId } ?: return
        if (!rankedSourceEnabled(work, selected)) {
            _detail.update { it?.copy(rankedSourceError = "此来源已停用或作品编号有冲突，请重新选择。") }
            return
        }
        rankedWorkJob?.cancel()
        val token = ++rankedWorkRequest
        _detail.update { it?.copy(rankedSourcesLoading = false, rankedSourceError = null, rankedSourcePreview = null) }
        val confirmed = state.rankedLinkedSource?.takeIf { it.id == selected.id && it.sourceId == selected.sourceId }
        rankedWorkJob = viewModelScope.launch { connectRankedSource(work, selected, token, confirmed) }
    }

    private suspend fun connectRankedSource(work: SiteRankedWork, selected: Manga, token: Long, confirmed: Manga? = null): Boolean {
        if (!rankedWorkCurrent(token, work)) return false
        if (!rankedSourceEnabled(work, selected)) {
            _detail.update { it?.copy(rankedSourceError = "此来源已停用，请重新选择。") }
            return false
        }
        _detail.update { it?.copy(rankedOpeningSourceId = selected.id, rankedSourceError = null) }
        try {
            // Reuse the real source catalogue/cache once. Nothing blocks scrolling, Back or a new search.
            val cached = withContext(Dispatchers.Default) {
                repository.cachedCatalog(selected.id)?.takeIf { it.fresh }?.detail
            }
            val retained = if (cached == null) readerCatalogs.read(selected.id)?.takeIf {
                siteRankingsRepository.sameSourceResult(work, selected, it.manga)
            } else null
            val catalogue = cached ?: retained ?: withTimeout(35_000L) { repository.detail(selected.id) }
            val canonical = catalogue.manga.copy(sourceName = bodySources.entries.value
                .firstOrNull { it.source.id == selected.sourceId }?.source?.name
                ?: selected.sourceName.ifBlank { sourceDisplayName(selected.sourceId) })
            if (!rankedWorkCurrent(token, work)) return false
            if (!rankedSourceEnabled(work, selected)) {
                _detail.update { it?.copy(rankedSourceError = "此来源已停用，请重新选择。") }
                return false
            }
            if (!siteRankingsRepository.sameSourceResult(work, selected, canonical)) {
                try { siteRankingsRepository.forgetLink(work, selected.id) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* Future opens still validate the canonical source before routing. */ }
                if (rankedWorkCurrent(token, work)) {
                    siteRankings.forgetLink(work)
                    _detail.update { current -> current?.copy(rankedLinkedSource = null,
                        rankedSources = current.rankedSources.filterNot { it.manga.id == selected.id },
                        rankedSourcePreview = null,
                        rankedSourceError = "来源返回的作品与所选结果不一致，已停止关联。请重新选择。") }
                }
                return false
            }
            if (cached == null && retained == null) {
                try { readerCatalogs.save(catalogue) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* Optional catalogue persistence cannot block a valid source. */ }
            }
            val nativeMatch = SourceDiscoveryIdentity.workMatches(work.manga, canonical,
                work.manga.alternativeTitles, sameChapterSystem = false)
            if (!nativeMatch && (confirmed == null || !siteRankingsRepository.sameSourceResult(work, confirmed, canonical))) {
                if (rankedWorkCurrent(token, work)) _detail.update {
                    it?.copy(rankedSourcePreview = RankedSourcePreview(selected, canonical, token))
                }
                return false
            }
            var stored = false
            try {
                stored = siteRankingsRepository.rememberLink(work, selected, canonical) {
                    rankedWorkCurrent(token, work) && rankedSourceEnabled(work, selected)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { messages.trySend("阅读来源尚未保存，下次可能需要重新选择。") }
            if (!rankedWorkCurrent(token, work)) return false
            if (!rankedSourceEnabled(work, canonical)) {
                _detail.update { it?.copy(rankedSourceError = "此来源已停用，请重新选择。") }
                return false
            }
            if (stored) siteRankings.recordLink(work, canonical)
            // The destination owns its new detail job. Never cancel this handoff from inside itself.
            rankedWorkJob = null
            openManga(canonical, null, false, work)
            return true
        } catch (cancel: CancellationException) {
            if (cancel !is kotlinx.coroutines.TimeoutCancellationException) throw cancel
            if (rankedWorkCurrent(token, work)) _detail.update { it?.copy(
                rankedSourceError = "读取来源超时，可以重试或选择其他来源。") }
        } catch (failure: Exception) {
            if (rankedWorkCurrent(token, work)) _detail.update { it?.copy(
                rankedSourceError = failure.message ?: "暂时无法读取这个来源，请重试或更换。") }
        } finally {
            if (rankedWorkCurrent(token, work)) _detail.update { it?.copy(rankedOpeningSourceId = null) }
        }
        return false
    }

    fun confirmRankedSource(workKey: String, sourceId: String, request: Long) {
        val state = _detail.value ?: return
        val work = state.rankedWork?.takeIf { it.key == workKey } ?: return
        val preview = state.rankedSourcePreview?.takeIf {
            it.request == request && request == rankedWorkRequest && it.canonical.id == sourceId
        } ?: return
        rankedWorkJob?.cancel()
        val token = ++rankedWorkRequest
        _detail.update { it?.copy(rankedSourcePreview = preview.copy(request = token), rankedSourceError = null) }
        rankedWorkJob = viewModelScope.launch { connectRankedSource(work, preview.selected, token, preview.canonical) }
    }

    fun cancelRankedSourcePreview(workKey: String, request: Long) {
        _detail.update { state ->
            if (state?.rankedWork?.key == workKey && state.rankedSourcePreview?.request == request)
                state.copy(rankedSourcePreview = null) else state
        }
    }

    fun clearRankedSource(workKey: String) {
        val work = _detail.value?.rankedWork?.takeIf { it.key == workKey } ?: return
        rankedWorkJob?.cancel()
        val token = ++rankedWorkRequest
        _detail.update { it?.copy(rankedOpeningSourceId = null, rankedSourceError = null, rankedSourcesLoading = false, rankedSourcePreview = null) }
        rankedWorkJob = viewModelScope.launch {
            try {
                siteRankingsRepository.forgetLink(work)
                if (!rankedWorkCurrent(token, work)) return@launch
                siteRankings.forgetLink(work)
                _detail.update { it?.copy(rankedLinkedSource = null) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (rankedWorkCurrent(token, work)) _detail.update { it?.copy(
                    rankedSourceError = failure.message ?: "阅读关联未能移除，请重试。") }
            }
        }
    }

    fun openManga(manga: Manga, continueChapterId: String? = null, forceRefresh: Boolean = false) =
        openManga(manga, continueChapterId, forceRefresh,
            _detail.value?.takeIf { it.manga.id == manga.id }?.rankedSourceWork)

    private fun openManga(manga: Manga, continueChapterId: String?, forceRefresh: Boolean, rankedFrom: SiteRankedWork?) {
        rankedWorkRequest++
        rankedWorkJob?.cancel()
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
        val initialManga = (if (rankedFrom != null) manga else previous?.manga ?: manga).let {
            val totalHeat = it.totalHeat() ?: manga.totalHeat()
            it.copy(popularity = totalHeat, totalPopularity = totalHeat, rank = null)
        }
        // Navigation starts before a potentially large multi-source projection is built.
        _detail.value = DetailState(initialManga, detail = previous?.detail,
            reviews = previous?.reviews.orEmpty(), loading = true, rankedSourceWork = rankedFrom)
        detailJob = viewModelScope.launch {
            fun checkRankedIdentity(candidate: Manga) {
                if (rankedFrom != null && !siteRankingsRepository.sameSourceResult(rankedFrom, manga, candidate))
                    throw RankedSourceIdentityMismatch()
            }
            var supplementaryStarted = false
            var restoringDirectory: Job? = null
            try {
                val offline = downloadedChapters(manga.id)
                val (snapshot, retained, initialChapters) = cachedDetailForDisplay(manga.id, previous?.detail, offline)
                val cached = snapshot?.detail
                if (requestId != detailRequestId) return@launch
                val restoredManga = (retained?.manga ?: initialManga).let {
                    val totalHeat = it.totalHeat() ?: manga.totalHeat()
                    it.copy(popularity = totalHeat, totalPopularity = totalHeat, rank = null)
                }
                checkRankedIdentity(restoredManga)
                if (cached == null) repository.retainWork(MangaDetail(restoredManga, initialChapters))
                val reuseCatalog = cached != null && cached.chapters.isNotEmpty() && !forceRefresh &&
                    snapshot?.fresh == true &&
                    (manga.latestChapter.isBlank() || manga.latestChapter == cached.manga.latestChapter) &&
                    (continueChapterId == null || findSourceChapter(initialChapters, continueChapterId) != null)
                _detail.update { current ->
                    if (requestId != detailRequestId || current?.manga?.id != manga.id) current
                    else current.copy(manga = restoredManga,
                        detail = initialChapters.takeIf { it.isNotEmpty() }?.let { MangaDetail(restoredManga, it) },
                        loading = !reuseCatalog)
                }
                discoverWorkSources(restoredManga)
                loadReviews(restoredManga, requestId)
                supplementaryStarted = true
                if (reuseCatalog) {
                    if (continueChapterId != null) findSourceChapter(initialChapters, continueChapterId)?.let(::readChapter)
                    return@launch
                }
                if (retained == null) {
                    suspend fun restoreDirectory() {
                        val stored = readerCatalogs.read(manga.id) ?: return
                        if (requestId != detailRequestId) return
                        checkRankedIdentity(stored.manga)
                        repository.retainWork(stored)
                        _detail.update { current ->
                            if (current?.manga?.id != manga.id || requestId != detailRequestId || !current.loading) current
                            else current.copy(detail = MangaDetail(current.manga,
                                retainedChapters(stored.chapters, downloadedChapters(manga.id))))
                        }
                    }
                    // An ordinary direct opening can show its saved directory while the source is loading.
                    // Ranked associations and adult source projections retain their identity-first ordering.
                    if (rankedFrom != null || isAdultSource(manga.sourceId)) restoreDirectory() else restoringDirectory = launch {
                        try { restoreDirectory() }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { /* The source still owns this opening when the optional copy is unreadable. */ }
                    }
                    if (requestId != detailRequestId) return@launch
                }
                val loaded = repository.detail(manga.id) { metadata ->
                    checkRankedIdentity(metadata)
                    _detail.update { current ->
                        if (current?.manga?.id != manga.id || requestId != detailRequestId) current
                        else {
                            val totalHeat = metadata.totalHeat() ?: current.manga.totalHeat()
                            val updatedManga = metadata.copy(popularity = totalHeat, totalPopularity = totalHeat, rank = null)
                            current.copy(manga = updatedManga, detail = current.detail?.copy(manga = updatedManga),
                                reviews = current.reviews.map { review -> if (review.id == "copy")
                                    review.copy(popularity = totalHeat ?: review.popularity) else review })
                        }
                    }
                }
                if (requestId != detailRequestId) return@launch
                checkRankedIdentity(loaded.manga)
                restoringDirectory?.cancel()
                _detail.update { current ->
                    if (requestId != detailRequestId || current?.manga?.id != manga.id) current
                    else {
                        val totalHeat = loaded.manga.totalHeat() ?: current.manga.totalHeat()
                        val updatedManga = loaded.manga.copy(popularity = totalHeat, totalPopularity = totalHeat, rank = null)
                        val chapters = projectionChapters(manga.id, loaded.chapters,
                            current.detail?.chapters.orEmpty(), downloadedChapters(manga.id), primaryObserved = true)
                        current.copy(manga = updatedManga,
                            detail = loaded.copy(manga = updatedManga, chapters = chapters), loading = false)
                    }
                }
                if (continueChapterId != null) {
                    val chapter = _detail.value?.detail?.chapters?.let { findSourceChapter(it, continueChapterId) }
                    if (chapter != null) readChapter(chapter)
                    else messages.send("阅读记录中的章节不在当前目录中，请从目录选择章节。")
                }
                readerCatalogs.save(loaded)
                if (requestId != detailRequestId) return@launch
                discoverWorkSources(loaded.manga)
                recordDetailMetrics(manga.id, requestId)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                // A fast network error must not drag an available offline directory out with it.
                restoringDirectory?.join()
                if (failure is RankedSourceIdentityMismatch && rankedFrom != null) {
                    if (requestId != detailRequestId) return@launch
                    try { siteRankingsRepository.forgetLink(rankedFrom, manga.id) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { /* The route guard remains in force even if local removal fails. */ }
                    if (requestId != detailRequestId) return@launch
                    siteRankings.forgetLink(rankedFrom)
                    detailJob = null
                    inspectRankedWork(rankedFrom)
                    _detail.update { current ->
                        if (current?.rankedWork?.key == rankedFrom.key)
                            current.copy(rankedSourceError = "来源资料与所选作品不一致，已停止打开。请更换来源。") else current
                    }
                    return@launch
                }
                if (!supplementaryStarted && requestId == detailRequestId) {
                    _detail.value?.manga?.let { current ->
                        discoverWorkSources(current)
                        loadReviews(current, requestId)
                    }
                }
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

    /** Keep the view's cached projection off Main and reject concurrent source-setting changes. */
    private suspend fun cachedDetailForDisplay(mangaId: String, previous: MangaDetail?,
        offline: List<Chapter>): Triple<UnifiedMangaRepository.CatalogSnapshot?, MangaDetail?, List<Chapter>> {
        while (true) {
            val settings = unifiedChapters.revision.value
            val catalogs = repository.catalogRevision.value
            val prepared = withContext(Dispatchers.Default) {
                val cached = repository.cachedCatalog(mangaId)
                val retained = cached?.detail?.takeIf { it.chapters.isNotEmpty() }
                    ?: previous?.takeIf { it.chapters.isNotEmpty() }
                Triple(cached, retained, retainedChapters(retained?.chapters.orEmpty(), offline))
            }
            if (settings == unifiedChapters.revision.value && catalogs == repository.catalogRevision.value) return prepared
        }
    }

    private fun loadReviews(manga: Manga, requestId: Long) {
        if (isAdultSource(manga.sourceId)) {
            _detail.update { current ->
                if (requestId == detailRequestId && current?.manga?.id == manga.id)
                    current.copy(reviewsLoading = false) else current
            }
            return
        }
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
                    discoverWorkSources(it.manga, metadataOnly = true)
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
        if (source.status == ReviewStatus.READY) {
            _detail.value?.takeIf { it.manga.id == mangaId && requestId == detailRequestId &&
                (_reading.value == null || _reading.value?.manga?.id == mangaId) }?.let {
                discoverWorkSources(it.manga, metadataOnly = true)
            }
        }
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

    private fun retainedChapters(primary: List<Chapter>, extra: List<Chapter>): List<Chapter> {
        val present = buildSet {
            primary.forEach { chapter ->
                add(chapter.id)
                chapter.variants.forEach { add(it.storageId) }
            }
        }
        return (primary + extra.filter { it.id !in present })
            .distinctBy { it.logicalId.ifBlank { it.id } }.sortedBy(Chapter::index)
    }

    /** An unobserved source cannot erase its last directory while its link is still valid. */
    private fun projectionChapters(workId: String, primary: List<Chapter>, previous: List<Chapter>,
        offline: List<Chapter>, primaryObserved: Boolean): List<Chapter> {
        if (previous.isEmpty()) return retainedChapters(primary, offline)
        val installed = repository.sources.mapTo(mutableSetOf()) { it.id }
        val primarySource = SourceKeys.decodeManga(workId).sourceId
        val settings = unifiedChapters.settings(workId)
        val unobserved = buildSet {
            if (!primaryObserved) add(SourceKeys.decodeManga(workId))
            settings.links.forEach { link ->
                if (link.confirmedAt > 0L && link.manga.sourceId in installed &&
                    repository.sharesSourceProfile(primarySource, link.manga.sourceId) &&
                    repository.loadedSourceDetail(link.manga.id) == null) add(SourceKeys.decodeManga(link.manga.id))
            }
        }
        if (unobserved.isEmpty()) return retainedChapters(primary, offline)
        val retained = previous.mapNotNull { chapter ->
            val logicalId = chapter.logicalId.ifBlank { chapter.id }
            val variants = chapter.variants.filter { variant ->
                val id = variant.storageId
                val assigned = settings.manualLinks[id] ?: if (id in settings.separated)
                    ChapterSemantics.independentId(id) else logicalId
                assigned == logicalId && SourceMangaRef(variant.sourceId, variant.mangaId) in unobserved
            }
            if (chapter.variants.isNotEmpty()) {
                chapter.takeIf { variants.any { it.storageId == chapter.id } }?.copy(variants = variants)
            } else {
                val reference = runCatching { SourceKeys.decodeChapter(workId, chapter.id) }.getOrNull()
                chapter.takeIf { reference != null && it.sourceId == reference.sourceId &&
                    (it.sourceMangaId.isBlank() || it.sourceMangaId == reference.mangaId) &&
                    SourceMangaRef(reference.sourceId, reference.mangaId) in unobserved }
            }
        }
        return retainedChapters(primary, offline + retained)
    }

    private fun selectedChapters(chapters: List<Chapter>, selected: Chapter): List<Chapter> {
        val logical = selected.logicalId.ifBlank { selected.id }
        val group = chapters.indexOfFirst { it.logicalId.ifBlank { it.id } == logical ||
            it.variants.any { variant -> variant.storageId == selected.id } }
        return if (group >= 0) chapters.mapIndexed { index, chapter ->
            // Keep the open native source/cursor, but use the current directory's grouping.
            if (index == group) selected.copy(title = chapter.title, group = chapter.group,
                logicalId = chapter.logicalId.ifBlank { chapter.id }, variants = chapter.variants, index = chapter.index) else chapter
        } else (chapters + selected).distinctBy(Chapter::id).sortedBy(Chapter::index)
    }

    /** Reproject the view; choosing a source never writes a reading event or copies a page cursor. */
    private suspend fun refreshUnifiedProjection() {
        val request = ++projectionRequestId
        val detailEpoch = detailRequestId
        val readingEpoch = readingRequestId
        val detailWorkId = _detail.value?.takeIf { it.rankedWork == null }?.manga?.id
        val readingWorkId = _reading.value?.manga?.id
        val settingsRevision = unifiedChapters.revision.value
        val catalogRevision = repository.catalogRevision.value
        val workIds = listOfNotNull(detailWorkId, readingWorkId).distinct()
        val catalogs = withContext(Dispatchers.Default) {
            workIds.forEach(repository::reconcileSourceDiscovery)
            workIds.associateWith { repository.activeCatalog(it) }
        }
        fun currentProjection(): Boolean = request == projectionRequestId &&
            settingsRevision == unifiedChapters.revision.value && catalogRevision == repository.catalogRevision.value
        if (!currentProjection()) return
        var changedCatalog: Pair<MangaDetail, MangaDetail>? = null
        val currentDetail = _detail.value
        if (detailWorkId != null && detailRequestId == detailEpoch &&
            currentDetail != null && currentDetail.manga.id == detailWorkId) {
            catalogs[detailWorkId]?.let { snapshot ->
                val catalog = snapshot.detail
                val ranked = currentDetail.rankedSourceWork
                val display = catalog.copy(chapters = projectionChapters(detailWorkId, catalog.chapters,
                    currentDetail.detail?.chapters.orEmpty(), downloadedChapters(detailWorkId), snapshot.fresh))
                if (currentDetail.detail != display && (ranked == null ||
                    siteRankingsRepository.sameSourceResult(ranked, currentDetail.manga, catalog.manga))) {
                    _detail.value = currentDetail.copy(detail = display)
                    if (snapshot.fresh) changedCatalog = display to catalog
                }
            }
        }
        refreshSourceOverview()
        val session = _reading.value
        if (readingWorkId != null && readingRequestId == readingEpoch &&
            session != null && session.manga.id == readingWorkId) {
            catalogs[readingWorkId]?.let { snapshot ->
                // Read the visible source now: the reader can turn pages/chapters while
                // a background projection is being built, without a new reading epoch.
                val visible = visibleReading?.takeIf { it.first.id == session.manga.id }?.second
                    ?: findSourceChapter(session.chapters, session.chapterId)
                val chapters = projectionChapters(readingWorkId, snapshot.detail.chapters,
                    session.chapters, downloadedChapters(readingWorkId), snapshot.fresh)
                val projected = if (visible != null) selectedChapters(chapters, visible) else chapters
                if (session.chapters != projected) {
                    cancelReaderSourceStandbys()
                    _reading.value = session.copy(chapters = projected)
                }
            }
        }
        changedCatalog?.let { (display, catalog) ->
            projectionSaveLock.withLock {
                if (currentProjection() && detailRequestId == detailEpoch &&
                    _detail.value?.manga?.id == catalog.manga.id && _detail.value?.detail == display)
                    readerCatalogs.save(catalog)
            }
        }
    }

    fun manageSources() {
        val work = _detail.value?.manga ?: _reading.value?.manga ?: return
        if (work.sourceUrl == LOCAL_SOURCE_URL) return
        closeSourceManagement()
        val available = repository.sources.filter { it.id != work.sourceId &&
            repository.canAssociateWorkSource(work, it.id) }
        val first = available.firstOrNull()?.id.orEmpty()
        _sourceManagement.value = SourceManagementState(
            work = work,
            sourceId = first,
            sources = available.map { it.id to it.name },
            linked = repository.confirmedLinkedWorks(work),
            preferredSource = unifiedChapters.settings(work.id).preferredSources.firstOrNull() ?: work.sourceId,
            domainOptions = sourceDomainOptions(first), sourceDomain = sourceDomain(first),
        )
        refreshSourceOverview()
        discoverWorkSources(work)
    }

    private fun sourceIdentityReviews(work: Manga): List<ReviewSource> =
        if (isAdultSource(work.sourceId)) emptyList()
        else (_detail.value?.takeIf { it.manga.id == work.id }?.reviews.orEmpty() + reviews.cachedSources(work))
            .filter { it.status == ReviewStatus.READY }.distinct()

    private fun sourceAliases(work: Manga): List<String> =
        (work.alternativeTitles + sourceIdentityReviews(work).flatMap { it.aliases + it.matchedTitle })
            .filter(String::isNotBlank).distinct()

    private fun isAdultSource(sourceId: String): Boolean =
        repository.isAdultSource(sourceId) || bodySources.entries.value.any { it.source.id == sourceId && it.adult }

    private fun discoverWorkSources(work: Manga, force: Boolean = false, metadataOnly: Boolean = false,
        onlySourceId: String? = null) {
        if (work.sourceId == "catalog") return
        if (!metadataOnly) services.sourceChecks.watch(work)
        val ready = sourceIdentityReviews(work)
        val aliases = sourceAliases(work)
        val ids = (work.externalIds.toList() + ready.flatMap { source ->
            source.externalIds.toList() + listOf(source.id to source.subjectId)
        }).filter { it.second.isNotBlank() }.groupBy({ (provider, _) ->
            when (provider.lowercase()) {
                "al" -> "anilist"; "mal" -> "myanimelist"; "kt" -> "kitsu"; else -> provider.lowercase()
            }
        }, { it.second.trim().lowercase() })
        val conflicts = ids.filterValues { it.distinct().size > 1 }.keys
        val unique = ids.mapNotNull { (provider, values) ->
            values.distinct().singleOrNull()?.let { provider to it }
        }.toMap()
        val identity = work.copy(alternativeTitles = aliases, externalIds = unique,
            authorAliases = (work.authorAliases + ready.flatMap { it.authorAliases })
                .filter(String::isNotBlank).distinct())
        if (metadataOnly) repository.reconsiderSourceCandidates(identity, aliases, conflicts)
        else if (force) repository.retryFailedSources(identity, aliases, conflicts, onlySourceId,
            _sourceManagement.value?.takeIf { it.work.id == work.id }?.discovery.orEmpty()
                .filter { it.status == SourceDiscoveryStatus.FAILED }.map { it.sourceId }.toSet())
        else repository.discoverSources(identity, aliases, false, conflicts)
    }

    private fun sourceDomainOptions(sourceId: String): List<String> =
        if (sourceId == "komiic") KomiicBodySource.BASE_URLS else emptyList()

    private fun sourceDomain(sourceId: String): String =
        if (sourceId == "komiic") komiicSource.info.homeUrl else ""

    private fun refreshSourceOverview() {
        val state = _sourceManagement.value ?: return
        val work = state.work
        val settings = unifiedChapters.settings(work.id)
        val linkedWorks = repository.confirmedLinkedWorks(work)
        val discovered = repository.sourceDiscovery.value[work.id].orEmpty()
        val chapters = _detail.value?.takeIf { it.manga.id == work.id }?.detail?.chapters
            ?: _reading.value?.takeIf { it.manga.id == work.id }?.chapters.orEmpty()
        val results = repository.sources.filter { repository.sharesSourceProfile(work.sourceId, it.id) }.map { source ->
            val linked = source.id == work.sourceId || linkedWorks.any { it.sourceId == source.id }
            val existingChapters = chapters.count { chapter -> chapter.variants.any { it.sourceId == source.id } ||
                chapter.variants.isEmpty() && chapter.sourceId == source.id }
            val alignedChapters = chapters.count { chapter ->
                chapter.variants.any { it.sourceId == source.id } && chapter.variants.any { it.sourceId != source.id }
            }.takeIf { existingChapters > 0 }
            val discovery = discovered[source.id]
            val linkedFailure = settings.links.firstOrNull {
                it.manga.sourceId == source.id && it.confirmedAt > 0L
            }?.let {
                repository.sourceErrors.value[work.id]?.get(it.manga.id)
            }
            val primaryFailure = _detail.value?.takeIf { source.id == work.sourceId && it.manga.id == work.id }?.error
            when {
                source.id in settings.excludedSources -> SourceDiscoveryResult(source.id, source.name,
                    SourceDiscoveryStatus.DISABLED, message = "已取消关联，可手动重新添加")
                !repository.canAssociateWorkSource(work, source.id) && source.id != work.sourceId -> SourceDiscoveryResult(source.id, source.name,
                    SourceDiscoveryStatus.DISABLED, message = "已在漫画源设置中停用")
                discovery?.status in listOf(SourceDiscoveryStatus.QUEUED, SourceDiscoveryStatus.SEARCHING,
                    SourceDiscoveryStatus.RETRYING, SourceDiscoveryStatus.FAILED) -> discovery!!.copy(
                        chapters = maxOf(existingChapters, discovery.chapters), alignedChapters = alignedChapters)
                discovery?.status == SourceDiscoveryStatus.LINKED -> discovery.copy(
                    chapters = maxOf(existingChapters, discovery.chapters), alignedChapters = alignedChapters)
                primaryFailure != null -> SourceDiscoveryResult(source.id, source.name, SourceDiscoveryStatus.FAILED,
                    chapters = existingChapters, message = primaryFailure)
                linkedFailure != null -> SourceDiscoveryResult(source.id, source.name, SourceDiscoveryStatus.FAILED,
                    chapters = existingChapters, message = "$linkedFailure 已保留上次目录。")
                !linked && discovery?.status == SourceDiscoveryStatus.NEEDS_CONFIRMATION -> discovery!!.copy(chapters = existingChapters)
                linked -> SourceDiscoveryResult(source.id, source.name, SourceDiscoveryStatus.LINKED,
                    chapters = existingChapters, alignedChapters = alignedChapters)
                else -> discovered[source.id] ?: SourceDiscoveryResult(source.id, source.name,
                    SourceDiscoveryStatus.NO_MATCH, message = "尚未检查")
            }
        }
        val available = repository.sources.filter { it.id != work.sourceId && repository.canAssociateWorkSource(work, it.id) }
            .map { it.id to it.name }
        _sourceManagement.update { if (it?.work?.id == work.id) it.copy(
            linked = linkedWorks, sources = available, chapters = chapters,
            preferredSource = settings.preferredSources.firstOrNull() ?: work.sourceId,
            discovery = results, scanning = discovered.values.any { result -> result.status in setOf(
                SourceDiscoveryStatus.QUEUED, SourceDiscoveryStatus.SEARCHING, SourceDiscoveryStatus.RETRYING) },
            domainOptions = sourceDomainOptions(it.sourceId), sourceDomain = sourceDomain(it.sourceId)) else it }
    }

    fun retrySourceDiscovery() {
        val work = _sourceManagement.value?.work ?: _detail.value?.manga ?: return
        discoverWorkSources(work, force = true)
    }

    fun retrySourceCheck(sourceId: String) {
        val work = _sourceManagement.value?.work ?: return
        discoverWorkSources(work, force = true, onlySourceId = sourceId)
    }

    fun chooseBodySource(sourceId: String) {
        val state = _sourceManagement.value ?: return
        if (state.connecting || state.sources.none { it.first == sourceId } ||
            !repository.canAssociateWorkSource(state.work, sourceId)) return
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
        if (state.connecting || state.sources.none { it.first == sourceId } ||
            !repository.canAssociateWorkSource(state.work, sourceId)) return
        sourceManagementJob?.cancel()
        val request = ++sourceManagementRequest
        val configuration = repository.sourceConfigurationKey()
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
                        require(repository.sourceConfigurationKey() == configuration &&
                            repository.canAssociateWorkSource(state.work, sourceId)) { "来源设置已变化，请重新选择。" }
                        found = repository.searchSource(sourceId, term)
                        if (found.items.isNotEmpty()) break
                    }
                    require(repository.sourceConfigurationKey() == configuration &&
                        repository.canAssociateWorkSource(state.work, sourceId)) { "来源设置已变化，请重新选择。" }
                    found.copy(items = found.items.filter { it.sourceId == sourceId &&
                        runCatching { SourceKeys.decodeManga(it.id).sourceId == sourceId }.getOrDefault(false) })
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

    fun attachBodySource(workId: String, manga: Manga) {
        val state = _sourceManagement.value ?: return
        if (state.work.id != workId || state.connecting || state.sources.none { it.first == manga.sourceId } ||
            !repository.canAssociateWorkSource(state.work, manga.sourceId)) return
        sourceManagementJob?.cancel()
        val request = ++sourceManagementRequest
        val configuration = repository.sourceConfigurationKey()
        _sourceManagement.value = state.copy(connecting = true, loading = false, error = null)
        sourceManagementJob = viewModelScope.launch {
            try {
                val catalog = foregroundRequest("来源目录") { repository.detailSource(manga.sourceId, manga.id) }
                if (request != sourceManagementRequest) return@launch
                require(catalog.manga.id == manga.id && catalog.manga.sourceId == manga.sourceId) {
                    "已选作品和返回的目录不一致，请重新选择。"
                }
                withContext(Dispatchers.IO) {
                    // Choosing the row confirms the work, not its chapter numbering or edition.
                    repository.attachWorkSource(state.work, catalog, configuration) { request == sourceManagementRequest }
                }
                if (request != sourceManagementRequest) return@launch
                withContext(Dispatchers.Default) { repository.reconcileSourceDiscovery(workId) }
                refreshUnifiedProjection()
                _sourceManagement.update { it?.copy(connecting = false,
                    linked = repository.confirmedLinkedWorks(state.work)) }
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
        if (state.connecting || repository.confirmedLinkedWorks(state.work).none { it.id == manga.id }) return
        changeUnified {
            unifiedChapters.detach(state.work.id, manga.id)
            _sourceManagement.update { if (it?.work?.id == state.work.id)
                it.copy(linked = repository.confirmedLinkedWorks(state.work)) else it }
        }
    }

    /** Only the work's current native catalogue can confirm a cross-source chapter pair. */
    fun alignManagedChapter(chapter: Chapter, version: ChapterVariant, target: Chapter?) {
        val state = _sourceManagement.value ?: return
        val work = state.work.takeIf { isAdultSource(it.sourceId) } ?: return
        val versionWorkId = SourceKeys.manga(version.sourceId, version.mangaId)
        if (state.connecting || version.sourceId == work.sourceId ||
            repository.confirmedLinkedWorks(work).none { it.id == versionWorkId } ||
            state.chapters.none { it.id == chapter.id && it.variants.any { entry -> entry.storageId == version.storageId } })
            return
        val targetId = target?.let { it.logicalId.ifBlank { it.id } }
        if (target != null && state.chapters.none { candidate ->
                candidate.id == target.id && candidate.variants.any {
                    SourceKeys.manga(it.sourceId, it.mangaId) == work.id
                }
            }) return
        changeUnified {
            check(repository.canAssociateWorkSource(work, version.sourceId) &&
                repository.confirmedLinkedWorks(work).any { it.id == versionWorkId }) { "关联版本已更新，请重新选择章节。" }
            if (targetId == null) unifiedChapters.split(work.id, version.storageId)
            else unifiedChapters.associate(work.id, version.storageId, targetId)
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

    fun readReaderChapterVariant(chapter: Chapter, variant: ChapterVariant, pageIndex: Int, pageCount: Int) {
        requestSourceVariant(chapter, variant, pageIndex.coerceAtLeast(0), pageCount.coerceAtLeast(0))
    }

    /** Explicit whole-chapter fallback; the other edition owns its independent page/cursor. */
    fun quickReadReaderChapterVariant(chapter: Chapter, variant: ChapterVariant, pageIndex: Int, pageCount: Int) {
        if (chapter.variants.none { it.storageId == variant.storageId } || variant.storageId == chapter.id) return
        _sourceResumeChoice.value = null
        prepareReadChapterVariant(chapter, variant, pageIndex.coerceAtLeast(0), pageCount.coerceAtLeast(0))
    }

    /** Only the reader's decoded, explicitly aligned target can enter this automatic path. */
    fun verifiedReadReaderChapterVariant(chapter: Chapter, variant: ChapterVariant,
        targetIndex: Int, targetManifest: String) {
        val session = _reading.value ?: return
        val current = visibleReading?.takeIf { it.first.id == session.manga.id }?.second ?: return
        if (current.id != chapter.id || session.collection != null || _chapterSourceOperation.value.loading) return
        val policy = ExperiencePolicyStore.get(application).state.value.recovery
        if (!policy.autoSwitch || policy.sourceLocked || targetManifest.isBlank() || targetIndex < 0) return
        val key = session.manga.id + "\u0000" + chapter.logicalId.ifBlank { chapter.id }
        val now = monotonicTimeMillis()
        val last = automaticSourceSwitches[key]
        if (last != null && now - last < policy.switchCooldownSeconds.toLong() * 1_000L) return
        prepareReadChapterVariant(chapter, variant, targetIndex, verifiedManifest = targetManifest,
            automaticKey = key)
    }

    /** Undo restores the original native index after checking its exact edition again. */
    fun undoReaderSourceSwitch() {
        val undo = readerSourceUndo ?: return
        val session = _reading.value ?: return
        if (session.manga.id != undo.workId || readingRequestId != undo.afterReadingEpoch ||
            visibleReading?.second?.id != undo.afterChapterId || _chapterSourceOperation.value.loading) return
        if (scriptSourceImageEpoch(application, undo.variant.sourceId) != undo.sourceEpoch) {
            _readerSourceSwitchUndoAvailable.value = false
            viewModelScope.launch { messages.send("原来源账号或图片会话已变化，请重新选择来源。") }
            return
        }
        val current = session.chapters.firstOrNull { it.id == undo.afterChapterId } ?: return
        val original = current.variants.firstOrNull { it.storageId == undo.variant.storageId } ?: return
        prepareReadChapterVariant(current, original, undo.nativePage,
            verifiedManifest = undo.manifest, undoing = true)
    }

    private fun requestSourceVariant(chapter: Chapter, variant: ChapterVariant,
        fromPageIndex: Int?, fromPageCount: Int = 0) {
        if (chapter.variants.none { it.storageId == variant.storageId }) return
        val work = _reading.value?.manga ?: _detail.value?.manga ?: return
        _sourceResumeChoice.value = null
        cancelSourceSelection()
        val page = fromPageIndex?.takeIf { it > 0 && chapter.id != variant.storageId }
        if (page != null && _sourceResumePolicy.value == "ask") {
            sourceResumeFromReader = _reading.value != null
            sourceResumeEpoch = if (sourceResumeFromReader) readingRequestId else detailRequestId
            _sourceResumeChoice.value = SourceResumeChoice(work.id, chapter, variant, page, fromPageCount)
        } else prepareReadChapterVariant(chapter, variant,
            page?.takeIf { _sourceResumePolicy.value == "same_page" }, fromPageCount)
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
                choice.fromPageIndex.takeIf { samePage }, choice.fromPageCount)
        }
    }

    private fun prepareReadChapterVariant(chapter: Chapter, variant: ChapterVariant,
        samePageIndex: Int?, fromPageCount: Int = 0, verifiedManifest: String? = null,
        automaticKey: String? = null, undoing: Boolean = false) {
        if (chapter.variants.none { it.storageId == variant.storageId }) return
        val session = _reading.value
        val detail = _detail.value
        val work = session?.manga ?: detail?.manga ?: return
        val catalog = session?.chapters ?: detail?.detail?.chapters ?: return
        val readingEpoch = readingRequestId
        val detailEpoch = detailRequestId
        cancelSourceSelection()
        val request = sourceReadRequest
        val currentChapter = findSourceChapter(catalog, chapter.id)
        val currentVariant = currentChapter?.variants?.firstOrNull { it.storageId == variant.storageId }
        if (currentChapter == null || currentVariant == null ||
            currentVariant.sourceId != work.sourceId && !repository.sharesSourceProfile(work.sourceId, currentVariant.sourceId)) {
            _chapterSourceOperation.value = ChapterSourceOperation(
                error = "章节来源已更新，请重新选择。", targetSource = variant.sourceName)
            return
        }
        val selected = resolveChapter(currentChapter, currentVariant)
        val sourceMangaId = chapterSourceMangaId(work.id, selected)
        val targetIdentity = work.id to selected.id
        val retryImage = automaticKey == null && failedSourcePage == targetIdentity
        val configuration = repository.sourceConfigurationKey()
        val targetSourceEpoch = scriptSourceImageEpoch(application, currentVariant.sourceId)
        val originalVariant = currentChapter.variants.firstOrNull { it.storageId == currentChapter.id }
        val originalPages = repository.cachedPages(chapterSourceMangaId(work.id, currentChapter), currentChapter.id)
        val originalPage = readerNativeCursor?.takeIf { it.first == work.id && it.second == currentChapter.id }?.third
            ?: library.progressFor(work.id, currentChapter.id)?.pageIndex ?: session?.initialPageIndex ?: 0
        val originalEpoch = originalVariant?.let { scriptSourceImageEpoch(application, it.sourceId) }
        val targetRouteIdentity = DownloadRoutingStore.get(application).sourceSnapshot(currentVariant.sourceId).identity()
        val selectionSettings = unifiedChapters.settings(work.id)
        fun readingChapterCurrent(): Boolean = session == null ||
            (_reading.value?.manga?.id == work.id &&
                (readerNativeCursor?.takeIf { it.first == work.id }?.second
                    ?: visibleReading?.takeIf { it.first.id == work.id }?.second?.id
                    ?: _reading.value?.chapterId) == currentChapter.id)
        fun verifiedTargetCurrent(): Boolean =
            readingChapterCurrent() && repository.sourceConfigurationKey() == configuration &&
                DownloadRoutingStore.get(application).sourceSnapshot(currentVariant.sourceId).identity() == targetRouteIdentity &&
                scriptSourceImageEpoch(application, currentVariant.sourceId) == targetSourceEpoch &&
                (automaticKey == null || ExperiencePolicyStore.get(application).state.value.recovery.let {
                    it.autoSwitch && !it.sourceLocked
                })
        _chapterSourceOperation.value = ChapterSourceOperation(loading = true, targetSource = variant.sourceName)
        // Keep the old readable page mounted until the target manifest is ready.
        // The source returns only URLs/metadata: no preceding images are fetched.
        sourceReadJob = viewModelScope.launch {
            val storage = dev.mangaflow.storage.StorageCacheStore.get(application)
            val release = storage.protectReading(work.id, selected.id, sourceMangaId)
            var preparingImage = false
            try {
                val pages = withContext(Dispatchers.IO) {
                    withTimeout(ExperiencePolicyStore.get(application).state.value.recovery.manifestTimeoutSeconds.toLong() * 1_000L) {
                        foregroundRequest("准备本章来源") {
                            repository.cachedPages(sourceMangaId, selected.id)
                                ?: downloads.storedPages(work.id, selected.id)
                                ?: repository.pages(sourceMangaId, selected.id)
                        }
                    }
                }
                require(pages.isNotEmpty()) { "这个来源暂时没有可读图片。" }
                if (request != sourceReadRequest) return@launch
                if (session != null && (readingEpoch != readingRequestId || _reading.value?.manga?.id != work.id)) return@launch
                if (session == null && (detailEpoch != detailRequestId || _detail.value?.manga?.id != work.id)) return@launch
                // Page cuts may differ between editions. Preserve approximate progress
                // rather than treating the same integer page as the same artwork.
                require(verifiedTargetCurrent()) { "来源或恢复设置已变化，请重新确认。" }
                val initialPage = if (verifiedManifest != null) {
                    require(readerManifestFingerprint(pages) == verifiedManifest &&
                        samePageIndex != null && samePageIndex in pages.indices) {
                        "目标图片清单已变化，请重新对齐后再切换。"
                    }
                    samePageIndex
                } else samePageIndex?.let { oldIndex ->
                    approximateReaderPage(oldIndex, fromPageCount, pages.size)
                }
                val targetPageIndex = initialPage ?: library.progressFor(work.id, selected.id)?.pageIndex
                    ?.coerceIn(pages.indices) ?: 0
                val preparedManifest = readerManifestFingerprint(pages)
                val headers = repository.imageHeaders(sourceMangaId, selected.id).toMap()
                val runtime = ExperiencePolicyStore.get(application).state.value.runtime
                preparingImage = true
                prepareReaderSourcePage(application, work.id, currentVariant, pages[targetPageIndex], headers,
                    downloads, runtime.previewWidthPx, runtime.previewHeightPx, retry = retryImage)
                preparingImage = false
                currentCoroutineContext().ensureActive()
                require(verifiedTargetCurrent() && repository.cachedPages(sourceMangaId, selected.id)
                    ?.let(::readerManifestFingerprint)?.let { it == preparedManifest } != false) {
                    "来源图片清单或线路已变化，请重新选择。"
                }
                if (request != sourceReadRequest || readingEpoch != readingRequestId ||
                    session == null && detailEpoch != detailRequestId) return@launch
                val latestCatalog = (if (session != null) _reading.value?.chapters
                    else _detail.value?.detail?.chapters) ?: return@launch
                val latestChapter = findSourceChapter(latestCatalog, chapter.id)
                val latestVariant = latestChapter?.variants?.firstOrNull { it.storageId == selected.id }
                require(latestChapter != null && latestVariant != null) { "章节来源已更新，请重新选择。" }
                val latestSelected = resolveChapter(latestChapter, latestVariant)
                withContext(Dispatchers.IO) {
                    unifiedChapters.choose(work.id, latestSelected.logicalId.ifBlank { latestSelected.id }, latestSelected.id,
                        expectedSettings = selectionSettings, isCurrent = {
                            request == sourceReadRequest && readingEpoch == readingRequestId &&
                                (session != null || detailEpoch == detailRequestId) && verifiedTargetCurrent()
                        })
                }
                if (request != sourceReadRequest) return@launch
                if (session != null && (readingEpoch != readingRequestId || _reading.value?.manga?.id != work.id)) return@launch
                if (session == null && (detailEpoch != detailRequestId || _detail.value?.manga?.id != work.id)) return@launch
                // Storage yielded to catalogue updates. Only the final selection map runs
                // synchronously; the expensive semantic projection remains off Main.
                val currentCatalog = (if (session != null) _reading.value?.chapters
                    else _detail.value?.detail?.chapters) ?: return@launch
                val current = findSourceChapter(currentCatalog, chapter.id)
                val available = current?.variants?.firstOrNull { it.storageId == selected.id }
                require(current != null && available != null &&
                    current.logicalId.ifBlank { current.id } == latestSelected.logicalId.ifBlank { latestSelected.id }) {
                    "章节来源已更新，请重新选择。"
                }
                require(verifiedTargetCurrent()) { "来源或恢复设置已变化，请重新确认。" }
                val projected = selectedChapters(currentCatalog, resolveChapter(current, available))
                val pendingCatalog = if (session != null) _reading.value?.let {
                    it.chaptersLoading || it.chaptersError != null
                } == true else _detail.value?.let { it.loading || it.error != null || it.detail == null } == true
                val needsCatalog = pendingCatalog ||
                    work.sourceUrl != LOCAL_SOURCE_URL && !repository.isCatalogFresh(work.id)
                _chapterSourceOperation.value = ChapterSourceOperation()
                failedSourcePage = null
                beginReading(ReadingSession(work, projected, selected.id,
                    chaptersLoading = needsCatalog, initialPageIndex = targetPageIndex))
                if (needsCatalog) loadReadingCatalog()
                if (automaticKey != null) automaticSourceSwitches[automaticKey] = monotonicTimeMillis()
                if (!undoing && session != null && originalVariant != null && originalPages != null &&
                    originalPage in originalPages.indices && originalEpoch != null) {
                    readerSourceUndo = ReaderSourceUndo(work.id, currentChapter, originalVariant,
                        originalPage, readerManifestFingerprint(originalPages), originalEpoch, selected.id, readingRequestId)
                    _readerSourceSwitchUndoAvailable.value = true
                }
                messages.send(if (verifiedManifest != null)
                    if (undoing) "已回到原版本第${initialPage!! + 1}页。"
                    else "已切换到 ${variant.sourceName}，从已对齐的第${initialPage!! + 1}页继续。"
                else if (initialPage != null)
                    "已切换到 ${variant.sourceName}，从约第${initialPage + 1}页继续。不同版本分图可能不同。"
                else if (library.progressFor(work.id, selected.id) != null)
                    "已切换到 ${variant.sourceName}，恢复该版本的位置。"
                else "已切换到 ${variant.sourceName}，从本话开头阅读。")
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                if (request == sourceReadRequest) {
                    val error = "目标来源图片清单等待超时，仍保留当前阅读版本。"
                    _chapterSourceOperation.value = ChapterSourceOperation(error = error, targetSource = variant.sourceName)
                    messages.send(error)
                }
            } catch (cancel: CancellationException) { throw cancel
            } catch (failure: Exception) {
                if (request == sourceReadRequest) {
                    if (preparingImage) failedSourcePage = targetIdentity
                    val error = failure.message ?: "本章来源未能打开，请重试。"
                    _chapterSourceOperation.value = ChapterSourceOperation(error = error, targetSource = variant.sourceName)
                    messages.send(error)
                }
            } finally {
                release()
                if (request == sourceReadRequest && _chapterSourceOperation.value.loading)
                    _chapterSourceOperation.value = ChapterSourceOperation()
            }
        }
    }

    fun retryDetail() {
        _detail.value?.rankedWork?.let { searchRankedSources(it.key, it.manga.title.take(120)); return }
        val work = _detail.value?.manga ?: return
        openManga(work, forceRefresh = true)
        viewModelScope.launch {
            try { repository.refreshLinked(work.id); refreshUnifiedProjection() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { messages.send(failure.message ?: "正文来源刷新失败，可继续阅读已保存章节。") }
        }
    }

    fun closeDetail() {
        clearCopyProgress()
        rankedWorkRequest++
        rankedWorkJob?.cancel()
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
        val needsCatalog = current.loading || current.error != null || current.detail == null ||
            current.manga.sourceUrl != LOCAL_SOURCE_URL && !repository.isCatalogFresh(current.manga.id)
        beginReading(ReadingSession(current.manga, selectedChapters(chapters, chapter), chapter.id, chaptersLoading = needsCatalog))
        if (needsCatalog) loadReadingCatalog()
    }

    fun restartChapter(chapter: Chapter) {
        val current = _detail.value ?: return
        val chapters = current.detail?.chapters ?: downloadedChapters(current.manga.id)
        if (chapters.none { it.id == chapter.id }) return
        val needsCatalog = current.loading || current.error != null || current.detail == null ||
            current.manga.sourceUrl != LOCAL_SOURCE_URL && !repository.isCatalogFresh(current.manga.id)
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
        if (library.ready.value && continuePreferredResume(manga)) return
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
                if (continuePreferredResume(manga)) return@launch
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
                val memory = withContext(Dispatchers.Default) {
                    repository.cachedCatalog(manga.id)?.takeIf { it.detail.chapters.isNotEmpty() }
                }
                val saved = if (memory == null) readerCatalogs.read(manga.id) else null
                if (requestId != libraryOpenRequestId) return@launch
                // Only a full directory establishes the first chapter; a download may start midway.
                val catalog = memory?.detail ?: saved ?: repository.detail(manga.id)
                if (requestId != libraryOpenRequestId) return@launch
                val first = catalog.chapters.minByOrNull(Chapter::index)?.takeIf { it.id.isNotBlank() }
                    ?: throw IllegalStateException("暂时没有可读章节，请稍后重试。")
                if (requestId != libraryOpenRequestId) return@launch
                val needsCatalog = saved != null || memory?.fresh == false
                // Detach before beginReading invalidates other navigation intents; do not cancel this job.
                libraryOpenJob = null
                _libraryOpening.value = null
                closeDetail()
                beginReading(ReadingSession(catalog.manga, catalog.chapters, first.id,
                    chaptersLoading = needsCatalog))
                if (needsCatalog) loadReadingCatalog()
                // The reader can draw while this valid directory is persisted.
                if (memory == null && saved == null) readerCatalogs.save(catalog)
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
        val opening = _libraryOpening.value?.takeUnless { it.loading } ?: return
        libraryOpenRetry?.invoke() ?: readFromLibrary(opening.manga)
    }

    fun cancelLibraryReading() {
        libraryOpenRequestId++
        libraryOpenJob?.cancel()
        libraryOpenJob = null
        libraryOpenRetry = null
        _libraryOpening.value = null
    }

    private fun beginReading(session: ReadingSession) {
        readerNativeCursor = null
        readerSourceUndo = null
        _readerSourceSwitchUndoAvailable.value = false
        cancelReaderSourceStandbys()
        cancelLibraryReading()
        readingCatalogJob?.cancel()
        cancelNextChapterPrefetch()
        readingRequestId++
        visibleReading = null
        _reading.value = session
        if (session.collection == null && session.manga.sourceUrl != LOCAL_SOURCE_URL) {
            if (repository.cachedSourceDetail(session.manga.id) == null)
                repository.retainWork(MangaDetail(session.manga, session.chapters))
            discoverWorkSources(session.manga)
        }
    }

    private fun openSavedChapter(
        manga: Manga, chapterId: String, chapterTitle: String, fallback: Chapter? = null,
        collection: PageCollection? = null,
    ) {
        cancelLibraryReading()
        val requestId = libraryOpenRequestId
        _libraryOpening.value = LibraryOpenState(manga)
        libraryOpenRetry = { openSavedChapter(manga, chapterId, chapterTitle, fallback, collection) }
        val request = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val local = manga.sourceUrl == LOCAL_SOURCE_URL
                val offline = if (local) downloadedChapters(manga.id) else knownDownloadChapters(manga.id)
                // Reuse the directory already merged and ordered off Main, including saved chapters.
                val prepared = if (local) null else cachedDetailForDisplay(manga.id, null, offline)
                val snapshot = prepared?.first
                val cached = snapshot?.detail
                if (requestId != libraryOpenRequestId) return@launch
                fun savedFallback(): Chapter {
                    val title = chapterTitle.ifBlank { "继续阅读" }
                    if (!SourceKeys.isEncoded(chapterId)) return Chapter(chapterId, title, 0)
                    val ref = SourceKeys.decodeChapter(manga.id, chapterId)
                    if (isAdultSource(manga.sourceId) || ref.sourceId != manga.sourceId) {
                        require(repository.sharesSourceProfile(manga.sourceId, ref.sourceId)) {
                            "继续阅读所用版本的来源已变化，请进入作品详情重新选择。"
                        }
                    }
                    val info = repository.sources.firstOrNull { it.id == ref.sourceId }
                    val version = ChapterVariant(ref.sourceId, info?.name ?: ref.sourceId, ref.mangaId, ref.chapterId,
                        title, language = info?.language.orEmpty(), edition = "")
                    return Chapter(chapterId, title, 0, variants = listOf(version),
                        sourceId = ref.sourceId, sourceMangaId = ref.mangaId, sourceChapterId = ref.chapterId)
                }
                val catalogChapter = cached?.chapters?.let { findSourceChapter(it, chapterId) }
                val chapter = catalogChapter
                    ?: offline.firstOrNull { it.id == chapterId }
                    ?: fallback
                    ?: savedFallback()
                val chapters = selectedChapters(prepared?.third ?: retainedChapters(emptyList(), offline), chapter)
                val hasCurrentCatalog = catalogChapter != null && snapshot?.fresh == true
                val needsCatalog = if (local) offline.isEmpty() else !hasCurrentCatalog
                // Detach before beginReading cancels previous navigation intents.
                libraryOpenJob = null
                _libraryOpening.value = null
                closeDetail()
                // Image requests do not await download-store or catalogue restoration.
                beginReading(ReadingSession(cached?.manga ?: manga, chapters, chapter.id,
                    chaptersLoading = needsCatalog, collection = collection))
                if (needsCatalog) loadReadingCatalog()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (requestId == libraryOpenRequestId) _libraryOpening.value = LibraryOpenState(manga,
                    loading = false, error = failure.message ?: "暂时无法打开这本漫画，请重试。")
            }
        }
        libraryOpenJob = request
        request.start()
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
                        chapters = retainedChapters(current.chapters, saved?.chapters.orEmpty() + offline),
                    )
                }
                // Retained chapters are readable, not evidence of a complete directory.
                // Join the repository's source request rather than the whole detail job,
                // whose persistence/metrics may still be running after the catalog is ready.
                val cached = if (forceRefresh) null else withContext(Dispatchers.Default) {
                    repository.cachedCatalog(session.manga.id)?.takeIf {
                        it.fresh && findSourceChapter(it.detail.chapters, session.chapterId) != null
                    }?.detail
                }
                // Downloaded chapters are immediately usable, but they are not
                // a complete directory for continuing into the next chapter.
                val loaded = cached ?: repository.detail(session.manga.id)
                if (requestId != readingRequestId) return@launch
                discoverWorkSources(loaded.manga)
                _reading.update { current ->
                    if (current?.manga?.id != session.manga.id) current
                    else current.copy(
                        manga = loaded.manga,
                        chapters = selectedChapters(projectionChapters(session.manga.id, loaded.chapters,
                            current.chapters, offline, primaryObserved = true),
                            visibleReading?.takeIf { it.first.id == session.manga.id }?.second
                                ?: findSourceChapter(current.chapters, current.chapterId)
                                ?: Chapter(current.chapterId, "继续阅读", 0)),
                        chaptersLoading = false, chaptersError = null,
                    )
                }
                // Adjacent chapters are usable before the background directory write completes.
                readerCatalogs.save(loaded)
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

    fun cancelReaderSourceStandbys() = readerStandbys.clear()

    /** Prepare confirmed alternatives without selecting one or manufacturing page equivalence. */
    fun warmReaderSourceStandbys(manga: Manga, chapter: Chapter, pageIndex: Int, pageCount: Int, direction: Int) {
        if (manga.sourceUrl == LOCAL_SOURCE_URL) return
        val session = _reading.value?.takeIf { it.manga.id == manga.id && it.collection == null } ?: return
        val current = session.chapters.firstOrNull { it.id == chapter.id } ?: return
        if (pageIndex >= 0 && pageIndex < pageCount) readerNativeCursor = Triple(manga.id, current.id, pageIndex)
        val epoch = readingRequestId
        val logical = current.logicalId.ifBlank { current.id }
        val configuration = repository.sourceConfigurationKey()
        val links = unifiedChapters.linkedSources(manga.id).filter { link ->
            repository.sharesSourceProfile(manga.sourceId, link.manga.sourceId)
        }
        val confirmed = repository.confirmedLinkedWorks(manga).map { it.id }.toSet() + manga.id
        val enabled = repository.enabledSources(isAdultSource(manga.sourceId))
        val alternatives = current.variants.filter { it.storageId != current.id }.distinctBy { it.sourceId }
        val confirmedAlternatives = alternatives.filter { SourceKeys.manga(it.sourceId, it.mangaId) in confirmed }
        val candidates = confirmedAlternatives.filter { it.sourceId in enabled }
        val currentOwner = chapterSourceMangaId(manga.id, current)
        val knownOwners = (links.map { it.manga.id } + manga.id).filter { it != currentOwner }.toSet()
        val confirmedOwners = confirmed.filter { it != currentOwner }
        val availabilityPhase = when {
            candidates.isNotEmpty() -> ReaderSourceStandbyAvailabilityPhase.ELIGIBLE
            session.chaptersLoading -> ReaderSourceStandbyAvailabilityPhase.IDLE
            confirmedAlternatives.isNotEmpty() -> ReaderSourceStandbyAvailabilityPhase.DISABLED
            alternatives.isNotEmpty() -> ReaderSourceStandbyAvailabilityPhase.UNCONFIRMED
            confirmedOwners.isNotEmpty() -> ReaderSourceStandbyAvailabilityPhase.NO_MATCHING_CHAPTER
            knownOwners.isNotEmpty() -> ReaderSourceStandbyAvailabilityPhase.UNCONFIRMED
            else -> ReaderSourceStandbyAvailabilityPhase.NO_ALTERNATIVES
        }
        val availability = ReaderSourceStandbyAvailability(manga.id, current.id, availabilityPhase,
            alternatives.size, knownOwners.size, confirmedAlternatives.size, candidates.size, when (availabilityPhase) {
                ReaderSourceStandbyAvailabilityPhase.ELIGIBLE -> null
                ReaderSourceStandbyAvailabilityPhase.IDLE -> "目录仍在补全，备用章节待核对"
                ReaderSourceStandbyAvailabilityPhase.NO_ALTERNATIVES -> "此作品尚未关联可用的备用来源"
                ReaderSourceStandbyAvailabilityPhase.NO_MATCHING_CHAPTER -> "已有关联来源，但本章没有已确认匹配的备用章节"
                ReaderSourceStandbyAvailabilityPhase.UNCONFIRMED -> "备用来源关联尚未确认，确认后才能准备"
                ReaderSourceStandbyAvailabilityPhase.DISABLED -> "本章已确认的备用来源适配器均未启用"
            })
        val epochs = candidates.associate { it.sourceId to scriptSourceImageEpoch(application, it.sourceId) }
        val key = "$epoch\u0000${manga.id}\u0000${current.id}\u0000$logical\u0000$configuration\u0000" +
            candidates.joinToString("|") { "${it.storageId}:${epochs[it.sourceId]}" }
        readerStandbys.warm(key, manga.id, current, candidates, pageIndex, pageCount, direction, availability) { variant ->
            val active = _reading.value
            epoch == readingRequestId && active != null && active.manga.id == manga.id &&
                repository.sourceConfigurationKey() == configuration &&
                scriptSourceImageEpoch(application, variant.sourceId) == epochs[variant.sourceId] &&
                active.chapters.any { item -> item.id == current.id &&
                    item.logicalId.ifBlank { item.id } == logical &&
                    item.variants.any { it.storageId == variant.storageId } }
        }
    }

    /** Retry one exact confirmed alternate without resetting any other healthy preparation. */
    fun retryReaderSourceStandby(manga: Manga, chapter: Chapter, variantId: String) {
        val session = _reading.value?.takeIf { it.manga.id == manga.id && it.collection == null } ?: return
        val current = session.chapters.firstOrNull { it.id == chapter.id } ?: return
        val variant = current.variants.firstOrNull { it.storageId == variantId && it.storageId != current.id } ?: return
        if (!repository.canAssociateWorkSource(manga, variant.sourceId)) return
        val owner = SourceKeys.manga(variant.sourceId, variant.mangaId)
        if (owner != manga.id && repository.confirmedLinkedWorks(manga).none { it.id == owner }) return
        readerStandbys.retry(manga.id, current.id, variantId)
    }

    /** Only a real failed look-ahead page can request early preparation of an alternative. */
    fun onPrefetchPageFailed(manga: Manga, chapter: Chapter, page: Page) {
        val session = _reading.value?.takeIf { it.manga.id == manga.id } ?: return
        val current = session.chapters.firstOrNull { it.id == chapter.id } ?: return
        val pages = repository.cachedPages(chapterSourceMangaId(manga.id, current), current.id) ?: return
        if (pages.none { it == page }) return
        readerStandbys.nudge(manga.id, current.id)
    }

    fun rescueReaderSourceStandbys(chapter: Chapter, enabled: Boolean) {
        val session = _reading.value ?: return
        val current = session.chapters.firstOrNull { it.id == chapter.id } ?: return
        if (enabled && visibleReading?.second?.id != current.id) return
        readerStandbys.rescue(session.manga.id, current.id, enabled)
    }

    private data class NextChapterLimits(val allowed: Boolean, val ahead: Int, val bytes: Long,
        val foreground: Boolean, val pages: Int, val manifestSeconds: Int, val imageSeconds: Int, val cacheSeconds: Int)

    private fun resumeNextChapterIntent(intent: NextChapterIntent?) {
        if (intent == null || intent.epoch != readingRequestId ||
            services.sourceConfiguration.value != repository.sourceConfigurationKey() ||
            visibleReading?.first?.id != intent.workId || visibleReading?.second?.id != intent.currentId) return
        val reading = _reading.value?.takeIf { it.manga.id == intent.workId } ?: return
        if (reading.chapters.none { it.id == intent.currentId &&
                chapterSourceMangaId(reading.manga.id, it) == intent.currentOwner }) return
        reading.chapters.firstOrNull { it.id == intent.targetId &&
            chapterSourceMangaId(intent.workId, it) == intent.targetOwner }?.let {
            // Entry revalidates the reader's immediate successor, installed source and owner;
            // replacement work joins all retiring generations before acquiring its budget.
            prefetchNextChapter(reading.manga, it)
        }
    }

    private fun retireNextChapterJob() {
        retiringChapterPrefetches.removeAll { it.isCompleted }
        chapterPrefetchJob?.let { retiringChapterPrefetches += it; it.cancel() }
        chapterPrefetchJob = null
    }

    private fun cancelNextChapterPrefetch(clearIntent: Boolean = true) {
        chapterPrefetchRequest++
        retireNextChapterJob()
        if (clearIntent) {
            chapterPrefetchIntent = null
            chapterPrefetchImageRestarts = 0
        }
        chapterPrefetchKey = null
        _nextChapterPreparation.value = null
    }

    /** Only a visible chapter can request its immediate reader-sequence successor. */
    fun prefetchNextChapter(manga: Manga, chapter: Chapter) {
        if (manga.sourceUrl == LOCAL_SOURCE_URL) return
        val session = _reading.value ?: return
        val visible = visibleReading ?: return
        if (session.manga.id != manga.id || visible.first.id != manga.id) return
        val current = session.chapters.firstOrNull { it.id == visible.second.id } ?: return
        val siblings = ChapterSemantics.readingSequence(session.chapters, current,
            catalogComplete = !session.chaptersLoading && session.chaptersError == null)
        val next = siblings.getOrNull(siblings.indexOfFirst { it.id == current.id } + 1) ?: return
        if (next.id != chapter.id) return
        if (downloads.entries.value.any { it.manga.id == manga.id && it.chapter.id == chapter.id &&
                it.status == DownloadStatus.COMPLETED }) return
        val owner = chapterSourceMangaId(manga.id, chapter)
        val source = SourceKeys.decodeChapter(owner, chapter.id).sourceId
        if (repository.sources.none { it.id == source }) return
        val epoch = readingRequestId
        val currentChapter = current.id
        val currentOwner = chapterSourceMangaId(manga.id, current)
        val accepted = NextChapterIntent(epoch, manga.id, currentChapter, currentOwner, chapter.id, owner)
        val imageRevision = sourceImageRevisions.value[source] ?: 0L
        val sameIntent = chapterPrefetchIntent == accepted
        fun showSessionSuspended() {
            _nextChapterPreparation.value = ReaderNextChapterPreparation(epoch, manga.id,
                currentChapter, chapter.id, chapter.title, source, owner,
                ReaderNextChapterPreparationPhase.DEFERRED,
                reason = "来源图片会话连续变化，下一话准备已暂缓；请重新打开本章。")
        }
        if (!sameIntent) chapterPrefetchImageRestarts = 0
        else if ((source.startsWith("js_") || source == "jmcomic") && imageRevision != chapterPrefetchImageRevision) {
            // All re-entry paths, including configuration events arriving first, spend
            // the same intent budget. A script preference write cannot reset it.
            cancelNextChapterPrefetch(clearIntent = false)
            chapterPrefetchImageRevision = imageRevision
            if (chapterPrefetchImageRestarts >= 2) {
                chapterPrefetchImageRestarts = 3
                showSessionSuspended()
                return
            }
            chapterPrefetchImageRestarts++
        }
        if (sameIntent && chapterPrefetchImageRestarts > 2) {
            // Configuration cancellation clears the previous status, not the exhausted
            // intent. Rebuild its explanation only after the owner checks above pass.
            showSessionSuspended()
            return
        }
        chapterPrefetchIntent = accepted
        chapterPrefetchImageRevision = imageRevision
        val key = "${manga.id}/${chapter.id}"
        if (chapterPrefetchKey == key && chapterPrefetchJob?.isActive == true) return
        retireNextChapterJob()
        val retiring = retiringChapterPrefetches.toList()
        chapterPrefetchKey = key
        val request = ++chapterPrefetchRequest
        val configuration = repository.sourceConfigurationKey()
        val sourceEpoch = scriptSourceImageEpoch(application, source)
        fun currentSession(): Boolean {
            if (request != chapterPrefetchRequest || epoch != readingRequestId || chapterPrefetchKey != key ||
                visibleReading?.first?.id != manga.id || visibleReading?.second?.id != currentChapter ||
                repository.sourceConfigurationKey() != configuration ||
                scriptSourceImageEpoch(application, source) != sourceEpoch ||
                (sourceImageRevisions.value[source] ?: 0L) != imageRevision) return false
            val reading = _reading.value?.takeIf { it.manga.id == manga.id } ?: return false
            val origin = reading.chapters.firstOrNull { it.id == currentChapter } ?: return false
            if (chapterSourceMangaId(manga.id, origin) != currentOwner || repository.sources.none { it.id == source }) return false
            val ordered = ChapterSemantics.readingSequence(reading.chapters, origin,
                catalogComplete = !reading.chaptersLoading && reading.chaptersError == null)
            val target = ordered.getOrNull(ordered.indexOfFirst { it.id == origin.id } + 1) ?: return false
            return target.id == chapter.id && chapterSourceMangaId(manga.id, target) == owner
        }
        val initial = ReaderNextChapterPreparation(epoch, manga.id, currentChapter, chapter.id,
            chapter.title, source, owner, ReaderNextChapterPreparationPhase.QUEUED)
        _nextChapterPreparation.value = initial.copy(manifestQueued = true)
        chapterPrefetchJob = viewModelScope.launch {
            try {
                // Replacement work cannot acquire a fresh byte window until the old children settle.
                retiring.forEach { it.join() }
                currentCoroutineContext().ensureActive()
                if (!currentSession()) throw CancellationException("下一话准备已更新")
                val policy = ExperiencePolicyStore.get(application)
                policy.state.map { NextChapterLimits(it.crossChapter, it.prefetchAhead, it.nextChapterPrefetchBytes, it.foreground,
                    it.runtime.nextChapterPages, it.recovery.manifestTimeoutSeconds, it.recovery.imageTimeoutSeconds, it.runtime.cacheSampleSeconds) }
                    .distinctUntilChanged().collectLatest { (allowed, ahead, limit, foreground, pageLimit, manifestSeconds, imageSeconds, cacheSeconds) ->
                        if (!currentSession()) throw CancellationException("下一话准备已更新")
                        if (!allowed || !foreground || limit <= 0L || ahead <= 0 || pageLimit <= 0) {
                            _nextChapterPreparation.value = initial.copy(phase = ReaderNextChapterPreparationPhase.DEFERRED,
                                reason = if (!foreground) "阅读不在前台，下一话准备暂缓" else "当前策略未开启下一话原图准备")
                            return@collectLatest
                        }
                        _nextChapterPreparation.value = initial.copy(manifestQueued = true)
                        val upcoming = try {
                            // The foreground reader opens a saved page manifest even when only part of the chapter is on disk.
                            val mayHaveStoredPages = downloads.loading.value || downloads.entries.value.any {
                                it.manga.id == manga.id && it.chapter.id == chapter.id
                            }
                            val cached = if (mayHaveStoredPages) null else repository.cachedPages(owner, chapter.id)
                            cached ?: withTimeout(manifestSeconds.toLong() * 1_000L) {
                                policy.withPrefetchAdmission(source, 1_048_576L, { false }, onWaiting = { wait ->
                                    _nextChapterPreparation.update { value -> if (!currentSession()) value else initial.copy(
                                        phase = if (wait == null) ReaderNextChapterPreparationPhase.MANIFEST else ReaderNextChapterPreparationPhase.QUEUED,
                                        manifestQueued = wait != null, manifestActive = wait == null,
                                        reason = if (wait == null) null else "下一话目录等待准备名额或可用资源") }
                                }) {
                                    withTimeout(manifestSeconds.toLong() * 1_000L) {
                                        val stored = if (mayHaveStoredPages) downloads.storedPages(manga.id, chapter.id) else null
                                        stored ?: repository.pages(owner, chapter.id)
                                    }
                                }
                            }
                        } catch (_: TimeoutCancellationException) {
                            // A failed directory attempt must not terminate the policy observer. The
                            // reader has already submitted this chapter once; a real policy change
                            // (including off/on) can retry it without an automatic retry loop.
                            currentCoroutineContext().ensureActive()
                            if (!currentSession()) throw CancellationException("下一话准备已更新")
                            _nextChapterPreparation.value = initial.copy(
                                phase = ReaderNextChapterPreparationPhase.DEFERRED,
                                reason = "下一话目录准备等待超时")
                            return@collectLatest
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            currentCoroutineContext().ensureActive()
                            if (!currentSession()) throw CancellationException("下一话准备已更新")
                            _nextChapterPreparation.value = initial.copy(
                                phase = ReaderNextChapterPreparationPhase.FAILED,
                                reason = "下一话目录准备失败，翻章时可重试")
                            return@collectLatest
                        } finally {
                            _nextChapterPreparation.update { value -> if (!currentSession()) value else
                                value?.copy(manifestQueued = false, manifestActive = false) }
                        }
                        if (!currentSession()) throw CancellationException("下一话准备已更新")
                        if (upcoming.isEmpty()) {
                            _nextChapterPreparation.value = initial.copy(phase = ReaderNextChapterPreparationPhase.FAILED,
                                reason = "下一话尚未返回图片目录")
                            return@collectLatest
                        }
                        val headers = repository.imageHeaders(owner, chapter.id).toMap()
                        val window = PrefetchByteBudget(limit)
                        class NextPage(val page: Page, var ticket: PrefetchByteBudget) {
                            lateinit var work: PrefetchWork
                            var original: Page? = null
                            var cacheKey: String? = null
                            var ready: Boolean? = null
                            var done = false
                            var failure: String? = null
                            var deferred: String? = null
                            var recoveryStage: PrefetchStage? = null
                            var recoveries = 0
                            var retryAt = 0L
                        }
                        val pages = upcoming.take(minOf(ahead, pageLimit)).map { NextPage(it, window.child()) }
                        var windowLive = true
                        fun publish() {
                            if (!windowLive || !currentSession()) return
                            val pending = pages.filterNot { it.done }
                            val observations = pages.associateWith { it.work.snapshot() }
                            val activity = observations.values.toList()
                            val active = activity.count { it.admitted && !it.settled }
                            val queued = pending.count { item -> observations.getValue(item).let { !it.admitted || it.settled } }
                            val ready = pages.count { it.ready == true }
                            val failed = pages.count { it.done && it.ready != true && it.failure != null }
                            val deferred = pages.count { it.done && it.ready != true && it.failure == null }
                            val phase = when {
                                ready == pages.size -> ReaderNextChapterPreparationPhase.READY
                                active > 0 -> ReaderNextChapterPreparationPhase.PREPARING
                                queued > 0 -> ReaderNextChapterPreparationPhase.QUEUED
                                failed > 0 -> ReaderNextChapterPreparationPhase.FAILED
                                else -> ReaderNextChapterPreparationPhase.DEFERRED
                            }
                            _nextChapterPreparation.value = initial.copy(phase = phase, totalPages = upcoming.size,
                                requestedPages = pages.size, originalReadyPages = ready, queuedPages = queued,
                                activePages = active,
                                resolvingPages = activity.count { it.admitted && !it.settled && it.stage == PrefetchStage.RESOLVING },
                                downloadingPages = activity.count { it.admitted && !it.settled && it.stage == PrefetchStage.DOWNLOADING },
                                checkingPages = activity.count { it.admitted && !it.settled && it.stage == PrefetchStage.CHECKING },
                                failedPages = failed, deferredPages = deferred,
                                receivedBytes = activity.sumOf { it.receivedBytes },
                                reason = when {
                                    queued > 0 && active == 0 -> "等待当前页或可用准备资源"
                                    failed > 0 -> pages.firstOrNull { it.failure != null }?.failure
                                    deferred > 0 -> pages.firstOrNull { it.deferred != null }?.deferred
                                        ?: "原图已不在缓存中，翻章时按需读取"
                                    else -> null
                                })
                        }
                        fun newWork() = PrefetchWork(policy::wakeAdmissions) {
                            viewModelScope.launch { publish() }
                        }
                        pages.forEach { item -> item.work = newWork() }
                        fun admissionDeferred(item: NextPage, stage: PrefetchStage) {
                            item.recoveryStage = stage
                            val runtime = policy.state.value.runtime
                            val seconds = minOf(runtime.prefetchRetryBaseSeconds.coerceAtLeast(1).toLong() *
                                (1L shl item.recoveries.coerceAtMost(31)), runtime.prefetchRetryMaxSeconds.coerceAtLeast(1).toLong())
                            item.retryAt = monotonicTimeMillis() + seconds * 1_000L
                        }
                        suspend fun inspect(item: NextPage, original: Page): ReaderPreparedArtifacts =
                            item.work.during(PrefetchStage.CHECKING) {
                                item.work.admission(PrefetchStage.CHECKING, null)
                                inspectReaderPreparedArtifacts(application, original, headers, source, item.cacheKey)
                            }
                        try {
                            coroutineScope {
                                fun launchPage(item: NextPage) = launch {
                                    try {
                                        withPrefetchImageInactivity(item.work, imageSeconds.toLong() * 1_000L) {
                                            val available = downloads.resolvePrefetchPage(manga.id, chapter.id, item.page)
                                            if (available == null) { item.deferred = "下一话原图暂不可准备"; return@withPrefetchImageInactivity }
                                            item.original = available
                                            item.cacheKey = readerPageCacheKey(available, headers, source)
                                            val cached = inspect(item, available)
                                            item.ready = cached.originalPresent
                                            if (cached.originalPresent == true) {
                                                if (!available.url.startsWith("file:")) cached.originalBytes?.let(item.ticket::accept)
                                                return@withPrefetchImageInactivity
                                            }
                                            if (!currentSession() || !policy.state.value.crossChapter) return@withPrefetchImageInactivity
                                            if (source == "jmcomic") {
                                                item.original = prefetchJmPage(application, owner, chapter.id, available,
                                                    item.ticket, item.work)
                                                item.original?.let { item.cacheKey = readerPageCacheKey(it, headers, source) }
                                            } else if (source.startsWith("js_")) {
                                                item.original = prefetchScriptPage(application, owner, chapter.id, available,
                                                    item.ticket, item.work)
                                            } else {
                                                val result = prefetchOriginalPageResult(application, available, headers, source,
                                                    item.ticket, item.work)
                                                item.cacheKey = result.diskCacheKey ?: item.cacheKey
                                                if (result.failure != null) item.failure = "下一话图片准备失败，翻章时可重试"
                                                else if (!result.received) {
                                                    item.deferred = "当前资源或来源限制，下一话稍后准备"
                                                    result.admissionDeferredStage?.let { admissionDeferred(item, it) }
                                                }
                                            }
                                            if (!currentSession()) return@withPrefetchImageInactivity
                                            item.original?.let { original ->
                                                val artifacts = inspect(item, original)
                                                item.ready = artifacts.originalPresent
                                                artifacts.originalBytes?.let { bytes ->
                                                    if (item.ticket.used < bytes) item.ticket.accept(bytes - item.ticket.used)
                                                    else item.ticket.release(item.ticket.used - bytes)
                                                }
                                            }
                                        }
                                    } catch (failure: PrefetchImageInactivityException) {
                                        if (failure.sourceFailure) item.failure = "下一话图片等待新数据超时，翻章时可重试"
                                        else {
                                            item.deferred = "资源等待较久，下一话稍后准备"
                                            failure.blockedStage?.let { admissionDeferred(item, it) }
                                        }
                                    } catch (_: TimeoutCancellationException) { item.deferred = "下一话准备等待超时，翻章时按需读取" }
                                    catch (cancel: CancellationException) { throw cancel }
                                    catch (_: PrefetchBudgetExceeded) { item.deferred = "下一话准备窗口容量已用完" }
                                    catch (_: ExperienceAdmissionDeferred) {
                                        item.deferred = "当前页优先，下一话准备暂缓"
                                        admissionDeferred(item, item.work.snapshot().stage)
                                    }
                                    catch (_: JmImageRefreshRequired) { item.deferred = "来源会话已变化，下一话需重新准备" }
                                    catch (_: ScriptImageRefreshRequired) { item.deferred = "来源会话已变化，下一话需重新准备" }
                                    catch (_: DownloadStorageException) { item.deferred = "本机存储暂不可用，下一话稍后准备" }
                                    catch (_: DownloadRouteException) { item.deferred = "线路配置暂不可用，下一话稍后准备" }
                                    catch (_: OutOfMemoryError) { item.deferred = "内存压力下暂缓下一话准备" }
                                    catch (_: Exception) { item.failure = "下一话图片准备失败，翻章时可重试" }
                                    finally { item.done = true; publish() }
                                }
                                val jobs = pages.associateWith { launchPage(it) }.toMutableMap()
                                publish()
                                while (currentSession()) {
                                    if (jobs.values.all { it.isCompleted }) {
                                        val scanStarted = monotonicTimeMillis()
                                        val admissionRevision = policy.admissionChanges.value
                                        // Verify before refunding a failed attempt: successfully cached originals
                                        // remain charged to this same window and never trigger a new transfer.
                                        pages.forEach { item -> item.original?.let { original ->
                                            val artifact = inspect(item, original)
                                            item.ready = artifact.originalPresent
                                            if (item.ready == true && (!original.url.startsWith("file:") || item.ticket.used > 0L)) {
                                                artifact.originalBytes?.let { bytes ->
                                                    try {
                                                        if (item.ticket.used < bytes) item.ticket.accept(bytes - item.ticket.used)
                                                        else item.ticket.release(item.ticket.used - bytes)
                                                    } catch (_: PrefetchBudgetExceeded) { window.exhaust() }
                                                }
                                            }
                                        } }
                                        for (item in pages) {
                                            val stage = item.recoveryStage ?: continue
                                            if (!currentSession()) break
                                            if (item.ready == true || item.failure != null ||
                                                item.recoveries >= policy.state.value.runtime.prefetchRetries.coerceAtLeast(0) ||
                                                monotonicTimeMillis() < item.retryAt) continue
                                            val estimate = policy.estimatedPageBytes(item.page.url)
                                            if (!policy.canWritePrefetch() || !policy.canRecoverPrefetchAdmission(stage, source, estimate)) continue
                                            // Only an explicitly observed admission blockage can reach here.
                                            // All old jobs have settled, so closing its receipt cannot race a late writer.
                                            item.ticket.close()
                                            item.ticket = window.child()
                                            item.recoveryStage = null
                                            if (estimate > item.ticket.remaining) {
                                                item.deferred = "下一话准备窗口容量已用完"
                                                continue
                                            }
                                            item.recoveries++
                                            item.done = false; item.deferred = null; item.ready = null
                                            item.work = newWork()
                                            jobs[item] = launchPage(item)
                                        }
                                        publish()
                                        if (jobs.values.all { it.isCompleted }) {
                                            // Release events wake recovery without cancelling siblings or resetting
                                            // source failures. Sampling remains bounded when no event arrives.
                                            withTimeoutOrNull(cacheSeconds.coerceAtLeast(1).toLong() * 1_000L) {
                                                policy.admissionChanges.first { it != admissionRevision }
                                            }
                                            // A burst of unrelated permit events must not turn cache
                                            // inspection into a tight loop or manufacture retry attempts.
                                            delay((250L - (monotonicTimeMillis() - scanStarted)).coerceAtLeast(1L))
                                        } else delay(250L)
                                    } else {
                                        publish()
                                        delay(250L)
                                    }
                                }
                                jobs.values.forEach { it.cancel() }
                                if (!currentSession()) throw CancellationException("下一话准备已更新")
                            }
                        } finally {
                            windowLive = false
                            // coroutineScope settles every transfer before returning these child byte tickets.
                            pages.forEach { it.ticket.close() }
                            window.close()
                        }
                    }
            } catch (_: TimeoutCancellationException) {
                if (currentSession()) _nextChapterPreparation.value = initial.copy(
                    phase = ReaderNextChapterPreparationPhase.DEFERRED, reason = "下一话目录准备等待超时")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (currentSession()) _nextChapterPreparation.value = initial.copy(
                    phase = ReaderNextChapterPreparationPhase.FAILED, reason = "下一话目录准备失败，翻章时可重试")
            } finally {
                if (request == chapterPrefetchRequest && chapterPrefetchKey == key && !currentSession()) {
                    chapterPrefetchKey = null
                    _nextChapterPreparation.value = null
                }
            }
        }
    }

    fun retryDownload(entry: DownloadEntry) = retryDownload(entry, useLatestRoute = false)

    fun retryDownloadUsingLatestRoute(entry: DownloadEntry) = retryDownload(entry, useLatestRoute = true)

    internal fun refreshDownloadForQueue(entry: DownloadEntry, useLatestRoute: Boolean): Deferred<DownloadCommandResult> {
        val receipt = CompletableDeferred<DownloadCommandResult>()
        retryDownload(entry, useLatestRoute, receipt)
        return receipt
    }

    private fun retryDownload(entry: DownloadEntry, useLatestRoute: Boolean, receipt: CompletableDeferred<DownloadCommandResult>? = null) {
        if (entry.manga.sourceUrl == LOCAL_SOURCE_URL) {
            receipt?.complete(DownloadCommandResult.FAILED)
            viewModelScope.launch { messages.send("本地文件已损坏，请删除后重新导入。") }
            return
        }
        val expected = downloads.entryFor(entry.manga.id, entry.chapter.id)
        if (expected == null || (receipt != null && (expected !== entry || !expected.needsSourceRefresh))) {
            receipt?.complete(DownloadCommandResult.CHANGED)
            return
        }
        if (!expected.needsSourceRefresh) {
            if (useLatestRoute) downloads.retryUsingLatestRoute(expected.id) else downloads.retry(expected.id)
            return
        }
        viewModelScope.launch {
            try {
                val route = downloads.routeForReadingDownload(expected.manga.id, expected.chapter.id,
                    expectedEntry = expected, useLatestDefaults = useLatestRoute)
                enqueueChapterDownload(expected.manga, expected.chapter, refreshingEntry = expected, frozenRoute = route, receipt = receipt)
            } catch (cancel: CancellationException) {
                currentCoroutineContext().ensureActive()
                receipt?.complete(DownloadCommandResult.CHANGED)
                throw cancel
            } catch (failure: Exception) {
                receipt?.complete(DownloadCommandResult.FAILED)
                messages.send(preparationError(failure))
            }
        }.invokeOnCompletion { cause ->
            if (cause != null) receipt?.complete(if (cause is CancellationException) DownloadCommandResult.CANCELLED else DownloadCommandResult.FAILED)
        }
    }

    /** Leave reading at this work, retaining an already-open directory and its UI state. */
    fun returnToMangaDetail() {
        val session = _reading.value ?: return
        closeReader()
        if (_detail.value?.manga?.id == session.manga.id) return
        if (session.manga.sourceUrl == LOCAL_SOURCE_URL) {
            // openManga intentionally starts local reading; return must only show its directory.
            closeDetail()
            _detail.value = DetailState(session.manga,
                detail = MangaDetail(session.manga, downloadedChapters(session.manga.id)),
                loading = false, reviewsLoading = false)
        } else {
            openManga(session.manga)
        }
    }

    fun closeReader() {
        cancelReaderSourceStandbys()
        cancelSourceResumeChoice()
        cancelSourceSelection()
        cancelLibraryReading()
        readingRequestId++
        readingCatalogJob?.cancel()
        cancelNextChapterPrefetch()
        _reading.value = null
        visibleReading = null
    }

    fun loginWithPassword(username: String, password: String, route: CopyLoginRoute = CopyLoginRoute.COPY_SETTINGS) = authenticate {
        accountRepository.loginWithPassword(username, password, route)
    }

    fun cancelAccountLogin() {
        accountRestoreRetryJob?.cancel()
        accountRestoreRetryJob = null
        accountRequestId++
        accountJob?.cancel()
        accountRepository.cancelLoginAttempt()
        // Local-only restoration refreshes the preserved credential receipt.
        restoreAccount()
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

    fun cancelCopyShelfMetadata(clear: Boolean = false) {
        copyShelfRemoteRequest++
        copyShelfRemoteJob?.cancel()
        if (clear) _copyShelfRemote.value = CopyShelfRemoteState()
        else _copyShelfRemote.update { it.copy(loading = false) }
    }

    /** Cloud-owned collection/read ordering is fetched separately from collection import/upload. */
    fun refreshCopyShelfMetadata() {
        val owner = _account.value.profile?.username ?: return
        if (_account.value.busy) return
        cancelCopyShelfMetadata()
        val request = copyShelfRemoteRequest
        val version = accountRepository.sessionVersion
        val retained = _copyShelfRemote.value.takeIf { it.owner == owner } ?: CopyShelfRemoteState(owner)
        _copyShelfRemote.value = retained.copy(loading = true, error = null)
        copyShelfRemoteJob = viewModelScope.launch {
            try {
                val items = foregroundRequest("拷贝书架记录", 120_000L) { accountRepository.completeShelfMetadata() }
                if (request == copyShelfRemoteRequest && isCurrentAccount(version) && _account.value.profile?.username == owner)
                    _copyShelfRemote.value = CopyShelfRemoteState(owner, items, loaded = true)
            } catch (cancel: CancellationException) { throw cancel
            } catch (failure: Exception) {
                if (request == copyShelfRemoteRequest && isCurrentAccount(version) && _account.value.profile?.username == owner)
                    _copyShelfRemote.update { it.copy(loading = false,
                        error = accountError(failure, "拷贝书架记录暂未读取，可重试")) }
            }
        }
    }

    /** The chosen cloud chapter is a resume target, never a fabricated local reading event. */
    fun continuePreferredResume(manga: Manga): Boolean {
        val owner = _account.value.profile?.username ?: return false
        val target = library.resumeTargetFor(manga.id, owner) ?: return false
        libraryOpenJob = null
        _libraryOpening.value = null
        openSavedChapter(manga, target.chapterId, target.chapterTitle)
        return true
    }

    fun clearCopyProgress() {
        copyProgressRequest++
        copyProgressJob?.cancel()
        _copyProgress.value = null
    }

    /** This calls only the read-only account query, never the authenticated chapter-view endpoint. */
    fun refreshCopyProgress(manga: Manga) {
        clearCopyProgress()
        val owner = _account.value.profile?.username ?: return
        if (_account.value.busy || manga.sourceId != "copy" || SourceKeys.isEncoded(manga.id) ||
            manga.sourceUrl == LOCAL_SOURCE_URL || _detail.value?.manga?.id != manga.id) return
        val version = accountRepository.sessionVersion
        val request = copyProgressRequest
        _copyProgress.value = CopyProgressHintState(owner, manga.id, loading = true)
        copyProgressJob = viewModelScope.launch {
            try {
                val cloud = foregroundRequest("拷贝阅读进度", 30_000L) { accountRepository.cloudProgress(manga.id) }
                if (request != copyProgressRequest || !isCurrentAccount(version) || _detail.value?.manga?.id != manga.id)
                    return@launch
                _copyProgress.value = cloud?.takeIf { it.chapterId.isNotBlank() }?.let {
                    copyShelfSync.observeCloudProgress(manga, it.chapterId, it.chapterTitle)
                    CopyProgressHintState(owner, manga.id, cloudChapterId = it.chapterId, cloudChapterTitle = it.chapterTitle)
                }
                reconcileCopyProgress()
            } catch (cancel: CancellationException) { throw cancel
            } catch (failure: Exception) {
                if (request == copyProgressRequest && isCurrentAccount(version) && _detail.value?.manga?.id == manga.id)
                    _copyProgress.value = CopyProgressHintState(owner, manga.id,
                        error = accountError(failure, "暂未能读取拷贝进度"))
            }
        }
    }

    fun reconcileCopyProgress() {
        val state = _copyProgress.value ?: return
        val current = _detail.value ?: return
        val owner = _account.value.profile?.username ?: return
        if (state.owner != owner || state.mangaId != current.manga.id || state.loading || state.error != null) return
        val exact = current.detail?.chapters?.let { findSourceChapter(it, state.cloudChapterId) }
            ?.takeIf { it.sourceId == "copy" && !SourceKeys.isEncoded(it.id) }
        val target = library.resumeTargetFor(current.manga.id, owner)
        val selectedChapter = target?.chapterId ?: library.progressFor(current.manga.id)?.chapterId
        if (selectedChapter == state.cloudChapterId) {
            _copyProgress.value = null
            return
        }
        _copyProgress.value = state.copy(
            cloudChapterTitle = exact?.title?.takeIf(String::isNotBlank) ?: state.cloudChapterTitle,
            adopted = target?.chapterId == state.cloudChapterId,
            canAdopt = exact != null && selectedChapter != state.cloudChapterId,
        )
    }

    fun adoptCopyProgress() {
        val state = _copyProgress.value?.takeIf { it.canAdopt && !it.loading && it.error == null } ?: return
        val owner = _account.value.profile?.username ?: return
        val current = _detail.value ?: return
        if (state.owner != owner || state.mangaId != current.manga.id) return
        val chapter = current.detail?.chapters?.let { findSourceChapter(it, state.cloudChapterId) }
            ?.takeIf { it.sourceId == "copy" && !SourceKeys.isEncoded(it.id) } ?: return
        library.adoptResumeTarget(SavedResumeTarget(current.manga.id, chapter.id, chapter.title, owner))
        reconcileCopyProgress()
    }

    private fun authenticate(restoring: Boolean = false, action: suspend () -> AccountProfile?) {
        if (!restoring) {
            accountRestoreRetryJob?.cancel()
            accountRestoreRetryJob = null
            accountRestoreFailures = 0
        }
        clearCopyProgress()
        cancelCopyShelfMetadata(clear = true)
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
                val legacySaved = restoring && profile == null && accountRepository.hasSavedLogin()
                if (requestId != accountRequestId) return@launch
                _account.value = AccountUiState(profile = profile, busy = false,
                    savedCredentialPresent = legacySaved,
                    error = if (legacySaved) "旧登录凭据仍安全保留；请用账号密码重新登录以确认身份，收藏与历史未删除。" else null)
                if (profile != null) {
                    accountRestoreFailures = 0
                    accountRestoreRetryJob?.cancel()
                    accountRestoreRetryJob = null
                }
                copyShelfSync.accountChanged()
                services.readingOutbox.accountChanged()
                cloudFavoritesPage = -1
                cloudHistoryPage = -1
                if (profile != null) {
                    loadCloudFavorites(refresh = true)
                    visibleReading?.let { (manga, chapter) -> onChapterVisible(manga, chapter) }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (requestId == accountRequestId) {
                    val saved = if (restoring) try { accountRepository.hasSavedLogin() }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { false } else false
                    if (requestId != accountRequestId) return@launch
                    val retryAllowed = restoring && saved && (failure as? SourceException)
                        ?.httpStatus !in setOf(400, 401, 403, 422)
                    _account.value = AccountUiState(profile = accountRepository.profile.value, busy = false,
                        error = accountError(failure, "登录暂时未完成，请稍后重试。"),
                        savedCredentialPresent = saved,
                        autoRestorePending = retryAllowed,
                        canRetryConnection = accountRepository.canRetryLogin || (restoring && saved))
                    if (retryAllowed) scheduleAccountRestore(requestId, failure)
                }
            }
        }
    }

    /** Retries verification with encrypted saved credentials, never a plaintext password.
     *  A definitive authentication rejection requires user action; network errors do not. */
    private fun scheduleAccountRestore(request: Long, failure: Exception) {
        accountRestoreRetryJob?.cancel()
        val step = accountRestoreFailures.coerceAtMost(6)
        accountRestoreFailures++
        val exponential = (15_000L shl step).coerceAtMost(15L * 60_000L)
        val retryAfter = ((failure as? SourceException)?.retryAfterSeconds ?: 0L)
            .coerceIn(0L, 3_600L) * 1_000L
        accountRestoreRetryJob = viewModelScope.launch {
            delay(maxOf(exponential, retryAfter))
            if (request == accountRequestId && _account.value.profile == null &&
                !_account.value.busy && websiteLoginAttempt == null && !accountCleanupFailed) {
                restoreAccount()
            }
        }
    }

    fun logout() {
        accountRestoreRetryJob?.cancel()
        accountRestoreRetryJob = null
        accountRestoreFailures = 0
        val previousOwner = _account.value.profile?.username
        clearCopyProgress()
        cancelCopyShelfMetadata(clear = true)
        websiteLoginAttempt = null
        val request = ++accountRequestId
        accountJob?.cancel()
        cancelAccountRequests()
        cloudFavoritesPage = -1
        cloudHistoryPage = -1
        cloudFavoritesFailedPage = null
        cloudHistoryFailedPage = null
        _account.value = AccountUiState(busy = true)
        previousOwner?.let(library::clearResumeTargets)
        // Session invalidation starts on Main; protected disk/Keystore cleanup belongs
        // to the application's owner and cannot be cancelled by an outgoing screen.
        services.scope.launch {
            try {
                accountRepository.logout()
                if (request != accountRequestId) return@launch
                accountCleanupFailed = false
                _account.value = AccountUiState(busy = false)
                copyShelfSync.accountChanged()
                services.readingOutbox.accountChanged()
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (request != accountRequestId) return@launch
                accountCleanupFailed = true
                _account.value = AccountUiState(busy = false,
                    error = accountError(failure, "账号已退出，但设备凭据清理未完成，请重试。"), canRetryConnection = true)
            }
        }
    }

    fun refreshAccount() {
        if (_account.value.busy) return
        if (accountCleanupFailed) logout()
        else if (accountRepository.hasPendingLogin || _account.value.profile == null) restoreAccount()
        else {
            _account.update { it.copy(error = null) }
            loadCloudFavorites(refresh = true)
        }
    }

    fun refreshCloudFavorites() = loadCloudFavorites(refresh = true)

    fun moreCloudFavorites() = loadCloudFavorites(refresh = false, retryPage = cloudFavoritesFailedPage)

    private fun loadCloudFavorites(refresh: Boolean, retryPage: Int? = null) {
        val state = _account.value
        if (state.profile == null || state.busy || (!refresh && (state.favoritesLoading ||
            (retryPage == null && !state.favoritesHasMore && cloudFavoritesPage >= 0)))) return
        cloudFavoritesJob?.cancel()
        val requestId = ++cloudFavoritesRequestId
        val version = accountRepository.sessionVersion
        val replacing = refresh || retryPage == 0
        val firstPage = if (replacing) 0 else retryPage ?: cloudFavoritesPage + 1
        val retainedPage = if (replacing) cloudFavoritesPage.coerceAtLeast(0) else firstPage
        cloudFavoritesFailedPage = null
        _account.update { it.copy(favoritesLoading = true, favoritesRefreshing = replacing, favoritesError = null) }
        cloudFavoritesJob = viewModelScope.launch {
            try {
                // Refresh the loaded range atomically. Never truncate a scrolled collection to page zero.
                val refreshed = mutableListOf<Manga>()
                var page = firstPage
                var hasMore: Boolean
                do {
                    val result = accountRepository.favorites(page)
                    if (!isCurrentAccount(version) || requestId != cloudFavoritesRequestId) return@launch
                    refreshed.addAll(result.items)
                    hasMore = result.hasMore
                    if (!hasMore || page >= retainedPage) break
                    page++
                } while (true)
                cloudFavoritesPage = page
                _account.update { it.copy(
                    favorites = (if (replacing) refreshed else it.favorites + refreshed).distinctBy(Manga::id),
                    favoritesLoading = false, favoritesRefreshing = false, favoritesHasMore = hasMore, favoritesError = null,
                ) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (isCurrentAccount(version) && requestId == cloudFavoritesRequestId) {
                    // A failed range refresh retries the entire range, leaving the old snapshot intact.
                    cloudFavoritesFailedPage = if (replacing) 0 else firstPage
                    _account.update { it.copy(favoritesLoading = false, favoritesRefreshing = false,
                        favoritesError = accountError(failure, "云端收藏暂时无法读取，请重试。")) }
                }
            }
        }
    }

    fun refreshCloudHistory() = loadCloudHistory(refresh = true)

    fun moreCloudHistory() = loadCloudHistory(refresh = false, retryPage = cloudHistoryFailedPage)

    private fun loadCloudHistory(refresh: Boolean, retryPage: Int? = null) {
        val state = _account.value
        if (state.profile == null || state.busy || (!refresh && (state.historyLoading ||
            (retryPage == null && !state.historyHasMore && cloudHistoryPage >= 0)))) return
        cloudHistoryJob?.cancel()
        val requestId = ++cloudHistoryRequestId
        val version = accountRepository.sessionVersion
        val replacing = refresh || retryPage == 0
        val firstPage = if (replacing) 0 else retryPage ?: cloudHistoryPage + 1
        val retainedPage = if (replacing) cloudHistoryPage.coerceAtLeast(0) else firstPage
        cloudHistoryFailedPage = null
        _account.update { it.copy(historyLoading = true, historyRefreshing = replacing, historyError = null) }
        cloudHistoryJob = viewModelScope.launch {
            try {
                val refreshed = mutableListOf<CloudHistoryEntry>()
                var page = firstPage
                var hasMore: Boolean
                do {
                    val result = accountRepository.history(page)
                    if (!isCurrentAccount(version) || requestId != cloudHistoryRequestId) return@launch
                    refreshed.addAll(result.items)
                    hasMore = result.hasMore
                    if (!hasMore || page >= retainedPage) break
                    page++
                } while (true)
                cloudHistoryPage = page
                _account.update { it.copy(
                    history = (if (replacing) refreshed else it.history + refreshed).distinctBy { row -> row.manga.id },
                    historyLoading = false, historyRefreshing = false, historyHasMore = hasMore, historyError = null,
                ) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (isCurrentAccount(version) && requestId == cloudHistoryRequestId) {
                    cloudHistoryFailedPage = if (replacing) 0 else firstPage
                    _account.update { it.copy(historyLoading = false, historyRefreshing = false,
                        historyError = accountError(failure, "云端记录暂时无法读取，请重试。")) }
                }
            }
        }
    }

    /** Explicit local adoption of a manga in this account's loaded collection; no redundant owner POST. */
    fun addCloudMangaToLibrary(manga: Manga) {
        if (manga.sourceId != "copy" || SourceKeys.isEncoded(manga.id)) return
        val version = accountRepository.sessionVersion
        if (!isCurrentAccount(version)) return
        viewModelScope.launch {
            library.awaitReady()
            if (!isCurrentAccount(version)) return@launch
            val current = _account.value.favorites.firstOrNull { it.id == manga.id }
                ?: copyShelfSync.state.value.takeIf { it.owner == _account.value.profile?.username && it.favoritesReady }
                    ?.cloudFavorites?.firstOrNull { it.id == manga.id }
                ?: _account.value.history.firstOrNull { it.manga.id == manga.id }?.manga
                ?: return@launch
            if (!library.isFavorite(current.id)) {
                library.toggleFavorite(current)
                copyShelfSync.onLocalFavoriteChanged(current, true)
                messages.send("已加入本机书架")
            }
        }
    }

    fun continueCloudHistory(entry: CloudHistoryEntry) {
        val state = _account.value
        if (state.profile == null || state.busy) return
        val current = state.history.firstOrNull {
            it.manga.id == entry.manga.id && it.chapterId == entry.chapterId
        } ?: return
        if (current.chapterId.isBlank()) openManga(current.manga)
        else openSavedChapter(current.manga, current.chapterId, current.chapterTitle)
    }

    fun toggleFavorite(manga: Manga) = toggleFavorite(manga) {}

    fun toggleFavorite(manga: Manga, onChanged: (Boolean) -> Unit) {
        if (!library.ready.value) {
            viewModelScope.launch { library.awaitReady(); toggleFavorite(manga, onChanged) }
            return
        }
        val saved = library.toggleFavoriteAndGet(manga) ?: return
        copyShelfSync.onLocalFavoriteChanged(manga, saved)
        onChanged(saved)
    }

    fun removeFavorite(manga: Manga) {
        if (!library.ready.value) {
            viewModelScope.launch { library.awaitReady(); removeFavorite(manga) }
            return
        }
        if (_libraryOpening.value?.manga?.id == manga.id) cancelLibraryReading()
        if (!library.isFavorite(manga.id)) return
        library.removeFavorite(manga.id)
        copyShelfSync.onLocalFavoriteChanged(manga, false)
    }

    fun removeHistory(mangaId: String) {
        if (_libraryOpening.value?.manga?.id == mangaId) cancelLibraryReading()
        library.removeHistory(mangaId)
    }

    /** Compatibility entry: membership changes stay local until the explicit collection preview. */
    fun setCloudFavorite(manga: Manga, favorite: Boolean) {
        if (!library.ready.value) {
            viewModelScope.launch { library.awaitReady(); setCloudFavorite(manga, favorite) }
            return
        }
        if (library.isFavorite(manga.id) != favorite) library.toggleFavorite(manga)
        copyShelfSync.onLocalFavoriteChanged(manga, favorite)
    }

    fun retryAccountSync() {
        if (_account.value.profile == null || _account.value.busy) return
        copyShelfSync.check()
        services.readingOutbox.retry()
    }

    /** Called after a visible image succeeds; public reading never awaits this side channel. */
    fun onChapterVisible(manga: Manga, chapter: Chapter) {
        if (_reading.value?.manga?.id != manga.id) return
        if (visibleReading?.second?.id != chapter.id) {
            if (visibleReading != null) {
                cancelSourceSelection()
                cancelReaderSourceStandbys()
            }
            cancelNextChapterPrefetch()
        }
        visibleReading = manga to chapter
        if (readerSourceUndo?.afterChapterId != chapter.id) _readerSourceSwitchUndoAvailable.value = false
        if (chapter.sourceId != "copy" || SourceKeys.isEncoded(chapter.id) || manga.sourceId != "copy") return
        if (manga.sourceUrl == LOCAL_SOURCE_URL) return
        if (_account.value.profile == null || _account.value.busy) return
        services.readingOutbox.submit(manga, chapter)
    }

    fun onMangaReadingProgress(manga: Manga, chapter: Chapter, pageIndex: Int, pageCount: Int) {
        if (_reading.value?.manga?.id != manga.id || visibleReading?.second?.id != chapter.id ||
            manga.sourceUrl == LOCAL_SOURCE_URL) return
        val ordinary = bodySources.entries.value.filter { !it.adult && !it.source.adult && !it.removed && it.isInstalled }
            .mapTo(mutableSetOf()) { it.source.id }
        if (manga.sourceId !in ordinary || chapter.sourceId !in ordinary) return
        services.mangaTracking.reportProgress(manga, chapter, pageIndex, pageCount,
            currentChapters = _reading.value?.chapters.orEmpty())
    }

    fun continueTrackedManga(manga: Manga, chapter: Chapter) {
        val ordinary = bodySources.entries.value.filter { !it.adult && !it.source.adult && !it.removed && it.isInstalled }
            .mapTo(mutableSetOf()) { it.source.id }
        if (manga.sourceId !in ordinary || chapter.sourceId !in ordinary || manga.sourceUrl == LOCAL_SOURCE_URL) return
        openSavedChapter(manga, chapter.id, chapter.title, fallback = chapter)
    }

    private fun isCurrentAccount(version: Long) = accountRepository.sessionVersion == version &&
        _account.value.profile != null && !_account.value.busy

    private fun cancelAccountRequests() {
        cloudFavoritesRequestId++
        cloudHistoryRequestId++
        cloudFavoritesJob?.cancel()
        cloudHistoryJob?.cancel()
    }

    private fun accountError(failure: Exception, fallback: String) =
        (failure as? SourceException)?.message ?: fallback

    fun downloadChapter(chapter: Chapter, route: DownloadRouteMode? = null) {
        val manga = _detail.value?.manga ?: return
        enqueueChapterDownload(manga, chapter, route)
    }

    fun downloadChapterVariant(manga: Manga, chapter: Chapter, variant: ChapterVariant, route: DownloadRouteMode? = null) {
        if (chapter.variants.none { it == variant }) return
        enqueueChapterDownload(manga, resolveChapter(chapter, variant), route)
    }

    fun cancelChapterPreparation(id: String, generation: Long) = services.preparations.cancel(id, generation)

    fun retryChapterPreparation(id: String, generation: Long) =
        services.preparations.retry(id, generation, useLatestRoute = false)

    fun retryChapterPreparationUsingLatestRoute(id: String, generation: Long) =
        services.preparations.retry(id, generation, useLatestRoute = true)

    private fun enqueueChapterDownload(
        manga: Manga,
        chapter: Chapter,
        routeMode: DownloadRouteMode? = null,
        refreshingEntry: DownloadEntry? = null,
        frozenRoute: DownloadRouteSnapshot? = null,
        receipt: CompletableDeferred<DownloadCommandResult>? = null,
    ) {
        services.preparations.enqueue(manga, chapter, routeMode, refreshingEntry, frozenRoute, receipt)
        if (receipt == null) messages.trySend("已加入章节准备队列，可在下载页查看或取消")
    }

    private fun preparationError(failure: Exception): String = when {
        failure is DownloadRouteException -> failure.message ?: "下载线路不可用，请检查设置后重试。"
        failure is SourceException && failure.retryAfterSeconds > 0 ->
            "来源暂时限制请求，请在 ${failure.retryAfterSeconds} 秒后重试。"
        failure is SourceException && failure.httpStatus in listOf(401, 403) ->
            "来源拒绝获取章节信息，请检查来源账号或下载线路。"
        failure is SourceException && failure.httpStatus != null ->
            "来源未能返回章节信息（HTTP ${failure.httpStatus}），请稍后重试。"
        else -> "未能准备本章下载，请检查下载线路或稍后重试。"
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
