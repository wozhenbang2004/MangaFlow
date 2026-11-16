package dev.mangaflow.anime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.anime.player.AnimePlayerPhase
import dev.mangaflow.ui.*
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.mangaflow.ui.motion.*

private data class AnimeRoute(val key: String, val depth: Int, val detail: AnimeDetail?)
private data class AnimePlaybackIntent(val episode: AnimeEpisode, val option: AnimeEpisodeOption? = null)
private data class AnimeSourceAssociation(val title: AnimeSourceTitle, val subjectId: String)

/** The root owns one controller/player. Pane identity never follows the player's ticking position. */
@Composable
internal fun AnimeWorkspace(
    controller: AnimeController,
    onManga: () -> Unit,
    backdrop: HazeState,
    navigation: @Composable () -> Unit,
    navigationVisible: Boolean = true,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    searchRequest: Int = 0,
    backRequest: Int = 0,
    onPlaybackVisibility: (Boolean) -> Unit = {},
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val playback by controller.player.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val discoveryScrolls = remember { AnimeDiscoveryScrolls() }
    val libraryScroll = rememberLazyListState()
    val detailScrolls = remember { mutableMapOf<String, LazyListState>() }
    var query by rememberSaveable { mutableStateOf(state.query) }
    var consumedSearchRequest by remember { mutableIntStateOf(searchRequest) }
    var consumedBackRequest by remember { mutableIntStateOf(backRequest) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var searchPresent by remember { mutableStateOf(false) }
    var searchForeground by remember { mutableStateOf<State<Float>?>(null) }
    var sourcesVisible by remember { mutableStateOf(false) }
    var sourceManagerVisible by remember { mutableStateOf(false) }
    var downloadsVisible by remember { mutableStateOf(false) }
    var browseCandidate by remember { mutableStateOf<AnimeSourceTitle?>(null) }
    var browseCandidateVideo by remember { mutableStateOf(false) }
    var pendingAssociation by remember { mutableStateOf<AnimeSourceAssociation?>(null) }
    var sourceEpisode by remember { mutableStateOf<AnimeEpisode?>(null) }
    var trackingVisible by remember { mutableStateOf(false) }
    var reviewsVisible by remember { mutableStateOf(false) }
    var openingSubject by remember { mutableStateOf<AnimeSubject?>(null) }
    var playerFullscreen by rememberSaveable { mutableStateOf(false) }
    var playbackIntent by remember { mutableStateOf<AnimePlaybackIntent?>(null) }
    var playbackFailure by remember { mutableStateOf<String?>(null) }
    var awaitingResolve by remember { mutableStateOf(false) }
    var recheckRequest by remember { mutableStateOf<AnimeTrackingReview?>(null) }
    val playing = playbackIntent != null || state.resolving || playback.phase !in listOf(AnimePlayerPhase.IDLE, AnimePlayerPhase.RELEASED)
    val route = when {
        playing -> AnimeRoute("player", 2, state.detail)
        state.detail != null || state.detailLoading || state.detailError != null ->
            AnimeRoute("detail:" + (state.detail?.subject?.id ?: openingSubject?.id.orEmpty()), 1, state.detail)
        else -> AnimeRoute("home", 0, null)
    }
    val currentPlaybackVisibility by rememberUpdatedState(onPlaybackVisibility)
    LaunchedEffect(playing) { currentPlaybackVisibility(playing) }
    DisposableEffect(controller) {
        onDispose { currentPlaybackVisibility(false) }
    }
    LaunchedEffect(controller) {
        controller.ensureDiscovery()
    }
    LaunchedEffect(searchRequest) {
        if (searchRequest != consumedSearchRequest) {
            consumedSearchRequest = searchRequest
            playerFullscreen = false
            playbackIntent = null
            playbackFailure = null
            awaitingResolve = false
            controller.closePlayback()
            sourcesVisible = false
            sourceManagerVisible = false
            downloadsVisible = false
            browseCandidate = null
            browseCandidateVideo = false
            pendingAssociation = null
            trackingVisible = false
            reviewsVisible = false
            searchExpanded = true
        }
    }
    LaunchedEffect(state.detail?.subject?.id) {
        sourceEpisode = null
        sourcesVisible = false
    }

    fun closePlayback() {
        playerFullscreen = false
        playbackIntent = null
        playbackFailure = null
        awaitingResolve = false
        controller.closePlayback()
    }
    fun open(subject: AnimeSubject) {
        if (pendingAssociation?.subjectId != subject.id) pendingAssociation = null
        playerFullscreen = false
        playbackIntent = null
        playbackFailure = null
        awaitingResolve = false
        openingSubject = subject
        searchExpanded = false
        controller.open(subject)
    }
    fun resume(entry: AnimeLibraryEntry) {
        pendingAssociation = null
        browseCandidate = null
        browseCandidateVideo = false
        playerFullscreen = false
        playbackIntent = null
        playbackFailure = null
        awaitingResolve = false
        openingSubject = entry.subject
        searchExpanded = false
        controller.resume(entry.subject.id)
    }
    fun playDownload(id: String) {
        downloadsVisible = false
        searchExpanded = false
        playbackIntent = null
        playbackFailure = null
        awaitingResolve = false
        pendingAssociation = null
        browseCandidate = null
        browseCandidateVideo = false
        openingSubject = controller.downloads.state.value.entries.firstOrNull { it.id == id }?.subject
        controller.playDownload(id)
    }
    fun chooseReference(title: AnimeSourceTitle, videoSource: Boolean) {
        controller.knownDiscoverySubject(title)?.let { known ->
            browseCandidate = null
            browseCandidateVideo = false
            pendingAssociation = if (videoSource) AnimeSourceAssociation(title, known.id) else null
            open(known)
            return
        }
        browseCandidate = title
        browseCandidateVideo = videoSource
        query = title.title
        controller.search(title.title)
        searchExpanded = true
    }
    fun openSearchResult(subject: AnimeSubject) {
        pendingAssociation = browseCandidate?.takeIf { browseCandidateVideo }?.let { AnimeSourceAssociation(it, subject.id) }
        browseCandidate = null
        browseCandidateVideo = false
        open(subject)
    }
    fun showSources(episode: AnimeEpisode? = null) {
        sourceEpisode = episode
        sourcesVisible = true
    }
    fun requestPlayback(episode: AnimeEpisode, option: AnimeEpisodeOption?) {
        playbackIntent = AnimePlaybackIntent(episode, option)
        playbackFailure = null
        awaitingResolve = true
        controller.dismissNotice()
        controller.play(episode.id, option)
    }
    fun play(episode: AnimeEpisode) {
        val option = state.episodeOptions[episode.id].orEmpty().firstOrNull { it.confirmed }
        if (option != null) requestPlayback(episode, option) else showSources(episode)
    }
    fun back() {
        when {
            downloadsVisible -> downloadsVisible = false
            sourceManagerVisible -> sourceManagerVisible = false
            sourcesVisible -> sourcesVisible = false
            trackingVisible -> trackingVisible = false
            reviewsVisible -> reviewsVisible = false
            searchExpanded -> { searchExpanded = false; browseCandidate = null; browseCandidateVideo = false }
            playing && playerFullscreen -> playerFullscreen = false
            playing -> closePlayback()
            route.depth == 1 -> { pendingAssociation = null; controller.closeDetail() }
            tab == 0 && state.discoveryTab == AnimeDiscoveryTab.SOURCES && controller.sourceBrowse.state.value.selectedTitle != null ->
                controller.sourceBrowse.closeTitle()
            tab == 0 && state.discoveryTab != AnimeDiscoveryTab.HOME -> controller.selectDiscoveryTab(AnimeDiscoveryTab.HOME)
            tab != 0 -> tab = 0
            else -> onManga()
        }
    }

    LaunchedEffect(backRequest) {
        if (backRequest != consumedBackRequest) { consumedBackRequest = backRequest; back() }
    }
    LaunchedEffect(state.resolving, state.sourceError, state.pendingPlayback, playback.phase) {
        if (awaitingResolve && !state.resolving) {
            if (state.sourceError != null) {
                playbackFailure = state.sourceError
                awaitingResolve = false
            } else if (state.pendingPlayback != null || playback.phase != AnimePlayerPhase.IDLE) awaitingResolve = false
        }
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

    MotionBackHandler(active && !downloadsVisible && !sourceManagerVisible && !sourcesVisible && !trackingVisible && !reviewsVisible && !searchPresent,
        state = null, onBack = ::back)

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            MotionTabContent(selected = route, contentKey = { it.key }, order = { it.depth },
                modifier = Modifier.fillMaxSize()) { shown ->
                if (shown.depth == 2) {
                    AnimePlayerPane(controller, state, playback, backdrop,
                        requestedEpisode = playbackIntent?.episode, resolutionError = playbackFailure,
                        active = active, fullscreen = playerFullscreen, onFullscreenChange = { playerFullscreen = it },
                        obscured = sourcesVisible || downloadsVisible || sourceManagerVisible || trackingVisible || reviewsVisible || state.pendingPlayback != null,
                        onBack = ::closePlayback,
                        onRetry = {
                            if (playbackFailure != null && playbackIntent != null)
                                playbackIntent?.let { requestPlayback(it.episode, it.option) }
                            else controller.retryPlayback()
                        },
                        onKeepPlayback = {
                            controller.cancelPlaybackResolution()
                            playbackFailure = null
                            awaitingResolve = false
                            playbackIntent = state.detail?.episodes?.firstOrNull { it.id == state.selectedEpisodeId }
                                ?.let { AnimePlaybackIntent(it) }
                        },
                        onRestorePlayback = {
                            playbackFailure = null
                            playbackIntent = null
                            awaitingResolve = false
                            controller.restorePreviousPlayback()
                        },
                        onPlayDownload = ::playDownload,
                        onSources = {
                            val current = state.detail?.episodes?.firstOrNull { it.id == state.selectedEpisodeId }
                            showSources(if (state.resolving || playbackFailure != null) playbackIntent?.episode ?: current
                                else current ?: playbackIntent?.episode)
                        },
                        onEpisode = ::play, modifier = Modifier.fillMaxSize())
                } else {
                    Column(Modifier.fillMaxSize()) {
                        AnimeWorkspaceHeader(root = shown.depth == 0,
                            title = shown.detail?.subject?.title ?: openingSubject?.title ?: "番剧",
                            backdrop = backdrop, navigationVisible = navigationVisible, onManga = onManga,
                            navigation = {
                                if (shown.depth == 0) navigation()
                                else MotionIconButton(onClick = { pendingAssociation = null; controller.closeDetail() }, modifier = Modifier.size(44.dp),
                                    tooltip = "返回番剧") {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", Modifier.size(20.dp))
                                }
                            },
                            actions = {
                                Row(Modifier.padding(horizontal = 8.dp).heightIn(min = 44.dp)
                                    .graphicsLayer { alpha = if (searchExpanded) 0f else
                                        1f - (searchForeground?.value ?: 0f).coerceIn(0f, 1f) }
                                    .motionInputGate(active && !searchPresent),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    MotionIconButton(onClick = { sourceManagerVisible = true }, modifier = Modifier.size(44.dp),
                                        tooltip = "来源管理") {
                                        Icon(Icons.Outlined.Extension, "来源管理", Modifier.size(20.dp))
                                    }
                                    MotionIconButton(onClick = { trackingVisible = true }, modifier = Modifier.size(44.dp),
                                        tooltip = "追番账号") {
                                        Icon(Icons.Outlined.AccountCircle, "追番账号", Modifier.size(21.dp))
                                    }
                                    Spacer(Modifier.size(44.dp))
                                }
                            })
                        if (shown.depth == 0) {
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val preferred = animePreferredColumns(maxWidth, state.discoveryColumns)
                                val actualColumns = animeDiscoveryColumns(maxWidth, state.discoveryColumns)
                                val availableWidth = maxWidth
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    MotionTabs(tab, listOf("发现", "追番"), onSelect = { tab = it },
                                        modifier = Modifier.width(minOf(144.dp, (availableWidth - 112.dp).coerceAtLeast(88.dp))), controlHeight = 44.dp,
                                        treatment = MotionTabTreatment.Underline)
                                    Spacer(Modifier.weight(1f))
                                    if (tab == 1) MotionIconButton(onClick = { reviewsVisible = true },
                                        modifier = Modifier.size(44.dp), tooltip = "复核进度差异") {
                                        Icon(if (state.reviews.isEmpty()) Icons.Outlined.Sync else Icons.Outlined.SwapHoriz,
                                            if (state.reviews.isEmpty()) "进度复核" else "待复核 " + state.reviews.size + " 项",
                                            Modifier.size(20.dp))
                                    }
                                    else {
                                        DiscoveryLayoutControl(preferred, actualColumns, backdrop, active && !searchPresent,
                                            onChange = controller::setDiscoveryColumns, onPresenceChange = {})
                                        MotionIconButton(onClick = controller::refreshDiscovery, modifier = Modifier.size(44.dp),
                                            enabled = state.discoveryFeeds[animeDiscoveryFeedKey(state.discoveryTab)]?.loading != true,
                                            tooltip = "刷新当前内容") {
                                            Icon(Icons.Outlined.Refresh, "刷新当前内容", Modifier.size(20.dp))
                                        }
                                    }
                                }
                            }
                            MotionTabContent(tab, Modifier.weight(1f).mangaHazeSource(backdrop)) { pane ->
                                if (pane == 0) AnimeDiscovery(state, controller, backdrop, discoveryScrolls, onOpen = ::open,
                                    onChooseReference = ::chooseReference, onManageSources = { sourceManagerVisible = true },
                                    active = active && tab == 0 && !searchPresent)
                                else AnimeLibrary(state, libraryScroll, controller, backdrop, onOpen = ::open, onResume = ::resume,
                                    onDiscover = { tab = 0 }, onReview = { reviewsVisible = true }, onDownloads = { downloadsVisible = true })
                            }
                        } else if (shown.detail != null) {
                            AnimeDetailPane(shown.detail, state, controller, backdrop,
                                remember(shown.detail.subject.id) { detailScrolls.getOrPut(shown.detail.subject.id) { LazyListState() } },
                                onPlay = ::play,
                                onOpen = ::open,
                                onSources = ::showSources,
                                onTracking = { trackingVisible = true }, modifier = Modifier.weight(1f).mangaHazeSource(backdrop))
                        } else Box(Modifier.weight(1f)) {
                            AnimeMessage(if (state.detailLoading) "正在载入番剧" else "番剧详情未取得",
                                state.detailError ?: openingSubject?.title,
                                action = if (state.detailLoading) null else "重试",
                                onAction = { openingSubject?.let(controller::open) })
                        }
                    }
                }
            }
            if (!playing) ExpandingSearchBar(expanded = searchExpanded, query = query,
                active = active && !downloadsVisible && !sourceManagerVisible && !sourcesVisible && !trackingVisible && !reviewsVisible, backdrop = backdrop,
                onExpandedChange = { searchExpanded = it; if (!it) { browseCandidate = null; browseCandidateVideo = false } },
                onPresenceChange = { searchPresent = it },
                onForegroundState = { searchForeground = it }, onQuery = { query = it },
                onSearch = { controller.search(query.trim()) }, placeholder = "搜索番剧",
                modifier = Modifier.fillMaxSize()
                    .windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Top))
                    .padding(horizontal = 8.dp, vertical = 2.dp)) { height, inputActive ->
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = height).motionInputGate(inputActive),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                    browseCandidate?.let { candidate -> item(key = "source-association") {
                        AnimeSectionTitle("选择对应番剧", candidate.title + " · " +
                            if (candidate.sourceId in setOf("anime-anilist", "anime-bangumi")) animeProviderName(candidate.sourceId)
                            else animeSourceName(candidate.sourceId, state), action = {
                            MotionIconButton(onClick = { browseCandidate = null; browseCandidateVideo = false }, modifier = Modifier.size(44.dp),
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
                            AnimeSelection(keyword, selected = false, onClick = { query = keyword; controller.search(keyword) })
                        }
                    }
                    when {
                        query.isBlank() -> item { AnimeQuietLabel("输入名称、原名或别名", Modifier.padding(16.dp)) }
                        query.trim() != state.query -> item {
                            AnimeMessage("搜索番剧名称", "作品资料与播放来源分开核对。", action = "搜索",
                                onAction = { controller.search(query.trim()) })
                        }
                        state.catalogLoading && state.catalog.isEmpty() -> item { AnimeMessage("正在搜索", query) }
                        state.catalogError != null && state.catalog.isEmpty() -> item { AnimeMessage("搜索未完成", state.catalogError,
                            action = "重试", onAction = { controller.search(query.trim()) }) }
                        state.catalog.isEmpty() -> item { AnimeMessage("没有找到番剧", "试试原名、别名或更短的关键词。") }
                        else -> {
                            if (state.catalogLoading) item { AnimeQuietLabel("显示已保存的结果 · 正在更新", Modifier.padding(12.dp)) }
                            if (state.catalogError != null) item { AnimeMessage("部分结果未能更新", state.catalogError,
                                action = "重新搜索", onAction = { controller.search(query.trim()) }) }
                            items(state.catalog, key = { it.id }) { subject ->
                                AnimeSubjectRow(subject, animeSubjectSummary(subject), onOpen = { openSearchResult(subject) })
                            }
                            if (state.catalogMoreError != null) item(key = "search-more-error") {
                                AnimeDiscoveryInlineStatus(state.catalogMoreError.orEmpty(), retry = controller::loadMoreCatalog)
                            }
                            if (state.catalogHasMore) item(key = "search-more") {
                                MotionTextButton(onClick = controller::loadMoreCatalog,
                                    enabled = !state.catalogLoadingMore, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                    Text(if (state.catalogLoadingMore) "正在加载" else "更多结果")
                                }
                            }
                        }
                    }
                }
            }
            if (!playing && route.depth == 0 && !searchPresent) state.resumeEntry?.let { entry ->
                AnimeResumeDock(entry, backdrop, onResume = { resume(entry) },
                    onDismiss = { controller.dismissResume(entry.subject.id) },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 12.dp)
                        .motionInputGate(active && !downloadsVisible && !sourcesVisible && !sourceManagerVisible && !trackingVisible && !reviewsVisible))
            }
        }
        if (!playing && (state.notice != null || state.canUndo)) AnimeNotice(state.notice, state.canUndo,
            onUndo = controller::undo, onDismiss = controller::dismissNotice)
    }

    AnimeSourceManager(sourceManagerVisible, state, controller, backdrop, onDismiss = { sourceManagerVisible = false })
    AnimeDownloadsPanel(downloadsVisible, controller, state, backdrop,
        onDismiss = { downloadsVisible = false }, onPlay = ::playDownload)
    AnimeSourcesPanel(sourcesVisible, state, controller, backdrop, sourceEpisode,
        onDismiss = { sourcesVisible = false }, onPlay = { episodeId, option ->
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
    AnimeResumePanel(state.pendingPlayback, backdrop, onChoose = { resumePrevious ->
        playbackFailure = null
        awaitingResolve = true
        controller.confirmPlayback(resumePrevious)
    },
        onDismiss = {
            controller.dismissPlaybackChoice()
            playbackFailure = null
            awaitingResolve = false
            playbackIntent = if (playback.phase == AnimePlayerPhase.IDLE || playback.phase == AnimePlayerPhase.RELEASED) null
                else state.detail?.episodes?.firstOrNull { it.id == state.selectedEpisodeId }?.let { AnimePlaybackIntent(it) }
        })
}

/** Desktop has a permanent navigation rail, so it must not receive an empty menu surface. */
@Composable
private fun AnimeWorkspaceHeader(root: Boolean, title: String, backdrop: HazeState,
    navigationVisible: Boolean, onManga: () -> Unit, navigation: @Composable () -> Unit,
    actions: @Composable () -> Unit) {
    val headerModifier = Modifier.windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Top))
        .padding(horizontal = 8.dp, vertical = 2.dp)
    val mediaSwitch: @Composable () -> Unit = {
        LiquidGlassSurface(backdrop, Modifier.width(132.dp), cornerRadius = 22.dp,
            role = GlassSurfaceRole.NAVIGATION) {
            MotionTabs(1, listOf("漫画", "番剧"), onSelect = { if (it == 0) onManga() },
                modifier = Modifier.fillMaxWidth(), controlHeight = 44.dp)
        }
    }
    if (root && !navigationVisible) {
        Row(headerModifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            mediaSwitch()
            Spacer(Modifier.weight(1f))
            LiquidGlassSurface(backdrop, cornerRadius = 22.dp, role = GlassSurfaceRole.NAVIGATION) {
                actions()
            }
        }
    } else PageTitleHeader(title, backdrop, navigation, modifier = headerModifier,
        leading = if (root) mediaSwitch else null, leadingWidth = if (root) 132.dp else 0.dp,
        summary = if (root) null else ({
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }), actions = actions)
}

internal fun animeSubjectSummary(subject: AnimeSubject): String =
    listOfNotNull(subject.year?.toString(), subject.episodeCount?.takeIf { it > 0 }?.let { it.toString() + " 集" })
        .joinToString(" · ").ifBlank { subject.originalTitle.ifBlank { "Bangumi 条目" } }

@Composable
private fun AnimeDetailPane(detail: AnimeDetail, state: AnimeScreenState, controller: AnimeController, backdrop: HazeState,
    scroll: LazyListState,
    onPlay: (AnimeEpisode) -> Unit, onOpen: (AnimeSubject) -> Unit,
    onSources: (AnimeEpisode?) -> Unit, onTracking: () -> Unit,
    modifier: Modifier = Modifier) {
    val subject = detail.subject
    val entry = state.library.firstOrNull { it.subject.id == subject.id }
    var episodeTab by rememberSaveable(subject.id) { mutableIntStateOf(0) }
    var descriptionExpanded by rememberSaveable(subject.id) { mutableStateOf(false) }
    var episodeReversed by rememberSaveable(subject.id) { mutableStateOf(false) }
    var episodeQuery by rememberSaveable(subject.id) { mutableStateOf("") }
    var episodeSearch by rememberSaveable(subject.id) { mutableStateOf(false) }
    var statusOpen by remember { mutableStateOf(false) }
    val main = detail.episodes.filter { it.kind == AnimeEpisodeKind.MAIN }
    val extra = detail.episodes.filter { it.kind != AnimeEpisodeKind.MAIN }
    LaunchedEffect(main.isEmpty(), extra.isNotEmpty()) {
        if (main.isEmpty() && extra.isNotEmpty()) episodeTab = 1
    }
    val episodes = (if (episodeTab == 0 || extra.isEmpty()) main else extra)
        .let { if (episodeReversed) it.asReversed() else it }
        .filter { episodeQuery.isBlank() || animeEpisodeNumber(it.number).contains(episodeQuery.trim()) ||
            it.title.contains(episodeQuery.trim(), ignoreCase = true) }
    val resume = entry?.resume?.takeUnless { it.completed }?.let { progress -> detail.episodes.firstOrNull { it.id == progress.episodeId } }
    val first = resume ?: main.firstOrNull { it.id !in entry?.completedEpisodeIds.orEmpty() } ?: main.firstOrNull() ?: extra.firstOrNull()
    val sourceNames = state.sourceBindings.map { binding ->
        binding.catalog.sourceId to (state.sourceResults.firstOrNull { it.providerId == binding.catalog.sourceId }?.name ?:
            animeSourceName(binding.catalog.sourceId, state))
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val columns = if (maxWidth >= 760.dp) 2 else 1
        LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "identity") {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Top) {
                    AnimePoster(subject, Modifier.width(96.dp).height(138.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(subject.title, style = MaterialTheme.typography.titleLarge)
                        if (subject.originalTitle.isNotBlank() && subject.originalTitle != subject.title)
                            Text(subject.originalTitle, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        AnimeQuietLabel(animeSubjectSummary(subject))
                        if (subject.rating != null || subject.rank != null) AnimeQuietLabel(listOfNotNull(
                            subject.rating?.takeIf { it > 0 }?.let { it.toString() + " 分" },
                            subject.rank?.takeIf { it > 0 }?.let { "排名 #" + it }).joinToString(" · "))
                        if (state.detailFromCache) AnimeQuietLabel(if (state.detailLoading) "已保存的资料 · 正在更新" else "已保存的资料")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MotionTextButton(onClick = { controller.setFollowed(subject, entry?.followed != true) },
                                modifier = Modifier.heightIn(min = 44.dp)) {
                                Icon(if (entry?.followed == true) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                                    null, Modifier.size(17.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (entry?.followed == true) "已追番" else "追番")
                            }
                            AnimeSourceStack(sourceNames, onClick = { onSources(null) })
                        }
                    }
                }
            }
            item(key = "continue") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    MotionButton(onClick = { first?.let(onPlay) }, enabled = first != null,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface)) {
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(if (resume != null) "继续 " + animeEpisodeLabel(resume) + " · " + animeTime(entry?.resume?.positionMs ?: 0L)
                            else first?.let { "播放 " + animeEpisodeLabel(it) } ?: if (state.detailLoading) "正在载入集数"
                            else if (state.detailError != null) "集数未取得" else "暂无集数",
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Box {
                        MotionTextButton(onClick = { statusOpen = true }, modifier = Modifier.heightIn(min = 44.dp)) {
                            Text(animeStatusLabel(entry?.status))
                            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
                        }
                        CompactGlassPopover(statusOpen, backdrop = backdrop,
                            onDismiss = { statusOpen = false }) {
                            AnimeWatchStatus.entries.forEach { status ->
                                CompactMenuAction(Icons.Outlined.Check, animeStatusLabel(status),
                                    onClick = { statusOpen = false; controller.setWatchStatus(subject.id, status) })
                            }
                            CompactMenuAction(Icons.Outlined.Sync, "核对追番进度", onClick = { statusOpen = false; onTracking() })
                        }
                    }
                }
            }
            if (subject.description.isNotBlank()) item(key = "description") {
                Column {
                    Text(subject.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (descriptionExpanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis)
                    MotionTextButton(onClick = { descriptionExpanded = !descriptionExpanded },
                        modifier = Modifier.heightIn(min = 44.dp)) { Text(if (descriptionExpanded) "收起简介" else "展开简介") }
                }
            }
            if (state.detailLoading) item(key = "episode-loading") {
                AnimeMessage("正在载入集数", "已保存的作品与续看记录会保留。")
            }
            if (state.detailError != null) item(key = "episode-error") {
                AnimeMessage("集数未取得", state.detailError, action = "重试",
                    onAction = { controller.open(subject) })
            }
            if (state.sourceError != null) item(key = "source-error") {
                AnimeSelection("来源暂未就绪", state.sourceError, false, onClick = { onSources(null) })
            }
            item(key = "episodes-heading") {
                AnimeSectionTitle("选集", action = {
                    Row {
                        MotionIconButton(onClick = { episodeSearch = !episodeSearch; if (!episodeSearch) episodeQuery = "" },
                            modifier = Modifier.size(44.dp), tooltip = "查找集数") {
                            Icon(Icons.Outlined.Search, "查找集数", Modifier.size(19.dp))
                        }
                        MotionIconButton(onClick = { episodeReversed = !episodeReversed }, modifier = Modifier.size(44.dp),
                            tooltip = if (episodeReversed) "改为正序" else "改为倒序") {
                            Icon(Icons.Outlined.SwapVert, if (episodeReversed) "倒序，点按切换" else "正序，点按切换", Modifier.size(19.dp))
                        }
                        MotionIconButton(onClick = { onSources(null) }, modifier = Modifier.size(44.dp), tooltip = "来源与集数映射") {
                            Icon(Icons.Outlined.Tune, "来源与集数映射", Modifier.size(19.dp))
                        }
                    }
                })
                if (episodeSearch) AnimeTextField(episodeQuery, { episodeQuery = it }, "集号或标题",
                    Modifier.padding(bottom = 6.dp))
                if (extra.isNotEmpty()) MotionTabs(episodeTab, listOf("正片 " + main.size, "特别篇等 " + extra.size),
                    onSelect = { episodeTab = it }, modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth(),
                    controlHeight = 44.dp, treatment = MotionTabTreatment.Underline)
            }
            if (episodes.isEmpty() && !state.detailLoading && state.detailError == null) item(key = "empty-episodes") {
                AnimeMessage(if (episodeQuery.isNotBlank()) "没有匹配的集数" else "这组还没有集数",
                    if (episodeQuery.isNotBlank()) "试试其他集号或标题。" else "作品资料尚未提供集数时，不按列表位置猜配播放资源。",
                    action = if (episodeQuery.isNotBlank()) "清除查找" else "查看来源",
                    onAction = { if (episodeQuery.isNotBlank()) episodeQuery = "" else onSources(null) })
            }
            items(episodes.chunked(columns), key = { row -> row.first().id }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { episode ->
                        AnimeEpisodeCell(episode, completed = episode.id in entry?.completedEpisodeIds.orEmpty(),
                            resume = episode.id == resume?.id,
                            matched = state.episodeOptions[episode.id].orEmpty().count { it.confirmed },
                            onPlay = { onPlay(episode) }, onSources = { onSources(episode) },
                            onCompleted = { controller.setEpisodeCompleted(episode.id, episode.id !in entry?.completedEpisodeIds.orEmpty()) },
                            modifier = Modifier.weight(1f))
                    }
                    if (row.size < columns) Spacer(Modifier.weight(1f))
                }
            }
            if (state.relatedSubjects.isNotEmpty()) {
                item(key = "related-heading") { AnimeSectionTitle("相关番剧") }
                items(state.relatedSubjects, key = { "related:" + it.id }) { related ->
                    AnimeSubjectRow(related, animeSubjectSummary(related), onOpen = { onOpen(related) })
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun AnimeEpisodeCell(episode: AnimeEpisode, completed: Boolean, resume: Boolean, matched: Int,
    onPlay: () -> Unit, onSources: () -> Unit, onCompleted: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember(episode.id) { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    Row(modifier.heightIn(min = 68.dp).clip(RoundedCornerShape(12.dp))
        .background(if (resume) colors.onSurface.copy(alpha = .05f) else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 68.dp).motionPress(interaction, pressedScale = .99f)
            .clickable(interactionSource = interaction, indication = null, onClick = onPlay)
            .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(animeEpisodeNumber(episode.number), Modifier.widthIn(min = 28.dp),
                style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (episode.kind == AnimeEpisodeKind.MAIN) episode.title.ifBlank { animeEpisodeLabel(episode) }
                    else animeEpisodeLabel(episode) + " · " + episode.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium)
                AnimeQuietLabel(listOfNotNull(if (resume) "续看" else null,
                    if (matched == 0) "待匹配来源" else "已匹配 " + matched + " 个来源").joinToString(" · "))
            }
        }
        MotionIconButton(onClick = onCompleted, modifier = Modifier.size(44.dp),
            tooltip = if (completed) "取消已看" else "标记已看") {
            Icon(if (completed) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                if (completed) "已看，点按取消" else "标记已看", Modifier.size(18.dp),
                tint = colors.onSurfaceVariant.copy(alpha = if (completed) .85f else .4f))
        }
        MotionIconButton(onClick = onSources, modifier = Modifier.size(44.dp), tooltip = "本集来源") {
            Icon(Icons.Outlined.MoreHoriz, "本集来源与映射", Modifier.size(19.dp), tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun AnimeNotice(notice: String?, canUndo: Boolean, onUndo: () -> Unit, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)
        .navigationBarsPadding().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(notice ?: "上次改动可以撤销", Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (canUndo) MotionTextButton(onClick = onUndo, modifier = Modifier.heightIn(min = 44.dp)) { Text("撤销") }
        if (notice != null) MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭提示") {
            Icon(Icons.Outlined.Close, "关闭提示", Modifier.size(18.dp))
        }
    }
}
