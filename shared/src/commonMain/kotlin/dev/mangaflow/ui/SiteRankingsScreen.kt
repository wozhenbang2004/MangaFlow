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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
    var awards by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(controller, active, awards) {
        controller.setVisible(active && !awards)
        onDispose { controller.setVisible(false) }
    }
    var siteMenu by remember { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }
    var sitePresent by remember { mutableStateOf(false) }
    var filterPresent by remember { mutableStateOf(false) }
    var menuSelection by remember { mutableStateOf(state.selection) }
    var menuSite by remember { mutableStateOf(state.selection.site) }
    var menuFetchedAt by remember { mutableLongStateOf(0L) }
    var menuError by remember { mutableStateOf<String?>(null) }
    var infoRequested by remember { mutableStateOf(false) }
    var infoVisible by remember { mutableStateOf(false) }
    var infoPresent by remember { mutableStateOf(false) }
    var filtersRequested by remember { mutableStateOf(false) }
    var filtersVisible by remember { mutableStateOf(false) }
    var filtersPresent by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (!active) {
            siteMenu = false; filterMenu = false
            infoRequested = false; infoVisible = false; infoPresent = false
            filtersRequested = false; filtersVisible = false; filtersPresent = false
        }
    }
    val colors = MaterialTheme.colorScheme
    val controlColors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface)
    val positions = rememberSaveableStateHolder()
    val ready = active && !state.restoringSelection
    val menuWidth = 208.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.4f)
    val sectionTabs: @Composable () -> Unit = {
        val tabsActive = active && !siteMenu && !filterMenu && !sitePresent && !filterPresent &&
            !infoPresent && !filtersPresent
        MotionTabs(if (awards) 1 else 0, listOf("各站榜单", "年度获奖"), onSelect = {
            if (tabsActive) awards = it == 1
        }, treatment = MotionTabTreatment.Underline, controlHeight = 44.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).motionInputGate(tabsActive))
    }
    if (awards) {
        positions.SaveableStateProvider("award-catalogue") {
            MangaAwardsScreen(controller.repository, backdrop, navigation, active,
                onBack = { awards = false }, onOpen = onOpen, sectionTabs = sectionTabs)
        }
        return
    }
    RankingPageFrame(Modifier.fillMaxSize().background(colors.background.copy(alpha = .97f))
        .semantics { paneTitle = "漫画榜单" }, header = {
        Column {
            PageTitleHeader("漫画榜单", backdrop, navigation,
                modifier = Modifier.windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Top))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                summary = {
                    Text("漫画榜单", Modifier.padding(start = 4.dp),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }, actions = {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        if (state.refreshing) MotionBusyIndicator(Modifier.size(17.dp))
                        else MotionIconButton(onClick = controller::refresh,
                            enabled = ready && !state.loading && !state.loadingMore && !sitePresent && !filterPresent &&
                                !filtersPresent && !infoPresent,
                            modifier = Modifier.size(44.dp), tooltip = "刷新当前榜单") {
                            Icon(Icons.Outlined.Refresh, "刷新当前榜单", Modifier.size(19.dp))
                        }
                    }
                })
            sectionTabs()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    MotionTextButton(onClick = {
                        if (!siteMenu) menuSite = state.selection.site
                        siteMenu = !siteMenu; filterMenu = false
                    }, enabled = ready && !filterPresent && !filtersPresent && !infoPresent,
                        modifier = Modifier.heightIn(min = 44.dp).semantics {
                            contentDescription = "选择榜单站点"
                            stateDescription = "当前站点 ${state.selection.site.title}"
                        }, contentPadding = PaddingValues(horizontal = 7.dp), colors = controlColors) {
                        if (!state.restoringSelection) ReviewSourceMark(state.selection.site.id, size = 16.dp)
                        Text(if (state.restoringSelection) "站点" else state.selection.site.title,
                            Modifier.padding(horizontal = 5.dp).widthIn(max = 84.dp),
                            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Outlined.ExpandMore, null, Modifier.size(14.dp))
                    }
                    CompactGlassPopover(siteMenu && ready, backdrop, onDismiss = { siteMenu = false },
                        onPresenceChange = { sitePresent = it }, alignToStart = true, maximumWidth = menuWidth) {
                        Column(Modifier.selectableGroup()) {
                            RankingSite.entries.forEach { site ->
                                RankingMenuChoice(site.title, site == menuSite, ready && siteMenu,
                                    sourceId = site.id) {
                                    siteMenu = false; controller.selectSite(site)
                                }
                            }
                        }
                    }
                }
                val kinds = state.selection.availableKinds
                val keywordMatch = state.selection.site == RankingSite.BANGUMI &&
                    state.selection.filters.query.isNotBlank()
                if (keywordMatch) Text("搜索匹配", Modifier.weight(1f).padding(horizontal = 6.dp),
                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                else if (kinds.size > 1) BoxWithConstraints(Modifier.weight(1f)) {
                    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                    val minimum = 48.dp * scale * kinds.size
                    val tabsWidth = maxWidth.coerceAtLeast(minimum)
                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                        MotionTabs(kinds.indexOf(state.selection.kind).coerceAtLeast(0), kinds.map { it.label },
                            onSelect = { if (ready) controller.selectKind(kinds[it]) },
                            treatment = MotionTabTreatment.Underline, fontSize = 12.sp, controlHeight = 44.dp,
                            modifier = Modifier.width(tabsWidth).motionInputGate(ready))
                    }
                } else Spacer(Modifier.weight(1f))
                Box {
                    MotionTextButton(onClick = {
                        if (!filterMenu) {
                            menuSelection = state.selection
                            menuFetchedAt = state.fetchedAt
                            menuError = state.preferenceError
                        }
                        filterMenu = !filterMenu; siteMenu = false
                    }, enabled = ready && !sitePresent && !filtersPresent && !infoPresent,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        colors = controlColors, modifier = Modifier.heightIn(min = 44.dp)) {
                        Icon(Icons.Outlined.FilterList, null, Modifier.size(16.dp))
                        Column(Modifier.padding(start = 5.dp).widthIn(max = 88.dp)) {
                            Text("筛选", style = MaterialTheme.typography.labelMedium)
                            if (!state.restoringSelection) Text(state.selection.scopeLabel,
                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    CompactGlassPopover(filterMenu && ready, backdrop, onDismiss = { filterMenu = false },
                        onPresenceChange = { present ->
                            filterPresent = present
                            if (!present && infoRequested) {
                                infoRequested = false
                                infoVisible = active
                            }
                            if (!present && filtersRequested) {
                                filtersRequested = false
                                filtersVisible = active
                            }
                        }, maximumWidth = menuWidth) {
                        Column(Modifier.selectableGroup()) {
                            menuSelection.availableScopes.forEach { value ->
                                RankingMenuChoice(if (menuSelection.usesBangumiSearch) menuSelection.scopeLabel else value.label,
                                    value == menuSelection.scope, ready && filterMenu) {
                                    filterMenu = false; controller.selectScope(value)
                                }
                            }
                        }
                        menuError?.let { Text(it, Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            fontSize = 11.sp, lineHeight = 16.sp, color = colors.error) }
                        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
                            .height(1.dp).background(colors.onSurface.copy(alpha = .07f)))
                        CompactMenuAction(Icons.Outlined.FilterList, "组合筛选", enabled = ready && filterMenu) {
                            filtersRequested = true; filtersPresent = true; filterMenu = false
                        }
                        CompactMenuAction(Icons.Outlined.Info, "榜单说明", enabled = ready && filterMenu) {
                            infoRequested = true; infoPresent = true; filterMenu = false
                        }
                    }
                }
            }
            if (state.selection.activeFilterCount > 0) Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                MotionTextButton(onClick = {
                    menuSelection = state.selection
                    filtersPresent = true; filtersVisible = true
                }, enabled = ready && !sitePresent && !filterPresent && !infoPresent,
                    modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Text(state.selection.filterSummary, Modifier.fillMaxWidth(), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant)
                }
                MotionIconButton(onClick = {
                    controller.applySelection(state.selection.copy(filters = SiteRankingFilters()))
                }, enabled = ready && !filtersPresent, modifier = Modifier.size(44.dp), tooltip = "清除筛选") {
                    Icon(Icons.Outlined.Close, "清除筛选", Modifier.size(16.dp))
                }
            }
        }
    }) {
        MotionTabContent(selected = state, contentKey = { it.selection.key },
            order = { it.selection.site.ordinal * 100 + it.selection.kind.ordinal * 10 + it.selection.scope.ordinal },
            soft = true, modifier = Modifier.fillMaxSize()) { pane ->
            positions.SaveableStateProvider(pane.selection.key) {
                RankingPane(pane, backdrop, 0.dp, active = ready && pane.selection == state.selection &&
                    !siteMenu && !filterMenu && !sitePresent && !filterPresent && !infoPresent && !filtersPresent,
                    onRetry = controller::retry, onMore = controller::more, onOpen = onOpen, onInspect = onInspect)
            }
        }
    }
    SiteRankingFiltersDialog(visible = filtersVisible && active, selection = menuSelection,
        active = active, backdrop = backdrop, onApply = {
            controller.applySelection(it); filtersVisible = false
        }, onDismiss = { filtersVisible = false }, onClosed = {
            if (!filtersVisible) filtersPresent = false
        })
    // A help action waits for the actual menu exit; it never stacks two active surfaces.
    MotionDialog(visible = infoVisible && active, onDismissRequest = { infoVisible = false },
        maximumWidth = 320.dp, onClosed = { if (!infoVisible) infoPresent = false }) {
        LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), cornerRadius = 22.dp,
            role = GlassSurfaceRole.PANEL, softCard = true) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(menuSelection.site.title + " · " + menuSelection.kind.label,
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                        Text(menuSelection.scopeLabel, style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant)
                    }
                    MotionIconButton(onClick = { infoVisible = false }, modifier = Modifier.size(44.dp),
                        tooltip = "关闭榜单说明") {
                        Icon(Icons.Outlined.Close, "关闭榜单说明", Modifier.size(18.dp))
                    }
                }
                Text(menuSelection.explanation, Modifier.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (menuFetchedAt > 0) Text("本机获取 · " + formatInstantMillis(menuFetchedAt, "MM-dd HH:mm"),
                    Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant)
            }
        }
    }
}

/** Adapt CompactMenuAction's press/material row, with actual single-choice semantics. */
@Composable
internal fun RankingMenuChoice(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    sourceId: String? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .background(colors.onSurface.copy(alpha = if (selected) .07f else 0f))
        .selectable(selected, enabled = enabled, role = Role.RadioButton,
            interactionSource = interaction, indication = null, onClick = onClick)
        .motionPress(interaction, enabled, pressedScale = .98f)
        .heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (sourceId != null) {
            ReviewSourceMark(sourceId, size = 18.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(label, Modifier.weight(1f), fontSize = 13.sp, lineHeight = 19.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.onSurface)
        Box(Modifier.padding(start = 8.dp).size(18.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(17.dp), tint = colors.onSurface)
        }
    }
}

/** Adapt AccountScreen's same-pass header/content measurement, without a deferred inset update. */
@Composable
internal fun RankingPageFrame(
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
    val grid = rememberLazyGridState()
    val desktop = platformKind == PlatformKind.DESKTOP
    val empty = state.entries.isEmpty()
    val firstError = state.error?.takeIf { empty || (state.failedPage ?: 0) == 0 }
    val fetching = empty && (state.loading || state.restoringSelection)
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
            if (empty || state.refreshing || firstError != null) item(key = "rank-status", span = { GridItemSpan(maxLineSpan) }) {
                val site = state.selection.site.title
                val observed = state.fetchedAt.takeIf { it > 0 }?.let { formatInstantMillis(it, "MM-dd HH:mm") }
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 9.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (fetching) MotionBusyIndicator(Modifier.size(16.dp))
                        Text(when {
                            state.restoringSelection -> "正在恢复榜单选择"
                            fetching -> "正在读取$site 榜单"
                            firstError != null && empty -> "$site 榜单暂未载入"
                            firstError != null -> observed?.let { "更新未完成 · 显示 $it 的结果" } ?: "更新未完成 · 显示上次结果"
                            state.refreshing -> "正在更新 · 显示已载入结果"
                            state.loadingMore -> "正在读取下一页"
                            else -> "当前条件没有匹配作品"
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (firstError != null) {
                        Text(firstError, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MotionTextButton(onClick = onRetry, enabled = active) { Text("重试") }
                    } else if (empty && !fetching && !state.loadingMore) {
                        if (state.hasMore) MotionTextButton(onClick = onMore, enabled = active) { Text("继续读取") }
                        else Text("可调整筛选或切换站点。", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(state.entries, key = { it.work.key }, contentType = { "native-ranking" }) { entry ->
                SiteRankingRow(entry, state.selection.kind, entry.work.key in state.linkedIds,
                    state.selection.key, onOpen, onInspect)
            }
            if (!empty && (state.loadingMore || state.error != null && firstError == null)) item(
                key = "rank-footer", span = { GridItemSpan(maxLineSpan) }
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (state.loadingMore) MotionBusyIndicator(Modifier.padding(16.dp).size(20.dp))
                    else if (state.error != null) {
                        Text(state.error, Modifier.padding(vertical = 10.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MotionTextButton(onClick = onRetry, enabled = active) { Text("重试") }
                    }
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
                    SiteRankingKind.NEWEST -> w.publishedDate
                }
                if (value != null) Text(value, modifier = Modifier.alignByBaseline(), style = mangaMetricValueStyle(16.sp, 22.sp))
                if (kind == SiteRankingKind.SCORE && w.ratingCount != null)
                    Text(rankingCount(w.ratingCount.toLong()) + "人评分", Modifier.alignByBaseline(),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
                else if (kind != SiteRankingKind.SCORE && value != null)
                    Text(when (kind) {
                        SiteRankingKind.POPULARITY -> "书单"
                        SiteRankingKind.NEWEST -> "首发"
                        else -> kind.label
                    }, Modifier.alignByBaseline(),
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
internal fun CatalogueCover(manga: Manga, modifier: Modifier) {
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
    var expandedGroup by rememberSaveable(work.key) { mutableStateOf<String?>(null) }
    LaunchedEffect(active) { if (!active) { info = false; failures = false } }
    val candidates = remember(state.rankedSources, state.rankedLinkedSource) {
        val linked = state.rankedLinkedSource?.let(::federatedMangaIdentity)
        state.rankedSources.filterNot { federatedMangaIdentity(it.manga) == linked }
    }
    val primary = remember(candidates) { candidates.filter { it.relevant } }
    val secondary = remember(candidates, primary) { candidates.filterNot { it in primary } }
    val rows = if (otherResults || !state.rankedSourcesLoading && primary.isEmpty()) candidates else primary
    val groups = remember(state.rankedLinkedSource, rows) {
        val matches = listOfNotNull(state.rankedLinkedSource) + rows.map { it.manga }
        val order = matches.mapIndexed { index, manga -> federatedMangaIdentity(manga) to index }.toMap()
        groupFederatedSearch(matches).sortedBy { group -> group.matches.minOf { order.getValue(federatedMangaIdentity(it)) } }
    }
    val verified = remember(state.rankedSources) {
        state.rankedSources.filter { it.verified }.mapTo(hashSetOf()) { federatedMangaIdentity(it.manga) }
    }
    val sourceReady = active && state.rankedOpeningSourceId == null
    val submit = {
        if (active && query.isNotBlank()) {
            focus.clearFocus(); otherResults = false; failures = false; expandedGroup = null
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
            val previewIdentity = state.rankedSourcePreview?.selected?.let(::federatedMangaIdentity)
            LaunchedEffect(previewIdentity, active) {
                if (active && previewIdentity != null && !scroll.isScrollInProgress) {
                    val group = groups.firstOrNull { group -> group.matches.any { federatedMangaIdentity(it) == previewIdentity } }
                    val visible = scroll.layoutInfo.visibleItemsInfo.firstOrNull { it.key == group?.let(::rankedSourceGroupKey) }
                    if (visible != null) scroll.animateScrollToItem(visible.index)
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
                    items(groups, key = ::rankedSourceGroupKey, contentType = { "reading-source" }) { group ->
                        val groupKey = rankedSourceGroupKey(group)
                        val identities = remember(group.matches) { group.matches.mapTo(hashSetOf(), ::federatedMangaIdentity) }
                        val linked = state.rankedLinkedSource?.let(::federatedMangaIdentity)?.takeIf(identities::contains)
                        val preview = state.rankedSourcePreview?.takeIf { federatedMangaIdentity(it.selected) in identities }
                        val opening = group.matches.any { it.id == state.rankedOpeningSourceId }
                        val showChoices = group.matches.size > 1
                        val expanded = showChoices && (expandedGroup == groupKey || preview != null || opening)
                        Column {
                            RankedSourceRow(if (showChoices) group.manga else preview?.canonical ?: group.manga, repository,
                                matched = preview == null && !showChoices && identities.any(verified::contains),
                                linked = linked != null, opening = opening, enabled = sourceReady && preview == null,
                                showAction = showChoices || preview == null, choices = group.matches.takeIf { showChoices }.orEmpty(),
                                expanded = expanded, onClick = {
                                    if (showChoices) expandedGroup = groupKey.takeUnless { expanded }
                                    else onUseSource(work.key, group.manga)
                                })
                            if (expanded) {
                                val counts = remember(group.matches) { group.matches.groupingBy { it.sourceId }.eachCount() }
                                group.matches.forEach { manga ->
                                    val identity = federatedMangaIdentity(manga)
                                    key(identity) {
                                        val selected = preview?.takeIf { federatedMangaIdentity(it.selected) == identity }
                                        RankedSourceChoice(manga, linked = identity == linked, matched = identity in verified,
                                            selected = selected != null, repeated = (counts[manga.sourceId] ?: 0) > 1,
                                            opening = manga.id == state.rankedOpeningSourceId,
                                            enabled = sourceReady && selected == null,
                                            onClick = { onUseSource(work.key, manga) })
                                        if (selected != null) RankedSourceConfirmation(work, selected, backdrop,
                                            enabled = sourceReady, onConfirm = {
                                                onConfirmSource(work.key, selected.canonical.id, selected.request)
                                            }, onCancel = { onCancelPreview(work.key, selected.request) })
                                    }
                                }
                            } else if (!showChoices && preview != null) {
                                RankedSourceConfirmation(work, preview, backdrop, enabled = sourceReady,
                                    onConfirm = { onConfirmSource(work.key, preview.canonical.id, preview.request) },
                                    onCancel = { onCancelPreview(work.key, preview.request) })
                            }
                        }
                    }
                    if (secondary.isNotEmpty() && (primary.isNotEmpty() || state.rankedSourcesLoading)) item(key = "other-results") {
                        MotionTextButton(onClick = { otherResults = !otherResults }, enabled = active,
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                            Text(if (otherResults) "收起其他结果" else "其他搜索结果",
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
    backdrop: HazeState, enabled: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var evidence by remember(work.key, preview.request) { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) evidence = false }
    // The candidate above already owns the cover/title/author. Only the decision is disclosed here.
    Row(Modifier.fillMaxWidth().padding(start = 54.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("核对作品", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Box {
            MotionIconButton(onClick = { evidence = !evidence }, enabled = enabled,
                modifier = Modifier.size(44.dp), tooltip = "查看两站作品资料") {
                Icon(Icons.Outlined.Info, "查看两站作品资料", Modifier.size(16.dp), tint = colors.onSurfaceVariant)
            }
            CompactGlassPopover(evidence && enabled, backdrop, onDismiss = { evidence = false }, maximumWidth = 288.dp) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RankedConfirmationEvidence(work.site.title, work.manga)
                    RankedConfirmationEvidence(preview.canonical.sourceName.ifBlank {
                        sourceDisplayName(preview.canonical.sourceId)
                    }, preview.canonical)
                }
            }
        }
        MotionIconButton(onClick = onCancel, enabled = enabled, modifier = Modifier.size(44.dp),
            tooltip = "取消本次关联") {
            Icon(Icons.Outlined.Close, "取消本次关联", Modifier.size(17.dp), tint = colors.onSurfaceVariant)
        }
        MotionTextButton(onClick = onConfirm, enabled = enabled,
            modifier = Modifier.heightIn(min = 44.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
            Icon(Icons.Outlined.Check, null, Modifier.size(16.dp))
            Text("确认打开", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun RankedConfirmationEvidence(source: String, manga: Manga) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(source, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        Text((listOf(manga.title) + manga.alternativeTitles).filter(String::isNotBlank).distinct().take(6).joinToString(" / "),
            fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE), style = MaterialTheme.typography.bodySmall)
        Text(manga.authors.take(4).joinToString(" / ").ifBlank { "作者资料未提供" },
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

private fun rankedSourceGroupKey(group: FederatedSearchGroup): String =
    normalizedBodySourceTitle(group.manga.title).takeIf(String::isNotBlank)?.let { "work:$it" }
        ?: federatedMangaIdentity(group.manga).let { (source, id) -> "native:${source.length}:$source:$id" }

@Composable
private fun RankedSourceRow(manga: Manga, repository: MangaRepository, matched: Boolean,
    linked: Boolean, opening: Boolean, enabled: Boolean, showAction: Boolean = true,
    choices: List<Manga> = emptyList(), expanded: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hasChoices = choices.isNotEmpty()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction,
            indication = null, onClickLabel = when {
                !hasChoices -> "核对来源资料并打开"
                expanded -> "收起阅读来源"
                else -> "展开阅读来源"
            }, onClick = onClick)
        .motionPress(interaction, enabled, pressedScale = .988f).heightIn(min = 76.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        MangaCover(manga, repository, Modifier.width(42.dp).height(60.dp).mangaCoverCard(8.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (hasChoices) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val count = choices.distinctBy { federatedMangaIdentity(it).first }.size
                SearchSourceMarks(choices, Modifier.width((count * 18).coerceAtMost(82).dp))
                if (linked) Icon(Icons.Outlined.Link, "组内有已关联来源", Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                Text(if (count == 1) "${choices.size} 个条目" else "$count 个来源",
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
            } else {
                val source = manga.sourceName.ifBlank { sourceDisplayName(manga.sourceId) }
                Text(listOf(source, manga.authors.take(2).joinToString(" / ")).filter(String::isNotBlank).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        if (showAction) Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            if (opening) MotionBusyIndicator(Modifier.size(17.dp), strokeWidth = 1.6.dp)
            else if (hasChoices) Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            else RankedSourceStatus(linked, matched)
        }
    }
}

@Composable
private fun RankedSourceChoice(manga: Manga, linked: Boolean, matched: Boolean, selected: Boolean,
    repeated: Boolean, opening: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val name = manga.sourceName.ifBlank { sourceDisplayName(manga.sourceId) }
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().padding(start = 54.dp).clip(RoundedCornerShape(10.dp))
        .background(if (selected) colors.surfaceContainerHigh else androidx.compose.ui.graphics.Color.Transparent)
        .semantics {
            if (selected) stateDescription = "等待确认"
        }
        .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null,
            onClickLabel = "核对${name}的${manga.title}", onClick = onClick)
        .motionPress(interaction, enabled, pressedScale = .99f)
        .heightIn(min = 44.dp).padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChapterSourceLogo(manga.sourceId, name, Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (repeated) {
                val (_, id) = federatedMangaIdentity(manga)
                val details = listOf(manga.authors.take(2).joinToString(" / "), manga.latestChapter, id)
                    .filter(String::isNotBlank).joinToString(" · ")
                Text(details, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (opening) MotionBusyIndicator(Modifier.size(16.dp))
        else if (!selected) RankedSourceStatus(linked, matched)
    }
}

@Composable
private fun RankedSourceStatus(linked: Boolean, matched: Boolean) {
    Icon(if (linked) Icons.Outlined.Link else if (matched) Icons.Outlined.Check else Icons.Outlined.ChevronRight,
        if (linked) "已关联的来源" else if (matched) "作品信息匹配" else null,
        Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun rankingCount(value: Long): String = when {
    value >= 100_000_000 -> (value / 10_000_000 / 10.0).toString().removeSuffix(".0") + "亿"
    value >= 10_000 -> (value / 1_000 / 10.0).toString().removeSuffix(".0") + "万"
    else -> value.toString()
}
