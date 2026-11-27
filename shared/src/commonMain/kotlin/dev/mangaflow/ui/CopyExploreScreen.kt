@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.mangaflow.ui

import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState
import dev.mangaflow.CopyExploreController
import dev.mangaflow.CopyExploreSection
import dev.mangaflow.CopyExploreState
import dev.mangaflow.data.CopyCatalogSelection
import dev.mangaflow.data.CopyMangaRepository
import dev.mangaflow.data.CopyTopic
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.data.MangaTag
import dev.mangaflow.data.Page
import dev.mangaflow.data.UnifiedMangaRepository
import dev.mangaflow.data.copyTagForName
import dev.mangaflow.experience.readerPageCacheKey
import dev.mangaflow.experience.sourceNetwork
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionBackHandler
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionInputGate

/** Native Copy discovery, composed from the adopted Ovio, PANEL and contextual popover controls. */
@Composable
internal fun CopyExploreScreen(
    controller: CopyExploreController,
    repository: MangaRepository,
    onBack: () -> Unit,
    onOpen: (Manga) -> Unit,
    isFavorite: (Manga) -> Boolean,
    onToggleFavorite: (Manga) -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val backdrop = rememberHazeState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var directoryOpen by rememberSaveable { mutableStateOf(false) }
    var topicQuery by rememberSaveable { mutableStateOf("") }
    var indexView by rememberSaveable { mutableStateOf(false) }
    var actionsId by remember { mutableStateOf<String?>(null) }
    val positions = rememberSaveable(saver = CopyExplorePositionsSaver) {
        LinkedHashMap<String, LazyGridState>()
    }
    val releaseInput: () -> Unit = { keyboard?.hide(); focus.clearFocus(force = true) }
    val goBack: () -> Unit = {
        if (active) {
            releaseInput()
            if (state.topic != null) controller.closeTopic() else onBack()
        }
    }
    LaunchedEffect(controller, active) {
        if (active) controller.start() else {
            directoryOpen = false
            actionsId = null
            releaseInput()
        }
    }
    LaunchedEffect(state.contentKey) { actionsId = null }
    // An inner topic return keeps this route alive; do not complete the outer frame's back state.
    MotionBackHandler(enabled = active && state.topic != null && !directoryOpen, state = null, onBack = goBack)

    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val desktop = platformKind == PlatformKind.DESKTOP
        val wide = fitsDesktopIndex(maxWidth, 880.dp)
        val openDirectory = { if (active) { releaseInput(); directoryOpen = true } }
        Column(Modifier.fillMaxSize().screenFitViewport()
            .windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Top))
            .mangaHazeSource(backdrop).motionInputGate(active)
            .semantics { paneTitle = state.topic?.title ?: "拷贝分类与专题" }) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 52.dp),
                verticalAlignment = Alignment.CenterVertically) {
                MotionIconButton(goBack, Modifier.size(44.dp), enabled = active,
                    tooltip = if (state.topic != null) "返回专题" else "返回") {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack,
                        if (state.topic != null) "返回专题" else "返回", Modifier.size(21.dp))
                }
                BodySourceMark("copy", "拷贝", Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                if (state.topic == null) MotionTabs(
                    selected = if (state.section == CopyExploreSection.CATALOG) 0 else 1,
                    labels = listOf("分类", "专题"),
                    onSelect = {
                        if (active) {
                            releaseInput()
                            controller.selectSection(if (it == 0) CopyExploreSection.CATALOG else CopyExploreSection.TOPICS)
                        }
                    },
                    modifier = Modifier.width(156.dp), controlHeight = 40.dp,
                    fontSize = 13.sp, fullWidthIndicator = true,
                ) else Text("专题", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                MotionIconButton(onClick = { if (active) controller.refresh() },
                    modifier = Modifier.size(44.dp),
                    enabled = active && !state.loading && !state.refreshing && !state.loadingMore,
                    tooltip = "刷新当前内容") {
                    if (state.refreshing) MotionBusyIndicator(Modifier.size(18.dp)
                        .semantics { contentDescription = "正在刷新，保留当前内容" }, strokeWidth = 1.5.dp)
                    else Icon(Icons.Outlined.Refresh, "刷新当前内容", Modifier.size(20.dp))
                }
            }
            if (state.section == CopyExploreSection.CATALOG && !wide) {
                CopyCatalogControls(state, controller, backdrop, active,
                    onDirectory = openDirectory)
            } else if (state.section == CopyExploreSection.TOPICS && state.topic == null && !wide) {
                CatalogueKeywordField(topicQuery, { topicQuery = it }, "查找已加载专题",
                    active, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("已加载 ${state.topics.size} 个专题", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text("按拷贝原顺序", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Row(Modifier.weight(1f).fillMaxWidth()) {
            if (wide && state.section == CopyExploreSection.CATALOG) DesktopCopyFilters(state, controller,
                enabled = active && !directoryOpen, onDirectory = openDirectory,
                modifier = Modifier.width(214.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)))
            MotionTabContent(selected = state, modifier = Modifier.weight(1f).fillMaxHeight(),
                contentKey = { it.contentKey }, order = {
                    if (it.section == CopyExploreSection.CATALOG) 0 else if (it.topic == null) 1 else 2
                }, soft = true) { pane ->
                val paneActive = active && pane.contentKey == state.contentKey && !directoryOpen
                val topicList = pane.section == CopyExploreSection.TOPICS && pane.topic == null
                val query = if (topicList) topicQuery.trim() else ""
                val topics = remember(pane.topics, query) {
                    pane.topics.filter { topic ->
                        query.isEmpty() || listOf(topic.title, topic.brief, topic.period, topic.date)
                            .any { it.contains(query, ignoreCase = true) }
                    }
                }
                val positionKey = pane.contentKey + if (topicList) ":query:$query" else ""
                val savedGrid = remember(positions, positionKey) { positions.positionFor(positionKey) }
                val initial = pane.loading && (if (topicList) pane.topics.isEmpty() else pane.items.isEmpty())
                val grid = if (initial) remember(positionKey) { LazyGridState() } else savedGrid
                Column(Modifier.fillMaxSize()) {
                val index = wide && !topicList && indexView
                if (wide) {
                    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 20.dp, top = 8.dp, bottom = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(when { topicList -> "专题目录"; pane.topic != null -> pane.topic.title
                                else -> pane.selection.theme?.name ?: pane.selection.original?.name ?: "分类目录" },
                                modifier = Modifier.semantics { heading() }, style = browseTitleStyle(26.sp, 34.sp),
                                maxLines = if (pane.topic == null) 1 else 2, overflow = TextOverflow.Ellipsis)
                            val count = if (topicList) topics.size else pane.items.size
                            val summary = when {
                                topicList && query.isNotEmpty() -> "匹配 $count 个，共 ${pane.topics.size} 个已载入专题"
                                topicList -> "已载入 $count 个专题 · 来源顺序"
                                pane.topic != null -> "已载入 $count 部"
                                else -> listOfNotNull("已载入 $count 部", pane.selection.scope?.name,
                                    pane.directory.orderings.firstOrNull { pane.topic == null && it.path == pane.selection.ordering }?.name)
                                    .joinToString(" · ")
                            }
                            DesktopIndexNote(summary)
                        }
                        if (topicList) Box(Modifier.width(242.dp)) {
                            CatalogueKeywordField(topicQuery, { if (paneActive) topicQuery = it }, "查找已加载专题", paneActive)
                        } else MotionTabs(if (indexView) 1 else 0, listOf("封面", "列表"),
                            onSelect = { if (paneActive) indexView = it == 1 }, modifier = Modifier.width(116.dp).motionInputGate(paneActive),
                            controlHeight = 36.dp, fullWidthIndicator = true, indicatorHeight = 30.dp,
                            treatment = MotionTabTreatment.Soft)
                    }
                    if (index) DesktopIndexLabels("最新章节", "热度与评分", modifier = Modifier.padding(start = 16.dp, end = 22.dp))
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                    val gap = if (desktop) 16.dp else 10.dp
                    val columns = if (desktop) MangaLayout.automaticDesktopColumns(maxWidth, scale, gap, 20.dp)
                        else MangaLayout.columns(maxWidth, if (maxWidth >= 600.dp) 4 else 3, 83.dp * scale, 10.dp, 16.dp)
                    val listColumns = if (index) 1 else if (topicList) MangaLayout.columns(maxWidth, 3, 280.dp * scale, gap, 16.dp)
                        else columns
                    if (desktop && query.isEmpty()) {
                        val tail = remember(pane.items, topics, topicList) {
                            if (topicList) browsePageTail(topics) { "topic:" + it.path }
                            else browsePageTail(pane.items) { "comic:" + it.id }
                        }
                        BrowseGridPagination(grid, positionKey, pane.updatedAt, if (topicList) topics.size else pane.items.size,
                            tail, if (topicList) "copy-topic" else "copy-comic",
                            enabled = paneActive && actionsId == null && pane.hasMore && !pane.loading &&
                                !pane.refreshing && !pane.loadingMore && pane.error == null && pane.failedPage == null,
                            fillViewport = false, onMore = controller::loadMore)
                    }
                    LazyVerticalGrid(columns = GridCells.Fixed(listColumns), state = grid,
                        modifier = Modifier.fillMaxSize().motionInputGate(paneActive),
                        contentPadding = PaddingValues(start = 16.dp, end = if (wide) 22.dp else 16.dp, top = if (index) 0.dp else 10.dp,
                            bottom = 28.dp + screenFitContentInsets().asPaddingValues().calculateBottomPadding()),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalArrangement = Arrangement.spacedBy(if (index) 0.dp else if (topicList) 24.dp else 18.dp)) {
                        pane.topic?.let { topic ->
                            item(key = "topic-heading", span = { GridItemSpan(maxLineSpan) }) {
                                CopyTopicHeading(topic, repository, paneActive, titleInHeader = wide)
                            }
                        }
                        if (initial) {
                            item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                                QuietLoading(if (topicList) "正在读取专题…" else "正在读取漫画…")
                            }
                        } else {
                            if (topicList) items(topics, key = { "topic:" + it.path },
                                contentType = { "copy-topic" }) { topic ->
                                CopyTopicRow(topic, repository, paneActive) {
                                    releaseInput()
                                    controller.openTopic(topic)
                                }
                            } else items(pane.items, key = { "comic:" + it.id },
                                contentType = { "copy-comic" }) { manga ->
                                Box {
                                    val open = { if (paneActive) { releaseInput(); onOpen(manga) } }
                                    val placement = "copy-explore-${pane.contentKey}"
                                    val commands = if (wide) listOf(DesktopMenuAction("详情与目录", icon = Icons.Outlined.ViewList, onClick = open),
                                        desktopShelfAction(isFavorite(manga), "加入本机书架", "移出本机书架") {
                                            if (paneActive) onToggleFavorite(manga)
                                        }) else emptyList()
                                    DesktopBrowseActions(commands, paneActive) {
                                    if (index) DesktopBrowseIndexRow(manga, repository, placement,
                                        detail = { DesktopIndexNote(manga.latestChapter.ifBlank { "—" }) },
                                        figure = { MangaMetricBadges(manga, compact = true, plain = true, showMore = false) },
                                        onLongClick = { if (paneActive) actionsId = manga.id }, onOpen = open)
                                    else CoverTile(manga, repository, dense = true, narrow = columns >= 4, placement = placement,
                                        desktopCatalogue = desktop,
                                        onLongClick = { if (paneActive) actionsId = manga.id },
                                        onClick = open)
                                    }
                                    CompactGlassPopover(visible = paneActive && actionsId == manga.id,
                                        backdrop = backdrop, onDismiss = { actionsId = null }) {
                                        Text(manga.title, Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            style = MaterialTheme.typography.bodyMedium)
                                        val favorite = isFavorite(manga)
                                        CompactMenuAction(
                                            if (favorite) Icons.Outlined.BookmarkRemove else Icons.Outlined.BookmarkAdd,
                                            if (favorite) "移出本机书架" else "加入本机书架",
                                            enabled = paneActive,
                                        ) {
                                            if (paneActive) onToggleFavorite(manga)
                                            actionsId = null
                                        }
                                    }
                                }
                            }
                            val empty = if (topicList) topics.isEmpty() else pane.items.isEmpty()
                            if (empty && pane.error == null) item(key = "empty",
                                span = { GridItemSpan(maxLineSpan) }) {
                                EmptyMessage(
                                    if (topicList && query.isNotEmpty()) "已加载专题中没有匹配项" else "暂无内容",
                                    if (topicList && query.isNotEmpty()) {
                                        if (pane.hasMore) "继续加载后会保留关键词，再匹配新增专题。" else "可以修改关键词查看其他专题。"
                                    } else if (pane.hasMore) "还有后续页面，可以继续加载。"
                                    else "拷贝暂时没有返回内容，可以稍后刷新。",
                                )
                            }
                            item(key = "page-footer", span = { GridItemSpan(maxLineSpan) }) {
                                CopyExploreFooter(pane, empty, paneActive,
                                    onRetry = controller::retry, onMore = controller::loadMore)
                            }
                        }
                    }
                    if (wide) DesktopVerticalScrollbar(grid, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(7.dp))
                }
                }
            }
            }
        }

        CopyDirectoryDialog(visible = directoryOpen && active, state = state,
            backdrop = backdrop, onDismiss = { directoryOpen = false; releaseInput() },
            onChoose = { tag ->
                if (active) { controller.selectTheme(tag); directoryOpen = false; releaseInput() }
            },
            onRetry = { if (active) controller.refreshDirectory() })
    }
}

@Composable
private fun DesktopCopyFilters(state: CopyExploreState, controller: CopyExploreController, enabled: Boolean,
    onDirectory: () -> Unit, modifier: Modifier) {
    val selection = state.selection
    DesktopCatalogueSidebar(modifier) {
        val themes = remember(state.directory.themes, selection.theme) {
            (state.directory.themes.filter { copyConventionNumber(it) == null }.take(6) + listOfNotNull(selection.theme))
                .distinctBy { it.path }
        }
        Column {
            if (themes.isNotEmpty()) {
                val choices = listOf<MangaTag?>(null) + themes
                DesktopFilterOptions("题材", choices.map { it?.name ?: "全部" },
                    choices.indexOfFirst { it?.path == selection.theme?.path }, enabled) { controller.selectTheme(choices[it]) }
            } else DesktopFilterHeading("题材")
            MotionTextButton(onClick = onDirectory, enabled = enabled, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(15.dp))
                Text("全部题材与展会", Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
        if (state.directory.scopes.isNotEmpty()) {
            val scopes = listOf<MangaTag?>(null) + state.directory.scopes
            DesktopFilterOptions("漫画范围", scopes.map { it?.name ?: "全部" },
                scopes.indexOfFirst { it?.path == selection.scope?.path }, enabled) {
                controller.applyScopeAndOrdering(scopes[it], selection.ordering)
            }
        }
        if (state.directory.orderings.isNotEmpty()) {
            val options = state.directory.orderings
            DesktopFilterOptions("排序", options.map { it.name }, options.indexOfFirst { it.path == selection.ordering },
                enabled) { controller.applyScopeAndOrdering(selection.scope, options[it].path) }
        }
        selection.original?.let { DesktopIndexNote("原作 · " + it.name) }
        if (state.directoryLoading) QuietLoading("读取分类目录…")
        state.directoryError?.let { CopyExploreNotice("目录未更新", it, enabled && !state.directoryLoading, controller::refreshDirectory) }
        if (selection != CopyCatalogSelection()) MotionTextButton(onClick = controller::resetFilters, enabled = enabled) {
            Icon(Icons.Outlined.RestartAlt, null, Modifier.size(16.dp))
            Text("重置分类筛选", Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun CopyCatalogControls(
    state: CopyExploreState,
    controller: CopyExploreController,
    backdrop: HazeState,
    active: Boolean,
    onDirectory: () -> Unit,
) {
    var optionsOpen by remember { mutableStateOf(false) }
    val selection = state.selection
    var draftScope by remember { mutableStateOf(selection.scope) }
    var draftOrdering by remember { mutableStateOf(selection.ordering) }
    LaunchedEffect(active) { if (!active) optionsOpen = false }
    val colors = MaterialTheme.colorScheme
    val controlColors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface)
    val ordering = state.directory.orderings.firstOrNull { it.path == selection.ordering }
    val canReset = selection != CopyCatalogSelection()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MotionTextButton(onDirectory, enabled = active && !optionsOpen, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), colors = controlColors) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(16.dp))
                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                    val selectedTheme = selection.theme?.name
                    if (selectedTheme != null) Text("题材与展会", style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant)
                    Text(selectedTheme ?: "题材与展会", style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            MotionTextButton(onClick = {
                draftScope = selection.scope
                draftOrdering = selection.ordering
                optionsOpen = true
            }, enabled = active && !optionsOpen, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), colors = controlColors) {
                Icon(Icons.Outlined.Tune, null, Modifier.size(16.dp))
                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                    Text("筛选与排序", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    Text(listOfNotNull(selection.scope?.name ?: "全部范围", ordering?.name).joinToString(" · "),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                }
            }
            if (canReset) MotionIconButton(onClick = controller::resetFilters, enabled = active && !optionsOpen,
                modifier = Modifier.size(44.dp), tooltip = "重置全部分类筛选") {
                Icon(Icons.Outlined.RestartAlt, "重置全部分类筛选", Modifier.size(17.dp))
            }
        }
        selection.original?.let { original ->
            Text("原作 · ${original.name}", Modifier.padding(start = 6.dp), maxLines = 2,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant)
        }
        if (state.directoryLoading) Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            MotionBusyIndicator(Modifier.size(13.dp).semantics { contentDescription = "正在读取分类目录" },
                color = colors.onSurfaceVariant, strokeWidth = 1.5.dp)
            Text("读取分类目录", Modifier.padding(start = 7.dp), style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant)
        }
        state.directoryError?.let { message ->
            CopyExploreNotice("分类目录未更新", message, active && !state.directoryLoading,
                onRetry = controller::refreshDirectory)
        }
    }
    MotionDialog(visible = optionsOpen && active, onDismissRequest = { optionsOpen = false },
        maximumWidth = 420.dp) {
        BoxWithConstraints {
            val panelHeight = maxHeight.coerceAtMost(560.dp)
            LiquidGlassSurface(backdrop, Modifier.fillMaxWidth().heightIn(max = panelHeight),
                cornerRadius = 24.dp, role = GlassSurfaceRole.PANEL, softCard = true) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("筛选与排序", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        MotionIconButton(onClick = { optionsOpen = false }, enabled = optionsOpen && active,
                            modifier = Modifier.size(44.dp), tooltip = "关闭筛选") {
                            Icon(Icons.Outlined.Close, "关闭筛选", Modifier.size(18.dp))
                        }
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        Text("漫画范围", Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                        Column(Modifier.selectableGroup()) {
                            RankingMenuChoice("全部范围", draftScope == null, optionsOpen && active) { draftScope = null }
                            state.directory.scopes.forEach { scope ->
                                RankingMenuChoice(scope.name, scope.path == draftScope?.path, optionsOpen && active) {
                                    draftScope = scope
                                }
                            }
                        }
                        Text("排序", Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                        Column(Modifier.selectableGroup()) {
                            state.directory.orderings.forEach { option ->
                                RankingMenuChoice(option.name, option.path == draftOrdering, optionsOpen && active) {
                                    draftOrdering = option.path
                                }
                            }
                        }
                        if (state.directoryLoading) Text("正在读取目录", Modifier.padding(vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        else if (state.directoryError != null || state.directory.orderings.isEmpty())
                            CopyExploreNotice("目录选项未载入", state.directoryError ?: "暂未读取到排序选项",
                                optionsOpen && active, onRetry = controller::refreshDirectory)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        MotionTextButton(onClick = { optionsOpen = false }, enabled = optionsOpen && active,
                            colors = controlColors) { Text("取消") }
                        MotionTextButton(onClick = {
                            controller.applyScopeAndOrdering(draftScope, draftOrdering)
                            optionsOpen = false
                        }, enabled = optionsOpen && active && (draftScope?.path != selection.scope?.path ||
                            draftOrdering != selection.ordering), colors = controlColors) { Text("应用") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyDirectoryDialog(
    visible: Boolean,
    state: CopyExploreState,
    backdrop: HazeState,
    onDismiss: () -> Unit,
    onChoose: (MangaTag?) -> Unit,
    onRetry: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableIntStateOf(0) }
    val tags = remember(state.themes, state.directory.themes) {
        (state.themes + state.directory.themes).distinctBy { it.path }
    }
    val conventions = remember(tags) {
        tags.filter { copyConventionNumber(it) != null }.sortedByDescending { copyConventionNumber(it) }
    }
    val ordinary = remember(tags) { tags.filter { copyConventionNumber(it) == null } }
    LaunchedEffect(visible) {
        if (visible && query.isBlank()) group = if (state.selection.theme?.let(::copyConventionNumber) != null) 1 else 0
    }
    val all = if (group == 1) conventions else ordinary
    val matches = remember(all, query) {
        val text = query.trim()
        val resolved = copyTagForName(text, all)?.path
        all.filter { text.isEmpty() || it.name.contains(text, true) || it.path.contains(text, true) || it.path == resolved }
    }
    val desktop = fitsDesktopIndex(LocalMangaWindow.current.width, 850.dp)
    val positions = rememberSaveable(saver = CopyExplorePositionsSaver) { LinkedHashMap<String, LazyGridState>() }
    MotionDialog(visible, onDismissRequest = onDismiss, maximumWidth = if (desktop) 792.dp else 560.dp,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.padding(horizontal = 12.dp)) {
            // Capture the outer BoxWithConstraints receiver before entering the glass lambda.
            val directoryWidth = maxWidth
            LiquidGlassSurface(backdrop, Modifier.fillMaxWidth().height(minOf(maxHeight, 650.dp)),
                cornerRadius = 24.dp, role = GlassSurfaceRole.PANEL, softCard = true) {
                Column(Modifier.fillMaxSize().padding(14.dp).semantics { paneTitle = "拷贝题材与展会目录" }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        MotionTabs(group, listOf("题材", "展会"),
                            onSelect = { if (visible) group = it }, modifier = Modifier.width(160.dp),
                            controlHeight = 40.dp, fontSize = 12.sp, fullWidthIndicator = true)
                        Spacer(Modifier.weight(1f))
                        if (desktop && !state.directoryLoading) DesktopIndexNote(if (query.isBlank()) "共 ${all.size} 项"
                            else "匹配 ${matches.size} 项")
                        MotionIconButton(onDismiss, Modifier.size(44.dp), enabled = visible, tooltip = "关闭目录") {
                            Icon(Icons.Outlined.Close, "关闭目录", Modifier.size(20.dp))
                        }
                    }
                    CatalogueKeywordField(query, { query = it }, "查找名称或编号", visible,
                        Modifier.padding(vertical = 8.dp))
                    if (state.directoryLoading) MotionBusyIndicator(
                        Modifier.padding(vertical = 4.dp).size(16.dp)
                            .semantics { contentDescription = "正在读取分类目录" }, strokeWidth = 1.5.dp)
                    state.directoryError?.let { message ->
                        CopyExploreNotice("目录更新失败", message, visible && !state.directoryLoading, onRetry)
                    }
                    val key = "${group}:${query.trim()}"
                    val grid = if (desktop) remember(positions, key) { positions.positionFor(key) } else rememberLazyGridState()
                    val columnCount = if (desktop) MangaLayout.columns(directoryWidth, 5,
                        140.dp * LocalDensity.current.fontScale.coerceAtLeast(1f), 4.dp, 14.dp)
                        else if (directoryWidth >= 440.dp) 3 else 2
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyVerticalGrid(columns = GridCells.Fixed(columnCount),
                        state = grid, modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(end = if (desktop) 8.dp else 0.dp, bottom = 10.dp)) {
                        if (state.selection.theme != null || state.selection.original != null) item(
                            key = "current-selection", span = { GridItemSpan(maxLineSpan) }) {
                            SelectionContainer {
                                Text(listOfNotNull(state.selection.original?.let { "原作：${it.name}" },
                                    state.selection.theme?.let { "已选：${it.name}" }).joinToString("\n"),
                                    Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item(key = "all") {
                            CopyDirectoryChoice("不限题材", null, state.selection.theme == null, visible) { onChoose(null) }
                        }
                        items(matches, key = { "tag:" + it.path }) { tag ->
                            CopyDirectoryChoice(tag.name, tag.count, state.selection.theme?.path == tag.path, visible) {
                                onChoose(tag)
                            }
                        }
                        if (matches.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                            Text(when {
                                state.directoryLoading -> "正在读取…"
                                query.isNotBlank() -> "没有匹配的名称"
                                state.directoryError != null -> "目录未能读取，请重试"
                                else -> "当前目录没有这一类标签"
                            }, Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (desktop) DesktopVerticalScrollbar(grid, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyDirectoryChoice(
    name: String,
    count: Int?,
    chosen: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    MotionTextButton(onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
            selected = chosen
            contentDescription = name + (count?.let { "，$it 部" } ?: "")
        },
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (chosen) colors.primary.copy(alpha = .10f) else Color.Transparent,
            contentColor = if (chosen) colors.primary else colors.onSurface),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
        if (chosen) Icon(Icons.Outlined.Check, null, Modifier.size(13.dp))
        Text(name, Modifier.weight(1f).padding(start = if (chosen) 4.dp else 0.dp),
            maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        if (count != null) Text(count.toString(), Modifier.padding(start = 5.dp),
            style = mangaMetricValueStyle(), color = colors.onSurfaceVariant)
    }
}

@Composable
private fun CopyTopicRow(topic: CopyTopic, repository: MangaRepository, active: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    MotionTextButton(onClick = { if (active) onClick() },
        modifier = Modifier.fillMaxWidth(), enabled = active,
        colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface),
        contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (topic.coverUrl.isNotBlank()) CopyTopicCover(topic, repository,
                Modifier.fillMaxWidth().aspectRatio(3.1f).mangaCoverCard(12.dp, shadowRadius = 8.dp))
            Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(topic.title, style = browseTitleStyle(19.sp, 25.sp), color = colors.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val caption = topic.brief.ifBlank { topic.period }
                if (caption.isNotBlank()) Text(copyTopicPlainText(caption), style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (topic.date.isNotBlank()) Text(topic.date.substringBefore('T').substringBefore(' '),
                    style = mangaMetricValueStyle(), color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CopyTopicHeading(topic: CopyTopic, repository: MangaRepository, active: Boolean, titleInHeader: Boolean) {
    val colors = MaterialTheme.colorScheme
    val description = remember(topic.intro, topic.brief) { copyTopicPlainText(topic.intro.ifBlank { topic.brief }) }
    var expanded by rememberSaveable(topic.path) { mutableStateOf(false) }
    var descriptionOverflows by remember(description) { mutableStateOf(false) }
    val motion = LocalMotionEnabled.current
    val disclosure = animateFloatAsState(if (expanded) 1f else 0f,
        if (motion) spring(MangaMotion.PressDamping, MangaMotion.PressStiffness) else snap(), label = "topic-disclosure")
    val caption: @Composable () -> Unit = {
        SelectionContainer {
            Column(Modifier.animateContentSize(if (motion)
                spring(MangaMotion.NavigationDamping, MangaMotion.GestureStiffness) else snap()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!titleInHeader) Text(topic.title, style = browseTitleStyle(25.sp, 32.sp))
                if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant, maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) descriptionOverflows = it.hasVisualOverflow })
            }
        }
        if (expanded || descriptionOverflows) MotionTextButton(onClick = { if (active) expanded = !expanded },
            enabled = active, contentPadding = PaddingValues(horizontal = 0.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)) {
            Text(if (expanded) "收起简介" else "展开简介", style = MaterialTheme.typography.labelSmall)
            Icon(Icons.Outlined.ExpandMore, null, Modifier.padding(start = 4.dp).size(15.dp)
                .graphicsLayer { rotationZ = 180f * disclosure.value })
        }
        Text(listOf(topic.period, "拷贝专题顺序").filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp))
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (topic.coverUrl.isNotBlank() && fitsDesktopIndex(maxWidth, 620.dp)) {
            val artWidth = (maxWidth * .43f).coerceAtMost(450.dp)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
                CopyTopicCover(topic, repository, Modifier.width(artWidth).aspectRatio(2.8f)
                    .mangaCoverCard(6.dp, shadowRadius = 4.dp), scale = ContentScale.Fit)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { caption() }
            }
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (topic.coverUrl.isNotBlank()) CopyTopicCover(topic, repository,
                Modifier.fillMaxWidth().heightIn(max = 236.dp).aspectRatio(2.65f).mangaCoverCard(14.dp, shadowRadius = 9.dp))
            caption()
        }
    }
}

@Composable
private fun CopyExploreFooter(
    state: CopyExploreState,
    empty: Boolean,
    active: Boolean,
    onRetry: () -> Unit,
    onMore: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.error != null) {
            if (empty) EmptyMessage("暂时没能读取", state.error)
            else CopyExploreNotice("本次读取未完成", state.error,
                active && !state.refreshing && !state.loadingMore, onRetry)
            if (empty) MotionTextButton(onClick = { if (active) onRetry() },
                enabled = active && !state.loadingMore && !state.refreshing) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(17.dp))
                Text("重试", Modifier.padding(start = 6.dp))
            }
        } else if (state.loadingMore) {
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                MotionBusyIndicator(Modifier.size(17.dp), strokeWidth = 1.5.dp)
                Text("正在加载", Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (state.hasMore) MotionTextButton(onClick = { if (active) onMore() },
            enabled = active && !state.loading && !state.refreshing,
            modifier = Modifier.heightIn(min = 48.dp)) {
            Text("继续加载", style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.padding(start = 6.dp).size(16.dp))
        }
    }
}

@Composable
private fun CopyExploreNotice(title: String, message: String, enabled: Boolean, onRetry: () -> Unit) {
    var expanded by remember(message) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MotionTextButton(onClick = { if (enabled) expanded = !expanded },
                enabled = enabled, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(15.dp))
            }
            MotionIconButton(onClick = { if (enabled) onRetry() }, modifier = Modifier.size(44.dp),
                enabled = enabled, tooltip = "重试") {
                Icon(Icons.Outlined.Refresh, "重试", Modifier.size(17.dp))
            }
        }
        if (expanded) Text(message, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Topic artwork has its own referer; it never enters the comic identity/header pipeline. */
@Composable
private fun CopyTopicCover(topic: CopyTopic, repository: MangaRepository, modifier: Modifier,
    scale: ContentScale = ContentScale.Crop) {
    val context = LocalMangaContext.current
    val motion = LocalMotionEnabled.current
    val copy = when (repository) {
        is UnifiedMangaRepository -> repository.copy
        is CopyMangaRepository -> repository
        else -> null
    }
    val headers = if (topic.coverUrl.isNotBlank()) copy?.topicImageHeaders(topic.path) else null
    val request = remember(context, topic.coverUrl, headers, motion) {
        if (headers == null || topic.coverUrl.isBlank()) null else {
            val cacheKey = readerPageCacheKey(Page(0, topic.coverUrl), headers, "copy")
            ImageRequest.Builder(imagePlatformContext(context)).data(topic.coverUrl)
                .sourceNetwork("copy")
                .memoryCacheKey("copy-topic-cover:$cacheKey").diskCacheKey(cacheKey)
                .httpHeaders(NetworkHeaders.Builder().apply {
                    headers.forEach { (name, value) -> set(name, value) }
                }.build()).crossfade(if (motion) MangaMotion.Quick else 0).build()
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(25.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f))
        if (request != null) AsyncImage(model = request, contentDescription = null,
            contentScale = scale, modifier = Modifier.fillMaxSize())
    }
}

private val CopyConventionPattern = Regex("^C([0-9]+)$", RegexOption.IGNORE_CASE)
private fun copyConventionNumber(tag: MangaTag): Int? =
    (CopyConventionPattern.matchEntire(tag.path.trim()) ?: CopyConventionPattern.matchEntire(tag.name.trim()))
        ?.groupValues?.getOrNull(1)?.toIntOrNull()

private fun copyTopicPlainText(value: String): String = value
    .replace(Regex("(?i)<br\\s*/?>|</p>|</div>"), "\n")
    .replace(Regex("<[^>]+>"), "")
    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&#39;", "'").trim()

private const val CopyExplorePositionLimit = 24
private fun LinkedHashMap<String, LazyGridState>.positionFor(key: String): LazyGridState {
    val grid = remove(key) ?: LazyGridState()
    put(key, grid)
    while (size > CopyExplorePositionLimit) remove(keys.first())
    return grid
}
private val CopyExplorePositionsSaver = listSaver<LinkedHashMap<String, LazyGridState>, Any>(
    save = { positions -> positions.entries.flatMap { (key, state) ->
        listOf(key, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
    } },
    restore = { saved -> LinkedHashMap<String, LazyGridState>().apply {
        saved.chunked(3).takeLast(CopyExplorePositionLimit).forEach { entry ->
            if (entry.size == 3) put(entry[0] as String, LazyGridState(entry[1] as Int, entry[2] as Int))
        }
    } },
)
