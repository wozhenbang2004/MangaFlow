package dev.mangaflow.anime.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.anime.sources.AnimeSourceBrowseState
import dev.mangaflow.anime.sources.lanerc.LanercCategory
import dev.mangaflow.anime.sources.lanerc.LanercFilter
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
    var sourceMenu by remember { mutableStateOf(false) }
    var categoryMenu by remember { mutableStateOf(false) }
    var filtersVisible by remember { mutableStateOf(false) }
    val category = state.categories.firstOrNull { it.key == state.categoryKey }
    val defaultSection = if (source?.capabilities?.contains("latest") == true) "更新" else "推荐"
    LaunchedEffect(active, state.sourceId, state.sourceRevision) {
        if (!active) { sourceMenu = false; categoryMenu = false; filtersVisible = false }
    }
    LaunchedEffect(state.sourceId, state.sourceRevision, state.categoryKey) { filtersVisible = false }
    Column(Modifier.fillMaxSize().motionInputGate(active)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.weight(1f)) {
                MotionTextButton(onClick = { sourceMenu = true }, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text(source?.name ?: "选择来源", Modifier.weight(1f, fill = false),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(17.dp))
                }
                CompactGlassPopover(sourceMenu, backdrop, onDismiss = { sourceMenu = false }, alignToStart = true) {
                    state.sources.forEach { item ->
                        CompactMenuAction(if (source?.id == item.id) Icons.Outlined.Check else Icons.Outlined.Public,
                            item.name, onClick = { owner.selectSource(item.id); sourceMenu = false })
                    }
                }
            }
            if (state.selectedTitle == null && state.categories.isNotEmpty()) Box(Modifier.widthIn(max = 120.dp)) {
                MotionTextButton(onClick = { categoryMenu = true }, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text(category?.title ?: defaultSection, Modifier.weight(1f, fill = false),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
                }
                CompactGlassPopover(categoryMenu, backdrop, onDismiss = { categoryMenu = false }) {
                    if (source?.capabilities?.any { it == "homeSections" || it == "latest" } == true) CompactMenuAction(
                        if (state.categoryKey == null) Icons.Outlined.Check else Icons.Outlined.Explore, defaultSection,
                        onClick = { owner.selectCategory(null); categoryMenu = false })
                    state.categories.forEach { item ->
                        CompactMenuAction(if (state.categoryKey == item.key) Icons.Outlined.Check else Icons.Outlined.Category,
                            item.title, onClick = { owner.selectCategory(item.key); categoryMenu = false })
                    }
                }
            }
            if (state.selectedTitle == null && category?.filters?.isNotEmpty() == true) {
                MotionIconButton(onClick = { filtersVisible = true }, modifier = Modifier.size(44.dp), tooltip = "来源筛选") {
                    Icon(Icons.Outlined.FilterList, "来源筛选", Modifier.size(20.dp),
                        tint = if (state.filters.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (source != null && state.selectedTitle == null) MotionIconButton(onClick = owner::refresh,
                enabled = !state.loading && !state.homeLoading && !state.categoriesLoading,
                modifier = Modifier.size(44.dp), tooltip = "刷新当前来源") {
                Icon(Icons.Outlined.Refresh, "刷新当前来源", Modifier.size(20.dp))
            }
        }
        if (source == null) AnimeMessage("还没有可浏览的来源", "可在设置中管理动漫来源。")
        else MotionTabContent(source to state,
            contentKey = { (selected, snapshot) -> selected.id + ":" + selected.revision + ":" + (snapshot.selectedTitle?.id ?: "catalog") },
            order = { (_, snapshot) -> if (snapshot.selectedTitle == null) 0 else 1 }, modifier = Modifier.weight(1f)) { (shownSource, snapshot) ->
            val title = snapshot.selectedTitle
            if (title != null) AnimeSourcePreview(title, shownSource.name, shownSource.videoSource, snapshot,
                columns, scrolls, contentBottomPadding, onChooseTitle, onOpen = owner::openTitle, onRetry = owner::retryRelated)
            else AnimeSourceCatalogue(snapshot, columns, scrolls, contentBottomPadding, onOpen = { title ->
                if (shownSource.videoSource && "related" !in shownSource.capabilities) onChooseTitle(title, true)
                else owner.openTitle(title)
            },
                onRetry = owner::retryPage, onMore = owner::loadMore)
        }
    }
    AnimeSourceFilters(filtersVisible, category, state.filters, backdrop,
        onDismiss = { filtersVisible = false }, onSelect = owner::setFilter)
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
        LazyVerticalGrid(GridCells.Fixed(columns), state = scroll, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = contentBottomPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.categoryError != null) item(key = "category-error", span = { GridItemSpan(maxLineSpan) }) {
                AnimeDiscoveryInlineStatus(state.categoryError, onRetry)
            }
            if (latest || state.categoryKey != null) {
                if (state.error != null) item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                    AnimeDiscoveryInlineStatus(state.error, onRetry)
                }
                if (state.items.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    AnimeDiscoveryInlineStatus(if (state.loading) "正在读取来源内容" else if (state.error == null) "没有符合条件的内容" else "")
                }
                else items(state.items, key = { it.sourceId + ":" + it.id }) { title ->
                    AnimeSourceDiscoveryCard(title, list = columns == 1, onOpen = { onOpen(title) })
                }
                if (state.loading && state.items.isNotEmpty()) item(key = "refresh", span = { GridItemSpan(maxLineSpan) }) {
                    AnimeQuietLabel("更新中")
                }
                if (state.moreError != null) item(key = "more-error", span = { GridItemSpan(maxLineSpan) }) {
                    AnimeDiscoveryInlineStatus(state.moreError, onRetry)
                }
                if (state.hasMore) item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                    MotionTextButton(onClick = onMore, enabled = !state.loading && !state.loadingMore,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (state.loadingMore) "正在加载" else "加载更多") }
                }
            } else {
                if (state.homeError != null) item(key = "home-error", span = { GridItemSpan(maxLineSpan) }) {
                    AnimeDiscoveryInlineStatus(state.homeError, onRetry)
                }
                if (state.home.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    AnimeDiscoveryInlineStatus(if (state.homeLoading || state.categoriesLoading) "正在读取来源内容"
                        else if (state.homeError == null && state.categoryError == null)
                            if (state.categories.isNotEmpty()) "选择分类继续浏览" else "来源暂无推荐" else "")
                }
                state.home.forEachIndexed { index, section ->
                    item(key = "heading:$index", span = { GridItemSpan(maxLineSpan) }) {
                        AnimeDiscoverySectionHeading(section.title, source?.name.orEmpty(), refreshing = state.homeLoading)
                    }
                    items(section.items.distinctBy { it.sourceId to it.id }, key = { "section:$index:${it.sourceId}:${it.id}" }) { title ->
                        AnimeSourceDiscoveryCard(title, list = columns == 1, onOpen = { onOpen(title) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimeSourcePreview(title: AnimeSourceTitle, sourceName: String, videoSource: Boolean,
    state: AnimeSourceBrowseState, preferred: Int?, scrolls: AnimeDiscoveryScrolls,
    contentBottomPadding: Dp, onChooseTitle: (AnimeSourceTitle, Boolean) -> Unit,
    onOpen: (AnimeSourceTitle) -> Unit, onRetry: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = animeDiscoveryColumns(maxWidth, preferred)
        LazyVerticalGrid(GridCells.Fixed(columns),
            state = scrolls.grid("preview:${title.sourceId}:${title.sourceRevision}:${title.id}"), modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = contentBottomPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "subject", span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AnimePoster(title.coverUrl, Modifier.width(80.dp).height(114.dp), title.sourceId)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(title.title, style = MaterialTheme.typography.titleMedium)
                        AnimeQuietLabel(sourceName)
                        listOfNotNull(title.year?.toString(), title.updateText.takeIf(String::isNotBlank),
                            title.ratingText.takeIf(String::isNotBlank)).joinToString(" · ").takeIf(String::isNotBlank)
                            ?.let { AnimeQuietLabel(it) }
                    }
                }
            }
            item(key = "open", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MotionButton(onClick = { onChooseTitle(title, videoSource) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(if (videoSource) Icons.Outlined.PlayArrow else Icons.Outlined.Search, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (videoSource) "打开番剧" else "选择对应番剧")
                    }
                }
            }
            if (state.related.isNotEmpty() || state.relatedLoading || state.relatedError != null) {
                item(key = "related-heading", span = { GridItemSpan(maxLineSpan) }) {
                    AnimeDiscoverySectionHeading("相关内容", sourceName, refreshing = state.relatedLoading)
                }
                if (state.relatedError != null) item(key = "related-error", span = { GridItemSpan(maxLineSpan) }) {
                    AnimeDiscoveryInlineStatus(state.relatedError, onRetry)
                }
                items(state.related, key = { "related:${it.sourceId}:${it.id}" }) { related ->
                    AnimeSourceDiscoveryCard(related, list = columns == 1, onOpen = { onOpen(related) })
                }
            }
        }
    }
}

@Composable
private fun AnimeSourceFilters(visible: Boolean, category: LanercCategory?, selected: Map<String, String>,
    backdrop: HazeState, onDismiss: () -> Unit, onSelect: (String, String?) -> Unit) {
    var retained by remember { mutableStateOf<LanercCategory?>(null) }
    if (category != null && visible) SideEffect { retained = category }
    MotionDialog(visible && category != null, onDismissRequest = onDismiss, maximumWidth = 480.dp,
        onClosed = { retained = null }) {
        val shown = category ?: retained
        LiquidGlassSurface(backdrop, role = GlassSurfaceRole.PANEL, cornerRadius = 24.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = LocalMangaWindow.current.panelHeight)
                .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AnimeSectionTitle(shown?.title ?: "来源筛选", action = {
                    MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭筛选") {
                        Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp))
                    }
                })
                shown?.filters?.forEach { filter ->
                    AnimeBrowseFilter(filter, selected[filter.key], backdrop,
                        onSelect = { onSelect(filter.key, it) })
                }
                MotionButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("完成") }
            }
        }
    }
}

@Composable
private fun AnimeBrowseFilter(filter: LanercFilter, value: String?, backdrop: HazeState, onSelect: (String?) -> Unit) {
    var menu by remember(filter.key) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AnimeQuietLabel(filter.title, Modifier.weight(1f))
        Box {
            MotionTextButton(onClick = { menu = true }, modifier = Modifier.heightIn(min = 44.dp)) {
                Text(filter.options.firstOrNull { it.value == value }?.label ?: "默认")
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
            }
            CompactGlassPopover(menu, backdrop, onDismiss = { menu = false }) {
                CompactMenuAction(if (value == null) Icons.Outlined.Check else Icons.Outlined.FilterList, "来源默认",
                    onClick = { onSelect(null); menu = false })
                filter.options.distinctBy { it.value }.forEach { option ->
                    CompactMenuAction(if (value == option.value) Icons.Outlined.Check else Icons.Outlined.FilterList,
                        option.label.ifBlank { "不限" }, onClick = { onSelect(option.value); menu = false })
                }
            }
        }
    }
}
