package dev.mangaflow.anime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.anime.player.AnimePlayerPhase
import dev.mangaflow.platform.LocalWindowFullscreen
import dev.mangaflow.ui.*
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.mangaflow.ui.motion.*

private data class AnimeRoute(
    val key: String,
    val depth: Int,
    val detailState: AnimeScreenState? = null,
    val relatedDepth: Int = 0,
)
private data class AnimeDetailVisit(
    val detail: AnimeDetail, val related: List<AnimeSubject>,
    val candidates: List<AnimeSourceTitle>, val scores: List<AnimeSourceScore>,
)
private data class AnimeSourceAssociation(val title: AnimeSourceTitle, val subjectId: String)
internal enum class AnimeWorkspaceDestination(val label: String, val icon: ImageVector) {
    HOME("发现", Icons.Outlined.Explore), LIBRARY("追番", Icons.Outlined.Bookmarks),
    HISTORY("观看历史", Icons.Outlined.History), RANKING("榜单", Icons.Outlined.Leaderboard),
    SEASON("新番", Icons.Outlined.NewReleases), CALENDAR("番表", Icons.Outlined.CalendarMonth),
    GENRES("分类", Icons.Outlined.GridView), DIRECTORY("片源目录", Icons.Outlined.Public),
    DOWNLOADS("离线下载", Icons.Outlined.DownloadForOffline), TRACKING("追番账号", Icons.Outlined.AccountCircle),
    SOURCES("管理片源", Icons.Outlined.Tune),
}
private enum class AnimeWorkspacePage(val discovery: AnimeDiscoveryTab? = null) {
    HOME(AnimeDiscoveryTab.HOME), SEASON(AnimeDiscoveryTab.SEASON),
    CALENDAR(AnimeDiscoveryTab.CALENDAR), RANKING(AnimeDiscoveryTab.RANKING),
    GENRES(AnimeDiscoveryTab.GENRES), LIBRARY,
}
private enum class AnimeWorkspaceTool { SOURCES }
private enum class AnimeWorkspacePane { DISCOVERY, DIRECTORY, LIBRARY }

/** The root owns one controller/player. Pane identity never follows the player's ticking position. */
@Composable
internal fun AnimeWorkspace(
    controller: AnimeController,
    backdrop: HazeState,
    navigation: @Composable () -> Unit,
    navigationVisible: Boolean = true,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    searchRequest: Int = 0,
    backRequest: Int = 0,
    browseRequest: Int = 0,
    onBrowseRequestConsumed: () -> Unit = {},
    onPlaybackVisibility: (Boolean) -> Unit = {},
    requestedDestination: AnimeWorkspaceDestination = AnimeWorkspaceDestination.HOME,
    navigationRequest: Int = 0,
    onDestinationChange: (AnimeWorkspaceDestination) -> Unit = {},
    onOpenMangaDownloads: () -> Unit = {},
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val playbackPhase by controller.playbackPhase.collectAsStateWithLifecycle()
    val sourceBrowseState by controller.sourceBrowse.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var directoryOpen by rememberSaveable { mutableStateOf(false) }
    var consumedNavigationRequest by rememberSaveable { mutableIntStateOf(0) }
    val page = if (tab == 1) AnimeWorkspacePage.LIBRARY else
        AnimeWorkspacePage.entries.firstOrNull { it.discovery == state.discoveryTab } ?: AnimeWorkspacePage.HOME
    val pane = when {
        directoryOpen -> AnimeWorkspacePane.DIRECTORY
        tab == 1 -> AnimeWorkspacePane.LIBRARY
        else -> AnimeWorkspacePane.DISCOVERY
    }
    val discoveryScrolls = remember { AnimeDiscoveryScrolls() }
    val libraryScroll = rememberLazyListState()
    val historyScroll = rememberLazyListState()
    val libraryViewState = rememberAnimeLibraryViewState()
    val baseDestination = when {
        directoryOpen -> AnimeWorkspaceDestination.DIRECTORY
        tab == 1 -> if (libraryViewState.filter == 2) AnimeWorkspaceDestination.HISTORY else AnimeWorkspaceDestination.LIBRARY
        else -> when (page) {
            AnimeWorkspacePage.HOME -> AnimeWorkspaceDestination.HOME
            AnimeWorkspacePage.SEASON -> AnimeWorkspaceDestination.SEASON
            AnimeWorkspacePage.CALENDAR -> AnimeWorkspaceDestination.CALENDAR
            AnimeWorkspacePage.RANKING -> AnimeWorkspaceDestination.RANKING
            AnimeWorkspacePage.GENRES -> AnimeWorkspaceDestination.GENRES
            AnimeWorkspacePage.LIBRARY -> AnimeWorkspaceDestination.LIBRARY
        }
    }
    val directoryDetailOpen = directoryOpen && sourceBrowseState.selectedTitle != null
    val detailState = rememberSaveableStateHolder()
    val detailScrolls = remember { mutableMapOf<String, LazyListState>() }
    val detailHistory = remember(controller) { mutableStateListOf<AnimeDetailVisit>() }
    val searchScroll = rememberLazyListState()
    val search by controller.searchState.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(controller.searchState.value.query) }
    var consumedSearchRequest by rememberSaveable { mutableIntStateOf(0) }
    var consumedBackRequest by rememberSaveable { mutableIntStateOf(0) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var returnToSearch by rememberSaveable { mutableStateOf(false) }
    var focusSearchInput by rememberSaveable { mutableStateOf(true) }
    var searchPresent by remember { mutableStateOf(false) }
    var searchForeground by remember { mutableStateOf<State<Float>?>(null) }
    var toolsVisible by remember { mutableStateOf(false) }
    var toolsPresent by remember { mutableStateOf(false) }
    var layoutPresent by remember { mutableStateOf(false) }
    var pendingTool by remember { mutableStateOf<AnimeWorkspaceTool?>(null) }
    var sourcesVisible by remember { mutableStateOf(false) }
    var sourceManagerVisible by remember { mutableStateOf(false) }
    var downloadsVisible by remember { mutableStateOf(false) }
    var storageVisible by remember { mutableStateOf(false) }
    var storagePresent by remember { mutableStateOf(false) }
    var storageModelsVisible by remember { mutableStateOf(false) }
    var storageDestination by remember { mutableStateOf<String?>(null) }
    var browseCandidate by remember { mutableStateOf<AnimeSourceTitle?>(null) }
    var pendingAssociation by remember { mutableStateOf<AnimeSourceAssociation?>(null) }
    var sourceEpisode by remember { mutableStateOf<AnimeEpisode?>(null) }
    var trackingVisible by remember { mutableStateOf(false) }
    var reviewsVisible by remember { mutableStateOf(false) }
    var openingSubject by remember { mutableStateOf<AnimeSubject?>(null) }
    var openingReference by remember { mutableStateOf<AnimeSourceTitle?>(null) }
    val windowFullscreen = LocalWindowFullscreen.current
    var layoutFullscreen by rememberSaveable { mutableStateOf(false) }
    val playerFullscreen = windowFullscreen?.active ?: layoutFullscreen
    fun setPlayerFullscreen(enabled: Boolean) {
        if (windowFullscreen != null) windowFullscreen.setActive(enabled)
        else layoutFullscreen = enabled
    }
    var recheckRequest by remember { mutableStateOf<AnimeTrackingReview?>(null) }
    val playing = state.playbackTarget != null || state.resolving ||
        playbackPhase != AnimePlayerPhase.IDLE && playbackPhase != AnimePlayerPhase.RELEASED
    val route = when {
        playing -> AnimeRoute("player", 2, relatedDepth = detailHistory.size)
        state.detail != null || state.detailLoading || state.detailError != null ->
            AnimeRoute("detail:" + (state.detail?.subject?.id ?: openingSubject?.id.orEmpty()), 1, state, detailHistory.size)
        else -> AnimeRoute("home", 0)
    }
    val overlayVisible = downloadsVisible || sourcesVisible || sourceManagerVisible || trackingVisible || reviewsVisible ||
        storagePresent || storageModelsVisible
    val displayedDestination = when {
        sourceManagerVisible -> AnimeWorkspaceDestination.SOURCES
        downloadsVisible -> AnimeWorkspaceDestination.DOWNLOADS
        trackingVisible -> AnimeWorkspaceDestination.TRACKING
        else -> baseDestination
    }
    val currentDestinationChange by rememberUpdatedState(onDestinationChange)
    LaunchedEffect(active, displayedDestination, navigationRequest, consumedNavigationRequest) {
        if (active && navigationRequest == consumedNavigationRequest) currentDestinationChange(displayedDestination)
    }
    val rootActive = active && route.depth == 0 && !searchPresent && !toolsPresent && !layoutPresent && !overlayVisible
    LaunchedEffect(active, route.key, page, directoryOpen) {
        toolsVisible = false
        pendingTool = null
        if (!active) { storageVisible = false; storageModelsVisible = false; storageDestination = null }
    }
    val currentPlaybackVisibility by rememberUpdatedState(onPlaybackVisibility)
    LaunchedEffect(playing) { currentPlaybackVisibility(playing) }
    DisposableEffect(controller) {
        onDispose { currentPlaybackVisibility(false) }
    }
    val reportWindowPlayback = windowFullscreen?.onPlaybackVisibilityChange
    DisposableEffect(reportWindowPlayback, playing) {
        if (playing) reportWindowPlayback?.invoke(true)
        onDispose { if (playing) reportWindowPlayback?.invoke(false) }
    }
    val discoveryVisible = rootActive && pane == AnimeWorkspacePane.DISCOVERY
    DisposableEffect(controller, discoveryVisible) {
        if (discoveryVisible) controller.setDiscoveryVisible(true)
        onDispose { if (discoveryVisible) controller.setDiscoveryVisible(false) }
    }
    LaunchedEffect(controller, directoryOpen) {
        if (directoryOpen) controller.sourceBrowse.activate()
    }
    LaunchedEffect(browseRequest) {
        if (browseRequest != 0) {
            layoutFullscreen = false
            pendingAssociation = null
            browseCandidate = null
            tab = 0
            directoryOpen = false
            searchExpanded = false
            returnToSearch = false
            detailHistory.clear()
            toolsVisible = false
            pendingTool = null
            sourcesVisible = false
            sourceManagerVisible = false
            downloadsVisible = false
            trackingVisible = false
            reviewsVisible = false
            onBrowseRequestConsumed()
        }
    }
    LaunchedEffect(state.discoveryTab) {
        if (state.discoveryTab == AnimeDiscoveryTab.SOURCES) controller.selectDiscoveryTab(AnimeDiscoveryTab.HOME)
    }
    LaunchedEffect(searchRequest, active) {
        if (active && searchRequest != consumedSearchRequest) {
            consumedSearchRequest = searchRequest
            layoutFullscreen = false
            controller.closePlayback()
            controller.closeDetail()
            toolsVisible = false
            pendingTool = null
            sourcesVisible = false
            sourceManagerVisible = false
            downloadsVisible = false
            browseCandidate = null
            pendingAssociation = null
            trackingVisible = false
            reviewsVisible = false
            directoryOpen = false
            returnToSearch = false
            detailHistory.clear()
            focusSearchInput = true
            searchExpanded = true
        }
    }
    LaunchedEffect(state.detail?.subject?.id) {
        sourceEpisode = null
        sourcesVisible = false
    }

    fun closePlayback() {
        layoutFullscreen = false
        controller.closePlayback()
    }
    fun submitSearch(term: String) {
        val next = term.trim()
        val provider = if (browseCandidate != null) AnimeDiscoveryProvider.BANGUMI else AnimeDiscoveryProvider.DOUBAN
        val previous = controller.searchState.value
        if (previous.query != next || !previous.indexOnly || previous.catalogProvider != provider)
            searchScroll.requestScrollToItem(0)
        controller.search(next, indexOnly = true, provider = provider)
    }
    fun beginDetailNavigation() {
        openingReference = null
        if (route.depth == 0) {
            detailHistory.clear()
            returnToSearch = searchExpanded
        }
        layoutFullscreen = false
        searchExpanded = false
    }
    fun open(subject: AnimeSubject) {
        if (pendingAssociation?.subjectId != subject.id) pendingAssociation = null
        beginDetailNavigation()
        openingSubject = subject
        controller.open(subject)
    }
    fun openRelated(subject: AnimeSubject) {
        val latest = controller.state.value
        val current = latest.detail ?: return
        if (current.subject.id == subject.id) return
        detailHistory += AnimeDetailVisit(current, latest.relatedSubjects, latest.groupSourceCandidates, latest.detailScores)
        open(subject)
    }
    fun returnFromDetail() {
        pendingAssociation = null
        if (detailHistory.isNotEmpty()) {
            val previous = detailHistory.removeAt(detailHistory.lastIndex)
            openingSubject = previous.detail.subject
            controller.returnToVisitedDetail(previous.detail, previous.related, previous.candidates, previous.scores)
            val candidates = controller.searchState.value.groups
                .firstOrNull { returnToSearch && it.subject?.id == previous.detail.subject.id }?.sources.orEmpty()
            if (candidates.isNotEmpty()) controller.stageSearchCandidates(previous.detail.subject.id, candidates)
        } else {
            openingSubject = null
            controller.closeDetail()
            if (returnToSearch) {
                focusSearchInput = false
                searchExpanded = true
            }
            returnToSearch = false
        }
    }
    fun resume(entry: AnimeLibraryEntry) {
        pendingAssociation = null
        browseCandidate = null
        beginDetailNavigation()
        openingSubject = entry.subject
        controller.resume(entry.subject.id)
    }
    fun playDownload(selection: AnimeDownloadSelection) {
        downloadsVisible = false
        beginDetailNavigation()
        pendingAssociation = null
        browseCandidate = null
        openingSubject = controller.downloads.state.value.entries.firstOrNull { it.id == selection.downloadId }?.subject
        when {
            selection.originalOnly -> controller.playOriginalDownload(selection.downloadId)
            selection.versionId != null -> controller.playEnhancedDownload(selection.downloadId, selection.versionId)
            else -> controller.playDownload(selection.downloadId)
        }
    }
    fun chooseReference(title: AnimeSourceTitle, videoSource: Boolean) {
        if (!videoSource && title.sourceId in setOf("anime-douban", "anime-lanerc-douban")) {
            pendingAssociation = null
            browseCandidate = null
            beginDetailNavigation()
            openingSubject = null
            openingReference = title
            controller.openCatalogueReference(title)
            return
        }
        controller.knownDiscoverySubject(title, allowIndexIdentity = !videoSource)?.let { known ->
            browseCandidate = null
            pendingAssociation = if (videoSource && known.externalIds["native-source"] == null &&
                !controller.stillBound(known.id, title))
                AnimeSourceAssociation(title, known.id) else null
            open(known)
            return
        }
        if (videoSource) {
            pendingAssociation = null
            browseCandidate = null
            beginDetailNavigation()
            openingSubject = controller.sourceSubject(title)
            controller.openSourceTitle(title)
            return
        }
        browseCandidate = title
        query = title.title
        submitSearch(title.title)
        focusSearchInput = true
        searchExpanded = true
    }
    fun openDiscoveryItem(item: AnimeDiscoveryItem) {
        val subject = item.subject
        if (subject != null) {
            pendingAssociation = null
            browseCandidate = null
            beginDetailNavigation()
            openingSubject = subject
            controller.openDiscoveryItem(item)
        } else item.reference?.let { chooseReference(it, false) }
    }
    fun openSearchResult(subject: AnimeSubject) {
        val group = if (browseCandidate == null)
            controller.searchState.value.groups.firstOrNull { it.subject?.id == subject.id } else null
        pendingAssociation = null
        if (group == null) open(subject)
        else {
            beginDetailNavigation()
            openingSubject = subject
            controller.openSearchGroup(group)
        }
    }
    fun dismissSources() {
        sourcesVisible = false
        controller.dismissSourcePreview()
    }
    fun showSources(episode: AnimeEpisode? = null) {
        controller.cancelPendingResume()
        sourceEpisode = episode
        sourcesVisible = true
    }
    fun requestPlayback(episode: AnimeEpisode, option: AnimeEpisodeOption?) {
        if (option == null && controller.episodeAvailability(episode.id) == AnimeEpisodeAvailability.UPCOMING) {
            showSources(episode)
            return
        }
        controller.dismissNotice()
        controller.play(episode.id, option)
    }
    fun openDirectory(sourceId: String? = null) {
        controller.sourceBrowse.activate(sourceId)
        directoryOpen = true
    }
    fun returnFromDirectory() {
        if (controller.sourceBrowse.state.value.selectedTitle != null) controller.sourceBrowse.closeTitle()
        else directoryOpen = false
    }
    fun back() {
        when {
            toolsVisible -> { toolsVisible = false; pendingTool = null }
            sourceManagerVisible -> sourceManagerVisible = false
            downloadsVisible -> downloadsVisible = false
            sourcesVisible -> dismissSources()
            trackingVisible -> trackingVisible = false
            reviewsVisible -> reviewsVisible = false
            searchExpanded -> { searchExpanded = false; browseCandidate = null }
            playing && playerFullscreen -> setPlayerFullscreen(false)
            playing -> closePlayback()
            route.depth == 1 -> returnFromDetail()
            directoryOpen -> returnFromDirectory()
            baseDestination != AnimeWorkspaceDestination.HOME -> {
                tab = 0
                controller.selectDiscoveryTab(AnimeDiscoveryTab.HOME)
            }
        }
    }

    fun queueTool(tool: AnimeWorkspaceTool) {
        pendingTool = tool
        toolsVisible = false
    }

    fun finishToolMenu(present: Boolean) {
        toolsPresent = present
        if (present) return
        val next = pendingTool
        pendingTool = null
        if (!active || route.depth != 0 || searchExpanded) return
        when (next) {
            AnimeWorkspaceTool.SOURCES -> sourceManagerVisible = true
            null -> Unit
        }
    }

    LaunchedEffect(navigationRequest, active) {
        if (!active || navigationRequest == 0 || navigationRequest == consumedNavigationRequest) return@LaunchedEffect
        consumedNavigationRequest = navigationRequest
        layoutFullscreen = false
        controller.closeDetail()
        pendingAssociation = null
        browseCandidate = null
        sourceEpisode = null
        openingSubject = null
        recheckRequest = null
        searchExpanded = false
        returnToSearch = false
        detailHistory.clear()
        toolsVisible = false
        pendingTool = null
        sourcesVisible = false
        sourceManagerVisible = false
        downloadsVisible = false
        trackingVisible = false
        reviewsVisible = false
        when (requestedDestination) {
            AnimeWorkspaceDestination.DOWNLOADS -> downloadsVisible = true
            AnimeWorkspaceDestination.TRACKING -> trackingVisible = true
            AnimeWorkspaceDestination.SOURCES -> sourceManagerVisible = true
            AnimeWorkspaceDestination.DIRECTORY -> {
                controller.sourceBrowse.closeTitle()
                openDirectory()
            }
            AnimeWorkspaceDestination.LIBRARY, AnimeWorkspaceDestination.HISTORY -> {
                directoryOpen = false
                tab = 1
                libraryViewState.filter = if (requestedDestination == AnimeWorkspaceDestination.HISTORY) 2 else 1
            }
            else -> {
                directoryOpen = false
                tab = 0
                controller.selectDiscoveryTab(when (requestedDestination) {
                    AnimeWorkspaceDestination.SEASON -> AnimeDiscoveryTab.SEASON
                    AnimeWorkspaceDestination.CALENDAR -> AnimeDiscoveryTab.CALENDAR
                    AnimeWorkspaceDestination.RANKING -> AnimeDiscoveryTab.RANKING
                    AnimeWorkspaceDestination.GENRES -> AnimeDiscoveryTab.GENRES
                    else -> AnimeDiscoveryTab.HOME
                })
            }
        }
    }

    LaunchedEffect(backRequest, active) {
        if (active && backRequest != consumedBackRequest) { consumedBackRequest = backRequest; back() }
    }
    LaunchedEffect(state.detail, state.detailLoading, state.detailError, recheckRequest) {
        val review = recheckRequest
        if (review != null && state.detail?.subject?.id == review.subjectId && !state.detailLoading) {
            recheckRequest = null
            if (state.detailError == null) controller.reviewTracking(review.providerId, review.remoteSubjectId)
        }
    }
    LaunchedEffect(state.detail?.subject?.id, state.detailLoading, pendingAssociation) {
        val association = pendingAssociation ?: return@LaunchedEffect
        if (state.detail?.subject?.id == association.subjectId && !state.detailLoading) {
            pendingAssociation = null
            sourceEpisode = null
            controller.previewSource(association.title)
            sourcesVisible = true
        }
    }

    val canGoBack = route.depth > 0 || baseDestination != AnimeWorkspaceDestination.HOME
    MotionBackHandler(active && canGoBack && !overlayVisible && !searchPresent && !toolsPresent && !layoutPresent,
        state = null, onBack = ::back)

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            MotionTabContent(selected = route, contentKey = { it.key }, order = { it.depth + it.relatedDepth },
                modifier = Modifier.fillMaxSize()) { shown ->
                if (shown.depth == 2) {
                    val playback by controller.player.state.collectAsStateWithLifecycle()
                    AnimePlayerPane(controller, state, playback, backdrop,
                        active = active && shown.key == route.key, fullscreen = playerFullscreen,
                        onFullscreenChange = ::setPlayerFullscreen,
                        obscured = overlayVisible || state.pendingPlayback != null,
                        onBack = ::closePlayback,
                        onRetry = controller::retryPlayback,
                        onKeepPlayback = controller::cancelPlaybackResolution,
                        onRestorePlayback = controller::restorePreviousPlayback,
                        onPlayDownload = ::playDownload,
                        onSources = ::showSources,
                        onEpisode = { requestPlayback(it, null) }, modifier = Modifier.fillMaxSize(),
                        onStorage = { storagePresent = true; storageVisible = true })
                } else {
                    val shownState = shown.detailState
                    val shownDetail = shownState?.detail
                    Column(Modifier.fillMaxSize()) {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val preferred = animePreferredColumns(maxWidth, state.discoveryColumns)
                            val actualColumns = animeDiscoveryColumns(maxWidth, state.discoveryColumns)
                            AnimeWorkspaceHeader(root = shown.depth == 0 && !directoryDetailOpen,
                                title = if (shown.depth == 0) (if (directoryDetailOpen) sourceBrowseState.selectedTitle?.title.orEmpty()
                                    else "番剧 · ${baseDestination.label}") else
                                    shownDetail?.subject?.title ?: openingSubject?.title ?: "番剧详情",
                                backdrop = backdrop, navigationVisible = navigationVisible,
                                navigation = {
                                    if (shown.depth == 0 && !directoryDetailOpen) navigation()
                                    else MotionIconButton(onClick = {
                                        if (shown.depth == 0) returnFromDirectory()
                                        else returnFromDetail()
                                    },
                                        modifier = Modifier.size(44.dp), tooltip = "返回") {
                                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", Modifier.size(20.dp))
                                    }
                                },
                                actions = if (shown.depth != 0 || directoryDetailOpen) null else ({
                                    Row(Modifier.heightIn(min = 44.dp)
                                        .graphicsLayer { alpha = if (searchExpanded) 0f else
                                            1f - (searchForeground?.value ?: 0f).coerceIn(0f, 1f) }
                                        .motionInputGate(active && route.depth == 0 && !searchPresent && !overlayVisible),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        if (tab == 0 || directoryOpen) DiscoveryLayoutControl(preferred, actualColumns, backdrop,
                                            active && route.depth == 0 && !searchPresent && !toolsPresent && !overlayVisible,
                                            onChange = controller::setDiscoveryColumns,
                                            onPresenceChange = { layoutPresent = it })
                                        Box {
                                            MotionIconButton(onClick = { pendingTool = null; toolsVisible = !toolsVisible },
                                                modifier = Modifier.size(44.dp), enabled = !layoutPresent && (toolsVisible || !toolsPresent),
                                                tooltip = "番剧菜单") {
                                                Icon(Icons.Outlined.MoreHoriz, "番剧菜单", Modifier.size(21.dp))
                                            }
                                            CompactGlassPopover(toolsVisible, backdrop,
                                                onDismiss = { toolsVisible = false; pendingTool = null },
                                                onPresenceChange = ::finishToolMenu) {
                                                if (tab == 0 || directoryOpen)
                                                    CompactMenuAction(Icons.Outlined.Refresh, "刷新当前栏目",
                                                        enabled = if (directoryOpen) !sourceBrowseState.loading && !sourceBrowseState.homeLoading
                                                            else state.discoveryFeeds[animeDiscoveryFeedKey(state.discoveryTab)]?.loading != true,
                                                        onClick = {
                                                            pendingTool = null
                                                            toolsVisible = false
                                                            if (directoryOpen) controller.sourceBrowse.refresh() else controller.refreshDiscovery()
                                                        })
                                                CompactMenuAction(Icons.Outlined.Tune, "管理片源",
                                                    onClick = { queueTool(AnimeWorkspaceTool.SOURCES) })
                                            }
                                        }
                                        Spacer(Modifier.size(44.dp))
                                    }
                                }))
                        }
                        if (shown.depth == 0) {
                            MotionTabContent(pane, Modifier.weight(1f).mangaHazeSource(backdrop).motionInputGate(rootActive),
                                contentKey = { it }, order = { it.ordinal }) { shownPane ->
                                val paneActive = rootActive && shownPane == pane
                                when (shownPane) {
                                    AnimeWorkspacePane.DISCOVERY -> AnimeDiscovery(state, controller, backdrop, discoveryScrolls,
                                        onOpenItem = ::openDiscoveryItem,
                                        onResume = ::resume, active = paneActive)
                                    AnimeWorkspacePane.DIRECTORY -> AnimeSourceBrowsePane(controller, backdrop, discoveryScrolls,
                                        columns = state.discoveryColumns, active = paneActive,
                                        onChooseTitle = ::chooseReference, contentBottomPadding = 24.dp)
                                    AnimeWorkspacePane.LIBRARY -> AnimeLibrary(state,
                                        if (libraryViewState.filter == 2) historyScroll else libraryScroll, controller, backdrop,
                                        onOpen = ::open, onResume = ::resume,
                                        onDiscover = { tab = 0; controller.selectDiscoveryTab(AnimeDiscoveryTab.HOME) },
                                        onReview = { reviewsVisible = true }, viewState = libraryViewState,
                                        active = paneActive, contentBottomPadding = 24.dp)
                                }
                            }
                        } else if (shownState != null && shownDetail != null) {
                            Box(Modifier.weight(1f).mangaHazeSource(backdrop)) {
                                detailState.SaveableStateProvider(shown.key) {
                                    AnimeDetailPane(shownDetail, shownState, controller, backdrop,
                                        remember(shownDetail.subject.id) {
                                            detailScrolls.getOrPut(shownDetail.subject.id) { LazyListState() }
                                        },
                                        onPlay = { requestPlayback(it, null) }, onOpenRelated = ::openRelated, onSources = ::showSources,
                                        onTracking = { trackingVisible = true }, modifier = Modifier.fillMaxSize(),
                                        active = active && shown.key == route.key && !overlayVisible && !searchPresent)
                                }
                            }
                        } else Box(Modifier.weight(1f)) {
                            AnimeMessage(if (state.detailLoading) "正在载入番剧" else "番剧详情未取得",
                                state.detailError ?: openingSubject?.title ?: openingReference?.title,
                                action = if (state.detailLoading) null else "重试",
                                onAction = {
                                    openingReference?.let(controller::openCatalogueReference)
                                        ?: openingSubject?.let(controller::open)
                                })
                        }
                    }
                }
            }
            if (!playing && (route.depth == 0 && !directoryDetailOpen || searchExpanded || searchPresent)) ExpandingSearchBar(expanded = searchExpanded, query = query,
                active = active && route.depth == 0 && (!directoryDetailOpen || searchExpanded || searchPresent) &&
                    !overlayVisible && !toolsPresent && !layoutPresent, backdrop = backdrop,
                onExpandedChange = {
                    focusSearchInput = true
                    searchExpanded = it
                    if (!it) browseCandidate = null
                },
                onPresenceChange = { searchPresent = it },
                onForegroundState = { searchForeground = it }, onQuery = { query = it },
                onSearch = { submitSearch(query) }, placeholder = "搜索番剧", focusInputOnExpand = focusSearchInput,
                modifier = Modifier.fillMaxSize()
                    .windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Top))
                    .padding(horizontal = 8.dp, vertical = 2.dp)) { height, inputActive ->
                val matchingReference = browseCandidate != null
                val catalogProvider = if (matchingReference) AnimeDiscoveryProvider.BANGUMI else AnimeDiscoveryProvider.DOUBAN
                LaunchedEffect(searchExpanded, catalogProvider, search.query, search.indexOnly, search.catalogProvider) {
                    if (searchExpanded && search.query.isNotBlank() && search.query == query.trim() &&
                        (!search.indexOnly || search.catalogProvider != catalogProvider)) submitSearch(search.query)
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = height).motionInputGate(inputActive),
                    state = searchScroll, userScrollEnabled = inputActive,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                    browseCandidate?.let { candidate -> item(key = "source-association") {
                        AnimeSectionTitle("选择对应番剧", candidate.title + " · " +
                            if (candidate.sourceId in setOf("anime-anilist", "anime-bangumi")) animeProviderName(candidate.sourceId)
                            else animeSourceName(candidate.sourceId, state), action = {
                            MotionIconButton(onClick = { browseCandidate = null }, modifier = Modifier.size(44.dp),
                                tooltip = "取消来源关联") { Icon(Icons.Outlined.Close, "取消来源关联", Modifier.size(18.dp)) }
                        })
                    } }
                    if (query.isBlank() && state.searchHistory.isNotEmpty()) {
                        item(key = "search-history-header") {
                            AnimeSectionTitle("最近搜索", action = {
                                MotionIconButton(onClick = controller::clearSearchHistory, modifier = Modifier.size(44.dp),
                                    tooltip = "清除搜索历史") { Icon(Icons.Outlined.DeleteOutline, "清除搜索历史", Modifier.size(18.dp)) }
                            })
                        }
                        items(state.searchHistory, key = { "search-history:$it" }) { keyword ->
                            AnimeSelection(keyword, selected = false, onClick = {
                                query = keyword
                                submitSearch(keyword)
                            })
                        }
                    }
                    when {
                        query.isBlank() -> item { AnimeQuietLabel("输入名称、原名或别名", Modifier.padding(16.dp)) }
                        query.trim() != search.query -> item {
                            AnimeMessage("搜索番剧名称", null, action = "搜索",
                                onAction = { submitSearch(query) })
                        }
                        search.catalogProvider != catalogProvider || !search.indexOnly -> item {
                            AnimeMessage("正在搜索", "正在查作品资料")
                        }
                        else -> animeSearchResults(search, controller, matchingReference,
                            onOpenSubject = { openSearchResult(it) },
                            onBrowseSources = {
                                browseCandidate = null
                                searchExpanded = false
                                openDirectory()
                            })
                    }
                }
            }
        }
        if (!playing && (state.notice != null || state.canUndo)) AnimeNotice(state.notice, state.canUndo,
            onUndo = controller::undo, onDismiss = controller::dismissNotice)
    }

    if (storagePresent) {
        val context = dev.mangaflow.platform.LocalMangaContext.current
        val services = remember(context) { dev.mangaflow.MangaApplicationServices.get(context) }
        StorageManagementDialog(storageVisible, backdrop, services.library, services.repository, active,
            onDismiss = { storageVisible = false },
            onDownloads = { storageDestination = "manga"; storageVisible = false },
            onAnimeDownloads = { storageDestination = "anime"; storageVisible = false },
            onModels = { storageDestination = "models"; storageVisible = false },
            onClosed = {
                val destination = storageDestination
                storageDestination = null
                storagePresent = false
                if (active) when (destination) {
                    "anime" -> downloadsVisible = true
                    "models" -> storageModelsVisible = true
                    "manga" -> onOpenMangaDownloads()
                    else -> Unit
                }
            })
    }
    dev.mangaflow.reader.panels.PanelModelsDialog(storageModelsVisible, backdrop) { storageModelsVisible = false }
    AnimeDownloadsPanel(downloadsVisible, controller, state, backdrop,
        onDismiss = { downloadsVisible = false }, onPlay = ::playDownload)
    AnimeSourceManager(sourceManagerVisible, state, controller, backdrop,
        onDismiss = { sourceManagerVisible = false }, onBrowse = { provider ->
            if (active) {
                directoryOpen = false
                tab = 0
                controller.configureDiscovery(AnimeDiscoveryTab.RANKING,
                    AnimeDiscoveryQuery(provider = provider, order = when (provider) {
                        AnimeDiscoveryProvider.BANGUMI -> AnimeDiscoveryOrder.RANK
                        AnimeDiscoveryProvider.ANILIST -> AnimeDiscoveryOrder.TRENDING
                        AnimeDiscoveryProvider.DOUBAN -> AnimeDiscoveryOrder.POPULARITY
                    }))
            }
        })
    AnimeSourcesPanel(sourcesVisible, state, controller, backdrop, sourceEpisode,
        recovering = playbackPhase == AnimePlayerPhase.ERROR || state.playbackTarget?.error != null,
        onDismiss = ::dismissSources, onAutomaticPlay = { episode ->
            sourcesVisible = false
            requestPlayback(episode, null)
        }, onPlay = { episodeId, option ->
            sourcesVisible = false
            state.detail?.episodes?.firstOrNull { it.id == episodeId }?.let { requestPlayback(it, option) }
        }, onOpenSourceWork = { chooseReference(it, true) })
    AnimeTrackingPanel(trackingVisible, state, controller, backdrop,
        onDismiss = { trackingVisible = false }, onReview = { reviewsVisible = true })
    AnimeProgressReviewPanel(reviewsVisible, state, controller, backdrop, onDismiss = { reviewsVisible = false },
        onConnect = { reviewsVisible = false; trackingVisible = true },
        onRecheck = { review ->
            if (state.detail?.subject?.id == review.subjectId && !state.detailLoading)
                controller.reviewTracking(review.providerId, review.remoteSubjectId)
            else {
                recheckRequest = review
                open(state.library.firstOrNull { it.subject.id == review.subjectId }?.subject ?:
                    AnimeSubject(review.subjectId, review.subjectTitle))
            }
        })
    AnimeResumePanel(state.pendingPlayback, backdrop, onChoose = controller::confirmPlayback,
        onDismiss = controller::dismissPlaybackChoice)
}

/** Desktop has a permanent navigation rail, so it must not receive an empty menu surface. */
@Composable
private fun AnimeWorkspaceHeader(root: Boolean, title: String, backdrop: HazeState,
    navigationVisible: Boolean, navigation: @Composable () -> Unit,
    actions: (@Composable () -> Unit)?) {
    val headerModifier = Modifier.windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Top))
        .padding(horizontal = 8.dp, vertical = 2.dp)
    val heading: @Composable () -> Unit = {
        Text(title, style = if (root) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (root && !navigationVisible) {
        Row(headerModifier.fillMaxWidth().heightIn(min = 44.dp).semantics { paneTitle = title }, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).padding(start = 8.dp)) { heading() }
            if (actions != null) LiquidGlassSurface(backdrop, cornerRadius = 22.dp, role = GlassSurfaceRole.NAVIGATION) {
                actions()
            }
        }
    } else PageTitleHeader(title, backdrop, navigation, modifier = headerModifier,
        summary = heading, actions = actions)
}

internal fun animeSubjectSummary(subject: AnimeSubject): String =
    listOfNotNull(subject.year?.toString(), subject.episodeCount?.takeIf { it > 0 }?.let { it.toString() + " 集" })
        .joinToString(" · ").ifBlank { subject.originalTitle.ifBlank { "Bangumi 条目" } }

@Composable
private fun AnimeNotice(notice: String?, canUndo: Boolean, onUndo: () -> Unit, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)
        .navigationBarsPadding().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(notice ?: "上次改动可以撤销", Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (canUndo && notice == null) MotionTextButton(onClick = onUndo, modifier = Modifier.heightIn(min = 44.dp)) { Text("撤销") }
        if (notice != null) MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭提示") {
            Icon(Icons.Outlined.Close, "关闭提示", Modifier.size(18.dp))
        }
    }
}
