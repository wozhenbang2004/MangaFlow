package dev.mangaflow.anime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.anime.player.AnimePlayerPhase
import dev.mangaflow.ui.*
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.mangaflow.ui.motion.*

private data class AnimeRoute(val key: String, val depth: Int, val detail: AnimeDetail?)
private data class AnimeSourceAssociation(val title: AnimeSourceTitle, val subjectId: String)
private enum class AnimeWorkspacePage(val label: String, val discovery: AnimeDiscoveryTab? = null) {
    HOME("发现", AnimeDiscoveryTab.HOME), SEASON("新番", AnimeDiscoveryTab.SEASON),
    CALENDAR("番表", AnimeDiscoveryTab.CALENDAR), RANKING("榜单", AnimeDiscoveryTab.RANKING),
    GENRES("分类", AnimeDiscoveryTab.GENRES), LIBRARY("追番"),
}
private enum class AnimeWorkspaceTool { TRACKING, DOWNLOADS, DIRECTORY }
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
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val playbackPhase by controller.playbackPhase.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var directoryOpen by rememberSaveable { mutableStateOf(false) }
    val page = if (tab == 1) AnimeWorkspacePage.LIBRARY else
        AnimeWorkspacePage.entries.firstOrNull { it.discovery == state.discoveryTab } ?: AnimeWorkspacePage.HOME
    val pane = when {
        directoryOpen -> AnimeWorkspacePane.DIRECTORY
        tab == 1 -> AnimeWorkspacePane.LIBRARY
        else -> AnimeWorkspacePane.DISCOVERY
    }
    val homeSource = rememberSaveable { mutableStateOf<String?>(null) }
    val discoveryScrolls = remember(homeSource) { AnimeDiscoveryScrolls(homeSource) }
    val libraryScroll = rememberLazyListState()
    val libraryViewState = rememberAnimeLibraryViewState()
    val detailState = rememberSaveableStateHolder()
    val detailScrolls = remember { mutableMapOf<String, LazyListState>() }
    var query by rememberSaveable { mutableStateOf(controller.searchState.value.query) }
    var consumedSearchRequest by remember { mutableIntStateOf(searchRequest) }
    var consumedBackRequest by remember { mutableIntStateOf(backRequest) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var searchPresent by remember { mutableStateOf(false) }
    var searchForeground by remember { mutableStateOf<State<Float>?>(null) }
    var toolsVisible by remember { mutableStateOf(false) }
    var toolsPresent by remember { mutableStateOf(false) }
    var layoutPresent by remember { mutableStateOf(false) }
    var pendingTool by remember { mutableStateOf<AnimeWorkspaceTool?>(null) }
    var sourcesVisible by remember { mutableStateOf(false) }
    var downloadsVisible by remember { mutableStateOf(false) }
    var browseCandidate by remember { mutableStateOf<AnimeSourceTitle?>(null) }
    var pendingAssociation by remember { mutableStateOf<AnimeSourceAssociation?>(null) }
    var sourceEpisode by remember { mutableStateOf<AnimeEpisode?>(null) }
    var trackingVisible by remember { mutableStateOf(false) }
    var reviewsVisible by remember { mutableStateOf(false) }
    var openingSubject by remember { mutableStateOf<AnimeSubject?>(null) }
    var playerFullscreen by rememberSaveable { mutableStateOf(false) }
    var recheckRequest by remember { mutableStateOf<AnimeTrackingReview?>(null) }
    val playing = state.playbackTarget != null || state.resolving ||
        playbackPhase != AnimePlayerPhase.IDLE && playbackPhase != AnimePlayerPhase.RELEASED
    val route = when {
        playing -> AnimeRoute("player", 2, state.detail)
        state.detail != null || state.detailLoading || state.detailError != null ->
            AnimeRoute("detail:" + (state.detail?.subject?.id ?: openingSubject?.id.orEmpty()), 1, state.detail)
        else -> AnimeRoute("home", 0, null)
    }
    val resumeDockVisible = !playing && route.depth == 0 && !directoryOpen && !searchPresent && state.resumeEntry?.resume != null
    val contentBottomPadding = if (resumeDockVisible) 108.dp else 24.dp
    val overlayVisible = downloadsVisible || sourcesVisible || trackingVisible || reviewsVisible
    val rootActive = active && route.depth == 0 && !searchPresent && !toolsPresent && !layoutPresent && !overlayVisible
    LaunchedEffect(active, route.key, page, directoryOpen) {
        toolsVisible = false
        pendingTool = null
    }
    val currentPlaybackVisibility by rememberUpdatedState(onPlaybackVisibility)
    LaunchedEffect(playing) { currentPlaybackVisibility(playing) }
    DisposableEffect(controller) {
        onDispose { currentPlaybackVisibility(false) }
    }
    LaunchedEffect(controller) {
        controller.ensureDiscovery()
    }
    LaunchedEffect(controller, directoryOpen) {
        if (directoryOpen) controller.sourceBrowse.activate()
    }
    LaunchedEffect(browseRequest) {
        if (browseRequest != 0) {
            playerFullscreen = false
            pendingAssociation = null
            browseCandidate = null
            tab = 0
            directoryOpen = false
            searchExpanded = false
            toolsVisible = false
            pendingTool = null
            sourcesVisible = false
            downloadsVisible = false
            trackingVisible = false
            reviewsVisible = false
            onBrowseRequestConsumed()
        }
    }
    LaunchedEffect(state.discoveryTab) {
        if (state.discoveryTab == AnimeDiscoveryTab.SOURCES) controller.selectDiscoveryTab(AnimeDiscoveryTab.HOME)
    }
    LaunchedEffect(searchRequest) {
        if (searchRequest != consumedSearchRequest) {
            consumedSearchRequest = searchRequest
            playerFullscreen = false
            controller.closePlayback()
            controller.closeDetail()
            toolsVisible = false
            pendingTool = null
            sourcesVisible = false
            downloadsVisible = false
            browseCandidate = null
            pendingAssociation = null
            trackingVisible = false
            reviewsVisible = false
            directoryOpen = false
            searchExpanded = true
        }
    }
    LaunchedEffect(state.detail?.subject?.id) {
        sourceEpisode = null
        sourcesVisible = false
    }

    fun closePlayback() {
        playerFullscreen = false
        controller.closePlayback()
    }
    fun open(subject: AnimeSubject) {
        if (pendingAssociation?.subjectId != subject.id) pendingAssociation = null
        playerFullscreen = false
        openingSubject = subject
        searchExpanded = false
        controller.open(subject)
    }
    fun resume(entry: AnimeLibraryEntry) {
        pendingAssociation = null
        browseCandidate = null
        playerFullscreen = false
        openingSubject = entry.subject
        searchExpanded = false
        controller.resume(entry.subject.id)
    }
    fun playDownload(id: String) {
        downloadsVisible = false
        searchExpanded = false
        pendingAssociation = null
        browseCandidate = null
        openingSubject = controller.downloads.state.value.entries.firstOrNull { it.id == id }?.subject
        controller.playDownload(id)
    }
    fun chooseReference(title: AnimeSourceTitle, videoSource: Boolean) {
        controller.knownDiscoverySubject(title)?.let { known ->
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
            playerFullscreen = false
            openingSubject = controller.sourceSubject(title)
            searchExpanded = false
            controller.openSourceTitle(title)
            return
        }
        browseCandidate = title
        query = title.title
        controller.search(title.title, indexOnly = true)
        searchExpanded = true
    }
    fun openSearchResult(subject: AnimeSubject) {
        val candidates = if (browseCandidate == null)
            controller.searchState.value.groups.firstOrNull { it.subject?.id == subject.id }?.sources.orEmpty()
        else emptyList()
        pendingAssociation = null
        browseCandidate = null
        open(subject)
        if (candidates.isNotEmpty()) controller.stageSearchCandidates(subject.id, candidates)
    }
    fun dismissSources() {
        sourcesVisible = false
        controller.dismissSourcePreview()
    }
    fun showSources(episode: AnimeEpisode? = null) {
        if (state.resolving) controller.pausePlaybackResolution()
        sourceEpisode = episode
        sourcesVisible = true
    }
    fun requestPlayback(episode: AnimeEpisode, option: AnimeEpisodeOption?) {
        controller.dismissNotice()
        controller.play(episode.id, option)
    }
    fun play(episode: AnimeEpisode) {
        requestPlayback(episode, null)
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
            downloadsVisible -> downloadsVisible = false
            sourcesVisible -> dismissSources()
            trackingVisible -> trackingVisible = false
            reviewsVisible -> reviewsVisible = false
            searchExpanded -> { searchExpanded = false; browseCandidate = null }
            playing && playerFullscreen -> playerFullscreen = false
            playing -> closePlayback()
            route.depth == 1 -> { pendingAssociation = null; controller.closeDetail() }
            directoryOpen -> returnFromDirectory()
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
            AnimeWorkspaceTool.TRACKING -> trackingVisible = true
            AnimeWorkspaceTool.DOWNLOADS -> downloadsVisible = true
            AnimeWorkspaceTool.DIRECTORY -> openDirectory()
            null -> Unit
        }
    }

    LaunchedEffect(backRequest) {
        if (backRequest != consumedBackRequest) { consumedBackRequest = backRequest; back() }
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

    val canGoBack = route.depth > 0 || directoryOpen
    MotionBackHandler(active && canGoBack && !overlayVisible && !searchPresent && !toolsPresent && !layoutPresent,
        state = null, onBack = ::back)

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            MotionTabContent(selected = route, contentKey = { it.key }, order = { it.depth },
                modifier = Modifier.fillMaxSize()) { shown ->
                if (shown.depth == 2) {
                    val playback by controller.player.state.collectAsStateWithLifecycle()
                    AnimePlayerPane(controller, state, playback, backdrop,
                        active = active, fullscreen = playerFullscreen, onFullscreenChange = { playerFullscreen = it },
                        obscured = sourcesVisible || downloadsVisible || trackingVisible || reviewsVisible || state.pendingPlayback != null,
                        onBack = ::closePlayback,
                        onRetry = controller::retryPlayback,
                        onKeepPlayback = controller::cancelPlaybackResolution,
                        onRestorePlayback = controller::restorePreviousPlayback,
                        onPlayDownload = ::playDownload,
                        onSources = {
                            val current = state.detail?.episodes?.firstOrNull { it.id == state.selectedEpisodeId }
                            showSources(state.playbackTarget?.episode ?: current)
                        },
                        onEpisode = ::play, modifier = Modifier.fillMaxSize())
                } else {
                    Column(Modifier.fillMaxSize()) {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val preferred = animePreferredColumns(maxWidth, state.discoveryColumns)
                            val actualColumns = animeDiscoveryColumns(maxWidth, state.discoveryColumns)
                            AnimeWorkspaceHeader(root = shown.depth == 0 && !directoryOpen,
                                title = if (shown.depth == 0) (if (directoryOpen) "来源目录" else "动漫") else
                                    shown.detail?.subject?.title ?: openingSubject?.title ?: "动漫详情",
                                backdrop = backdrop, navigationVisible = navigationVisible,
                                navigation = {
                                    if (shown.depth == 0 && !directoryOpen) navigation()
                                    else MotionIconButton(onClick = {
                                        if (shown.depth == 0) returnFromDirectory()
                                        else { pendingAssociation = null; controller.closeDetail() }
                                    },
                                        modifier = Modifier.size(44.dp), tooltip = "返回动漫") {
                                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", Modifier.size(20.dp))
                                    }
                                },
                                actions = if (shown.depth != 0 || directoryOpen) null else ({
                                    Row(Modifier.heightIn(min = 44.dp)
                                        .graphicsLayer { alpha = if (searchExpanded) 0f else
                                            1f - (searchForeground?.value ?: 0f).coerceIn(0f, 1f) }
                                        .motionInputGate(active && route.depth == 0 && !searchPresent && !overlayVisible),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        if (tab == 0) DiscoveryLayoutControl(preferred, actualColumns, backdrop,
                                            active && route.depth == 0 && !searchPresent && !toolsPresent && !overlayVisible,
                                            onChange = controller::setDiscoveryColumns,
                                            onPresenceChange = { layoutPresent = it })
                                        Box {
                                            MotionIconButton(onClick = { pendingTool = null; toolsVisible = !toolsVisible },
                                                modifier = Modifier.size(44.dp), enabled = !layoutPresent && (toolsVisible || !toolsPresent),
                                                tooltip = "动漫菜单") {
                                                Icon(Icons.Outlined.MoreHoriz, "动漫菜单", Modifier.size(21.dp))
                                            }
                                            CompactGlassPopover(toolsVisible, backdrop,
                                                onDismiss = { toolsVisible = false; pendingTool = null },
                                                onPresenceChange = ::finishToolMenu) {
                                                if (tab == 0)
                                                    CompactMenuAction(Icons.Outlined.Refresh, "刷新当前栏目",
                                                        enabled = state.discoveryFeeds[animeDiscoveryFeedKey(state.discoveryTab)]?.loading != true,
                                                        onClick = { pendingTool = null; toolsVisible = false; controller.refreshDiscovery() })
                                                CompactMenuAction(Icons.Outlined.Public, "来源目录",
                                                    onClick = { queueTool(AnimeWorkspaceTool.DIRECTORY) })
                                                CompactMenuAction(Icons.Outlined.DownloadForOffline, "离线下载",
                                                    onClick = { queueTool(AnimeWorkspaceTool.DOWNLOADS) })
                                                CompactMenuAction(Icons.Outlined.AccountCircle, "追番账号",
                                                    onClick = { queueTool(AnimeWorkspaceTool.TRACKING) })
                                            }
                                        }
                                        Spacer(Modifier.size(44.dp))
                                    }
                                }))
                        }
                        if (shown.depth == 0) {
                            if (!directoryOpen) AnimeWorkspaceNavigation(page, active = rootActive, onSelect = { selected ->
                                tab = if (selected == AnimeWorkspacePage.LIBRARY) 1 else 0
                                selected.discovery?.let(controller::selectDiscoveryTab)
                            })
                            MotionTabContent(pane, Modifier.weight(1f).mangaHazeSource(backdrop).motionInputGate(rootActive),
                                contentKey = { it }, order = { it.ordinal }) { shownPane ->
                                val paneActive = rootActive && shownPane == pane
                                when (shownPane) {
                                    AnimeWorkspacePane.DISCOVERY -> AnimeDiscovery(state, controller, backdrop, discoveryScrolls,
                                        onOpen = ::open, onChooseReference = ::chooseReference, onBrowseSource = ::openDirectory,
                                        active = paneActive, contentBottomPadding = contentBottomPadding)
                                    AnimeWorkspacePane.DIRECTORY -> AnimeSourceBrowsePane(controller, backdrop, discoveryScrolls,
                                        columns = state.discoveryColumns, active = paneActive,
                                        onChooseTitle = ::chooseReference, contentBottomPadding = contentBottomPadding)
                                    AnimeWorkspacePane.LIBRARY -> AnimeLibrary(state, libraryScroll, controller, backdrop,
                                        onOpen = ::open, onResume = ::resume,
                                        onDiscover = { tab = 0; controller.selectDiscoveryTab(AnimeDiscoveryTab.HOME) },
                                        onReview = { reviewsVisible = true }, viewState = libraryViewState,
                                        active = paneActive, contentBottomPadding = contentBottomPadding)
                                }
                            }
                        } else if (shown.detail != null) {
                            Box(Modifier.weight(1f).mangaHazeSource(backdrop)) {
                                detailState.SaveableStateProvider(shown.key) {
                                    AnimeDetailPane(shown.detail, state, controller, backdrop,
                                        remember(shown.detail.subject.id) { detailScrolls.getOrPut(shown.detail.subject.id) { LazyListState() } },
                                        onPlay = ::play, onOpen = ::open, onSources = ::showSources,
                                        onTracking = { trackingVisible = true }, modifier = Modifier.fillMaxSize(),
                                        active = active && shown.key == route.key && !overlayVisible && !searchPresent)
                                }
                            }
                        } else Box(Modifier.weight(1f)) {
                            AnimeMessage(if (state.detailLoading) "正在载入番剧" else "番剧详情未取得",
                                state.detailError ?: openingSubject?.title,
                                action = if (state.detailLoading) null else "重试",
                                onAction = { openingSubject?.let(controller::open) })
                        }
                    }
                }
            }
            if (!playing && (route.depth == 0 && !directoryOpen || searchExpanded || searchPresent)) ExpandingSearchBar(expanded = searchExpanded, query = query,
                active = active && route.depth == 0 && (!directoryOpen || searchExpanded || searchPresent) &&
                    !overlayVisible && !toolsPresent && !layoutPresent, backdrop = backdrop,
                onExpandedChange = { searchExpanded = it; if (!it) { browseCandidate = null } },
                onPresenceChange = { searchPresent = it },
                onForegroundState = { searchForeground = it }, onQuery = { query = it },
                onSearch = { controller.search(query.trim(), indexOnly = browseCandidate != null) }, placeholder = "搜索番剧",
                modifier = Modifier.fillMaxSize()
                    .windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Top))
                    .padding(horizontal = 8.dp, vertical = 2.dp)) { height, inputActive ->
                val search by controller.searchState.collectAsStateWithLifecycle()
                val indexOnly = browseCandidate != null
                LaunchedEffect(searchExpanded, indexOnly, search.query, search.indexOnly) {
                    if (searchExpanded && search.query.isNotBlank() && search.query == query.trim() && search.indexOnly != indexOnly) {
                        controller.search(search.query, indexOnly = indexOnly)
                    }
                }
                var showSearchIssues by remember(search.query, indexOnly) { mutableStateOf(false) }
                var expandedSearchGroup by remember(search.query, indexOnly) { mutableStateOf<String?>(null) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = height).motionInputGate(inputActive),
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
                                controller.search(keyword, indexOnly = indexOnly)
                            })
                        }
                    }
                    when {
                        query.isBlank() -> item { AnimeQuietLabel("输入名称、原名或别名", Modifier.padding(16.dp)) }
                        query.trim() != search.query -> item {
                            AnimeMessage("搜索番剧名称", null, action = "搜索",
                                onAction = { controller.search(query.trim(), indexOnly = indexOnly) })
                        }
                        else -> animeSearchResults(search, indexOnly, controller, showSearchIssues,
                            onToggleIssues = { showSearchIssues = !showSearchIssues }, expandedGroup = expandedSearchGroup,
                            onToggleSources = { key -> expandedSearchGroup = key.takeUnless { it == expandedSearchGroup } },
                            onOpenSubject = ::openSearchResult, onOpenSource = { chooseReference(it, true) })
                    }
                }
            }
            if (resumeDockVisible) state.resumeEntry?.let { entry ->
                AnimeResumeDock(entry, backdrop, onResume = { resume(entry) },
                    onDismiss = { controller.dismissResume(entry.subject.id) },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 12.dp)
                        .motionInputGate(rootActive))
            }
        }
        if (!playing && (state.notice != null || state.canUndo)) AnimeNotice(state.notice, state.canUndo,
            onUndo = controller::undo, onDismiss = controller::dismissNotice)
    }

    AnimeDownloadsPanel(downloadsVisible, controller, state, backdrop,
        onDismiss = { downloadsVisible = false }, onPlay = ::playDownload)
    AnimeSourcesPanel(sourcesVisible, state, controller, backdrop, sourceEpisode,
        onDismiss = ::dismissSources, onAutomaticPlay = { episode ->
            sourcesVisible = false
            requestPlayback(episode, null)
        }, onPlay = { episodeId, option ->
            sourcesVisible = false
            state.detail?.episodes?.firstOrNull { it.id == episodeId }?.let { requestPlayback(it, option) }
        })
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

/** All anime columns are peers. Narrow windows scroll this one row instead of adding a second level. */
@Composable
private fun AnimeWorkspaceNavigation(selected: AnimeWorkspacePage, active: Boolean,
    onSelect: (AnimeWorkspacePage) -> Unit) {
    val pages = AnimeWorkspacePage.entries
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val motion = LocalMotionEnabled.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp).motionInputGate(active)) {
        val viewportWidth = maxWidth
        val minimum = with(density) { (13.sp.toDp() * 2 + 24.dp).coerceAtLeast(48.dp) * pages.size }
        val width = viewportWidth.coerceAtMost(480.dp).coerceAtLeast(minimum)
        LaunchedEffect(selected, width, viewportWidth, density, scroll.maxValue) {
            val slot = with(density) { width.toPx() / pages.size }
            val viewport = with(density) { viewportWidth.toPx() }
            val start = slot * selected.ordinal
            val end = start + slot
            val target = when {
                start < scroll.value -> start.toInt()
                end > scroll.value + viewport -> (end - viewport).toInt()
                else -> scroll.value
            }.coerceIn(0, with(density) { (width - viewportWidth).roundToPx() }.coerceAtLeast(0))
            if (motion) scroll.animateScrollTo(target) else scroll.scrollTo(target)
        }
        Box(Modifier.horizontalScroll(scroll)) {
            MotionTabs(selected.ordinal, pages.map { it.label }, onSelect = { onSelect(pages[it]) },
                modifier = Modifier.width(width), controlHeight = 44.dp, treatment = MotionTabTreatment.Underline,
                foreground = MaterialTheme.colorScheme.onSurface,
                indicator = MaterialTheme.colorScheme.primary)
        }
    }
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
