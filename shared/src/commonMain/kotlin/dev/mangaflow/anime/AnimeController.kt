package dev.mangaflow.anime

import dev.mangaflow.anime.player.AnimePlayer
import dev.mangaflow.anime.player.AnimePlayerPhase
import dev.mangaflow.anime.player.AnimePlayerRequest
import dev.mangaflow.anime.player.AnimePlayerState
import dev.mangaflow.anime.player.AnimeNativeVideoStatistics
import dev.mangaflow.anime.player.AnimePlayerOptions
import dev.mangaflow.anime.player.AnimePreparedVideoRequest
import dev.mangaflow.anime.player.AnimeStartupCacheRequest
import dev.mangaflow.anime.player.AnimeStartupCacheResult
import dev.mangaflow.anime.player.AnimeVideoEnhancementMode
import dev.mangaflow.anime.player.AnimeLocalMedia
import dev.mangaflow.anime.player.createAnimePlayer
import dev.mangaflow.anime.sources.AnimeMetadataRepository
import dev.mangaflow.anime.sources.DoubanAnimeRepository
import dev.mangaflow.anime.sources.DOUBAN_PROVIDER
import dev.mangaflow.anime.sources.AnimeMetadataIdentityException
import dev.mangaflow.anime.sources.AnimeProvider
import dev.mangaflow.anime.sources.AnimeDiscoveryRepository
import dev.mangaflow.anime.sources.AnimeDiscussionController
import dev.mangaflow.anime.sources.AnimeSourceBrowseController
import dev.mangaflow.anime.sources.AnimeSourceException
import dev.mangaflow.anime.sources.AnimeSourceRepository
import dev.mangaflow.anime.sources.AnimeSourceStage
import dev.mangaflow.anime.sources.AnimeTrackingRepository
import dev.mangaflow.anime.sources.TvtFunPlaybackException
import dev.mangaflow.anime.sources.animeContentIdentity
import dev.mangaflow.anime.sources.lanerc.LanercCapabilityException
import dev.mangaflow.anime.sources.lanerc.LanercMediaSniffingUnsupported
import dev.mangaflow.anime.sources.lanerc.LanercProvider
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.SourceNetworkSettings
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.platform.randomUuid
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.enhancement.*
import dev.mangaflow.enhancement.engine.EngineContent
import dev.mangaflow.enhancement.engine.EngineModel
import dev.mangaflow.enhancement.engine.EnhancementEngineIds
import dev.mangaflow.experience.ExperiencePolicyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

private val SOURCE_HEIGHT = Regex("""(?<!\d)([1-8]\d{2,3})\s*[pP](?!\d)""")
private const val ONLINE_ANIME_SOURCE_BYTES = 24L * 1024 * 1024
private const val ONLINE_ANIME_RESULT_BYTES = 64L * 1024 * 1024
private const val ANIME_SOURCE_PARALLELISM = 4
private const val ANIME_STARTUP_RESOLVE_PARALLELISM = 2
private const val ANIME_STARTUP_HEDGE_DELAY_MS = 650L
private const val ANIME_STANDBY_LIFETIME_MS = 15_000L

/** Application-owned data and playback. Screens keep only disclosure, drafts and scroll state. */
class AnimeController(private val context: PlatformContext, private val scope: CoroutineScope,
    downloadStore: () -> dev.mangaflow.anime.downloads.AnimeDownloadStore,
    videoPreparationStore: () -> VideoPreparationStore) {
    private val enhancementCenter by lazy { EnhancementCenter.get(context) }
    private var observedEnhancementSession: String? = null
    private var observedEnhancementTimeline = -1L
    private var reducedAutoEnhancementSession: String? = null
    private var onlineVideoJob: Job? = null
    private var onlineVideoModel: EngineModel? = null
    private var onlineVideoTimeline: Pair<Long, Long>? = null
    private var onlineVideoNotBefore = 0L
    private var onlineVideoCost = 0L
    private var onlineVideoWindowTarget = 0L
    private var onlineVideoResultBudget = 0L
    private var onlineVideoGeneration = 0L
    private val metadata = AnimeMetadataRepository(context)
    private val douban = DoubanAnimeRepository(context)
    private val sources = AnimeSourceRepository(context)
    private val unifiedSearch = AnimeUnifiedSearch(scope, metadata, sources, douban)
    internal val searchState = unifiedSearch.state
    private val discovery = AnimeDiscoveryRepository(context)
    val sourceBrowse = AnimeSourceBrowseController(context, scope, sources)
    internal val discussion by lazy { AnimeDiscussionController(context, scope, discovery, metadata) }
    private val tracking = AnimeTrackingRepository(context)
    private val library = AnimeLibraryStore.get(context)
    internal val ratings by lazy { AnimeRatingsController(context, scope, metadata, discovery, douban, library) }
    private val routing = DownloadRoutingStore.get(context)
    private val mutableState = MutableStateFlow(AnimeScreenState())
    val state = mutableState.asStateFlow()
    private val discoveryLoads = AnimeDiscoveryLoads(scope, discovery,
        currentFeed = { mutableState.value.discoveryFeeds[it] },
        publishFeed = { key, feed -> mutableState.update { state ->
            val subjectId = state.detail?.subject?.id
            val scores = if (subjectId == null) emptyList() else
                feed.items.firstOrNull { it.subject?.id == subjectId }?.sourceScores.orEmpty()
            state.copy(discoveryFeeds = state.discoveryFeeds + (key to feed),
                detailScores = (state.detailScores + scores.filter {
                    it.provider !in detailRatingRequests && it.provider !in state.detailScoreChecks
                })
                    .distinctBy { it.provider })
        } })
    val extensions get() = sources.extensions
    internal val builtInVideoSources get() = sources.builtInVideoSources
    val danmaku by lazy { dev.mangaflow.anime.danmaku.AnimeDanmakuController(context, scope, sources) }
    private val downloadsOwner = lazy { downloadStore().also {
        it.setPlaybackBusy(playbackBusy())
    } }
    val downloads get() = downloadsOwner.value
    private val enhancementsOwner = lazy { videoPreparationStore() }
    val enhancements get() = enhancementsOwner.value
    val sourceHomes: Map<String, String> get() = sources.providers.associate { it.id to it.homeUrl } +
        dev.mangaflow.anime.sources.lanerc.LANERC_SOURCE_DIRECTORY.filter { descriptor ->
            sources.extensions.state.value.any { it.id == descriptor.id && it.installed && it.enabled }
        }.associate { it.id to it.homeUrl } + mapOf(
        "anime-bangumi" to "https://api.bgm.tv/", "anime-anilist" to "https://graphql.anilist.co/",
        DOUBAN_PROVIDER to "https://movie.douban.com/",
        "anime-danmaku-dandanplay" to "https://api.dandanplay.net/")
    private var playerInstance: AnimePlayer? = null
    /** Diagnostic reads must never create a native player or start an anime session. */
    fun peekNativeVideoStatistics(): AnimeNativeVideoStatistics? = playerInstance?.state?.value?.nativeVideoStatistics
    private val mutablePlaybackPhase = MutableStateFlow(AnimePlayerPhase.IDLE)
    internal val playbackPhase = mutablePlaybackPhase.asStateFlow()
    val player: AnimePlayer
        get() = playerInstance ?: createAnimePlayer(context, scope).also {
            playerInstance = it
            it.configure(playerOptions(mutableState.value.settings))
            it.setForeground(foreground)
            observePlayer(it)
        }

    private var discoveryDate: String? = null
    private var discoveryVisible = false
    private val discoveryOptions = mutableMapOf<Pair<AnimeDiscoveryTab, AnimeDiscoveryProvider>, AnimeDiscoveryQuery>()
    private var detailGeneration = 0L
    private var detailOpenedAt: Long? = null
    private var retryNativeReference: AnimeSourceTitle? = null
    private var searchGeneration = 0L
    private var previewGeneration = 0L
    private var resolveGeneration = 0L
    private var catalogGeneration = 0L
    private val catalogRequests = mutableMapOf<String, Long>()
    private var detailJob: Job? = null
    private data class EpisodeOptionsInput(val detail: AnimeDetail, val bindings: List<AnimeSourceBinding>,
        val catalogs: List<AnimeSourceCatalog>, val sourceNames: Map<String, String>,
        val sourceOwners: Map<String, Pair<String?, DownloadRouteSnapshot?>>)
    private var episodeOptionsJob: Job? = null
    private var episodeOptionsGeneration = 0L
    private var episodeOptionsInput: EpisodeOptionsInput? = null
    private var appliedEpisodeOptions: EpisodeOptionsInput? = null
    private var projectedEpisodeMembers = emptyMap<String, Pair<AnimeSourceCatalog, Set<AnimeSourceEpisode>>>()
    private data class DetailRatingRequest(val subject: AnimeSubject, val ownerId: String,
        val generation: Long, val route: DownloadRouteSnapshot)
    private val detailRatingJobs = mutableMapOf<AnimeDiscoveryProvider, Job>()
    private val detailRatingRequests = mutableMapOf<AnimeDiscoveryProvider, DetailRatingRequest>()
    private var nativeMetadataJob: Job? = null
    private var nativeDiscussionOwner: Pair<String, AnimeSubject?>? = null
    private var sourceJob: Job? = null
    private var previewJob: Job? = null
    private var previewSourceId: String? = null
    private var previewSourceRevision: String? = null
    private var resolveJob: Job? = null
    private var autoPlayJob: Job? = null
    private var autoPlayGeneration = 0L
    private var autoPlaybackDeadline = 0L
    private var openingAddresses: OpeningAddresses? = null
    private var associationGeneration = 0L
    private var associationEpisodeId: String? = null
    private val associationJobs = mutableMapOf<String, Job>()
    private var associationSequence = 0L
    private val associationAttempts = mutableMapOf<String, Long>()
    private data class AssociationSignature(
        val subjectId: String,
        val title: String,
        val originalTitle: String,
        val year: Int?,
        val externalIds: Map<String, String>,
        val revision: String?,
        val route: DownloadRouteSnapshot?,
        val episodeId: String?,
        val episodeNumbers: Pair<Double, Double?>?,
    )
    private val associationSignatures = mutableMapOf<String, AssociationSignature>()
    private val associationCandidates = mutableMapOf<String, Pair<String?, List<AnimeSourceTitle>>>()
    private data class SourceCatalogueCandidate(val catalog: AnimeSourceCatalog, val revision: String?,
        val route: DownloadRouteSnapshot?, val request: Long?, val owner: Long, val restoreEpoch: Long)
    private val catalogueCandidates = mutableMapOf<String, SourceCatalogueCandidate>()
    private var cataloguePriority = emptyList<String>()
    private var catalogueJob: Job? = null
    private var catalogueRebuildRequested = false
    private val associationPermits = Semaphore(4)
    private val associationScriptPermits = Semaphore(2)
    // Leave capacity for a discovered work's catalogue while other sources are still searching.
    private val associationSearchPermits = Semaphore(3)
    private val associationScriptSearchPermits = Semaphore(1)
    private var boundCatalogJob: Job? = null
    private var relatedJob: Job? = null
    private var prepareJob: Job? = null
    private var cacheNextJob: Job? = null
    private var sleepJob: Job? = null
    private val sourceSearchJobs = mutableMapOf<String, Job>()
    private val sourceSearchRequests = mutableMapOf<String, Long>()
    private var sourceSearchSequence = 0L
    private var sourceQuery = ""
    private var foreground = true
    private var resolvingSourceId: String? = null
    private var preparationTarget: PreparationTarget? = null
    private var prepared: PreparedPlayback? = null
    private var rollback: PlaybackReceipt? = null
    private var recoveryResume: RecoveryResume? = null
    private var playbackIntent: PlaybackIntentTiming? = null
    private var resumeRequest: Pair<String, String>? = null
    private var resumeAfterCompleted: Boolean = false
    private var restoreEpoch = 0L
    private val recoveryOptions = mutableSetOf<String>()
    private val playbackAddressRetries = mutableSetOf<PlaybackHistoryKey>()
    private data class PlaybackHistoryKey(val subjectId: String, val episodeId: String,
        val candidate: String, val route: DownloadRouteSnapshot)
    private data class OpeningAddress(val asset: AnimePlaybackAsset, val elapsedMs: Long, val observedAt: Long)
    private data class OpeningRequest(val sourceId: String, val result: Deferred<OpeningAddress>)
    private class OpeningAddresses(val detailOwner: Long, val subjectId: String, val episodeId: String,
        val preferredHeight: Int, val restoreEpoch: Long, parent: CoroutineScope) {
        val job = SupervisorJob(parent.coroutineContext[Job])
        val owner = CoroutineScope(parent.coroutineContext + job)
        val permits = Semaphore(ANIME_STARTUP_RESOLVE_PARALLELISM)
        val requests = linkedMapOf<PlaybackHistoryKey, OpeningRequest>()
        val warmed = mutableSetOf<PlaybackHistoryKey>()
        var expiry: Job? = null
    }
    private data class PlaybackFailureReceipt(val failure: AnimePlaybackFailure, val observedAt: Long)
    private data class PlaybackMeasurementReceipt(val option: AnimeEpisodeOption, val measurements: AnimePlaybackMeasurements)
    private data class SourceMeasurementTarget(val episodeId: String, val lines: List<AnimeEpisodeOption>)
    private data class PlaybackProbe(
        val addressObservedAt: Long,
        val addressMs: Long?,
        val declaredQuality: String?,
        val media: AnimeMediaPreflight,
        val preferredHeight: Int,
    )
    private class PlaybackResolutionException(val failure: AnimePlaybackFailure, cause: Exception) :
        IllegalStateException(failure.description, cause)
    private val playbackFailures = mutableMapOf<PlaybackHistoryKey, PlaybackFailureReceipt>()
    private val playbackMeasurements = mutableMapOf<PlaybackHistoryKey, PlaybackMeasurementReceipt>()
    private var sourceMeasurementsJob: Job? = null
    private var sourceMeasurementsUserRequested = false
    private var sourceMeasurementsEnqueue: (() -> Unit)? = null
    private var sourceMeasurementsGeneration = 0L
    private val playbackAttemptOrder = mutableMapOf<PlaybackHistoryKey, Long>()
    private val playbackSourceAttemptOrder = mutableMapOf<PlaybackHistoryKey, Long>()
    private var playbackAttemptSequence = 0L
    private val recentPlaybackSources = mutableMapOf<String, PlaybackSourceReceipt>()
    private var sourceRecoveryWindowStarted = false
    private var playbackDiscoveryRetried = false
    private val playbackStartupRefreshes = mutableSetOf<String>()
    private val playbackEpochs = mutableMapOf<String, Long>()
    private var active: ActivePlayback? = null
    private var pending: PendingPlayback? = null
    private var motionIntentSession: String? = null
    private var motionIntentPolicy: EnhancementPolicy? = null
    private var realtimeMotionIntent = 0L
    private val reviewSnapshots = mutableMapOf<String, ReviewContext>()
    private var lastCheckpointAt = 0L
    private var lastSavedPosition = -1L
    private var lastSavedPhase: AnimePlayerPhase? = null

    private data class ActivePlayback(
        val detail: AnimeDetail,
        val episode: AnimeEpisode,
        val option: AnimeEpisodeOption,
        val asset: AnimePlaybackAsset,
        val epoch: Long,
        val playerGeneration: Long,
        val libraryEpoch: Long,
        val openedAt: Long,
        val intent: PlaybackIntentTiming? = null,
        val sourceRevision: String? = null,
        val localMedia: AnimeLocalMedia? = null,
        var endedSaved: Boolean = false,
        var endActionHandled: Boolean = false,
        var openingSkipped: Boolean = false,
        var endingSkipped: Boolean = false,
        var nextPreparationNotBefore: Long = 0L,
        var recoveryHandled: Boolean = false,
        var playbackStarted: Boolean = false,
        var firstFrameMeasurementSaved: Boolean = false,
        var stablePlaybackSince: Long? = null,
        var stableRecoveryReset: Boolean = false,
        var sourceReceiptSaved: Boolean = false,
        var measuredDownloadSpeed: Long? = null,
        var measuredVideoSize: Pair<Int, Int>? = null,
        var measuredDurationMs: Long? = null,
        val automaticIntent: Boolean = false,
        val explicitResolutionId: String? = null,
        val preserveEarlierPositionMs: Long? = null,
        var resumeNoticeShown: Boolean = false,
        var viewingPolicy: EnhancementPolicy? = null,
    )
    private data class PendingPlayback(
        val detail: AnimeDetail, val episode: AnimeEpisode, val option: AnimeEpisodeOption,
        val asset: AnimePlaybackAsset, val positionMs: Long, val generation: Long,
        val sourceRevision: String? = null, val playWhenReady: Boolean = true,
        val resolutionId: String? = null,
        val localMedia: AnimeLocalMedia? = null,
        val useSourceStart: Boolean = true,
        val automaticIntent: Boolean = false,
        val bypassMediaCache: Boolean = false,
        val preferredHeight: Int? = null,
        val preserveEarlierPositionMs: Long? = null,
    )
    private data class PlaybackSourceReceipt(
        val sourceId: String, val revision: String, val route: DownloadRouteSnapshot, val observedAt: Long,
        val catalogId: String, val catalogUrl: String, val group: String, val edition: String,
    ) {
        fun matches(option: AnimeEpisodeOption) = option.sourceId == sourceId && option.catalog.id == catalogId &&
            option.catalog.url == catalogUrl && option.episode.group == group && option.episode.edition == edition
    }
    private data class PreparationTarget(
        val subjectId: String, val episodeId: String, val option: AnimeEpisodeOption,
        val revision: String, val route: DownloadRouteSnapshot, val preferredHeight: Int, val timelineGeneration: Long,
        val explicitSourceId: String?,
        val settings: AnimePlaybackSettings,
        val budget: AnimePreparationBudget,
    )
    private data class PreparedPlayback(
        val target: PreparationTarget, val asset: AnimePlaybackAsset, val validUntil: Long,
        var decoderAsset: AnimePlaybackAsset? = null,
        var cacheAsset: AnimePlaybackAsset? = null,
        var cacheResult: AnimeStartupCacheResult? = null,
        var cachePositionMs: Long? = null,
        var mediaSettled: Boolean = false,
    )
    private data class ResolvedPlaybackOption(val option: AnimeEpisodeOption, val asset: AnimePlaybackAsset,
        val revision: String?)
    private sealed interface PlaybackResolutionEvent {
        data object CatalogueChanged : PlaybackResolutionEvent
        data object HedgeDelayElapsed : PlaybackResolutionEvent
        data class Completed(val option: AnimeEpisodeOption, val result: Result<ResolvedPlaybackOption>) : PlaybackResolutionEvent
    }

    private data class PlaybackReceipt(val playback: ActivePlayback, val positionMs: Long, val playWhenReady: Boolean)
    private data class RecoveryResume(val playback: ActivePlayback, val assetId: String, val positionMs: Long,
        val durationMs: Long?, val playWhenReady: Boolean)
    private data class PlaybackIntentTiming(val subjectId: String, val episodeId: String, val startedAt: Long)
    private data class ReviewContext(val detail: AnimeDetail, val remote: AnimeTrackingSnapshot, val restoreEpoch: Long)

    init {
        scope.launch {
            combine(enhancementCenter.settings.state, enhancementCenter.settings.sessions,
                enhancementCenter.resources.usage, enhancementCenter.inventory,
                mutableState.map { it.resolving }.distinctUntilChanged()) { _, _, _, _, _ -> Unit }.collect {
                configureEnhancementPolicy()
            }
        }
        scope.launch {
            enhancementCenter.resources.state.collect {
                if (onlineVideoResultBudget > onlineResultBudget()) cancelOnlineVideo(clear = true)
                playerInstance?.configure(playerOptions(mutableState.value.settings))
                invalidatePreparation()
            }
        }
        scope.launch {
            try {
                try { library.awaitReady() }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { notice(failure.message ?: "追番记录未能读取，原文件已保留；可通过备份恢复。") }
                // Keep observing so an explicit recovery can replace a previously unreadable document.
                var previousBindings: List<AnimeSourceBinding>? = null
                library.state.collect { stored ->
                    if (!enhancementCenter.settings.isConfigured(EnhancementContent.ANIME) &&
                        stored.settings.videoEnhancement != dev.mangaflow.anime.player.AnimeVideoEnhancementMode.OFF) {
                        enhancementCenter.savePolicy(EnhancementContent.ANIME, EnhancementPolicy(
                            tier = if (stored.settings.videoEnhancement == dev.mangaflow.anime.player.AnimeVideoEnhancementMode.FAST)
                                QualityTier.LIGHT else QualityTier.BALANCED,
                            scale = 2, preparation = setOf(PreparationMode.IMMEDIATE, PreparationMode.SMART),
                        )).await()
                    }
                    val settingsChanged = mutableState.value.settings != stored.settings
                    val preparationChanged = !mutableState.value.settings.sameNextPreparation(stored.settings)
                    val sourceHeightChanged = mutableState.value.settings.preferredSourceHeight != stored.settings.preferredSourceHeight
                    val bindingsChanged = previousBindings != stored.bindings
                    previousBindings = stored.bindings
                    if (stored.restoreEpoch != restoreEpoch) {
                        restoreEpoch = stored.restoreEpoch
                        ++detailGeneration
                        cancelSourceAggregation()
                        detailJob?.cancel()
                        cancelDetailScores()
                        boundCatalogJob?.cancel()
                        nativeMetadataJob?.cancel()
                        relatedJob?.cancel()
                        nativeDiscussionOwner = null
                        discussion.open(null)
                        retryNativeReference = null
                        stopPlayback(discardCheckpoint = true)
                        recentPlaybackSources.clear()
                        reviewSnapshots.clear()
                        mutableState.update { it.copy(reviews = emptyList(), detailLoading = false,
                            detailScores = emptyList(), detailScoreChecks = emptyMap(),
                            nativeMetadataSearch = null, relatedSubjects = emptyList(),
                            detailError = if (it.detail != null) "本机记录已恢复，请重新打开番剧以读取恢复后的关联。" else null) }
                    }
                    if (sourceHeightChanged) cancelEpisodeSourceMeasurements(clearProgress = true)
                    mutableState.update { it.copy(
                        libraryReadUnavailable = stored.readUnavailable,
                        library = stored.entries.values.sortedWith(compareByDescending<AnimeLibraryEntry> { entry -> entry.pinned }
                            .thenByDescending { entry -> entry.updatedAt }),
                        episodeProgress = projectEpisodeProgress(stored, it.detail),
                        settings = stored.settings,
                        searchHistory = stored.searchHistory,
                        discoveryColumns = stored.discoveryColumns,
                        resumeEntry = stored.entries.values.filter { entry -> entry.resume?.let { progress ->
                            !progress.completed && progress.positionMs > 0 &&
                                progress.updatedAt > (stored.dismissedResume[entry.subject.id] ?: -1L)
                        } == true }.maxByOrNull { entry -> entry.resume?.updatedAt ?: 0L },
                        canUndo = stored.undo?.let { undo ->
                            stored.entries[undo.subjectId]?.revision == undo.expectedRevision
                        } == true,
                    ) }
                    if (settingsChanged) playerInstance?.configure(playerOptions(stored.settings))
                    if (preparationChanged) {
                        clearPreparation()
                        active?.nextPreparationNotBefore = 0L
                    }
                    if (bindingsChanged) rebuildOptions()
                    if (sourceHeightChanged) {
                        mutableState.value.detail?.let { startSourceAggregation(it) }
                        prepareDetailOpeningAddresses()
                    }
                    val owner = nativeDiscussionOwner
                    val subject = mutableState.value.detail?.subject
                    if (owner != null && subject?.id == owner.first) {
                        mutableState.value.detail?.let { refreshDetailDiscussion(it, detailGeneration) }
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { notice(failure.message ?: "追番记录未能读取。") }
        }
        scope.launch {
            sources.extensions.preparation.collect { preparation ->
                mutableState.update { it.copy(sourcePreparation = preparation) }
            }
        }
        scope.launch {
            sources.extensions.ready.await()
            var previous = sources.extensions.state.value.associateBy { it.id }
            sources.extensions.state.collect { listings ->
                val changed = listings.filter { old -> previous[old.id]?.let { before ->
                    before.revision != old.revision || before.enabled != old.enabled || before.installed != old.installed
                } == true }.map { it.id }.toSet()
                previous = listings.associateBy { it.id }
                mutableState.update { it.copy(sourceExtensions = listings,
                    groupSourceCandidates = it.groupSourceCandidates.filterNot { title -> title.sourceId in changed },
                    associationChoices = it.associationChoices - changed,
                    sourceCatalogs = it.sourceCatalogs.filterNot { catalog -> catalog.title.sourceId in changed },
                    sourceResults = it.sourceResults.map { result -> if (result.providerId in changed)
                        result.copy(items = emptyList(), loading = false, loadingMore = false, hasMore = false, page = 0,
                            error = "来源版本或启用状态已改变，请单独刷新。") else result }) }
                if (changed.isNotEmpty()) {
                    changed.forEach {
                        catalogRequests[it] = ++catalogGeneration
                        sourceSearchRequests[it] = ++sourceSearchSequence
                        sourceSearchJobs.remove(it)?.cancel()
                    }
                    if (previewSourceId in changed) dismissSourcePreview()
                    if (resolvingSourceId in changed || pending?.option?.sourceId in changed) {
                        pausePlaybackResolution("来源已更新或停用，请重试。")
                    }
                    if (preparationTarget?.option?.sourceId in changed) clearPreparation()
                    rebuildOptions()
                    changed.forEach(::refreshSource)
                }
                unifiedSearch.reconcile()
                mutableState.value.detail?.let { startSourceAggregation(it) }
                projectRoutes()
            }
        }
        scope.launch {
            routing.state.collect {
                cancelEpisodeSourceMeasurements(clearProgress = true)
                projectRoutes()
                mutableState.value.detail?.let { startSourceAggregation(it) }
                unifiedSearch.reconcile()
                invalidatePreparation()
                prepareDetailOpeningAddresses()
                if (discoveryVisible && foreground) ensureDiscovery()
                mutableState.value.detail?.subject?.id?.let { owner ->
                    val providers = detailRatingRequests.keys.toSet() + mutableState.value.detailScoreChecks.keys +
                        AnimeDiscoveryProvider.ANILIST
                    providers.forEach { provider -> currentRatingSubject(provider)?.let { reference ->
                        startDetailRating(provider, reference, owner, detailGeneration)
                    } }
                }
            }
        }
        scope.launch {
            try { refreshAccounts() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { notice("追番账号未能读取，番剧浏览仍可使用。请重新连接账号。") }
        }
        scope.launch {
            state.map { it.resolving }.distinctUntilChanged().collect {
                if (downloadsOwner.isInitialized()) downloads.setPlaybackBusy(playbackBusy())
            }
        }
    }

    /** Search never borrows the current discovery feed, filters or cancellation generation. */
    fun search(query: String, indexOnly: Boolean = true,
        provider: AnimeDiscoveryProvider = AnimeDiscoveryProvider.DOUBAN) {
        val keyword = query.trim()
        if (keyword.isNotEmpty()) mutate { library.rememberSearch(keyword) }
        unifiedSearch.search(keyword, indexOnly, provider)
    }

    fun clearSearchHistory() = mutate { library.clearSearchHistory() }
    fun dismissResume(subjectId: String) = mutate { library.dismissResume(subjectId) }
    fun setDiscoveryColumns(columns: Int) = mutate { library.setDiscoveryColumns(columns) }

    internal fun loadMoreCatalog() = unifiedSearch.loadMore()
    internal fun retrySearchIndex() = unifiedSearch.retryIndex()
    internal fun retrySearchProvider(sourceId: String) = unifiedSearch.retrySource(sourceId)

    /** Each destination keeps its query and completed range, including the single home catalogue. */
    fun ensureDiscovery(loadHome: Boolean = true) {
        val today = Instant.fromEpochMilliseconds(wallTimeMillis()).toLocalDateTime(TimeZone.currentSystemDefault()).date
        val season = AnimeSeason.entries[(today.monthNumber - 1) / 3]
        val seasonal = AnimeDiscoveryQuery(year = today.year, season = season)
        val calendar = AnimeDiscoveryQuery(calendar = true, weekday = today.dayOfWeek.isoDayNumber)
        val mainCatalog = AnimeDiscoveryQuery(provider = AnimeDiscoveryProvider.DOUBAN)
        val defaults = mapOf(AnimeDiscoveryTab.HOME to mainCatalog,
            AnimeDiscoveryTab.RANKING to mainCatalog, AnimeDiscoveryTab.SEASON to seasonal,
            AnimeDiscoveryTab.CALENDAR to calendar, AnimeDiscoveryTab.GENRES to mainCatalog)
        if (discoveryDate != today.toString() || defaults.keys.any { animeDiscoveryFeedKey(it) !in mutableState.value.discoveryFeeds }) {
            discoveryDate = today.toString()
            val before = mutableState.value
            val feeds = before.discoveryFeeds.toMutableMap()
            defaults.forEach { (tab, query) ->
                val key = animeDiscoveryFeedKey(tab)
                if (key !in feeds) feeds[key] = AnimeDiscoveryFeed(query)
            }
            mutableState.update { it.copy(discoveryFeeds = feeds) }
        }
        if (!loadHome) return
        val tab = mutableState.value.discoveryTab
        val keys = when (tab) {
            AnimeDiscoveryTab.SOURCES -> emptySet()
            else -> setOf(animeDiscoveryFeedKey(tab))
        }
        discoveryLoads.visible(keys.takeIf { discoveryVisible && foreground }.orEmpty())
        keys.forEach(discoveryLoads::ensure)
    }

    internal fun setDiscoveryVisible(visible: Boolean) {
        discoveryVisible = visible
        if (visible && foreground) ensureDiscovery() else discoveryLoads.visible(emptySet())
    }

    fun selectDiscoveryTab(tab: AnimeDiscoveryTab) {
        ensureDiscovery(loadHome = false)
        mutableState.update { it.copy(discoveryTab = tab) }
        when (tab) {
            AnimeDiscoveryTab.SOURCES -> sourceBrowse.activate()
            else -> ensureDiscovery()
        }
    }

    internal fun rememberedDiscoveryOptions(tab: AnimeDiscoveryTab, provider: AnimeDiscoveryProvider): AnimeDiscoveryQuery? =
        discoveryOptions[tab to provider]

    fun configureDiscovery(tab: AnimeDiscoveryTab, query: AnimeDiscoveryQuery) {
        if (tab == AnimeDiscoveryTab.SOURCES) return
        ensureDiscovery(loadHome = false)
        val key = animeDiscoveryFeedKey(tab)
        val tag = query.tag.trim()
        val normalized = when {
            tab == AnimeDiscoveryTab.HOME -> query.copy(provider = AnimeDiscoveryProvider.DOUBAN,
                tag = tag, combined = false, season = null, calendar = false, weekday = null,
                order = query.order.takeIf { it in listOf(AnimeDiscoveryOrder.POPULARITY,
                    AnimeDiscoveryOrder.NEWEST, AnimeDiscoveryOrder.SCORE) } ?: AnimeDiscoveryOrder.POPULARITY,
                // Douban's default and SERIES both use tv; keep one identity for range/scroll restoration.
                format = query.format?.takeIf { it == AnimeDiscoveryFormat.MOVIE })
            query.provider == AnimeDiscoveryProvider.DOUBAN -> query.copy(tag = tag, combined = false)
            !query.combined -> query.copy(tag = tag)
            query.order == AnimeDiscoveryOrder.TRENDING -> query.copy(provider = AnimeDiscoveryProvider.ANILIST,
                tag = animeDiscoveryGenreLabels.entries.firstOrNull { it.value == tag }?.key ?: tag)
            else -> query.copy(provider = AnimeDiscoveryProvider.BANGUMI, tag = animeDiscoveryGenreLabels[tag] ?: tag)
        }
        val current = mutableState.value.discoveryFeeds[key]
        if (tab != AnimeDiscoveryTab.CALENDAR) {
            current?.query?.let { discoveryOptions[tab to it.provider] = it }
            discoveryOptions[tab to normalized.provider] = normalized
        }
        mutableState.update { it.copy(discoveryTab = tab, discoveryFeeds = it.discoveryFeeds +
            (key to if (current != null && current.query == normalized) current else AnimeDiscoveryFeed(normalized))) }
        ensureDiscovery()
    }

    fun refreshDiscovery() {
        ensureDiscovery(loadHome = false)
        val current = mutableState.value
        when (current.discoveryTab) {
            AnimeDiscoveryTab.SOURCES -> sourceBrowse.refresh()
            else -> discoveryLoads.refresh(animeDiscoveryFeedKey(current.discoveryTab))
        }
    }

    fun retryDiscovery(key: String) = discoveryLoads.retry(key)

    fun loadMoreDiscovery(key: String) = discoveryLoads.more(key)

    /** A video URL needs a saved binding; trusted index lookups may reuse their own subject ID. */
    fun knownDiscoverySubject(reference: AnimeSourceTitle, allowIndexIdentity: Boolean = false): AnimeSubject? {
        if (reference.sourceRevision != null && reference.sourceRevision != discoverySourceRevision(reference.sourceId)) return null
        val stored = library.state.value
        val ids = stored.bindings.filter { it.catalog.sourceId == reference.sourceId &&
            it.catalog.id == reference.id && it.catalog.url == reference.url }
            .map { it.subjectId }.toMutableSet()
        if (allowIndexIdentity) stored.entries.values.filter { entry ->
            "native-source" !in entry.subject.externalIds && reference.externalIds.any { (provider, id) ->
                id.isNotBlank() && entry.subject.externalIds[provider] == id
            }
        }.forEach { ids += it.subject.id }
        return ids.singleOrNull()?.let { stored.entries[it]?.subject }
    }

    fun open(subject: AnimeSubject) = openDetail(subject, resumeEpisodeId = null)

    internal fun returnToVisitedDetail(detail: AnimeDetail, related: List<AnimeSubject>,
        candidates: List<AnimeSourceTitle> = emptyList(), scores: List<AnimeSourceScore> = emptyList()) {
        openDetail(detail.subject, resumeEpisodeId = null, visitedDetail = detail, visitedRelated = related,
            sourceCandidates = candidates, observedScores = scores)
    }

    internal fun openDiscoveryItem(item: AnimeDiscoveryItem) {
        val subject = item.subject ?: return
        openDetail(subject, resumeEpisodeId = null, observedScores = item.sourceScores)
    }

    internal fun retryCurrentDetail() {
        val current = mutableState.value
        val subject = current.detail?.subject ?: return
        openDetail(subject, resumeRequest?.takeIf { it.first == subject.id }?.second,
            sourceCandidates = current.groupSourceCandidates, observedScores = current.detailScores, forceSourceRefresh = true)
    }

    /** Search grouping supplies candidates, never a persisted work/episode association. */
    fun openSearchGroup(group: AnimeSearchGroup) {
        val subject = group.subject ?: return
        openDetail(subject, resumeEpisodeId = null, sourceCandidates = group.sources)
    }

    internal fun currentVideoCandidates(candidates: List<AnimeSourceTitle>): List<AnimeSourceTitle> =
        candidates.filter { title -> sources.provider(title.sourceId) != null &&
            (title.sourceRevision == null || title.sourceRevision == discoverySourceRevision(title.sourceId))
        }.distinctBy { Triple(it.sourceId, it.id, it.url) }

    fun stageSearchCandidates(subjectId: String, candidates: List<AnimeSourceTitle>) {
        if (mutableState.value.detail?.subject?.id != subjectId) return
        val current = currentVideoCandidates(candidates)
        sourceQuery = mutableState.value.detail?.subject?.title.orEmpty()
        mutableState.update { state -> state.copy(sourceResults = current.groupBy { it.sourceId }.map { (id, items) ->
            AnimeSourceResult(id, sources.provider(id)?.name ?: id, items.distinctBy { it.id to it.url })
        }) }
    }

    fun resume(subjectId: String) {
        val entry = library.state.value.entries[subjectId] ?: return
        val progress = entry.resume
        if (progress == null) { openDetail(entry.subject, null); return }
        val target = continuationEpisode(subjectId)
        val completed = progress.completed
        if (target != null && (target.id != progress.episodeId || completed)) {
            resumeEpisode(subjectId, target.id)
        } else if (!completed && progress.localDownloadId != null) {
            openDownload(progress.localDownloadId, entry)
        } else if (!completed && mutableState.value.detail?.takeIf { it.subject.id == subjectId }
                ?.episodes?.any { it.id == progress.episodeId && it.subjectId == subjectId } == true) {
            play(progress.episodeId)
        } else {
            openDetail(entry.subject, progress.episodeId,
                visitedDetail = mutableState.value.detail?.takeIf { it.subject.id == subjectId },
                continueAfterCompleted = completed)
        }
    }

    /** The current catalogue owns ordering; no guessed episode numbers or cross-group jumps. */
    internal fun continuationEpisode(subjectId: String): AnimeEpisode? {
        val entry = library.state.value.entries[subjectId]
        val detail = mutableState.value.detail?.takeIf { it.subject.id == subjectId } ?: entry?.catalogDetail
            ?: return null
        val progress = entry?.resume
        if (progress == null) return detail.episodes.firstOrNull { it.kind == AnimeEpisodeKind.MAIN &&
            it.id !in entry?.completedEpisodeIds.orEmpty() } ?: detail.episodes.firstOrNull()
        val previous = detail.episodes.firstOrNull { it.id == progress.episodeId && it.subjectId == subjectId }
            ?: return null
        if (!progress.completed) return previous
        val group = detail.episodes.filter { it.subjectId == subjectId && it.kind == previous.kind }
        return group.getOrNull(group.indexOfFirst { it.id == previous.id } + 1) ?: previous
    }

    /** A history-row action resumes that exact episode, without the default next-episode advance. */
    internal fun resumeEpisode(subjectId: String, episodeId: String) {
        val stored = library.state.value
        val entry = stored.entries[subjectId] ?: return
        val current = mutableState.value.detail?.takeIf { it.subject.id == subjectId }
        val known = (current ?: entry.catalogDetail)?.episodes.orEmpty()
            .any { it.id == episodeId && it.subjectId == subjectId }
        val progress = projectEpisodeProgress(stored, current)[episodeId]?.takeIf { it.subjectId == subjectId }
        if (!known && progress == null) { notice("这集的观看记录已变化，请重新打开作品。"); return }
        if (progress?.localDownloadId != null) {
            openDownload(progress.localDownloadId, entry.copy(resume = progress))
        } else if (current?.episodes?.any { it.id == episodeId && it.subjectId == subjectId } == true) {
            play(episodeId)
        } else openDetail(entry.subject, episodeId, visitedDetail = current)
    }

    private fun projectEpisodeProgress(stored: AnimeLibraryState, detail: AnimeDetail?): Map<String, AnimeWatchProgress> =
        stored.progress.mapNotNull { (id, row) ->
            val owner = row.subjectId.takeIf { it.isNotBlank() } ?: stored.entries.values.filter { entry ->
                entry.resume?.episodeId == id || id in entry.completedEpisodeIds ||
                    entry.catalogDetail?.episodes?.any { it.id == id && it.subjectId == entry.subject.id } == true ||
                    detail?.takeIf { it.subject.id == entry.subject.id }?.episodes?.any {
                        it.id == id && it.subjectId == entry.subject.id
                    } == true
            }.singleOrNull()?.subject?.id
            if (owner == null || owner !in stored.entries ||
                row.updatedAt <= (stored.clearedHistoryAt[owner] ?: -1L)) null
            else id to if (row.subjectId == owner) row else row.copy(subjectId = owner)
        }.toMap()

    fun sourceSubject(title: AnimeSourceTitle): AnimeSubject = AnimeSubject(
        id = "native:${title.sourceId}:${dev.mangaflow.anime.sources.animeHash(title.id + "\n" + title.url)}",
        title = title.title, coverUrl = title.coverUrl, year = title.year,
        externalIds = title.externalIds + mapOf("native-source" to title.sourceId),
    )

    fun openSourceTitle(title: AnimeSourceTitle) = openDetail(sourceSubject(title), null, title)

    /** A Douban browse card already carries its own catalogue identity; playback is resolved underneath it. */
    internal fun openCatalogueReference(title: AnimeSourceTitle) {
        if (title.sourceId !in setOf(DOUBAN_PROVIDER, "anime-lanerc-douban")) return
        if (title.sourceRevision != null && title.sourceRevision != discoverySourceRevision(title.sourceId)) {
            notice("目录已更新，请刷新后打开。")
            return
        }
        val id = (title.externalIds["douban"] ?: title.id).toLongOrNull()?.takeIf { it > 0 }
            ?: run { notice("这个条目缺少有效的豆瓣作品身份，请重新搜索。"); return }
        val subject = AnimeSubject("douban:$id", title.title, coverUrl = title.coverUrl,
            episodeCount = title.episodeCount, year = title.year, externalIds = mapOf("douban" to id.toString()))
        open(subject)
    }

    internal fun openVideoGroup(subject: AnimeSubject, nativeReference: AnimeSourceTitle?,
        candidates: List<AnimeSourceTitle>) =
        openDetail(subject, resumeEpisodeId = null, nativeReference = nativeReference, sourceCandidates = candidates)

    private fun nativeEpisodeId(subject: AnimeSubject, episode: AnimeSourceEpisode): String =
        "${subject.id}:episode:${dev.mangaflow.anime.sources.animeHash(episode.group + "\n" + episode.id)}"

    private fun nativeDetailSubject(subject: AnimeSubject, catalog: AnimeSourceCatalog): AnimeSubject =
        subject.copy(title = catalog.title.title, year = catalog.title.year ?: subject.year,
            coverUrl = catalog.title.coverUrl.ifBlank { subject.coverUrl },
            description = catalog.description.ifBlank { subject.description },
            episodeCount = catalog.title.episodeCount ?: subject.episodeCount,
            externalIds = subject.externalIds + catalog.title.externalIds +
                mapOf("native-source" to catalog.title.sourceId))

    private fun nativeDetail(subject: AnimeSubject, catalog: AnimeSourceCatalog, group: String): AnimeDetail =
        AnimeDetail(nativeDetailSubject(subject, catalog), catalog.episodes.filter { it.group == group }.map { episode ->
            AnimeEpisode(nativeEpisodeId(subject, episode), subject.id, episode.title,
                episode.number ?: 0.0, if (episode.number == null) AnimeEpisodeKind.OTHER else episode.kind)
        })

    private fun openDetail(subject: AnimeSubject, resumeEpisodeId: String?, nativeReference: AnimeSourceTitle? = null,
        visitedDetail: AnimeDetail? = null, visitedRelated: List<AnimeSubject> = emptyList(),
        sourceCandidates: List<AnimeSourceTitle> = emptyList(), observedScores: List<AnimeSourceScore> = emptyList(),
        continueAfterCompleted: Boolean = false, forceSourceRefresh: Boolean = false) {
        // A related-title back action can reuse the exact catalogue the user saw. Media
        // bindings and source options still resolve against the current registry and library.
        val openedAt = monotonicTimeMillis()
        val initialDetail = visitedDetail ?: library.state.value.entries[subject.id]?.catalogDetail
            ?: AnimeDetail(subject, emptyList())
        val nativeOpeningReference = nativeReference ?: retryNativeReference?.takeIf { sourceSubject(it).id == subject.id }
        retryNativeReference = nativeOpeningReference
        closePlayback()
        val generation = ++detailGeneration
        cancelSourceAggregation()
        detailOpenedAt = openedAt
        val openingRestoreEpoch = library.state.value.restoreEpoch
        detailJob?.cancel()
        cancelDetailScores()
        boundCatalogJob?.cancel()
        relatedJob?.cancel()
        nativeMetadataJob?.cancel()
        nativeDiscussionOwner = null
        sourceJob?.cancel()
        sourceSearchJobs.values.forEach { it.cancel() }
        sourceSearchJobs.clear()
        previewJob?.cancel()
        previewSourceId = null
        previewSourceRevision = null
        ++searchGeneration
        ++previewGeneration
        resumeRequest = resumeEpisodeId?.let { subject.id to it }
        resumeAfterCompleted = continueAfterCompleted
        val candidates = currentVideoCandidates(sourceCandidates)
        val ratingSnapshot = ratings.currentSnapshot(subject)
        val seedScores = observedScores + mutableState.value.discoveryFeeds.values.asSequence()
            .flatMap { it.items.asSequence() }.filter { it.subject?.id == subject.id }
            .flatMap { it.sourceScores.asSequence() }.toList()
        val scores = (ratingSnapshot?.scores.orEmpty() + seedScores.filter { score ->
            val check = ratingSnapshot?.checks?.get(score.provider)
            check == null
        }).distinctBy { it.provider }
        mutableState.update { it.copy(
            detail = initialDetail, detailLoading = true, detailError = null,
            episodeProgress = projectEpisodeProgress(library.state.value, initialDetail),
            detailScores = scores, detailScoreChecks = ratingSnapshot?.checks.orEmpty().filterValues { !it.loading },
            groupSourceCandidates = candidates, associationChoices = emptyMap(),
            nativeMetadataSearch = null,
            detailFromCache = visitedDetail != null, relatedSubjects = visitedRelated,
            sourceCatalogs = emptyList(), sourceResults = emptyList(), episodeOptions = emptyMap(),
            sourceBindings = library.state.value.bindings.filter { binding -> binding.subjectId == subject.id },
            sourceCatalogErrors = emptyMap(),
            previewCatalog = null, previewLoading = false, sourceError = null, selectedEpisodeId = null,
            sourceGroups = emptyMap(), preferredSourceId = null,
            sourceAggregation = emptyMap(), sourceAggregationRunning = false,
            sourceAggregationCompleted = 0, sourceAggregationTotal = 0,
        ) }
        if ("native-source" in subject.externalIds || subject.canonicalDoubanId() != null) {
            refreshNativeDiscussion(subject, supplementarySubject(initialDetail), generation)
            if (subject.canonicalDoubanId() != null) mutableState.update { it.copy(detailScoreChecks =
                it.detailScoreChecks + (AnimeDiscoveryProvider.DOUBAN to AnimeScoreCheck(loading = true))) }
        } else {
            discussion.open(subject)
            mutableState.update { it.copy(detailScoreChecks = it.detailScoreChecks +
                (AnimeDiscoveryProvider.BANGUMI to AnimeScoreCheck(loading = true))) }
            loadDetailScore(subject, generation)
        }
        if (candidates.isNotEmpty()) {
            stageSearchCandidates(subject.id, candidates)
            // Group membership supplies candidates for detail lookup. The source's
            // catalogue still has to pass the existing work and episode checks.
            candidates.groupBy { it.sourceId }.forEach { (id, titles) ->
                associationCandidates[id] = discoverySourceRevision(id) to titles
            }
        }
        if (subject.externalIds["native-source"] != null) {
            detailJob = scope.launch {
                try {
                    library.awaitLoaded()
                    sources.extensions.ready.await()
                    val sourceId = subject.externalIds.getValue("native-source")
                    val saved = library.state.value.bindings.firstOrNull { it.subjectId == subject.id && it.catalog.sourceId == sourceId }
                    val reference = nativeOpeningReference ?: saved?.catalog ?: error("原始来源记录未找到，请重新搜索打开。")
                    check(sourceSubject(reference).id == subject.id) { "来源条目身份已变化。" }
                    val revision = discoverySourceRevision(sourceId)
                    check(nativeOpeningReference == null || reference.sourceRevision == null || reference.sourceRevision == revision) { "来源已更新，请重新搜索。" }
                    val provider = sources.provider(sourceId) ?: error("这个来源已停用或未安装。")
                    val catalog = withTimeoutOrNull(15_000L) { sources.catalog(provider, reference, forceRefresh = forceSourceRefresh) }
                        ?: error("来源目录读取超时，请重试。")
                    check(catalog.title.sourceId == sourceId && catalog.title.id == reference.id && catalog.title.url == reference.url) {
                        "原始目录身份已变化，请重新搜索。"
                    }
                    check(discoverySourceRevision(sourceId) == revision && sources.provider(sourceId) != null) { "来源已经变化。" }
                    if (!currentDetail(generation, subject.id)) return@launch
                    check(library.state.value.restoreEpoch == openingRestoreEpoch) { "资料已恢复，请重新打开目录。" }
                    val groups = catalog.episodes.map { it.group }.distinct()
                    val resumeGroup = resumeEpisodeId?.let { id -> catalog.episodes.firstOrNull { nativeEpisodeId(subject, it) == id }?.group }
                    // A budgeted/failed read is not evidence that the user's saved line disappeared.
                    // Keep the binding and pending resume untouched until that exact line is observed.
                    val changedSavedGroup = saved?.selectedGroup?.let { it !in groups } == true
                    val preserveSaved = saved != null && (catalog.notice != null || changedSavedGroup)
                    val unresolvedResume = catalog.notice != null && resumeEpisodeId != null && resumeGroup == null
                    val group = when {
                        preserveSaved -> saved?.selectedGroup
                        unresolvedResume -> saved?.selectedGroup
                        else -> resumeGroup ?: saved?.selectedGroup?.takeIf { it in groups } ?: groups.firstOrNull()
                    }
                    check(group != null || catalog.notice != null) { "这个来源暂无剧集。" }
                    val catalogSubject = nativeDetailSubject(subject, catalog)
                    val binding = if (preserveSaved) requireNotNull(saved)
                        else saved?.copy(catalog = reference.copy(sourceRevision = revision), selectedGroup = group)
                            ?: AnimeSourceBinding(subject.id, reference.copy(sourceRevision = revision), preferred = true, selectedGroup = group, automatic = true)
                    if (!preserveSaved) {
                        // Intrinsic native ownership only, never a guessed Bangumi/cross-source association.
                        library.editPlaybackAssociation(catalogSubject, editBindings = {
                            check(currentDetail(generation, subject.id) && library.state.value.restoreEpoch == openingRestoreEpoch &&
                                discoverySourceRevision(sourceId) == revision && sources.provider(sourceId) != null) {
                                "来源或页面已经变化，请重新打开。"
                            }
                            library.state.value.bindings.filter { it.subjectId == subject.id &&
                                it.catalog.sourceId != sourceId } + binding
                        })
                    }
                    if (!currentDetail(generation, subject.id) || library.state.value.restoreEpoch != openingRestoreEpoch ||
                        discoverySourceRevision(sourceId) != revision || sources.provider(sourceId) == null) return@launch
                    retryNativeReference = null
                    val shownCatalog = when {
                        changedSavedGroup -> catalog.copy(notice = listOfNotNull(catalog.notice,
                            "来源线路结构已更新，原线路与续看记录已保留；请打开来源面板选择新线路后继续。")
                            .joinToString(" "))
                        catalog.notice != null && (group !in groups || unresolvedResume) ->
                            catalog.copy(notice = catalog.notice + " 已保留原线路与续看位置，待对应剧集加载后继续。")
                        else -> catalog
                    }
                    mutableState.update { it.copy(detail = group?.let { selected -> nativeDetail(subject, shownCatalog, selected) }
                        ?: AnimeDetail(catalogSubject, emptyList()), detailLoading = false,
                        sourceBindings = listOf(binding), sourceCatalogs = listOf(shownCatalog)) }
                    rebuildOptions()
                    if (catalog.notice == null && !changedSavedGroup && resumeRequest?.takeIf { it.first == subject.id }
                            ?.second?.let { id -> mutableState.value.detail?.episodes.orEmpty().none { it.id == id } } == true) {
                        notice("这个来源的目录里暂时找不到上次观看的剧集，原进度已保留。")
                    }
                    mutableState.value.detail?.let { startSourceAggregation(it) }
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { if (currentDetail(generation, subject.id)) mutableState.update {
                    it.copy(detailLoading = false, detailError = failure.message ?: "来源目录未能加载。")
                } }
            }
            return
        }
        // Source catalogues have independent ownership and do not wait for the metadata service.
        boundCatalogJob = scope.launch {
            try { library.awaitLoaded(); sources.extensions.ready.await()
                if (currentDetail(generation, subject.id)) startSourceAggregation(initialDetail, force = forceSourceRefresh)
            }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { if (currentDetail(generation, subject.id)) notice(failure.message ?: "来源目录未能读取。") }
        }
        if (subject.canonicalBangumiId() != null) relatedJob = scope.launch {
            try {
                val related = metadata.related(subject)
                if (currentDetail(generation, subject.id)) mutableState.update { it.copy(relatedSubjects = related) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Related subjects are optional; failure cannot invalidate the current detail. */ }
        }
        detailJob = scope.launch {
            try {
                library.awaitLoaded()
                (if (visitedDetail == null) library.state.value.entries[subject.id]?.catalogDetail
                    ?: metadata.cachedDetail(subject) else null)?.let { cached ->
                    if (!currentDetail(generation, subject.id)) return@launch
                    mutableState.update { it.copy(detail = cached, detailFromCache = true) }
                    refreshDetailDiscussion(cached, generation)
                    rebuildOptions()
                    startSourceAggregation(cached)
                }
                val saved = library.state.value.entries[subject.id]
                val metadataProviders = if (subject.canonicalDoubanId() != null)
                    listOf(DOUBAN_PROVIDER, dev.mangaflow.anime.sources.BANGUMI_PROVIDER)
                    else listOf(dev.mangaflow.anime.sources.BANGUMI_PROVIDER)
                fun routes() = metadataProviders.associateWith { id ->
                    runCatching { DownloadRoutingStore.activeSourceSnapshot(id, DownloadCategory.ANIME) }.getOrNull()
                }
                val requestedRoutes = routes()
                fun currentMetadata() = currentDetail(generation, subject.id) &&
                    library.state.value.restoreEpoch == openingRestoreEpoch && requestedRoutes == routes()
                val result = metadata.detail(subject, previous = mutableState.value.detail,
                    reference = saved?.nativeMetadata, allowAutoMatch = saved?.metadataAutoMatchDisabled != true,
                    onSubjectAvailable = { primary, problem -> withContext(Dispatchers.Main.immediate) {
                        if (!currentMetadata()) return@withContext
                        val previous = mutableState.value.detail ?: return@withContext
                        val reference = supplementarySubject(previous)?.takeIf { selected ->
                            saved?.nativeMetadata?.id == selected.id || animeMetadataSubjectsMatch(primary, selected)
                        }
                        val main = if (reference == null) withoutStaleMetadata(primary, previous.metadataSubject)
                            else enrichAnimeCatalogueSubject(primary, reference)
                        val ready = previous.copy(subject = main, metadataSubject = reference,
                            episodes = if (reference != null) previous.episodes else previous.episodes.map {
                                it.copy(metadataSubjectId = null, metadataEpisodeId = null)
                            })
                        val score = main.rating?.takeIf { it.isFinite() && it > 0.0 && it <= 10.0 }
                            ?.let { AnimeSourceScore(AnimeDiscoveryProvider.DOUBAN, it, 10, main.ratingCount) }
                        mutableState.update { state -> state.copy(detail = ready, detailLoading = false,
                            detailError = problem?.let { "作品资料：$it" },
                            detailScores = if (AnimeDiscoveryProvider.DOUBAN in detailRatingRequests) state.detailScores else
                                state.detailScores.filterNot { it.provider == AnimeDiscoveryProvider.DOUBAN } + listOfNotNull(score),
                            detailScoreChecks = if (AnimeDiscoveryProvider.DOUBAN in detailRatingRequests) state.detailScoreChecks else
                                state.detailScoreChecks + (AnimeDiscoveryProvider.DOUBAN to AnimeScoreCheck(error = problem,
                                    message = "豆瓣 暂无评分".takeIf { problem == null && score == null }))) }
                        refreshDetailDiscussion(ready, generation)
                        rebuildOptions()
                        startSourceAggregation(ready)
                        scheduleCatalogBootstrap()
                    } }) { episodes ->
                    withContext(Dispatchers.Main.immediate) {
                        if (!currentMetadata()) return@withContext
                        val current = mutableState.value.detail ?: return@withContext
                        // Douban merges supplementary episodes once, against the latest local source catalogue.
                        if (subject.canonicalDoubanId() != null) return@withContext
                        if (current.episodes == episodes) return@withContext
                        val ready = current.copy(episodes = episodes)
                        mutableState.update { it.copy(detail = ready) }
                        rebuildOptions()
                        startSourceAggregation(ready)
                    }
                }
                if (!currentDetail(generation, subject.id)) return@launch
                check(currentMetadata()) { "资料线路或本地记录已变化，请重试；现有选集和进度已保留。" }
                val previous = mutableState.value.detail
                val returned = result.subject ?: previous?.subject ?: subject
                val reference = result.metadataSubject ?: previous?.metadataSubject?.takeIf { old ->
                    saved?.metadataAutoMatchDisabled != true &&
                        (saved?.nativeMetadata?.id == old.id || animeMetadataSubjectsMatch(returned, old))
                }
                val primary = if (returned.canonicalDoubanId() != null && reference == null)
                    withoutStaleMetadata(returned, previous?.metadataSubject) else returned
                val projected = if (primary.canonicalDoubanId() != null && result.metadataSubject != null &&
                    result.metadataEpisodes != null) projectAnimeMetadataEpisodes(primary,
                    AnimeDetail(result.metadataSubject, result.metadataEpisodes), previous) else null
                val episodes = (projected ?: if (primary.canonicalDoubanId() != null && previous?.episodes?.isNotEmpty() == true)
                    previous.episodes else result.episodes?.takeIf { it.isNotEmpty() } ?: previous?.episodes.orEmpty()).let { rows ->
                    if (primary.canonicalDoubanId() == null || reference != null) rows
                    else rows.map { it.copy(metadataEpisodeId = null, metadataSubjectId = null) }
                }
                val detail = AnimeDetail(primary, episodes, reference)
                val failure = listOfNotNull(result.subjectError?.let { "作品资料：$it" },
                    result.episodeError?.let { if (primary.canonicalDoubanId() != null) "补充资料：$it" else "集数目录：$it" })
                    .joinToString("\n").ifBlank { null }
                mutableState.update { current ->
                    val primaryProvider = if (primary.canonicalDoubanId() != null)
                        AnimeDiscoveryProvider.DOUBAN else AnimeDiscoveryProvider.BANGUMI
                    val providerName = if (primaryProvider == AnimeDiscoveryProvider.DOUBAN) "豆瓣" else "Bangumi"
                    val independentScore = primaryProvider in detailRatingRequests
                    val freshScore = result.subject?.let { canonical ->
                        canonical.rating?.takeIf { it.isFinite() && it > 0.0 && it <= 10.0 }
                            ?.let { AnimeSourceScore(primaryProvider, it, 10, canonical.ratingCount) }
                    }
                    current.copy(detail = detail, detailLoading = false, detailError = failure,
                        detailFromCache = current.detailFromCache && (result.subject == null || result.episodes == null),
                        detailScores = if (independentScore || result.subject == null) current.detailScores else
                            current.detailScores.filterNot { it.provider == primaryProvider } + listOfNotNull(freshScore),
                        detailScoreChecks = if (independentScore) current.detailScoreChecks else current.detailScoreChecks +
                            (primaryProvider to AnimeScoreCheck(
                                error = result.subjectError?.let { "$providerName：$it" },
                                message = "$providerName 暂无评分".takeIf { result.subject != null && freshScore == null }))
                    )
                }
                refreshDetailDiscussion(detail, generation)
                rebuildOptions()
                retainCatalogue(detail, generation, openingRestoreEpoch)
                if (result.episodes != null && resumeRequest?.takeIf { it.first == subject.id }
                        ?.second?.let { id -> detail.episodes.none { it.id == id } } == true) {
                    notice("当前剧集目录里暂时找不到上次观看的剧集，原进度已保留。")
                }
                startSourceAggregation(detail)
                scheduleCatalogBootstrap()
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (currentDetail(generation, subject.id)) {
                    if (failure is AnimeMetadataIdentityException) {
                        closePlayback()
                        cancelSourceAggregation()
                        cancelDetailScores()
                        discussion.open(null)
                        mutableState.update { it.copy(detail = AnimeDetail(subject, emptyList()), episodeOptions = emptyMap(),
                            detailScores = emptyList(), detailScoreChecks = emptyMap()) }
                    }
                    val primaryProvider = if (subject.canonicalDoubanId() != null)
                        AnimeDiscoveryProvider.DOUBAN else AnimeDiscoveryProvider.BANGUMI
                    mutableState.update {
                        it.copy(detailLoading = false, detailError = failure.message ?: "番剧详情未能加载，请重试。",
                            detailScoreChecks = if (primaryProvider in detailRatingRequests) it.detailScoreChecks else
                                it.detailScoreChecks + (primaryProvider to AnimeScoreCheck(
                                    error = failure.message ?: "评分资料未能读取，请重试。")))
                    }
                    if (failure !is AnimeMetadataIdentityException) scheduleCatalogBootstrap()
                }
            }
        }
    }

    private fun withoutStaleMetadata(primary: AnimeSubject, old: AnimeSubject?): AnimeSubject {
        if (primary.canonicalDoubanId() == null || old == null) return primary
        val names = animeCatalogueNames(old).toSet()
        return primary.copy(externalIds = primary.externalIds.filter { (key, value) ->
            key == "douban" || old.externalIds[key] != value
        }, aliases = primary.aliases.filterNot { it in names })
    }

    private fun supplementarySubject(detail: AnimeDetail): AnimeSubject? {
        val saved = library.state.value.entries[detail.subject.id]
        return saved?.nativeMetadata ?: detail.metadataSubject.takeUnless { saved?.metadataAutoMatchDisabled == true }
    }

    private fun refreshDetailDiscussion(detail: AnimeDetail, generation: Long) {
        if (detail.subject.canonicalDoubanId() != null || "native-source" in detail.subject.externalIds)
            refreshNativeDiscussion(detail.subject, supplementarySubject(detail), generation)
        else {
            discussion.open(detail.subject)
            loadDetailScore(detail.subject, generation)
        }
    }

    private fun refreshNativeDiscussion(subject: AnimeSubject, reference: AnimeSubject?, generation: Long) {
        val owner = subject.id to reference
        if (nativeDiscussionOwner == owner) return
        val previous = nativeDiscussionOwner
        nativeDiscussionOwner = owner
        discussion.open(subject, reference)
        cancelDetailScores(keep = AnimeDiscoveryProvider.DOUBAN.takeIf { subject.canonicalDoubanId() != null })
        mutableState.update { it.copy(
            detailScores = it.detailScores.filter { score -> score.provider == AnimeDiscoveryProvider.DOUBAN },
            detailScoreChecks = it.detailScoreChecks.filterKeys { provider -> provider == AnimeDiscoveryProvider.DOUBAN }) }
        if (reference != null) {
            loadDetailScore(reference, generation, subject.id)
            startDetailRating(AnimeDiscoveryProvider.BANGUMI, reference, subject.id, generation)
        }
        relatedJob?.cancel()
        if (reference == null || previous?.first == subject.id) {
            mutableState.update { it.copy(relatedSubjects = emptyList()) }
        }
        if (reference == null) return
        relatedJob = scope.launch {
            val related = try { metadata.related(reference) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { emptyList() }
            if (currentDetail(generation, subject.id) && nativeDiscussionOwner == owner) {
                mutableState.update { it.copy(relatedSubjects = related) }
            }
        }
    }

    internal fun searchNativeMetadata(subjectId: String, input: String) {
        val snapshot = mutableState.value
        val subject = snapshot.detail?.subject?.takeIf { it.id == subjectId &&
            ("native-source" in it.externalIds || it.canonicalDoubanId() != null) } ?: return
        if (snapshot.libraryReadUnavailable || snapshot.nativeMetadataSearch?.saving == true) return
        val query = input.trim().take(160)
        if (query.isEmpty()) return
        val generation = detailGeneration
        val owner = AnimeNativeMetadataSearch(subject.id, query, loading = true)
        nativeMetadataJob?.cancel()
        mutableState.update { it.copy(nativeMetadataSearch = owner) }
        nativeMetadataJob = scope.launch {
            val result = try {
                val candidates = metadata.search(query)
                val referenceId = subject.externalIds["bangumi"]?.toIntOrNull()?.takeIf { it > 0 && query == subject.title }
                val reference = if (referenceId == null || candidates.any { it.canonicalBangumiId() == referenceId }) null
                    else try { metadata.subjectByBangumiId(referenceId) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { null }
                owner.copy(loading = false, candidates = (listOfNotNull(reference) + candidates)
                    .filter { it.canonicalBangumiId() != null }.distinctBy { it.id }
                    .sortedByDescending { it.canonicalBangumiId() == referenceId && referenceId != null })
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { owner.copy(loading = false, error = failure.message ?: "Bangumi 资料查找失败。") }
            if (currentDetail(generation, subject.id) && mutableState.value.nativeMetadataSearch === owner) {
                mutableState.update { it.copy(nativeMetadataSearch = result) }
            }
        }
    }

    internal fun dismissNativeMetadataSearch(subjectId: String) {
        val search = mutableState.value.nativeMetadataSearch
        if (search?.subjectId != subjectId || search.saving) return
        nativeMetadataJob?.cancel()
        mutableState.update { it.copy(nativeMetadataSearch = null) }
    }

    internal fun setNativeMetadata(subjectId: String, canonicalId: String?) {
        val snapshot = mutableState.value
        if (snapshot.syncing) { notice("请等本次追番核对完成后再修改资料关联。"); return }
        val subject = snapshot.detail?.subject?.takeIf { it.id == subjectId &&
            ("native-source" in it.externalIds || it.canonicalDoubanId() != null) } ?: return
        val search = snapshot.nativeMetadataSearch?.takeIf { it.subjectId == subjectId }
        if (snapshot.libraryReadUnavailable || search?.saving == true) return
        val reference = canonicalId?.let { id -> search?.candidates?.singleOrNull { it.id == id } ?: return }
        val previousDetail = snapshot.detail
        val oldReference = previousDetail?.metadataSubject
        val oldIds = oldReference?.externalIds.orEmpty()
        val oldNames = oldReference?.let(::animeCatalogueNames).orEmpty().toSet()
        val cleanSubject = if (subject.canonicalDoubanId() == null) subject else subject.copy(
            externalIds = subject.externalIds.filter { (key, value) -> key == "douban" || oldIds[key] != value },
            aliases = subject.aliases.filterNot { it in oldNames },
            originalTitle = subject.originalTitle.takeUnless { it in oldNames }.orEmpty())
        val retained = previousDetail?.takeIf { subject.canonicalDoubanId() != null }?.copy(
            subject = cleanSubject, metadataSubject = null,
            episodes = previousDetail.episodes.map { it.copy(metadataSubjectId = null, metadataEpisodeId = null) })
        val generation = detailGeneration
        val stored = library.state.value
        val owner = (search ?: AnimeNativeMetadataSearch(subject.id, subject.title)).copy(saving = true, error = null)
        nativeMetadataJob?.cancel()
        mutableState.update { it.copy(nativeMetadataSearch = owner) }
        nativeMetadataJob = scope.launch {
            fun current() = currentDetail(generation, subject.id) && library.state.value.restoreEpoch == stored.restoreEpoch
            try {
                library.edit(cleanSubject, expectedRevision = stored.entries[subject.id]?.revision ?: 0L) { entry ->
                    check(current()) { "作品或本地记录已变化，请重新打开资料关联。" }
                    entry.copy(nativeMetadata = reference,
                        catalogDetail = retained ?: entry.catalogDetail,
                        metadataAutoMatchDisabled = subject.canonicalDoubanId() != null && reference == null)
                }
                if (current()) {
                    mutableState.update { it.copy(nativeMetadataSearch = null) }
                    if (subject.canonicalDoubanId() != null) {
                        openDetail(cleanSubject, resumeRequest?.takeIf { it.first == subject.id }?.second, visitedDetail = retained,
                            sourceCandidates = snapshot.groupSourceCandidates,
                            observedScores = snapshot.detailScores.filter { it.provider == AnimeDiscoveryProvider.DOUBAN })
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (current()) mutableState.update { it.copy(nativeMetadataSearch = owner.copy(saving = false,
                    error = failure.message ?: "资料关联未能保存，请重试。")) }
            }
        }
    }

    private fun currentRatingSubject(provider: AnimeDiscoveryProvider = AnimeDiscoveryProvider.BANGUMI): AnimeSubject? {
        val detail = mutableState.value.detail ?: return null
        val subject = detail.subject
        if (provider == AnimeDiscoveryProvider.DOUBAN) return subject.takeIf { it.canonicalDoubanId() != null }
        return (if ("native-source" in subject.externalIds || subject.canonicalDoubanId() != null)
            supplementarySubject(detail) else subject)
            ?.takeIf { it.canonicalBangumiId() != null }
    }

    private fun cancelDetailScores(keep: AnimeDiscoveryProvider? = null) {
        detailRatingJobs.keys.filter { it != keep }.forEach { detailRatingJobs.remove(it)?.cancel() }
        detailRatingRequests.keys.filter { it != keep }.forEach(detailRatingRequests::remove)
    }

    private fun loadDetailScore(subject: AnimeSubject, generation: Long, ownerId: String = subject.id) {
        startDetailRating(AnimeDiscoveryProvider.ANILIST, subject, ownerId, generation)
    }

    /** Explicit refresh stays in the current detail; each rating site fails independently. */
    internal fun refreshDetailScores(provider: AnimeDiscoveryProvider? = null) {
        val ownerId = mutableState.value.detail?.subject?.id ?: return
        (provider?.let { listOf(it) } ?: AnimeDiscoveryProvider.entries).forEach {
            currentRatingSubject(it)?.let { subject ->
                startDetailRating(it, subject, ownerId, detailGeneration, forceRefresh = true)
            }
        }
    }

    private fun startDetailRating(provider: AnimeDiscoveryProvider, subject: AnimeSubject, ownerId: String,
        generation: Long, forceRefresh: Boolean = false) {
        if (!currentDetail(generation, ownerId) ||
            (if (provider == AnimeDiscoveryProvider.DOUBAN) subject.canonicalDoubanId() == null
                else subject.canonicalBangumiId() == null)) return
        val routingId = when (provider) {
            AnimeDiscoveryProvider.BANGUMI -> dev.mangaflow.anime.sources.BANGUMI_PROVIDER
            AnimeDiscoveryProvider.ANILIST -> dev.mangaflow.anime.sources.ANILIST_PROVIDER
            AnimeDiscoveryProvider.DOUBAN -> DOUBAN_PROVIDER
        }
        val name = when (provider) {
            AnimeDiscoveryProvider.BANGUMI -> "Bangumi"
            AnimeDiscoveryProvider.ANILIST -> "AniList"
            AnimeDiscoveryProvider.DOUBAN -> "豆瓣"
        }
        val route = try { DownloadRoutingStore.activeSourceSnapshot(routingId, DownloadCategory.ANIME) }
        catch (failure: Exception) {
            detailRatingJobs.remove(provider)?.cancel()
            detailRatingRequests.remove(provider)
            mutableState.update { it.copy(detailScoreChecks = it.detailScoreChecks +
                (provider to AnimeScoreCheck(error = "$name：${failure.message ?: "评分网络线路不可用。"}"))) }
            return
        }
        val request = DetailRatingRequest(subject, ownerId, generation, route)
        if (!forceRefresh && detailRatingRequests[provider] == request) return
        detailRatingJobs.remove(provider)?.cancel()
        detailRatingRequests[provider] = request
        fun current() = detailRatingRequests[provider] === request && currentDetail(generation, ownerId) &&
            currentRatingSubject(provider)?.let { it.id == subject.id &&
                (provider == AnimeDiscoveryProvider.DOUBAN || it.externalIds == subject.externalIds) } == true &&
            runCatching { DownloadRoutingStore.activeSourceSnapshot(routingId, DownloadCategory.ANIME) }.getOrNull() == route
        mutableState.update { it.copy(detailScoreChecks = it.detailScoreChecks +
            (provider to AnimeScoreCheck(loading = true))) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                var message: String? = null
                val score = when (provider) {
                    AnimeDiscoveryProvider.DOUBAN -> {
                        val fresh = douban.detail(subject, forceRefresh)
                        fresh.rating?.takeIf { it.isFinite() && it > 0.0 && it <= 10.0 }
                            ?.let { AnimeSourceScore(provider, it, 10, fresh.ratingCount) }
                            .also { if (it == null) message = "豆瓣 暂无评分" }
                    }
                    AnimeDiscoveryProvider.BANGUMI -> {
                        val fresh = withTimeoutOrNull(8_000L) {
                            metadata.subjectByBangumiId(requireNotNull(subject.canonicalBangumiId()), route)
                                ?: error("返回的作品资料与当前番剧不一致。")
                        } ?: error("等待读取评分超时。")
                        fresh.rating?.takeIf { it.isFinite() && it > 0.0 && it <= 10.0 }
                            ?.let { AnimeSourceScore(provider, it, 10, fresh.ratingCount) }
                            .also { if (it == null) message = "Bangumi 暂无评分" }
                    }
                    AnimeDiscoveryProvider.ANILIST -> {
                        val match = discovery.lookupAniListMatch(subject, forceRefresh, reportFailure = true)
                        match?.rating?.let { AnimeSourceScore(provider, it, 100, match.ratingCount) }
                            .also { if (it == null) message = if (match == null)
                                "AniList 未匹配到同一部番剧" else "AniList 暂无评分" }
                    }
                }
                if (current()) {
                    mutableState.value.detail?.let { detail ->
                        val accepted = ratings.accept(detail.subject, listOfNotNull(score),
                            mapOf(provider to AnimeScoreCheck(message = message)), supplementarySubject(detail))
                        if (accepted) mutableState.update { it.copy(
                            detailScores = it.detailScores.filterNot { value -> value.provider == provider } + listOfNotNull(score),
                            detailScoreChecks = it.detailScoreChecks + (provider to AnimeScoreCheck(message = message))) }
                        else ratings.currentSnapshot(detail.subject)?.let { confirmed ->
                            mutableState.update { it.copy(detailScores = confirmed.scores,
                                detailScoreChecks = confirmed.checks +
                                    (provider to (confirmed.checks[provider] ?: AnimeScoreCheck()).copy(loading = true))) }
                            val checked = ratings.recheck(detail.subject, provider) ?: confirmed
                            if (current()) mutableState.update {
                                it.copy(detailScores = checked.scores, detailScoreChecks = checked.checks)
                            }
                        }
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (current()) mutableState.update { it.copy(detailScoreChecks = it.detailScoreChecks +
                    (provider to AnimeScoreCheck(error = "$name：${failure.message ?: "评分读取失败，请重试。"}"))) }
            } finally {
                if (detailRatingRequests[provider] === request) detailRatingJobs.remove(provider)
            }
        }
        detailRatingJobs[provider] = job
        job.start()
    }

    fun closeDetail() {
        retryNativeReference = null
        closePlayback()
        nativeDiscussionOwner = null
        nativeMetadataJob?.cancel()
        discussion.open(null)
        resumeRequest = null
        ++detailGeneration
        cancelSourceAggregation()
        ++searchGeneration
        ++previewGeneration
        detailJob?.cancel()
        cancelDetailScores()
        boundCatalogJob?.cancel()
        relatedJob?.cancel()
        sourceJob?.cancel()
        sourceSearchJobs.values.forEach { it.cancel() }
        sourceSearchJobs.clear()
        previewJob?.cancel()
        mutableState.update { it.copy(detail = null, detailLoading = false, detailError = null,
            nativeMetadataSearch = null,
            detailScores = emptyList(), detailScoreChecks = emptyMap(),
            groupSourceCandidates = emptyList(), associationChoices = emptyMap(),
            sourceResults = emptyList(), sourceCatalogs = emptyList(), episodeOptions = emptyMap(),
            previewCatalog = null, previewLoading = false, sourceError = null, preferredSourceId = null,
            sourceGroups = emptyMap(), sourceBindings = emptyList(), sourceCatalogErrors = emptyMap(),
            relatedSubjects = emptyList(), detailFromCache = false) }
    }

    private suspend fun loadBoundCatalogs(detail: AnimeDetail, generation: Long) = coroutineScope {
        library.state.value.bindings.filter { it.subjectId == detail.subject.id }.forEach { binding ->
            val request = ++catalogGeneration
            catalogRequests[binding.catalog.sourceId] = request
            launch { loadBoundCatalog(binding, generation, request) }
        }
    }

    private suspend fun loadBoundCatalog(binding: AnimeSourceBinding, generation: Long, request: Long) {
        val sourceId = binding.catalog.sourceId
        if (catalogRequests[sourceId] != request || !currentDetail(generation, binding.subjectId) ||
            !stillBound(binding.subjectId, binding.catalog)) return
        val revision = discoverySourceRevision(sourceId)
        val epoch = library.state.value.restoreEpoch
        mutableState.update { it.copy(sourceCatalogErrors = it.sourceCatalogErrors - sourceId) }
        fun current() = request == catalogRequests[sourceId] &&
            currentDetail(generation, binding.subjectId) && stillBound(binding.subjectId, binding.catalog) &&
            library.state.value.restoreEpoch == epoch && discoverySourceRevision(sourceId) == revision &&
            sources.provider(sourceId) != null
        if (current()) publishAssociation(sourceId, AnimeAssociationProgress(AnimeAssociationPhase.SEARCHING))
        try {
            val provider = sources.provider(sourceId) ?: error("这个来源当前不可用，可以解除关联。")
            val received = withTimeoutOrNull(15_000) { sources.catalog(provider, binding.catalog, forceRefresh = true) }
                ?: error("来源目录刷新超时，已保留现有目录。")
            val catalog = retainPartialCatalog(mutableState.value.sourceCatalogs.firstOrNull { it.title == binding.catalog }, received)
            check(catalog.title.sourceId == sourceId && catalog.title.id == binding.catalog.id &&
                catalog.title.url == binding.catalog.url) { "来源目录身份已变化，请重新搜索并确认关联。" }
            if (!current()) return
            mutableState.update { it.copy(
                sourceCatalogs = it.sourceCatalogs.filterNot { old -> old.title.sourceId == sourceId } + catalog,
                sourceCatalogErrors = it.sourceCatalogErrors - sourceId,
            ) }
            stageCatalogue(catalog)
            rebuildOptions()
            if (current()) publishAssociation(sourceId, AnimeAssociationProgress(AnimeAssociationPhase.LINKED, catalog.notice))
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            if (current()) {
                val message = failure.message ?: "目录暂不可用，请重试。"
                mutableState.update { it.copy(sourceCatalogErrors = it.sourceCatalogErrors + (sourceId to message)) }
                publishAssociation(sourceId, AnimeAssociationProgress(AnimeAssociationPhase.UNAVAILABLE, message))
            }
        }
    }

    fun refreshSource(sourceId: String) {
        val detail = mutableState.value.detail ?: return
        val binding = library.state.value.bindings.firstOrNull {
            it.subjectId == detail.subject.id && it.catalog.sourceId == sourceId
        } ?: return
        val generation = detailGeneration
        val request = ++catalogGeneration
        catalogRequests[sourceId] = request
        scope.launch { loadBoundCatalog(binding, generation, request) }
    }

    fun searchSources(query: String = mutableState.value.detail?.subject?.title.orEmpty()) {
        if (mutableState.value.detail == null) return
        val keyword = query.trim()
        if (keyword.isBlank()) return
        ++searchGeneration
        sourceQuery = keyword
        sourceJob?.cancel()
        sourceSearchJobs.values.forEach { it.cancel() }
        sourceSearchJobs.clear()
        mutableState.update { it.copy(sourceError = null, sourceResults =
            sources.providers.map { source -> AnimeSourceResult(source.id, source.name) }) }
        sources.providers.forEach { requestSourceSearch(it.id, append = false) }
    }

    fun retrySourceSearch(sourceId: String) = requestSourceSearch(sourceId, append = false, forceRefresh = true)
    fun loadMoreSourceSearch(sourceId: String) = requestSourceSearch(sourceId, append = true)

    private fun requestSourceSearch(sourceId: String, append: Boolean, forceRefresh: Boolean = false) {
        val detail = mutableState.value.detail ?: return
        val provider = sources.provider(sourceId) ?: return
        if (sourceQuery.isBlank()) sourceQuery = detail.subject.title
        val previous = mutableState.value.sourceResults.firstOrNull { it.providerId == sourceId }
            ?: AnimeSourceResult(sourceId, provider.name)
        if (append && (!previous.hasMore || previous.loading || previous.loadingMore)) return
        val page = if (append) previous.page + 1 else 1
        val keyword = sourceQuery
        val owner = detailGeneration
        val generation = searchGeneration
        val request = ++sourceSearchSequence
        sourceSearchRequests[sourceId] = request
        sourceSearchJobs.remove(sourceId)?.cancel()
        if (mutableState.value.sourceResults.none { it.providerId == sourceId }) mutableState.update {
            it.copy(sourceResults = it.sourceResults + previous)
        }
        updateSourceResult(previous.copy(loading = !append, loadingMore = append, error = null))
        sourceSearchJobs[sourceId] = scope.launch {
            fun current() = generation == searchGeneration && sourceSearchRequests[sourceId] == request &&
                currentDetail(owner, detail.subject.id)
            try {
                val items = sources.search(provider, keyword, page, forceRefresh).distinctBy { it.id to it.url }
                check(items.all { it.sourceId == sourceId }) { "来源搜索返回了不同来源的条目。" }
                if (!current()) return@launch
                val added = if (append) items.filter { item -> previous.items.none { it.id == item.id && it.url == item.url } } else items
                updateSourceResult(AnimeSourceResult(provider.id, provider.name,
                    items = if (append) previous.items + added else items, page = page,
                    hasMore = provider.supportsSearchPagination && added.isNotEmpty()))
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (current()) updateSourceResult(previous.copy(loading = false, loadingMore = false,
                    error = failure.message ?: "这个来源暂未响应，请单独重试。"))
            }
        }
    }

    private fun updateSourceResult(result: AnimeSourceResult) = mutableState.update {
        it.copy(sourceResults = it.sourceResults.map { old -> if (old.providerId == result.providerId) result else old })
    }

    fun previewSource(title: AnimeSourceTitle) {
        val subjectId = mutableState.value.detail?.subject?.id ?: return
        if (title.sourceRevision != null && title.sourceRevision != discoverySourceRevision(title.sourceId)) {
            dismissSourcePreview()
            mutableState.update { it.copy(sourceError = "来源已更新，请返回片源页重新选择这个条目。") }
            return
        }
        val owner = detailGeneration
        val generation = ++previewGeneration
        previewJob?.cancel()
        previewSourceId = title.sourceId
        previewSourceRevision = sourceRevision(title.sourceId)
        mutableState.update { it.copy(previewCatalog = it.previewCatalog?.takeIf { old -> old.title == title },
            previewLoading = true, sourceError = null) }
        previewJob = scope.launch {
            try {
                val provider = sources.provider(title.sourceId) ?: error("这个来源尚未安装。")
                val catalog = sources.catalog(provider, title)
                check(catalog.title.sourceId == title.sourceId && catalog.title.id == title.id && catalog.title.url == title.url) {
                    "来源返回了不同的作品目录，请重新搜索。"
                }
                if (generation == previewGeneration && currentDetail(owner, subjectId))
                    mutableState.update { it.copy(previewCatalog = retainPartialCatalog(it.previewCatalog, catalog), previewLoading = false) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (generation == previewGeneration && currentDetail(owner, subjectId))
                    mutableState.update { it.copy(previewLoading = false, sourceError = failure.message ?: "来源目录未能读取。") }
            }
        }
    }

    /** An incomplete refresh cannot erase a previously observed exact native episode/line. */
    private fun retainPartialCatalog(previous: AnimeSourceCatalog?, current: AnimeSourceCatalog): AnimeSourceCatalog {
        if (current.notice == null || previous == null || previous.title != current.title) return current
        val episodes = previous.episodes.associateBy { it.id } + current.episodes.associateBy { it.id }
        return current.copy(episodes = episodes.values.toList())
    }

    fun dismissSourcePreview() {
        ++previewGeneration
        previewJob?.cancel()
        previewSourceId = null
        previewSourceRevision = null
        mutableState.update { it.copy(previewCatalog = null, previewLoading = false) }
    }

    fun bindPreviewSource() {
        val detail = mutableState.value.detail ?: return
        val catalog = mutableState.value.previewCatalog ?: return
        val nativeOrigin = detail.subject.externalIds["native-source"]
        if (nativeOrigin != null && (nativeOrigin == catalog.title.sourceId && sourceSubject(catalog.title).id != detail.subject.id ||
                nativeOrigin != catalog.title.sourceId && library.state.value.bindings.none {
                    it.subjectId == detail.subject.id && it.catalog.sourceId == nativeOrigin
                })) {
            notice("原作品目录尚未建立，或所选版本属于另一个目录。请打开该来源单独查看。")
            return
        }
        val expectedRevision = previewSourceRevision
        if (previewSourceId != catalog.title.sourceId || sources.provider(catalog.title.sourceId) == null ||
            expectedRevision != sourceRevision(catalog.title.sourceId)) {
            dismissSourcePreview()
            notice("来源已经变化，请重新读取条目后关联。")
            return
        }
        val owner = detailGeneration
        associationJobs.remove(catalog.title.sourceId)?.cancel()
        mutate {
            val groups = catalog.episodes.map { it.group }.distinct()
            library.edit(detail.subject, editBindings = { bindings ->
                check(sources.provider(catalog.title.sourceId) != null && expectedRevision == sourceRevision(catalog.title.sourceId)) {
                    "来源已经变化，请重新读取条目后关联。"
                }
                val previous = bindings.firstOrNull { it.catalog.sourceId == catalog.title.sourceId }
                bindings.filterNot { it.catalog.sourceId == catalog.title.sourceId } + AnimeSourceBinding(
                    detail.subject.id, catalog.title,
                    episodeMappings = previous?.takeIf { it.catalog.id == catalog.title.id && it.catalog.url == catalog.title.url }
                        ?.episodeMappings.orEmpty(),
                    preferred = previous?.preferred ?: bindings.isEmpty(),
                    preferredExplicitly = previous?.preferredExplicitly == true,
                    selectedGroup = previous?.takeIf { it.catalog.id == catalog.title.id && it.catalog.url == catalog.title.url }
                        ?.selectedGroup ?: groups.singleOrNull(), automatic = false)
            }, transform = { it.copy(excludedSourceIds = it.excludedSourceIds - catalog.title.sourceId) })
            if (currentDetail(owner, detail.subject.id)) {
                ++previewGeneration
                previewSourceId = null
                previewSourceRevision = null
                catalogRequests[catalog.title.sourceId] = ++catalogGeneration
                mutableState.update { it.copy(previewCatalog = null, previewLoading = false,
                    sourceCatalogErrors = it.sourceCatalogErrors - catalog.title.sourceId,
                    sourceCatalogs = it.sourceCatalogs.filterNot { old -> old.title.sourceId == catalog.title.sourceId } +
                        if (sources.provider(catalog.title.sourceId) != null && expectedRevision == sourceRevision(catalog.title.sourceId))
                            listOf(catalog) else emptyList()) }
                stageCatalogue(catalog)
                rebuildOptions()
                publishAssociation(catalog.title.sourceId, AnimeAssociationProgress(AnimeAssociationPhase.LINKED))
                if (expectedRevision != sourceRevision(catalog.title.sourceId)) refreshSource(catalog.title.sourceId)
            }
            notice("已关联这个来源，可以撤销。")
        }
    }

    fun unlinkSource(sourceId: String) {
        val detail = mutableState.value.detail ?: return
        val owner = detailGeneration
        if (detail.subject.externalIds["native-source"] == sourceId) {
            notice("这是该条目的原始来源，不能解除自身归属；可在来源设置中停用。")
            return
        }
        associationJobs.remove(sourceId)?.cancel()
        mutate {
            library.edit(detail.subject, editBindings = { bindings -> bindings.filterNot { it.catalog.sourceId == sourceId } },
                transform = { it.copy(excludedSourceIds = it.excludedSourceIds + sourceId) })
            if (currentDetail(owner, detail.subject.id)) {
                publishAssociation(sourceId, AnimeAssociationProgress(AnimeAssociationPhase.EXCLUDED, "已解除关联，不会自动重新关联。"))
                catalogRequests[sourceId] = ++catalogGeneration
                mutableState.update { it.copy(
                    sourceCatalogs = it.sourceCatalogs.filterNot { catalog -> catalog.title.sourceId == sourceId },
                    sourceCatalogErrors = it.sourceCatalogErrors - sourceId,
                ) }
                rebuildOptions()
            }
            notice("已解除关联，观看记录保留。")
        }
    }

    fun setPreferredSource(sourceId: String) {
        val detail = mutableState.value.detail ?: return
        mutate {
            library.edit(detail.subject, editBindings = { bindings ->
                check(bindings.any { it.catalog.sourceId == sourceId }) { "请先关联这个来源。" }
                bindings.map { binding ->
                    val preferred = binding.catalog.sourceId == sourceId
                    binding.copy(preferred = preferred, preferredExplicitly = preferred)
                }
            })
            notice("后续选集优先使用这个来源，当前播放不变。")
        }
    }

    fun selectSourceGroup(sourceId: String, group: String) {
        val detail = mutableState.value.detail ?: return
        val catalog = mutableState.value.sourceCatalogs.firstOrNull { it.title.sourceId == sourceId } ?: return
        if (catalog.episodes.none { it.group == group }) return
        val allowedIds = catalog.episodes.filter { it.group == group }.map { it.id }.toSet()
        mutate {
            library.edit(detail.subject, editBindings = { bindings -> bindings.map { binding ->
                if (binding.catalog.sourceId != sourceId) binding else {
                    check(binding.catalog.id == catalog.title.id && binding.catalog.url == catalog.title.url) {
                        "来源关联已经变化，请重新选择季与线路。"
                    }
                    binding.copy(selectedGroup = group, automatic = false,
                        episodeMappings = binding.episodeMappings.filterValues { it in allowedIds })
                }
            } })
            notice("已选择季与线路；同组唯一对应的集数可直接播放。")
        }
    }

    fun mapEpisode(episodeId: String, sourceId: String, sourceEpisodeId: String) {
        val detail = mutableState.value.detail ?: return
        if (detail.episodes.none { it.id == episodeId }) return
        val catalog = mutableState.value.sourceCatalogs.firstOrNull { it.title.sourceId == sourceId } ?: return
        val episode = catalog.episodes.singleOrNull { it.id == sourceEpisodeId } ?: return
        mutate {
            library.edit(detail.subject, editBindings = { bindings -> bindings.map { binding ->
                if (binding.catalog.sourceId != sourceId) binding else {
                    check(binding.catalog.id == catalog.title.id && binding.catalog.url == catalog.title.url) {
                        "来源关联已经变化，请重新核对这一集。"
                    }
                    binding.copy(selectedGroup = episode.group, automatic = false,
                        episodeMappings = binding.episodeMappings.filterValues { oldId ->
                            catalog.episodes.any { it.id == oldId && it.group == episode.group }
                        } + (episodeId to sourceEpisodeId))
                }
            } })
            notice("已记住本集对应关系，可以撤销。")
        }
    }

    private fun rebuildOptions() {
        val snapshot = mutableState.value
        val baseline = snapshot.detail ?: return
        val stored = library.state.value
        val bindings = stored.bindings.filter { it.subjectId == baseline.subject.id }
        val names = bindings.mapNotNull { binding -> sources.provider(binding.catalog.sourceId)?.let {
            it.id to it.name
        } }.toMap()
        val sourceOwners = names.keys.associateWith { discoverySourceRevision(it) to playbackHistoryRoute(it) }
        val input = EpisodeOptionsInput(baseline, bindings, snapshot.sourceCatalogs, names, sourceOwners)
        val owner = detailGeneration
        val epoch = stored.restoreEpoch
        if (input == episodeOptionsInput && episodeOptionsJob?.isActive == true) return
        if (input == appliedEpisodeOptions) {
            finishOptionsRebuild(baseline)
            return
        }
        val request = ++episodeOptionsGeneration
        episodeOptionsJob?.cancel()
        val previous = episodeOptionsInput
        episodeOptionsInput = input
        appliedEpisodeOptions = null
        // Keep both the previous full projection and any early episode result only
        // where the current inputs still own those lines.
        val unchangedSources = if (previous == null || !sameEpisodeMappingDetail(previous.detail, baseline)) emptySet() else bindings.mapNotNull { binding ->
            val id = binding.catalog.sourceId
            id.takeIf { id in names && previous.sourceOwners[id] == sourceOwners[id] && previous.bindings.firstOrNull { it.catalog.sourceId == id } == binding &&
                previous.catalogs.firstOrNull { it.title.sourceId == id } ==
                    input.catalogs.firstOrNull { it.title.sourceId == id } }
        }.toSet()
        val preferred = bindings.firstOrNull { it.preferred }?.catalog?.sourceId
        mutableState.update { state -> state.copy(
            episodeOptions = when {
                unchangedSources.isEmpty() -> emptyMap()
                previous != null && unchangedSources.containsAll(previous.sourceNames.keys) -> state.episodeOptions
                else -> state.episodeOptions.mapValues { (_, options) -> options.filter { it.sourceId in unchangedSources } }
            },
            episodeOptionsLoading = true, preferredSourceId = preferred, sourceBindings = bindings,
            sourceGroups = bindings.mapNotNull { binding -> binding.selectedGroup?.let { binding.catalog.sourceId to it } }.toMap(),
            sourceCatalogErrors = state.sourceCatalogErrors.filterKeys { id -> bindings.any { it.catalog.sourceId == id } },
        ) }
        fun ownsProjection() = request == episodeOptionsGeneration && currentDetail(owner, baseline.subject.id) &&
            library.state.value.restoreEpoch == epoch
        fun inputsCurrent() = mutableState.value.detail == baseline && mutableState.value.sourceCatalogs == input.catalogs &&
            library.state.value.bindings.filter { it.subjectId == baseline.subject.id } == bindings &&
            sourceOwners.all { (id, ownership) -> sources.provider(id) != null &&
                ownership == (discoverySourceRevision(id) to playbackHistoryRoute(id)) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // Binding persistence and its catalogue receipt can arrive in the same UI turn.
            yield()
            try {
                val focusIds = listOfNotNull(mutableState.value.playbackTarget?.episode?.id, pendingResumeEpisode(baseline)?.id,
                    associationEpisodeId, continuationEpisode(baseline.subject.id)?.id)
                val (detail, options, members) = withContext(Dispatchers.Default) {
                    val nativeSource = baseline.subject.externalIds["native-source"]
                    val nativeBinding = bindings.firstOrNull { it.catalog.sourceId == nativeSource }
                    val nativeCatalog = input.catalogs.firstOrNull { it.title.sourceId == nativeSource }
                    val detail = if (nativeBinding?.selectedGroup != null && nativeCatalog != null)
                        nativeDetail(baseline.subject, nativeCatalog, nativeBinding.selectedGroup) else baseline
                    val matchers = bindings.mapNotNull { binding ->
                        val catalog = input.catalogs.firstOrNull { it.title.sourceId == binding.catalog.sourceId &&
                            it.title.id == binding.catalog.id && it.title.url == binding.catalog.url } ?: return@mapNotNull null
                        if (binding.catalog.sourceId !in names || binding.automatic && nativeSource != binding.catalog.sourceId &&
                            !autoPlaySubjectCandidate(detail.subject, catalog.title)) return@mapNotNull null
                        binding.catalog.sourceId to (catalog to AnimeEpisodeMatcher(detail, catalog))
                    }.toMap()
                    val explicitEpisodeIndexes = mutableMapOf<Pair<String, String>, Map<String, List<AnimeSourceEpisode>>>()
                    fun optionsFor(canonical: AnimeEpisode): List<AnimeEpisodeOption> =
                        bindings.flatMap bindingLoop@ { binding ->
                            val sourceId = binding.catalog.sourceId
                            val (catalog, matcher) = matchers[sourceId] ?: return@bindingLoop emptyList()
                            val automaticGroups = matcher.groups(canonical, binding.selectedGroup)
                            fun mappingStillMatches(episode: AnimeSourceEpisode): Boolean =
                                detail.subject.canonicalDoubanId() == null || !binding.automatic || matcher.matches(canonical, episode)
                            val mappedGroup = binding.episodeMappings[canonical.id]?.let { id ->
                                matcher.groupedEpisodes[binding.selectedGroup].orEmpty()
                                    .singleOrNull { it.id == id && mappingStillMatches(it) }?.group
                            }
                            val groups = when {
                                mappedGroup != null -> if (binding.automatic && mappedGroup in automaticGroups)
                                    automaticGroups else listOf(mappedGroup)
                                binding.selectedGroup == null -> automaticGroups
                                !binding.automatic -> listOf(binding.selectedGroup)
                                nativeSource == binding.catalog.sourceId -> {
                                    if (automaticGroups.size > 1 && binding.selectedGroup in automaticGroups) automaticGroups
                                    else listOf(binding.selectedGroup)
                                }
                                else -> automaticGroups
                            }
                            val offered = groups.flatMap { group ->
                                val intrinsic = nativeSource == sourceId
                                val explicitId = if (intrinsic) canonical.id else binding.episodeMappings[canonical.id]
                                val explicit = explicitId?.let { id ->
                                    val byId = explicitEpisodeIndexes.getOrPut(sourceId to group) {
                                        matcher.groupedEpisodes[group].orEmpty().groupBy { episode ->
                                            if (intrinsic) nativeEpisodeId(detail.subject, episode) else episode.id
                                        }
                                    }
                                    byId[id]?.filter { intrinsic || mappingStillMatches(it) }?.singleOrNull()
                                }
                                val matches = explicit?.let(::listOf) ?: if ((intrinsic && groups.size == 1) ||
                                    !canonical.number.isFinite()) emptyList() else matcher.matchingEpisodes(canonical, group)
                                matches.map { episode -> AnimeEpisodeOption(binding.catalog.sourceId,
                                    names.getValue(binding.catalog.sourceId), binding.catalog, episode,
                                    confirmed = explicit != null || matches.size == 1) }
                            }
                            // Expand equivalent routes once here, not on every cell composition or
                            // playback-status read. Manual, non-numbered mappings remain exact.
                            offered.flatMap { base ->
                                if (!base.confirmed || (base.sourceId != "anime-age" && base.sourceId != "anime-tvtfun" &&
                                    !hasLmmCatalogWorkId(catalog.title)) || !matcher.matches(canonical, base.episode)) listOf(base)
                                else {
                                    val equivalent = matcher.groups(canonical, base.episode.group)
                                    if (equivalent.size < 2 || base.episode.group !in equivalent) listOf(base)
                                    else listOf(base) + equivalent.filterNot { it == base.episode.group }.mapNotNull { group ->
                                        matcher.matchingEpisodes(canonical, group).singleOrNull()?.let { base.copy(episode = it) }
                                    }
                                }
                            }.distinctBy(::optionKey)
                        }.sortedWith(compareByDescending<AnimeEpisodeOption> { it.sourceId == preferred }.thenBy { it.sourceName })
                    val focused = focusIds.firstNotNullOfOrNull { id -> detail.episodes.firstOrNull { it.id == id } }
                    val focusedOptions = focused?.let(::optionsFor).orEmpty()
                    if (focused != null && detail == baseline && focusedOptions.any { it.confirmed }) {
                        withContext(Dispatchers.Main.immediate) publishFocused@ {
                            if (!ownsProjection() || !inputsCurrent()) return@publishFocused
                            val state = mutableState.value
                            if (state.episodeOptions[focused.id] == focusedOptions) return@publishFocused
                            mutableState.update { it.copy(
                                episodeOptions = it.episodeOptions + (focused.id to focusedOptions),
                                detailFirstConfirmedLineMs = it.detailFirstConfirmedLineMs ?: detailOpenElapsed(),
                            ) }
                            if (ownsProjection()) {
                                resumeAfterOptions(detail, readyEpisodeId = focused.id)
                                prepareDetailOpeningAddresses()
                            }
                        }
                    }
                    val options = detail.episodes.associate { canonical ->
                        currentCoroutineContext().ensureActive()
                        canonical.id to if (canonical === focused) focusedOptions else optionsFor(canonical)
                    }
                    Triple(detail, options, input.catalogs.associate { catalog ->
                        catalog.title.sourceId to (catalog to catalog.episodes.toSet())
                    })
                }
                if (!ownsProjection()) return@launch
                if (!inputsCurrent()) {
                    rebuildOptions()
                    return@launch
                }
                val applied = input.copy(detail = detail)
                episodeOptionsInput = applied
                appliedEpisodeOptions = applied
                projectedEpisodeMembers = members
                mutableState.update { it.copy(detail = detail, episodeOptions = options, episodeOptionsLoading = false,
                    detailFirstConfirmedLineMs = it.detailFirstConfirmedLineMs ?: detailOpenElapsed()?.takeIf {
                        options.values.any { lines -> lines.any { line -> line.confirmed } }
                    },
                    episodeProgress = projectEpisodeProgress(library.state.value, detail),
                    sourceDanmakuAvailable = active?.let(::hasSourceDanmaku) == true) }
                finishOptionsRebuild(detail)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (request == episodeOptionsGeneration && currentDetail(owner, baseline.subject.id) &&
                    library.state.value.restoreEpoch == epoch) mutableState.update {
                    it.copy(episodeOptionsLoading = false,
                        sourceError = failure.message ?: "剧集对应未能完成，已保留可用线路，请重试。")
                }
            }
        }
        episodeOptionsJob = job
        job.start()
    }

    private fun sameEpisodeMappingDetail(left: AnimeDetail, right: AnimeDetail): Boolean {
        fun identity(subject: AnimeSubject) = subject.copy(coverUrl = "", description = "", tags = emptyList(),
            airWeekday = null, rating = null, rank = null, ratingCount = null)
        val sameEpisodes = left.episodes.size == right.episodes.size && left.episodes.indices.all { index ->
            val before = left.episodes[index]
            val after = right.episodes[index]
            before == after || before.copy(title = after.title, durationMs = after.durationMs,
                airDate = after.airDate) == after
        }
        return identity(left.subject) == identity(right.subject) && sameEpisodes &&
            left.metadataSubject?.let { it.id to it.canonicalBangumiId() } ==
                right.metadataSubject?.let { it.id to it.canonicalBangumiId() }
    }

    private fun finishOptionsRebuild(detail: AnimeDetail) {
        scheduleCatalogBootstrap()
        invalidatePreparation()
        resumeAfterOptions(detail)
        prepareDetailOpeningAddresses()
    }

    private fun detailOpenElapsed(): Long? =
        detailOpenedAt?.let { (monotonicTimeMillis() - it).coerceAtLeast(0L) }

    private fun pendingResumeEpisode(detail: AnimeDetail): AnimeEpisode? {
        val request = resumeRequest?.takeIf { it.first == detail.subject.id } ?: return null
        val saved = detail.episodes.firstOrNull { it.id == request.second && it.subjectId == request.first } ?: return null
        if (!resumeAfterCompleted) return saved
        val group = detail.episodes.filter { it.subjectId == request.first && it.kind == saved.kind }
        return group.getOrNull(group.indexOfFirst { it.id == saved.id } + 1)
            ?: saved.takeUnless { mutableState.value.detailLoading }
    }

    private fun resumeAfterOptions(detail: AnimeDetail, readyEpisodeId: String? = null) {
        val target = pendingResumeEpisode(detail) ?: return
        if (readyEpisodeId != null && target.id != readyEpisodeId ||
            episodeAvailability(target.id) == AnimeEpisodeAvailability.UPCOMING) return
        resumeRequest = null
        resumeAfterCompleted = false
        play(target.id)
    }

    private fun resetPlaybackRecoveryWindow(keepOpening: OpeningAddresses? = null) {
        if (keepOpening == null || openingAddresses !== keepOpening) releaseOpeningAddresses()
        autoPlaybackDeadline = monotonicTimeMillis() + 45_000L
        recoveryOptions.clear()
        playbackAddressRetries.clear()
        sourceRecoveryWindowStarted = false
        recoveryResume = null
        playbackDiscoveryRetried = false
        playbackStartupRefreshes.clear()
        mutableState.update { it.copy(playbackRecoveryStopReason = null) }
    }

    fun play(episodeId: String, option: AnimeEpisodeOption? = null) {
        val requestedAt = monotonicTimeMillis()
        val detail = mutableState.value.detail ?: return
        val episode = detail.episodes.firstOrNull { it.id == episodeId } ?: return
        cancelEpisodeSourceMeasurements()
        val readyAddresses = matchingOpeningAddresses(episodeId)
        cancelPlaybackResolution(readyAddresses)
        resetPlaybackRecoveryWindow(readyAddresses)
        playbackIntent = PlaybackIntentTiming(detail.subject.id, episodeId, requestedAt)
        if (option == null && !hasAutomaticPlaybackLine(episodeId)) {
            // A new play intent may retry temporary failures for this exact episode.
            // Recovery within the same round never clears these receipts, and
            // a still-valid unsupported-capability receipt remains authoritative.
            confirmedPlaybackLines(episodeId).forEach { line ->
                val key = playbackHistoryKey(detail.subject.id, episodeId, line) ?: return@forEach
                if (currentPlaybackFailure(key)?.unsupported == false) playbackFailures.remove(key)
            }
        }
        mutableState.update { it.copy(playbackTarget = AnimePlaybackTarget(episode, option), autoMatching = false) }
        if (option == null && !hasAutomaticPlaybackLine(episodeId)) {
            autoAssociateAndPlay(episodeId)
        } else {
            resolveAndPlay(episodeId, option)
        }
    }

    private fun cancelSourceAggregation() {
        ++episodeOptionsGeneration
        episodeOptionsJob?.cancel()
        episodeOptionsJob = null
        episodeOptionsInput = null
        appliedEpisodeOptions = null
        projectedEpisodeMembers = emptyMap()
        detailOpenedAt = null
        ++associationGeneration
        associationEpisodeId = null
        associationJobs.values.forEach { it.cancel() }
        associationJobs.clear()
        associationSignatures.clear()
        associationAttempts.clear()
        associationCandidates.clear()
        catalogueJob?.cancel()
        catalogueJob = null
        catalogueRebuildRequested = false
        catalogueCandidates.clear()
        cataloguePriority = emptyList()
        mutableState.update { it.copy(sourceAggregation = emptyMap(), sourceAggregationRunning = false,
            episodeOptionsLoading = false, detailFirstConfirmedLineMs = null,
            associationChoices = emptyMap(),
            sourceAggregationCompleted = 0, sourceAggregationTotal = 0) }
    }

    private fun publishAssociation(sourceId: String, progress: AnimeAssociationProgress) {
        mutableState.update { current ->
            val rows = current.sourceAggregation + (sourceId to progress)
            current.copy(sourceAggregation = rows,
                sourceAggregationRunning = rows.values.any { it.phase in setOf(AnimeAssociationPhase.QUEUED, AnimeAssociationPhase.SEARCHING) },
                sourceAggregationCompleted = rows.values.count { it.phase !in setOf(AnimeAssociationPhase.QUEUED, AnimeAssociationPhase.SEARCHING) },
                sourceAggregationTotal = rows.size)
        }
        scheduleCatalogBootstrap()
    }

    private fun stageCatalogue(catalog: AnimeSourceCatalog) {
        val detail = mutableState.value.detail ?: return
        if (detail.subject.canonicalDoubanId() == null || catalog.notice != null) return
        val sourceId = catalog.title.sourceId
        catalogueCandidates[sourceId] = SourceCatalogueCandidate(catalog, discoverySourceRevision(sourceId),
            playbackHistoryRoute(sourceId), catalogRequests[sourceId], detailGeneration, library.state.value.restoreEpoch)
    }

    private suspend fun retainCatalogue(detail: AnimeDetail, owner: Long, epoch: Long) {
        if (detail.subject.canonicalDoubanId() == null || detail.episodes.isEmpty()) return
        fun current() = currentDetail(owner, detail.subject.id) && library.state.value.restoreEpoch == epoch &&
            mutableState.value.detail == detail
        if (!current() || library.state.value.entries[detail.subject.id]?.catalogDetail == detail) return
        library.editPlaybackAssociation(detail.subject, editBindings = { bindings ->
            check(current()) { "作品目录已经变化，未覆盖保存的选集。" }
            bindings
        }, transform = { entry ->
            check(current()) { "作品目录已经变化，未覆盖保存的选集。" }
            entry.copy(catalogDetail = detail)
        })
        if (current()) metadata.cacheCatalogDetail(detail)
    }

    private fun scheduleCatalogBootstrap() {
        val snapshot = mutableState.value
        val detail = snapshot.detail ?: return
        if (detail.subject.canonicalDoubanId() == null) return
        if (catalogueJob?.isActive == true) {
            catalogueRebuildRequested = true
            return
        }
        val owner = detailGeneration
        val epoch = library.state.value.restoreEpoch
        catalogueRebuildRequested = false
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // Let this scheduling turn register every provider before comparing priority.
                yield()
                val live = mutableState.value
                val baseline = live.detail?.takeIf { it.subject.id == detail.subject.id } ?: return@launch
                val bindings = library.state.value.bindings.filter { it.subjectId == detail.subject.id }
                val ordered = catalogueCandidates.values.sortedWith(compareByDescending<SourceCatalogueCandidate> { candidate ->
                    bindings.any { it.catalog.sourceId == candidate.catalog.title.sourceId && it.preferred }
                }.thenBy { candidate -> cataloguePriority.indexOf(candidate.catalog.title.sourceId).takeIf { it >= 0 } ?: Int.MAX_VALUE }
                    .thenBy { it.catalog.title.sourceId })
                val eligible = ordered.filter { candidate ->
                    catalogueCurrent(candidate, baseline.subject.id) && candidate.catalog.title.sourceId !in
                        library.state.value.entries[baseline.subject.id]?.excludedSourceIds.orEmpty()
                }
                val chosen = withContext(Dispatchers.Default) {
                    eligible.firstNotNullOfOrNull { candidate ->
                        currentCoroutineContext().ensureActive()
                        projectAnimeSourceCatalogue(baseline, candidate.catalog,
                            bindings.singleOrNull { it.catalog.sourceId == candidate.catalog.title.sourceId })
                            ?.let { candidate to it }
                    }
                } ?: return@launch
                val (candidate, projection) = chosen
                val sourceId = candidate.catalog.title.sourceId
                val binding = bindings.singleOrNull { it.catalog.sourceId == sourceId }
                if (baseline.episodes == projection.episodes && binding?.episodeMappings == projection.mappings) return@launch
                val next = baseline.copy(episodes = projection.episodes)
                fun valid() = catalogueCurrent(candidate, next.subject.id) && mutableState.value.detail == baseline &&
                    sourceId !in library.state.value.entries[next.subject.id]?.excludedSourceIds.orEmpty()
                if (!valid()) {
                    catalogueRebuildRequested = true
                    return@launch
                }
                try {
                    library.editPlaybackAssociation(next.subject, editBindings = { saved ->
                        check(valid()) { "来源或作品已变化，未替换统一目录。" }
                        val previous = saved.singleOrNull { it.catalog.sourceId == sourceId }
                        check(previous == binding) { "来源选择已经变化，未覆盖新的选择。" }
                        saved.filterNot { it.catalog.sourceId == sourceId } +
                            (previous ?: AnimeSourceBinding(next.subject.id, candidate.catalog.title.copy(sourceRevision = candidate.revision),
                                preferred = saved.isEmpty(), automatic = true)).copy(
                                selectedGroup = projection.group, episodeMappings = projection.mappings)
                    }, transform = { entry ->
                        check(valid()) { "作品已变化，未替换统一目录。" }
                        entry.copy(catalogDetail = next)
                    })
                    if (!valid()) return@launch
                    mutableState.update { it.copy(detail = next,
                        sourceCatalogs = it.sourceCatalogs.filterNot { row -> row.title.sourceId == sourceId } + candidate.catalog,
                        sourceCatalogErrors = it.sourceCatalogErrors - sourceId) }
                    rebuildOptions()
                    publishAssociation(sourceId, AnimeAssociationProgress(AnimeAssociationPhase.LINKED))
                    if (catalogueCurrent(candidate, next.subject.id)) startSourceAggregation(next)
                    if (currentDetail(candidate.owner, next.subject.id) && mutableState.value.detail == next)
                        metadata.cacheCatalogDetail(next)
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) {
                    if (catalogueCurrent(candidate, next.subject.id)) notice(failure.message ?: "统一选集未能保存，请重试。")
                }
            } finally {
                val running = currentCoroutineContext()[Job]
                if (catalogueJob === running) {
                    catalogueJob = null
                    val rerun = catalogueRebuildRequested
                    catalogueRebuildRequested = false
                    if (rerun && running?.isActive == true && currentDetail(owner, detail.subject.id) &&
                        library.state.value.restoreEpoch == epoch) scheduleCatalogBootstrap()
                }
            }
        }
        catalogueJob = job
        job.start()
    }

    private fun catalogueCurrent(candidate: SourceCatalogueCandidate, subjectId: String): Boolean {
        val id = candidate.catalog.title.sourceId
        return currentDetail(candidate.owner, subjectId) && candidate.restoreEpoch == library.state.value.restoreEpoch &&
            candidate.request == catalogRequests[id] && candidate.revision == discoverySourceRevision(id) &&
            candidate.route == playbackHistoryRoute(id) && sources.provider(id) != null
    }

    /** Detail-owned discovery, independent of player preparation and its cancellation. */
    private fun startSourceAggregation(
        requestedDetail: AnimeDetail,
        force: Boolean = false,
        retryFailed: Boolean = false,
        episodeId: String? = null,
        onlySourceId: String? = null,
    ) {
        // Awaiting library/extension readiness can outlive a metadata refresh. The visible
        // current detail, not the caller's older title/year snapshot, owns discovery evidence.
        val detail = mutableState.value.detail?.takeIf { it.subject.id == requestedDetail.subject.id } ?: return
        val nativeOrigin = detail.subject.externalIds["native-source"]
        // A native work needs its exact original record before alternative bindings can be saved.
        if (nativeOrigin != null && library.state.value.bindings.none {
                it.subjectId == detail.subject.id && it.catalog.sourceId == nativeOrigin
            }) return
        if (episodeId != null && detail.episodes.any { it.id == episodeId }) associationEpisodeId = episodeId
        fun focusFor(value: AnimeDetail) = value.episodes.firstOrNull { it.id == associationEpisodeId }
            ?: value.episodes.firstOrNull { it.kind == AnimeEpisodeKind.MAIN && it.number.isFinite() && it.number > 0.0 }
        val focus = focusFor(detail)
        val owner = detailGeneration
        val generation = associationGeneration
        val epoch = library.state.value.restoreEpoch
        val savedBindings = library.state.value.bindings.filter { it.subjectId == detail.subject.id }
            .associateBy { it.catalog.sourceId }
        val providers = sources.providers.sortedBy { provider ->
            when {
                savedBindings[provider.id]?.preferred == true -> 0
                provider.id in savedBindings -> 1
                associationCandidates[provider.id]?.second?.any { autoPlaySubjectHasSharedId(detail.subject, it) } == true -> 2
                associationCandidates[provider.id]?.second?.any { autoPlaySubjectMatches(detail.subject, it) } == true -> 3
                else -> 4
            }
        }
        cataloguePriority = providers.map { it.id }
        val eligible = providers.map { it.id }.toSet()
        associationJobs.keys.filter { it !in eligible }.forEach { id ->
            associationJobs.remove(id)?.cancel(); associationSignatures.remove(id)
        }
        mutableState.update { state ->
            val rows = state.sourceAggregation.filterKeys { it in eligible }
            state.copy(sourceAggregation = rows,
                sourceAggregationRunning = rows.values.any { it.phase in setOf(AnimeAssociationPhase.QUEUED, AnimeAssociationPhase.SEARCHING) },
                sourceAggregationCompleted = rows.values.count { it.phase !in setOf(AnimeAssociationPhase.QUEUED, AnimeAssociationPhase.SEARCHING) },
                sourceAggregationTotal = rows.size)
        }
        for (provider in providers) {
            val sourceId = provider.id
            if (onlySourceId != null && sourceId != onlySourceId) continue
            val revision = discoverySourceRevision(sourceId)
            val route = playbackHistoryRoute(sourceId)
            val bound = sourceId in savedBindings
            val oldSignature = associationSignatures[sourceId]
            val signature = with(detail.subject) {
                val unboundFocus = focus.takeUnless { bound }
                AssociationSignature(id, title, originalTitle, year, externalIds, revision, route,
                    unboundFocus?.id, unboundFocus?.let { it.number to it.numberInSubject })
            }
            if (!force && !retryFailed && bound && oldSignature?.copy(episodeId = null, episodeNumbers = null) == signature &&
                mutableState.value.sourceAggregation[sourceId]?.phase == AnimeAssociationPhase.LINKED) continue
            val progress = mutableState.value.sourceAggregation[sourceId]
            val sameLookup = oldSignature?.copy(episodeId = signature.episodeId,
                episodeNumbers = signature.episodeNumbers) == signature
            if (!force && sameLookup && !(retryFailed && progress?.phase == AnimeAssociationPhase.UNAVAILABLE)) {
                if (associationJobs[sourceId]?.isActive == true) {
                    // Search and catalogue requests belong to the work. Their result
                    // is matched against the live episode when it becomes available.
                    if (progress != null && progress.episodeId != signature.episodeId &&
                        progress.phase in setOf(AnimeAssociationPhase.QUEUED, AnimeAssociationPhase.SEARCHING)) {
                        publishAssociation(sourceId, progress.copy(episodeId = signature.episodeId))
                    }
                    continue
                }
                if (oldSignature?.episodeNumbers == signature.episodeNumbers &&
                    progress != null && progress.episodeId == signature.episodeId &&
                    progress.phase !in setOf(AnimeAssociationPhase.QUEUED, AnimeAssociationPhase.SEARCHING)) continue
            }
            if (!force && associationSignatures[sourceId] == signature &&
                !(retryFailed && mutableState.value.sourceAggregation[sourceId]?.phase == AnimeAssociationPhase.UNAVAILABLE)) continue
            associationJobs.remove(sourceId)?.cancel()
            associationSignatures[sourceId] = signature
            val attempt = ++associationSequence
            associationAttempts[sourceId] = attempt
            // Allocate at scheduling, before the permit wait: an older queued discovery
            // must not regain ownership after an explicit refresh has started.
            val catalogRequest = ++catalogGeneration
            catalogRequests[sourceId] = catalogRequest
            fun current() = currentDetail(owner, detail.subject.id) && generation == associationGeneration &&
                epoch == library.state.value.restoreEpoch && associationSignatures[sourceId] == signature &&
                associationAttempts[sourceId] == attempt &&
                catalogRequests[sourceId] == catalogRequest &&
                sources.provider(sourceId) != null && discoverySourceRevision(sourceId) == revision &&
                playbackHistoryRoute(sourceId) == route
            fun report(phase: AnimeAssociationPhase, message: String? = null,
                checks: List<AnimeAssociationCandidateCheck> = emptyList()) {
                if (!current()) return
                if (checks.isNotEmpty()) {
                    val candidates = checks.map { it.title }.distinctBy { it.id to it.url }.take(12)
                    mutableState.update { it.copy(associationChoices = it.associationChoices + (sourceId to candidates)) }
                } else if (phase in setOf(AnimeAssociationPhase.LINKED, AnimeAssociationPhase.NO_MATCH,
                    AnimeAssociationPhase.EXCLUDED)) {
                    mutableState.update { it.copy(associationChoices = it.associationChoices - sourceId) }
                }
                val scopedEpisode = if (bound) null else mutableState.value.detail?.let(::focusFor)?.id
                publishAssociation(sourceId, AnimeAssociationProgress(phase, message, scopedEpisode, checks.take(12)))
            }
            report(AnimeAssociationPhase.QUEUED)
            associationJobs[sourceId] = scope.launch {
                try {
                    library.awaitLoaded()
                    if (!current()) return@launch
                    if (sourceId in library.state.value.entries[detail.subject.id]?.excludedSourceIds.orEmpty()) {
                        report(AnimeAssociationPhase.EXCLUDED, "已按你的选择解除，不会自动重新关联。")
                        return@launch
                    }
                    var remainingSourceMs = 35_000L
                    var remainingSearchMs = 14_000L
                    suspend fun <T> readSource(timeoutMs: Long, searching: Boolean = false, read: suspend () -> T): T? {
                        suspend fun admitted(): T? = associationPermits.withPermit {
                            if (!current()) return@withPermit null
                            val remaining = minOf(remainingSourceMs,
                                if (searching) remainingSearchMs else remainingSourceMs)
                            if (remaining <= 0L) return@withPermit null
                            report(AnimeAssociationPhase.SEARCHING)
                            val started = monotonicTimeMillis()
                            try { withTimeoutOrNull(minOf(timeoutMs, remaining)) { read() } }
                            finally {
                                val elapsed = (monotonicTimeMillis() - started).coerceAtLeast(0L)
                                remainingSourceMs = (remainingSourceMs - elapsed).coerceAtLeast(0L)
                                if (searching) remainingSearchMs = (remainingSearchMs - elapsed).coerceAtLeast(0L)
                            }
                        }
                        // Each request rejoins the queue; one slow source cannot own
                        // a slot while searching every alias/page and then its catalogue.
                        suspend fun sourceSlot(): T? = if (provider is LanercProvider)
                            associationScriptPermits.withPermit { admitted() } else admitted()
                        return if (!searching) sourceSlot()
                            else if (provider is LanercProvider) associationScriptSearchPermits.withPermit {
                                associationSearchPermits.withPermit { sourceSlot() }
                            } else associationSearchPermits.withPermit { sourceSlot() }
                    }
                    suspend fun findCatalogues() {
                        if (!current()) return
                        val loaded = run aggregation@ {
                            val existing = library.state.value.bindings.firstOrNull {
                                it.subjectId == detail.subject.id && it.catalog.sourceId == sourceId
                            }
                            if (existing != null) {
                                // Native detail already loaded this bound catalog. Leave its playable
                                // lines intact while the other sources are discovered in parallel.
                                val loadedCatalog = mutableState.value.sourceCatalogs.firstOrNull {
                                    it.title.sourceId == sourceId && it.title.id == existing.catalog.id &&
                                        it.title.url == existing.catalog.url && it.notice == null
                                }
                                if (!force && !retryFailed && loadedCatalog != null &&
                                    detail.subject.externalIds["native-source"] == sourceId) {
                                    report(AnimeAssociationPhase.LINKED)
                                    return@aggregation true
                                }
                                val catalog = readSource(15_000L) { sources.catalog(provider, existing.catalog, forceRefresh = force || retryFailed) }
                                    ?: error("来源目录读取超时，请重试。")
                                check(catalog.title.sourceId == sourceId && catalog.title.id == existing.catalog.id &&
                                    catalog.title.url == existing.catalog.url) { "来源目录身份不一致。" }
                                if (!current() || !stillBound(detail.subject.id, existing.catalog)) return@aggregation false
                                mutableState.update { state -> state.copy(sourceCatalogs = state.sourceCatalogs.filterNot {
                                    it.title.sourceId == sourceId
                                } + retainPartialCatalog(state.sourceCatalogs.firstOrNull { it.title.sourceId == sourceId }, catalog),
                                    sourceCatalogErrors = state.sourceCatalogErrors - sourceId) }
                                stageCatalogue(catalog)
                                rebuildOptions()
                                val liveDetail = mutableState.value.detail ?: return@aggregation false
                                val automaticallyBound = mutableState.value.sourceBindings.any {
                                    it.catalog.sourceId == sourceId && it.automatic
                                }
                                val identityIssue = if (automaticallyBound && nativeOrigin != sourceId &&
                                    !autoPlaySubjectCandidate(liveDetail.subject, catalog.title))
                                    animeCatalogIdentityIssue(liveDetail, catalog) else null
                                report(AnimeAssociationPhase.LINKED, catalog.notice ?: identityIssue?.let {
                                    "$it 已暂停这个来源的自动对应。"
                                })
                                return@aggregation true
                            }
                            val keywords = autoPlaySearchKeywords(detail.subject)
                            val rows = if (force) mutableListOf<AnimeSourceTitle>() else associationCandidates[sourceId]
                                ?.takeIf { it.first == revision }?.second.orEmpty().toMutableList()
                            var searchSucceeded = false
                            var searchFailure: String? = null
                            val seenByKeyword = keywords.associateWith { mutableSetOf<Pair<String, String>>() }
                            val finishedKeywords = mutableSetOf<String>()
                            val earlyCatalogues = mutableMapOf<Pair<String, String>, AnimeSourceCatalog>()
                            val earlyCatalogueAttempts = mutableSetOf<Pair<String, String>>()
                            fun hasCatalogueCandidate(): Boolean {
                                if (rows.any { autoPlaySubjectHasSharedId(detail.subject, it) }) return true
                                val candidate = rows.singleOrNull { autoPlaySubjectCandidate(detail.subject, it) }
                                return candidate != null && autoPlaySubjectMatches(detail.subject, candidate)
                            }
                            suspend fun verifyEarlyCatalogue(): Boolean {
                                // Native suggestions often omit the year. Read the unique exact
                                // title's real catalogue before spending the recall budget on aliases.
                                val title = rows.singleOrNull { autoPlaySubjectCandidate(detail.subject, it) }
                                    ?.takeIf { it.year == null && autoPlayTitleCandidate(detail.subject, it) } ?: return false
                                val key = title.id to title.url
                                if (earlyCatalogueAttempts.size >= 2 || !earlyCatalogueAttempts.add(key)) return false
                                val catalog = try {
                                    readSource(if (provider is LanercProvider) 8_000L else 6_000L) {
                                        sources.catalog(provider, title, forceRefresh = force || retryFailed)
                                    }
                                } catch (cancel: CancellationException) { throw cancel }
                                catch (_: Exception) { null } ?: return false
                                if (!current() || catalog.title.sourceId != sourceId || catalog.title.id != title.id ||
                                    catalog.title.url != title.url) return false
                                earlyCatalogues[key] = catalog
                                rows.indexOfFirst { it.id == title.id && it.url == title.url }.takeIf { it >= 0 }
                                    ?.let { rows[it] = catalog.title }
                                // Contradictory or incomplete evidence continues recall; it never
                                // promotes a same-name suggestion directly into a binding.
                                return catalog.notice == null && animeCatalogIdentityIssue(detail, catalog) == null
                            }
                            // Recall only. Round-robin keywords so extra title pages cannot
                            // starve the original-title first page. Leave time to read catalogues.
                            val lastPage = if (provider.supportsSearchPagination) 3 else 1
                            run recall@ {
                                for (pageNumber in 1..lastPage) {
                                    for (keyword in keywords) {
                                        // A confirmed search hit can go straight to catalogue verification.
                                        if (hasCatalogueCandidate() || verifyEarlyCatalogue()) return@recall true
                                        if (keyword in finishedKeywords) continue
                                        if (!current()) return@recall false
                                        val page = try {
                                            readSource(if (provider is LanercProvider) 6_000L else 5_000L, searching = true) {
                                                sources.search(provider, keyword, pageNumber, forceRefresh = force || retryFailed)
                                            }
                                                ?.also { searchSucceeded = true } ?: run {
                                                searchFailure = "来源搜索超时，本次搜索尚未完成。"
                                                null
                                            }
                                        } catch (cancel: CancellationException) { throw cancel }
                                        catch (failure: Exception) {
                                            searchFailure = failure.message ?: "来源搜索失败，本次搜索尚未完成。"
                                            null
                                        }
                                        if (page == null) {
                                            finishedKeywords += keyword
                                            continue
                                        }
                                        check(page.all { it.sourceId == sourceId }) { "来源搜索身份不一致。" }
                                        val seen = seenByKeyword.getValue(keyword)
                                        val fresh = page.distinctBy { it.id to it.url }.filter { seen.add(it.id to it.url) }
                                        if (fresh.isEmpty()) finishedKeywords += keyword
                                        fresh.forEach { found ->
                                            // The fresh row may carry evidence missing from the staged row.
                                            val index = rows.indexOfFirst { it.id == found.id && it.url == found.url }
                                            if (index < 0) rows += found
                                            else if (autoPlaySubjectMatches(detail.subject, found)) rows[index] = found
                                        }
                                    }
                                    if (finishedKeywords.size == keywords.size) break
                                }
                                true
                            }
                            if (!current()) return@aggregation false
                            if (searchSucceeded) associationCandidates[sourceId] = revision to rows.toList()
                            val titles = rows.filter { autoPlaySubjectCandidate(detail.subject, it) }
                            if (titles.isEmpty()) {
                                // A source may list the same season with contradictory metadata.
                                // Never bind it automatically, but provide a clear review candidate.
                                val review = rows.filter { autoPlayTitleCandidate(detail.subject, it) }
                                when {
                                    review.isNotEmpty() -> report(AnimeAssociationPhase.NEEDS_SELECTION,
                                        checks = review.map { AnimeAssociationCandidateCheck(it, AnimeAssociationPhase.NEEDS_SELECTION,
                                            "找到同名作品，但年份或身份不一致，请核对后关联。") })
                                    searchFailure != null -> report(AnimeAssociationPhase.UNAVAILABLE, searchFailure)
                                    else -> report(AnimeAssociationPhase.NO_MATCH,
                                        "本次有界搜索未找到可靠同作品候选，可手动继续搜索。")
                                }
                                return@aggregation true
                            }
                            val identified = titles.filter { autoPlaySubjectHasSharedId(detail.subject, it) }
                            val possible = identified.ifEmpty { titles }
                            if (possible.size > 2) {
                                report(AnimeAssociationPhase.NEEDS_SELECTION, checks = possible.map {
                                    AnimeAssociationCandidateCheck(it, AnimeAssociationPhase.NEEDS_SELECTION,
                                        "同名版本不唯一，可在来源详情中核对。")
                                })
                                return@aggregation true
                            }
                            val candidateChecks = mutableListOf<AnimeAssociationCandidateCheck>()
                            val verified = possible.mapNotNull { title ->
                                val catalog = earlyCatalogues[title.id to title.url] ?: try {
                                    readSource(if (provider is LanercProvider) 8_000L else 6_000L) {
                                        sources.catalog(provider, title, forceRefresh = force || retryFailed)
                                    }
                                        ?: error("来源目录读取超时，请重试。")
                                } catch (cancel: CancellationException) { throw cancel }
                                catch (failure: Exception) {
                                    candidateChecks += AnimeAssociationCandidateCheck(title, AnimeAssociationPhase.UNAVAILABLE,
                                        (failure as? AnimeSourceException)?.httpStatus?.let { "HTTP $it · 目录读取失败" }
                                            ?: failure.message ?: "目录暂未取得，请重试。")
                                    return@mapNotNull null
                                }
                                if (catalog.title.sourceId != sourceId || catalog.title.id != title.id ||
                                    catalog.title.url != title.url) {
                                    candidateChecks += AnimeAssociationCandidateCheck(title, AnimeAssociationPhase.NEEDS_SELECTION,
                                        "来源返回了不同的作品目录，请重新核对。")
                                    return@mapNotNull null
                                }
                                animeCatalogIdentityIssue(detail, catalog)?.let { reason ->
                                    candidateChecks += AnimeAssociationCandidateCheck(catalog.title,
                                        AnimeAssociationPhase.NEEDS_SELECTION, reason)
                                    return@mapNotNull null
                                }
                                catalog
                            }
                            val unfinished = candidateChecks.any { it.phase == AnimeAssociationPhase.UNAVAILABLE }
                            val catalog = verified.singleOrNull()?.takeUnless { unfinished }
                            if (catalog == null) {
                                val phase = if (unfinished) AnimeAssociationPhase.UNAVAILABLE else AnimeAssociationPhase.NEEDS_SELECTION
                                report(phase, checks = candidateChecks + verified.map {
                                    AnimeAssociationCandidateCheck(it.title, phase,
                                        if (unfinished) "此目录已取得，另一个候选仍在等待核对。"
                                        else "有多个符合身份条件的目录，请核对版本。")
                                })
                                return@aggregation true
                            }
                            if (!current()) return@aggregation false
                            val catalogNotice = catalog.notice
                            if (catalogNotice != null) {
                                report(AnimeAssociationPhase.UNAVAILABLE, checks = candidateChecks +
                                    AnimeAssociationCandidateCheck(catalog.title, AnimeAssociationPhase.UNAVAILABLE, catalogNotice))
                                return@aggregation true
                            }
                            library.editPlaybackAssociation(detail.subject, editBindings = { bindings ->
                                check(current() && sourceId !in library.state.value.entries[detail.subject.id]?.excludedSourceIds.orEmpty()) {
                                    "来源选择已变化，自动关联已停止。"
                                }
                                check(bindings.none { it.catalog.sourceId == sourceId }) { "已有新的来源关联，未覆盖。" }
                                // This records identity only. No stream health or viewing event is fabricated.
                                bindings + AnimeSourceBinding(detail.subject.id, catalog.title.copy(sourceRevision = revision),
                                    preferred = bindings.isEmpty(), automatic = true)
                            })
                            if (!current() || !stillBound(detail.subject.id, catalog.title)) return@aggregation false
                            mutableState.update { state -> state.copy(sourceCatalogs = state.sourceCatalogs.filterNot {
                                it.title.sourceId == sourceId
                            } + catalog, sourceCatalogErrors = state.sourceCatalogErrors - sourceId) }
                            stageCatalogue(catalog)
                            rebuildOptions()
                            // Save the verified work before calculating its episode coverage.
                            // One matcher serves this immutable catalogue off the UI thread.
                            val liveDetail = mutableState.value.detail ?: return@aggregation false
                            val requested = focusFor(liveDetail)
                            val episodeStatus = withContext(Dispatchers.Default) {
                                val matcher = AnimeEpisodeMatcher(liveDetail, catalog)
                                val groups = requested?.let { matcher.groups(it) }.orEmpty()
                                when {
                                    groups.isNotEmpty() -> null
                                    requested == null -> "作品已关联，正在核对统一选集。"
                                    catalog.episodes.none { it.number != null } -> "作品已关联，来源未标集号，本集尚未对应。"
                                    catalog.episodes.any { matcher.matches(requested, it) } ->
                                        "作品已关联，本集线路尚未对应。"
                                    else -> "作品已关联，目录中暂无本集。"
                                }
                            }
                            if (!current() || !stillBound(detail.subject.id, catalog.title)) return@aggregation false
                            val latestDetail = mutableState.value.detail
                            report(AnimeAssociationPhase.LINKED, episodeStatus.takeIf {
                                latestDetail == liveDetail && latestDetail?.let(::focusFor) == requested
                            })
                            true
                        }
                        if (loaded != true) report(AnimeAssociationPhase.UNAVAILABLE, "来源暂未完成，已保留现有目录。")
                    }
                    findCatalogues()
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { report(AnimeAssociationPhase.UNAVAILABLE, failure.message ?: "来源暂不可用，现有目录保留。") }
            }
        }
    }

    fun retrySourceAggregation() {
        mutableState.value.detail?.let { startSourceAggregation(it, force = true) }
    }

    internal fun verifyEpisodeSources(episodeId: String?, retry: Boolean = false, sourceId: String? = null,
        retryFailed: Boolean = false) {
        mutableState.value.detail?.let {
            startSourceAggregation(it, force = retry, retryFailed = retryFailed, episodeId = episodeId, onlySourceId = sourceId)
        }
    }

    private fun optionKey(option: AnimeEpisodeOption): String {
        val episode = option.episode
        val url = if (option.sourceId == "anime-age") animeContentIdentity(episode.url) else episode.url
        return listOf(option.sourceId, option.catalog.id, option.catalog.url, episode.id, episode.group,
            episode.kind, episode.number, episode.edition, episode.playbackData, url,
            discoverySourceRevision(option.sourceId)).joinToString("") { value ->
            val text = value?.toString().orEmpty()
            "${text.length}:$text"
        }
    }

    /** The chooser and automatic recovery share the same exact episode lines.
     * A manual choice is tried first; manual non-number mappings stay exact. */
    internal fun confirmedPlaybackLines(episodeId: String,
        snapshot: AnimeScreenState = mutableState.value): List<AnimeEpisodeOption> {
        val offered = snapshot.episodeOptions[episodeId].orEmpty().filter { it.confirmed }
        val detail = snapshot.detail ?: return emptyList()
        if (detail.episodes.none { it.id == episodeId }) return emptyList()
        return offered.filter { option ->
            sources.provider(option.sourceId) != null && stillBound(detail.subject.id, option.catalog) &&
                snapshot.sourceCatalogs.any { catalog -> catalog.title.sourceId == option.sourceId &&
                    catalog.title.id == option.catalog.id && catalog.title.url == option.catalog.url &&
                    (projectedEpisodeMembers[option.sourceId]?.takeIf { it.first === catalog }?.second
                        ?.contains(option.episode) ?: (option.episode in catalog.episodes)) }
        }.distinctBy(::optionKey)
    }

    internal fun episodeAvailability(episodeId: String,
        snapshot: AnimeScreenState = mutableState.value): AnimeEpisodeAvailability {
        val options = confirmedPlaybackLines(episodeId, snapshot)
        if (options.any { recentPlaybackLineFailure(episodeId, it, snapshot) == null })
            return AnimeEpisodeAvailability.MATCHED
        val airDate = snapshot.detail?.episodes?.firstOrNull { it.id == episodeId }?.airDate
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (options.isEmpty() && airDate != null && airDate >
            Instant.fromEpochMilliseconds(wallTimeMillis()).toLocalDateTime(TimeZone.currentSystemDefault()).date)
            return AnimeEpisodeAvailability.UPCOMING
        if (snapshot.detailLoading || snapshot.sourceAggregationRunning || snapshot.episodeOptionsLoading)
            return AnimeEpisodeAvailability.CHECKING
        return if (options.isEmpty()) AnimeEpisodeAvailability.UNMATCHED else AnimeEpisodeAvailability.RECENTLY_FAILED
    }

    internal fun canAttemptPlaybackLine(episodeId: String, option: AnimeEpisodeOption,
        snapshot: AnimeScreenState = mutableState.value): Boolean {
        val subjectId = snapshot.detail?.subject?.id ?: return false
        val key = playbackHistoryKey(subjectId, episodeId, option) ?: return false
        return currentPlaybackFailure(key)?.unsupported != true
    }

    internal fun hasAutomaticPlaybackLine(episodeId: String): Boolean = automaticPlaybackLines(episodeId).isNotEmpty()

    private fun automaticPlaybackLines(episodeId: String, snapshot: AnimeScreenState = mutableState.value) =
        confirmedPlaybackLines(episodeId, snapshot).filter {
            canAttemptPlaybackLine(episodeId, it, snapshot) && recentPlaybackLineFailure(episodeId, it, snapshot) == null
        }

    internal fun recentPlaybackLineFailure(episodeId: String, option: AnimeEpisodeOption,
        snapshot: AnimeScreenState = mutableState.value): String? {
        val subjectId = snapshot.detail?.subject?.id ?: return null
        val key = playbackHistoryKey(subjectId, episodeId, option) ?: return "线路配置暂不可用"
        return currentPlaybackFailure(key)?.reason
    }

    internal fun samePlaybackLine(left: AnimeEpisodeOption, right: AnimeEpisodeOption): Boolean {
        if (left.sourceId != right.sourceId || left.catalog.id != right.catalog.id ||
            left.catalog.url != right.catalog.url) return false
        val a = left.episode
        val b = right.episode
        if (a.id != b.id || a.kind != b.kind || a.number != b.number || a.group != b.group ||
            a.edition != b.edition || a.playbackData != b.playbackData) return false
        // AGE uses content identity for its native episode ID. A catalog refresh
        // can rotate the signing query of the same media object while we resolve it.
        return a.url == b.url || (left.sourceId == "anime-age" &&
            animeContentIdentity(a.url) == animeContentIdentity(b.url))
    }

    private fun currentPlaybackLine(episodeId: String, expected: AnimeEpisodeOption): AnimeEpisodeOption? {
        val snapshot = mutableState.value
        val detail = snapshot.detail ?: return null
        val current = confirmedPlaybackLines(episodeId, snapshot)
            .singleOrNull { samePlaybackLine(it, expected) } ?: return null
        if (!stillBound(detail.subject.id, current.catalog)) return null
        return current.takeIf { snapshot.sourceCatalogs.any { catalog ->
            catalog.title.sourceId == current.sourceId && catalog.title.id == current.catalog.id &&
                catalog.title.url == current.catalog.url && current.episode in catalog.episodes
        } }
    }

    // Invalid saved routes are candidate failures, never a reason to abort the UI callback
    // or silently substitute a direct/system route while ordering other sources.
    private fun playbackHistoryRoute(sourceId: String): DownloadRouteSnapshot? = runCatching {
        DownloadRoutingStore.activeSourceSnapshot(sourceId, DownloadCategory.ANIME)
    }.getOrNull()

    private fun playbackHistoryKey(subjectId: String, episodeId: String, option: AnimeEpisodeOption) =
        playbackHistoryRoute(option.sourceId)?.let { PlaybackHistoryKey(subjectId, episodeId, optionKey(option), it) }

    internal fun playbackMeasurements(episodeId: String, option: AnimeEpisodeOption): AnimePlaybackMeasurements? =
        mutableState.value.detail?.subject?.id?.let { playbackHistoryKey(it, episodeId, option) }
            ?.let { currentPlaybackMeasurements(it, option) }

    /** A recent confirmed line for each source on this work. All fields come from that line. */
    internal fun workPlaybackMeasurements(snapshot: AnimeScreenState = state.value): Map<String, AnimeWorkPlaybackMeasurements> {
        val detail = snapshot.detail ?: return emptyMap()
        if (detail.subject.id != mutableState.value.detail?.subject?.id || playbackMeasurements.isEmpty()) return emptyMap()
        val episodes = detail.episodes.associateBy { it.id }
        val confirmedByEpisode = mutableMapOf<String, List<AnimeEpisodeOption>>()
        val samples = mutableMapOf<String, AnimeWorkPlaybackMeasurements>()
        val batch = snapshot.sourceMeasurements?.takeIf {
            it.subjectId == detail.subject.id && it.episodeId == null
        }
        val order = compareBy<AnimePlaybackMeasurements> {
            val playedAt = it.playbackObservedAt ?: 0L
            val mediaAt = it.mediaObservedAt ?: 0L
            if (playedAt > mediaAt) true else mediaAt > 0L && it.mediaVerified == true
        }.thenBy { it.playbackObservedAt != null }.thenBy { it.mediaFailure == null }
            .thenBy { it.mediaObservedAt ?: it.addressObservedAt ?: 0L }.thenBy { it.updatedAt }
        for ((key, receipt) in playbackMeasurements) {
            if (key.subjectId != detail.subject.id) continue
            val episode = episodes[key.episodeId] ?: continue
            val measurements = currentPlaybackMeasurements(key) ?: continue
            val sourceId = receipt.option.sourceId
            val progress = batch?.sources?.get(sourceId)
            if (progress != null && (episode.id != progress.episodeId ||
                    progress.lines.none { samePlaybackLine(it, receipt.option) })) continue
            val selected = samples[sourceId]
            if (selected != null && order.compare(measurements, selected.measurements) <= 0) continue
            val option = confirmedByEpisode.getOrPut(episode.id) { confirmedPlaybackLines(episode.id, snapshot) }
                .singleOrNull { samePlaybackLine(it, receipt.option) } ?: continue
            if (playbackHistoryKey(detail.subject.id, episode.id, option) != key) continue
            samples[sourceId] = AnimeWorkPlaybackMeasurements(episode, option, measurements)
        }
        return samples
    }

    private fun currentPlaybackMeasurements(key: PlaybackHistoryKey,
        option: AnimeEpisodeOption? = null): AnimePlaybackMeasurements? {
        val receipt = playbackMeasurements[key] ?: return null
        if (option != null && !samePlaybackLine(receipt.option, option)) return null
        val data = receipt.measurements
        val now = wallTimeMillis()
        val frame = data.firstFrameObservedAt?.takeIf { now - it in 0L until 30 * 60_000L }
        val played = data.playbackObservedAt?.takeIf { now - it in 0L until 30 * 60_000L }
        val duration = data.durationObservedAt?.takeIf { now - it in 0L until 30 * 60_000L }
        val resolved = data.addressObservedAt?.takeIf { now - it in 0L until 5 * 60_000L }
        val media = data.mediaObservedAt?.takeIf { now - it in 0L until 5 * 60_000L &&
            data.mediaPreferredHeight == mutableState.value.settings.preferredSourceHeight }
        if (frame == null && played == null && duration == null && resolved == null && media == null) return null
        return data.copy(updatedAt = maxOf(frame ?: 0L, played ?: 0L, duration ?: 0L, resolved ?: 0L, media ?: 0L),
            width = data.width.takeIf { played != null }, height = data.height.takeIf { played != null },
            firstFrameMs = data.firstFrameMs.takeIf { frame != null }, firstFrameObservedAt = frame,
            measuredBytesPerSecond = data.measuredBytesPerSecond.takeIf { played != null },
            durationMs = data.durationMs.takeIf { duration != null },
            durationAssetId = data.durationAssetId.takeIf { duration != null }, durationObservedAt = duration,
            addressMs = data.addressMs.takeIf { resolved != null }, declaredQuality = data.declaredQuality.takeIf { resolved != null },
            playbackObservedAt = played, addressObservedAt = resolved,
            mediaObservedAt = media, mediaPreferredHeight = data.mediaPreferredHeight.takeIf { media != null },
            mediaHttpStatus = data.mediaHttpStatus.takeIf { media != null },
            mediaVerified = data.mediaVerified.takeIf { media != null },
            mediaFailure = data.mediaFailure.takeIf { media != null },
            mediaQuality = data.mediaQuality.takeIf { media != null },
            mediaHeaderHeight = data.mediaHeaderHeight.takeIf { media != null },
            mediaSampleBytesPerSecond = data.mediaSampleBytesPerSecond.takeIf { media != null },
            mediaSampleMs = data.mediaSampleMs.takeIf { media != null })
    }

    private fun storePlaybackMeasurements(key: PlaybackHistoryKey, option: AnimeEpisodeOption,
        update: (AnimePlaybackMeasurements) -> AnimePlaybackMeasurements) {
        val now = wallTimeMillis()
        val previous = currentPlaybackMeasurements(key, option)
        val measurements = update(previous ?: AnimePlaybackMeasurements(updatedAt = now))
        playbackMeasurements.remove(key)
        playbackMeasurements[key] = PlaybackMeasurementReceipt(option, measurements.copy(updatedAt = now))
        while (playbackMeasurements.size > 256) playbackMeasurements.remove(playbackMeasurements.keys.first())
        mutableState.update { it.copy(playbackMeasurementsRevision = it.playbackMeasurementsRevision + 1) }
    }

    internal fun rankedPlaybackLines(episodeId: String): List<AnimeEpisodeOption> {
        val subjectId = mutableState.value.detail?.subject?.id ?: return emptyList()
        val ranked = fairPlaybackOptions(subjectId, episodeId, automaticPlaybackLines(episodeId))
        val first = preparedOpeningLine(ranked, preparedForEpisode(subjectId, episodeId)) ?: return ranked
        return listOf(first) + ranked.filterNot { it === first }
    }

    private fun preparedForEpisode(subjectId: String, episodeId: String): PreparedPlayback? =
        prepared?.takeIf { it.target.subjectId == subjectId && it.target.episodeId == episodeId && freshPreparation(it) }

    private fun preparedOpeningLine(ranked: List<AnimeEpisodeOption>, ready: PreparedPlayback?): AnimeEpisodeOption? {
        val target = ready?.target ?: return null
        if (target.option.sourceId != target.explicitSourceId && ranked.any { it.sourceId == target.explicitSourceId }) return null
        return ranked.firstOrNull { samePlaybackLine(it, target.option) }
    }

    private suspend fun probePlaybackLine(
        episodeId: String,
        provider: AnimeProvider,
        catalog: AnimeSourceCatalog,
        line: AnimeEpisodeOption,
        revision: String,
        route: DownloadRouteSnapshot,
        onResolved: (elapsedMs: Long, quality: String?, observedAt: Long) -> Unit,
        current: () -> Boolean,
    ): PlaybackProbe? {
        if (!current()) return null
        val preferredHeight = mutableState.value.settings.preferredSourceHeight
        var failure: Exception? = null
        val addresses = matchingOpeningAddresses(episodeId)
        val key = mutableState.value.detail?.subject?.id?.let { playbackHistoryKey(it, episodeId, line) }
        val warm = key?.let { addresses?.requests?.get(it)?.result }?.takeUnless { it.isCancelled }
        suspend fun fetchAddress(): OpeningAddress {
            val start = monotonicTimeMillis()
            val asset = provider.resolve(catalog, line.episode)
            return OpeningAddress(asset, (monotonicTimeMillis() - start).coerceAtLeast(0L), wallTimeMillis())
        }
        val address = try { withTimeoutOrNull(8_000L) {
            val cached = warm?.await()?.takeIf { freshOpeningAddress(it) }
            cached ?: fetchAddress()
        } }
            catch (cancel: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (!current()) return null
                failure = cancel
                null
            } catch (resolveFailure: Exception) { failure = resolveFailure; null }
        if (!current()) return null
        val addressAt = address?.observedAt ?: wallTimeMillis()
        val asset = address?.asset
        if (asset == null) {
            val code = (failure as? AnimeSourceException)?.httpStatus
            val definite = code in setOf(401, 403, 404, 410, 429)
            val reason = when (failure) {
                is TvtFunPlaybackException -> listOfNotNull(code?.let { "HTTP $it" },
                    "${failure.playbackPhase.label}失败").joinToString(" · ")
                null -> "本集取址超时"
                is LanercMediaSniffingUnsupported -> "此线路需要当前平台未支持的嗅探能力"
                is DownloadRouteException -> "本集网络线路暂不可用"
                is LanercCapabilityException -> failure.message
                else -> code?.let { "HTTP $it · 取址失败" } ?: "本集取址未完成，可重新取址"
            }
            return PlaybackProbe(addressAt, null, null,
                AnimeMediaPreflight(addressAt, code, if (definite) false else null, reason), preferredHeight)
        }
        if (asset.sourceId != line.sourceId || asset.sourceEpisodeId != line.episode.id ||
            asset.sourceRevision != revision || asset.route != route || asset.url.isBlank() ||
            asset.expiresAt?.let { it <= addressAt } == true) {
            return PlaybackProbe(addressAt, null, null, AnimeMediaPreflight(addressAt,
                failure = "本集地址身份、网络线路或有效期已变化，请重新取址"), preferredHeight)
        }
        val elapsed = requireNotNull(address).elapsedMs
        val selected = preferredSourceAsset(asset, line.episode, preferredHeight)
        val quality = declaredPlaybackQuality(selected)
        if (mutableState.value.settings.preferredSourceHeight != preferredHeight) return null
        onResolved(elapsed, quality, addressAt)
        val media = try { inspectAnimeMediaBeforePlayback(selected) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { AnimeMediaPreflight(wallTimeMillis(), failure = "本集未取得限量媒体样本") }
        return PlaybackProbe(addressAt, elapsed, quality, media, preferredHeight)
            .takeIf { current() && mutableState.value.settings.preferredSourceHeight == preferredHeight }
    }

    private fun storePlaybackProbe(key: PlaybackHistoryKey, option: AnimeEpisodeOption, probe: PlaybackProbe) {
        val media = probe.media
        storePlaybackMeasurements(key, option) { it.copy(addressMs = probe.addressMs,
            declaredQuality = probe.declaredQuality, addressObservedAt = probe.addressObservedAt,
            mediaObservedAt = media.observedAt, mediaPreferredHeight = probe.preferredHeight,
            mediaHttpStatus = media.httpStatus, mediaVerified = media.verified,
            mediaFailure = media.failure, mediaQuality = media.quality,
            mediaHeaderHeight = media.headerHeight,
            mediaSampleBytesPerSecond = media.sampleBytesPerSecond, mediaSampleMs = media.elapsedMs) }
    }

    /** A source sheet can read bounded headers/manifest data without opening the player.
     * A failed HTTP request is evidence for this exact episode and route, never proof that a whole site is offline. */
    internal fun measureEpisodeSources(episodeId: String, force: Boolean = false, onlyLine: AnimeEpisodeOption? = null) {
        measureSources(episodeId, force, onlyLine)
    }

    internal fun measureWorkSources(force: Boolean = false) {
        measureSources(episodeId = null, force = force)
    }

    private fun measurementPhase(
        mediaVerified: Boolean?, mediaFailure: String?, mediaHttpStatus: Int?, addressMs: Long?,
    ): AnimeSourceMeasurementPhase = when {
        mediaVerified == true && mediaFailure != null -> AnimeSourceMeasurementPhase.MANIFEST_ONLY
        mediaVerified == true -> AnimeSourceMeasurementPhase.COMPLETE
        mediaVerified == false || (mediaHttpStatus ?: 0) >= 400 -> AnimeSourceMeasurementPhase.FAILED
        addressMs != null -> AnimeSourceMeasurementPhase.ADDRESS_ONLY
        else -> AnimeSourceMeasurementPhase.FAILED
    }

    private fun cachedMeasurement(target: SourceMeasurementTarget): Pair<AnimeSourceMeasurementPhase, String?> =
        target.lines.mapNotNull { line ->
            val receipt = playbackMeasurements(target.episodeId, line) ?: return@mapNotNull null
            val playedAt = receipt.playbackObservedAt
            if (playedAt != null && playedAt > (receipt.mediaObservedAt ?: 0L)) {
                AnimeSourceMeasurementPhase.COMPLETE to null
            } else if (receipt.mediaObservedAt != null || receipt.addressObservedAt != null) {
                val phase = measurementPhase(receipt.mediaVerified, receipt.mediaFailure, receipt.mediaHttpStatus, receipt.addressMs)
                phase to receipt.mediaFailure.takeUnless { phase == AnimeSourceMeasurementPhase.COMPLETE }
            } else null
        }.maxByOrNull { it.first.evidencePriority }
            ?: (AnimeSourceMeasurementPhase.UNAVAILABLE to "尚未取得本次线路结果。")

    private fun measureSources(episodeId: String?, force: Boolean, onlyLine: AnimeEpisodeOption? = null) {
        val snapshot = mutableState.value
        val detail = snapshot.detail ?: return
        if (sourceMeasurementsJob?.isActive == true) {
            if (snapshot.sourceMeasurements?.episodeId == episodeId &&
                snapshot.sourceMeasurements?.subjectId == detail.subject.id && onlyLine == null &&
                (!force || sourceMeasurementsUserRequested)) {
                sourceMeasurementsEnqueue?.invoke()
                return
            }
            cancelEpisodeSourceMeasurements()
        }
        if (!force && (playbackBusy() || snapshot.autoMatching)) return
        // A passive sample must not replace the completed report of a manual batch.
        val lastBatch = snapshot.sourceMeasurements
        if (!force && lastBatch != null && lastBatch.manual && !lastBatch.running &&
            lastBatch.subjectId == detail.subject.id && lastBatch.episodeId == episodeId) return
        val detailOwner = detailGeneration
        val generation = ++sourceMeasurementsGeneration
        val checkEveryLine = force && episodeId != null
        sourceMeasurementsUserRequested = force
        mutableState.update { it.copy(sourceMeasurements = AnimeSourceMeasurementBatch(
            detail.subject.id, episodeId, running = true, sources = emptyMap(), manual = force)) }
        sourceMeasurementsJob = scope.launch(start = CoroutineStart.LAZY) {
            fun current() = currentDetail(detailOwner, detail.subject.id) && sourceMeasurementsGeneration == generation
            fun report(sourceId: String, phase: AnimeSourceMeasurementPhase, reason: String? = null,
                lines: List<AnimeEpisodeOption>? = null) {
                if (!current()) return
                mutableState.update { state ->
                    val batch = state.sourceMeasurements ?: return@update state
                    val previous = batch.sources[sourceId] ?: return@update state
                    state.copy(sourceMeasurements = batch.copy(sources = batch.sources +
                        (sourceId to previous.copy(phase = phase, reason = reason, lines = lines ?: previous.lines))))
                }
            }
            fun reportLine(sourceId: String, option: AnimeEpisodeOption, measurement: AnimeSourceLineMeasurement) {
                if (!current()) return
                mutableState.update { state ->
                    val batch = state.sourceMeasurements ?: return@update state
                    val source = batch.sources[sourceId] ?: return@update state
                    if (option !in source.lineMeasurements) return@update state
                    val lines = source.lineMeasurements + (option to measurement)
                    val results = lines.values
                    val outcome = results.firstOrNull { it.phase == AnimeSourceMeasurementPhase.RUNNING }
                        ?: results.firstOrNull { it.phase == AnimeSourceMeasurementPhase.QUEUED }
                        ?: results.maxByOrNull { it.phase.evidencePriority }
                        ?: return@update state
                    val updated = source.copy(phase = outcome.phase, reason = outcome.reason, lineMeasurements = lines)
                    state.copy(sourceMeasurements = batch.copy(sources = batch.sources + (sourceId to updated)))
                }
            }
            suspend fun measureLine(target: SourceMeasurementTarget, option: AnimeEpisodeOption): Pair<PlaybackProbe?, String?> {
                val line = currentPlaybackLine(target.episodeId, option) ?: return null to "对应线路已变化，请重新测速。"
                val provider = sources.provider(line.sourceId) ?: return null to "来源已停用或移除。"
                val revision = discoverySourceRevision(line.sourceId) ?: return null to "来源版本已变化，请重新测速。"
                val key = playbackHistoryKey(detail.subject.id, target.episodeId, line) ?: return null to "网络线路配置不可用。"
                val catalog = mutableState.value.sourceCatalogs.firstOrNull {
                    it.title.sourceId == line.sourceId && it.title.id == line.catalog.id && it.title.url == line.catalog.url
                } ?: return null to "来源目录已变化，请重新测速。"
                val probe = probePlaybackLine(target.episodeId, provider, catalog, line, revision, key.route, onResolved = { elapsed, quality, at ->
                    storePlaybackMeasurements(key, line) { it.copy(addressMs = elapsed,
                        declaredQuality = quality, addressObservedAt = at) }
                }) {
                    current() && discoverySourceRevision(line.sourceId) == revision &&
                        playbackHistoryRoute(line.sourceId) == key.route && currentPlaybackLine(target.episodeId, line) != null
                } ?: return null to "线路或画质设置已变化，请重新测速。"
                storePlaybackProbe(key, line, probe)
                return probe to null
            }
            try {
                coroutineScope {
                    val permits = Semaphore(if (force) ANIME_SOURCE_PARALLELISM else 1)
                    val scheduled = mutableMapOf<String, MutableSet<String>>()
                    fun enqueue() {
                        if (!current()) return
                        val latest = mutableState.value
                        if (!sourceMeasurementsUserRequested && (playbackBusy() || latest.autoMatching)) return
                        val sourceIds = if (onlyLine != null) listOf(onlyLine.sourceId) else latest.sourceBindings
                            .filter { it.subjectId == detail.subject.id }.map { it.catalog.sourceId }.distinct()
                        // Work samples remain source-specific; an episode request never borrows another episode.
                        val targets = linkedMapOf<String, SourceMeasurementTarget>()
                        val episodes = if (episodeId != null) latest.detail?.episodes.orEmpty().filter { it.id == episodeId }
                            else latest.detail?.episodes.orEmpty().sortedBy { it.kind != AnimeEpisodeKind.MAIN }
                        for (episode in episodes) {
                            if (targets.keys.containsAll(sourceIds)) break
                            val options = confirmedPlaybackLines(episode.id, latest).filter {
                                it.sourceId in sourceIds && it.sourceId !in targets &&
                                    (onlyLine == null || samePlaybackLine(it, onlyLine))
                            }
                            fairPlaybackOptions(detail.subject.id, episode.id, options).groupBy { it.sourceId }.forEach { (id, lines) ->
                                targets[id] = SourceMeasurementTarget(episode.id, when {
                                    checkEveryLine -> lines
                                    force -> lines.take(2)
                                    else -> lines.take(1)
                                })
                            }
                        }
                        val pending = targets.filterKeys { checkEveryLine || it !in scheduled }.mapValues { (id, target) ->
                            val cachedSuccess = !sourceMeasurementsUserRequested && target.lines.any {
                                playbackMeasurements(target.episodeId, it)?.let { sample ->
                                    (sample.playbackObservedAt ?: 0L) > (sample.mediaObservedAt ?: 0L) ||
                                        sample.mediaVerified == true && sample.mediaFailure == null &&
                                        (sample.mediaQuality != null || sample.mediaHeaderHeight != null || sample.mediaSampleBytesPerSecond != null)
                                } == true
                            }
                            target.copy(lines = if (cachedSuccess) emptyList() else target.lines.filter {
                                optionKey(it) !in scheduled[id].orEmpty() &&
                                    (sourceMeasurementsUserRequested ||
                                        playbackMeasurements(target.episodeId, it)?.mediaObservedAt == null)
                            })
                        }.filterValues { it.lines.isNotEmpty() }
                        val previous = latest.sourceMeasurements?.sources.orEmpty()
                        val progress = sourceIds.associateWith { id ->
                            val newlyQueued = pending[id]?.takeIf { checkEveryLine }
                            if (newlyQueued != null) {
                                val prior = previous[id]
                                val lines = prior?.lineMeasurements.orEmpty() + newlyQueued.lines.associateWith {
                                    AnimeSourceLineMeasurement(AnimeSourceMeasurementPhase.QUEUED)
                                }
                                return@associateWith AnimeSourceMeasurementProgress(newlyQueued.episodeId,
                                    prior?.phase?.takeIf { it == AnimeSourceMeasurementPhase.RUNNING }
                                        ?: AnimeSourceMeasurementPhase.QUEUED,
                                    lines = lines.keys.toList(), lineMeasurements = lines)
                            }
                            previous[id]?.takeIf { id in scheduled } ?: run {
                                val target = targets[id]
                                val extension = latest.sourceExtensions.firstOrNull { it.id == id }
                                val (phase, reason) = when {
                                    id in pending -> AnimeSourceMeasurementPhase.QUEUED to null
                                    target != null -> cachedMeasurement(target)
                                    extension != null && (!extension.installed || !extension.enabled) ->
                                        AnimeSourceMeasurementPhase.UNAVAILABLE to "来源已停用，启用后可探测。"
                                    latest.sourceCatalogErrors[id] != null ->
                                        AnimeSourceMeasurementPhase.UNAVAILABLE to "目录读取失败，展开来源可重试。"
                                    latest.sourceCatalogs.none { it.title.sourceId == id } ->
                                        AnimeSourceMeasurementPhase.WAITING_CATALOG to "来源目录就绪后自动探测。"
                                    else -> AnimeSourceMeasurementPhase.NO_EPISODE to
                                        if (episodeId == null) "该来源还没有已对应的剧集。" else "本集还没有已对应的线路。"
                                }
                                AnimeSourceMeasurementProgress(target?.episodeId, phase, reason, target?.lines.orEmpty())
                            }
                        }
                        mutableState.update { it.copy(sourceMeasurements = AnimeSourceMeasurementBatch(
                            detail.subject.id, episodeId, running = true, sources = progress, manual = force)) }
                        pending.forEach { (sourceId, target) ->
                            scheduled.getOrPut(sourceId) { mutableSetOf() }.addAll(target.lines.map(::optionKey))
                            launch {
                                val measured = mutableListOf<AnimeEpisodeOption>()
                                var best = AnimeSourceMeasurementPhase.UNAVAILABLE
                                var outcomeReason: String? = null
                                for (option in target.lines) {
                                    if (!current()) return@launch
                                    // Release between lines so queued sources get their first sample before a fallback.
                                    val (probe, reason) = permits.withPermit {
                                        if (!current()) return@withPermit null to "此线路检查已停止。"
                                        if (checkEveryLine) reportLine(sourceId, option,
                                            AnimeSourceLineMeasurement(AnimeSourceMeasurementPhase.RUNNING))
                                        else report(sourceId, AnimeSourceMeasurementPhase.RUNNING)
                                        try { measureLine(target, option) }
                                        catch (cancel: CancellationException) {
                                            currentCoroutineContext().ensureActive()
                                            null to "此线路检查已中止，可重新测速。"
                                        } catch (_: Exception) { null to "此线路检查未完成，可重新测速。" }
                                    }
                                    if (checkEveryLine) {
                                        val measurement = if (probe != null) {
                                            val sample = probe.media
                                            AnimeSourceLineMeasurement(
                                                measurementPhase(sample.verified, sample.failure, sample.httpStatus, probe.addressMs),
                                                sample.failure,
                                            )
                                        } else AnimeSourceLineMeasurement(AnimeSourceMeasurementPhase.UNAVAILABLE, reason)
                                        reportLine(sourceId, option, measurement)
                                    } else if (probe != null) {
                                        measured += option
                                        val sample = probe.media
                                        val phase = measurementPhase(sample.verified, sample.failure, sample.httpStatus, probe.addressMs)
                                        if (phase.evidencePriority > best.evidencePriority) {
                                            best = phase
                                            outcomeReason = sample.failure.takeUnless { phase == AnimeSourceMeasurementPhase.COMPLETE }
                                        }
                                        if (phase == AnimeSourceMeasurementPhase.COMPLETE &&
                                            (sample.quality != null || sample.headerHeight != null || sample.sampleBytesPerSecond != null)) break
                                    } else if (best == AnimeSourceMeasurementPhase.UNAVAILABLE) {
                                        outcomeReason = reason
                                    }
                                }
                                if (!checkEveryLine) report(sourceId, best, outcomeReason, lines = measured.takeIf { it.isNotEmpty() })
                            }
                        }
                    }
                    sourceMeasurementsEnqueue = ::enqueue
                    enqueue()
                    // A manual click also owns sources that finish associating during this batch.
                    if (sourceMeasurementsUserRequested && (mutableState.value.sourceAggregationRunning ||
                            mutableState.value.episodeOptionsLoading)) {
                        mutableState.first { !current() || (!it.sourceAggregationRunning && !it.episodeOptionsLoading) }
                        enqueue()
                    }
                }
            } finally {
                if (current()) {
                    sourceMeasurementsJob = null
                    sourceMeasurementsEnqueue = null
                    sourceMeasurementsUserRequested = false
                    mutableState.update { it.copy(sourceMeasurements = it.sourceMeasurements?.copy(running = false)) }
                }
            }
        }
        sourceMeasurementsJob?.start()
    }

    internal fun cancelEpisodeSourceMeasurements(clearProgress: Boolean = false) {
        val job = sourceMeasurementsJob
        if (job == null && !clearProgress) return
        ++sourceMeasurementsGeneration
        sourceMeasurementsEnqueue = null
        sourceMeasurementsUserRequested = false
        sourceMeasurementsJob = null
        job?.cancel()
        mutableState.update { state -> state.copy(sourceMeasurements = if (clearProgress) null else
            state.sourceMeasurements?.let { batch -> batch.copy(running = false,
                sources = batch.sources.mapValues { (_, progress) ->
                    if (progress.phase.checking)
                        progress.copy(phase = AnimeSourceMeasurementPhase.CANCELLED, reason = "已停止，可重新测速。",
                            lineMeasurements = progress.lineMeasurements.mapValues { (_, line) ->
                                if (line.phase.checking) AnimeSourceLineMeasurement(AnimeSourceMeasurementPhase.CANCELLED)
                                else line
                            })
                    else progress
                }) }) }
    }

    private fun declaredPlaybackQuality(asset: AnimePlaybackAsset): String? = asset.resolutions
        .singleOrNull { it.url == asset.url }?.label?.let { label ->
            Regex("(?i)\\b(2160p|1440p|1080p|720p|576p|540p|480p|360p|4k)\\b").find(label)?.value?.lowercase()
        }

    private fun currentPlaybackFailure(key: PlaybackHistoryKey): AnimePlaybackFailure? {
        val receipt = playbackFailures[key] ?: return null
        val validFor = when {
            receipt.failure.unsupported -> 30 * 60_000L
            listOf("HTTP 401", "HTTP 403", "HTTP 404", "HTTP 410").any { it in receipt.failure.reason } -> 5 * 60_000L
            else -> 60_000L
        }
        return receipt.failure.takeIf { wallTimeMillis() - receipt.observedAt in 0L until validFor }
    }

    private fun rememberPlaybackFailure(key: PlaybackHistoryKey, failure: AnimePlaybackFailure) {
        playbackFailures.remove(key)
        playbackFailures[key] = PlaybackFailureReceipt(failure, wallTimeMillis())
        while (playbackFailures.size > 256) playbackFailures.remove(playbackFailures.keys.first())
    }

    private fun rememberPlaybackAttempt(key: PlaybackHistoryKey, sourceId: String) {
        val sequence = ++playbackAttemptSequence
        playbackAttemptOrder.remove(key)
        playbackAttemptOrder[key] = sequence
        val sourceKey = key.copy(candidate = sourceId)
        playbackSourceAttemptOrder.remove(sourceKey)
        playbackSourceAttemptOrder[sourceKey] = sequence
        while (playbackAttemptOrder.size > 256) playbackAttemptOrder.remove(playbackAttemptOrder.keys.first())
        while (playbackSourceAttemptOrder.size > 256) playbackSourceAttemptOrder.remove(playbackSourceAttemptOrder.keys.first())
    }

    private fun rememberWorkingSource(owner: ActivePlayback, observedAt: Long) {
        val sourceId = owner.option.sourceId
        val revision = discoverySourceRevision(sourceId) ?: return
        if (owner.localMedia != null || owner.asset.sourceRevision != revision ||
            owner.asset.route != playbackHistoryRoute(sourceId)) return
        owner.sourceReceiptSaved = true
        val subjectId = owner.detail.subject.id
        recentPlaybackSources.remove(subjectId)
        val option = owner.option
        playbackHistoryKey(subjectId, owner.episode.id, option)?.let(playbackFailures::remove)
        recentPlaybackSources[subjectId] = PlaybackSourceReceipt(sourceId, revision, owner.asset.route, observedAt,
            option.catalog.id, option.catalog.url, option.episode.group, option.episode.edition)
        if (recentPlaybackSources.size > 128) recentPlaybackSources.remove(recentPlaybackSources.keys.first())
    }

    private fun observePlaybackMeasurements(owner: ActivePlayback, snapshot: AnimePlayerState, now: Long, stableFor: Long) {
        if (owner.localMedia != null || snapshot.hasVideoTracks == false) return
        val firstFrame = snapshot.firstFrameAtMillis?.let { (it - owner.openedAt).takeIf { elapsed -> elapsed >= 0L } }
        val recordFirstFrame = firstFrame != null && !owner.firstFrameMeasurementSaved && owner.explicitResolutionId == null
        val initialSample = now - owner.openedAt in 0L until 30_000L
        val sampled = snapshot.downloadBytesPerSecond.takeIf { initialSample && it > 0 }
        val speedImproved = sampled != null && owner.measuredDownloadSpeed == null
        if (speedImproved) owner.measuredDownloadSpeed = sampled
        val width = snapshot.videoWidth?.takeIf { snapshot.firstFrameRendered == true && it > 0 }
        val height = snapshot.videoHeight?.takeIf { snapshot.firstFrameRendered == true && it > 0 }
        val size = if (width != null && height != null) width to height else null
        val sizeChanged = size != null && size != owner.measuredVideoSize
        val recordSteady = owner.explicitResolutionId == null && stableFor >= 2_000L &&
            (sizeChanged || initialSample && (!owner.sourceReceiptSaved || speedImproved))
        val duration = snapshot.durationMs.takeIf { it > 0L && owner.playbackStarted &&
            snapshot.phase == AnimePlayerPhase.READY }
        val recordDuration = duration != null && duration != owner.measuredDurationMs
        if (!recordFirstFrame && !recordSteady && !recordDuration) return
        if (owner.asset.sourceRevision != discoverySourceRevision(owner.option.sourceId)) return
        val key = playbackHistoryKey(owner.detail.subject.id, owner.episode.id, owner.option)
            ?.takeIf { it.route == owner.asset.route } ?: return
        if (currentPlaybackLine(owner.episode.id, owner.option) == null) return
        if (recordFirstFrame) owner.firstFrameMeasurementSaved = true
        if (recordSteady && size != null) owner.measuredVideoSize = size
        if (recordDuration) owner.measuredDurationMs = duration
        val observedAt = wallTimeMillis()
        storePlaybackMeasurements(key, owner.option) {
            it.copy(width = size?.first?.takeIf { recordSteady } ?: it.width,
                height = size?.second?.takeIf { recordSteady } ?: it.height,
                firstFrameMs = if (recordFirstFrame) firstFrame else it.firstFrameMs,
                firstFrameObservedAt = if (recordFirstFrame) observedAt else it.firstFrameObservedAt,
                measuredBytesPerSecond = if (recordSteady) owner.measuredDownloadSpeed else it.measuredBytesPerSecond,
                playbackObservedAt = if (recordSteady) observedAt else it.playbackObservedAt,
                durationMs = if (recordDuration) duration else it.durationMs,
                durationAssetId = if (recordDuration) owner.asset.id else it.durationAssetId,
                durationObservedAt = if (recordDuration) observedAt else it.durationObservedAt)
        }
    }

    /** Try each source before another route from it. Recent failures go last, and retries
     * continue with least-recently attempted sources/options instead of repeating a prefix.
     * Within AGE, known dynamic-only wrappers follow routes this resolver can already read. */
    private fun fairPlaybackOptions(subjectId: String, episodeId: String,
        options: List<AnimeEpisodeOption>, preferContinuity: Boolean = true): List<AnimeEpisodeOption> {
        val preferred = mutableState.value.preferredSourceId.takeIf { preferContinuity }
        val explicit = if (preferContinuity) explicitPlaybackSource(subjectId) else null
        val recent = recentPlaybackSources[subjectId]?.takeIf { preferContinuity &&
            monotonicTimeMillis() - it.observedAt in 0L until 30 * 60_000L &&
            it.route == playbackHistoryRoute(it.sourceId) && it.revision == discoverySourceRevision(it.sourceId) }
        val resumed = if (preferContinuity) library.progressFor(subjectId, episodeId)?.takeIf {
            !it.completed && it.localDownloadId == null
        }?.sourceId else null
        fun priority(sourceId: String) = when (sourceId) {
            preferred -> 0
            recent?.sourceId -> 1
            resumed -> 2
            else -> 3
        }
        val historyKeys = options.associateWith { playbackHistoryKey(subjectId, episodeId, it) }
        val samples = options.associateWith { option ->
            historyKeys[option]?.let { currentPlaybackMeasurements(it, option) }
        }
        fun cooling(option: AnimeEpisodeOption): Boolean {
            val sample = samples[option]
            return historyKeys[option]?.let { currentPlaybackFailure(it) != null } == true ||
                sample?.mediaVerified == false && maxOf(sample.playbackObservedAt ?: 0L, sample.firstFrameObservedAt ?: 0L) <
                    (sample.mediaObservedAt ?: 0L)
        }
        val order = compareByDescending<AnimeEpisodeOption> { it.sourceId == explicit }.thenBy { option ->
            val sample = samples[option]
            when {
                sample?.playbackObservedAt != null -> 0
                sample?.firstFrameMs != null -> 1
                sample?.mediaVerified == true && sample.mediaFailure == null -> 2
                sample?.addressMs != null && sample.mediaVerified != false -> 3
                sample?.mediaVerified == false -> 5
                else -> 4
            }
        }.thenBy { samples[it]?.firstFrameMs ?: Long.MAX_VALUE }
            .thenByDescending { samples[it]?.measuredBytesPerSecond ?: 0L }
            .thenBy { samples[it]?.addressMs ?: Long.MAX_VALUE }
            .thenByDescending { samples[it]?.mediaSampleBytesPerSecond ?: 0L }
            .thenByDescending { samples[it]?.height ?: 0 }
            .thenBy { priority(it.sourceId) }
            .thenBy { recent?.matches(it) != true }
            .thenBy { dev.mangaflow.anime.sources.animePlaybackParserPriority(it.sourceId, it.episode) }
            .thenBy { historyKeys[it]?.copy(candidate = it.sourceId)?.let(playbackSourceAttemptOrder::get) ?: Long.MIN_VALUE }
            .thenBy { historyKeys[it]?.let(playbackAttemptOrder::get) ?: Long.MIN_VALUE }
        return listOf(false, true).flatMap { cooldown ->
            val buckets = options.filter { cooling(it) == cooldown }.groupBy { it.sourceId }.values
                .map { it.sortedWith(order) }.sortedWith { a, b -> order.compare(a.first(), b.first()) }
            val result = mutableListOf<AnimeEpisodeOption>()
            repeat(buckets.maxOfOrNull { it.size } ?: 0) { index ->
                buckets.forEach { rows -> rows.getOrNull(index)?.let(result::add) }
            }
            result
        }
    }

    /** Wait for progressive discovery, never cancel the other sources when one can start. */
    private fun autoAssociateAndPlay(episodeId: String, attempted: Set<String> = emptySet(),
        lastFailure: AnimePlaybackFailure? = null) {
        val detail = mutableState.value.detail ?: return
        val episode = detail.episodes.firstOrNull { it.id == episodeId } ?: return
        clearPreparation()
        val owner = detailGeneration
        val generation = autoPlayGeneration
        ++resolveGeneration
        resolveJob?.cancel()
        pending = null
        resumeRequest = null
        mutableState.update { it.copy(resolving = true, autoMatching = true,
            pendingPlayback = null, playbackTarget = AnimePlaybackTarget(episode)) }
        autoPlayJob = scope.launch {
            try {
                library.awaitLoaded(); sources.extensions.ready.await()
                if (generation != autoPlayGeneration || !currentDetail(owner, detail.subject.id)) return@launch
                fun nextLineAvailable(snapshot: AnimeScreenState) =
                    automaticPlaybackLines(episodeId, snapshot).any { optionKey(it) !in attempted }
                suspend fun awaitLines(): AnimeScreenState? {
                    val remaining = autoPlaybackDeadline - monotonicTimeMillis()
                    if (remaining <= 0L) return null
                    return withTimeoutOrNull(remaining) {
                        state.first { nextLineAvailable(it) || (!it.sourceAggregationRunning && !it.episodeOptionsLoading) }
                    }
                }
                startSourceAggregation(detail, episodeId = episodeId)
                var ready = awaitLines()
                if (generation != autoPlayGeneration || !currentDetail(owner, detail.subject.id)) return@launch
                if (ready != null && !nextLineAvailable(ready) && canRetryPlaybackDiscovery(ready)) {
                    playbackDiscoveryRetried = true
                    startSourceAggregation(detail, retryFailed = true, episodeId = episodeId)
                    ready = awaitLines()
                }
                if (generation != autoPlayGeneration || !currentDetail(owner, detail.subject.id)) return@launch
                if (ready != null && nextLineAvailable(ready)) {
                    resolveAndPlay(episodeId, attempted = attempted)
                } else {
                    val reason = when {
                        ready == null -> "本轮查找已到时限，可重试本集或查看线路。"
                        hasRetryableAssociation(ready) -> "本集有来源尚未完成核对，可在来源面板查看并重试。"
                        else -> "暂未找到其他可确认的本集线路。"
                    }
                    failPlaybackTarget(lastFailure?.description ?: reason,
                        recoveryStopReason = reason.takeIf { lastFailure != null }, failure = lastFailure)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (generation == autoPlayGeneration && currentDetail(owner, detail.subject.id)) {
                    failPlaybackTarget(failure.message ?: "未能匹配本集片源，请重试。")
                }
            }
        }
    }

    private fun resolveAndPlay(episodeId: String, option: AnimeEpisodeOption? = null,
        attempted: Set<String> = emptySet(), startupRefresh: ActivePlayback? = null) {
        val detail = mutableState.value.detail ?: return
        val episode = detail.episodes.firstOrNull { it.id == episodeId } ?: return
        if (!sourceMeasurementsUserRequested) cancelEpisodeSourceMeasurements()
        val offered = automaticPlaybackLines(episodeId).filterNot { optionKey(it) in attempted }
        val options = fairPlaybackOptions(detail.subject.id, episodeId, offered,
            preferContinuity = attempted.isEmpty())
        val settings = mutableState.value.settings
        val preferredHeight = settings.preferredSourceHeight
        val automaticIntent = option == null || startupRefresh?.automaticIntent == true
        val manualOption = if (automaticIntent) null else option
        val canFallback = settings.automaticSourceRecovery && settings.automaticRecoveryAttempts > 0
        val bounded = option == null || startupRefresh != null || canFallback
        val refreshSnapshot = startupRefresh?.let { owner ->
            playerInstance?.state?.value?.takeIf { belongsTo(owner, it) }
        }
        val refreshResolutionId = startupRefresh?.explicitResolutionId
        val ready = if (startupRefresh == null) preparedForEpisode(detail.subject.id, episodeId) else null
        val selected = if (option == null) preparedOpeningLine(options, ready) ?: options.firstOrNull()
            else currentPlaybackLine(episodeId, option)?.takeIf { canAttemptPlaybackLine(episodeId, it) }
        if (selected == null) {
            if (manualOption != null && startupRefresh == null &&
                keepCurrentEpisodeAfterFailedSwitch(detail.subject.id, episodeId)) return
            if (canFallback) {
                autoAssociateAndPlay(episodeId, attempted = attempted, lastFailure = startupRefresh?.let { previous ->
                    refreshSnapshot?.error?.let {
                        AnimePlaybackFailure(previous.option.sourceId, previous.option.sourceName, "播放视频", it)
                    }
                })
                return
            }
            cancelPlaybackResolution()
            mutableState.update { it.copy(playbackTarget = AnimePlaybackTarget(episode, manualOption,
                error = "本集线路尚未确认或已变化。")) }
            return
        }
        resumeRequest = null
        val available = ready?.takeIf { samePlaybackLine(it.target.option, selected) }
        clearPreparation(keepDecoder = available)
        val owner = detailGeneration
        val addresses = matchingOpeningAddresses(episodeId) ?: if (canFallback) {
                releaseOpeningAddresses()
                OpeningAddresses(owner, detail.subject.id, episodeId, preferredHeight, library.state.value.restoreEpoch, scope)
                    .also { openingAddresses = it }
        } else {
            releaseOpeningAddresses()
            null
        }
        addresses?.expiry?.cancel()
        addresses?.expiry = null
        val generation = ++resolveGeneration
        resolveJob?.cancel()
        val selectedKey = playbackHistoryKey(detail.subject.id, episodeId, selected)
        addresses?.requests?.entries?.removeAll { (key, request) ->
            (key != selectedKey && !request.result.isCompleted).also { if (it) request.result.cancel() }
        }
        pending = null
        resolvingSourceId = selected.sourceId
        mutableState.update { it.copy(resolving = true, autoMatching = false, pendingPlayback = null,
            playbackTarget = AnimePlaybackTarget(episode, manualOption, selected.sourceName)) }
        resolveJob = scope.launch {
            var resolutionCompleted = false
            var manualFailed = false
            var preparedAdopted = false
            val tried = attempted.toMutableSet()
            val rejected = mutableSetOf<String>()
            try {
                library.awaitLoaded()
                fun current() = generation == resolveGeneration && currentDetail(owner, detail.subject.id)
                var raceOpen = true
                fun currentAttempt() = raceOpen && current()
                fun recoveryEnabled() = library.state.value.settings.let {
                    it.automaticSourceRecovery && it.automaticRecoveryAttempts > 0
                }
                suspend fun attempt(candidate: AnimeEpisodeOption): Result<ResolvedPlaybackOption> {
                    var admissionKey: PlaybackHistoryKey? = null
                    var admissionStillCurrent: (() -> Boolean)? = null
                    val key = optionKey(candidate)
                    val refreshing = startupRefresh != null && samePlaybackLine(candidate, startupRefresh.option)
                    fun refreshAllowed() = when {
                        refreshing -> library.state.value.settings.automaticRecoveryAttempts > 0
                        candidate != selected || attempted.isNotEmpty() || startupRefresh != null -> recoveryEnabled()
                        else -> true
                    }
                    fun checkRefreshPermission() {
                        if (!refreshAllowed()) throw AnimeSourceException(candidate.sourceId, AnimeSourceStage.RESOLVE,
                            "已关闭自动恢复，可手动重试本集。")
                    }
                    suspend fun resolveOption(): ResolvedPlaybackOption {
                        check(currentAttempt()) { "播放请求已变化。" }
                        checkRefreshPermission()
                        val line = currentPlaybackLine(episodeId, candidate)
                            ?: error("本集来源关联已变化，请重新选集。")
                        val revision = sourceRevision(candidate.sourceId)
                        val discoveryRevision = discoverySourceRevision(candidate.sourceId)
                            ?: error("这个来源当前不可用。")
                        val catalog = mutableState.value.sourceCatalogs.firstOrNull {
                            it.title.sourceId == candidate.sourceId && it.title.id == candidate.catalog.id && it.title.url == candidate.catalog.url
                        } ?: error("来源目录已变化，请重新选集。")
                        val provider = sources.provider(candidate.sourceId) ?: error("这个来源当前不可用。")
                        check(!bounded || monotonicTimeMillis() < autoPlaybackDeadline) { "本轮播放准备已到时限，请重试。" }
                        // Only admitted attempts count; exhausted-budget candidates remain untried.
                        val historyKey = playbackHistoryKey(detail.subject.id, episodeId, candidate)
                            ?: error("此来源的网络线路不可用，请检查来源设置。")
                        if (refreshing && startupRefresh != null && (active !== startupRefresh || startupRefresh.sourceRevision != revision ||
                                startupRefresh.asset.sourceRevision != discoveryRevision || startupRefresh.asset.route != historyKey.route)) {
                            throw DownloadRouteException("原播放线路或来源已变化，请手动重试。")
                        }
                        admissionKey = historyKey
                        admissionStillCurrent = {
                            currentAttempt() && refreshAllowed() &&
                                mutableState.value.settings.preferredSourceHeight == preferredHeight &&
                                revision == sourceRevision(candidate.sourceId) &&
                                discoveryRevision == discoverySourceRevision(candidate.sourceId) &&
                                historyKey.route == playbackHistoryRoute(candidate.sourceId) &&
                                currentPlaybackLine(episodeId, line) != null
                        }
                        val preparedAsset = available?.takeIf { samePlaybackLine(it.target.option, line) &&
                            monotonicTimeMillis() < it.validUntil && it.asset.expiresAt?.let { expiry -> expiry > wallTimeMillis() } != false &&
                            it.target.revision == discoveryRevision && it.target.route == historyKey.route &&
                            it.target.explicitSourceId == explicitPlaybackSource(detail.subject.id) }?.asset
                        rememberPlaybackAttempt(historyKey, candidate.sourceId)
                        suspend fun fetchAddress(): OpeningAddress {
                            val start = monotonicTimeMillis()
                            val result = provider.resolve(catalog, line.episode)
                            return OpeningAddress(result, (monotonicTimeMillis() - start).coerceAtLeast(0L), wallTimeMillis())
                        }
                        val address = when {
                            preparedAsset != null -> null
                            addresses == null -> fetchAddress()
                            refreshing -> addresses.permits.withPermit { fetchAddress() }
                            else -> readOpeningAddress(addresses, historyKey, candidate.sourceId, ::fetchAddress)
                        }
                        val resolved = (preparedAsset ?: requireNotNull(address).asset).copy(
                            episodeId = episode.id, title = detail.subject.title + " · " + episode.title)
                        checkRefreshPermission()
                        check(resolved.sourceId == line.sourceId && resolved.sourceEpisodeId == line.episode.id) {
                            "来源返回的剧集身份不一致，请重新读取目录。"
                        }
                        check(resolved.sourceRevision == discoveryRevision &&
                            discoveryRevision == discoverySourceRevision(candidate.sourceId) &&
                            revision == sourceRevision(candidate.sourceId)) {
                            "来源已更新，请重新解析。"
                        }
                        check(resolved.expiresAt == null || resolved.expiresAt > wallTimeMillis()) { "播放地址已过期，请重试解析。" }
                        check(currentAttempt()) { "播放请求已变化。" }
                        check(resolved.route == historyKey.route && resolved.route == playbackHistoryRoute(candidate.sourceId)) {
                            "本集网络线路已变化，请重试。"
                        }
                        val confirmed = currentPlaybackLine(episodeId, line)
                            ?: error("本集来源关联已变化，请重新选集。")
                        val asset = if (refreshing && refreshResolutionId != null) {
                            val resolution = resolved.resolutions.singleOrNull { it.id == refreshResolutionId }
                                ?: throw AnimeSourceException(candidate.sourceId, AnimeSourceStage.RESOLVE,
                                    "重新解析后未找到已选画质，请手动重试。")
                            assetWithResolution(resolved, confirmed.episode, resolution)
                        } else if (refreshing && startupRefresh != null) {
                            refreshSourceAsset(resolved, confirmed.episode, preferredHeight, startupRefresh.asset)
                        } else preferredSourceAsset(resolved, confirmed.episode, preferredHeight)
                        if (address != null) {
                            storePlaybackMeasurements(historyKey, confirmed) { it.copy(addressMs = address.elapsedMs,
                                declaredQuality = declaredPlaybackQuality(resolved), addressObservedAt = address.observedAt) }
                        }
                        check(admissionStillCurrent?.invoke() == true) { "播放请求、画质或来源已变化。" }
                        return ResolvedPlaybackOption(confirmed, asset, revision)
                    }
                    suspend fun resolveOnCurrentRoute(): ResolvedPlaybackOption {
                        try { return resolveOption() }
                        catch (failure: Exception) {
                            currentCoroutineContext().ensureActive()
                            if (refreshing) throw failure
                            val previousRoute = admissionKey?.route ?: throw failure
                            val currentRoute = playbackHistoryRoute(candidate.sourceId) ?: throw failure
                            if (!currentAttempt() || previousRoute == currentRoute) throw failure
                        }
                        // A real settings change discards the previous asset. Retry this exact
                        // line once using the current configured route and the same time budget.
                        return resolveOption()
                    }
                    return try {
                        val resolved = if (!bounded) resolveOnCurrentRoute()
                            else withTimeoutOrNull(minOf(8_000L, (autoPlaybackDeadline - monotonicTimeMillis()).coerceAtLeast(1L))) {
                                resolveOnCurrentRoute()
                            } ?: error("本集线路解析超时。")
                        Result.success(resolved)
                    } catch (cancel: CancellationException) {
                        // A provider can cancel its own child session on revision changes. A
                        // cancelled playback request still cancels the whole race.
                        currentCoroutineContext().ensureActive()
                        if (currentAttempt()) rejected += key
                        Result.failure(IllegalStateException("${candidate.sourceName} · 本集来源已变化，请重试。", cancel))
                    } catch (failure: Exception) {
                        val diagnostic = when {
                            (failure as? AnimeSourceException)?.httpStatus == 403 -> "请求被拒绝（HTTP 403）。"
                            failure is AnimeSourceException || failure is LanercCapabilityException || failure is DownloadRouteException ->
                                failure.message ?: "未能取得本集播放地址。"
                            else -> "未能取得本集播放地址。"
                        }
                        val incident = (failure as? PlaybackResolutionException)?.failure ?: AnimePlaybackFailure(candidate.sourceId, candidate.sourceName,
                            (failure as? TvtFunPlaybackException)?.playbackPhase?.label ?: "取播放地址",
                            diagnostic, unsupported = failure is LanercMediaSniffingUnsupported)
                        if (currentAttempt()) {
                            val admitted = admissionKey
                            if (admitted != null && admissionStillCurrent?.invoke() == true) {
                                tried += key
                                recoveryOptions += key
                                rememberPlaybackFailure(admitted, incident)
                            } else rejected += key
                        }
                        Result.failure(PlaybackResolutionException(incident, failure))
                    }
                }
                val resolvedSelection = run race@{
                    val events = Channel<PlaybackResolutionEvent>(Channel.UNLIMITED)
                    val raceJob = SupervisorJob(scope.coroutineContext[Job])
                    val attempts = CoroutineScope(scope.coroutineContext + raceJob)
                    val queued = mutableListOf<AnimeEpisodeOption>()
                    val scheduled = attempted.toMutableSet()
                    val runningLanes = mutableMapOf<AnimeEpisodeOption, String>()
                    fun lane(candidate: AnimeEpisodeOption) = if (sources.provider(candidate.sourceId) is LanercProvider)
                        "script:${candidate.sourceId}" else optionKey(candidate)
                    val manualFirst = manualOption != null && startupRefresh == null || available != null
                    var preferredPending = manualFirst
                    var hedgeReady = false
                    var spare: ResolvedPlaybackOption? = null
                    var lastFailure: Result<ResolvedPlaybackOption>? = null
                    fun usableSpare(): Result<ResolvedPlaybackOption>? = spare?.takeIf { candidate ->
                        recoveryEnabled() && mutableState.value.settings.preferredSourceHeight == preferredHeight &&
                            candidate.revision == sourceRevision(candidate.option.sourceId) &&
                            candidate.asset.sourceRevision == discoverySourceRevision(candidate.option.sourceId) &&
                            candidate.asset.route == playbackHistoryRoute(candidate.option.sourceId) &&
                            candidate.asset.expiresAt?.let { it > wallTimeMillis() } != false &&
                            currentPlaybackLine(episodeId, candidate.option) != null
                    }?.let { Result.success(it) }
                    fun enqueue(candidate: AnimeEpisodeOption, retry: Boolean = false) {
                        if (retry || scheduled.add(optionKey(candidate))) queued += candidate
                    }
                    fun addAvailableSources() {
                        if (!canFallback || !recoveryEnabled()) return
                        fairPlaybackOptions(detail.subject.id, episodeId, automaticPlaybackLines(episodeId),
                            preferContinuity = attempted.isEmpty()).forEach { enqueue(it) }
                    }
                    fun startAvailable() {
                        val limit = if (hedgeReady && recoveryEnabled()) ANIME_STARTUP_RESOLVE_PARALLELISM else 1
                        while (current() && runningLanes.size < limit &&
                            (!preferredPending || usableSpare() == null) &&
                            (!bounded || monotonicTimeMillis() < autoPlaybackDeadline)) {
                            val index = queued.indexOfFirst {
                                lane(it) !in runningLanes.values && (samePlaybackLine(it, selected) || recoveryEnabled())
                            }
                            if (index < 0) break
                            val candidate = queued.removeAt(index)
                            runningLanes[candidate] = lane(candidate)
                            attempts.launch { events.send(PlaybackResolutionEvent.Completed(candidate, attempt(candidate))) }
                        }
                        val displayed = if (preferredPending) selected else runningLanes.keys.singleOrNull()
                        resolvingSourceId = displayed?.sourceId
                        mutableState.update { state ->
                            val target = state.playbackTarget
                            if (target == null || target.sourceName == displayed?.sourceName) state
                            else state.copy(playbackTarget = target.copy(sourceName = displayed?.sourceName))
                        }
                    }
                    fun retryable(failure: Throwable?): Boolean {
                        val incident = failure as? PlaybackResolutionException ?: return false
                        if (incident.failure.unsupported) return false
                        val cause = incident.cause
                        if (cause is DownloadRouteException || cause is LanercCapabilityException ||
                            cause is CancellationException) return false
                        return if (cause is AnimeSourceException) cause.retryAfterSeconds == 0L &&
                            (cause.httpStatus == null || cause.httpStatus in 500..599) else cause is IllegalStateException
                    }
                    try {
                        if (canFallback) attempts.launch {
                            state.map { it.episodeOptions[episodeId] }.distinctUntilChanged().collect {
                                events.trySend(PlaybackResolutionEvent.CatalogueChanged)
                            }
                        }
                        if (canFallback) attempts.launch {
                            delay(ANIME_STARTUP_HEDGE_DELAY_MS)
                            events.trySend(PlaybackResolutionEvent.HedgeDelayElapsed)
                        }
                        enqueue(selected, retry = startupRefresh != null)
                        addAvailableSources()
                        startAvailable()
                        while (runningLanes.isNotEmpty() && current()) {
                            val now = monotonicTimeMillis()
                            val remaining = if (bounded) autoPlaybackDeadline - now else Long.MAX_VALUE
                            if (remaining <= 0L) break
                            val event = if (remaining == Long.MAX_VALUE) events.receive()
                                else withTimeoutOrNull(remaining) { events.receive() } ?: break
                            if (event is PlaybackResolutionEvent.CatalogueChanged) {
                                addAvailableSources()
                                startAvailable()
                                continue
                            }
                            if (event is PlaybackResolutionEvent.HedgeDelayElapsed) {
                                hedgeReady = true
                                startAvailable()
                                continue
                            }
                            val (candidate, result) = event as PlaybackResolutionEvent.Completed
                            // Remove the admitted lane even if the source revision changed
                            // while resolving; recomputing its key would leave a ghost slot.
                            runningLanes.remove(candidate)
                            val preferred = samePlaybackLine(candidate, selected)
                            if (result.isSuccess) {
                                if (preferred || !preferredPending && recoveryEnabled()) return@race result
                                if (recoveryEnabled()) spare = result.getOrThrow()
                                continue
                            }
                            lastFailure = result
                            if (preferredPending && preferred) {
                                preferredPending = false
                                manualFailed = true
                                if (manualOption != null && startupRefresh == null &&
                                    keepCurrentEpisodeAfterFailedSwitch(detail.subject.id, episodeId)) return@launch
                                usableSpare()?.let { return@race it }
                            }
                            addAvailableSources()
                            if (canFallback && recoveryEnabled() && startupRefresh == null &&
                                retryable(result.exceptionOrNull())) {
                                val key = playbackHistoryKey(detail.subject.id, episodeId, candidate)
                                if (key != null && playbackAddressRetries.add(key)) enqueue(candidate, retry = true)
                            }
                            startAvailable()
                        }
                        usableSpare() ?: lastFailure ?: Result.failure(IllegalStateException("对应片源暂不可用。"))
                    } finally {
                        raceOpen = false
                        raceJob.cancel()
                        events.cancel()
                    }
                }.getOrThrow()
                val (resolvedOption, resolved, revision) = resolvedSelection
                resolutionCompleted = true
                if (!current()) return@launch
                if (addresses != null && openingAddresses === addresses) {
                    val winner = playbackHistoryKey(detail.subject.id, episodeId, resolvedOption)
                    val keep = if (recoveryEnabled()) addresses.requests.entries
                        .filter { it.key != winner && !it.value.result.isCancelled }
                        .sortedByDescending { it.value.result.isCompleted }
                        .take(1).map { it.key }.toSet() else emptySet()
                    addresses.requests.entries.removeAll { (key, request) ->
                        (key !in keep).also { if (it) request.result.cancel() }
                    }
                    if (addresses.requests.isEmpty()) releaseOpeningAddresses()
                    else addresses.expiry = addresses.owner.launch {
                        delay(ANIME_STANDBY_LIFETIME_MS)
                        if (openingAddresses === addresses) releaseOpeningAddresses()
                    }
                }
                recoveryOptions += optionKey(resolvedOption)
                val live = active?.takeIf { it.detail.subject.id == detail.subject.id && it.episode.id == episodeId }
                val liveSnapshot = playerInstance?.state?.value?.takeIf { snapshot ->
                    live != null && belongsTo(live, snapshot) && hasPlaybackOutput(snapshot)
                }
                val previous = library.progressFor(detail.subject.id, episodeId)
                val recovery = recoveryResume?.takeIf { receipt -> receipt.playback.detail.subject.id == detail.subject.id &&
                    receipt.playback.episode.id == episodeId && receipt.playback.libraryEpoch == library.state.value.restoreEpoch }
                val unconfirmedResume = recovery?.takeIf { it.positionMs > 0L &&
                    !canResumeRecovery(it, resolvedOption, resolved) }
                val preserveEarlier = unconfirmedResume?.positionMs
                    ?: live?.takeIf { it.asset.id == resolved.id }?.preserveEarlierPositionMs
                val position = if (unconfirmedResume != null) 0L
                    else if (recovery != null) recovery.positionMs
                    else if (refreshSnapshot != null) refreshSnapshot.positionMs.coerceAtLeast(0)
                    else if (liveSnapshot != null && !liveSnapshot.ended) liveSnapshot.positionMs.coerceAtLeast(0)
                    else previous?.takeUnless { it.completed }?.positionMs ?: 0L
                val previousAsset = live?.asset?.id ?: previous?.assetId
                val choice = PendingPlayback(detail, episode, resolvedOption, resolved, position, generation, revision,
                    playWhenReady = recovery?.playWhenReady ?: refreshSnapshot?.playWhenReady
                        ?: liveSnapshot?.takeIf { it.phase == AnimePlayerPhase.READY }?.playWhenReady ?: true,
                    resolutionId = refreshResolutionId.takeIf { startupRefresh != null &&
                        samePlaybackLine(resolvedOption, startupRefresh.option) },
                    useSourceStart = recovery == null && startupRefresh == null && previous?.completed != true,
                    automaticIntent = automaticIntent || manualFailed,
                    bypassMediaCache = startupRefresh != null && samePlaybackLine(resolvedOption, startupRefresh.option),
                    preferredHeight = preferredHeight, preserveEarlierPositionMs = preserveEarlier)
                if (manualOption != null && !manualFailed && startupRefresh == null && position > 0L &&
                    previousAsset != null && previousAsset != resolved.id && (live == null || !samePlaybackLine(live.option, resolvedOption))) {
                    pending = choice
                    mutableState.update { it.copy(resolving = false, pendingPlayback = AnimePlaybackChoice(
                        episode, resolvedOption, position, live?.asset?.sourceId ?: previous?.sourceId.orEmpty())) }
                } else startPlayback(choice)
                preparedAdopted = listOfNotNull(available?.decoderAsset, available?.cacheAsset).any {
                    it.copy(title = resolved.title, resolutions = resolved.resolutions) == resolved
                }
            } catch (cancel: CancellationException) {
                if (generation == resolveGeneration && currentDetail(owner, detail.subject.id)) {
                    failPlaybackTarget("播放准备中断，请重试。")
                }
                throw cancel
            } catch (failure: Exception) {
                if (generation == resolveGeneration && currentDetail(owner, detail.subject.id)) {
                    val incident = (failure as? PlaybackResolutionException)?.failure
                    val latestSettings = library.state.value.settings
                    val stillCanFallback = latestSettings.automaticSourceRecovery && latestSettings.automaticRecoveryAttempts > 0
                    if (!resolutionCompleted && canFallback && stillCanFallback) {
                        // Continue progressive same-episode discovery after this batch, including
                        // a failed one-time manual choice. Explicit recovery opt-outs still apply.
                        autoAssociateAndPlay(episodeId, attempted = tried + rejected, lastFailure = incident)
                        return@launch
                    }
                    failPlaybackTarget(failure.message ?: "未能取得本集播放地址。", failure = incident)
                }
            } finally {
                if (!preparedAdopted && available != null) {
                    listOfNotNull(available.decoderAsset, available.cacheAsset).distinctBy { it.id }
                        .forEach { playerInstance?.discardStartup(it) }
                }
                if (generation == resolveGeneration) resolvingSourceId = null
            }
        }
    }

    fun confirmPlayback(resumePrevious: Boolean) {
        val choice = pending ?: return
        if (choice.generation != resolveGeneration || mutableState.value.detail?.subject?.id != choice.detail.subject.id) return
        pending = null
        playbackIntent = PlaybackIntentTiming(choice.detail.subject.id, choice.episode.id, monotonicTimeMillis())
        mutableState.update { it.copy(pendingPlayback = null, resolving = true) }
        resolveJob = scope.launch {
            try { startPlayback(choice.copy(positionMs = if (resumePrevious) choice.positionMs else 0L,
                useSourceStart = resumePrevious)) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (choice.generation == resolveGeneration) {
                    failPlaybackTarget(failure.message ?: "播放器未能打开。")
                }
            }
        }
    }

    fun dismissPlaybackChoice() = cancelPlaybackResolution()

    fun pausePlaybackResolution(reason: String = "已暂停准备本集。") {
        val target = mutableState.value.playbackTarget
        cancelPlaybackResolution()
        if (target != null) mutableState.update { it.copy(playbackTarget = target.copy(error = reason)) }
    }

    internal fun cancelPendingResume() { resumeRequest = null }

    private fun failPlaybackTarget(error: String, recoveryStopReason: String? = null, failure: AnimePlaybackFailure? = null) {
        releaseOpeningAddresses()
        mutableState.update { it.copy(resolving = false, autoMatching = false,
            playbackTarget = it.playbackTarget?.copy(error = error, failure = failure), playbackRecoveryStopReason = recoveryStopReason) }
    }

    fun cancelPlaybackResolution() = cancelPlaybackResolution(keepOpening = null)

    private fun cancelPlaybackResolution(keepOpening: OpeningAddresses?) {
        ++autoPlayGeneration
        autoPlayJob?.cancel()
        autoPlayJob = null
        ++resolveGeneration
        resolveJob?.cancel()
        resolveJob = null
        if (keepOpening == null || openingAddresses !== keepOpening) releaseOpeningAddresses()
        resolvingSourceId = null
        pending = null
        recoveryResume = null
        playbackIntent = null
        mutableState.update { it.copy(resolving = false, autoMatching = false, pendingPlayback = null, playbackTarget = null) }
    }

    private fun sourceRevision(sourceId: String): String? = sources.extensions.state.value.firstOrNull { it.id == sourceId }?.revision

    private fun discoverySourceRevision(sourceId: String): String? = sources.extensions.snapshot(sourceId)?.revision
        ?: runCatching { sources.provider(sourceId)?.revision }.getOrNull()

    private fun matchingOpeningAddresses(episodeId: String): OpeningAddresses? = openingAddresses?.takeIf {
        it.job.isActive && it.detailOwner == detailGeneration && it.episodeId == episodeId &&
            it.subjectId == mutableState.value.detail?.subject?.id &&
            it.preferredHeight == mutableState.value.settings.preferredSourceHeight &&
            it.restoreEpoch == library.state.value.restoreEpoch
    }

    /** Resolve the same episode shown by the detail's play action without opening
     * another player or writing viewing progress before the user presses play. */
    private fun prepareDetailOpeningAddresses() {
        val snapshot = mutableState.value
        val detail = snapshot.detail ?: return
        if (!foreground || active != null || (snapshot.detailLoading && detail.episodes.isEmpty()) || snapshot.resolving ||
            snapshot.autoMatching || snapshot.pendingPlayback != null || resumeRequest != null) return
        val episode = continuationEpisode(detail.subject.id) ?: run { releaseOpeningAddresses(); return }
        if (episodeAvailability(episode.id, snapshot) == AnimeEpisodeAvailability.UPCOMING) {
            releaseOpeningAddresses()
            return
        }
        val lines = fairPlaybackOptions(detail.subject.id, episode.id, automaticPlaybackLines(episode.id, snapshot))
        val existing = matchingOpeningAddresses(episode.id)
        if (existing == null && openingAddresses != null) releaseOpeningAddresses()
        if (lines.isEmpty()) {
            releaseOpeningAddresses()
            return
        }
        val addresses = existing ?: OpeningAddresses(detailGeneration, detail.subject.id, episode.id,
            snapshot.settings.preferredSourceHeight, library.state.value.restoreEpoch, scope).also { owner ->
            openingAddresses = owner
            owner.expiry = owner.owner.launch {
                delay(30_000L)
                if (openingAddresses === owner) releaseOpeningAddresses()
            }
        }
        val keyed = lines.mapNotNull { line -> playbackHistoryKey(detail.subject.id, episode.id, line)?.let { it to line } }
        val validKeys = keyed.map { it.first }.toSet()
        addresses.requests.entries.removeAll { (key, request) ->
            (key !in validKeys).also { if (it) request.result.cancel() }
        }
        addresses.warmed.retainAll(validKeys)
        if (addresses.warmed.isNotEmpty()) return
        keyed.take(1)
            .forEach { (key, line) ->
                addresses.warmed += key
                addresses.owner.launch {
                    fun current() = openingAddresses === addresses && matchingOpeningAddresses(episode.id) === addresses &&
                        key.route == playbackHistoryRoute(line.sourceId) &&
                        playbackHistoryKey(detail.subject.id, episode.id, line) == key &&
                        currentPlaybackLine(episode.id, line) != null
                    try {
                        val provider = sources.provider(line.sourceId) ?: return@launch
                        val catalog = mutableState.value.sourceCatalogs.firstOrNull {
                            it.title.sourceId == line.sourceId && it.title.id == line.catalog.id && it.title.url == line.catalog.url
                        } ?: return@launch
                        if (!current()) return@launch
                        val address = readOpeningAddress(addresses, key, line.sourceId) {
                            val started = monotonicTimeMillis()
                            val asset = provider.resolve(catalog, line.episode)
                            check(current() && asset.sourceId == line.sourceId && asset.sourceEpisodeId == line.episode.id &&
                                asset.sourceRevision == discoverySourceRevision(line.sourceId) && asset.route == key.route &&
                                asset.url.isNotBlank() && asset.expiresAt?.let { it > wallTimeMillis() } != false) {
                                "本集地址或来源已经变化。"
                            }
                            OpeningAddress(asset, (monotonicTimeMillis() - started).coerceAtLeast(0L), wallTimeMillis())
                        }
                        if (current()) storePlaybackMeasurements(key, line) { it.copy(addressMs = address.elapsedMs,
                            declaredQuality = declaredPlaybackQuality(preferredSourceAsset(address.asset, line.episode,
                                addresses.preferredHeight)), addressObservedAt = address.observedAt) }
                    } catch (cancel: CancellationException) {
                        currentCoroutineContext().ensureActive()
                    } catch (_: Exception) {
                        // A background preparation failure is not a failed viewing attempt.
                        // The explicit play action may try this exact line again.
                    }
                }
            }
    }

    private fun releaseOpeningAddresses() {
        val previous = openingAddresses
        openingAddresses = null
        previous?.job?.cancel()
    }

    private suspend fun readOpeningAddress(addresses: OpeningAddresses, key: PlaybackHistoryKey, sourceId: String,
        fetch: suspend () -> OpeningAddress): OpeningAddress {
        fun start(): OpeningRequest {
            addresses.requests.remove(key)?.result?.cancel()
            val result = addresses.owner.async {
                // The foreground attempt owns its recovery deadline. A detail-owned
                // address may start before that playback window even exists.
                addresses.permits.withPermit {
                    withTimeoutOrNull(8_000L) { fetch() } ?: error("本集线路解析超时。")
                }
            }
            return OpeningRequest(sourceId, result).also { addresses.requests[key] = it }
        }
        val existing = addresses.requests[key]?.takeUnless { it.result.isCancelled }
        val address = (existing ?: start()).result.await()
        return if (existing != null && !freshOpeningAddress(address)) start().result.await() else address
    }

    private fun freshOpeningAddress(address: OpeningAddress): Boolean {
        val now = wallTimeMillis()
        return now - address.observedAt in 0L until 30_000L && address.asset.expiresAt?.let { it > now } != false
    }

    private fun keepCurrentEpisodeAfterFailedSwitch(subjectId: String, episodeId: String): Boolean {
        val owner = active?.takeIf { it.detail.subject.id == subjectId && it.episode.id == episodeId } ?: return false
        val current = playerInstance?.state?.value ?: return false
        if (!belongsTo(owner, current) || current.phase != AnimePlayerPhase.READY || current.isBuffering ||
            current.error != null || !hasPlaybackOutput(current)) return false
        releaseOpeningAddresses()
        mutableState.update { it.copy(resolving = false, autoMatching = false, playbackTarget = null,
            pendingPlayback = null, playbackRecoveryStopReason = null) }
        notice("所选线路暂不可用，已继续当前播放。")
        return true
    }

    private fun validCandidate(choice: PendingPlayback): Boolean =
        choice.localMedia != null || sources.provider(choice.option.sourceId) != null && sourceRevision(choice.option.sourceId) == choice.sourceRevision &&
            (!choice.bypassMediaCache || library.state.value.settings.automaticRecoveryAttempts > 0) &&
            (choice.preferredHeight == null || choice.preferredHeight == library.state.value.settings.preferredSourceHeight) &&
            choice.asset.sourceRevision == discoverySourceRevision(choice.option.sourceId) &&
            (choice.asset.expiresAt == null || choice.asset.expiresAt > wallTimeMillis()) &&
            choice.asset.route == playbackHistoryRoute(choice.option.sourceId) &&
            currentPlaybackLine(choice.episode.id, choice.option) != null

    private suspend fun startPlayback(choice: PendingPlayback, rememberPrevious: Boolean = true,
        inheritEnhancementSession: String? = null, explicitEnhancementPolicy: EnhancementPolicy? = null,
        expectedViewingPolicy: EnhancementPolicy? = null) {
        check(validCandidate(choice)) { "来源、网络路线或集数对应已变化，请重新解析。" }
        if (choice.generation != resolveGeneration) return
        if (!sourceMeasurementsUserRequested) cancelEpisodeSourceMeasurements()
        check(mutableState.value.detail?.subject?.id == choice.detail.subject.id) {
            "来源关联已变化，请重新选择这一集。"
        }
        if (inheritEnhancementSession != null && expectedViewingPolicy != null &&
            !sameViewingEffects(expectedViewingPolicy,
                enhancementCenter.settings.policy(EnhancementContent.ANIME, inheritEnhancementSession))) {
            val owner = active ?: return
            val snapshot = playerInstance?.state?.value?.takeIf { belongsTo(owner, it) } ?: return
            val local = owner.localMedia ?: return
            openDownload(local.downloadId, fallback = null,
                continuation = PlaybackReceipt(owner, snapshot.positionMs, snapshot.playWhenReady))
            return
        }
        val engine = player
        val old = active
        val oldSnapshot = engine.state.value
        val continuingSnapshot = if (inheritEnhancementSession != null) {
            val owner = old ?: return
            if ("anime:${owner.asset.id}:${owner.playerGeneration}" != inheritEnhancementSession ||
                !belongsTo(owner, oldSnapshot)) return
            // Use the latest seek and pause state at the handoff.
            oldSnapshot
        } else null
        if (old != null) savePlaybackAtHandoff(old, oldSnapshot)
        if (rememberPrevious && old != null && old.detail.subject.id == choice.detail.subject.id &&
            belongsTo(old, oldSnapshot) && oldSnapshot.phase == AnimePlayerPhase.READY && hasPlaybackOutput(oldSnapshot))
            rollback = PlaybackReceipt(old, oldSnapshot.positionMs, oldSnapshot.playWhenReady)
        else if (old?.detail?.subject?.id != choice.detail.subject.id) rollback = null
        val nextEpoch = (playbackEpochs[choice.detail.subject.id] ?: 0L) + 1L
        playbackEpochs[choice.detail.subject.id] = nextEpoch
        pending = null
        val requestedPosition = continuingSnapshot?.positionMs ?: choice.positionMs
        val position = if (requestedPosition > 0 || !choice.useSourceStart) requestedPosition
            else choice.asset.startPositionMs?.coerceAtLeast(0) ?: 0L
        val labelledAsset = choice.asset.copy(subjectId = choice.detail.subject.id,
            subjectTitle = choice.detail.subject.title, episodeTitle = choice.episode.title)
        active = ActivePlayback(choice.detail, choice.episode, choice.option, labelledAsset,
            nextEpoch, engine.state.value.sessionGeneration + 1, library.state.value.restoreEpoch,
            openedAt = monotonicTimeMillis(),
            intent = playbackIntent?.takeIf { it.subjectId == choice.detail.subject.id && it.episodeId == choice.episode.id },
            sourceRevision = choice.sourceRevision, localMedia = choice.localMedia, openingSkipped = position > 0,
            automaticIntent = choice.automaticIntent, explicitResolutionId = choice.resolutionId,
            preserveEarlierPositionMs = choice.preserveEarlierPositionMs)
        active?.let { owner ->
            val session = "anime:${owner.asset.id}:${owner.playerGeneration}"
            val sessionEnhancementOverride = inheritEnhancementSession?.let {
                enhancementCenter.settings.sessions.value[it]?.policy
            } ?: explicitEnhancementPolicy
            if (sessionEnhancementOverride != null)
                enhancementCenter.settings.setSessionOverride(session, EnhancementContent.ANIME, sessionEnhancementOverride)
            owner.viewingPolicy = enhancementCenter.settings.policy(EnhancementContent.ANIME, session)
        }
        danmaku.setContext(active?.let(::playbackKey))
        lastCheckpointAt = 0L
        lastSavedPosition = -1L
        lastSavedPhase = null
        mutableState.update { it.copy(resolving = false, selectedEpisodeId = choice.episode.id, pendingPlayback = null,
            playbackTarget = null, selectedPlaybackOption = choice.option.takeIf { choice.localMedia == null },
            canRestorePlayback = false, sourceError = null, playbackRecoveryStopReason = null,
            playbackFirstFrameMs = null, playbackIntentFirstFrameMs = null,
            playbackRouteMode = choice.asset.route.mode.takeIf { choice.localMedia == null },
            sourceDanmakuAvailable = active?.let(::hasSourceDanmaku) == true,
            playbackResolutions = choice.asset.resolutions, selectedResolutionId = choice.resolutionId ?:
                choice.asset.resolutions.singleOrNull { item -> item.url == choice.asset.url }?.id) }
        engine.configure(playerOptions(mutableState.value.settings))
        val owner = active
        engine.open(AnimePlayerRequest(labelledAsset, position, continuingSnapshot?.playWhenReady ?: choice.playWhenReady, choice.localMedia,
            bypassMediaCache = choice.bypassMediaCache, hasStartupAlternative = owner?.takeIf { it.localMedia == null }?.let { playback ->
                { canFailOverStartup(playback) }
            }))
    }

    fun restorePreviousPlayback() {
        val receipt = rollback ?: return
        val previous = receipt.playback
        if (previous.detail.subject.id != mutableState.value.detail?.subject?.id) return
        cancelPlaybackResolution()
        clearPreparation()
        resetPlaybackRecoveryWindow()
        val choice = PendingPlayback(previous.detail, previous.episode, previous.option, previous.asset,
            receipt.positionMs, resolveGeneration, previous.sourceRevision, receipt.playWhenReady,
            resolutionId = previous.explicitResolutionId, localMedia = previous.localMedia, useSourceStart = false,
            automaticIntent = previous.automaticIntent, preserveEarlierPositionMs = previous.preserveEarlierPositionMs)
        scope.launch {
            try {
                startPlayback(choice, rememberPrevious = false)
                rollback = null
                notice("已恢复上次播放。")
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { notice(failure.message ?: "原播放地址已失效，请重新选择来源。") }
        }
    }

    fun selectResolution(id: String) {
        val owner = active ?: return
        val resolution = owner.asset.resolutions.singleOrNull { it.id == id } ?: return
        val snapshot = playerInstance?.state?.value ?: return
        if (!belongsTo(owner, snapshot)) return
        cancelPlaybackResolution()
        clearPreparation()
        resetPlaybackRecoveryWindow()
        val asset = assetWithResolution(owner.asset, owner.option.episode, resolution)
        val choice = PendingPlayback(owner.detail, owner.episode, owner.option, asset,
            snapshot.positionMs, resolveGeneration, owner.sourceRevision, snapshot.playWhenReady, id,
            useSourceStart = false, automaticIntent = owner.automaticIntent,
            preserveEarlierPositionMs = owner.preserveEarlierPositionMs)
        scope.launch {
            try { startPlayback(choice) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { notice(failure.message ?: "清晰度切换未完成。") }
        }
    }

    private fun assetWithResolution(asset: AnimePlaybackAsset, episode: AnimeSourceEpisode, resolution: AnimeResolution) =
        asset.copy(id = asset.sourceId + ":quality:" +
            dev.mangaflow.anime.sources.animeHash(episode.id + "\n" + resolution.id + "\n" +
                dev.mangaflow.anime.sources.animeContentIdentity(resolution.url)),
            url = resolution.url, mimeType = resolution.mimeType, headers = resolution.headers)

    private fun preferredSourceAsset(asset: AnimePlaybackAsset, episode: AnimeSourceEpisode, targetHeight: Int): AnimePlaybackAsset {
        if (targetHeight == 0) return asset
        val variants = asset.resolutions.mapNotNull { resolution ->
            val height = SOURCE_HEIGHT.find(resolution.label)?.groupValues?.get(1)?.toIntOrNull()
                ?: when (resolution.label.trim().lowercase()) {
                    "4k", "uhd" -> 2160
                    "2k", "qhd" -> 1440
                    else -> null
                }
            height?.let { it to resolution }
        }
        val selected = variants.filter { it.first <= targetHeight }.maxByOrNull { it.first }
            ?: variants.minByOrNull { it.first } ?: return asset
        return assetWithResolution(asset, episode, selected.second)
    }

    private fun refreshSourceAsset(asset: AnimePlaybackAsset, episode: AnimeSourceEpisode, targetHeight: Int,
        failed: AnimePlaybackAsset): AnimePlaybackAsset {
        val preferred = preferredSourceAsset(asset, episode, targetHeight)
        // A refreshed signature or session header can repair the same media object.
        if (preferred.url != failed.url || preferred.headers != failed.headers ||
            preferred.mimeType != failed.mimeType) return preferred
        val failedAddress = animeContentIdentity(failed.url)
        if (animeContentIdentity(asset.url) != failedAddress) return asset
        val otherResolutions = asset.resolutions.filter { animeContentIdentity(it.url) != failedAddress }
        val next = otherResolutions.firstOrNull() ?: return preferred
        val alternate = assetWithResolution(asset, episode, next).copy(resolutions = otherResolutions)
        return preferredSourceAsset(alternate, episode, targetHeight).copy(resolutions = asset.resolutions)
    }

    fun retryPlayback() {
        mutableState.value.playbackTarget?.takeIf { it.error != null }?.let {
            play(it.episode.id, it.manualOption?.takeIf { line -> canAttemptPlaybackLine(it.episode.id, line) })
            return
        }
        val current = active ?: return
        if (current.localMedia != null) player.retry()
        else play(current.episode.id, current.option.takeUnless { current.automaticIntent }
            ?.takeIf { canAttemptPlaybackLine(current.episode.id, it) })
    }

    fun closePlayback() = stopPlayback(discardCheckpoint = false)

    private fun stopPlayback(discardCheckpoint: Boolean) {
        cancelEpisodeSourceMeasurements()
        if (!discardCheckpoint) flushPlayback()
        else active?.let { playbackEpochs[it.detail.subject.id] = it.epoch + 1 }
        cancelPlaybackResolution()
        clearPreparation()
        rollback = null
        active = null
        danmaku.setContext(null)
        playerInstance?.stop()
        if (downloadsOwner.isInitialized()) downloads.setPlaybackBusy(false)
        mutableState.update { it.copy(selectedEpisodeId = null, playbackResolutions = emptyList(),
            selectedResolutionId = null, canRestorePlayback = false, sourceDanmakuAvailable = false,
            selectedPlaybackOption = null, playbackFirstFrameMs = null, playbackIntentFirstFrameMs = null,
            playbackRouteMode = null) }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) flushPlayback()
        playerInstance?.setForeground(value)
        if (!value) {
            clearPreparation()
            cancelOnlineVideo(clear = true)
            if (active == null && !mutableState.value.resolving) releaseOpeningAddresses()
        }
        if (value) prepareDetailOpeningAddresses()
        if (value && discoveryVisible) ensureDiscovery()
        else if (!value) discoveryLoads.visible(emptySet())
    }

    fun flushPlayback() {
        val owner = active ?: return
        val snapshot = playerInstance?.state?.value ?: return
        checkpoint(owner, snapshot, force = true)
    }

    private fun belongsTo(owner: ActivePlayback, snapshot: AnimePlayerState) =
        snapshot.sessionGeneration == owner.playerGeneration && snapshot.episodeId == owner.episode.id && snapshot.assetKey == owner.asset.id

    private fun hasPlaybackOutput(snapshot: AnimePlayerState) =
        snapshot.firstFrameRendered != false || snapshot.hasVideoTracks == false

    private fun playbackKey(owner: ActivePlayback) = "${owner.detail.subject.id}/${owner.episode.id}/${owner.epoch}/${owner.libraryEpoch}"

    private fun hasSourceDanmaku(owner: ActivePlayback): Boolean = owner.localMedia == null &&
        sources.provider(owner.option.sourceId) != null &&
        mutableState.value.sourceCatalogs.any { it.title.sourceId == owner.option.sourceId &&
            it.title.id == owner.option.catalog.id && it.title.url == owner.option.catalog.url &&
            danmaku.supportsSource(it, owner.option.episode) }

    fun loadCurrentSourceDanmaku() {
        val owner = active ?: return
        val catalog = mutableState.value.sourceCatalogs.firstOrNull { it.title.sourceId == owner.option.sourceId &&
            it.title.id == owner.option.catalog.id && it.title.url == owner.option.catalog.url } ?: run {
            notice("当前来源目录已改变，请刷新后核对弹幕剧集。"); return
        }
        danmaku.loadSource(catalog, owner.option.episode)
    }

    private fun savePlaybackAtHandoff(owner: ActivePlayback, snapshot: AnimePlayerState) {
        if (library.state.value.readUnavailable) return
        if (!belongsTo(owner, snapshot) || !hasPlaybackOutput(snapshot) || !maySavePlaybackPosition(owner, snapshot) ||
            snapshot.phase != AnimePlayerPhase.READY && snapshot.phase != AnimePlayerPhase.ENDED &&
            !(snapshot.phase == AnimePlayerPhase.ERROR && owner.playbackStarted && snapshot.positionMs > 0L)) return
        val captured = progress(owner, snapshot)
        scope.launch {
            try {
                library.checkpoint(owner.detail.subject, captured) {
                    library.state.value.restoreEpoch == owner.libraryEpoch
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if (active?.detail?.subject?.id == owner.detail.subject.id) notice("上一段观看位置暂未保存。")
            }
        }
    }

    private fun progress(owner: ActivePlayback, snapshot: AnimePlayerState) = AnimeWatchProgress(
        owner.episode.id, owner.asset.id, owner.asset.sourceId, snapshot.positionMs.coerceAtLeast(0L),
        snapshot.durationMs.coerceAtLeast(0L), wallTimeMillis(), snapshot.ended, owner.detail.subject.id, owner.localMedia?.downloadId,
        episodeTitle = owner.episode.title, episodeNumber = owner.episode.number.takeIf { it.isFinite() },
        episodeKind = owner.episode.kind)

    private fun maySavePlaybackPosition(owner: ActivePlayback, snapshot: AnimePlayerState) =
        snapshot.ended || owner.preserveEarlierPositionMs?.let { snapshot.positionMs >= it } != false

    private fun playbackBusy(snapshot: AnimePlayerState? = playerInstance?.state?.value): Boolean =
        mutableState.value.resolving || snapshot?.isBuffering == true || snapshot?.phase == AnimePlayerPhase.OPENING ||
            active?.let { snapshot != null && snapshot.sessionGeneration < it.playerGeneration } == true

    private fun observePlayer(engine: AnimePlayer) {
        scope.launch {
            engine.state.collect { snapshot ->
                observeEnhancement(snapshot)
                mutablePlaybackPhase.value = snapshot.phase
                val owner = active ?: return@collect
                if (!belongsTo(owner, snapshot)) return@collect
                val firstOutput = snapshot.firstFrameRendered == true || snapshot.hasVideoTracks == false && snapshot.isPlaying
                if (firstOutput || (snapshot.phase == AnimePlayerPhase.READY && snapshot.isPlaying &&
                    !snapshot.isBuffering && hasPlaybackOutput(snapshot))) owner.playbackStarted = true
                val firstFrameAt = snapshot.firstFrameAtMillis
                if (firstFrameAt != null && mutableState.value.playbackFirstFrameMs == null) {
                    val elapsed = (firstFrameAt - owner.openedAt).coerceAtLeast(0L)
                    val intentElapsed = owner.intent?.let { (firstFrameAt - it.startedAt).coerceAtLeast(0L) }
                    mutableState.update { it.copy(playbackFirstFrameMs = elapsed, playbackIntentFirstFrameMs = intentElapsed) }
                }
                if (owner.playbackStarted) {
                    if (playbackIntent === owner.intent) playbackIntent = null
                    if (owner.preserveEarlierPositionMs != null && !owner.resumeNoticeShown) {
                        owner.resumeNoticeShown = true
                        notice("备用线路的时间轴尚未确认，已从本集开头播放，原观看进度已保留。")
                    }
                }
                danmaku.updatePosition(playbackKey(owner), snapshot.positionMs, snapshot.isPlaying)
                if (downloadsOwner.isInitialized()) downloads.setPlaybackBusy(playbackBusy(snapshot))
                checkpoint(owner, snapshot, force = snapshot.phase != lastSavedPhase || snapshot.ended || !snapshot.playWhenReady)
                if (snapshot.phase == AnimePlayerPhase.ERROR) {
                    if (owner.localMedia?.enhancedVersion == true && !owner.recoveryHandled) {
                        owner.recoveryHandled = true
                        openDownload(owner.localMedia.downloadId, fallback = null, useOriginal = true)
                        notice("增强版本播放失败，已切回保留的原始视频")
                        return@collect
                    }
                    val historyKey = playbackHistoryKey(owner.detail.subject.id, owner.episode.id, owner.option)
                    if (!owner.recoveryHandled && snapshot.sourceRecoveryAllowed && historyKey != null && owner.asset.route == historyKey.route &&
                        owner.sourceRevision == sourceRevision(owner.option.sourceId) &&
                        currentPlaybackLine(owner.episode.id, owner.option) != null)
                        rememberPlaybackFailure(historyKey, AnimePlaybackFailure(owner.option.sourceId,
                            owner.option.sourceName, "播放视频", snapshot.error ?: "视频未能打开。"))
                    mutableState.update { it.copy(canRestorePlayback = rollback != null) }
                    if (!owner.recoveryHandled) { owner.recoveryHandled = true; recoverPlayback(owner) }
                }
                val playingSteadily = snapshot.isPlaying && !snapshot.playbackSuppressed && !snapshot.isBuffering &&
                    snapshot.phase == AnimePlayerPhase.READY &&
                    hasPlaybackOutput(snapshot)
                val now = monotonicTimeMillis()
                val stableSince = if (playingSteadily) owner.stablePlaybackSince ?: now else null
                owner.stablePlaybackSince = stableSince
                val stableFor = stableSince?.let { now - it } ?: 0L
                if (stableFor >= 2_000L && mutableState.value.playbackTarget == null && !mutableState.value.resolving) {
                    if (openingAddresses != null) releaseOpeningAddresses()
                    if (maySavePlaybackPosition(owner, snapshot)) recoveryResume = null
                }
                prepareOnlineVideo(owner, snapshot, stableFor)
                if (preparationTarget?.timelineGeneration?.let { it != snapshot.timelineGeneration } == true)
                    clearPreparation()
                if (snapshot.isBuffering && sourceMeasurementsJob != null && !sourceMeasurementsUserRequested)
                    cancelEpisodeSourceMeasurements()
                observePlaybackMeasurements(owner, snapshot, now, stableFor)
                if (!owner.sourceReceiptSaved && owner.localMedia == null && snapshot.hasVideoTracks != false && stableFor >= 2_000L) {
                    rememberWorkingSource(owner, now)
                }
                if (!owner.stableRecoveryReset && stableFor >= 30_000L && mutableState.value.playbackTarget == null) {
                    owner.stableRecoveryReset = true
                    sourceRecoveryWindowStarted = false
                    playbackAddressRetries.clear()
                    playbackStartupRefreshes.clear()
                    playbackDiscoveryRetried = false
                    recoveryOptions.clear()
                }
                if (!playingSteadily && !snapshot.ended && preparationTarget != null) {
                    if (prepared == null || snapshot.phase == AnimePlayerPhase.ERROR) {
                        clearPreparation()
                        owner.nextPreparationNotBefore = now + 1_000L
                    } else if (cacheNextJob != null) {
                        // Keep the exact address and completed cache work while current playback needs resources.
                        cacheNextJob?.cancel()
                        cacheNextJob = null
                        mutableState.update { it.copy(nextEpisodePreparation = it.nextEpisodePreparation?.copy(
                            phase = AnimePreparationPhase.WAITING, detail = "优先当前播放，已准备的下一集内容保留。")) }
                    }
                }
                if (snapshot.isPlaying) {
                    val settings = mutableState.value.settings
                    if (!owner.openingSkipped && settings.skipOpeningSeconds > 0 && snapshot.durationMs > 0) {
                        owner.openingSkipped = true
                        val target = settings.skipOpeningSeconds * 1_000L
                        if (target < snapshot.durationMs && snapshot.positionMs < target) engine.seekTo(target)
                    }
                    val endingSkip = (settings.skipEndingSeconds * 1_000L).takeIf { it in 1L until snapshot.durationMs } ?: 0L
                    val remaining = snapshot.durationMs - endingSkip - snapshot.positionMs
                    if (!owner.endingSkipped && endingSkip > 0L && remaining <= 0L) {
                        owner.endingSkipped = true
                        engine.seekTo(snapshot.durationMs)
                    }
                    if (stableFor >= 2_000L && foreground && owner.localMedia == null && settings.prepareNextEpisode &&
                        now >= owner.nextPreparationNotBefore && !mutableState.value.resolving &&
                        !mutableState.value.sleepAtEpisodeEnd && snapshot.durationMs > 0L &&
                        remaining <= preparationBudget(settings).nextEpisodeLeadMs) {
                        val ready = prepared
                        if (ready != null) warmNextEpisode(owner, ready)
                        else if (preparationTarget == null) prepareNext(owner, snapshot.timelineGeneration)
                    }
                }
                if (snapshot.ended && hasPlaybackOutput(snapshot) && !owner.endActionHandled) {
                    owner.endActionHandled = true
                    if (mutableState.value.sleepAtEpisodeEnd) {
                        setSleepTimer(null); cancelPlaybackResolution(); clearPreparation(); engine.pause()
                    }
                    else if (mutableState.value.settings.autoPlayNext &&
                        (foreground || mutableState.value.settings.backgroundAudio || snapshot.inPictureInPicture)) {
                        nextEpisode(owner)?.let { next -> play(next.id) }
                    }
                }
            }
        }
    }

    private fun checkpoint(owner: ActivePlayback, snapshot: AnimePlayerState, force: Boolean) {
        if (library.state.value.readUnavailable) return
        if (!belongsTo(owner, snapshot) || !hasPlaybackOutput(snapshot) || !maySavePlaybackPosition(owner, snapshot) ||
            snapshot.phase != AnimePlayerPhase.READY && snapshot.phase != AnimePlayerPhase.ENDED) return
        val now = wallTimeMillis()
        if (!force && now - lastCheckpointAt < 5_000L) return
        val ended = snapshot.ended && !owner.endedSaved
        if (!ended && snapshot.positionMs == lastSavedPosition && snapshot.phase == lastSavedPhase) return
        lastCheckpointAt = now
        lastSavedPosition = snapshot.positionMs
        lastSavedPhase = snapshot.phase
        val progress = progress(owner, snapshot)
        scope.launch {
            try {
                val saved = library.checkpoint(owner.detail.subject, progress) {
                    playbackEpochs[owner.detail.subject.id] == owner.epoch && library.state.value.restoreEpoch == owner.libraryEpoch
                }
                if (ended && saved) owner.endedSaved = true
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (active === owner) { lastCheckpointAt = 0L; lastSavedPosition = -1L; lastSavedPhase = null }
                notice(failure.message ?: "播放进度暂未保存，请重试。")
            }
        }
    }

    private fun nextEpisode(owner: ActivePlayback): AnimeEpisode? {
        val detail = mutableState.value.detail?.takeIf { it.subject.id == owner.detail.subject.id } ?: return null
        val episodes = detail.episodes.filter { it.kind == owner.episode.kind }
        val index = episodes.indexOfFirst { it.id == owner.episode.id }
        // End-of-episode playback may await this exact next episode's pending sources.
        // Do not consume the one end action merely because correspondence is still loading.
        return episodes.getOrNull(index + 1)?.takeIf { next -> index >= 0 &&
            episodeAvailability(next.id) != AnimeEpisodeAvailability.UPCOMING }
    }

    private fun prepareNext(owner: ActivePlayback, timelineGeneration: Long) {
        val next = nextEpisode(owner)?.takeIf {
            episodeAvailability(it.id) == AnimeEpisodeAvailability.MATCHED
        } ?: return
        val subjectId = owner.detail.subject.id
        val option = defaultPreparationOption(subjectId, next.id) ?: return
        val catalog = mutableState.value.sourceCatalogs.firstOrNull { it.title.sourceId == option.catalog.sourceId &&
            it.title.id == option.catalog.id && it.title.url == option.catalog.url } ?: return
        val provider = sources.provider(option.sourceId) ?: return
        val revision = discoverySourceRevision(option.sourceId) ?: return
        val route = playbackHistoryRoute(option.sourceId) ?: return
        val engine = playerInstance ?: return
        val settings = mutableState.value.settings
        val budget = preparationBudget(settings)
        val target = PreparationTarget(subjectId, next.id, option, revision, route,
            settings.preferredSourceHeight, timelineGeneration, explicitPlaybackSource(subjectId), settings, budget)
        val generation = resolveGeneration
        preparationTarget = target
        mutableState.update { it.copy(nextEpisodePreparation = AnimeNextEpisodePreparation(next.id,
            targetDurationMs = budget.nextEpisodeDurationMs, maximumBytes = budget.nextEpisodeBytes)) }
        prepareJob = scope.launch(start = CoroutineStart.LAZY) {
            var failed = false
            try {
                val policy = ExperiencePolicyStore.get(context)
                val asset = withTimeoutOrNull(8_000L) {
                    policy.withResolveAdmission(promoted = { false }) { provider.resolve(catalog, option.episode) }
                }
                    ?: error("下一集来源解析超时。")
                if (preparationTarget !== target || active !== owner || generation != resolveGeneration ||
                    !preparationStillCurrent(target)) return@launch
                check(asset.sourceId == option.sourceId && asset.sourceEpisodeId == option.episode.id &&
                    asset.sourceRevision == revision && asset.route == route)
                val currentPlayback = engine.state.value
                val endingSkip = (settings.skipEndingSeconds * 1_000L)
                    .takeIf { it in 1L until currentPlayback.durationMs } ?: 0L
                val remainingMs = (currentPlayback.durationMs - endingSkip - currentPlayback.positionMs).coerceAtLeast(0L)
                val speed = currentPlayback.speed.toDouble().takeIf { it.isFinite() && it > 0.0 } ?: 1.0
                val untilSwitch = (remainingMs / speed).toLong()
                val retention = untilSwitch.coerceAtMost(Long.MAX_VALUE - monotonicTimeMillis() - 30_000L) + 30_000L
                val lifetime = asset.expiresAt?.let { expiry ->
                    val now = wallTimeMillis()
                    check(expiry > now)
                    minOf(retention, expiry - now - 2_000L)
                } ?: retention
                check(lifetime > 0L)
                val canonical = asset.copy(episodeId = next.id, title = owner.detail.subject.title + " · " + next.title,
                    subjectId = subjectId, subjectTitle = owner.detail.subject.title, episodeTitle = next.title)
                val ready = PreparedPlayback(target, canonical, monotonicTimeMillis() + lifetime)
                prepared = ready
                publishNextPreparation(owner, target) { it.copy(addressResolved = true, phase = AnimePreparationPhase.WAITING) }
                warmNextEpisode(owner, ready)
                delay(lifetime)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if (preparationTarget === target) {
                    failed = true
                    owner.nextPreparationNotBefore = monotonicTimeMillis() + 30_000L
                    publishNextPreparation(owner, target) { it.copy(phase = AnimePreparationPhase.FAILED, detail = "下一集地址未能准备，稍后自动重试。") }
                }
            } finally {
                if (preparationTarget === target) releasePreparation(keepStatus = failed)
            }
        }
        prepareJob?.start()
    }

    private fun publishNextPreparation(owner: ActivePlayback, target: PreparationTarget,
        change: (AnimeNextEpisodePreparation) -> AnimeNextEpisodePreparation) {
        mutableState.update { state ->
            if (preparationTarget !== target || active !== owner) state else state.copy(
                nextEpisodePreparation = state.nextEpisodePreparation?.takeIf { it.episodeId == target.episodeId }?.let(change))
        }
    }

    private fun warmNextEpisode(owner: ActivePlayback, ready: PreparedPlayback) {
        if (cacheNextJob != null || ready.mediaSettled || !freshPreparation(ready)) return
        val engine = playerInstance ?: return
        val target = ready.target
        val snapshot = engine.state.value
        if (!foreground || !snapshot.isPlaying || snapshot.playbackSuppressed || playbackBusy(snapshot)) return
        val option = target.option
        val settings = target.settings
        val budget = target.budget
        val policy = ExperiencePolicyStore.get(context)
        cacheNextJob = scope.launch(start = CoroutineStart.LAZY) nextMedia@{
            val job = currentCoroutineContext()[Job]
            fun mayPrepare() = cacheNextJob === job && foreground && !playbackBusy() &&
                active === owner && prepared === ready && freshPreparation(ready)
            fun publish(change: (AnimeNextEpisodePreparation) -> AnimeNextEpisodePreparation) {
                if (cacheNextJob === job) publishNextPreparation(owner, target, change)
            }
            try {
                val startup = preferredSourceAsset(ready.asset, option.episode, target.preferredHeight)
                ready.cacheAsset = startup
                val previous = library.progressFor(target.subjectId, target.episodeId)
                val resume = previous?.takeUnless { it.completed }?.positionMs
                val position = if (previous?.completed == true) 0L else
                    resume?.takeIf { it > 0L } ?: startup.startPositionMs?.coerceAtLeast(0L) ?: 0L
                publish { it.copy(positionMs = position) }
                val pauseReason = when {
                    budget.nextEpisodeBytes <= 0L -> "下一集缓存容量为 0，仅保留已解析的地址。"
                    policy.state.value.prefetchBytes < budget.nextEpisodeBytes ->
                        "共享预读预算不足以容纳所设容量；已保留设置，可在体验设置调整总预算。"
                    else -> null
                }
                if (pauseReason != null) {
                    publish { it.copy(phase = AnimePreparationPhase.PARTIAL, detail = pauseReason) }
                    ready.mediaSettled = true
                    return@nextMedia
                }
                val cached = ready.cacheResult.takeIf { ready.cachePositionMs == position }
                val result = cached ?: if (budget.nextEpisodeDurationMs == 0L) AnimeStartupCacheResult(0L,
                    detail = "下一集原片预读已关闭。") else try {
                    withTimeoutOrNull(minOf(ready.validUntil - monotonicTimeMillis(), 30_000L)) {
                        policy.withPrefetchAdmission(option.sourceId, budget.nextEpisodeBytes, { false },
                            onWaiting = { wait -> if (wait != null) publish { it.copy(
                                phase = AnimePreparationPhase.WAITING, detail = "等待共享预读资源，优先当前播放。") } }) {
                            if (!mayPrepare()) return@withPrefetchAdmission AnimeStartupCacheResult(0L,
                                detail = "当前播放需要资源，已暂停下一集准备。")
                            publish { it.copy(phase = AnimePreparationPhase.CACHING, detail = null) }
                            engine.prepareStartupCache(AnimeStartupCacheRequest(startup, position,
                                budget.nextEpisodeDurationMs, budget.nextEpisodeBytes, ready.validUntil))
                        }
                    } ?: AnimeStartupCacheResult(0L, detail = "下一集原片准备超时，已写入的缓存仍保留。")
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { AnimeStartupCacheResult(0L, detail = "下一集原片暂未准备完成，仍可正常切集。") }
                if (!mayPrepare()) return@nextMedia
                ready.cacheResult = result
                ready.cachePositionMs = position
                publish { it.copy(cachedBytes = result.bytes, bufferedDurationMs = result.bufferedDurationMs,
                    phase = AnimePreparationPhase.PARTIAL, detail = result.detail) }
                val timeLeft = ready.validUntil - monotonicTimeMillis()
                if (timeLeft <= 0L) return@nextMedia
                val decoded = settings.predecodeNextEpisode && withTimeoutOrNull(minOf(timeLeft, 12_000L)) {
                    policy.withPrefetchAdmission(option.sourceId, budget.nextEpisodeBytes, { false },
                        onWaiting = { wait -> if (wait != null) publish { it.copy(
                            phase = AnimePreparationPhase.WAITING, detail = "等待首帧准备资源，原片缓存已保留。") } }) {
                        policy.withDecodeAdmission(optional = true, promoted = { false }) {
                            if (!mayPrepare()) return@withDecodeAdmission false
                            publish { it.copy(phase = AnimePreparationPhase.DECODING, detail = null) }
                            engine.prepareStartup(startup, position, budget.nextEpisodeBytes, ready.validUntil)
                        }
                    }
                } == true
                if (decoded) ready.decoderAsset = startup
                if (!mayPrepare()) return@nextMedia
                val cacheReady = budget.nextEpisodeDurationMs == 0L ||
                    result.bufferedDurationMs?.let { it >= budget.nextEpisodeDurationMs } == true
                val complete = cacheReady && (!settings.predecodeNextEpisode || decoded)
                ready.mediaSettled = true
                publish { it.copy(startupFramePrepared = decoded,
                    phase = if (complete) AnimePreparationPhase.READY else AnimePreparationPhase.PARTIAL,
                    detail = when {
                        settings.predecodeNextEpisode && !decoded -> "首帧尚未就绪；已准备的原片仍保留。"
                        result.detail != null -> result.detail
                        !cacheReady && result.bufferedDurationMs == null -> "已缓存的字节不能换算为可靠时长。"
                        !cacheReady -> "已准备部分原片，达到本次容量或可用片段边界。"
                        else -> null
                    }) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (!mayPrepare()) return@nextMedia
                ready.mediaSettled = true
                publish { it.copy(phase = AnimePreparationPhase.PARTIAL,
                    detail = "媒体准备暂未完成，已解析的播放地址仍可使用。") }
            } finally {
                if (cacheNextJob === job) cacheNextJob = null
            }
        }
        cacheNextJob?.start()
    }

    private fun defaultPreparationOption(subjectId: String, episodeId: String): AnimeEpisodeOption? =
        fairPlaybackOptions(subjectId, episodeId, automaticPlaybackLines(episodeId))
            .firstOrNull()

    private fun explicitPlaybackSource(subjectId: String): String? = mutableState.value.sourceBindings.firstOrNull {
        it.subjectId == subjectId && it.preferred && it.preferredExplicitly
    }?.catalog?.sourceId

    private fun preparationStillCurrent(target: PreparationTarget): Boolean {
        val budget = preparationBudget()
        return target.budget.nextEpisodeLeadMs == budget.nextEpisodeLeadMs &&
            target.budget.nextEpisodeDurationMs == budget.nextEpisodeDurationMs &&
            target.budget.nextEpisodeBytes == budget.nextEpisodeBytes &&
            mutableState.value.detail?.subject?.id == target.subjectId && mutableState.value.settings.prepareNextEpisode &&
            mutableState.value.settings.sameNextPreparation(target.settings) &&
            mutableState.value.settings.preferredSourceHeight == target.preferredHeight &&
            playerInstance?.state?.value?.timelineGeneration == target.timelineGeneration &&
            active?.let(::nextEpisode)?.id == target.episodeId &&
            target.route == playbackHistoryRoute(target.option.sourceId) &&
            target.revision == discoverySourceRevision(target.option.sourceId) &&
            target.explicitSourceId == explicitPlaybackSource(target.subjectId) &&
            currentPlaybackLine(target.episodeId, target.option) != null &&
            recentPlaybackLineFailure(target.episodeId, target.option) == null
    }

    private fun freshPreparation(value: PreparedPlayback): Boolean =
        monotonicTimeMillis() < value.validUntil && (value.asset.expiresAt?.let { it > wallTimeMillis() } != false) &&
            preparationStillCurrent(value.target)

    private fun invalidatePreparation() {
        if (preparationTarget?.let(::preparationStillCurrent) == false) clearPreparation()
    }

    private fun releasePreparedDecoder() {
        prepared?.let { ready ->
            listOfNotNull(ready.decoderAsset, ready.cacheAsset).distinctBy { it.id }.forEach { asset ->
                playerInstance?.discardStartup(asset)
            }
            prepared?.decoderAsset = null
            prepared?.cacheAsset = null
            mutableState.update { it.copy(nextEpisodePreparation =
                it.nextEpisodePreparation?.copy(startupFramePrepared = false)) }
        }
    }

    private fun releasePreparation(keepDecoder: PreparedPlayback? = null, keepStatus: Boolean = false) {
        if (keepDecoder == null || keepDecoder !== prepared) releasePreparedDecoder()
        preparationTarget = null
        prepareJob = null
        cacheNextJob?.cancel()
        cacheNextJob = null
        prepared = null
        if (!keepStatus) mutableState.update { it.copy(nextEpisodePreparation = null) }
    }

    private fun clearPreparation(keepDecoder: PreparedPlayback? = null) {
        val job = prepareJob
        releasePreparation(keepDecoder)
        job?.cancel()
    }

    private fun hasRetryableAssociation(snapshot: AnimeScreenState) =
        snapshot.sourceAggregation.values.any { it.phase == AnimeAssociationPhase.UNAVAILABLE }

    private fun canRetryPlaybackDiscovery(snapshot: AnimeScreenState) =
        !playbackDiscoveryRetried && hasRetryableAssociation(snapshot)

    private fun canFailOverStartup(owner: ActivePlayback): Boolean {
        val snapshot = mutableState.value
        return active === owner && !owner.playbackStarted && !owner.recoveryHandled &&
            owner.libraryEpoch == library.state.value.restoreEpoch && snapshot.detail?.subject?.id == owner.detail.subject.id &&
            !snapshot.resolving && pending == null && snapshot.settings.automaticSourceRecovery &&
            snapshot.settings.automaticRecoveryAttempts > 0 &&
            (!sourceRecoveryWindowStarted || monotonicTimeMillis() < autoPlaybackDeadline) &&
            automaticPlaybackLines(owner.episode.id, snapshot).any {
                !samePlaybackLine(it, owner.option) && optionKey(it) !in recoveryOptions
            }
    }

    private fun canResumeRecovery(receipt: RecoveryResume, option: AnimeEpisodeOption, asset: AnimePlaybackAsset): Boolean {
        if (asset.id == receipt.assetId) return true
        val previous = receipt.playback
        if (receipt.assetId != previous.asset.id) return false
        if (animeContentIdentity(previous.asset.url) == animeContentIdentity(asset.url)) return true
        val oldCatalog = previous.option.catalog
        if (oldCatalog.sourceId != option.catalog.sourceId || oldCatalog.id != option.catalog.id ||
            oldCatalog.url != option.catalog.url) return false
        val sharedCatalogue = mutableState.value.sourceCatalogs.any {
            it.title.sourceId == oldCatalog.sourceId && it.title.id == oldCatalog.id && it.title.url == oldCatalog.url &&
                (it.sameWorkRoutes || hasLmmCatalogWorkId(it.title))
        }
        if (!sharedCatalogue) return false
        val previousEdition = previous.option.episode.edition.trim().takeIf { it.isNotEmpty() } ?: return false
        if (!previousEdition.equals(option.episode.edition.trim(), ignoreCase = true)) return false
        val duration = receipt.durationMs ?: return false
        val key = playbackHistoryKey(previous.detail.subject.id, previous.episode.id, option) ?: return false
        val observed = currentPlaybackMeasurements(key, option)?.takeIf { it.durationAssetId == asset.id }?.durationMs
            ?: return false
        return receipt.positionMs < observed && kotlin.math.abs(observed - duration) <= 1_000L
    }

    private fun recoverPlayback(owner: ActivePlayback) {
        val playback = playerInstance?.state?.value
            ?.takeIf { it.phase == AnimePlayerPhase.ERROR && belongsTo(owner, it) } ?: return
        if (!playback.sourceRecoveryAllowed || owner.localMedia != null || active !== owner ||
            pending != null || mutableState.value.resolving) return
        val settings = mutableState.value.settings
        // The saved count bounds retries of one media request in the player, not how many
        // independently verified same-episode candidates may be tried. Zero still opts out.
        if (settings.automaticRecoveryAttempts == 0) return
        val canSwitchSource = settings.automaticSourceRecovery
        val refreshFormat = playback.refreshSourceOnStartupFailure && playback.firstFrameRendered == false
        val canRetryQuality = owner.explicitResolutionId == null && owner.asset.resolutions.isNotEmpty()
        val lineKey = optionKey(owner.option)
        val canRefreshLine = lineKey !in playbackStartupRefreshes && !owner.playbackStarted &&
            (refreshFormat || canRetryQuality) && owner.asset.route == playbackHistoryRoute(owner.option.sourceId) &&
            owner.asset.sourceRevision == discoverySourceRevision(owner.option.sourceId) &&
            owner.sourceRevision == sourceRevision(owner.option.sourceId) &&
            currentPlaybackLine(owner.episode.id, owner.option) != null
        if (!canRefreshLine && !canSwitchSource) return
        if (!sourceRecoveryWindowStarted) {
            sourceRecoveryWindowStarted = true
            // Native preparation has its own buffering limit. Its first terminal failure
            // gets one recovery window even when no frame rendered; never renew per source.
            autoPlaybackDeadline = monotonicTimeMillis() + 45_000L
        }
        if (monotonicTimeMillis() >= autoPlaybackDeadline) {
            mutableState.update { it.copy(playbackRecoveryStopReason = "本轮自动恢复已到时限，可手动重试本集。") }
            return
        }
        if (recoveryResume == null) {
            val saved = library.progressFor(owner.detail.subject.id, owner.episode.id)?.takeUnless { it.completed }
            recoveryResume = if (owner.playbackStarted && maySavePlaybackPosition(owner, playback)) {
                RecoveryResume(owner, owner.asset.id, playback.positionMs.coerceAtLeast(0L),
                    playback.durationMs.takeIf { it > 0L }, playback.playWhenReady)
            } else RecoveryResume(owner, saved?.assetId ?: owner.asset.id, saved?.positionMs ?: 0L,
                saved?.durationMs?.takeIf { it > 0L }, playback.playWhenReady)
        }
        recoveryOptions += lineKey
        if (canRefreshLine) {
            // Refresh the original line once alongside available alternatives. Keep its
            // exact quality only for that line; all work shares the same recovery deadline.
            playbackStartupRefreshes += lineKey
            ++autoPlayGeneration
            autoPlayJob?.cancel()
            resolveAndPlay(owner.episode.id, owner.option, attempted = recoveryOptions.toSet(), startupRefresh = owner)
            return
        }
        val hasAlternative = automaticPlaybackLines(owner.episode.id).any { optionKey(it) !in recoveryOptions }
        val discovery = mutableState.value
        if (!hasAlternative && associationEpisodeId == owner.episode.id && !discovery.sourceAggregationRunning && !discovery.episodeOptionsLoading &&
            !canRetryPlaybackDiscovery(discovery)) {
            val reason = if (hasRetryableAssociation(discovery))
                "本集有来源尚未完成核对，可在来源面板查看并重试。"
            else "当前已确认的本集线路暂不可用，可选择线路重试。"
            mutableState.update { it.copy(playbackRecoveryStopReason = reason) }
            return
        }
        ++autoPlayGeneration
        autoPlayJob?.cancel()
        if (hasAlternative) resolveAndPlay(owner.episode.id, attempted = recoveryOptions.toSet())
        else autoAssociateAndPlay(owner.episode.id, attempted = recoveryOptions.toSet(),
            lastFailure = playback.error?.let { AnimePlaybackFailure(owner.option.sourceId, owner.option.sourceName, "播放视频", it) })
    }

    fun updatePlaybackSettings(change: (AnimePlaybackSettings) -> AnimePlaybackSettings) = mutate {
        library.updateSettings(change)
        val settings = library.state.value.settings
        playerInstance?.configure(playerOptions(settings))
        if (!settings.prepareNextEpisode || preparationTarget?.settings?.let { !it.sameNextPreparation(settings) } == true)
            clearPreparation()
        active?.nextPreparationNotBefore = 0L
    }

    fun preparationBudget(settings: AnimePlaybackSettings = mutableState.value.settings): AnimePreparationBudget =
        resolveAnimePreparationBudget(settings, enhancementCenter.resources.state.value,
            enhancementCenter.resources.usage.value.resources)

    private fun configureEnhancementPolicy() {
        val engine = playerInstance ?: return
        val owner = active
        val snapshot = engine.state.value
        if (owner != null && belongsTo(owner, snapshot)) {
            val key = animeEnhancementSessionKey(snapshot)
            val policy = enhancementCenter.settings.policy(EnhancementContent.ANIME, key)
            val previous = owner.viewingPolicy
            val effectsChanged = previous != null && !sameViewingEffects(previous, policy)
            // Let an explicit episode/source request finish first. Keep this edit pending
            // so a failed or cancelled request cannot leave disabled effects baked in.
            if (effectsChanged && owner.localMedia != null && mutableState.value.resolving) return
            owner.viewingPolicy = policy
            val preparationChanged = previous != null &&
                (PreparationMode.SMART in previous.preparation) != (PreparationMode.SMART in policy.preparation)
            if (effectsChanged || preparationChanged) {
                // Retire completion callbacks immediately; a late result cannot undo a viewing switch.
                cancelOnlineVideo(clear = true, retainReusable = true)
                onlineVideoNotBefore = 0L
                onlineVideoCost = 0L
            }
            if (effectsChanged && owner.localMedia != null) {
                // Select a completed matching version or its leased original at the same position.
                // Disabling an effect must not keep playing it baked into an offline file.
                openDownload(owner.localMedia.downloadId, fallback = null,
                    continuation = PlaybackReceipt(owner, snapshot.positionMs, snapshot.playWhenReady))
                return
            }
        }
        engine.configure(playerOptions(mutableState.value.settings))
    }

    private fun sameViewingEffects(a: EnhancementPolicy, b: EnhancementPolicy): Boolean {
        val pictureA = a.realtimeEnhancementEnabled && a.tier != QualityTier.ORIGINAL
        val pictureB = b.realtimeEnhancementEnabled && b.tier != QualityTier.ORIGINAL
        val framesA = a.realtimeInterpolationEnabled && a.frameMode != FrameMode.ORIGINAL
        val framesB = b.realtimeInterpolationEnabled && b.frameMode != FrameMode.ORIGINAL
        return pictureA == pictureB && framesA == framesB &&
            (!pictureA || a.tier == b.tier && a.scale == b.scale && a.modelId == b.modelId) &&
            (!framesA || a.frameMode == b.frameMode && a.frameQuality == b.frameQuality &&
                a.customFps == b.customFps && a.detectScenes == b.detectScenes)
    }

    private fun playerOptions(settings: AnimePlaybackSettings): AnimePlayerOptions {
        val snapshot = playerInstance?.state?.value?.takeIf { value -> active?.let { belongsTo(it, value) } != false }
        val key = active?.let { "anime:${it.asset.id}:${it.playerGeneration}" } ?: snapshot?.let(::animeEnhancementSessionKey)
        val policy = enhancementCenter.settings.policy(EnhancementContent.ANIME, key)
        // A resource projection may temporarily remove the live request. Only a real
        // viewing choice or session change permits a failed interpolation attempt again.
        val previousIntent = motionIntentPolicy
        if (motionIntentSession != key || previousIntent == null || !sameViewingEffects(previousIntent, policy)) {
            realtimeMotionIntent++
            motionIntentSession = key
            motionIntentPolicy = policy
        }
        val p95 = key?.let { enhancementCenter.telemetry.state.value.sessions[it]?.gpuP95Millis }
        val selected = selectRealtimeEnhancement(policy, enhancementCenter.resources.usage.value.resources,
            snapshot?.sourceFps, if (key != null && reducedAutoEnhancementSession == key) Double.MAX_VALUE else p95,
            preferredRealtimeModel(policy, snapshot))
        if (policy.tier == QualityTier.AUTO && policy.modelId == null &&
            selected.mode == dev.mangaflow.anime.player.AnimeVideoEnhancementMode.FAST &&
            p95 != null) reducedAutoEnhancementSession = key
        val realtimeFrames = preferredRealtimeFrames(policy).takeIf { active?.localMedia?.enhancedVersion != true }
        val prepared = preferredOnlineModel(policy, selected.mode)
            ?.takeIf { active?.localMedia == null && realtimeFrames == null &&
                selected.mode != AnimeVideoEnhancementMode.MODEL }
        // A candidate model is not a prepared frame. The Android shader chooses a matching
        // cached frame before its CNN; all other frames retain the selected realtime path.
        val realtimeEnabled = active?.localMedia?.enhancedVersion != true
        val preparation = preparationBudget(settings)
        return AnimePlayerOptions(
        backgroundAudio = settings.backgroundAudio,
        bufferMinMs = settings.bufferMinMs,
        bufferMaxMs = settings.bufferMaxMs,
        bufferRefillNearTarget = settings.bufferRefillNearTarget,
        bufferStartMs = settings.bufferStartMs,
        bufferRebufferMs = settings.bufferRebufferMs,
        automaticRecoveryAttempts = settings.automaticRecoveryAttempts,
        videoEnhancement = if (realtimeEnabled) selected.mode else AnimeVideoEnhancementMode.OFF,
        enhancementScale = if (realtimeEnabled) selected.scale else 1,
        enhancementModel = selected.model.takeIf { realtimeEnabled },
        preparedVideoModel = prepared,
        realtimeFrames = realtimeFrames,
        realtimeMotionIntent = realtimeMotionIntent,
        bufferForwardBytes = preparation.forwardBytes,
        bufferBackBytes = preparation.backBytes,
        )
    }

    private fun preferredRealtimeModel(policy: EnhancementPolicy, snapshot: AnimePlayerState?): EngineModel? {
        val inventory = enhancementCenter.inventory.value.models
        if (platformKind in setOf(PlatformKind.ANDROID, PlatformKind.DESKTOP) && policy.scale in 1..2) {
            inventory.firstOrNull {
                it.id == policy.modelId && dev.mangaflow.anime.player.Anime4KOptionalModels.supports(it.engineId)
            }?.let { return it.copy(nativeScale = policy.scale) }
        }
        if (platformKind == PlatformKind.DESKTOP && policy.scale in 1..2) {
            val modelId = policy.modelId ?: when (policy.tier) {
                QualityTier.FINE -> if (inventory.any { it.id == "animejanai-live:performance" })
                    "animejanai-live:performance" else "artcnn:C4F16"
                QualityTier.ULTRA -> if (inventory.any { it.id == "animejanai-live:balanced" })
                    "animejanai-live:balanced" else "artcnn:C4F32"
                else -> return null
            }
            return inventory.firstOrNull {
                it.engineId in setOf(EnhancementEngineIds.DESKTOP_ARTCNN, EnhancementEngineIds.DESKTOP_REALTIME) &&
                    it.id == modelId && it.nativeScale == policy.scale
            }
        }
        val capabilities = snapshot?.capabilities ?: playerInstance?.state?.value?.capabilities.orEmpty()
        if (dev.mangaflow.anime.player.AnimePlayerCapability.MODEL_ENHANCEMENT !in capabilities || platformKind != PlatformKind.ANDROID)
            return null
        val androidModels = inventory.filter {
            it.engineId in setOf(EnhancementEngineIds.ANDROID_VIDEO_ONNX, EnhancementEngineIds.ANDROID_CUGAN_VIDEO) &&
                it.nativeScale == policy.scale && EngineContent.VIDEO in it.contents
        }
        if (policy.modelId != null) return androidModels.firstOrNull { it.id == policy.modelId }
        if (policy.tier !in setOf(QualityTier.FINE, QualityTier.ULTRA)) return null
        return androidModels.firstOrNull { it.engineId == EnhancementEngineIds.ANDROID_CUGAN_VIDEO }
            ?: androidModels.firstOrNull()
    }

    private fun preferredRealtimeFrames(policy: EnhancementPolicy): dev.mangaflow.anime.player.AnimeRealtimeFrameRequest? {
        if (!policy.realtimeInterpolationEnabled ||
            policy.frameMode == dev.mangaflow.enhancement.FrameMode.ORIGINAL ||
            policy.frameQuality != dev.mangaflow.enhancement.FrameQuality.BALANCED) return null
        val engineId = when (platformKind) {
            PlatformKind.DESKTOP -> EnhancementEngineIds.DESKTOP_REALTIME_RIFE
            PlatformKind.ANDROID -> EnhancementEngineIds.ANDROID_RIFE_VULKAN
            else -> return null
        }
        val resources = enhancementCenter.resources.usage.value.resources
        if (resources.memoryPressure == true || (resources.thermalPressure ?: 0) >= 2) return null
        val model = enhancementCenter.inventory.value.frameModels.firstOrNull {
            it.engineId == engineId
        } ?: return null
        val target = when (policy.frameMode) {
            dev.mangaflow.enhancement.FrameMode.FPS60 -> 60.0
            dev.mangaflow.enhancement.FrameMode.FPS120 -> 120.0
            dev.mangaflow.enhancement.FrameMode.CUSTOM ->
                policy.customFps.toDouble().takeIf { it in 1.0..(if (platformKind == PlatformKind.ANDROID) 120.0 else 240.0) }
                    ?: return null
            dev.mangaflow.enhancement.FrameMode.DOUBLE -> null
            dev.mangaflow.enhancement.FrameMode.AUTO -> null
            dev.mangaflow.enhancement.FrameMode.ORIGINAL -> return null
        }
        return dev.mangaflow.anime.player.AnimeRealtimeFrameRequest(
            model, target, automatic = policy.frameMode == dev.mangaflow.enhancement.FrameMode.AUTO,
        )
    }

    private fun preferredOnlineModel(policy: EnhancementPolicy, mode: AnimeVideoEnhancementMode): EngineModel? {
        if (!policy.realtimeEnhancementEnabled || policy.tier == QualityTier.ORIGINAL ||
            PreparationMode.SMART !in policy.preparation) return null
        val engines = when (platformKind) {
            PlatformKind.ANDROID -> setOf(EnhancementEngineIds.ANDROID_CUGAN_VIDEO, EnhancementEngineIds.ANDROID_VIDEO_ONNX)
            PlatformKind.DESKTOP -> setOf(EnhancementEngineIds.VIDEOJANAI)
            else -> return null
        }
        val candidates = enhancementCenter.inventory.value.models.filter { model ->
            model.engineId in engines &&
                EngineContent.VIDEO in model.contents && model.nativeScale == policy.scale
        }
        if (policy.modelId != null) return candidates.firstOrNull { it.id == policy.modelId }
        if (mode == AnimeVideoEnhancementMode.FAST ||
            policy.tier !in setOf(QualityTier.AUTO, QualityTier.BALANCED, QualityTier.FINE, QualityTier.ULTRA)) return null
        return candidates.firstOrNull { it.engineId == EnhancementEngineIds.ANDROID_CUGAN_VIDEO }
            ?: candidates.firstOrNull()
    }

    private fun cancelOnlineVideo(clear: Boolean, retainReusable: Boolean = false) {
        ++onlineVideoGeneration
        onlineVideoJob?.cancel()
        if (clear) playerInstance?.discardPreparedVideo(retainReusable = retainReusable)
    }

    private fun onlineResultBudget(): Long {
        val shared = enhancementCenter.resources.state.value.diskCacheBytes.coerceAtLeast(0L)
        return if (platformKind == PlatformKind.DESKTOP) shared else minOf(ONLINE_ANIME_RESULT_BYTES, shared)
    }

    private fun prepareOnlineVideo(owner: ActivePlayback, snapshot: AnimePlayerState, stableFor: Long) {
        val engine = playerInstance ?: return
        val configuration = playerOptions(mutableState.value.settings)
        val model = configuration.preparedVideoModel
        val windowTarget = preparationBudget().onlineWindowMs
        val resultBudget = onlineResultBudget()
        val timeline = snapshot.sessionGeneration to snapshot.timelineGeneration
        if (onlineVideoTimeline != timeline || onlineVideoModel != model || onlineVideoWindowTarget != windowTarget ||
            onlineVideoResultBudget != resultBudget) {
            val canRetain = onlineVideoTimeline == timeline && resultBudget > 0L && resultBudget >= onlineVideoResultBudget
            onlineVideoTimeline = timeline
            onlineVideoModel = model
            onlineVideoNotBefore = 0L
            onlineVideoCost = 0L
            onlineVideoWindowTarget = windowTarget
            onlineVideoResultBudget = resultBudget
            cancelOnlineVideo(clear = true, retainReusable = canRetain)
        }
        val resources = enhancementCenter.resources.usage.value.resources
        if (!foreground || playbackBusy(snapshot) || resources.memoryPressure == true || (resources.thermalPressure ?: 0) >= 2 ||
            snapshot.phase == AnimePlayerPhase.ERROR || snapshot.ended || owner.localMedia != null) {
            cancelOnlineVideo(clear = true)
            engine.noteVideoPreparationIssue(when {
                resources.memoryPressure == true -> "内存压力较高，暂缓在线增强准备。"
                (resources.thermalPressure ?: 0) >= 2 -> "设备温度较高，暂缓在线增强准备。"
                playbackBusy(snapshot) -> "优先恢复当前播放，稍后再准备增强片段。"
                else -> null
            })
            return
        }
        if (!snapshot.isPlaying || snapshot.playbackSuppressed) {
            cancelOnlineVideo(clear = false)
            return
        }
        val session = animeEnhancementSessionKey(snapshot)
        val policy = enhancementCenter.settings.policy(EnhancementContent.ANIME, session)
        if (model == null) {
            engine.noteVideoPreparationIssue(when {
                policy.tier == QualityTier.ORIGINAL || !policy.realtimeEnhancementEnabled -> null
                PreparationMode.SMART !in policy.preparation -> "在线预处理未开启。"
                configuration.realtimeFrames != null -> "当前使用实时补帧；这条处理路径暂不同时准备在线增强窗口。"
                configuration.videoEnhancement == AnimeVideoEnhancementMode.MODEL -> "当前使用实时模型增强；在线窗口准备暂缓。"
                policy.modelId != null -> "所选模型当前走实时或离线路径，不能生成在线预处理窗口。"
                else -> "尚无兼容的在线预处理模型；实时增强按当前设置继续。"
            })
            return
        }
        if (stableFor < 2_000L || onlineVideoJob?.isActive == true) return
        if (resultBudget <= 0L) {
            engine.noteVideoPreparationIssue("共享缓存额度为零，可在体验策略 → 缓存中调整；原视频继续播放。")
            return
        }
        if (snapshot.durationMs <= 0L || snapshot.hasVideoTracks == false ||
            owner.asset.expiresAt?.let { it - wallTimeMillis() < 30_000L } == true) {
            engine.noteVideoPreparationIssue("当前媒体时长、视频轨或地址有效期不足以准备增强窗口。")
            return
        }
        val path = owner.asset.url.substringBefore('?')
        val supportedMedia = owner.asset.mimeType == "video/mp4" || owner.asset.mimeType?.contains("mpegurl", true) == true ||
            path.endsWith(".mp4", true) || path.endsWith(".m3u8", true)
        if (!supportedMedia) {
            engine.noteVideoPreparationIssue("当前片源格式未接通在线预处理，原视频继续播放。")
            return
        }
        val width = snapshot.videoWidth ?: return
        val height = snapshot.videoHeight ?: return
        val pixels = width.toLong() * height * model.nativeScale * model.nativeScale
        val pixelLimit = if (platformKind == PlatformKind.ANDROID) 2_200_000L else 8_847_360L
        val durationRange = if (platformKind == PlatformKind.ANDROID) 5_000L..15_000L else 1_000L..30_000L
        if (pixels !in 1L..pixelLimit || snapshot.sourceFps?.let { it > 60.0 } == true || windowTarget !in durationRange) {
            engine.noteVideoPreparationIssue(when {
                pixels !in 1L..pixelLimit -> "${width}×${height} 放大 ${model.nativeScale} 倍后的输出超过当前在线处理容量；可降低倍率，或使用实时／离线增强。"
                windowTarget !in durationRange -> "当前内核在线片段支持 ${durationRange.first / 1000}–${durationRange.last / 1000} 秒；已保留所设时长。"
                else -> "当前在线预处理支持不超过 60 fps 的源视频。"
            })
            return
        }
        val now = monotonicTimeMillis()
        if (snapshot.preparedVideoWindow?.let { snapshot.positionMs >= it.endMs } == true) engine.discardPreparedVideo()
        if (now < onlineVideoNotBefore ||
            snapshot.preparedVideoWindow?.let { it.endMs > snapshot.positionMs + 2_000L } == true) return
        val lead = (onlineVideoCost * snapshot.speed.toDouble() + 5_000).toLong().coerceAtLeast(5_000L)
        val duration = windowTarget
        val start = snapshot.positionMs + lead
        val end = minOf(start + duration, snapshot.durationMs)
        if (end - start < durationRange.first) return
        val budgets = enhancementCenter.resources
        val sourceBudget = minOf(ONLINE_ANIME_SOURCE_BYTES, budgets.state.value.prefetchBytes)
        if (sourceBudget < 1024L * 1024) {
            engine.noteVideoPreparationIssue("共享原片预读预算不足，暂缓增强窗口准备。")
            return
        }
        val request = AnimePreparedVideoRequest(owner.asset, timeline.first, timeline.second, start, end, width, height, model,
            sourceBudget, resultBudget)
        val release = budgets.tryAcquireEnhancement(priority = 1, estimatedBytes = 160L * 1024 * 1024,
            sharedRealtimeOwner = "anime-player") ?: run {
            engine.noteVideoPreparationIssue("等待增强处理资源，当前原视频继续播放。")
            return
        }
        engine.noteVideoPreparationIssue(null)
        onlineVideoNotBefore = now + 5_000L
        val generation = onlineVideoGeneration
        onlineVideoJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            fun current() = generation == onlineVideoGeneration && active === owner && onlineVideoTimeline == timeline && onlineVideoModel == model &&
                onlineVideoWindowTarget == windowTarget && onlineVideoResultBudget == resultBudget &&
                engine.state.value.sessionGeneration == timeline.first &&
                engine.state.value.timelineGeneration == timeline.second
            var finished = false
            try {
                finished = withTimeoutOrNull(if (platformKind == PlatformKind.DESKTOP) 60_000L else 25_000L) {
                    budgets.withPrefetchAdmission(owner.asset.sourceId, sourceBudget, { false }) {
                        current() && !playbackBusy() && engine.prepareVideoWindow(request)
                    }
                }?.also { prepared -> if (!prepared && current() && engine.state.value.preparedVideoIssue == null)
                    engine.noteVideoPreparationIssue("这段增强尚未准备好，当前播放继续使用可用画面。") }
                    ?: false.also { if (current())
                        engine.noteVideoPreparationIssue("在线增强准备超时，稍后按实际耗时调整提前量。") }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (current() && engine.state.value.preparedVideoIssue == null)
                engine.noteVideoPreparationIssue("在线增强暂未完成，原视频继续播放。") }
            finally {
                release()
                if (current()) {
                    onlineVideoCost = monotonicTimeMillis() - now
                    onlineVideoNotBefore = monotonicTimeMillis() + if (finished) 1_000L else 10_000L
                }
            }
        }
    }

    private fun observeEnhancement(snapshot: AnimePlayerState) {
        val center = enhancementCenter
        val hasSession = snapshot.assetKey != null && snapshot.phase !in setOf(AnimePlayerPhase.IDLE, AnimePlayerPhase.RELEASED)
        center.resources.setRealtimeEnhancement("anime-player", hasSession && snapshot.isPlaying &&
            !snapshot.playbackSuppressed && (foreground || snapshot.inPictureInPicture) && snapshot.hasVideoTracks != false)
        val key = if (hasSession) animeEnhancementSessionKey(snapshot) else null
        if (observedEnhancementSession != key) {
            observedEnhancementSession?.let(center::endSession)
            observedEnhancementSession = key
            observedEnhancementTimeline = snapshot.timelineGeneration
            reducedAutoEnhancementSession = null
        } else if (observedEnhancementTimeline != snapshot.timelineGeneration && key != null) {
            center.scheduler.cancelSession(key)
            center.telemetry.endSession(key)
            observedEnhancementTimeline = snapshot.timelineGeneration
        }
        if (key == null) return
        val policy = center.settings.policy(EnhancementContent.ANIME, key)
        val selection = selectRealtimeEnhancement(policy, center.resources.usage.value.resources, snapshot.sourceFps,
            center.telemetry.state.value.sessions[key]?.gpuP95Millis, preferredRealtimeModel(policy, snapshot))
        val effect = snapshot.videoEnhancement
        val observation = effect.observation
        val preparedWindow = snapshot.preparedVideoWindow
        val usingPrepared = snapshot.isPlaying && preparedWindow != null && preparedWindow.framesUsed > 0 &&
            monotonicTimeMillis() - preparedWindow.lastUsedAt in 0L..1_000L
        val model = if (usingPrepared) preparedWindow?.modelName else if (snapshot.preEnhanced) "离线增强版本"
        else effect.modelName ?: when (effect.mode) {
            dev.mangaflow.anime.player.AnimeVideoEnhancementMode.OFF -> null
            dev.mangaflow.anime.player.AnimeVideoEnhancementMode.FAST -> "Anime4K S/S"
            dev.mangaflow.anime.player.AnimeVideoEnhancementMode.QUALITY -> "Anime4K M/M"
            dev.mangaflow.anime.player.AnimeVideoEnhancementMode.MODEL -> effect.modelName
        }
        center.telemetry.recordVideo(key, model, snapshot.sourceFps,
            policy.targetFps(snapshot.sourceFps, snapshot.displayRefreshHz),
            if (usingPrepared) preparedWindow?.outputWidth else if (snapshot.preEnhanced) snapshot.videoWidth else effect.outputWidth,
            if (usingPrepared) preparedWindow?.outputHeight else if (snapshot.preEnhanced) snapshot.videoHeight else effect.outputHeight,
            observation?.submittedFrames, observation?.submitThreadCpuNanos,
            presentedFps = snapshot.presentedFps, presentedFrames = snapshot.presentedFrames,
            droppedFrames = snapshot.droppedFrames,
            preparedSeconds = preparedWindow?.let { (it.endMs - maxOf(snapshot.positionMs, it.startMs)).coerceAtLeast(0L) / 1000.0 },
            reason = when {
                usingPrepared -> "正在复用同一原始帧的预处理结果"
                snapshot.preEnhanced -> "正在使用已完成并校验的增强版本"
                preparedWindow != null -> "预处理帧已缓存，按原视频时间与画面匹配"
                snapshot.preparedVideoIssue != null -> "本次在线预处理未完成 · ${snapshot.preparedVideoIssue}"
                else -> effect.detail ?: selection.reason
            },
            gpuSampleSequence = observation?.gpuSampleSequence, gpuExecutionNanos = observation?.gpuExecutionNanos,
            gpuSubmittedAtNanos = observation?.gpuSubmittedAtNanos,
            gpuCompletionObservedAtNanos = observation?.gpuCompletionObservedAtNanos,
            presentedAtNanos = observation?.presentedAtNanos)
        selection.reason?.let { center.telemetry.noteAdjustment(key, EnhancementContent.ANIME, it) }
    }

    fun setSleepTimer(minutes: Int?, atEpisodeEnd: Boolean = false) {
        if (minutes != null && minutes <= 0) { notice("睡眠计时需要大于零的分钟数。"); return }
        sleepJob?.cancel()
        val deadline = minutes?.let { wallTimeMillis() + it * 60_000L }
        mutableState.update { it.copy(sleepDeadlineMs = deadline, sleepAtEpisodeEnd = atEpisodeEnd && deadline == null) }
        if (atEpisodeEnd && deadline == null) clearPreparation()
        if (deadline != null) sleepJob = scope.launch {
            delay((deadline - wallTimeMillis()).coerceAtLeast(0))
            if (mutableState.value.sleepDeadlineMs == deadline) {
                cancelPlaybackResolution()
                clearPreparation()
                playerInstance?.pause()
                flushPlayback()
                mutableState.update { it.copy(sleepDeadlineMs = null, sleepAtEpisodeEnd = false) }
                notice("已到睡眠时间，播放已暂停。")
            }
        }
    }

    fun downloadCurrent() {
        val owner = active ?: run { notice("请先打开要保存的剧集。"); return }
        if (owner.localMedia != null) { notice("当前播放来自已保存的离线剧集。"); return }
        mutate {
            enhancements // Observe new completed originals for the user's after-download policy.
            downloads.setPlaybackBusy(playbackBusy())
            downloads.enqueue(owner.detail.subject, owner.episode, owner.asset)
            notice("已加入离线下载，可在下载列表查看进度。")
        }
    }

    fun pauseDownload(id: String) = mutate { downloads.pause(id) }
    fun resumeDownload(id: String) = mutate { downloads.resume(id) }
    fun removeDownload(id: String) = mutate { downloads.remove(id) }

    fun playDownload(id: String) = openDownload(id, fallback = null)
    fun playOriginalDownload(id: String) = openDownload(id, fallback = null, useOriginal = true)
    fun playEnhancedDownload(id: String, versionId: String) = openDownload(id, fallback = null, versionId = versionId)
    fun enhanceDownload(id: String) { enhancements.enqueue(id) }

    private fun openDownload(id: String, fallback: AnimeLibraryEntry?, versionId: String? = null, useOriginal: Boolean = false,
        continuation: PlaybackReceipt? = null) {
        cancelPlaybackResolution()
        clearPreparation()
        val request = resolveGeneration
        mutableState.update { it.copy(resolving = true, sourceError = null) }
        resolveJob = scope.launch {
            try {
                library.awaitLoaded()
                val original = downloads.playback(id)
                val continuationSession = continuation?.playback?.let { "anime:${it.asset.id}:${it.playerGeneration}" }
                val viewingPolicy = enhancementCenter.settings.policy(EnhancementContent.ANIME, continuationSession)
                val offline = if (useOriginal) original else try { enhancements.playback(original, versionId, viewingPolicy) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { original }
                check(fallback == null || offline.subject.id == fallback.subject.id &&
                    offline.episode.id == fallback.resume?.episodeId && offline.asset.id == fallback.resume?.assetId) {
                    "离线内容与续看记录不一致。"
                }
                if (request != resolveGeneration || continuation != null && active !== continuation.playback) return@launch
                if (continuation != null) {
                    val latestPolicy = enhancementCenter.settings.policy(EnhancementContent.ANIME, continuationSession)
                    if (!sameViewingEffects(viewingPolicy, latestPolicy)) {
                        openDownload(id, fallback = null, continuation = continuation)
                        return@launch
                    }
                    if (continuation.playback.localMedia?.enhancedVersion == false && !offline.localMedia.enhancedVersion) {
                        // Both choices use the exact original; a live-effect edit needs no reopen.
                        playerInstance?.configure(playerOptions(mutableState.value.settings))
                        mutableState.update { it.copy(resolving = false, sourceError = null) }
                        return@launch
                    }
                }
                ++detailGeneration
                detailOpenedAt = null
                ++searchGeneration
                ++previewGeneration
                detailJob?.cancel(); cancelDetailScores(); boundCatalogJob?.cancel(); relatedJob?.cancel(); previewJob?.cancel()
                nativeMetadataJob?.cancel()
                nativeDiscussionOwner = null
                sourceSearchJobs.values.forEach { it.cancel() }
                sourceSearchJobs.clear()
                resumeRequest = null
                val cached = continuation?.playback?.detail ?: metadata.cachedDetail(offline.subject)
                if (request != resolveGeneration || continuation != null && active !== continuation.playback) return@launch
                val detail = cached?.takeIf { it.episodes.any { episode -> episode.id == offline.episode.id } }
                    ?: AnimeDetail(offline.subject, listOf(offline.episode))
                val catalog = AnimeSourceTitle(offline.asset.sourceId, id, offline.subject.title, "",
                    offline.subject.coverUrl, offline.subject.year)
                val native = AnimeSourceEpisode(offline.asset.sourceEpisodeId, offline.episode.title, "",
                    offline.episode.number, offline.episode.kind, edition = offline.asset.edition)
                val option = AnimeEpisodeOption(offline.asset.sourceId, "离线", catalog, native, confirmed = true)
                val previous = library.progressFor(offline.subject.id, offline.episode.id)
                val continuingSnapshot = if (continuation != null) playerInstance?.state?.value
                    ?.takeIf { belongsTo(continuation.playback, it) } else null
                val position = continuingSnapshot?.positionMs ?: continuation?.positionMs
                    ?: previous?.takeIf { !it.completed && it.assetId == offline.asset.id }?.positionMs ?: 0L
                discussion.open(null)
                mutableState.update { it.copy(detail = detail, detailLoading = false, detailError = null,
                    detailFirstConfirmedLineMs = null, nativeMetadataSearch = null,
                    detailScores = emptyList(), detailScoreChecks = emptyMap(),
                    groupSourceCandidates = emptyList(), associationChoices = emptyMap(),
                    detailFromCache = cached != null, relatedSubjects = emptyList(), sourceResults = emptyList(),
                    sourceCatalogs = emptyList(), sourceBindings = emptyList(), sourceCatalogErrors = emptyMap(),
                    episodeOptions = emptyMap(), previewCatalog = null, previewLoading = false,
                    preferredSourceId = null, sourceGroups = emptyMap()) }
                startPlayback(PendingPlayback(detail, offline.episode, option, offline.asset, position, request,
                    playWhenReady = continuingSnapshot?.playWhenReady ?: continuation?.playWhenReady ?: true,
                    localMedia = offline.localMedia, useSourceStart = continuation == null && previous?.completed != true),
                    rememberPrevious = continuation == null,
                    inheritEnhancementSession = continuationSession,
                    explicitEnhancementPolicy = if (versionId != null && offline.localMedia.enhancedVersion)
                        enhancements.state.value.entries.firstOrNull { it.id == versionId }?.policy() else null,
                    expectedViewingPolicy = viewingPolicy.takeIf { continuation != null })
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (request == resolveGeneration) {
                    if (fallback != null) {
                        openDetail(fallback.subject, fallback.resume?.episodeId)
                        notice("原离线文件不可用，已打开该集的关联来源。")
                    } else {
                        if (continuation != null && active === continuation.playback) playerInstance?.pause()
                        mutableState.update { it.copy(resolving = false,
                            sourceError = if (continuation != null) "未能切换观看效果，已暂停播放。${failure.message ?: "请检查原始下载文件。"}"
                                else failure.message ?: "离线文件未能打开，请检查下载列表。") }
                    }
                }
            }
        }
    }

    fun setFollowed(subject: AnimeSubject, followed: Boolean) = mutate {
        library.edit(subject) { it.copy(followed = followed) }
        notice(if (followed) "已加入追番。" else "已取消追番，观看记录仍保留。")
    }

    fun setWatchStatus(subjectId: String, status: AnimeWatchStatus) {
        val subject = subject(subjectId) ?: return
        mutate { library.edit(subject) { it.copy(status = status, followed = true) } }
    }

    fun setFolder(subjectId: String, folder: String) {
        val subject = subject(subjectId) ?: return
        mutate { library.edit(subject) { it.copy(folder = folder.trim()) } }
    }

    fun setPinned(subjectId: String, pinned: Boolean) {
        val subject = subject(subjectId) ?: return
        mutate { library.edit(subject) { it.copy(pinned = pinned) } }
    }

    internal val libraryRestoreEpoch: Long get() = library.state.value.restoreEpoch

    fun clearWatchHistory(subjectId: String, expectedResume: AnimeWatchProgress? = null,
        expectedRevision: Long? = null, expectedRestoreEpoch: Long? = null) =
        clearEntryHistory(subjectId, removeEntry = false) {
            val stored = library.state.value
            val entry = stored.entries[subjectId]
            (expectedRestoreEpoch == null || stored.restoreEpoch == expectedRestoreEpoch) &&
                (expectedRevision == null || entry?.revision == expectedRevision) &&
                (expectedResume == null && expectedRevision == null && expectedRestoreEpoch == null ||
                    entry?.resume == expectedResume)
        }
    fun removeLibraryEntry(subjectId: String) { clearEntryHistory(subjectId, removeEntry = true) }

    private fun clearEntryHistory(subjectId: String, removeEntry: Boolean, isCurrent: () -> Boolean = { true }): Job? {
        if (!isCurrent()) { notice("观看记录已变化，新的观看记录已保留。"); return null }
        if (active?.detail?.subject?.id == subjectId) stopPlayback(discardCheckpoint = true)
        else playbackEpochs[subjectId] = (playbackEpochs[subjectId] ?: 0L) + 1
        if (mutableState.value.detail?.subject?.id == subjectId) cancelPlaybackResolution()
        resumeRequest = resumeRequest?.takeUnless { it.first == subjectId }
        return scope.launch {
            try {
                library.awaitReady()
                check(isCurrent()) { "观看记录已变化，新的观看记录已保留。" }
                library.clearWatchHistory(subjectId, removeEntry, isCurrent)
                if (mutableState.value.detail?.subject?.id == subjectId && removeEntry) {
                    mutableState.update { it.copy(sourceCatalogs = emptyList()) }
                    rebuildOptions()
                }
                notice(if (removeEntry) "已移除这部番的本机记录。" else "已清除观看位置；追番状态与已看标记保留。")
            }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { notice(failure.message ?: "操作尚未保存，请重试。") }
        }
    }

    fun retryAutomaticSources() = sourceOperation("automatic-sources") { sources.extensions.retryAutomaticSources() }
    fun retryAutomaticSource(id: String) = sourceOperation(id) { sources.extensions.retryAutomaticSource(id) }
    fun restoreAutomaticSource(id: String) = sourceOperation(id) { sources.extensions.restoreAutomaticSource(id) }

    fun installSource(id: String) = sourceOperation(id) { sources.extensions.install(id) }
    fun updateSource(id: String) = sourceOperation(id) { sources.extensions.update(id) }
    fun rollbackSource(id: String) = sourceOperation(id) { sources.extensions.rollback(id) }
    fun enableSource(id: String, enabled: Boolean) = sourceOperation(id) { sources.extensions.setEnabled(id, enabled) }
    fun removeSource(id: String) = sourceOperation(id) { sources.extensions.remove(id) }
    fun setSourceOptions(id: String, optionsJson: String) = sourceOperation(id) { sources.extensions.setOptions(id, optionsJson) }

    fun installVideoSources() = sourceOperation("all-video") {
        val failures = sources.extensions.installAllVideo()
        notice(if (failures.isEmpty()) "视频来源已安装，可在来源面板关联番剧。"
            else "来源安装已完成；${failures.size} 个来源失败，可在对应条目重试。")
    }

    private fun sourceOperation(id: String, block: suspend () -> Unit) {
        if (id in mutableState.value.sourceOperationIds || "all-video" in mutableState.value.sourceOperationIds ||
            id == "all-video" && mutableState.value.sourceOperationIds.isNotEmpty()) return
        mutableState.update { it.copy(sourceOperationIds = it.sourceOperationIds + id) }
        scope.launch {
            try { sources.extensions.ready.await(); block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { notice(failure.message ?: "来源操作未完成，原版本已保留。") }
            finally { mutableState.update { it.copy(sourceOperationIds = it.sourceOperationIds - id) } }
        }
    }

    private fun projectRoutes() {
        val settings = routing.state.value
        val ids = sourceHomes.keys + sources.extensions.state.value.map { it.id } + tracking.providerIds
        mutableState.update { it.copy(sourceRoutes = ids.associateWith { id ->
            settings.sourceOverrides[id]?.mode ?: settings.categoryOverrides[dev.mangaflow.net.DownloadCategory.ANIME]
                ?: settings.defaultMode
        }) }
    }

    fun setEpisodeCompleted(episodeId: String, completed: Boolean) {
        val detail = mutableState.value.detail ?: return
        if (detail.episodes.none { it.id == episodeId }) return
        mutate {
            library.edit(detail.subject) { it.copy(completedEpisodeIds =
                if (completed) it.completedEpisodeIds + episodeId else it.completedEpisodeIds - episodeId) }
        }
    }

    fun connectTracking(providerId: String, token: String) {
        if (mutableState.value.syncing) return
        mutableState.update { it.copy(syncing = true, notice = null) }
        scope.launch {
            try {
                val account = tracking.connect(providerId, token.trim())
                clearProviderReviews(providerId)
                refreshAccounts()
                notice("已连接 " + account.displayName + "。在番剧详情中核对进度后再同步。")
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { notice(failure.message ?: "账号连接失败，原账号未替换。") }
            finally { mutableState.update { it.copy(syncing = false) } }
        }
    }

    fun disconnectTracking(providerId: String) {
        if (mutableState.value.syncing) return
        mutate {
            withContext(Dispatchers.IO) { tracking.disconnect(providerId) }
            clearProviderReviews(providerId)
            refreshAccounts()
            notice("账号已断开，本地追番和播放进度保留。")
        }
    }

    private suspend fun refreshAccounts() {
        val (accounts, problems) = withContext(Dispatchers.IO) {
            tracking.providerIds.mapNotNull(tracking::account) to
                tracking.providerIds.mapNotNull(tracking::credentialError)
        }
        mutableState.update { it.copy(accounts = accounts,
            notice = problems.firstOrNull() ?: it.notice) }
    }

    private fun clearProviderReviews(providerId: String) {
        val ids = mutableState.value.reviews.filter { it.providerId == providerId }.map { it.id }.toSet()
        ids.forEach(reviewSnapshots::remove)
        mutableState.update { it.copy(reviews = it.reviews.filterNot { review -> review.id in ids }) }
    }

    fun reviewTracking(providerId: String, remoteSubjectId: String? = null) {
        if (mutableState.value.detail?.subject?.externalIds?.get("native-source") != null) {
            notice("来源独有条目只保留本机进度；确认标准番剧身份前不向追番服务写入。")
            return
        }

        val detail = mutableState.value.detail ?: return
        if (detail.episodes.isEmpty() || mutableState.value.syncing) return
        val owner = detailGeneration
        val account = mutableState.value.accounts.firstOrNull { it.providerId == providerId }
            ?: run { notice("请先连接这个追番账号。"); return }
        mutableState.update { it.copy(syncing = true, notice = null) }
        scope.launch {
            try {
                library.awaitReady()
                val inspectedStore = library.state.value
                val inspectedLocal = inspectedStore.entries[detail.subject.id] ?: AnimeLibraryEntry(detail.subject)
                val inspectedReference = supplementarySubject(detail)
                fun currentReview() = currentDetail(owner, detail.subject.id) &&
                    library.state.value.restoreEpoch == inspectedStore.restoreEpoch &&
                    (library.state.value.entries[detail.subject.id]?.revision ?: 0L) == inspectedLocal.revision &&
                    mutableState.value.detail?.episodes == detail.episodes &&
                    mutableState.value.detail?.let(::supplementarySubject)?.id == inspectedReference?.id &&
                    tracking.account(providerId)?.ownerId == account.ownerId
                check(currentReview()) { "作品或本地记录已变化，请重新核对追番进度。" }
                val reviewedDetail = if (detail.subject.canonicalDoubanId() != null) {
                    check(providerId == "anime-bangumi") { "豆瓣条目的跨平台进度仍需独立核对；当前可同步已确认的 Bangumi 正片记录。" }
                    val reference = supplementarySubject(detail)?.takeIf { it.canonicalBangumiId() != null }
                        ?: error("当前尚未确认同一季的 Bangumi 资料，本机追番和续播记录会正常保存。")
                    val remoteCatalogue = metadata.cachedDetail(reference) ?: metadata.detail(reference).let { result ->
                        AnimeDetail(reference, result.episodes ?: error(result.episodeError ?: "无法读取远端剧集目录。"))
                    }
                    val main = detail.episodes.filter { it.kind == AnimeEpisodeKind.MAIN }
                    val mapped = main.map { local ->
                        check(local.metadataSubjectId == reference.id && local.metadataEpisodeId != null) {
                            "部分剧集尚未确认远端身份，不能按列表位置同步。"
                        }
                        val remoteEpisode = remoteCatalogue.episodes.singleOrNull { it.id == local.metadataEpisodeId &&
                            it.subjectId == reference.id && it.kind == AnimeEpisodeKind.MAIN }
                            ?: error("远端剧集身份已变化，请刷新资料后核对。")
                        local.copy(number = remoteEpisode.number, numberInSubject = remoteEpisode.numberInSubject)
                    }
                    check(mapped.map { it.metadataEpisodeId }.distinct().size == mapped.size) { "远端剧集对应不唯一，请重新核对。" }
                    detail.copy(episodes = detail.episodes.filterNot { it.kind == AnimeEpisodeKind.MAIN } + mapped)
                } else detail
                val remoteId = remoteSubjectId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: library.trackingTarget(detail.subject.id, providerId, account.ownerId)
                    ?: (if (providerId == "anime-bangumi") detail.subject.externalIds["bangumi"]
                        ?: detail.subject.id.removePrefix("bangumi:").takeIf { value -> value.isNotBlank() && value.all(Char::isDigit) } else null)
                    ?: error("请填写这个平台的番剧 ID，并确认对应的是同一季。")
                require(remoteId.isNotBlank() && remoteId.all(Char::isDigit)) { "番剧 ID 应为该平台的数字 ID。" }
                if (detail.subject.canonicalDoubanId() != null) check(remoteId == supplementarySubject(detail)?.canonicalBangumiId()?.toString()) {
                    "同步目标与已确认的本季资料不同，请先更正资料关联。"
                }
                val remote = tracking.read(providerId, remoteId)
                check(currentReview()) { "资料关联或本地记录已变化，未应用旧的远端结果，请重新核对。" }
                check(tracking.account(providerId)?.ownerId == remote.ownerId && remote.ownerId == account.ownerId) {
                    "追番账号已经变化，请重新核对。"
                }
                library.setTrackingTarget(detail.subject.id, providerId, account.ownerId, remoteId, isCurrent = ::currentReview)
                check(currentReview()) { "资料关联或本地记录已变化，请重新核对。" }
                installReview(reviewedDetail, remote, inspectedLocal, inspectedStore.restoreEpoch)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { notice(failure.message ?: "远端进度读取失败，现有记录和差异保留。") }
            finally { mutableState.update { it.copy(syncing = false) } }
        }
    }

    private fun installReview(detail: AnimeDetail, remote: AnimeTrackingSnapshot, local: AnimeLibraryEntry, epoch: Long) {
        val localNumbers = mainNumbers(detail, local.completedEpisodeIds)
        val localStatus = local.status.takeIf { local.followed }
        val previousIds = mutableState.value.reviews.filter {
            it.subjectId == detail.subject.id && it.providerId == remote.providerId
        }.map { it.id }.toSet()
        previousIds.forEach(reviewSnapshots::remove)
        val review = AnimeTrackingReview(randomUuid(), detail.subject.id, detail.subject.title, remote.providerId,
            localStatus, remote.status, localNumbers, remote.completedNumbers, remote.remoteSubjectId, local.revision)
        val same = localStatus == remote.status && localNumbers == remote.completedNumbers
        if (!same) reviewSnapshots[review.id] = ReviewContext(detail, remote, epoch)
        mutableState.update { it.copy(reviews = it.reviews.filterNot { row -> row.id in previousIds } +
            if (same) emptyList() else listOf(review)) }
        notice(if (same) "这个平台的追番状态与正片已看记录一致。秒级续播仍由本机保存。" else "发现差异，请分别选择追番状态和已看集数。")
    }

    fun applyReview(reviewId: String, statusChoice: AnimeSyncChoice, episodesChoice: AnimeSyncChoice, publish: Boolean = false) {
        if (mutableState.value.syncing) return
        val review = mutableState.value.reviews.firstOrNull { it.id == reviewId } ?: return
        val inspected = reviewSnapshots[reviewId] ?: return
        if (statusChoice == AnimeSyncChoice.MERGE) { notice("追番状态请选择本机或远端。"); return }
        mutableState.update { it.copy(syncing = true, notice = null) }
        scope.launch {
            try {
                library.awaitReady()
                val before = library.state.value.entries[review.subjectId] ?: AnimeLibraryEntry(inspected.detail.subject)
                check(library.state.value.restoreEpoch == inspected.restoreEpoch) { "本地记录已恢复，请重新核对差异。" }
                check(before.revision == review.localRevision) { "本地记录已变化，请重新核对，尚未应用旧选择。" }
                check(tracking.account(review.providerId)?.ownerId == inspected.remote.ownerId) {
                    "账号已变化，请重新核对。"
                }
                val nextStatus = if (statusChoice == AnimeSyncChoice.REMOTE) review.remoteStatus else review.localStatus
                val numbers = when (episodesChoice) {
                    AnimeSyncChoice.LOCAL -> review.localNumbers
                    AnimeSyncChoice.REMOTE -> review.remoteNumbers
                    AnimeSyncChoice.MERGE -> review.localNumbers + review.remoteNumbers
                }
                val main = inspected.detail.episodes.filter { it.kind == AnimeEpisodeKind.MAIN }
                val selected = numbers.map { number ->
                    main.singleOrNull { it.number == number }?.id
                        ?: error("远端包含本季目录中无法唯一对应的集数，请刷新目录并人工核对。")
                }.toSet()
                val mainIds = main.map { it.id }.toSet()
                // Remote writes are never hidden behind a local apply. Each provider revalidates its revision.
                val receipt = if (publish) tracking.write(inspected.remote, nextStatus, numbers) else null
                if (receipt != null) {
                    check(receipt.ownerId == inspected.remote.ownerId && receipt.remoteSubjectId == inspected.remote.remoteSubjectId) {
                        "远端返回的账号或番剧身份变化，请重新核对。"
                    }
                }
                try {
                    library.edit(inspected.detail.subject, expectedRevision = review.localRevision) {
                        check(library.state.value.restoreEpoch == inspected.restoreEpoch) { "本地记录已恢复，未覆盖新的进度。" }
                        it.copy(followed = nextStatus != null, status = nextStatus ?: it.status,
                            completedEpisodeIds = (it.completedEpisodeIds - mainIds) + selected)
                    }
                } catch (failure: Exception) {
                    if (receipt != null) throw IllegalStateException(
                        "远端已保存；本地应用失败，原本地记录保留。请重新核对差异。", failure)
                    throw failure
                }
                reviewSnapshots.remove(reviewId)
                mutableState.update { it.copy(reviews = it.reviews.filterNot { row -> row.id == reviewId }) }
                notice(if (publish) "已同步选定字段。本机修改可撤销；远端撤回需重新核对后同步。" else "已应用到本机，可以撤销。远端记录未改动。")
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { notice(failure.message ?: "尚未完成应用，请重新核对后重试。") }
            finally { mutableState.update { it.copy(syncing = false) } }
        }
    }

    fun undo() = mutate {
        if (!library.undo()) notice("记录已有后续变化，未覆盖新的操作。")
        else {
            val detail = mutableState.value.detail
            if (detail != null) {
                val restored = library.state.value.entries[detail.subject.id]?.catalogDetail
                if (restored != null && (restored.metadataSubject != detail.metadataSubject || restored.episodes != detail.episodes)) {
                    // An undone metadata choice invalidates its still-running supplement request.
                    openDetail(restored.subject, resumeRequest?.takeIf { it.first == restored.subject.id }?.second,
                        visitedDetail = restored, sourceCandidates = mutableState.value.groupSourceCandidates)
                    notice("已撤销上一步本机修改。")
                    return@mutate
                }
                mutableState.update { it.copy(sourceCatalogs = it.sourceCatalogs.filter { catalog ->
                    stillBound(detail.subject.id, catalog.title)
                }, sourceCatalogErrors = emptyMap()) }
                rebuildOptions()
                startSourceAggregation(detail, force = true)
            }
            notice("已撤销上一步本机修改。")
        }
    }

    fun setSourceRoute(sourceId: String, mode: DownloadRouteMode) {
        if (mode == DownloadRouteMode.SYSTEM) return
        mutate {
            withContext(Dispatchers.IO) {
                val settings = routing.state.value
                val existing = settings.sourceOverrides[sourceId]
                val endpoint = if (existing?.mode == DownloadRouteMode.PROXY) existing.proxy else settings.proxy
                routing.setSource(sourceId, SourceNetworkSettings(mode, if (mode == DownloadRouteMode.PROXY) endpoint else null))
            }
            notice("后续解析与播放使用新路线；当前播放保持原路线。")
        }
    }

    fun dismissNotice() = mutableState.update { it.copy(notice = null) }

    private fun mainNumbers(detail: AnimeDetail, completed: Set<String>): Set<Double> =
        detail.episodes.filter { it.kind == AnimeEpisodeKind.MAIN && it.id in completed }.map { it.number }.toSet()

    private fun subject(id: String): AnimeSubject? = mutableState.value.detail?.subject?.takeIf { it.id == id }
        ?: library.state.value.entries[id]?.subject

    internal fun stillBound(subjectId: String, title: AnimeSourceTitle): Boolean =
        library.state.value.bindings.any { it.subjectId == subjectId && it.catalog.sourceId == title.sourceId && it.catalog.id == title.id && it.catalog.url == title.url }

    private fun currentDetail(generation: Long, subjectId: String) =
        generation == detailGeneration && mutableState.value.detail?.subject?.id == subjectId

    private fun notice(message: String) = mutableState.update { it.copy(notice = message) }

    private fun mutate(block: suspend () -> Unit) {
        scope.launch {
            try { library.awaitReady(); block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { notice(failure.message ?: "操作尚未保存，请重试。") }
        }
    }
}
