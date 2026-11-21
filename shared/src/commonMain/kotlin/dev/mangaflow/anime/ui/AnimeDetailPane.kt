package dev.mangaflow.anime.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.AnimeController
import dev.mangaflow.anime.AnimeDetail
import dev.mangaflow.anime.AnimeEpisode
import dev.mangaflow.anime.AnimeEpisodeAvailability
import dev.mangaflow.anime.AnimeEpisodeKind
import dev.mangaflow.anime.AnimeLibraryEntry
import dev.mangaflow.anime.AnimeNativeMetadataSearch
import dev.mangaflow.anime.AnimeScreenState
import dev.mangaflow.anime.AnimeSourceScore
import dev.mangaflow.anime.AnimeSubject
import dev.mangaflow.anime.AnimeWatchProgress
import dev.mangaflow.anime.AnimeWatchStatus
import dev.mangaflow.anime.canonicalBangumiId
import dev.mangaflow.anime.canonicalDoubanId
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.typography.MangaFontScene
import dev.mangaflow.ui.CompactGlassPopover
import dev.mangaflow.ui.CompactMenuAction
import dev.mangaflow.ui.CommunityPanel
import dev.mangaflow.ui.DesktopVerticalScrollbar
import dev.mangaflow.ui.DetailAction
import dev.mangaflow.ui.screenFitContentInsets
import dev.mangaflow.data.ReviewPage
import dev.mangaflow.ui.GlassSurfaceRole
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.mangaFontFamily
import dev.mangaflow.ui.motion.*
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnimeDetailPane(
    detail: AnimeDetail,
    state: AnimeScreenState,
    controller: AnimeController,
    backdrop: HazeState,
    scroll: LazyListState,
    onPlay: (AnimeEpisode) -> Unit,
    onOpenRelated: (AnimeSubject) -> Unit,
    onSources: (AnimeEpisode?) -> Unit,
    onTracking: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val subject = detail.subject
    val nativeSource = subject.externalIds["native-source"]
    val native = nativeSource != null
    val douban = subject.canonicalDoubanId() != null
    val supportsMetadataLink = native || douban
    val entry = state.library.firstOrNull { it.subject.id == subject.id }
    val linkedMetadata = (detail.metadataSubject ?: entry?.nativeMetadata.takeIf { supportsMetadataLink })
        ?.takeIf { it.canonicalBangumiId() != null }
    val canonicalInfo = subject.takeIf { it.canonicalBangumiId() != null } ?: linkedMetadata
    val currentDiscussion by controller.discussion.state.collectAsStateWithLifecycle()
    val discussion = currentDiscussion.takeIf { it.subjectId == subject.id }
    val commentSources = discussion?.sources.orEmpty()
    var detailTab by rememberSaveable(subject.id, "detail-sections") { mutableIntStateOf(0) }
    var discussionFilter by remember(subject.id) { mutableStateOf(false) }
    var discussionFilterChanged by remember(subject.id) { mutableStateOf(false) }
    var discussionChecksPresent by remember(subject.id) { mutableStateOf(false) }
    var discussionOpenRevision by rememberSaveable(subject.id) { mutableIntStateOf(0) }
    var statusPresent by remember(subject.id) { mutableStateOf(false) }
    var episodeMenuOwner by remember(subject.id) { mutableStateOf<String?>(null) }
    val sections = rememberSaveableStateHolder()
    val groups = rememberSaveableStateHolder()
    val detailTabs = listOf("剧集", "简介", "评论")
    fun selectDetailTab(index: Int) {
        if (index == 2) discussionOpenRevision++
        discussionFilter = false
        detailTab = index
    }
    val commentsVisible = active && detailTab == 2
    DisposableEffect(controller, subject.id, commentsVisible) {
        if (commentsVisible) controller.discussion.setVisible(subject.id, true)
        onDispose { if (commentsVisible) controller.discussion.setVisible(subject.id, false) }
    }
    val metadataSearch = state.nativeMetadataSearch?.takeIf { it.subjectId == subject.id }
    var metadataQuery by rememberSaveable(subject.id, "metadata-query") { mutableStateOf(subject.title) }
    var reviewedMetadata by rememberSaveable(subject.id, "metadata-candidate") { mutableStateOf<String?>(null) }
    var unlinkMetadata by rememberSaveable(subject.id, "metadata-unlink") { mutableStateOf(false) }
    val completed = entry?.completedEpisodeIds.orEmpty()
    var episodeTab by rememberSaveable(subject.id) { mutableIntStateOf(0) }
    var episodeReversed by rememberSaveable(subject.id) { mutableStateOf(false) }
    var episodeQuery by rememberSaveable(subject.id) { mutableStateOf("") }
    var episodeSearch by rememberSaveable(subject.id) { mutableStateOf(false) }
    var locateRevision by rememberSaveable(subject.id) { mutableIntStateOf(0) }
    var appliedLocate by rememberSaveable(subject.id) { mutableIntStateOf(0) }
    val (main, extra) = remember(detail.episodes, native) {
        if (native) detail.episodes to emptyList() else detail.episodes.partition { it.kind == AnimeEpisodeKind.MAIN }
    }
    val selectedTab = if (main.isEmpty() && extra.isNotEmpty()) 1 else if (extra.isEmpty()) 0 else episodeTab
    val selectedEpisodes = if (selectedTab == 0) main else extra
    val search = episodeQuery.trim()
    val episodes = remember(selectedEpisodes, episodeReversed, search) {
        val ordered = if (episodeReversed) selectedEpisodes.asReversed() else selectedEpisodes
        if (search.isEmpty()) ordered else ordered.filter { episode ->
            animeEpisodeLabel(episode).contains(search, ignoreCase = true) || episode.title.contains(search, ignoreCase = true)
        }
    }
    val savedProgress = entry?.resume
    val savedEpisode = detail.episodes.firstOrNull { it.id == savedProgress?.episodeId && it.subjectId == subject.id }
    val first = controller.continuationEpisode(subject.id) ?: if (savedProgress == null)
        main.firstOrNull { it.id !in completed } ?: main.firstOrNull() ?: extra.firstOrNull() else null
    val firstAvailability = first?.let { controller.episodeAvailability(it.id, state) }
    val bindings = remember(state.sourceBindings, subject.id) { state.sourceBindings.filter { it.subjectId == subject.id } }
    val sourceNames = bindings.sortedBy { it.catalog.sourceId != state.preferredSourceId }
        .map { it.catalog.sourceId }.distinct().map { it to animeSourceName(it, state) }
    val pendingSourceCount = (state.groupSourceCandidates.map { it.sourceId } +
        state.associationChoices.filterValues { it.isNotEmpty() }.keys).distinct().count { id -> sourceNames.none { it.first == id } }
    val sourceNeedsAttention = state.sourceError != null || state.sourceCatalogErrors.isNotEmpty() ||
        state.sourceCatalogs.any { !it.notice.isNullOrBlank() } || pendingSourceCount > 0
    val pendingNativeCatalog = state.sourceCatalogs.firstOrNull {
        it.title.sourceId == nativeSource && !it.notice.isNullOrBlank() && detail.episodes.isEmpty()
    }
    val missingSelectedGroup = pendingNativeCatalog?.let { catalog ->
        bindings.firstOrNull { it.catalog.sourceId == catalog.title.sourceId && it.catalog.id == catalog.title.id &&
            it.catalog.url == catalog.title.url }?.selectedGroup?.let { group -> catalog.episodes.none { it.group == group } }
    } == true
    val detailScores = (state.detailScores + animeSubjectRatingSeeds(subject).filter {
        state.detailScoreChecks[it.provider] == null
    }).distinctBy { it.provider }
    val liveScores = observedAnimeRatingSnapshot(subject, detailScores, enabled = active).scores
    val motion = LocalMotionEnabled.current
    val scope = rememberCoroutineScope()
    val header = key(subject.id) { rememberTopAppBarState(initialHeightOffsetLimit = 0f) }
    var catalogueHeaderOffset by remember(subject.id) { mutableFloatStateOf(0f) }
    var informationOwnsHeader by remember(subject.id) { mutableStateOf(false) }
    var measuredHero by remember(subject.id) { mutableIntStateOf(0) }
    var measuredDock by remember(subject.id) { mutableIntStateOf(0) }
    var activeCatalogScroll by remember(subject.id) { mutableStateOf(scroll) }
    var contentsRequested by remember(subject.id) { mutableStateOf(false) }
    var headerJump by remember(subject.id) { mutableStateOf<Job?>(null) }
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val fontScale = density.fontScale.coerceAtLeast(1f)
        val wide = maxWidth >= 840.dp * fontScale.coerceAtMost(1.5f)
        val collapsedHero = !wide && maxHeight < 420.dp
        val width = minOf(maxWidth, if (wide) 1440.dp else 1080.dp)
        val horizontal = if (wide) 20.dp else 16.dp
        val bottomInset = screenFitContentInsets().asPaddingValues().calculateBottomPadding()
        val dockInset = if (wide) 16.dp + bottomInset else
            maxOf(64.dp, with(density) { measuredDock.toDp() }) + 24.dp + bottomInset
        val dockTravel = with(density) { 96.dp.toPx() }
        val dockCollapse by remember(header, activeCatalogScroll, detailTab, wide, dockTravel) {
            derivedStateOf {
                if (wide || detailTab != 0) 0f else maxOf(
                    (-header.heightOffset / dockTravel).coerceIn(0f, 1f),
                    if (activeCatalogScroll.firstVisibleItemIndex > 0) 1f else
                        (activeCatalogScroll.firstVisibleItemScrollOffset / dockTravel).coerceIn(0f, 1f),
                )
            }
        }
        LaunchedEffect(detailTab, measuredHero, wide, motion) {
            if (wide || measuredHero <= 0) return@LaunchedEffect
            if (detailTab != 0 && !informationOwnsHeader) {
                catalogueHeaderOffset = header.heightOffset
                informationOwnsHeader = true
            }
            if (detailTab == 0 && !informationOwnsHeader && !contentsRequested) return@LaunchedEffect
            val target = (if (detailTab != 0 || contentsRequested) header.heightOffsetLimit
                else catalogueHeaderOffset).coerceIn(header.heightOffsetLimit, 0f)
            if (motion) Animatable(header.heightOffset).animateTo(target,
                spring(dampingRatio = MangaMotion.NavigationDamping, stiffness = 240f)) {
                header.heightOffset = value.coerceIn(header.heightOffsetLimit, 0f)
            } else header.heightOffset = target
            if (detailTab == 0) { informationOwnsHeader = false; contentsRequested = false }
        }
        val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
            state = header, snapAnimationSpec = null, flingAnimationSpec = rememberSplineBasedDecay())
        val scrolling = remember(behavior) { object : NestedScrollConnection by behavior.nestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) headerJump?.cancel()
                return behavior.nestedScrollConnection.onPreScroll(available, source)
            }
        } }
        fun locateSavedEpisode() {
            val alreadyInCatalogue = detailTab == 0
            savedEpisode?.let { episodeTab = if (native || it.kind == AnimeEpisodeKind.MAIN) 0 else 1 }
            episodeQuery = ""
            episodeSearch = false
            locateRevision++
            contentsRequested = true
            selectDetailTab(0)
            if (!wide && alreadyInCatalogue) {
                headerJump?.cancel()
                headerJump = scope.launch {
                    if (motion) Animatable(header.heightOffset).animateTo(header.heightOffsetLimit,
                        tween(320, easing = MangaMotion.EnterEasing)) { header.heightOffset = value }
                    else header.heightOffset = header.heightOffsetLimit
                    contentsRequested = false
                }
            } else if (wide) contentsRequested = false
        }
        val hero: @Composable () -> Unit = {
            AnimeDetailHero(subject, canonicalInfo, liveScores, entry, !state.libraryReadUnavailable,
                active, wide, backdrop, native,
                onFollow = { controller.setFollowed(subject, entry?.followed != true) },
                onStatus = { controller.setWatchStatus(subject.id, it) }, onTracking = onTracking,
                onStatusPresence = { statusPresent = it })
        }
        val dock: @Composable (Modifier) -> Unit = { dockModifier ->
            AnimeDetailReadingDock(subject.id, first, firstAvailability, savedProgress,
                persistent = wide, collapseProgress = { dockCollapse }, backdrop = backdrop,
                active = active && !statusPresent && !discussionChecksPresent && episodeMenuOwner == null,
                modifier = dockModifier,
                onHeightChanged = { measuredDock = maxOf(measuredDock, it) },
                onContinue = {
                    if (savedProgress != null) {
                        controller.dismissNotice()
                        controller.resume(subject.id)
                    } else first?.let(onPlay)
                },
                onSources = { onSources(first) }, onContents = ::locateSavedEpisode)
        }
        val sectionPane: @Composable (Modifier) -> Unit = { paneModifier ->
            Column(paneModifier) {
                Row(Modifier.fillMaxWidth().padding(horizontal = horizontal).heightIn(min = 44.dp)
                    .motionInputGate(active && !statusPresent && !discussionChecksPresent && episodeMenuOwner == null),
                    verticalAlignment = Alignment.CenterVertically) {
                    MotionTabs(detailTab, detailTabs, ::selectDetailTab,
                        Modifier.weight(1f), compact = true, controlHeight = 44.dp, fontSize = 13.sp,
                        treatment = MotionTabTreatment.Underline,
                        foreground = colors.onSurface, muted = colors.onSurfaceVariant,
                        track = Color.Transparent, indicator = colors.onSurface.copy(alpha = .85f))
                    if (detailTab == 2) {
                        AnimeDiscussionSourcesStrip(subject.id, discussion?.checks.orEmpty(), backdrop,
                            enabled = active, canRetry = canonicalInfo != null,
                            onRetry = { controller.discussion.retry(subject.id, it) },
                            onPresence = { discussionChecksPresent = it }, compact = true)
                        DetailAction(Icons.Outlined.Refresh, "刷新评论",
                            enabled = active && !discussionChecksPresent && canonicalInfo != null &&
                                discussion?.matching != true && commentSources.none { it.loadingMore },
                            onClick = {
                                commentSources.forEach { controller.discussion.retry(subject.id, it.id) }
                                controller.discussion.retryMatch(subject.id)
                            })
                        DetailAction(Icons.Outlined.FilterList, "筛选评论",
                            selected = discussionFilter || discussionFilterChanged,
                            enabled = active && !discussionChecksPresent,
                            onClick = { discussionFilter = !discussionFilter })
                    } else if (detailTab == 0) {
                        Box(contentAlignment = Alignment.Center) {
                        if (sourceNames.isNotEmpty()) AnimeSourceBadge(sourceNames,
                            Modifier.heightIn(min = 44.dp).widthIn(max = 88.dp).semantics {
                                stateDescription = listOfNotNull("${sourceNames.size} 个已关联片源",
                                    "$pendingSourceCount 个待确认".takeIf { pendingSourceCount > 0 },
                                    "正在找源".takeIf { state.sourceAggregationRunning },
                                    "来源需要查看".takeIf { sourceNeedsAttention }).joinToString("，")
                            }, compact = true, enabled = active, onClick = { onSources(null) })
                        else DetailAction(Icons.Outlined.Link, when {
                            state.sourceAggregationRunning -> "正在查找播放来源"
                            sourceNeedsAttention -> "查看播放来源与重试"
                            else -> "播放来源"
                        }, enabled = active, onClick = { onSources(null) })
                        if (state.sourceAggregationRunning) MotionBusyIndicator(
                            Modifier.align(Alignment.BottomEnd).padding(end = 3.dp, bottom = 5.dp).size(9.dp),
                            color = colors.onSurfaceVariant, strokeWidth = 1.dp)
                        else if (sourceNeedsAttention) Icon(Icons.Outlined.ErrorOutline, null,
                            Modifier.align(Alignment.BottomEnd).padding(end = 3.dp, bottom = 5.dp).size(10.dp),
                            tint = colors.onSurfaceVariant)
                        }
                    }
                }
                MotionTabContent(detailTab, Modifier.weight(1f).fillMaxWidth(), soft = true) { target ->
                    sections.SaveableStateProvider(target) {
                        val paneActive = active && target == detailTab && !statusPresent &&
                            !discussionChecksPresent && episodeMenuOwner == null
                        when (target) {
                            2 -> if (supportsMetadataLink && canonicalInfo == null) {
                                Box(Modifier.fillMaxSize().padding(horizontal = horizontal, vertical = 8.dp)) {
                                    AnimeDetailStatus("补充评论资料", Icons.Outlined.Info,
                                        detail = "确认对应作品和季度后，可读取 Bangumi 等来源的评论。",
                                        actionLabel = "补充资料", onAction = { selectDetailTab(1) })
                                }
                            } else CommunityPanel(mangaId = "anime:${subject.id}", sources = commentSources,
                                loading = discussion == null || discussion.matching,
                                onMore = { controller.discussion.loadMore(subject.id, it) },
                                onRetry = { controller.discussion.retry(subject.id, it) },
                                onMatch = { _, _ -> }, onRelink = {}, loadReplies = { _, _, _ -> ReviewPage(emptyList()) },
                                modifier = Modifier.fillMaxSize().motionInputGate(paneActive),
                                backdrop = backdrop, active = paneActive, bottomPadding = dockInset,
                                openRevision = discussionOpenRevision,
                                filterOpen = discussionFilter, onFilterDismiss = { discussionFilter = false },
                                onFilterChanged = { discussionFilterChanged = it })
                            1 -> {
                                val position = rememberLazyListState()
                                Box(Modifier.fillMaxSize()) {
                                    LazyColumn(Modifier.fillMaxSize().motionInputGate(paneActive), state = position,
                                        contentPadding = PaddingValues(start = horizontal, end = horizontal, bottom = dockInset)) {
                                        item(key = "description") {
                                            val info = subject.copy(description = subject.description.ifBlank { canonicalInfo?.description.orEmpty() },
                                                tags = subject.tags.ifEmpty { canonicalInfo?.tags.orEmpty() })
                                            if (info.description.isNotBlank() || info.tags.isNotEmpty()) AnimeDetailDescription(info)
                                            else AnimeMessage("暂无简介", subject.originalTitle.takeIf { it != subject.title && it.isNotBlank() })
                                        }
                                        if (supportsMetadataLink && !state.libraryReadUnavailable) {
                                            nativeMetadataPicker(linkedMetadata, metadataSearch, paneActive, metadataQuery, reviewedMetadata, unlinkMetadata,
                                                onQueryChange = { metadataQuery = it },
                                                onSearch = { reviewedMetadata = null; unlinkMetadata = false
                                                    controller.searchNativeMetadata(subject.id, metadataQuery) },
                                                onReview = { reviewedMetadata = it }, onRequestUnlink = { unlinkMetadata = it },
                                                onSave = { controller.setNativeMetadata(subject.id, it) },
                                                onClose = { unlinkMetadata = false; reviewedMetadata = null
                                                    controller.dismissNativeMetadataSearch(subject.id) })
                                        }
                                        if (state.relatedSubjects.isNotEmpty()) {
                                            item(key = "related-heading") { AnimeSectionTitle("相关番剧", modifier = Modifier.semantics { heading() }) }
                                            items(state.relatedSubjects, key = { "related:" + it.id }) { related ->
                                                AnimeSubjectRow(related, animeSubjectSummary(related), onOpen = { onOpenRelated(related) },
                                                    ratings = { AnimeSubjectRatings(related, enabled = paneActive) })
                                            }
                                        }
                                    }
                                    if (wide) DesktopVerticalScrollbar(position, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                                }
                            }
                            else -> Column(Modifier.fillMaxSize()) {
                                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = horizontal)
                                    .motionInputGate(paneActive),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    if (main.isNotEmpty() && extra.isNotEmpty()) MotionTabs(selectedTab, listOf("正片", "特别篇"),
                                        onSelect = { episodeTab = it }, modifier = Modifier.weight(1f), compact = true,
                                        controlHeight = 44.dp, treatment = MotionTabTreatment.Soft)
                                    else Text("${selectedEpisodes.size} 集", style = MaterialTheme.typography.labelMedium,
                                        color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                                    if (savedProgress != null) DetailAction(Icons.Outlined.MyLocation, "定位上次观看集",
                                        enabled = paneActive, onClick = ::locateSavedEpisode)
                                    if (detail.episodes.size > 1 || episodeSearch) {
                                        DetailAction(if (episodeSearch) Icons.Outlined.Close else Icons.Outlined.Search,
                                            if (episodeSearch) "关闭集数查找" else "查找集数", selected = episodeSearch,
                                            enabled = paneActive, onClick = { episodeSearch = !episodeSearch; if (!episodeSearch) episodeQuery = "" })
                                        DetailAction(Icons.Outlined.SwapVert, if (episodeReversed) "改为正序" else "改为倒序",
                                            selected = episodeReversed, enabled = paneActive, onClick = { episodeReversed = !episodeReversed })
                                    }
                                }
                                AnimatedVisibility(episodeSearch,
                                    enter = expandVertically(tween(if (motion) MangaMotion.Content else 0)) + fadeIn(),
                                    exit = shrinkVertically(tween(if (motion) MangaMotion.Content else 0)) + fadeOut()) {
                                    AnimeTextField(episodeQuery, { episodeQuery = it }, "集号或标题",
                                        Modifier.padding(horizontal = horizontal, vertical = 4.dp).motionInputGate(paneActive && episodeSearch))
                                }
                                groups.SaveableStateProvider("$selectedTab:${search.isNotEmpty()}") {
                                    val position = if (selectedTab == 0 && search.isEmpty()) scroll else rememberLazyListState()
                                    SideEffect { if (target == detailTab) activeCatalogScroll = position }
                                    var lastSearch by rememberSaveable(subject.id) { mutableStateOf("") }
                                    LaunchedEffect(search, paneActive) {
                                        if (paneActive && search.isNotEmpty() && lastSearch != search) {
                                            position.requestScrollToItem(0); lastSearch = search
                                        }
                                    }
                                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                                        val columns = ((maxWidth - horizontal * 2).value / ((if (wide) 116f else 72f) * fontScale))
                                            .toInt().coerceIn(1, if (wide) 8 else 4)
                                        val rows = remember(episodes, columns) { episodes.chunked(columns) }
                                        val showStatus = episodes.isEmpty() || state.detailLoading || state.detailError != null
                                        val missingResume = savedProgress != null && savedEpisode == null && !state.detailLoading
                                        val prefix = (if (state.libraryReadUnavailable) 1 else 0) + (if (showStatus) 1 else 0) +
                                            (if (missingResume) 1 else 0)
                                        LaunchedEffect(locateRevision, selectedTab, episodes, columns, paneActive, prefix) {
                                            if (!paneActive || locateRevision == appliedLocate || search.isNotEmpty()) return@LaunchedEffect
                                            val index = episodes.indexOfFirst { it.id == savedProgress?.episodeId }
                                            if (index >= 0) { position.scrollToItem(prefix + index / columns); appliedLocate = locateRevision }
                                        }
                                        LazyColumn(Modifier.fillMaxSize().motionInputGate(paneActive), state = position,
                                            contentPadding = PaddingValues(start = horizontal, end = horizontal, top = 4.dp, bottom = dockInset),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            if (state.libraryReadUnavailable) item(key = "library-read-warning") {
                                                AnimeDetailStatus("临时浏览 · 不保存进度", Icons.Outlined.Info,
                                                    detail = "本地追番记录未能读取，原收藏与观看记录已保留。")
                                            }
                                            if (showStatus) item(key = "episode-status") {
                                                when {
                                                    state.detailLoading -> AnimeDetailStatus(if (detail.episodes.isEmpty()) "正在加载集数" else "正在更新集数",
                                                        Icons.Outlined.Info, loading = true)
                                                    state.detailError != null -> AnimeDetailStatus(if (detail.episodes.isEmpty()) "集数暂不可用" else "资料更新未完成",
                                                        Icons.Outlined.CloudOff, detail = state.detailError, actionLabel = "重新加载", onAction = controller::retryCurrentDetail)
                                                    pendingNativeCatalog != null -> AnimeDetailStatus(if (missingSelectedGroup) "原线路暂不可用" else "原线路尚未加载完整",
                                                        Icons.Outlined.Info, detail = pendingNativeCatalog.notice,
                                                        actionLabel = if (missingSelectedGroup) null else "继续加载原线路",
                                                        onAction = { controller.refreshSource(pendingNativeCatalog.title.sourceId) })
                                                    search.isNotEmpty() -> AnimeDetailStatus("没有匹配的集数", Icons.Outlined.Search,
                                                        actionLabel = "清除查找", actionIcon = Icons.Outlined.Close, onAction = { episodeQuery = "" })
                                                    else -> AnimeDetailStatus("暂无集数", Icons.Outlined.Info)
                                                }
                                            }
                                            if (missingResume) item(key = "missing-resume") {
                                                AnimeDetailStatus("上次观看记录已保留", Icons.Outlined.History,
                                                    detail = "当前目录尚未找到对应剧集，不会用其他集替代。",
                                                    actionLabel = "查看作品线路", actionIcon = Icons.Outlined.Link, onAction = { onSources(null) })
                                            }
                                            items(rows, key = { row -> "episodes:" + row.first().id }) { row ->
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    row.forEach { episode -> key(episode.id) {
                                                        val lines = controller.confirmedPlaybackLines(episode.id, state)
                                                        AnimeEpisodeCell(episode, episode.id in completed,
                                                            episode.id == savedEpisode?.id,
                                                            state.episodeProgress[episode.id]?.takeIf { it.subjectId == subject.id },
                                                            controller.episodeAvailability(episode.id, state),
                                                            animeEpisodeSources(episode.id, lines, controller, state),
                                                            backdrop, active && target == detailTab, compact = wide,
                                                            libraryAvailable = !state.libraryReadUnavailable,
                                                            onPlay = { onPlay(episode) }, onSources = { onSources(episode) },
                                                            onCompleted = { controller.setEpisodeCompleted(episode.id, episode.id !in completed) },
                                                            onMenuPresenceChange = { present -> if (present) episodeMenuOwner = episode.id
                                                                else if (episodeMenuOwner == episode.id) episodeMenuOwner = null },
                                                            modifier = Modifier.weight(1f))
                                                    } }
                                                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                                }
                                            }
                                        }
                                        if (wide) DesktopVerticalScrollbar(position, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (wide) Row(Modifier.width(width).fillMaxHeight().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.width((width * .36f).coerceIn(280.dp, 420.dp)).fillMaxHeight()) {
                val summaryPosition = rememberScrollState()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.fillMaxSize().verticalScroll(summaryPosition).padding(end = 12.dp)) { hero() }
                    DesktopVerticalScrollbar(summaryPosition, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
                dock(Modifier.fillMaxWidth().padding(bottom = bottomInset))
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(colors.outlineVariant.copy(alpha = .4f)))
            sectionPane(Modifier.weight(1f).fillMaxHeight())
        } else Box(Modifier.widthIn(max = 1080.dp).fillMaxSize()) {
            Column(Modifier.fillMaxSize().then(if (detailTab == 0) Modifier.nestedScroll(scrolling) else Modifier)) {
                Box(Modifier.fillMaxWidth().clipToBounds()
                    .scrollable(activeCatalogScroll, Orientation.Vertical, reverseDirection = true, enabled = active && detailTab == 0)
                    .layout { measurable, constraints ->
                        val content = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                        val expanded = if (collapsedHero) 0 else content.height
                        if (measuredHero != expanded) measuredHero = expanded
                        val limit = -expanded.toFloat()
                        if (header.heightOffsetLimit != limit) {
                            header.heightOffsetLimit = limit
                            header.heightOffset = header.heightOffset.coerceIn(limit, 0f)
                        }
                        val offset = header.heightOffset.roundToInt().coerceIn(-expanded, 0)
                        layout(content.width, (expanded + offset).coerceIn(constraints.minHeight, constraints.maxHeight)) {
                            content.placeRelative(0, offset)
                        }
                    }.padding(horizontal = horizontal)) { hero() }
                sectionPane(Modifier.weight(1f).fillMaxWidth())
            }
            dock(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp)
                .padding(bottom = bottomInset + 12.dp))
        }
    }
}

private fun LazyListScope.nativeMetadataPicker(
    linked: AnimeSubject?,
    search: AnimeNativeMetadataSearch?,
    active: Boolean,
    query: String,
    reviewedId: String?,
    unlink: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onReview: (String) -> Unit,
    onRequestUnlink: (Boolean) -> Unit,
    onSave: (String?) -> Unit,
    onClose: () -> Unit,
) {
    item(key = "native-metadata") {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AnimeSectionTitle("补充资料", linked?.let { listOfNotNull(it.title, it.year?.toString()).joinToString(" · ") }) {
                if (search == null) MotionTextButton(onClick = onSearch,
                    enabled = active && query.isNotBlank(), modifier = Modifier.heightIn(min = 44.dp)) {
                    Text(if (linked == null) "关联 Bangumi" else "更换关联")
                } else {
                    MotionIconButton(onClick = onClose, enabled = active && !search.saving,
                        modifier = Modifier.size(44.dp), tooltip = "关闭资料选择") {
                        Icon(Icons.Outlined.Close, null, Modifier.size(18.dp))
                    }
                }
            }
            if (search != null) {
                val editable = active && !search.saving
                if (unlink && linked != null) {
                    Text("解除 ${linked.title} 的资料与评论关联？", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MotionTextButton(onClick = { onRequestUnlink(false) }, enabled = editable) { Text("保留关联") }
                        MotionButton(onClick = { onSave(null) }, enabled = editable) { Text("解除关联") }
                    }
                } else {
                    AnimeTextField(query, onQueryChange, "全名或原名", enabled = editable,
                        onSubmit = { if (editable && query.isNotBlank()) onSearch() })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MotionTextButton(onClick = onSearch, enabled = editable && query.isNotBlank()) { Text("搜索 Bangumi") }
                        if (linked != null) MotionTextButton(onClick = { onRequestUnlink(true) }, enabled = editable) {
                            Text("解除关联")
                        }
                    }
                }
                when {
                    search.saving -> AnimeDetailStatus("正在保存资料关联", Icons.Outlined.Info, loading = true)
                    search.loading -> AnimeDetailStatus("正在查找 ${search.query}", Icons.Outlined.Search, loading = true)
                    search.error != null -> AnimeDetailStatus("资料操作未完成", Icons.Outlined.Info, detail = search.error)
                    !unlink && search.candidates.isEmpty() -> AnimeQuietLabel("未找到 ${search.query} 的资料，可用原名或加上季度再搜索。")
                    !unlink -> AnimeQuietLabel("选择相同作品与季度，补充剧集与评论资料。")
                }
            }
        }
    }
    if (search != null && !unlink) items(search.candidates, key = { "metadata:${it.id}" }) { candidate ->
        val reviewed = candidate.id == reviewedId
        val enabled = active && !search.saving
        Column(Modifier.motionInputGate(enabled)) {
            AnimeSubjectRow(candidate, subtitle = listOfNotNull(
                candidate.year?.toString(), candidate.episodeCount?.let { "$it 集" },
                "#${candidate.id.substringAfter(':')}",
                candidate.originalTitle.takeIf { it.isNotBlank() && it != candidate.title },
            ).joinToString(" · "), onOpen = { if (enabled) onReview(candidate.id) }, trailing = {
                if (reviewed || candidate.id == linked?.id) Icon(Icons.Outlined.Check, "已选择", Modifier.size(18.dp))
            })
            if (reviewed && candidate.id != linked?.id) {
                Column(Modifier.padding(start = 68.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(candidate.title, style = MaterialTheme.typography.bodyMedium)
                    MotionButton(onClick = { onSave(candidate.id) }, enabled = enabled,
                        modifier = Modifier.heightIn(min = 44.dp)) { Text("确认关联") }
                }
            }
        }
    }
}

@Composable
private fun AnimeDetailHero(
    subject: AnimeSubject,
    metadata: AnimeSubject?,
    scores: List<AnimeSourceScore>,
    entry: AnimeLibraryEntry?,
    libraryAvailable: Boolean,
    active: Boolean,
    wide: Boolean,
    backdrop: HazeState,
    native: Boolean,
    onFollow: () -> Unit,
    onStatus: (AnimeWatchStatus) -> Unit,
    onTracking: () -> Unit,
    onStatusPresence: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val facts = listOfNotNull((subject.year ?: metadata?.year)?.toString(),
        (subject.episodeCount ?: metadata?.episodeCount)?.takeIf { it > 0 }?.let { "$it 集" }).joinToString(" · ")
    val alternateTitle = listOfNotNull(subject.originalTitle, metadata?.originalTitle, metadata?.title)
        .firstOrNull { it.isNotBlank() && it != subject.title }
    val artwork = metadata?.takeIf { subject.coverUrl.isBlank() && it.coverUrl.isNotBlank() } ?: subject
    val context = LocalMangaContext.current
    val motion = LocalMotionEnabled.current
    val density = LocalDensity.current
    val ratings = LocalAnimeRatings.current
    val openRatings = { ratings.open(AnimeRatingRequest(subject.id, subject.title, subject, scores)) }
    BoxWithConstraints(Modifier.fillMaxWidth().clipToBounds()) {
        val stacked = maxWidth < 300.dp || density.fontScale > 1.35f || wide
        val posterWidth = minOf(maxWidth * if (stacked) .62f else .38f, 224.dp)
        val imageWidth = with(density) { (posterWidth.roundToPx() * 2).coerceIn(1, 1200) }
        val request = remember(context, artwork.coverUrl, artwork.externalIds, imageWidth, motion) {
            ImageRequest.Builder(imagePlatformContext(context)).animeArtwork(artwork.coverUrl, animePosterSourceId(artwork))
                .size(imageWidth, imageWidth * 3 / 2).crossfade(if (motion) MangaMotion.Quick else 0).build()
        }
        val painter = rememberAsyncImagePainter(request, contentScale = ContentScale.Fit)
        val surface = colors.surface
        if (artwork.coverUrl.isNotBlank()) {
            Image(painter, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                0f to surface.copy(alpha = .52f), .54f to surface.copy(alpha = .80f), .89f to surface, 1f to surface)))
            if (!stacked) Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
                0f to Color.Transparent, .28f to Color.Transparent, .64f to surface.copy(alpha = .86f), 1f to surface)))
        }
        val poster: @Composable () -> Unit = {
            LiquidGlassSurface(backdrop, Modifier.width(posterWidth), cornerRadius = 12.dp,
                role = GlassSurfaceRole.ARTWORK, elevation = 0.dp, softCard = false) {
                Box(Modifier.fillMaxWidth().padding(2.dp).aspectRatio(2f / 3f), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Tv, null, Modifier.size(28.dp), tint = colors.onSurfaceVariant.copy(alpha = .26f))
                    Image(painter, contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)))
                }
            }
        }
        val information: @Composable (Modifier) -> Unit = { infoModifier ->
            Column(infoModifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(subject.title, Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
                        fontWeight = FontWeight.SemiBold))
                if (alternateTitle != null) Text(alternateTitle, style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (facts.isNotBlank()) Text(facts, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                if (scores.isNotEmpty()) AnimeRatingBadges(subject.id, scores, Modifier.fillMaxWidth(),
                    onExpand = openRatings.takeIf { active && ratings.active })
                else if (metadata != null || subject.canonicalDoubanId() != null) MotionTextButton(
                    onClick = openRatings, enabled = active && ratings.active,
                    contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 44.dp)) {
                    Icon(Icons.Outlined.StarOutline, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("各站评分", style = MaterialTheme.typography.labelMedium)
                }
                AnimeDetailFollow(entry, backdrop, libraryAvailable, active, native,
                    onFollow, onStatus, onTracking, onStatusPresence)
            }
        }
        Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (stacked) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { poster() }
                information(Modifier.fillMaxWidth())
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically) { poster(); information(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun AnimeDetailFollow(
    entry: AnimeLibraryEntry?,
    backdrop: HazeState,
    libraryAvailable: Boolean,
    active: Boolean,
    native: Boolean,
    onFollow: () -> Unit,
    onStatus: (AnimeWatchStatus) -> Unit,
    onTracking: () -> Unit,
    onPresence: (Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var trackingAfterStatus by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (!active) {
            open = false
            trackingAfterStatus = false
        }
    }
    val followed = entry?.followed == true
    val status = entry?.takeIf { it.followed }?.status
    Box {
        LiquidGlassSurface(backdrop, cornerRadius = 22.dp, role = GlassSurfaceRole.CONTROL) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val followLabel = if (followed) "取消追番" else "加入追番"
                MotionIconButton(onClick = onFollow, enabled = libraryAvailable && active,
                    modifier = Modifier.size(44.dp), tooltip = followLabel) {
                    Icon(if (followed) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                        followLabel, Modifier.size(19.dp),
                        tint = if (followed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
                MotionIconButton(
                    onClick = { open = true }, enabled = libraryAvailable && active,
                    modifier = Modifier.size(44.dp),
                    tooltip = status?.let { "追番状态 · " + animeStatusLabel(it) } ?: "追番状态",
                ) {
                    Icon(Icons.Outlined.MoreHoriz, status?.let { "追番状态 · " + animeStatusLabel(it) } ?: "追番状态",
                        Modifier.size(18.dp))
                }
            }
        }
        CompactGlassPopover(
            open, backdrop,
            onDismiss = { open = false; trackingAfterStatus = false },
            onPresenceChange = { present ->
                onPresence(present)
                if (!present && trackingAfterStatus) {
                    trackingAfterStatus = false
                    if (active) onTracking()
                }
            },
        ) {
            AnimeWatchStatus.entries.forEach { option ->
                CompactMenuAction(
                    if (option == status) Icons.Outlined.Check else Icons.Outlined.RadioButtonUnchecked,
                    animeStatusLabel(option), onClick = { open = false; onStatus(option) },
                )
            }
            if (!native) CompactMenuAction(Icons.Outlined.Sync, "核对追番进度", onClick = {
                trackingAfterStatus = true
                open = false
            })
        }
    }
}

@Composable
private fun AnimeDetailDescription(subject: AnimeSubject) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (subject.description.isNotBlank()) Text(subject.description,
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        if (subject.tags.isNotEmpty()) Text(subject.tags.joinToString("  ·  "),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AnimeDetailStatus(
    title: String,
    icon: ImageVector,
    detail: String? = null,
    loading: Boolean = false,
    actionLabel: String? = null,
    actionIcon: ImageVector = Icons.Outlined.Refresh,
    onAction: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    var expanded by remember(detail) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) MotionBusyIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp)
        else Icon(icon, null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            if (expanded && !detail.isNullOrBlank()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        if (!detail.isNullOrBlank()) {
            val label = if (expanded) "收起原因" else "查看原因"
            MotionIconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(44.dp), tooltip = label) {
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, label, Modifier.size(18.dp))
            }
        }
        if (actionLabel != null) {
            MotionIconButton(onClick = onAction, modifier = Modifier.size(44.dp), tooltip = actionLabel) {
                Icon(actionIcon, actionLabel, Modifier.size(18.dp))
            }
        }
    }
}

private fun upcomingEpisodeLabel(episode: AnimeEpisode, fullDate: Boolean = false): String {
    val date = episode.airDate?.takeIf { it.isNotBlank() } ?: return "尚未放送"
    return "预计 ${if (fullDate) date else date.substringAfter('-')}"
}

/** The manga chapter tile's two-line hierarchy, with exact video progress and episode sources. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AnimeEpisodeCell(
    episode: AnimeEpisode,
    completed: Boolean,
    resume: Boolean,
    progress: AnimeWatchProgress?,
    availability: AnimeEpisodeAvailability,
    sources: AnimeEpisodeSources,
    backdrop: HazeState,
    active: Boolean,
    compact: Boolean,
    libraryAvailable: Boolean,
    onPlay: () -> Unit,
    onSources: () -> Unit,
    onCompleted: () -> Unit,
    onMenuPresenceChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val native = episode.subjectId.startsWith("native:")
    val label = animeEpisodeLabel(episode)
    val subtitle = animeEpisodeSubtitle(episode)
    val canAutoStart = availability != AnimeEpisodeAvailability.UPCOMING
    val sourceState = when (availability) {
        AnimeEpisodeAvailability.MATCHED -> null
        AnimeEpisodeAvailability.CHECKING -> "查找中"
        AnimeEpisodeAvailability.UNMATCHED -> "尚未匹配"
        AnimeEpisodeAvailability.RECENTLY_FAILED -> "可重试"
        AnimeEpisodeAvailability.UPCOMING -> upcomingEpisodeLabel(episode)
    }
    val interaction = remember(episode.id) { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    val ink = if (resume) colors.primary else if (completed) colors.onSurfaceVariant.copy(alpha = .7f) else colors.onSurface
    var menuVisible by remember(episode.id) { mutableStateOf(false) }
    var sourcesAfterMenu by remember(episode.id) { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) { menuVisible = false; sourcesAfterMenu = false } }
    Box(modifier) {
        Column(Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (resume) colors.primary.copy(alpha = .055f) else Color.Transparent)
            .motionPress(interaction, enabled = active, pressedScale = .98f)
            .combinedClickable(interactionSource = interaction, indication = null, enabled = active, role = Role.Button,
                onClickLabel = if (canAutoStart) "播放 $label" else "查看 $label 的来源",
                onClick = if (canAutoStart) onPlay else onSources,
                onLongClickLabel = "本集操作", onLongClick = { menuVisible = true })
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(label, subtitle).joinToString(" ")
                stateDescription = listOfNotNull(animeWatchProgressDescription(progress, completed),
                    "上次观看集".takeIf { resume }, sourceState).joinToString("，")
                if (active) customActions = listOf(CustomAccessibilityAction("本集操作") { menuVisible = true; true })
            }.padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, minLines = 1, maxLines = if (native && !compact) 2 else 1,
                overflow = TextOverflow.Ellipsis, fontSize = 13.sp, lineHeight = 20.sp,
                fontWeight = if (resume) FontWeight.SemiBold else FontWeight.Medium, color = ink)
            Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (sources.sources.isNotEmpty()) AnimeEpisodeSourceBadges(sources,
                        Modifier.fillMaxWidth(), enabled = active, ink = colors.onSurfaceVariant, onOpen = onSources)
                    else if (availability == AnimeEpisodeAvailability.UPCOMING) Text("待播",
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    else if (availability == AnimeEpisodeAvailability.RECENTLY_FAILED) Icon(Icons.Outlined.CloudOff,
                        "播放失败，可重试", Modifier.size(12.dp), tint = colors.onSurfaceVariant)
                }
                AnimeWatchProgressMark(progress, completed, size = 11.dp)
            }
        }
        CompactGlassPopover(menuVisible, backdrop,
            onDismiss = { menuVisible = false; sourcesAfterMenu = false },
            onPresenceChange = { present ->
                onMenuPresenceChange(present)
                if (!present && sourcesAfterMenu) { sourcesAfterMenu = false; if (active) onSources() }
            }) {
            if (subtitle != null) AnimeQuietLabel(subtitle, Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
            AnimeQuietLabel(listOfNotNull(animeWatchProgressDescription(progress, completed),
                sourceState ?: "${sources.sources.size} 个来源 · ${sources.lineCount} 条已匹配线路").joinToString(" · "),
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            CompactMenuAction(if (completed) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                if (completed) "取消已看" else "标记已看", enabled = libraryAvailable,
                onClick = { menuVisible = false; onCompleted() })
            CompactMenuAction(Icons.Outlined.SwapHoriz, "本集来源与映射",
                onClick = { sourcesAfterMenu = true; menuVisible = false })
        }
    }
}
