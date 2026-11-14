@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.ChevronRight
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
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

/** Reuse root chrome and the actual Ovio tab motion; content has one stable readable base. */
@Composable
internal fun SiteRankingsScreen(
    controller: SiteRankingsController,
    backdrop: HazeState,
    navigation: @Composable () -> Unit,
    active: Boolean,
    onOpen: (SiteRankedWork) -> Unit,
    onInspect: (SiteRankedWork) -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    DisposableEffect(controller, active) {
        controller.setVisible(active)
        onDispose { controller.setVisible(false) }
    }
    var siteMenu by remember { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) { siteMenu = false; filterMenu = false } }
    val colors = MaterialTheme.colorScheme
    val positions = rememberSaveableStateHolder()
    val ready = active && !state.restoringSelection
    RankingPageFrame(Modifier.fillMaxSize().background(colors.background.copy(alpha = .97f))
        .semantics { paneTitle = "各站榜单" }, header = {
        Column {
            PageTitleHeader("各站榜单", backdrop, navigation,
                modifier = Modifier.windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Top))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                summary = {
                    Box {
                        MotionTextButton(onClick = { siteMenu = !siteMenu; filterMenu = false }, enabled = ready,
                            modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 4.dp)) {
                            if (!state.restoringSelection) ReviewSourceMark(state.selection.site.id, size = 17.dp)
                            Text(if (state.restoringSelection) "各站榜单" else state.selection.site.title,
                                Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Outlined.ExpandMore, "选择榜单站点", Modifier.size(17.dp))
                        }
                        CompactGlassPopover(siteMenu && ready, backdrop, onDismiss = { siteMenu = false }) {
                            RankingSite.entries.forEach { site ->
                                CompactMenuAction(if (site == state.selection.site) Icons.Outlined.Check else Icons.Outlined.Language,
                                    site.title) { siteMenu = false; controller.selectSite(site) }
                            }
                        }
                    }
                }, actions = {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        if (state.refreshing) MotionBusyIndicator(Modifier.size(17.dp))
                        else MotionIconButton(onClick = controller::refresh,
                            enabled = ready && !state.loading && !state.loadingMore,
                            modifier = Modifier.size(44.dp), tooltip = "刷新当前榜单") {
                            Icon(Icons.Outlined.Refresh, "刷新当前榜单", Modifier.size(19.dp))
                        }
                    }
                })
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                val kinds = state.selection.site.kinds
                if (kinds.size > 1) BoxWithConstraints(Modifier.weight(1f)) {
                    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                    val minimum = 48.dp * scale * kinds.size
                    val tabsWidth = maxWidth.coerceAtLeast(minimum)
                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                        MotionTabs(kinds.indexOf(state.selection.kind).coerceAtLeast(0), kinds.map { it.label },
                            onSelect = { if (ready) controller.selectKind(kinds[it]) },
                            treatment = MotionTabTreatment.Underline, fontSize = 12.sp, controlHeight = 44.dp,
                            modifier = Modifier.width(tabsWidth).motionInputGate(ready))
                    }
                } else Text("评分", Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Box {
                    MotionTextButton(onClick = { filterMenu = !filterMenu; siteMenu = false }, enabled = ready,
                        contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.heightIn(min = 44.dp)) {
                        Text(if (state.restoringSelection) "范围" else state.selection.scope.label,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        Icon(Icons.Outlined.FilterList, "榜单范围与说明", Modifier.padding(start = 5.dp).size(17.dp))
                    }
                    CompactGlassPopover(filterMenu && ready, backdrop, onDismiss = { filterMenu = false },
                        maximumWidth = 320.dp) {
                        state.selection.site.scopes.forEach { value ->
                            CompactMenuAction(if (value == state.selection.scope) Icons.Outlined.Check
                                else Icons.AutoMirrored.Outlined.MenuBook, value.label) {
                                filterMenu = false; controller.selectScope(value)
                            }
                        }
                        Text(state.selection.explanation, Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        state.preferenceError?.let { Text(it, Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                        if (state.fetchedAt > 0) Text("本地获取：" + formatInstantMillis(state.fetchedAt, "MM-dd HH:mm"),
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }) {
        MotionTabContent(selected = state, contentKey = { it.selection.key },
            order = { it.selection.site.ordinal * 100 + it.selection.kind.ordinal * 10 + it.selection.scope.ordinal },
            soft = true, modifier = Modifier.fillMaxSize()) { pane ->
            positions.SaveableStateProvider(pane.selection.key) {
                RankingPane(pane, backdrop, 0.dp, active = ready && pane.selection == state.selection && !siteMenu && !filterMenu,
                    onRetry = controller::retry, onMore = controller::more, onOpen = onOpen, onInspect = onInspect)
            }
        }
    }
}

/** Adapt AccountScreen's same-pass header/content measurement, without a deferred inset update. */
@Composable
private fun RankingPageFrame(
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Layout(modifier = modifier, content = {
        Box(Modifier.fillMaxWidth()) { header() }
        Box(Modifier.fillMaxSize()) { content() }
    }) { measurables, constraints ->
        val head = measurables[0].measure(constraints.copy(minHeight = 0))
        val remaining = (constraints.maxHeight - head.height).coerceAtLeast(0)
        val body = measurables[1].measure(constraints.copy(minHeight = remaining, maxHeight = remaining))
        layout(constraints.maxWidth, constraints.maxHeight) {
            body.placeRelative(0, head.height)
            head.placeRelative(0, 0)
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
                top = top + 6.dp, bottom = 24.dp + screenFitContentInsets().asPaddingValues().calculateBottomPadding()),
            horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val transition = LocalMangaCoverTransition.current
    val placement = "ranking-site-" + selectionKey
    val inspect = {
        transition?.select?.invoke(MangaCoverKey(w.manga.id, placement))
        onInspect(w)
    }
    Row(Modifier.fillMaxWidth().combinedClickable(role = Role.Button, interactionSource = interaction,
        indication = null, onLongClickLabel = "查看作品与更换阅读来源", onLongClick = inspect, onClick = {
            transition?.select?.invoke(if (linked) null else MangaCoverKey(w.manga.id, placement))
            onOpen(w)
        }).motionPress(interaction, pressedScale = .985f).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(entry.position.toString().padStart(2, '0'), Modifier.widthIn(min = 28.dp).padding(end = 6.dp),
            style = mangaMetricValueStyle(16.sp, 22.sp), maxLines = 1,
            color = colors.onSurface.copy(alpha = if (entry.position <= 3) .84f else .42f))
        CatalogueCover(w.manga, Modifier.width(56.dp).height(80.dp)
            .mangaSharedCover(w.manga.id, placement).mangaCoverCard(7.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(w.manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (w.manga.authors.isNotEmpty()) Text(w.manga.authors.take(2).joinToString(" / "),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = colors.onSurfaceVariant)
            FlowRow(verticalArrangement = Arrangement.spacedBy(2.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                val value = when (kind) {
                    SiteRankingKind.SCORE -> w.score?.toString()?.removeSuffix(".0")
                    SiteRankingKind.POPULARITY -> w.popularity?.let(::rankingCount)
                    SiteRankingKind.TRENDING -> w.trending?.let(::rankingCount)
                    SiteRankingKind.FAVOURITES -> w.favourites?.let(::rankingCount)
                }
                if (value != null) Text(value, modifier = Modifier.alignByBaseline(), style = mangaMetricValueStyle(16.sp, 22.sp))
                if (kind == SiteRankingKind.SCORE && w.ratingCount != null)
                    Text(rankingCount(w.ratingCount.toLong()) + "人评分", Modifier.alignByBaseline(),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
                else if (kind != SiteRankingKind.SCORE && value != null)
                    Text(if (kind == SiteRankingKind.POPULARITY) "书单" else kind.label, Modifier.alignByBaseline(),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
            }
        }
        if (linked) MotionIconButton(onClick = inspect, modifier = Modifier.size(44.dp), tooltip = "更换阅读来源") {
            Icon(Icons.Outlined.Link, "已关联，点击更换阅读来源", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
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
internal fun RankedWorkDetailScreen(
    state: DetailState, repository: MangaRepository, onBack: () -> Unit,
    onSearch: (String, String) -> Unit, onUseSource: (String, Manga) -> Unit,
    onClearSource: (String) -> Unit, onConfirmSource: (String, String, Long) -> Unit,
    onCancelPreview: (String, Long) -> Unit, active: Boolean, wallpaper: @Composable () -> Unit = {},
) {
    val work = state.rankedWork ?: return
    val backdrop = rememberHazeState()
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    var query by rememberSaveable(work.key) { mutableStateOf(work.manga.title.take(120)) }
    var info by remember(work.key) { mutableStateOf(false) }
    var failures by remember(work.key) { mutableStateOf(false) }
    var otherResults by rememberSaveable(work.key) { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) { info = false; failures = false } }
    val candidates = remember(state.rankedSources, state.rankedLinkedSource) {
        state.rankedSources.filterNot { it.manga.id == state.rankedLinkedSource?.id }
    }
    val primary = remember(candidates) { candidates.filter { it.relevant } }
    val secondary = remember(candidates, primary) { candidates.filterNot { it in primary } }
    val rows = if (otherResults || !state.rankedSourcesLoading && primary.isEmpty()) candidates else primary
    val sourceReady = active && state.rankedOpeningSourceId == null
    val submit = {
        if (active && query.isNotBlank()) {
            focus.clearFocus(); otherResults = false; failures = false
            onSearch(work.key, query)
        }
    }
    Box(Modifier.fillMaxSize().background(colors.background).screenFitViewport().motionInputGate(active)) {
        // Wallpaper supplies atmosphere; dense text never shares its contrast with the artwork.
        Box(Modifier.matchParentSize().graphicsLayer { alpha = .08f }) { wallpaper() }
        RankingPageFrame(Modifier.widthIn(max = 880.dp).fillMaxSize().align(Alignment.TopCenter)
            .background(colors.background.copy(alpha = .96f)).semantics { paneTitle = work.manga.title },
            header = {
                PageTitleHeader("作品资料", backdrop, navigation = {
                    MotionIconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回榜单", Modifier.size(21.dp))
                    }
                }, summary = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        ReviewSourceMark(work.site.id, size = 17.dp)
                        Text(work.site.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }, actions = {
                    Box {
                        MotionIconButton(onClick = { info = !info }, enabled = active, modifier = Modifier.size(44.dp),
                            tooltip = "作品资料与评分说明") {
                            Icon(Icons.Outlined.Info, "作品资料与评分说明", Modifier.size(19.dp))
                        }
                        CompactGlassPopover(info, backdrop, onDismiss = { info = false }, maximumWidth = 320.dp) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(work.manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
                                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(work.site.title + " · " + work.scoreScale + "分制",
                                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                work.ratingRank?.let { Text("原站评分排名 #" + it, style = MaterialTheme.typography.bodySmall) }
                                work.popularity?.let { Text(rankingCount(it) + "人加入书单", style = MaterialTheme.typography.bodySmall) }
                                work.favourites?.let { Text(rankingCount(it) + "人标记最爱", style = MaterialTheme.typography.bodySmall) }
                                if (work.manga.description.isNotBlank()) Text(work.manga.description,
                                    fontFamily = mangaFontFamily(MangaFontScene.READING),
                                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                                Text("榜单资料与阅读来源分别保留。选择来源只关联作品，不合并章节编号。",
                                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }, modifier = Modifier.windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Top))
                    .padding(horizontal = 8.dp, vertical = 2.dp))
            }) {
            val scroll = rememberLazyListState()
            val previewId = state.rankedSourcePreview?.selected?.id
            LaunchedEffect(previewId, active) {
                if (active && previewId != null && !scroll.isScrollInProgress &&
                    scroll.layoutInfo.visibleItemsInfo.any { it.key == previewId }) {
                    val index = rows.indexOfFirst { it.manga.id == previewId }
                    if (index >= 0) scroll.animateScrollToItem(2 + (if (state.rankedLinkedSource != null) 1 else 0) + index)
                }
            }
            val bottom = screenFitContentInsets().asPaddingValues().calculateBottomPadding()
            Box(Modifier.fillMaxSize()) {
                LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().mangaHazeSource(backdrop),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp + bottom),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = "catalogue-work") {
                        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                            CatalogueCover(work.manga, Modifier.width(80.dp).height(116.dp)
                                .mangaSharedCover(work.manga.id).mangaCoverCard(9.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(work.manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                                if (work.manga.authors.isNotEmpty()) Text(work.manga.authors.take(2).joinToString(" / "),
                                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    work.score?.let {
                                        Text(it.toString().removeSuffix(".0"), Modifier.alignByBaseline(),
                                            style = mangaMetricValueStyle(18.sp, 24.sp))
                                        Text("/ " + work.scoreScale, Modifier.alignByBaseline(),
                                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                    }
                                    work.ratingCount?.let { Text(rankingCount(it.toLong()) + "人评分", Modifier.alignByBaseline(),
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                                }
                            }
                        }
                    }
                    item(key = "source-search") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("阅读来源", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                if (state.rankedSourcesLoading) MotionBusyIndicator(Modifier.size(16.dp))
                                if (state.rankedLinkedSource != null) MotionTextButton(
                                    onClick = { onClearSource(work.key) }, enabled = sourceReady,
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                                    Text("取消关联", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                }
                            }
                            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                                .background(colors.onSurface.copy(alpha = .045f)).padding(start = 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                BasicTextField(query, onValueChange = { query = it.take(120) }, enabled = active,
                                    modifier = Modifier.weight(1f).semantics { contentDescription = "用完整书名或别名查找阅读来源" },
                                    singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                                    cursorBrush = SolidColor(colors.onSurface),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { submit() }))
                                MotionIconButton(onClick = submit, enabled = active && query.isNotBlank(), modifier = Modifier.size(44.dp)) {
                                    Icon(Icons.Outlined.Search, "查找阅读来源", Modifier.size(19.dp))
                                }
                            }
                            if (state.rankedOpeningSourceId != null) Text("正在核对来源资料…",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            state.rankedSourceError?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                                MotionTextButton(onClick = submit, enabled = active && query.isNotBlank(),
                                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                                    Text("重新查找", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    state.rankedLinkedSource?.let { linked ->
                        item(key = "linked-source") {
                            RankedSourceRow(linked, repository, matched = false, linked = true,
                                opening = state.rankedOpeningSourceId == linked.id, enabled = sourceReady,
                                onClick = { onUseSource(work.key, linked) })
                        }
                    }
                    items(rows, key = { it.manga.id }, contentType = { "reading-source" }) { candidate ->
                        Column {
                            RankedSourceRow(candidate.manga, repository, matched = candidate.verified, linked = false,
                                opening = state.rankedOpeningSourceId == candidate.manga.id, enabled = sourceReady,
                                onClick = { onUseSource(work.key, candidate.manga) })
                            MotionTabContent(selected = state.rankedSourcePreview?.takeIf { it.selected.id == candidate.manga.id },
                                contentKey = { it?.canonical?.id ?: "closed" }, order = { if (it == null) 0 else 1 },
                                fillContent = false, modifier = Modifier.fillMaxWidth()) { preview ->
                                if (preview == null) Spacer(Modifier.height(0.dp))
                                else RankedSourceConfirmation(work, preview, repository, sourceReady,
                                    onConfirm = { onConfirmSource(work.key, preview.canonical.id, preview.request) },
                                    onCancel = { onCancelPreview(work.key, preview.request) })
                            }
                        }
                    }
                    if (secondary.isNotEmpty() && (primary.isNotEmpty() || state.rankedSourcesLoading)) item(key = "other-results") {
                        MotionTextButton(onClick = { otherResults = !otherResults }, enabled = active,
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                            Text(if (otherResults) "收起其他结果" else "其他搜索结果 · " + secondary.size,
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            Icon(Icons.Outlined.ExpandMore, null, Modifier.padding(start = 5.dp).size(16.dp))
                        }
                    }
                    if (!state.rankedSourcesLoading && candidates.isEmpty() && state.rankedLinkedSource == null)
                        item(key = "source-empty") {
                            Text("暂未找到阅读来源，试试作品的短书名或别名。",
                                Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant)
                        }
                    state.rankedSourcesError?.let { error ->
                        item(key = "source-failures") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) {
                                    MotionTextButton(onClick = { failures = !failures }, enabled = active,
                                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp)) {
                                        Icon(Icons.Outlined.Info, null, Modifier.size(16.dp))
                                        Text("部分来源暂不可用", Modifier.padding(start = 6.dp),
                                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                    }
                                    CompactGlassPopover(failures, backdrop, onDismiss = { failures = false }, maximumWidth = 320.dp) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("来源状态", style = MaterialTheme.typography.titleSmall)
                                            SelectionContainer { Text(error, style = MaterialTheme.typography.bodySmall,
                                                color = colors.onSurfaceVariant) }
                                        }
                                    }
                                }
                                MotionTextButton(onClick = submit, enabled = active && query.isNotBlank()) { Text("重试") }
                            }
                        }
                    }
                }
                if (platformKind == PlatformKind.DESKTOP) DesktopVerticalScrollbar(scroll,
                    Modifier.align(Alignment.CenterEnd).padding(bottom = bottom))
            }
        }
    }
}

@Composable
private fun RankedSourceConfirmation(work: SiteRankedWork, preview: dev.mangaflow.RankedSourcePreview,
    repository: MangaRepository, enabled: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(colors.onSurface.copy(alpha = .035f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("核对作品", style = MaterialTheme.typography.titleSmall)
        Text("榜单 · " + work.manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Text("名称或作者尚未匹配，请核对封面与版本。",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        RankedSourceRow(preview.canonical, repository, matched = false, linked = false,
            opening = false, enabled = false, fullTitle = true, onClick = {})
        FlowRow(Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MotionTextButton(onClick = onCancel, enabled = enabled) { Text("重新选择") }
            MotionButton(onClick = onConfirm, enabled = enabled, shape = RoundedCornerShape(12.dp)) { Text("确认并打开") }
        }
    }
}

@Composable
private fun RankedSourceRow(manga: Manga, repository: MangaRepository, matched: Boolean,
    linked: Boolean, opening: Boolean, enabled: Boolean, fullTitle: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction,
            indication = null, onClickLabel = "核对来源资料并打开", onClick = onClick)
        .motionPress(interaction, enabled, pressedScale = .988f).heightIn(min = 76.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        MangaCover(manga, repository, Modifier.width(42.dp).height(60.dp).mangaCoverCard(8.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = if (fullTitle) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
            val source = manga.sourceName.ifBlank { sourceDisplayName(manga.sourceId) }
            Text(listOf(source, manga.authors.take(2).joinToString(" / ")).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            if (linked) Text("已关联", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            if (opening) MotionBusyIndicator(Modifier.size(17.dp), strokeWidth = 1.6.dp)
            else Icon(if (linked) Icons.Outlined.Link else if (matched) Icons.Outlined.Check else Icons.Outlined.ChevronRight,
                if (linked) "已关联的来源" else if (matched) "书名与作者信息匹配" else null,
                Modifier.size(18.dp), tint = colors.onSurfaceVariant)
        }
    }
}

private fun rankingCount(value: Long): String = when {
    value >= 100_000_000 -> (value / 10_000_000 / 10.0).toString().removeSuffix(".0") + "亿"
    value >= 10_000 -> (value / 1_000 / 10.0).toString().removeSuffix(".0") + "万"
    else -> value.toString()
}
