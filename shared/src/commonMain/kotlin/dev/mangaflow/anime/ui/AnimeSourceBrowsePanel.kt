package dev.mangaflow.anime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.anime.sources.AnimeSourceBrowseState
import dev.mangaflow.anime.sources.lanerc.LanercCategory
import dev.mangaflow.anime.sources.lanerc.LanercFilter
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.*
import dev.mangaflow.ui.motion.*

/** Only disclosure and scroll live here; the application owner retains requests and native identities. */
@Composable
internal fun AnimeSourceBrowsePane(controller: AnimeController, backdrop: HazeState,
    scrolls: AnimeDiscoveryScrolls, columns: Int?, active: Boolean,
    onChooseTitle: (AnimeSourceTitle, Boolean) -> Unit, contentBottomPadding: Dp = 24.dp) {
    val owner = controller.sourceBrowse
    val state by owner.state.collectAsStateWithLifecycle()
    val source = state.sources.firstOrNull { it.id == state.sourceId && it.revision == state.sourceRevision }
    var sourcePresent by remember { mutableStateOf(false) }
    var filtersPresent by remember { mutableStateOf(false) }
    var filtersVisible by remember { mutableStateOf(false) }
    val category = state.categories.firstOrNull { it.key == state.categoryKey }
    val appliedFilters = category?.filters.orEmpty().count { filter ->
        filter.options.any { it.value == state.filters[filter.key] }
    }
    val defaultSection = if (source?.capabilities?.contains("latest") == true) "更新" else "推荐"
    LaunchedEffect(active) { if (!active) filtersVisible = false }
    LaunchedEffect(state.sourceId, state.sourceRevision, state.categoryKey) { filtersVisible = false }
    val contentActive = active && !sourcePresent && !filtersPresent
    Column(Modifier.fillMaxSize().motionInputGate(active)) {
        if (state.selectedTitle == null && state.sources.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AnimeDirectoryPicker(controller, state.sources, source, state.recentSourceIds, backdrop,
                    active = active && !filtersPresent, modifier = Modifier.weight(1f),
                    onPresenceChange = { sourcePresent = it })
                AnimeCatalogueGridActions(LocalMangaWindow.current.width, columns, backdrop, contentActive,
                    controller::setDiscoveryColumns)
                if (category?.filters?.isNotEmpty() == true) {
                    val filterLabel = if (appliedFilters > 0) "目录筛选，已选 $appliedFilters 项" else "目录筛选"
                    MotionIconButton(onClick = { filtersPresent = true; filtersVisible = true }, enabled = contentActive,
                        modifier = Modifier.size(44.dp), tooltip = filterLabel) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                            val ink = if (appliedFilters > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            Icon(Icons.Outlined.FilterList, filterLabel, Modifier.size(18.dp), tint = ink)
                            if (appliedFilters > 0) Text(appliedFilters.toString(), Modifier.clearAndSetSemantics { },
                                style = MaterialTheme.typography.labelSmall, color = ink)
                        }
                    }
                }
                if (source != null) MotionIconButton(onClick = owner::refresh,
                    enabled = contentActive && !state.loading && !state.homeLoading && !state.categoriesLoading,
                    modifier = Modifier.size(44.dp), tooltip = "刷新当前片源") {
                    Icon(Icons.Outlined.Refresh, "刷新当前片源", Modifier.size(20.dp))
                }
            }
        }
        if (state.selectedTitle == null && state.categories.isNotEmpty()) {
            val sections = buildList<Pair<String?, String>> {
                if (source?.capabilities?.any { it == "homeSections" || it == "latest" } == true) add(null to defaultSection)
                addAll(state.categories.map { it.key to it.title })
            }
            AnimeChoiceStrip(sections.map { it.second }, sections.indexOfFirst { it.first == state.categoryKey },
                onSelect = { owner.selectCategory(sections[it].first) }, active = contentActive,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
        if (source == null && state.sources.isEmpty()) AnimeMessage("暂无可浏览的片源", "可以在侧边栏管理片源。")
        else if (source == null) AnimeDiscoveryLoading("正在打开片源目录")
        else MotionTabContent(source to state,
            contentKey = { (selected, snapshot) -> selected.id + ":" + selected.revision + ":" + (snapshot.selectedTitle?.id ?: "catalog") },
            order = { (_, snapshot) -> if (snapshot.selectedTitle == null) 0 else 1 }, modifier = Modifier.weight(1f).motionInputGate(contentActive)) { (shownSource, snapshot) ->
            val title = snapshot.selectedTitle
            val openTitle: (AnimeSourceTitle) -> Unit = { candidate ->
                if (!shownSource.videoSource && candidate.sourceId in setOf("anime-douban", "anime-lanerc-douban"))
                    onChooseTitle(candidate, false)
                else if (shownSource.videoSource && "related" !in shownSource.capabilities) onChooseTitle(candidate, true)
                else owner.openTitle(candidate)
            }
            if (title != null) AnimeSourcePreview(title, shownSource.name, shownSource.videoSource, snapshot,
                columns, scrolls, contentBottomPadding, onChooseTitle, onOpen = openTitle, onRetry = owner::retryRelated)
            else AnimeSourceCatalogue(snapshot, columns, scrolls, contentBottomPadding, onOpen = openTitle,
                onRetry = owner::retryPage, onMore = owner::loadMore)
        }
    }
    AnimeSourceFilters(filtersVisible, category, state.filters, backdrop,
        onDismiss = { filtersVisible = false }, onClosed = { filtersPresent = false }, onSelect = owner::setFilter)
}

@Composable
private fun AnimeSourceCatalogue(state: AnimeSourceBrowseState, preferred: Int?, scrolls: AnimeDiscoveryScrolls,
    contentBottomPadding: Dp, onOpen: (AnimeSourceTitle) -> Unit, onRetry: () -> Unit, onMore: () -> Unit) {
    val source = state.sources.firstOrNull { it.id == state.sourceId && it.revision == state.sourceRevision }
    val latest = source?.capabilities?.contains("latest") == true
    val filterKey = state.filters.entries.sortedBy { it.key }.joinToString("&") { it.key + "=" + it.value }
    val scroll = scrolls.grid("source:${state.sourceId}:${state.sourceRevision}:${state.categoryKey}:$filterKey")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = animeDiscoveryColumns(maxWidth, preferred)
        val inset = animeDiscoveryInset(maxWidth, preferred, columns)
        LazyVerticalGrid(GridCells.Fixed(columns), state = scroll, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = inset, end = inset, top = 6.dp, bottom = contentBottomPadding),
            horizontalArrangement = Arrangement.spacedBy(animeDiscoveryGap(columns)), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (state.categoryError != null) item(key = "category-error", span = { GridItemSpan(maxLineSpan) }) {
                AnimeDiscoveryInlineStatus(state.categoryError, onRetry)
            }
            if (latest || state.categoryKey != null) {
                if (state.error != null) item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                    AnimeDiscoveryInlineStatus(if (state.items.isEmpty()) "暂时无法读取片源目录" else "更新失败，保留上次结果",
                        onRetry, detail = state.error)
                }
                if (state.items.isEmpty() && state.error == null && state.categoryError == null) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    if (state.loading) AnimeDiscoveryLoading("正在载入番剧")
                    else AnimeMessage("没有符合当前条件的番剧",
                        if (state.filters.isEmpty()) null else "可调整当前分类的筛选条件。")
                }
                else items(state.items, key = { it.sourceId + ":" + it.id }) { title ->
                    AnimeSourceDiscoveryCard(title, list = columns == 1, onOpen = { onOpen(title) })
                }
                if (state.loading && state.items.isNotEmpty()) item(key = "refresh", span = { GridItemSpan(maxLineSpan) }) {
                    AnimeQuietLabel("更新中")
                }
                if (state.moreError != null) item(key = "more-error", span = { GridItemSpan(maxLineSpan) }) {
                    AnimeDiscoveryInlineStatus("暂时无法取得更多番剧", onRetry, detail = state.moreError)
                }
                if (state.hasMore) item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                    MotionTextButton(onClick = onMore, enabled = !state.loading && !state.loadingMore,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (state.loadingMore) "正在加载" else "加载更多") }
                }
            } else {
                if (state.homeError != null) item(key = "home-error", span = { GridItemSpan(maxLineSpan) }) {
                    AnimeDiscoveryInlineStatus("暂时无法读取片源目录", onRetry, detail = state.homeError)
                }
                if (state.home.isEmpty() && state.homeError == null && state.categoryError == null) item(
                    key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    if (state.homeLoading || state.categoriesLoading) AnimeDiscoveryLoading("正在载入番剧")
                    else AnimeMessage(if (state.categories.isNotEmpty()) "选择分类继续浏览" else "此片源暂无推荐")
                }
                state.home.forEachIndexed { index, section ->
                    item(key = "heading:$index", span = { GridItemSpan(maxLineSpan) }) {
                        AnimeDiscoverySectionHeading(section.title, "", refreshing = state.homeLoading)
                    }
                    items(section.items.distinctBy { it.sourceId to it.id }, key = { "section:$index:${it.sourceId}:${it.id}" }) { title ->
                        AnimeSourceDiscoveryCard(title, list = columns == 1, onOpen = { onOpen(title) })
                    }
                }
            }
        }
        DesktopVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun AnimeSourcePreview(title: AnimeSourceTitle, sourceName: String, videoSource: Boolean,
    state: AnimeSourceBrowseState, preferred: Int?, scrolls: AnimeDiscoveryScrolls,
    contentBottomPadding: Dp, onChooseTitle: (AnimeSourceTitle, Boolean) -> Unit,
    onOpen: (AnimeSourceTitle) -> Unit, onRetry: () -> Unit) {
    val related = state.related.isNotEmpty() || state.relatedLoading || state.relatedError != null
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val wide = platformKind == PlatformKind.DESKTOP && related && maxWidth >= 858.dp * scale && maxHeight >= 318.dp * scale
        val sideWidth = (maxWidth * .29f).coerceIn(224.dp, 332.dp)
        val catalogueWidth = if (wide) maxWidth - sideWidth - 28.dp else maxWidth
        val columns = animeDiscoveryColumns(catalogueWidth, preferred)
        val scrollKey = "preview:${title.sourceId}:${title.sourceRevision}:${title.id}"
        val grid = scrolls.grid(if (wide) "$scrollKey:related" else scrollKey)
        val choose = { onChooseTitle(title, videoSource) }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(if (wide) 12.dp else 0.dp)) {
            if (wide) {
                val sideScroll = rememberScrollState()
                Box(Modifier.width(sideWidth).fillMaxHeight().padding(start = 12.dp, top = 8.dp, bottom = 12.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .032f), RoundedCornerShape(18.dp))) {
                    Column(Modifier.fillMaxWidth().verticalScroll(sideScroll).padding(16.dp)) {
                        AnimeSourcePreviewHeading(title, sourceName, videoSource, true, choose)
                    }
                    DesktopVerticalScrollbar(sideScroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                LazyVerticalGrid(GridCells.Fixed(columns), state = grid, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = if (wide) 2.dp else 12.dp, end = 12.dp,
                        top = 8.dp, bottom = contentBottomPadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!wide) item(key = "subject", span = { GridItemSpan(maxLineSpan) }) {
                        AnimeSourcePreviewHeading(title, sourceName, videoSource, false, choose)
                    }
                    if (related) {
                        item(key = "related-heading", span = { GridItemSpan(maxLineSpan) }) {
                            AnimeDiscoverySectionHeading("相关番剧", "", refreshing = state.relatedLoading)
                        }
                        if (state.relatedError != null) item(key = "related-error", span = { GridItemSpan(maxLineSpan) }) {
                            AnimeDiscoveryInlineStatus("暂时无法读取相关番剧", onRetry, detail = state.relatedError)
                        }
                        items(state.related, key = { "related:${it.sourceId}:${it.id}" }) { item ->
                            AnimeSourceDiscoveryCard(item, list = columns == 1, onOpen = { onOpen(item) })
                        }
                    }
                }
                DesktopVerticalScrollbar(grid, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun AnimeSourcePreviewHeading(title: AnimeSourceTitle, sourceName: String, video: Boolean,
    side: Boolean, onChoose: () -> Unit) {
    val identity: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AnimeSourceMark(title.sourceId, sourceName, size = 18.dp)
            AnimeQuietLabel(sourceName)
        }
        Text(title.title, Modifier.semantics { heading() }, style = animeWorkTitleStyle(large = true),
            maxLines = if (side) 5 else 3, overflow = TextOverflow.Ellipsis)
        listOfNotNull(title.year?.toString(), title.updateText.takeIf(String::isNotBlank),
            title.ratingText.takeIf(String::isNotBlank)).joinToString(" · ").takeIf(String::isNotBlank)
            ?.let { AnimeQuietLabel(it) }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (side) {
            AnimePoster(title.coverUrl, Modifier.size(112.dp, 160.dp), title.sourceId)
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) { identity() }
        } else Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AnimePoster(title.coverUrl, Modifier.size(88.dp, 132.dp), title.sourceId)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) { identity() }
        }
        MotionButton(onClick = onChoose, modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (video) "查看详情" else "匹配对应番剧")
            Spacer(Modifier.width(6.dp))
            Icon(if (video) Icons.AutoMirrored.Outlined.ArrowForward else Icons.Outlined.Search, null, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun AnimeSourceFilters(visible: Boolean, category: LanercCategory?, selected: Map<String, String>,
    backdrop: HazeState, onDismiss: () -> Unit, onClosed: () -> Unit, onSelect: (String, String?) -> Unit) {
    var retained by remember { mutableStateOf<LanercCategory?>(null) }
    if (category != null && visible) SideEffect { retained = category }
    MotionDialog(visible && category != null, onDismissRequest = onDismiss, maximumWidth = animePanelWidth(774.dp, 480.dp), properties = animePanelDialogProperties,
        onClosed = { retained = null; onClosed() }) {
        val shown = category ?: retained
        AnimePanelSurface(backdrop) {
            var currentKey by remember(shown?.key) { mutableStateOf<String?>(null) }
            val filters = shown?.filters.orEmpty()
            val current = filters.firstOrNull { it.key == currentKey } ?: filters.firstOrNull()
            Column(Modifier.fillMaxWidth().heightIn(max = LocalMangaWindow.current.panelHeight)) {
                AnimeSectionTitle(shown?.title ?: "来源筛选", modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 4.dp),
                    action = {
                        MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭筛选") {
                            Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp))
                        }
                    })
                AnimePaneLayout(Modifier.weight(1f, fill = false), minimumWidth = 604.dp, sidebar = {
                    AnimeScrollableList(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(filters, key = { it.key }) { filter ->
                            val value = selected[filter.key]
                            val selectedLabel = filter.options.firstOrNull { it.value == value }?.label
                            AnimePaneChoice(filter.title, current?.key == filter.key, { currentKey = filter.key },
                                detail = selectedLabel?.takeIf(String::isNotBlank))
                        }
                    }
                }) { wide ->
                    key(if (wide) current?.key else null) {
                        AnimeScrollableList(Modifier.fillMaxWidth().weight(1f, fill = false),
                            contentPadding = PaddingValues(start = if (wide) 4.dp else 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(if (wide) listOfNotNull(current) else filters, key = { it.key }) { filter ->
                                AnimeBrowseFilter(filter, selected[filter.key], wide, onSelect = { onSelect(filter.key, it) })
                            }
                        }
                    }
                }
                MotionButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(horizontal = 16.dp, vertical = 8.dp)
                    .widthIn(max = 280.dp).fillMaxWidth().heightIn(min = 44.dp)) { Text("返回目录") }
            }
        }
    }
}

@Composable
private fun AnimeBrowseFilter(filter: LanercFilter, value: String?, wide: Boolean, onSelect: (String?) -> Unit) {
    val options = buildList<Pair<String?, String>> {
        add(null to "默认")
        addAll(filter.options.distinctBy { it.value }.map { it.value to it.label.ifBlank { "不限" } })
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (wide) {
            Text(filter.title, Modifier.padding(start = 6.dp, bottom = 6.dp).semantics { heading() },
                style = MaterialTheme.typography.titleMedium)
            options.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pair.forEach { option ->
                        Box(Modifier.weight(1f)) { AnimeSelection(option.second, selected = option.first == value,
                            onClick = { onSelect(option.first) }) }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else {
            AnimeQuietLabel(filter.title)
            AnimeChoiceStrip(options.map { it.second }, options.indexOfFirst { it.first == value },
                onSelect = { onSelect(options[it].first) })
        }
        if (value != null && options.none { it.first == value }) AnimeQuietLabel("原筛选已不可用，请重新选择")
    }
}
