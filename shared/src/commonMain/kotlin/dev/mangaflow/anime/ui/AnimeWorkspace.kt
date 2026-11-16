package dev.mangaflow.anime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.text.font.FontWeight
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
private data class AnimePlaybackIntent(val episode: AnimeEpisode, val option: AnimeEpisodeOption? = null)

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
    val discoveryScroll = rememberLazyGridState()
    val libraryScroll = rememberLazyListState()
    val detailScrolls = remember { mutableMapOf<String, LazyListState>() }
    var query by rememberSaveable { mutableStateOf(state.query) }
    var consumedSearchRequest by remember { mutableIntStateOf(searchRequest) }
    var consumedBackRequest by remember { mutableIntStateOf(backRequest) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var searchPresent by remember { mutableStateOf(false) }
    var searchForeground by remember { mutableStateOf<State<Float>?>(null) }
    var sourcesVisible by remember { mutableStateOf(false) }
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
        if (state.catalog.isEmpty() && !state.catalogLoading) controller.discover()
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
        playerFullscreen = false
        playbackIntent = null
        playbackFailure = null
        awaitingResolve = false
        openingSubject = subject
        searchExpanded = false
        controller.open(subject)
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
            sourcesVisible -> sourcesVisible = false
            trackingVisible -> trackingVisible = false
            reviewsVisible -> reviewsVisible = false
            searchExpanded -> searchExpanded = false
            playing && playerFullscreen -> playerFullscreen = false
            playing -> closePlayback()
            route.depth == 1 -> controller.closeDetail()
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

    MotionBackHandler(active && !sourcesVisible && !trackingVisible && !reviewsVisible && !searchPresent,
        state = null, onBack = ::back)

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            MotionTabContent(selected = route, contentKey = { it.key }, order = { it.depth },
                modifier = Modifier.fillMaxSize()) { shown ->
                if (shown.depth == 2) {
                    AnimePlayerPane(controller, state, playback, backdrop,
                        requestedEpisode = playbackIntent?.episode, resolutionError = playbackFailure,
                        active = active, fullscreen = playerFullscreen, onFullscreenChange = { playerFullscreen = it },
                        obscured = sourcesVisible || trackingVisible || reviewsVisible || state.pendingPlayback != null,
                        onBack = ::closePlayback,
                        onRetry = { playbackIntent?.let { requestPlayback(it.episode, it.option) } ?: controller.retryPlayback() },
                        onSources = { showSources(playbackIntent?.episode ?:
                            state.detail?.episodes?.firstOrNull { it.id == state.selectedEpisodeId }) },
                        onEpisode = ::play, modifier = Modifier.fillMaxSize())
                } else {
                    Column(Modifier.fillMaxSize()) {
                        AnimeWorkspaceHeader(root = shown.depth == 0,
                            title = shown.detail?.subject?.title ?: openingSubject?.title ?: "番剧",
                            backdrop = backdrop, navigationVisible = navigationVisible, onManga = onManga,
                            navigation = {
                                if (shown.depth == 0) navigation()
                                else MotionIconButton(onClick = controller::closeDetail, modifier = Modifier.size(44.dp),
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
                                    MotionIconButton(onClick = { trackingVisible = true }, modifier = Modifier.size(44.dp),
                                        tooltip = "追番账号") {
                                        Icon(Icons.Outlined.AccountCircle, "追番账号", Modifier.size(21.dp))
                                    }
                                    Spacer(Modifier.size(44.dp))
                                }
                            })
                        if (shown.depth == 0) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                MotionTabs(tab, listOf("发现", "我的追番"), onSelect = { tab = it },
                                    modifier = Modifier.width(188.dp), controlHeight = 44.dp,
                                    treatment = MotionTabTreatment.Underline)
                                Spacer(Modifier.weight(1f))
                                if (tab == 1) MotionIconButton(onClick = { reviewsVisible = true },
                                    modifier = Modifier.size(44.dp), tooltip = "复核进度差异") {
                                    Icon(if (state.reviews.isEmpty()) Icons.Outlined.Sync else Icons.Outlined.SwapHoriz,
                                        if (state.reviews.isEmpty()) "进度复核" else "待复核 " + state.reviews.size + " 项",
                                        Modifier.size(20.dp))
                                }
                                else MotionIconButton(onClick = controller::discover, modifier = Modifier.size(44.dp),
                                    enabled = !state.catalogLoading, tooltip = "刷新番剧") {
                                    Icon(Icons.Outlined.Refresh, "刷新", Modifier.size(20.dp))
                                }
                            }
                            MotionTabContent(tab, Modifier.weight(1f).mangaHazeSource(backdrop)) { pane ->
                                if (pane == 0) AnimeDiscovery(state, discoveryScroll, onOpen = ::open,
                                    onRetry = { if (state.query.isBlank()) controller.discover() else controller.search(state.query) },
                                    onSearch = { searchExpanded = true })
                                else AnimeLibrary(state, libraryScroll, onOpen = ::open,
                                    onDiscover = { tab = 0 }, onReview = { reviewsVisible = true })
                            }
                        } else if (shown.detail != null) {
                            AnimeDetailPane(shown.detail, state, controller, backdrop,
                                remember(shown.detail.subject.id) { detailScrolls.getOrPut(shown.detail.subject.id) { LazyListState() } },
                                onPlay = ::play,
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
                active = active && !sourcesVisible && !trackingVisible && !reviewsVisible, backdrop = backdrop,
                onExpandedChange = { searchExpanded = it }, onPresenceChange = { searchPresent = it },
                onForegroundState = { searchForeground = it }, onQuery = { query = it },
                onSearch = { controller.search(query.trim()) }, placeholder = "搜索番剧",
                modifier = Modifier.fillMaxSize()
                    .windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Top))
                    .padding(horizontal = 8.dp, vertical = 2.dp)) { height, inputActive ->
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = height).motionInputGate(inputActive),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                    when {
                        query.trim() != state.query -> item {
                            AnimeMessage("搜索番剧名称", "作品资料与播放来源分开核对。", action = "搜索",
                                onAction = { controller.search(query.trim()) })
                        }
                        state.catalogLoading -> item { AnimeMessage("正在搜索", query) }
                        state.catalogError != null -> item { AnimeMessage("搜索未完成", state.catalogError,
                            action = "重试", onAction = { controller.search(query.trim()) }) }
                        state.catalog.isEmpty() -> item { AnimeMessage("没有找到番剧", "试试原名、别名或更短的关键词。") }
                        else -> items(state.catalog, key = { it.id }) { subject ->
                            AnimeSubjectRow(subject, animeSubjectSummary(subject), onOpen = { open(subject) })
                        }
                    }
                }
            }
        }
        if (!playing && (state.notice != null || state.canUndo)) AnimeNotice(state.notice, state.canUndo,
            onUndo = controller::undo, onDismiss = controller::dismissNotice)
    }

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
private fun AnimeDiscovery(state: AnimeScreenState, scroll: LazyGridState, onOpen: (AnimeSubject) -> Unit,
    onRetry: () -> Unit, onSearch: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        when {
            state.catalogLoading && state.catalog.isEmpty() -> AnimeMessage("正在发现番剧", "正在取得作品资料。")
            state.catalogError != null && state.catalog.isEmpty() -> AnimeMessage("番剧列表未取得", state.catalogError,
                action = "重试", onAction = onRetry)
            state.catalog.isEmpty() -> AnimeMessage("从一部想看的番开始", "搜索名称，确认播放来源，再选集观看。",
                action = "搜索番剧", onAction = onSearch)
            else -> {
                val columns = when { maxWidth >= 1000.dp -> 6; maxWidth >= 740.dp -> 5
                    maxWidth >= 520.dp -> 4; maxWidth >= 360.dp -> 3; else -> 2 }
                LazyVerticalGrid(GridCells.Fixed(columns), state = scroll, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            AnimeQuietLabel(if (state.query.isBlank()) "发现番剧 · Bangumi" else "搜索结果 · " + state.query,
                                Modifier.weight(1f))
                            if (state.catalogLoading) AnimeQuietLabel("更新中")
                            if (state.catalogError != null) MotionTextButton(onClick = onRetry) { Text("刷新失败 · 重试") }
                        }
                    }
                    items(state.catalog, key = { it.id }) { subject ->
                        val interaction = remember(subject.id) { MutableInteractionSource() }
                        Column(Modifier.motionPress(interaction, pressedScale = .98f)
                            .clickable(interactionSource = interaction, indication = null, onClick = { onOpen(subject) }),
                            verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            AnimePoster(subject, Modifier.fillMaxWidth().aspectRatio(.7f))
                            Text(subject.title, style = MaterialTheme.typography.labelLarge,
                                maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
                            AnimeQuietLabel(animeSubjectSummary(subject))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimeLibrary(state: AnimeScreenState, scroll: LazyListState, onOpen: (AnimeSubject) -> Unit,
    onDiscover: () -> Unit, onReview: () -> Unit) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val entries = state.library.sortedByDescending { it.updatedAt }.filter {
        when (filter) { 1 -> it.status == AnimeWatchStatus.WATCHING; 2 -> it.resume != null
            else -> it.followed || it.resume != null }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            MotionTabs(filter, listOf("全部", "在看", "有续看"), onSelect = { filter = it },
                modifier = Modifier.width(216.dp), controlHeight = 44.dp, compact = true,
                treatment = MotionTabTreatment.Soft)
        }
        if (entries.isEmpty()) AnimeMessage(if (state.library.isEmpty()) "你的追番从这里开始" else "这里还没有番剧",
            "追番状态与已完成集可以核对；播放位置保存在本机。", action = "去发现", onAction = onDiscover)
        else LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
            if (state.reviews.isNotEmpty()) item(key = "reviews") {
                AnimeSelection("有 " + state.reviews.size + " 部番剧待复核", "逐项比较状态与已完成集，再应用改动。",
                    false, onReview)
            }
            items(entries, key = { it.subject.id }) { entry ->
                val resume = entry.resume
                val subtitle = listOf(
                    animeStatusLabel(entry.status),
                    entry.completedEpisodeIds.size.toString() + " 集已看",
                    if (resume != null) "续看 " + animeTime(resume.positionMs) else if (entry.followed) "已追番" else "本机记录",
                ).joinToString(" · ")
                AnimeSubjectRow(entry.subject, subtitle, onOpen = { onOpen(entry.subject) }, trailing = {
                    Icon(if (resume != null) Icons.Outlined.PlayArrow else Icons.Outlined.ChevronRight,
                        null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                })
            }
        }
    }
}

@Composable
private fun AnimeDetailPane(detail: AnimeDetail, state: AnimeScreenState, controller: AnimeController, backdrop: HazeState,
    scroll: LazyListState,
    onPlay: (AnimeEpisode) -> Unit, onSources: (AnimeEpisode?) -> Unit, onTracking: () -> Unit,
    modifier: Modifier = Modifier) {
    val subject = detail.subject
    val entry = state.library.firstOrNull { it.subject.id == subject.id }
    var episodeTab by rememberSaveable(subject.id) { mutableIntStateOf(0) }
    var descriptionExpanded by rememberSaveable(subject.id) { mutableStateOf(false) }
    var statusOpen by remember { mutableStateOf(false) }
    val main = detail.episodes.filter { it.kind == AnimeEpisodeKind.MAIN }
    val extra = detail.episodes.filter { it.kind != AnimeEpisodeKind.MAIN }
    LaunchedEffect(main.isEmpty(), extra.isNotEmpty()) {
        if (main.isEmpty() && extra.isNotEmpty()) episodeTab = 1
    }
    val episodes = if (episodeTab == 0 || extra.isEmpty()) main else extra
    val resume = entry?.resume?.let { progress -> detail.episodes.firstOrNull { it.id == progress.episodeId } }
    val first = resume ?: main.firstOrNull { it.id !in entry?.completedEpisodeIds.orEmpty() } ?: main.firstOrNull() ?: extra.firstOrNull()
    val sourceNames = state.sourceBindings.map { binding ->
        binding.catalog.sourceId to (state.sourceResults.firstOrNull { it.providerId == binding.catalog.sourceId }?.name ?:
            animeSourceName(binding.catalog.sourceId))
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
                AnimeSectionTitle("选集", "先核对作品与线路；特别篇保留自己的编号。", action = {
                    MotionIconButton(onClick = { onSources(null) }, modifier = Modifier.size(44.dp), tooltip = "来源与集数映射") {
                        Icon(Icons.Outlined.Tune, "来源与集数映射", Modifier.size(19.dp))
                    }
                })
                if (extra.isNotEmpty()) MotionTabs(episodeTab, listOf("正片 " + main.size, "特别篇等 " + extra.size),
                    onSelect = { episodeTab = it }, modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth(),
                    controlHeight = 44.dp, treatment = MotionTabTreatment.Underline)
            }
            if (episodes.isEmpty() && !state.detailLoading && state.detailError == null) item(key = "empty-episodes") {
                AnimeMessage("这组还没有集数", "作品资料尚未提供集数时，不按列表位置猜配播放资源。",
                    action = "查看来源", onAction = { onSources(null) })
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
