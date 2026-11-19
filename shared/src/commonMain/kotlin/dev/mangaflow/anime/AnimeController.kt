package dev.mangaflow.anime

import dev.mangaflow.anime.player.AnimePlayer
import dev.mangaflow.anime.player.AnimePlayerPhase
import dev.mangaflow.anime.player.AnimePlayerRequest
import dev.mangaflow.anime.player.AnimePlayerState
import dev.mangaflow.anime.player.AnimePlayerOptions
import dev.mangaflow.anime.player.AnimeLocalMedia
import dev.mangaflow.anime.player.createAnimePlayer
import dev.mangaflow.anime.sources.AnimeMetadataRepository
import dev.mangaflow.anime.sources.AnimeDiscoveryRepository
import dev.mangaflow.anime.sources.AnimeSourceBrowseController
import dev.mangaflow.anime.sources.AnimeSourceRepository
import dev.mangaflow.anime.sources.AnimeTrackingRepository
import dev.mangaflow.anime.sources.animeContentIdentity
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.SourceNetworkSettings
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.randomUuid
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.platform.monotonicTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Application-owned data and playback. Screens keep only disclosure, drafts and scroll state. */
class AnimeController(private val context: PlatformContext, private val scope: CoroutineScope) {
    private val metadata = AnimeMetadataRepository(context)
    private val sources = AnimeSourceRepository(context)
    private val unifiedSearch = AnimeUnifiedSearch(scope, metadata, sources)
    internal val searchState = unifiedSearch.state
    private val discovery = AnimeDiscoveryRepository(context)
    val sourceBrowse = AnimeSourceBrowseController(context, scope, sources)
    private val tracking = AnimeTrackingRepository(context)
    private val library = AnimeLibraryStore.get(context)
    private val routing = DownloadRoutingStore.get(context)
    private val mutableState = MutableStateFlow(AnimeScreenState())
    val state = mutableState.asStateFlow()
    val extensions get() = sources.extensions
    val danmaku by lazy { dev.mangaflow.anime.danmaku.AnimeDanmakuController(context, scope, sources.extensions) }
    private val downloadsOwner = lazy { dev.mangaflow.anime.downloads.AnimeDownloadStore(context, scope).also {
        it.setForeground(foreground)
        it.setPlaybackBusy(playbackBusy())
    } }
    val downloads get() = downloadsOwner.value
    val sourceHomes: Map<String, String> get() = sources.providers.associate { it.id to it.homeUrl } +
        dev.mangaflow.anime.sources.lanerc.LANERC_SOURCE_DIRECTORY.filter { descriptor ->
            sources.extensions.state.value.any { it.id == descriptor.id && it.installed && it.enabled }
        }.associate { it.id to it.homeUrl } + mapOf(
        "anime-bangumi" to "https://api.bgm.tv/", "anime-anilist" to "https://graphql.anilist.co/",
        "anime-danmaku-dandanplay" to "https://api.dandanplay.net/")
    private var playerInstance: AnimePlayer? = null
    private val mutablePlaybackPhase = MutableStateFlow(AnimePlayerPhase.IDLE)
    internal val playbackPhase = mutablePlaybackPhase.asStateFlow()
    val player: AnimePlayer
        get() = playerInstance ?: createAnimePlayer(context, scope).also {
            playerInstance = it
            it.configure(playerOptions(mutableState.value.settings))
            it.setForeground(foreground)
            observePlayer(it)
        }

    private var discoverySequence = 0L
    private var discoveryDate: String? = null
    private val discoveryRequests = mutableMapOf<String, Long>()
    private val discoveryJobs = mutableMapOf<String, Job>()
    private val discoveryOptions = mutableMapOf<Pair<AnimeDiscoveryTab, AnimeDiscoveryProvider>, AnimeDiscoveryQuery>()
    private var detailGeneration = 0L
    private var retryNativeReference: AnimeSourceTitle? = null
    private var searchGeneration = 0L
    private var previewGeneration = 0L
    private var resolveGeneration = 0L
    private var catalogGeneration = 0L
    private val catalogRequests = mutableMapOf<String, Long>()
    private var detailJob: Job? = null
    private var sourceJob: Job? = null
    private var previewJob: Job? = null
    private var previewSourceId: String? = null
    private var previewSourceRevision: String? = null
    private var resolveJob: Job? = null
    private var autoPlayJob: Job? = null
    private var autoPlayGeneration = 0L
    private var autoPlaybackDeadline = 0L
    private var associationGeneration = 0L
    private val associationJobs = mutableMapOf<String, Job>()
    private var associationSequence = 0L
    private val associationAttempts = mutableMapOf<String, Long>()
    private val associationSignatures = mutableMapOf<String, String>()
    private val associationCandidates = mutableMapOf<String, Pair<String?, List<AnimeSourceTitle>>>()
    private val associationPermits = Semaphore(4)
    private var boundCatalogJob: Job? = null
    private var relatedJob: Job? = null
    private var prepareJob: Job? = null
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
    private var resumeRequest: Pair<String, String>? = null
    private var restoreEpoch = 0L
    private val recoveryOptions = mutableSetOf<String>()
    private data class PlaybackHistoryKey(val subjectId: String, val episodeId: String,
        val candidate: String, val route: DownloadRouteSnapshot)
    private val playbackFailures = mutableMapOf<PlaybackHistoryKey, Long>()
    private val playbackAttemptOrder = mutableMapOf<PlaybackHistoryKey, Long>()
    private val playbackSourceAttemptOrder = mutableMapOf<PlaybackHistoryKey, Long>()
    private var playbackAttemptSequence = 0L
    private val recentPlaybackSources = mutableMapOf<String, PlaybackSourceReceipt>()
    private var sourceRecoveryWindowStarted = false
    private val playbackEpochs = mutableMapOf<String, Long>()
    private var active: ActivePlayback? = null
    private var pending: PendingPlayback? = null
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
        val sourceRevision: String? = null,
        val localMedia: AnimeLocalMedia? = null,
        var endedSaved: Boolean = false,
        var endActionHandled: Boolean = false,
        var openingSkipped: Boolean = false,
        var endingSkipped: Boolean = false,
        var nextPreparationNotBefore: Long = 0L,
        var recoveryHandled: Boolean = false,
        var stablePlaybackSince: Long? = null,
        var stableRecoveryReset: Boolean = false,
        var sourceReceiptSaved: Boolean = false,
        val automaticIntent: Boolean = false,
    )
    private data class PendingPlayback(
        val detail: AnimeDetail, val episode: AnimeEpisode, val option: AnimeEpisodeOption,
        val asset: AnimePlaybackAsset, val positionMs: Long, val generation: Long,
        val sourceRevision: String? = null, val playWhenReady: Boolean = true,
        val resolutionId: String? = null,
        val localMedia: AnimeLocalMedia? = null,
        val useSourceStart: Boolean = true,
        val automaticIntent: Boolean = false,
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
        val revision: String, val route: DownloadRouteSnapshot,
    )
    private data class PreparedPlayback(val target: PreparationTarget, val asset: AnimePlaybackAsset, val validUntil: Long)
    private data class ResolvedPlaybackOption(val option: AnimeEpisodeOption, val asset: AnimePlaybackAsset, val revision: String?)

    private data class PlaybackReceipt(val playback: ActivePlayback, val positionMs: Long, val playWhenReady: Boolean)
    private data class ReviewContext(val detail: AnimeDetail, val remote: AnimeTrackingSnapshot)

    init {
        scope.launch {
            try {
                try { library.awaitReady() }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { notice(failure.message ?: "追番记录未能读取，原文件已保留；可通过备份恢复。") }
                // Keep observing so an explicit recovery can replace a previously unreadable document.
                library.state.collect { stored ->
                    if (stored.restoreEpoch != restoreEpoch) {
                        restoreEpoch = stored.restoreEpoch
                        ++detailGeneration
                        cancelSourceAggregation()
                        detailJob?.cancel()
                        boundCatalogJob?.cancel()
                        retryNativeReference = null
                        stopPlayback(discardCheckpoint = true)
                        recentPlaybackSources.clear()
                        reviewSnapshots.clear()
                        mutableState.update { it.copy(reviews = emptyList(), detailLoading = false,
                            detailError = if (it.detail != null) "本机记录已恢复，请重新打开番剧以读取恢复后的关联。" else null) }
                    }
                    mutableState.update { it.copy(
                        libraryReadUnavailable = stored.readUnavailable,
                        library = stored.entries.values.sortedWith(compareByDescending<AnimeLibraryEntry> { entry -> entry.pinned }
                            .thenByDescending { entry -> entry.updatedAt }),
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
                    playerInstance?.configure(playerOptions(stored.settings))
                    rebuildOptions()
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
                projectRoutes()
                unifiedSearch.reconcile()
                invalidatePreparation()
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
    fun search(query: String, indexOnly: Boolean = false) {
        val keyword = query.trim()
        if (keyword.isNotEmpty()) mutate { library.rememberSearch(keyword) }
        unifiedSearch.search(keyword, indexOnly)
    }

    fun clearSearchHistory() = mutate { library.clearSearchHistory() }
    fun dismissResume(subjectId: String) = mutate { library.dismissResume(subjectId) }
    fun setDiscoveryColumns(columns: Int) = mutate { library.setDiscoveryColumns(columns) }

    internal fun loadMoreCatalog() = unifiedSearch.loadMore()
    internal fun retrySearchIndex() = unifiedSearch.retryIndex()
    internal fun retrySearchProvider(sourceId: String) = unifiedSearch.retrySource(sourceId)

    /** Fixed home slots keep independent results; changing a day or quarter only invalidates affected slots. */
    fun ensureDiscovery(loadHome: Boolean = true) {
        val today = Instant.fromEpochMilliseconds(wallTimeMillis()).toLocalDateTime(TimeZone.currentSystemDefault()).date
        val season = AnimeSeason.entries[(today.monthNumber - 1) / 3]
        val nextSeason = AnimeSeason.entries[(season.ordinal + 1) % AnimeSeason.entries.size]
        val nextYear = today.year + if (nextSeason == AnimeSeason.WINTER) 1 else 0
        val seasonal = AnimeDiscoveryQuery(year = today.year, season = season)
        val calendar = AnimeDiscoveryQuery(calendar = true, weekday = today.dayOfWeek.isoDayNumber)
        val sections = listOf(
            AnimeDiscoverySection("home:today", "今日放送", calendar, AnimeDiscoveryTab.CALENDAR),
            AnimeDiscoverySection("home:season", "本季人气", seasonal, AnimeDiscoveryTab.SEASON),
            AnimeDiscoverySection("home:trending", "近期趋势", AnimeDiscoveryQuery(
                provider = AnimeDiscoveryProvider.ANILIST, order = AnimeDiscoveryOrder.TRENDING), AnimeDiscoveryTab.RANKING),
            AnimeDiscoverySection("home:rank", "高分佳作", AnimeDiscoveryQuery(order = AnimeDiscoveryOrder.RANK), AnimeDiscoveryTab.RANKING),
            AnimeDiscoverySection("home:upcoming", "下季期待", AnimeDiscoveryQuery(year = nextYear, season = nextSeason), AnimeDiscoveryTab.SEASON),
        )
        if (discoveryDate != today.toString() || mutableState.value.discoverySections.isEmpty()) {
            discoveryDate = today.toString()
            val before = mutableState.value
            val feeds = before.discoveryFeeds.toMutableMap()
            sections.forEach { section ->
                if (feeds[section.key]?.query != section.query) {
                    discoveryRequests[section.key] = ++discoverySequence
                    discoveryJobs.remove(section.key)?.cancel()
                    feeds[section.key] = AnimeDiscoveryFeed(section.query)
                }
            }
            mapOf(AnimeDiscoveryTab.RANKING to AnimeDiscoveryQuery(), AnimeDiscoveryTab.SEASON to seasonal,
                AnimeDiscoveryTab.CALENDAR to calendar, AnimeDiscoveryTab.GENRES to AnimeDiscoveryQuery()).forEach { (tab, query) ->
                val key = animeDiscoveryFeedKey(tab)
                if (key !in feeds) feeds[key] = AnimeDiscoveryFeed(query)
            }
            mutableState.update { it.copy(discoverySections = sections, discoveryFeeds = feeds) }
        }
        if (loadHome && mutableState.value.discoveryTab == AnimeDiscoveryTab.HOME) {
            sections.forEach { section ->
                val feed = mutableState.value.discoveryFeeds[section.key] ?: return@forEach
                if (feed.page == 0 && !feed.loading && feed.error == null) loadDiscovery(section.key)
            }
            sourceBrowse.refreshLatest()
        }
    }

    fun selectDiscoveryTab(tab: AnimeDiscoveryTab) {
        ensureDiscovery(loadHome = false)
        mutableState.update { it.copy(discoveryTab = tab) }
        when (tab) {
            AnimeDiscoveryTab.HOME -> ensureDiscovery()
            AnimeDiscoveryTab.SOURCES -> sourceBrowse.activate()
            else -> {
                val key = animeDiscoveryFeedKey(tab)
                val feed = mutableState.value.discoveryFeeds[key] ?: return
                if (feed.page == 0 && !feed.loading && feed.error == null) loadDiscovery(key)
            }
        }
    }

    internal fun rememberedDiscoveryOptions(tab: AnimeDiscoveryTab, provider: AnimeDiscoveryProvider): AnimeDiscoveryQuery? =
        discoveryOptions[tab to provider]

    fun configureDiscovery(tab: AnimeDiscoveryTab, query: AnimeDiscoveryQuery) {
        if (tab == AnimeDiscoveryTab.HOME || tab == AnimeDiscoveryTab.SOURCES) return
        ensureDiscovery(loadHome = false)
        val key = animeDiscoveryFeedKey(tab)
        val normalized = query.copy(tag = query.tag.trim())
        val current = mutableState.value.discoveryFeeds[key]
        if (tab != AnimeDiscoveryTab.CALENDAR) {
            current?.query?.let { discoveryOptions[tab to it.provider] = it }
            discoveryOptions[tab to normalized.provider] = normalized
        }
        mutableState.update { it.copy(discoveryTab = tab, discoveryFeeds = it.discoveryFeeds +
            (key to if (current != null && current.query == normalized) current else AnimeDiscoveryFeed(normalized))) }
        if (current == null || current.query != normalized || current.page == 0 && !current.loading) loadDiscovery(key)
    }

    fun openDiscoverySection(sectionKey: String) {
        val current = mutableState.value
        val section = current.discoverySections.firstOrNull { it.key == sectionKey } ?: return
        val key = animeDiscoveryFeedKey(section.destination)
        discoveryRequests[key] = ++discoverySequence
        discoveryJobs.remove(key)?.cancel()
        val preview = current.discoveryFeeds[sectionKey]
        val feed = if (preview != null && preview.query == section.query && preview.page > 0)
            preview.copy(loading = false, loadingMore = false, moreError = null)
        else AnimeDiscoveryFeed(section.query)
        mutableState.update { it.copy(discoveryTab = section.destination, discoveryFeeds = it.discoveryFeeds + (key to feed)) }
        if (feed.page == 0) loadDiscovery(key)
    }

    fun refreshDiscovery() {
        ensureDiscovery(loadHome = false)
        val current = mutableState.value
        when (current.discoveryTab) {
            AnimeDiscoveryTab.HOME -> {
                current.discoverySections.forEach { loadDiscovery(it.key, forceRefresh = true) }
                sourceBrowse.refreshLatest(force = true)
            }
            AnimeDiscoveryTab.SOURCES -> sourceBrowse.refresh()
            else -> loadDiscovery(animeDiscoveryFeedKey(current.discoveryTab), forceRefresh = true)
        }
    }

    fun retryDiscovery(key: String) {
        val feed = mutableState.value.discoveryFeeds[key] ?: return
        if (feed.loading || feed.loadingMore) return
        loadDiscovery(key, forceRefresh = true)
    }

    fun loadMoreDiscovery(key: String) {
        val feed = mutableState.value.discoveryFeeds[key] ?: return
        if (feed.loading || feed.loadingMore || !feed.hasMore) return
        loadDiscovery(key, append = true, forceRefresh = feed.moreError != null)
    }

    private fun loadDiscovery(key: String, append: Boolean = false, forceRefresh: Boolean = false) {
        val before = mutableState.value.discoveryFeeds[key] ?: return
        val query = before.query
        val page = if (append) before.page.toLong() + 1 else 1L
        val generation = ++discoverySequence
        discoveryRequests[key] = generation
        discoveryJobs.remove(key)?.cancel()
        mutableState.update { it.copy(discoveryFeeds = it.discoveryFeeds + (key to before.copy(
            loading = !append, loadingMore = append, error = if (append) before.error else null, moreError = null))) }
        discoveryJobs[key] = scope.launch {
            fun current() = discoveryRequests[key] == generation && mutableState.value.discoveryFeeds[key]?.query == query
            fun publish(transform: (AnimeDiscoveryFeed) -> AnimeDiscoveryFeed) {
                if (!current()) return
                mutableState.update { state ->
                    val feed = state.discoveryFeeds[key]
                    if (feed == null || feed.query != query || discoveryRequests[key] != generation) state
                    else state.copy(discoveryFeeds = state.discoveryFeeds + (key to transform(feed)))
                }
            }
            try {
                require(page in 1..Int.MAX_VALUE.toLong()) { "分页位置已超出来源支持范围。" }
                if (!append && before.items.isEmpty()) discovery.cached(query, 1)?.let { cached ->
                    publish { it.copy(items = cached.items, page = cached.page, hasMore = cached.hasMore,
                        fetchedAt = cached.fetchedAt) }
                }
                val result = discovery.browse(query, page.toInt(), forceRefresh)
                check(result.page == page.toInt()) { "来源返回的页码与请求不一致。" }
                publish { feed ->
                    val oldKeys = feed.items.mapTo(mutableSetOf()) { it.key }
                    val added = if (append) result.items.filterNot { it.key in oldKeys } else result.items
                    feed.copy(items = (if (append) feed.items + added else result.items).distinctBy { it.key },
                        page = result.page, hasMore = result.hasMore,
                        loading = false, loadingMore = false, error = if (append) feed.error else null,
                        moreError = null, fetchedAt = if (append) feed.fetchedAt else result.fetchedAt)
                }
            } catch (cancel: CancellationException) {
                publish { it.copy(loading = false, loadingMore = false) }
                throw cancel
            } catch (failure: Exception) {
                publish { it.copy(loading = false, loadingMore = false,
                    error = if (append) it.error else failure.message ?: "这一栏暂未取得，可单独重试。",
                    moreError = if (append) failure.message ?: "这一页未能加载，请重试。" else null) }
            } finally {
                if (current()) discoveryJobs.remove(key)
            }
        }
    }

    /** Only an already saved exact association can bypass the explicit counterpart picker. */
    fun knownDiscoverySubject(reference: AnimeSourceTitle): AnimeSubject? {
        if (reference.sourceRevision != null && reference.sourceRevision != discoverySourceRevision(reference.sourceId)) return null
        val stored = library.state.value
        val ids = stored.bindings.filter { it.catalog.sourceId == reference.sourceId &&
            it.catalog.id == reference.id && it.catalog.url == reference.url }
            .map { it.subjectId }.toMutableSet()
        stored.entries.values.filter { entry -> reference.externalIds.any { (provider, id) ->
            id.isNotBlank() && entry.subject.externalIds[provider] == id
        } }.forEach { ids += it.subject.id }
        return ids.singleOrNull()?.let { stored.entries[it]?.subject }
    }

    fun open(subject: AnimeSubject) = openDetail(subject, resumeEpisodeId = null)

    internal fun returnToVisitedDetail(detail: AnimeDetail, related: List<AnimeSubject>) {
        openDetail(detail.subject, resumeEpisodeId = null, visitedDetail = detail, visitedRelated = related)
    }

    /** Search grouping supplies candidates, never a persisted work/episode association. */
    fun openSearchGroup(group: AnimeSearchGroup) {
        val subject = group.subject ?: return
        open(subject)
        stageSearchCandidates(subject.id, group.sources)
    }

    fun stageSearchCandidates(subjectId: String, candidates: List<AnimeSourceTitle>) {
        if (mutableState.value.detail?.subject?.id != subjectId) return
        val current = candidates.filter { title -> sources.provider(title.sourceId) != null &&
            (title.sourceRevision == null || title.sourceRevision == discoverySourceRevision(title.sourceId)) }
        sourceQuery = mutableState.value.detail?.subject?.title.orEmpty()
        mutableState.update { state -> state.copy(sourceResults = current.groupBy { it.sourceId }.map { (id, items) ->
            AnimeSourceResult(id, sources.provider(id)?.name ?: id, items.distinctBy { it.id to it.url })
        }) }
    }

    fun resume(subjectId: String) {
        val entry = library.state.value.entries[subjectId] ?: return
        val resume = entry.resume?.takeUnless { it.completed }
        if (resume?.localDownloadId != null) openDownload(resume.localDownloadId, entry)
        else openDetail(entry.subject, resume?.episodeId)
    }

    fun sourceSubject(title: AnimeSourceTitle): AnimeSubject = AnimeSubject(
        id = "native:${title.sourceId}:${dev.mangaflow.anime.sources.animeHash(title.id + "\n" + title.url)}",
        title = title.title, coverUrl = title.coverUrl, year = title.year,
        externalIds = title.externalIds + mapOf("native-source" to title.sourceId),
    )

    fun openSourceTitle(title: AnimeSourceTitle) = openDetail(sourceSubject(title), null, title)

    private fun nativeEpisodeId(subject: AnimeSubject, episode: AnimeSourceEpisode): String =
        "${subject.id}:episode:${dev.mangaflow.anime.sources.animeHash(episode.group + "\n" + episode.id)}"

    private fun nativeDetail(subject: AnimeSubject, catalog: AnimeSourceCatalog, group: String): AnimeDetail =
        AnimeDetail(subject.copy(title = catalog.title.title, year = catalog.title.year ?: subject.year,
            externalIds = subject.externalIds + catalog.title.externalIds +
                mapOf("native-source" to catalog.title.sourceId)), catalog.episodes.filter { it.group == group }.map { episode ->
            AnimeEpisode(nativeEpisodeId(subject, episode), subject.id, episode.title,
                episode.number ?: 0.0, if (episode.number == null) AnimeEpisodeKind.OTHER else episode.kind)
        })

    private fun openDetail(subject: AnimeSubject, resumeEpisodeId: String?, nativeReference: AnimeSourceTitle? = null,
        visitedDetail: AnimeDetail? = null, visitedRelated: List<AnimeSubject> = emptyList()) {
        // A related-title back action can reuse the exact catalogue the user saw. Media
        // bindings and source options still resolve against the current registry and library.
        val initialDetail = visitedDetail ?: AnimeDetail(subject, emptyList())
        val nativeOpeningReference = nativeReference ?: retryNativeReference?.takeIf { sourceSubject(it).id == subject.id }
        retryNativeReference = nativeOpeningReference
        closePlayback()
        val generation = ++detailGeneration
        cancelSourceAggregation()
        val openingRestoreEpoch = library.state.value.restoreEpoch
        detailJob?.cancel()
        boundCatalogJob?.cancel()
        relatedJob?.cancel()
        sourceJob?.cancel()
        sourceSearchJobs.values.forEach { it.cancel() }
        sourceSearchJobs.clear()
        previewJob?.cancel()
        previewSourceId = null
        previewSourceRevision = null
        ++searchGeneration
        ++previewGeneration
        resumeRequest = resumeEpisodeId?.let { subject.id to it }
        mutableState.update { it.copy(
            detail = initialDetail, detailLoading = true, detailError = null,
            detailFromCache = visitedDetail != null, relatedSubjects = visitedRelated,
            sourceCatalogs = emptyList(), sourceResults = emptyList(), episodeOptions = emptyMap(),
            sourceBindings = library.state.value.bindings.filter { binding -> binding.subjectId == subject.id },
            sourceCatalogErrors = emptyMap(),
            previewCatalog = null, previewLoading = false, sourceError = null, selectedEpisodeId = null,
            sourceGroups = emptyMap(), preferredSourceId = null,
            sourceAggregation = emptyMap(), sourceAggregationRunning = false,
            sourceAggregationCompleted = 0, sourceAggregationTotal = 0,
        ) }
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
                    val catalog = provider.catalog(reference)
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
                    val binding = if (preserveSaved) requireNotNull(saved)
                        else saved?.copy(catalog = reference.copy(sourceRevision = revision), selectedGroup = group)
                            ?: AnimeSourceBinding(subject.id, reference.copy(sourceRevision = revision), preferred = true, selectedGroup = group, automatic = true)
                    if (!preserveSaved) {
                        // Intrinsic native ownership only, never a guessed Bangumi/cross-source association.
                        library.editPlaybackAssociation(subject, editBindings = {
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
                        ?: AnimeDetail(subject, emptyList()), detailLoading = false,
                        sourceBindings = listOf(binding), sourceCatalogs = listOf(shownCatalog)) }
                    rebuildOptions()
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
                if (currentDetail(generation, subject.id)) startSourceAggregation(initialDetail)
            }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { if (currentDetail(generation, subject.id)) notice(failure.message ?: "来源目录未能读取。") }
        }
        relatedJob = scope.launch {
            try {
                val related = metadata.related(subject)
                if (currentDetail(generation, subject.id)) mutableState.update { it.copy(relatedSubjects = related) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Related subjects are optional; failure cannot invalidate the current detail. */ }
        }
        detailJob = scope.launch {
            try {
                library.awaitLoaded()
                (if (visitedDetail == null) metadata.cachedDetail(subject) else null)?.let { cached ->
                    if (!currentDetail(generation, subject.id)) return@launch
                    mutableState.update { it.copy(detail = cached, detailFromCache = true) }
                    rebuildOptions()
                    startSourceAggregation(cached)
                }
                val result = metadata.detail(subject)
                if (!currentDetail(generation, subject.id)) return@launch
                val previous = mutableState.value.detail
                val detail = AnimeDetail(result.subject ?: previous?.subject ?: subject,
                    result.episodes ?: previous?.episodes.orEmpty())
                val failure = listOfNotNull(result.subjectError?.let { "作品资料：$it" },
                    result.episodeError?.let { "集数目录：$it" }).joinToString("\n").ifBlank { null }
                mutableState.update { it.copy(detail = detail, detailLoading = false, detailError = failure,
                    detailFromCache = it.detailFromCache && (result.subject == null || result.episodes == null)) }
                rebuildOptions()
                startSourceAggregation(detail)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (currentDetail(generation, subject.id)) mutableState.update {
                    it.copy(detailLoading = false, detailError = failure.message ?: "番剧详情未能加载，请重试。")
                }
            }
        }
    }

    fun closeDetail() {
        retryNativeReference = null
        closePlayback()
        resumeRequest = null
        ++detailGeneration
        cancelSourceAggregation()
        ++searchGeneration
        ++previewGeneration
        detailJob?.cancel()
        boundCatalogJob?.cancel()
        relatedJob?.cancel()
        sourceJob?.cancel()
        sourceSearchJobs.values.forEach { it.cancel() }
        sourceSearchJobs.clear()
        previewJob?.cancel()
        mutableState.update { it.copy(detail = null, detailLoading = false, detailError = null,
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
            val received = withTimeoutOrNull(15_000) { provider.catalog(binding.catalog) }
                ?: error("来源目录刷新超时，已保留现有目录。")
            val catalog = retainPartialCatalog(mutableState.value.sourceCatalogs.firstOrNull { it.title == binding.catalog }, received)
            check(catalog.title.sourceId == sourceId && catalog.title.id == binding.catalog.id &&
                catalog.title.url == binding.catalog.url) { "来源目录身份已变化，请重新搜索并确认关联。" }
            if (!current()) return
            mutableState.update { it.copy(
                sourceCatalogs = it.sourceCatalogs.filterNot { old -> old.title.sourceId == sourceId } + catalog,
                sourceCatalogErrors = it.sourceCatalogErrors - sourceId,
            ) }
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

    fun retrySourceSearch(sourceId: String) = requestSourceSearch(sourceId, append = false)
    fun loadMoreSourceSearch(sourceId: String) = requestSourceSearch(sourceId, append = true)

    private fun requestSourceSearch(sourceId: String, append: Boolean) {
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
                val items = provider.search(keyword, page).distinctBy { it.id to it.url }
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
                val catalog = provider.catalog(title)
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
                bindings.map { it.copy(preferred = it.catalog.sourceId == sourceId) }
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
        var detail = mutableState.value.detail ?: return
        val nativeSource = detail.subject.externalIds["native-source"]
        val nativeBinding = library.state.value.bindings.firstOrNull { it.subjectId == detail.subject.id && it.catalog.sourceId == nativeSource }
        val nativeCatalog = mutableState.value.sourceCatalogs.firstOrNull { it.title.sourceId == nativeSource }
        if (nativeBinding != null && nativeCatalog != null && nativeBinding.selectedGroup != null) {
            detail = nativeDetail(detail.subject, nativeCatalog, nativeBinding.selectedGroup)
            mutableState.update { it.copy(detail = detail) }
        }
        val bindings = library.state.value.bindings.filter { it.subjectId == detail.subject.id }
        val catalogs = mutableState.value.sourceCatalogs
        val preferred = bindings.firstOrNull { it.preferred }?.catalog?.sourceId
        val options = detail.episodes.associate { canonical ->
            canonical.id to bindings.flatMap bindingLoop@ { binding ->
                val catalog = catalogs.firstOrNull {
                    it.title.sourceId == binding.catalog.sourceId && it.title.id == binding.catalog.id && it.title.url == binding.catalog.url
                } ?: return@bindingLoop emptyList()
                val automaticGroups = autoPlayGroups(detail, canonical, catalog, binding.selectedGroup)
                val groups = when {
                    binding.selectedGroup == null -> automaticGroups
                    !binding.automatic -> listOf(binding.selectedGroup)
                    nativeSource == binding.catalog.sourceId -> {
                        // Preserve the canonical native group; only audited equivalent tracks may
                        // extend it, never silently replace a disappeared default with another group.
                        if (automaticGroups.size > 1 && binding.selectedGroup in automaticGroups) automaticGroups
                        else listOf(binding.selectedGroup)
                    }
                    else -> automaticGroups
                }
                groups.flatMap groupLoop@ { group ->
                    val inGroup = catalog.episodes.filter { it.group == group }
                    val intrinsic = nativeSource == binding.catalog.sourceId
                    val explicit = if (intrinsic) inGroup.singleOrNull { nativeEpisodeId(detail.subject, it) == canonical.id }
                        else binding.episodeMappings[canonical.id]?.let { id -> inGroup.singleOrNull { it.id == id } }
                    val matches = explicit?.let(::listOf) ?: if ((intrinsic && groups.size == 1) || !canonical.number.isFinite()) emptyList() else inGroup.filter {
                        it.kind == canonical.kind && it.number != null && it.number == canonical.number
                    }
                    matches.map { episode -> AnimeEpisodeOption(binding.catalog.sourceId,
                        sources.provider(binding.catalog.sourceId)?.name ?: binding.catalog.sourceId,
                        binding.catalog, episode, confirmed = explicit != null || matches.size == 1) }
                }
            }.sortedWith(compareByDescending<AnimeEpisodeOption> { it.sourceId == preferred }.thenBy { it.sourceName })
        }
        mutableState.update { it.copy(episodeOptions = options, preferredSourceId = preferred,
            sourceDanmakuAvailable = active?.let(::hasSourceDanmaku) == true,
            sourceBindings = bindings,
            sourceCatalogErrors = it.sourceCatalogErrors.filterKeys { id -> bindings.any { binding -> binding.catalog.sourceId == id } },
            sourceGroups = bindings.mapNotNull { binding ->
                binding.selectedGroup?.let { group -> binding.catalog.sourceId to group }
            }.toMap()) }
        invalidatePreparation()
        resumeRequest?.takeIf { it.first == detail.subject.id }?.let { request ->
            if (options[request.second].orEmpty().any { it.confirmed }) {
                resumeRequest = null
                play(request.second)
            }
        }
    }

    private fun resetPlaybackRecoveryWindow() {
        autoPlaybackDeadline = monotonicTimeMillis() + 45_000L
        recoveryOptions.clear()
        sourceRecoveryWindowStarted = false
        mutableState.update { it.copy(playbackRecoveryStopReason = null) }
    }

    fun play(episodeId: String, option: AnimeEpisodeOption? = null) {
        val episode = mutableState.value.detail?.episodes?.firstOrNull { it.id == episodeId } ?: return
        ++autoPlayGeneration
        resetPlaybackRecoveryWindow()
        autoPlayJob?.cancel()
        mutableState.update { it.copy(playbackTarget = AnimePlaybackTarget(episode, option), autoMatching = false) }
        if (option == null && mutableState.value.episodeOptions[episodeId].orEmpty().none { it.confirmed }) {
            autoAssociateAndPlay(episodeId)
        } else resolveAndPlay(episodeId, option)
    }

    private fun cancelSourceAggregation() {
        ++associationGeneration
        associationJobs.values.forEach { it.cancel() }
        associationJobs.clear()
        associationSignatures.clear()
        associationAttempts.clear()
        associationCandidates.clear()
        mutableState.update { it.copy(sourceAggregation = emptyMap(), sourceAggregationRunning = false,
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
    }

    /** Detail-owned discovery, independent of player preparation and its cancellation. */
    private fun startSourceAggregation(requestedDetail: AnimeDetail, force: Boolean = false, retryFailed: Boolean = false) {
        // Awaiting library/extension readiness can outlive a metadata refresh. The visible
        // current detail, not the caller's older title/year snapshot, owns discovery evidence.
        val detail = mutableState.value.detail?.takeIf { it.subject.id == requestedDetail.subject.id } ?: return
        val owner = detailGeneration
        val generation = associationGeneration
        val epoch = library.state.value.restoreEpoch
        val providers = sources.providers
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
            val revision = discoverySourceRevision(sourceId)
            val signature = listOf(detail.subject.id, detail.subject.title, detail.subject.originalTitle,
                detail.subject.year, detail.subject.externalIds.entries.sortedBy { it.key }, revision).joinToString("|")
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
                sources.provider(sourceId) != null && discoverySourceRevision(sourceId) == revision
            fun report(phase: AnimeAssociationPhase, message: String? = null) {
                if (current()) publishAssociation(sourceId, AnimeAssociationProgress(phase, message))
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
                    associationPermits.withPermit {
                        if (!current()) return@withPermit
                        report(AnimeAssociationPhase.SEARCHING)
                        val loaded = withTimeoutOrNull(15_000) aggregation@ {
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
                                val catalog = provider.catalog(existing.catalog)
                                check(catalog.title.sourceId == sourceId && catalog.title.id == existing.catalog.id &&
                                    catalog.title.url == existing.catalog.url) { "来源目录身份不一致。" }
                                if (!current() || !stillBound(detail.subject.id, existing.catalog)) return@aggregation false
                                mutableState.update { state -> state.copy(sourceCatalogs = state.sourceCatalogs.filterNot {
                                    it.title.sourceId == sourceId
                                } + retainPartialCatalog(state.sourceCatalogs.firstOrNull { it.title.sourceId == sourceId }, catalog),
                                    sourceCatalogErrors = state.sourceCatalogErrors - sourceId) }
                                rebuildOptions()
                                report(AnimeAssociationPhase.LINKED, catalog.notice)
                                return@aggregation true
                            }
                            val keywords = listOf(detail.subject.title, detail.subject.originalTitle)
                                .map { it.trim() }.filter { it.isNotBlank() }.distinct()
                            val rows = if (force) mutableListOf<AnimeSourceTitle>() else associationCandidates[sourceId]
                                ?.takeIf { it.first == revision }?.second.orEmpty().toMutableList()
                            var searchSucceeded = false
                            var searchFailure: String? = null
                            val seenByKeyword = keywords.associateWith { mutableSetOf<Pair<String, String>>() }
                            val finishedKeywords = mutableSetOf<String>()
                            // Recall only. Round-robin keywords so extra title pages cannot
                            // starve the original-title first page. Keep the shared 15s budget.
                            val lastPage = if (provider.supportsSearchPagination) 3 else 1
                            searchPages@ for (pageNumber in 1..lastPage) {
                                for (keyword in keywords) {
                                    if (!force && rows.any { autoPlaySubjectCandidate(detail.subject, it) }) break@searchPages
                                    if (keyword in finishedKeywords) continue
                                    if (!current()) return@aggregation false
                                    val page = try {
                                        withTimeoutOrNull(4_000) { provider.search(keyword, pageNumber) }
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
                                    if (fresh.isEmpty()) {
                                        finishedKeywords += keyword // Empty or repeated page, never loop on ignored pagination.
                                        continue
                                    }
                                    rows += fresh.filter { found -> rows.none { it.id == found.id && it.url == found.url } }
                                    if (rows.any { autoPlaySubjectCandidate(detail.subject, it) }) break@searchPages
                                }
                                if (finishedKeywords.size == keywords.size) break
                            }
                            if (!current()) return@aggregation false
                            if (searchSucceeded) associationCandidates[sourceId] = revision to rows.toList()
                            val titles = rows.filter { autoPlaySubjectCandidate(detail.subject, it) }
                            if (titles.isEmpty()) {
                                if (searchFailure != null) report(AnimeAssociationPhase.UNAVAILABLE, searchFailure)
                                else report(AnimeAssociationPhase.NO_MATCH, "本次有界搜索未找到可靠的同作品候选，可手动继续搜索。")
                                return@aggregation true
                            }
                            if (titles.size > 2) {
                                report(AnimeAssociationPhase.NEEDS_SELECTION, "同名版本不唯一，可在来源详情中核对。")
                                return@aggregation true
                            }
                            val verified = titles.mapNotNull { title ->
                                val catalog = withTimeoutOrNull(5_000) { provider.catalog(title) }
                                    ?: return@aggregation false
                                check(catalog.title.sourceId == sourceId && catalog.title.id == title.id &&
                                    catalog.title.url == title.url) { "来源目录身份不一致。" }
                                catalog.takeIf { autoPlaySubjectMatches(detail.subject, it.title) }
                            }
                            val catalog = verified.singleOrNull()
                            if (catalog == null) {
                                report(AnimeAssociationPhase.NEEDS_SELECTION, "作品年份、身份或版本仍需核对。")
                                return@aggregation true
                            }
                            if (!current()) return@aggregation false
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
                            rebuildOptions()
                            report(AnimeAssociationPhase.LINKED, catalog.notice)
                            true
                        }
                        if (loaded != true) report(AnimeAssociationPhase.UNAVAILABLE, "来源暂未完成，已保留现有目录。")
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { report(AnimeAssociationPhase.UNAVAILABLE, failure.message ?: "来源暂不可用，现有目录保留。") }
            }
        }
    }

    fun retrySourceAggregation() {
        mutableState.value.detail?.let { startSourceAggregation(it, force = true) }
    }

    private fun optionKey(option: AnimeEpisodeOption): String = listOf(option.sourceId, option.catalog.id,
        option.catalog.url, option.episode.id, option.episode.group, option.episode.edition,
        discoverySourceRevision(option.sourceId)).joinToString("|")

    /** The chooser and the automatic player share the exact same eligible episode lines.
     * Explicit selection plays only that line. Manual non-number mappings stay exact. */
    internal fun confirmedPlaybackLines(episodeId: String,
        snapshot: AnimeScreenState = mutableState.value): List<AnimeEpisodeOption> {
        val offered = snapshot.episodeOptions[episodeId].orEmpty().filter { it.confirmed }
        val detail = snapshot.detail ?: return emptyList()
        val episode = detail.episodes.firstOrNull { it.id == episodeId } ?: return emptyList()
        return offered.flatMap { base ->
            if (base.sourceId != "anime-age" || base.episode.kind != episode.kind ||
                base.episode.number != episode.number) return@flatMap listOf(base)
            val catalog = snapshot.sourceCatalogs.firstOrNull {
                it.title.sourceId == base.sourceId && it.title.id == base.catalog.id && it.title.url == base.catalog.url
            } ?: return@flatMap listOf(base)
            val groups = autoPlayGroups(detail, episode, catalog, base.episode.group)
            if (groups.size < 2 || base.episode.group !in groups) return@flatMap listOf(base)
            listOf(base) + groups.filterNot { it == base.episode.group }.mapNotNull { group ->
                catalog.episodes.singleOrNull { it.group == group && it.kind == base.episode.kind &&
                    it.number == base.episode.number }?.let { base.copy(episode = it) }
            }
        }.distinctBy { optionKey(it) }
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

    private fun sourceAttemptKey(subjectId: String, episodeId: String, sourceId: String) =
        playbackHistoryRoute(sourceId)?.let { PlaybackHistoryKey(subjectId, episodeId, sourceId, it) }

    private fun playbackHistoryKey(subjectId: String, episodeId: String, option: AnimeEpisodeOption) =
        playbackHistoryRoute(option.sourceId)?.let { PlaybackHistoryKey(subjectId, episodeId, optionKey(option), it) }

    private fun rememberPlaybackFailure(key: PlaybackHistoryKey) {
        playbackFailures.remove(key)
        playbackFailures[key] = wallTimeMillis()
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
        recentPlaybackSources[subjectId] = PlaybackSourceReceipt(sourceId, revision, owner.asset.route, observedAt,
            option.catalog.id, option.catalog.url, option.episode.group, option.episode.edition)
        if (recentPlaybackSources.size > 128) recentPlaybackSources.remove(recentPlaybackSources.keys.first())
    }

    /** Try each source before another route from it. Recent failures go last, and retries
     * continue with least-recently attempted sources/options instead of repeating a prefix.
     * Within AGE, known dynamic-only wrappers follow routes this resolver can already read. */
    private fun fairPlaybackOptions(subjectId: String, episodeId: String,
        options: List<AnimeEpisodeOption>, preferContinuity: Boolean = true): List<AnimeEpisodeOption> {
        val now = wallTimeMillis()
        val preferred = mutableState.value.preferredSourceId.takeIf { preferContinuity }
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
        fun cooling(option: AnimeEpisodeOption) = playbackHistoryKey(subjectId, episodeId, option)?.let { playbackFailures[it] }?.let { now - it in 0L until 60_000L } == true
        return listOf(false, true).flatMap { cooldown ->
            val buckets = options.filter { cooling(it) == cooldown }.groupBy { it.sourceId }.entries
                .sortedWith(compareBy<Map.Entry<String, List<AnimeEpisodeOption>>> { priority(it.key) }.thenBy {
                    sourceAttemptKey(subjectId, episodeId, it.key)?.let { key -> playbackSourceAttemptOrder[key] } ?: Long.MIN_VALUE
                })
                .map { (_, rows) -> rows.sortedWith(compareBy<AnimeEpisodeOption> { recent?.matches(it) != true }.thenBy {
                    dev.mangaflow.anime.sources.animePlaybackParserPriority(it.sourceId, it.episode)
                }.thenBy { playbackHistoryKey(subjectId, episodeId, it)?.let { key -> playbackAttemptOrder[key] } ?: Long.MIN_VALUE }) }
            val result = mutableListOf<AnimeEpisodeOption>()
            repeat(buckets.maxOfOrNull { it.size } ?: 0) { index ->
                buckets.forEach { rows -> rows.getOrNull(index)?.let(result::add) }
            }
            result
        }
    }

    /** Wait for progressive discovery, never cancel the other sources when one can start. */
    private fun autoAssociateAndPlay(episodeId: String, attempted: Set<String> = emptySet(), lastFailure: String? = null) {
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
                startSourceAggregation(detail, retryFailed = true)
                val remaining = autoPlaybackDeadline - monotonicTimeMillis()
                val ready = if (remaining <= 0L) null else withTimeoutOrNull(remaining) {
                    state.first { state ->
                        confirmedPlaybackLines(episodeId, state).any { optionKey(it) !in attempted } ||
                            !state.sourceAggregationRunning
                    }
                }
                if (generation != autoPlayGeneration || !currentDetail(owner, detail.subject.id)) return@launch
                if (ready != null && confirmedPlaybackLines(episodeId, ready).any { optionKey(it) !in attempted }) {
                    resolveAndPlay(episodeId, attempted = attempted)
                } else {
                    failPlaybackTarget(lastFailure ?: if (attempted.isNotEmpty()) "已确认的本集线路暂未解析成功。"
                        else "暂未找到能确认对应本集的片源。")
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
        attempted: Set<String> = emptySet()) {
        val detail = mutableState.value.detail ?: return
        val episode = detail.episodes.firstOrNull { it.id == episodeId } ?: return
        val offered = confirmedPlaybackLines(episodeId).filterNot { optionKey(it) in attempted }
        val options = if (option == null) fairPlaybackOptions(detail.subject.id, episodeId, offered,
            preferContinuity = attempted.isEmpty()) else offered
        val settings = mutableState.value.settings
        val canFallback = option == null && settings.automaticSourceRecovery && settings.automaticRecoveryAttempts > 0
        val selected = if (option == null) options.firstOrNull() else currentPlaybackLine(episodeId, option)
        if (selected == null) {
            cancelPlaybackResolution()
            mutableState.update { it.copy(playbackTarget = AnimePlaybackTarget(episode, option,
                error = "本集线路尚未确认或已变化。")) }
            return
        }
        resumeRequest = null
        val available = prepared?.takeIf { it.target.subjectId == detail.subject.id &&
            it.target.episodeId == episodeId && it.target.option == selected && freshPreparation(it) }
        clearPreparation()
        val owner = detailGeneration
        val generation = ++resolveGeneration
        resolveJob?.cancel()
        pending = null
        resolvingSourceId = selected.sourceId
        mutableState.update { it.copy(resolving = true, autoMatching = false, pendingPlayback = null,
            playbackTarget = AnimePlaybackTarget(episode, option, selected.sourceName)) }
        resolveJob = scope.launch {
            var resolutionCompleted = false
            val tried = attempted.toMutableSet()
            val rejected = mutableSetOf<String>()
            try {
                library.awaitLoaded()
                fun current() = generation == resolveGeneration && currentDetail(owner, detail.subject.id)
                suspend fun attempt(candidate: AnimeEpisodeOption): Result<ResolvedPlaybackOption> {
                    var admissionKey: PlaybackHistoryKey? = null
                    var admissionStillCurrent: (() -> Boolean)? = null
                    val key = optionKey(candidate)
                    suspend fun resolveOption(): ResolvedPlaybackOption {
                        check(current()) { "播放请求已变化。" }
                        val line = currentPlaybackLine(episodeId, candidate)
                            ?: error("本集来源关联已变化，请重新选集。")
                        val revision = sourceRevision(candidate.sourceId)
                        val discoveryRevision = discoverySourceRevision(candidate.sourceId)
                            ?: error("这个来源当前不可用。")
                        val catalog = mutableState.value.sourceCatalogs.firstOrNull {
                            it.title.sourceId == candidate.sourceId && it.title.id == candidate.catalog.id && it.title.url == candidate.catalog.url
                        } ?: error("来源目录已变化，请重新选集。")
                        val provider = sources.provider(candidate.sourceId) ?: error("这个来源当前不可用。")
                        val preparedAsset = available?.takeIf { it.target.option == line && freshPreparation(it) }?.asset
                        check(option != null || monotonicTimeMillis() < autoPlaybackDeadline) { "本轮播放准备已到时限，请重试。" }
                        // Only admitted attempts count; exhausted-budget candidates remain untried.
                        val historyKey = playbackHistoryKey(detail.subject.id, episodeId, candidate)
                            ?: error("此来源的网络线路不可用，请检查来源设置。")
                        admissionKey = historyKey
                        admissionStillCurrent = {
                            current() &&
                                revision == sourceRevision(candidate.sourceId) &&
                                discoveryRevision == discoverySourceRevision(candidate.sourceId) &&
                                historyKey.route == playbackHistoryRoute(candidate.sourceId) &&
                                currentPlaybackLine(episodeId, line) != null
                        }
                        rememberPlaybackAttempt(historyKey, candidate.sourceId)
                        val resolved = (preparedAsset ?: provider.resolve(catalog, line.episode)).copy(
                            episodeId = episode.id, title = detail.subject.title + " · " + episode.title)
                        check(resolved.sourceId == line.sourceId && resolved.sourceEpisodeId == line.episode.id) {
                            "来源返回的剧集身份不一致，请重新读取目录。"
                        }
                        check(resolved.sourceRevision == discoveryRevision &&
                            discoveryRevision == discoverySourceRevision(candidate.sourceId) &&
                            revision == sourceRevision(candidate.sourceId)) {
                            "来源已更新，请重新解析。"
                        }
                        check(resolved.expiresAt == null || resolved.expiresAt > wallTimeMillis()) { "播放地址已过期，请重试解析。" }
                        check(current()) { "播放请求已变化。" }
                        check(resolved.route == historyKey.route && resolved.route == playbackHistoryRoute(candidate.sourceId)) {
                            "本集网络线路已变化，请重试。"
                        }
                        val confirmed = currentPlaybackLine(episodeId, line)
                            ?: error("本集来源关联已变化，请重新选集。")
                        return ResolvedPlaybackOption(confirmed, resolved, revision)
                    }
                    suspend fun resolveOnCurrentRoute(): ResolvedPlaybackOption {
                        try { return resolveOption() }
                        catch (failure: Exception) {
                            currentCoroutineContext().ensureActive()
                            val previousRoute = admissionKey?.route ?: throw failure
                            val currentRoute = playbackHistoryRoute(candidate.sourceId) ?: throw failure
                            if (!current() || previousRoute == currentRoute) throw failure
                        }
                        // A real settings change discards the previous asset. Retry this exact
                        // line once using the current configured route and the same time budget.
                        return resolveOption()
                    }
                    return try {
                        val resolved = if (option != null) resolveOnCurrentRoute()
                            else withTimeoutOrNull(minOf(8_000L, (autoPlaybackDeadline - monotonicTimeMillis()).coerceAtLeast(1L))) {
                                resolveOnCurrentRoute()
                            } ?: error("本集线路解析超时。")
                        if (current()) tried += key
                        Result.success(resolved)
                    } catch (cancel: CancellationException) {
                        // A provider can cancel its own child session on revision changes. A
                        // cancelled playback request still cancels the whole race.
                        currentCoroutineContext().ensureActive()
                        if (current()) rejected += key
                        Result.failure(IllegalStateException("本集来源已变化，请重试。", cancel))
                    } catch (failure: Exception) {
                        if (current()) {
                            val admitted = admissionKey
                            if (admitted != null && admissionStillCurrent?.invoke() == true) {
                                tried += key
                                recoveryOptions += key
                                rememberPlaybackFailure(admitted)
                            } else rejected += key
                        }
                        Result.failure(failure)
                    }
                }
                val choices = (if (canFallback) options else listOf(selected)).toMutableList()
                var resolvedChoice: Result<ResolvedPlaybackOption>? = null
                val backup = choices.firstOrNull { it.sourceId != selected.sourceId }
                    ?: choices.firstOrNull { it.sourceId == "anime-age" && it.episode.group != selected.episode.group &&
                        it.catalog.id == selected.catalog.id && it.catalog.url == selected.catalog.url }
                if (backup != null && available == null) {
                    choices.remove(selected)
                    resolvedChoice = coroutineScope {
                        val primary = async { attempt(selected) }
                        val early = withTimeoutOrNull(1_500L) { primary.await() }
                        if (early != null) return@coroutineScope early
                        if (!current() || monotonicTimeMillis() >= autoPlaybackDeadline) return@coroutineScope primary.await()
                        choices.remove(backup)
                        mutableState.update { it.copy(playbackTarget = it.playbackTarget?.copy(sourceName = null)) }
                        val standby = async { attempt(backup) }
                        select<Result<ResolvedPlaybackOption>> {
                            primary.onAwait { result ->
                                if (result.isSuccess) { standby.cancel(); result } else standby.await()
                            }
                            standby.onAwait { result ->
                                if (result.isSuccess) { primary.cancel(); result } else primary.await()
                            }
                        }
                    }
                }
                for (candidate in choices) {
                    if (!current()) return@launch
                    if (resolvedChoice?.isSuccess == true || (option == null && monotonicTimeMillis() >= autoPlaybackDeadline)) break
                    resolvingSourceId = candidate.sourceId
                    mutableState.update { it.copy(playbackTarget = it.playbackTarget?.copy(sourceName = candidate.sourceName)) }
                    resolvedChoice = attempt(candidate)
                }
                val resolvedSelection = resolvedChoice?.getOrThrow() ?: error("对应片源暂不可用。")
                val (resolvedOption, resolved, revision) = resolvedSelection
                resolutionCompleted = true
                if (!current()) return@launch
                recoveryOptions += optionKey(resolvedOption)
                val live = active?.takeIf { it.detail.subject.id == detail.subject.id && it.episode.id == episodeId }
                val liveSnapshot = playerInstance?.state?.value?.takeIf { snapshot ->
                    live != null && belongsTo(live, snapshot) && hasPlaybackOutput(snapshot)
                }
                val previous = library.progressFor(detail.subject.id, episodeId)
                val position = if (liveSnapshot != null && !liveSnapshot.ended) liveSnapshot.positionMs.coerceAtLeast(0)
                    else previous?.takeUnless { it.completed }?.positionMs ?: 0L
                val previousAsset = live?.asset?.id ?: previous?.assetId
                val choice = PendingPlayback(detail, episode, resolvedOption, resolved, position, generation, revision,
                    playWhenReady = liveSnapshot?.takeIf { it.phase == AnimePlayerPhase.READY }?.playWhenReady ?: true,
                    automaticIntent = option == null)
                // Default same-episode candidates have already passed work/edition/episode checks.
                // Keep the logical episode position on a best-effort basis; only an explicit line change needs a choice.
                if (option != null && position > 0L && previousAsset != null && previousAsset != resolved.id) {
                    pending = choice
                    mutableState.update { it.copy(resolving = false, pendingPlayback = AnimePlaybackChoice(
                        episode, resolvedOption, position, live?.asset?.sourceId ?: previous?.sourceId.orEmpty())) }
                } else startPlayback(choice)
            } catch (cancel: CancellationException) {
                if (generation == resolveGeneration && currentDetail(owner, detail.subject.id)) {
                    failPlaybackTarget("播放准备中断，请重试。")
                }
                throw cancel
            } catch (failure: Exception) {
                if (generation == resolveGeneration && currentDetail(owner, detail.subject.id)) {
                    if (!resolutionCompleted && canFallback) {
                        // Default playback can recover from a previously associated but now unavailable
                        // source. Explicit user-selected options never silently switch sources.
                        autoAssociateAndPlay(episodeId, attempted = tried + rejected, lastFailure = failure.message)
                        return@launch
                    }
                    failPlaybackTarget(failure.message ?: "未能取得本集播放地址。")
                }
            } finally { if (generation == resolveGeneration) resolvingSourceId = null }
        }
    }

    fun confirmPlayback(resumePrevious: Boolean) {
        val choice = pending ?: return
        if (choice.generation != resolveGeneration || mutableState.value.detail?.subject?.id != choice.detail.subject.id) return
        pending = null
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

    private fun failPlaybackTarget(error: String) {
        mutableState.update { it.copy(resolving = false, autoMatching = false,
            playbackTarget = it.playbackTarget?.copy(error = error)) }
    }

    fun cancelPlaybackResolution() {
        ++autoPlayGeneration
        autoPlayJob?.cancel()
        autoPlayJob = null
        ++resolveGeneration
        resolveJob?.cancel()
        resolveJob = null
        resolvingSourceId = null
        pending = null
        mutableState.update { it.copy(resolving = false, autoMatching = false, pendingPlayback = null, playbackTarget = null) }
    }

    private fun sourceRevision(sourceId: String): String? = sources.extensions.state.value.firstOrNull { it.id == sourceId }?.revision

    private fun discoverySourceRevision(sourceId: String): String? = sources.extensions.snapshot(sourceId)?.revision
        ?: runCatching { sources.provider(sourceId)?.revision }.getOrNull()

    private fun validCandidate(choice: PendingPlayback): Boolean =
        choice.localMedia != null || sources.provider(choice.option.sourceId) != null && sourceRevision(choice.option.sourceId) == choice.sourceRevision &&
            choice.asset.sourceRevision == discoverySourceRevision(choice.option.sourceId) &&
            (choice.asset.expiresAt == null || choice.asset.expiresAt > wallTimeMillis()) &&
            choice.asset.route == playbackHistoryRoute(choice.option.sourceId) &&
            currentPlaybackLine(choice.episode.id, choice.option) != null

    private suspend fun startPlayback(choice: PendingPlayback, rememberPrevious: Boolean = true) {
        check(validCandidate(choice)) { "来源、网络路线或集数对应已变化，请重新解析。" }
        if (choice.generation != resolveGeneration) return
        saveCurrentPlayback()
        if (choice.generation != resolveGeneration) return
        check(validCandidate(choice) && mutableState.value.detail?.subject?.id == choice.detail.subject.id) {
            "来源关联已变化，请重新选择这一集。"
        }
        val engine = player
        val old = active
        val oldSnapshot = engine.state.value
        if (rememberPrevious && old != null && old.detail.subject.id == choice.detail.subject.id &&
            belongsTo(old, oldSnapshot) && oldSnapshot.phase == AnimePlayerPhase.READY && hasPlaybackOutput(oldSnapshot))
            rollback = PlaybackReceipt(old, oldSnapshot.positionMs, oldSnapshot.playWhenReady)
        else if (old?.detail?.subject?.id != choice.detail.subject.id) rollback = null
        val nextEpoch = (playbackEpochs[choice.detail.subject.id] ?: 0L) + 1L
        playbackEpochs[choice.detail.subject.id] = nextEpoch
        pending = null
        val position = if (choice.positionMs > 0 || !choice.useSourceStart) choice.positionMs
            else choice.asset.startPositionMs?.coerceAtLeast(0) ?: 0L
        active = ActivePlayback(choice.detail, choice.episode, choice.option, choice.asset,
            nextEpoch, engine.state.value.sessionGeneration + 1, library.state.value.restoreEpoch,
            sourceRevision = choice.sourceRevision, localMedia = choice.localMedia, openingSkipped = position > 0,
            automaticIntent = choice.automaticIntent)
        danmaku.setContext(active?.let(::playbackKey))
        lastCheckpointAt = 0L
        lastSavedPosition = -1L
        lastSavedPhase = null
        mutableState.update { it.copy(resolving = false, selectedEpisodeId = choice.episode.id, pendingPlayback = null,
            playbackTarget = null, selectedPlaybackOption = choice.option.takeIf { choice.localMedia == null },
            canRestorePlayback = false, sourceError = null, playbackRecoveryStopReason = null,
            sourceDanmakuAvailable = active?.let(::hasSourceDanmaku) == true,
            playbackResolutions = choice.asset.resolutions, selectedResolutionId = choice.resolutionId ?:
                choice.asset.resolutions.singleOrNull { item -> item.url == choice.asset.url }?.id) }
        engine.configure(playerOptions(mutableState.value.settings))
        engine.open(AnimePlayerRequest(choice.asset, position, choice.playWhenReady, choice.localMedia))
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
            localMedia = previous.localMedia, useSourceStart = false, automaticIntent = previous.automaticIntent)
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
        val asset = owner.asset.copy(id = owner.option.sourceId + ":quality:" +
            dev.mangaflow.anime.sources.animeHash(owner.option.episode.id + "\n" + resolution.id + "\n" +
                dev.mangaflow.anime.sources.animeContentIdentity(resolution.url)),
            url = resolution.url, mimeType = resolution.mimeType ?: owner.asset.mimeType,
            headers = owner.asset.headers + resolution.headers)
        val choice = PendingPlayback(owner.detail, owner.episode, owner.option, asset,
            snapshot.positionMs, resolveGeneration, owner.sourceRevision, snapshot.playWhenReady, id,
            useSourceStart = false, automaticIntent = owner.automaticIntent)
        scope.launch {
            try { startPlayback(choice) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { notice(failure.message ?: "清晰度切换未完成。") }
        }
    }

    fun retryPlayback() {
        mutableState.value.playbackTarget?.takeIf { it.error != null }?.let {
            play(it.episode.id, it.manualOption)
            return
        }
        val current = active ?: return
        if (current.localMedia != null) player.retry()
        else play(current.episode.id, if (current.automaticIntent) null else current.option)
    }

    fun closePlayback() = stopPlayback(discardCheckpoint = false)

    private fun stopPlayback(discardCheckpoint: Boolean) {
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
            selectedPlaybackOption = null) }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) flushPlayback()
        playerInstance?.setForeground(value)
        if (downloadsOwner.isInitialized()) downloads.setForeground(value)
        if (!value && (!mutableState.value.settings.backgroundAudio || prepared == null)) clearPreparation()
        if (value && discoveryDate != null) ensureDiscovery()
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
        owner.option.sourceId == "anime-lanerc-ani" && sources.provider(owner.option.sourceId) != null &&
        mutableState.value.sourceCatalogs.any { it.title.sourceId == owner.option.sourceId &&
            it.title.id == owner.option.catalog.id && owner.option.episode in it.episodes }

    fun loadCurrentSourceDanmaku() {
        val owner = active ?: return
        val catalog = mutableState.value.sourceCatalogs.firstOrNull { it.title.sourceId == owner.option.sourceId &&
            it.title.id == owner.option.catalog.id && it.title.url == owner.option.catalog.url } ?: run {
            notice("当前来源目录已改变，请刷新后核对弹幕剧集。"); return
        }
        danmaku.loadSource(catalog, owner.option.episode)
    }

    private suspend fun saveCurrentPlayback() {
        val owner = active ?: return
        if (library.state.value.readUnavailable) return
        val snapshot = playerInstance?.state?.value ?: return
        if (!belongsTo(owner, snapshot) || !hasPlaybackOutput(snapshot) ||
            snapshot.phase !in setOf(AnimePlayerPhase.READY, AnimePlayerPhase.ENDED)) return
        library.checkpoint(owner.detail.subject, progress(owner, snapshot)) {
            playbackEpochs[owner.detail.subject.id] == owner.epoch && library.state.value.restoreEpoch == owner.libraryEpoch
        }
    }

    private fun progress(owner: ActivePlayback, snapshot: AnimePlayerState) = AnimeWatchProgress(
        owner.episode.id, owner.asset.id, owner.asset.sourceId, snapshot.positionMs.coerceAtLeast(0L),
        snapshot.durationMs.coerceAtLeast(0L), wallTimeMillis(), snapshot.ended, owner.detail.subject.id, owner.localMedia?.downloadId)

    private fun playbackBusy(snapshot: AnimePlayerState? = playerInstance?.state?.value): Boolean =
        mutableState.value.resolving || snapshot?.isBuffering == true || snapshot?.phase == AnimePlayerPhase.OPENING ||
            active?.let { snapshot != null && snapshot.sessionGeneration < it.playerGeneration } == true

    private fun observePlayer(engine: AnimePlayer) {
        scope.launch {
            engine.state.collect { snapshot ->
                mutablePlaybackPhase.value = snapshot.phase
                val owner = active ?: return@collect
                if (!belongsTo(owner, snapshot)) return@collect
                danmaku.updatePosition(playbackKey(owner), snapshot.positionMs, snapshot.isPlaying)
                if (downloadsOwner.isInitialized()) downloads.setPlaybackBusy(playbackBusy(snapshot))
                checkpoint(owner, snapshot, force = snapshot.phase != lastSavedPhase || snapshot.ended || !snapshot.playWhenReady)
                if (snapshot.phase == AnimePlayerPhase.ERROR) {
                    val historyKey = playbackHistoryKey(owner.detail.subject.id, owner.episode.id, owner.option)
                    if (snapshot.sourceRecoveryAllowed && historyKey != null && owner.asset.route == historyKey.route &&
                        owner.sourceRevision == sourceRevision(owner.option.sourceId) &&
                        currentPlaybackLine(owner.episode.id, owner.option) != null)
                        rememberPlaybackFailure(historyKey)
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
                if (!owner.sourceReceiptSaved && owner.localMedia == null && stableFor >= 2_000L) {
                    rememberWorkingSource(owner, now)
                }
                if (!owner.stableRecoveryReset && stableFor >= 30_000L) {
                    owner.stableRecoveryReset = true
                    sourceRecoveryWindowStarted = false
                    recoveryOptions.clear()
                }
                if ((snapshot.isBuffering || !snapshot.playWhenReady) && preparationTarget != null && prepared == null) {
                    clearPreparation()
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
                        preparationTarget == null && now >= owner.nextPreparationNotBefore && !mutableState.value.resolving &&
                        !mutableState.value.sleepAtEpisodeEnd && (snapshot.durationMs <= 0 || remaining <= 45_000L)) {
                        prepareNext(owner)
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
        if (!belongsTo(owner, snapshot) || !hasPlaybackOutput(snapshot) ||
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
        return episodes.getOrNull(index + 1)?.takeIf { next -> index >= 0 &&
            mutableState.value.episodeOptions[next.id].orEmpty().any { it.confirmed } }
    }

    private fun prepareNext(owner: ActivePlayback) {
        val next = nextEpisode(owner) ?: return
        val subjectId = owner.detail.subject.id
        val option = defaultPreparationOption(subjectId, next.id) ?: return
        val catalog = mutableState.value.sourceCatalogs.firstOrNull { it.title.sourceId == option.catalog.sourceId &&
            it.title.id == option.catalog.id && it.title.url == option.catalog.url } ?: return
        val provider = sources.provider(option.sourceId) ?: return
        val revision = discoverySourceRevision(option.sourceId) ?: return
        val route = playbackHistoryRoute(option.sourceId) ?: return
        val target = PreparationTarget(subjectId, next.id, option, revision, route)
        val generation = resolveGeneration
        preparationTarget = target
        prepareJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val asset = withTimeoutOrNull(8_000L) { provider.resolve(catalog, option.episode) }
                    ?: error("下一集来源解析超时。")
                if (preparationTarget !== target || active !== owner || generation != resolveGeneration ||
                    !preparationStillCurrent(target)) return@launch
                check(asset.sourceId == option.sourceId && asset.sourceEpisodeId == option.episode.id &&
                    asset.sourceRevision == revision && asset.route == route)
                val lifetime = asset.expiresAt?.let { expiry ->
                    val now = wallTimeMillis()
                    check(expiry > now)
                    minOf(60_000L, expiry - now - 2_000L)
                } ?: 60_000L
                check(lifetime > 0L)
                prepared = PreparedPlayback(target, asset, monotonicTimeMillis() + lifetime)
                mutableState.update { it.copy(nextEpisodeReady = true) }
                delay(lifetime)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if (preparationTarget === target) owner.nextPreparationNotBefore = monotonicTimeMillis() + 30_000L
            } finally {
                if (preparationTarget === target) releasePreparation()
            }
        }
        prepareJob?.start()
    }

    private fun defaultPreparationOption(subjectId: String, episodeId: String): AnimeEpisodeOption? =
        fairPlaybackOptions(subjectId, episodeId, confirmedPlaybackLines(episodeId))
            .firstOrNull()

    private fun preparationStillCurrent(target: PreparationTarget): Boolean =
        mutableState.value.detail?.subject?.id == target.subjectId && mutableState.value.settings.prepareNextEpisode &&
            active?.let(::nextEpisode)?.id == target.episodeId &&
            target.route == playbackHistoryRoute(target.option.sourceId) &&
            target.revision == discoverySourceRevision(target.option.sourceId) &&
            defaultPreparationOption(target.subjectId, target.episodeId) == target.option

    private fun freshPreparation(value: PreparedPlayback): Boolean =
        monotonicTimeMillis() < value.validUntil && (value.asset.expiresAt?.let { it > wallTimeMillis() } != false) &&
            preparationStillCurrent(value.target)

    private fun invalidatePreparation() {
        if (preparationTarget?.let(::preparationStillCurrent) == false) clearPreparation()
    }

    private fun releasePreparation() {
        preparationTarget = null
        prepareJob = null
        prepared = null
        mutableState.update { it.copy(nextEpisodeReady = false) }
    }

    private fun clearPreparation() {
        val job = prepareJob ?: return
        releasePreparation()
        job.cancel()
    }

    private fun recoverPlayback(owner: ActivePlayback) {
        val playback = playerInstance?.state?.value
        if (playback != null && belongsTo(owner, playback) && !playback.sourceRecoveryAllowed) return
        if (owner.localMedia != null || !owner.automaticIntent || !mutableState.value.settings.automaticSourceRecovery || active !== owner ||
            pending != null || mutableState.value.resolving) return
        // The saved count bounds retries of one media request in the player, not how many
        // independently verified same-episode candidates may be tried. Zero still opts out.
        if (mutableState.value.settings.automaticRecoveryAttempts == 0) return
        if (!sourceRecoveryWindowStarted) {
            sourceRecoveryWindowStarted = true
            // Native preparation has its own buffering limit. Its first terminal failure
            // gets one recovery window even when no frame rendered; never renew per source.
            autoPlaybackDeadline = monotonicTimeMillis() + 45_000L
        }
        if (monotonicTimeMillis() >= autoPlaybackDeadline) {
            mutableState.update { it.copy(playbackRecoveryStopReason = "本轮自动换线已到时限；可重试继续尝试本集片源。") }
            return
        }
        recoveryOptions += optionKey(owner.option)
        val hasAlternative = confirmedPlaybackLines(owner.episode.id).any { optionKey(it) !in recoveryOptions }
        if (!hasAlternative && !mutableState.value.sourceAggregationRunning) {
            mutableState.update { it.copy(playbackRecoveryStopReason = "当前已确认的本集线路均已尝试，暂无其他已确认线路。") }
            return
        }
        ++autoPlayGeneration
        autoPlayJob?.cancel()
        if (hasAlternative) resolveAndPlay(owner.episode.id, attempted = recoveryOptions.toSet())
        else autoAssociateAndPlay(owner.episode.id, attempted = recoveryOptions.toSet())
    }

    fun updatePlaybackSettings(settings: AnimePlaybackSettings) = mutate {
        library.setSettings(settings.validate())
        playerInstance?.configure(playerOptions(settings))
        if (!settings.prepareNextEpisode) clearPreparation()
        else active?.nextPreparationNotBefore = 0L
    }

    private fun playerOptions(settings: AnimePlaybackSettings) = AnimePlayerOptions(
        backgroundAudio = settings.backgroundAudio,
        bufferMinMs = settings.bufferMinMs,
        bufferMaxMs = settings.bufferMaxMs,
        bufferStartMs = settings.bufferStartMs,
        bufferRebufferMs = settings.bufferRebufferMs,
        automaticRecoveryAttempts = settings.automaticRecoveryAttempts,
        videoEnhancement = settings.videoEnhancement,
    )

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
            downloads.setPlaybackBusy(playbackBusy())
            downloads.enqueue(owner.detail.subject, owner.episode, owner.asset)
            notice("已加入离线下载，可在下载列表查看进度。")
        }
    }

    fun pauseDownload(id: String) = mutate { downloads.pause(id) }
    fun resumeDownload(id: String) = mutate { downloads.resume(id) }
    fun removeDownload(id: String) = mutate { downloads.remove(id) }

    fun playDownload(id: String) = openDownload(id, fallback = null)

    private fun openDownload(id: String, fallback: AnimeLibraryEntry?) {
        cancelPlaybackResolution()
        clearPreparation()
        val request = resolveGeneration
        mutableState.update { it.copy(resolving = true, sourceError = null) }
        resolveJob = scope.launch {
            try {
                library.awaitLoaded()
                val offline = downloads.playback(id)
                check(fallback == null || offline.subject.id == fallback.subject.id &&
                    offline.episode.id == fallback.resume?.episodeId && offline.asset.id == fallback.resume?.assetId) {
                    "离线内容与续看记录不一致。"
                }
                if (request != resolveGeneration) return@launch
                ++detailGeneration
                ++searchGeneration
                ++previewGeneration
                detailJob?.cancel(); boundCatalogJob?.cancel(); relatedJob?.cancel(); previewJob?.cancel()
                sourceSearchJobs.values.forEach { it.cancel() }
                sourceSearchJobs.clear()
                resumeRequest = null
                val cached = metadata.cachedDetail(offline.subject)
                if (request != resolveGeneration) return@launch
                val detail = cached?.takeIf { it.episodes.any { episode -> episode.id == offline.episode.id } }
                    ?: AnimeDetail(offline.subject, listOf(offline.episode))
                val catalog = AnimeSourceTitle(offline.asset.sourceId, id, offline.subject.title, "",
                    offline.subject.coverUrl, offline.subject.year)
                val native = AnimeSourceEpisode(offline.asset.sourceEpisodeId, offline.episode.title, "",
                    offline.episode.number, offline.episode.kind, edition = offline.asset.edition)
                val option = AnimeEpisodeOption(offline.asset.sourceId, "离线", catalog, native, confirmed = true)
                val previous = library.progressFor(offline.subject.id, offline.episode.id)
                val position = previous?.takeIf { !it.completed && it.assetId == offline.asset.id }?.positionMs ?: 0L
                mutableState.update { it.copy(detail = detail, detailLoading = false, detailError = null,
                    detailFromCache = cached != null, relatedSubjects = emptyList(), sourceResults = emptyList(),
                    sourceCatalogs = emptyList(), sourceBindings = emptyList(), sourceCatalogErrors = emptyMap(),
                    episodeOptions = emptyMap(), previewCatalog = null, previewLoading = false,
                    preferredSourceId = null, sourceGroups = emptyMap()) }
                startPlayback(PendingPlayback(detail, offline.episode, option, offline.asset, position, request,
                    localMedia = offline.localMedia))
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (request == resolveGeneration) {
                    if (fallback != null) {
                        openDetail(fallback.subject, fallback.resume?.episodeId)
                        notice("原离线文件不可用，已打开该集的关联来源。")
                    } else mutableState.update { it.copy(resolving = false,
                        sourceError = failure.message ?: "离线文件未能打开，请检查下载列表。") }
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

    fun clearWatchHistory(subjectId: String) = clearEntryHistory(subjectId, removeEntry = false)
    fun removeLibraryEntry(subjectId: String) = clearEntryHistory(subjectId, removeEntry = true)

    private fun clearEntryHistory(subjectId: String, removeEntry: Boolean) {
        if (active?.detail?.subject?.id == subjectId) stopPlayback(discardCheckpoint = true)
        else playbackEpochs[subjectId] = (playbackEpochs[subjectId] ?: 0L) + 1
        if (mutableState.value.detail?.subject?.id == subjectId) cancelPlaybackResolution()
        resumeRequest = resumeRequest?.takeUnless { it.first == subjectId }
        mutate {
            library.clearWatchHistory(subjectId, removeEntry)
            if (mutableState.value.detail?.subject?.id == subjectId && removeEntry) {
                mutableState.update { it.copy(sourceCatalogs = emptyList()) }
                rebuildOptions()
            }
            notice(if (removeEntry) "已移除这部番的本机记录。" else "已清除观看位置；追番状态与已看标记保留。")
        }
    }

    fun retryAutomaticSources() = sourceOperation("automatic-sources") { sources.extensions.retryAutomaticSources() }
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
        val account = mutableState.value.accounts.firstOrNull { it.providerId == providerId }
            ?: run { notice("请先连接这个追番账号。"); return }
        mutableState.update { it.copy(syncing = true, notice = null) }
        scope.launch {
            try {
                library.awaitReady()
                val remoteId = remoteSubjectId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: library.trackingTarget(detail.subject.id, providerId, account.ownerId)
                    ?: (if (providerId == "anime-bangumi") detail.subject.externalIds["bangumi"]
                        ?: detail.subject.id.removePrefix("bangumi:").takeIf { value -> value.isNotBlank() && value.all(Char::isDigit) } else null)
                    ?: error("请填写这个平台的番剧 ID，并确认对应的是同一季。")
                require(remoteId.isNotBlank() && remoteId.all(Char::isDigit)) { "番剧 ID 应为该平台的数字 ID。" }
                val remote = tracking.read(providerId, remoteId)
                check(tracking.account(providerId)?.ownerId == remote.ownerId && remote.ownerId == account.ownerId) {
                    "追番账号已经变化，请重新核对。"
                }
                library.setTrackingTarget(detail.subject.id, providerId, account.ownerId, remoteId)
                installReview(detail, remote)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { notice(failure.message ?: "远端进度读取失败，现有记录和差异保留。") }
            finally { mutableState.update { it.copy(syncing = false) } }
        }
    }

    private fun installReview(detail: AnimeDetail, remote: AnimeTrackingSnapshot) {
        val local = library.state.value.entries[detail.subject.id] ?: AnimeLibraryEntry(detail.subject)
        val localNumbers = mainNumbers(detail, local.completedEpisodeIds)
        val localStatus = local.status.takeIf { local.followed }
        val previousIds = mutableState.value.reviews.filter {
            it.subjectId == detail.subject.id && it.providerId == remote.providerId
        }.map { it.id }.toSet()
        previousIds.forEach(reviewSnapshots::remove)
        val review = AnimeTrackingReview(randomUuid(), detail.subject.id, detail.subject.title, remote.providerId,
            localStatus, remote.status, localNumbers, remote.completedNumbers, remote.remoteSubjectId, local.revision)
        val same = localStatus == remote.status && localNumbers == remote.completedNumbers
        if (!same) reviewSnapshots[review.id] = ReviewContext(detail, remote)
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
