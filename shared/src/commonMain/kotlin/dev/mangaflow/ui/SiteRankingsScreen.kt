@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class)
package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState
import dev.mangaflow.DetailState
import dev.mangaflow.SiteRankingsController
import dev.mangaflow.SiteRankingsState
import dev.mangaflow.data.*
import dev.mangaflow.platform.*
import dev.mangaflow.typography.MangaFontScene
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.mangaflow.ui.motion.*
import dev.mangaflow.ui.platform.*

/** Adapts the existing ranking row, Ovio selectors and retained contextual popover. */
@Composable
internal fun SiteRankingsScreen(
    controller: SiteRankingsController,
    backdrop: HazeState,
    navigation: @Composable () -> Unit,
    active: Boolean,
    onOpen: (SiteRankedWork) -> Unit,
    onInspect: (SiteRankedWork) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val availableWidth = maxWidth
    val state by controller.state.collectAsStateWithLifecycle()
    DisposableEffect(controller, active) {
        controller.setVisible(active)
        onDispose { controller.setVisible(false) }
    }
    var siteMenu by remember { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) { siteMenu = false; filterMenu = false } }
    val density = LocalDensity.current
    val safeTop = screenFitContentInsets().asPaddingValues().calculateTopPadding()
    val scale = density.fontScale.coerceAtLeast(1f)
    val stackedHeader = availableWidth - 16.dp < 56.dp + 280.dp * scale
    val primaryHeight = maxOf(44.dp, with(density) { 20.sp.toDp() })
    val secondaryHeight = maxOf(44.dp, with(density) { 12.sp.toDp() * 1.3f + 16.dp })
    var headerHeight by remember(density, safeTop, availableWidth) { mutableIntStateOf(with(density) {
        (safeTop + primaryHeight + 4.dp +
            (if (stackedHeader) 4.dp + maxOf(40.dp, with(density) { 20.sp.toDp() }) else 0.dp) +
            (if (state.selection.site.kinds.size > 1) secondaryHeight else 0.dp)).roundToPx()
    }) }
    val top = with(density) { headerHeight.toDp() } + HeaderContentGap
    val positions = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize().semantics { paneTitle = "各站榜单" }) {
        MotionTabContent(selected = state, contentKey = { it.selection.key },
            order = { it.selection.site.ordinal * 100 + it.selection.kind.ordinal * 10 + it.selection.scope.ordinal },
            soft = true, modifier = Modifier.fillMaxSize()) { pane ->
            val selected = pane.selection == state.selection
            var retainedTop by remember(pane.selection.key) { mutableStateOf(top) }
            SideEffect { if (selected) retainedTop = top }
            positions.SaveableStateProvider(pane.selection.key) {
                RankingPane(pane, backdrop, if (selected) top else retainedTop,
                    active = active && selected && !siteMenu && !filterMenu,
                    onRetry = controller::retry, onMore = controller::more, onOpen = onOpen, onInspect = onInspect)
            }
        }
        SegmentedGlassHeader(backdrop, navigation,
            controlsWidth = 148.dp * density.fontScale.coerceAtLeast(1f),
            actionsWidth = 132.dp * density.fontScale.coerceAtLeast(1f),
            modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { headerHeight = it.height }
                .windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Top))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            actions = {
                Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        MotionTextButton(onClick = { filterMenu = !filterMenu; siteMenu = false },
                            enabled = active, modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                            Text(state.selection.scope.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                            Icon(Icons.Outlined.FilterList, "榜单范围与说明", Modifier.padding(start = 4.dp).size(17.dp))
                        }
                        CompactGlassPopover(filterMenu && active, backdrop, onDismiss = { filterMenu = false },
                            maximumWidth = 320.dp) {
                            Text("范围", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.labelMedium)
                            state.selection.site.scopes.forEach { value ->
                                CompactMenuAction(if (value == state.selection.scope) Icons.Outlined.Check
                                    else Icons.AutoMirrored.Outlined.MenuBook, value.label) {
                                    filterMenu = false; controller.selectScope(value)
                                }
                            }
                            Text(state.selection.explanation, Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (state.fetchedAt > 0) Text("本地获取：" +
                                formatInstantMillis(state.fetchedAt, "MM-dd HH:mm"),
                                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    MotionIconButton(onClick = controller::refresh,
                        enabled = active && !state.loading && !state.refreshing && !state.loadingMore,
                        modifier = Modifier.size(44.dp)) {
                        if (state.refreshing) MotionBusyIndicator(Modifier.size(17.dp))
                        else Icon(Icons.Outlined.Refresh, "刷新当前站点榜单", Modifier.size(19.dp))
                    }
                }
            },
            secondary = {
                MotionTabContent(selected = state.selection, contentKey = { it.site.id },
                    order = { it.site.ordinal }, fillContent = false,
                    modifier = Modifier.fillMaxWidth()) { pane ->
                    val kinds = pane.site.kinds
                    if (kinds.size > 1) LiquidGlassSurface(backdrop,
                        Modifier.fillMaxWidth().padding(start = 50.dp), cornerRadius = 20.dp,
                        role = GlassSurfaceRole.NAVIGATION) {
                        MotionTabs(selected = kinds.indexOf(pane.kind).coerceAtLeast(0),
                            labels = kinds.map { it.label },
                            onSelect = { if (active && pane.site == state.selection.site) controller.selectKind(kinds[it]) },
                            treatment = MotionTabTreatment.Underline, fontSize = 12.sp, controlHeight = 44.dp,
                            modifier = Modifier.fillMaxWidth().motionInputGate(active && pane.site == state.selection.site))
                    }
                }
            },
        ) {
            Box(Modifier.fillMaxWidth()) {
                MotionTextButton(onClick = { siteMenu = !siteMenu; filterMenu = false },
                    enabled = active, modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    ReviewSourceMark(state.selection.site.id, size = 17.dp)
                    Text(state.selection.site.title, modifier = Modifier.weight(1f).padding(start = 6.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                    if (state.selection.site.kinds.size == 1) Text("评分", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.Outlined.ExpandMore, "选择榜单站点", Modifier.size(17.dp))
                }
                CompactGlassPopover(siteMenu && active, backdrop, onDismiss = { siteMenu = false }) {
                    RankingSite.entries.forEach { site ->
                        CompactMenuAction(if (site == state.selection.site) Icons.Outlined.Check else Icons.Outlined.Language,
                            site.title) { siteMenu = false; controller.selectSite(site) }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun RankingPane(state: SiteRankingsState, backdrop: HazeState,
    top: androidx.compose.ui.unit.Dp, active: Boolean, onRetry: () -> Unit, onMore: () -> Unit,
    onOpen: (SiteRankedWork) -> Unit, onInspect: (SiteRankedWork) -> Unit,
) {
    if (state.loading && state.entries.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(top = top).mangaHazeSource(backdrop)) { QuietLoading("正在读取榜单…") }
        return
    }
    val grid = rememberLazyGridState()
    val desktop = platformKind == PlatformKind.DESKTOP
    val nearEnd by remember(grid) { derivedStateOf {
        val info = grid.layoutInfo
        info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 4
    } }
    LaunchedEffect(active, nearEnd, state.entries.size, state.hasMore, state.loadingMore, state.refreshing, state.error) {
        if (active && nearEnd && state.entries.isNotEmpty() && state.hasMore &&
            !state.loadingMore && !state.refreshing && state.error == null) onMore()
    }
    Box(Modifier.fillMaxSize().mangaHazeSource(backdrop).motionInputGate(active)) {
        LazyVerticalGrid(columns = if (desktop) GridCells.Adaptive(360.dp * LocalDensity.current.fontScale.coerceAtLeast(1f))
            else GridCells.Fixed(1), state = grid, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = if (desktop) 24.dp else 16.dp,
                top = top + 8.dp, bottom = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.entries, key = { it.work.key }, contentType = { "native-ranking" }) { entry ->
                SiteRankingRow(entry, state.selection.kind, entry.work.key in state.linkedIds,
                    state.selection.key, onOpen, onInspect)
            }
            item(key = "rank-footer", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        state.error != null -> {
                            Text(state.error, Modifier.padding(vertical = 10.dp),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MotionTextButton(onClick = onRetry, enabled = active) { Text("重试") }
                        }
                        state.loadingMore -> MotionBusyIndicator(Modifier.padding(16.dp).size(20.dp))
                        state.entries.isEmpty() -> EmptyMessage("当前范围没有条目", "可以更换范围或站点。")
                    }
                    if (state.entries.isEmpty() && state.hasMore && !state.loadingMore && state.error == null)
                        MotionTextButton(onClick = onMore, enabled = active) { Text("继续读取") }
                }
            }
        }
        if (desktop) DesktopVerticalScrollbar(grid, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(7.dp))
    }
}

@Composable
private fun SiteRankingRow(entry: SiteRankingEntry, kind: SiteRankingKind, linked: Boolean,
    selectionKey: String, onOpen: (SiteRankedWork) -> Unit, onInspect: (SiteRankedWork) -> Unit) {
    val w = entry.work
    val interaction = remember { MutableInteractionSource() }
    val transition = LocalMangaCoverTransition.current
    Row(Modifier.fillMaxWidth().combinedClickable(role = Role.Button, interactionSource = interaction,
        indication = null, onLongClickLabel = "查看作品资料与重新关联来源", onLongClick = { onInspect(w) }, onClick = {
            transition?.select?.invoke(MangaCoverKey(w.manga.id, "site-ranking-" + selectionKey))
            onOpen(w)
        }).motionPress(interaction, pressedScale = .985f),
        verticalAlignment = Alignment.CenterVertically) {
        Text(entry.position.toString().padStart(2, '0'), Modifier.widthIn(min = 30.dp).padding(end = 6.dp),
            style = mangaMetricValueStyle(18.sp, 24.sp), maxLines = 1,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (entry.position <= 3) .84f else .42f))
        CatalogueCover(w.manga, Modifier.width(68.dp).height(98.dp)
            .mangaSharedCover(w.manga.id, "site-ranking-" + selectionKey).mangaCoverCard(7.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp).heightIn(min = 98.dp),
            verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(w.manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
                    fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (w.manga.authors.isNotEmpty()) Text(w.manga.authors.take(2).joinToString(" / "),
                    fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val value = when (kind) {
                    SiteRankingKind.SCORE -> w.score?.toString()?.removeSuffix(".0")
                    SiteRankingKind.POPULARITY -> w.popularity?.let(::rankingCount)
                    SiteRankingKind.TRENDING -> w.trending?.let(::rankingCount)
                    SiteRankingKind.FAVOURITES -> w.favourites?.let(::rankingCount)
                }
                if (value != null) Text(value, style = mangaMetricValueStyle(16.sp, 20.sp))
                if (kind == SiteRankingKind.SCORE && w.ratingCount != null)
                    Text(rankingCount(w.ratingCount.toLong()) + "人评分", fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                else if (kind != SiteRankingKind.SCORE && value != null)
                    Text(if (kind == SiteRankingKind.POPULARITY) "书单" else kind.label,
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                if (linked) Icon(Icons.Outlined.Check, "已关联阅读来源", Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Same anonymous cover cache and quiet fallback as MangaCover, without readable-source headers. */
@Composable
private fun CatalogueCover(manga: Manga, modifier: Modifier) {
    val context = LocalMangaContext.current
    val motion = LocalMotionEnabled.current
    val request = remember(context, manga.coverUrl, motion) {
        manga.coverUrl.takeIf(String::isNotBlank)?.let {
            ImageRequest.Builder(imagePlatformContext(context)).data(it)
                .memoryCacheKey("manga-cover:" + it).crossfade(if (motion) MangaMotion.Quick else 0).build()
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(25.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f))
        if (request != null) AsyncImage(request, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

@Composable
internal fun RankedWorkDetailScreen(state: DetailState, onBack: () -> Unit,
    onSearch: (String) -> Unit, onUseSource: (Manga) -> Unit, active: Boolean,
    wallpaper: @Composable () -> Unit = {},
) {
    val work = state.rankedWork ?: return
    val backdrop = rememberHazeState()
    var query by rememberSaveable(work.key) { mutableStateOf(work.manga.title.take(120)) }
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    val safeTop = screenFitContentInsets().asPaddingValues().calculateTopPadding()
    var headerHeight by remember(density, safeTop) { mutableIntStateOf(with(density) { (safeTop + 48.dp).roundToPx() }) }
    var expanded by rememberSaveable(work.key) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(colors.background).screenFitViewport().motionInputGate(active)) {
        wallpaper()
        LazyColumn(Modifier.fillMaxSize().mangaHazeSource(backdrop), contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp, top = with(density) { headerHeight.toDp() } + 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(key = "catalogue-work") {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                    CatalogueCover(work.manga, Modifier.width(104.dp).height(150.dp)
                        .mangaSharedCover(work.manga.id, "detail").mangaCoverCard(9.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(work.manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                        if (work.manga.authors.isNotEmpty()) Text(work.manga.authors.joinToString(" / "),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ReviewSourceMark(work.site.id, size = 15.dp)
                            Text(work.site.title, fontSize = 12.sp)
                            work.score?.let {
                                Text(it.toString().removeSuffix(".0"), style = mangaMetricValueStyle(16.sp, 20.sp))
                                Text(work.scoreScale.toString() + "分制", fontSize = 10.sp, color = colors.onSurfaceVariant)
                            }
                        }
                        work.ratingCount?.let { Text(rankingCount(it.toLong()) + "人评分",
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
                        work.ratingRank?.let { Text("原站评分排名 #" + it,
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
                    }
                }
            }
            item(key = "source-search") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("阅读来源", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        if (state.rankedSourcesLoading) MotionBusyIndicator(Modifier.size(16.dp))
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                        .background(colors.onSurface.copy(alpha = .045f)).padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(query, onValueChange = { query = it.take(120) }, enabled = active,
                            modifier = Modifier.weight(1f).semantics { contentDescription = "用作品名或别名查找阅读来源" },
                            singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                            cursorBrush = SolidColor(colors.onSurface),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                if (active && query.isNotBlank()) {
                                    focus.clearFocus(); onSearch(query)
                                }
                            }))
                        MotionIconButton(onClick = { focus.clearFocus(); onSearch(query) },
                            enabled = active && query.isNotBlank()) {
                            Icon(Icons.Outlined.Search, "查找阅读来源", Modifier.size(19.dp))
                        }
                    }
                    if (!state.rankedSourcesLoading && state.rankedSources.isEmpty())
                        Text(if (state.rankedSourcesError == null) "尚未找到来源，可以换个名称再查找。" else "部分来源未能完成搜索。",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    state.rankedSourcesError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
            items(state.rankedSources, key = { it.manga.id }) { candidate ->
                val interaction = remember { MutableInteractionSource() }
                Row(Modifier.fillMaxWidth().clickable(enabled = active, role = Role.Button,
                    interactionSource = interaction, indication = null, onClick = { onUseSource(candidate.manga) })
                    .motionPress(interaction).padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(candidate.manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
                            fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text((candidate.manga.sourceName.ifBlank { sourceDisplayName(candidate.manga.sourceId) } +
                            " · " + candidate.manga.authors.take(2).joinToString(" / ")).trimEnd(' ', '·'),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    Text(if (candidate.verified) "打开" else "关联并打开", fontSize = 12.sp,
                        color = colors.onSurfaceVariant)
                }
            }
            if (work.manga.description.isNotBlank()) item(key = "catalogue-description") {
                Column {
                    Text(work.manga.description, fontFamily = mangaFontFamily(MangaFontScene.READING),
                        style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 5,
                        overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
                    MotionTextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开简介") }
                }
            }
        }
        PageTitleHeader("作品资料", backdrop, navigation = {
            MotionIconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回榜单", Modifier.size(21.dp))
            }
        }, summary = { Text("作品资料", style = MaterialTheme.typography.titleSmall) },
            modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { headerHeight = it.height }
                .windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Top))
                .padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

private fun rankingCount(value: Long): String = when {
    value >= 100_000_000 -> (value / 10_000_000 / 10.0).toString().removeSuffix(".0") + "亿"
    value >= 10_000 -> (value / 1_000 / 10.0).toString().removeSuffix(".0") + "万"
    else -> value.toString()
}
