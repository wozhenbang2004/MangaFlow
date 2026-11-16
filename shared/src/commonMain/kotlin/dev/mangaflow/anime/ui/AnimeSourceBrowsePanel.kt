package dev.mangaflow.anime.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.anime.sources.lanerc.*
import dev.mangaflow.json.JSONObject
import dev.mangaflow.ui.*
import dev.mangaflow.ui.motion.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Source-owned cards stay outside the canonical library until the user identifies a subject. */
@Composable
internal fun AnimeSourceBrowsePanel(
    visible: Boolean,
    state: AnimeScreenState,
    controller: AnimeController,
    backdrop: HazeState,
    onDismiss: () -> Unit,
    onManageSources: () -> Unit,
    onChooseTitle: (AnimeSourceTitle, Boolean) -> Unit,
) {
    val available = state.sourceExtensions.filter {
        it.installed && it.enabled && it.kind in setOf(LanercSourceKind.BROWSE, LanercSourceKind.VIDEO)
    }
    var selectedSourceId by remember { mutableStateOf<String?>(null) }
    val source = available.firstOrNull { it.id == selectedSourceId }
        ?: available.firstOrNull { it.kind == LanercSourceKind.BROWSE } ?: available.firstOrNull()
    var sourceMenu by remember { mutableStateOf(false) }
    var categoryKey by remember(source?.id, source?.revision) { mutableStateOf<String?>(null) }
    var filters by remember(source?.id, source?.revision) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var selectedTitle by remember(source?.id, source?.revision) { mutableStateOf<AnimeSourceTitle?>(null) }
    var home by remember(source?.id, source?.revision) { mutableStateOf<List<LanercBrowseSection>>(emptyList()) }
    var categories by remember(source?.id, source?.revision) { mutableStateOf<List<LanercCategory>>(emptyList()) }
    var homeLoading by remember(source?.id, source?.revision) { mutableStateOf(false) }
    var categoriesLoading by remember(source?.id, source?.revision) { mutableStateOf(false) }
    var homeError by remember(source?.id, source?.revision) { mutableStateOf<String?>(null) }
    var categoryError by remember(source?.id, source?.revision) { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var requestedPage by remember(source?.id, source?.revision) { mutableIntStateOf(1) }
    var page by remember(source?.id, source?.revision) { mutableIntStateOf(0) }
    var rows by remember(source?.id, source?.revision) { mutableStateOf<List<AnimeSourceTitle>>(emptyList()) }
    var loading by remember(source?.id, source?.revision) { mutableStateOf(false) }
    var pageError by remember(source?.id, source?.revision) { mutableStateOf<String?>(null) }
    var more by remember(source?.id, source?.revision) { mutableStateOf(false) }
    var pageRetry by remember { mutableIntStateOf(0) }
    var related by remember(selectedTitle?.sourceId, selectedTitle?.id) { mutableStateOf<List<AnimeSourceTitle>>(emptyList()) }
    var relatedLoading by remember(selectedTitle?.sourceId, selectedTitle?.id) { mutableStateOf(false) }
    var relatedError by remember(selectedTitle?.sourceId, selectedTitle?.id) { mutableStateOf<String?>(null) }
    var relatedRetry by remember { mutableIntStateOf(0) }
    val scroll = rememberLazyListState()
    val filtersJson = JSONObject().apply { filters.forEach { (key, value) -> put(key, value) } }.toString()
    val category = categories.firstOrNull { it.key == categoryKey }

    LaunchedEffect(visible, source?.id, source?.revision, refresh) {
        val selected = source ?: return@LaunchedEffect
        if (!visible) return@LaunchedEffect
        homeError = null; categoryError = null
        coroutineScope {
            if ("homeSections" in selected.capabilities) launch {
                homeLoading = true
                try { home = controller.extensions.homeSections(selected.id) }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { homeError = animeBrowseError(failure) }
                finally { homeLoading = false }
            }
            if ("categories" in selected.capabilities && "searchFiltered" in selected.capabilities) launch {
                categoriesLoading = true
                try { categories = controller.extensions.categories(selected.id) }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { categoryError = animeBrowseError(failure) }
                finally { categoriesLoading = false }
            }
        }
    }
    LaunchedEffect(visible, source?.id, source?.revision, categoryKey, filtersJson, requestedPage, pageRetry) {
        val selected = source ?: return@LaunchedEffect
        val key = categoryKey ?: return@LaunchedEffect
        if (!visible) return@LaunchedEffect
        if (requestedPage == 1) { rows = emptyList(); page = 0; more = false; scroll.scrollToItem(0) }
        loading = true; pageError = null
        try {
            val result = controller.extensions.searchFiltered(selected.id, key, filtersJson, requestedPage)
                .distinctBy { it.sourceId to it.id }
            val merged = if (requestedPage == 1) result else (rows + result).distinctBy { it.sourceId to it.id }
            more = result.isNotEmpty() && (requestedPage == 1 || merged.size > rows.size)
            rows = merged; page = requestedPage
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { pageError = animeBrowseError(failure) }
        finally { loading = false }
    }
    LaunchedEffect(visible, source?.id, source?.revision, selectedTitle?.id, relatedRetry) {
        val selected = source ?: return@LaunchedEffect
        val title = selectedTitle ?: return@LaunchedEffect
        if (!visible || "related" !in selected.capabilities) return@LaunchedEffect
        relatedLoading = true; relatedError = null
        try { related = controller.extensions.related(selected.id, title.id).filter { it.id != title.id }
            .distinctBy { it.sourceId to it.id } }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { relatedError = animeBrowseError(failure) }
        finally { relatedLoading = false }
    }
    MotionDialog(visible, onDismissRequest = onDismiss, maximumWidth = 720.dp,
        onClosed = { selectedTitle = null; sourceMenu = false; requestedPage = 1 }) {
        LiquidGlassSurface(backdrop, role = GlassSurfaceRole.PANEL, cornerRadius = 24.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = LocalMangaWindow.current.panelHeight)) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (selectedTitle != null) MotionIconButton(onClick = { selectedTitle = null }, modifier = Modifier.size(44.dp),
                        tooltip = "返回来源发现") { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", Modifier.size(20.dp)) }
                    Text(selectedTitle?.title ?: "来源发现", Modifier.weight(1f).padding(start = if (selectedTitle == null) 8.dp else 0.dp),
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭来源发现") {
                        Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp))
                    }
                }
                if (source == null) {
                    AnimeMessage("选择你的发现来源", "安装并启用浏览或视频来源后，可在这里查看它的推荐与分类。",
                        action = "管理来源", onAction = onManageSources)
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            MotionTextButton(onClick = { sourceMenu = true }, modifier = Modifier.heightIn(min = 44.dp)) {
                                Text(source.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                            }
                            CompactGlassPopover(sourceMenu, backdrop, onDismiss = { sourceMenu = false }, alignToStart = true) {
                                available.forEach { item -> CompactMenuAction(
                                    if (source.id == item.id) Icons.Outlined.Check else Icons.Outlined.Tv,
                                    item.name, onClick = { selectedSourceId = item.id; sourceMenu = false }) }
                                CompactMenuAction(Icons.Outlined.Extension, "管理来源", onClick = {
                                    sourceMenu = false; onManageSources()
                                })
                            }
                        }
                        if (selectedTitle == null) MotionIconButton(onClick = {
                            refresh++; requestedPage = 1; pageRetry++
                        }, modifier = Modifier.size(44.dp), enabled = !homeLoading && !categoriesLoading && !loading, tooltip = "刷新来源内容") {
                            Icon(Icons.Outlined.Refresh, "刷新", Modifier.size(20.dp))
                        }
                    }
                    val title = selectedTitle
                    if (title != null) {
                        LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item {
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    AnimePoster(title.coverUrl, Modifier.width(86.dp).height(122.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(title.title, style = MaterialTheme.typography.titleMedium)
                                        AnimeQuietLabel(animeBrowseSummary(title))
                                        AnimeQuietLabel("来自 " + source.name)
                                    }
                                }
                            }
                            item {
                                AnimeQuietLabel("先选对应的番剧资料，再核对季度、版本与播放来源。")
                                MotionButton(onClick = { onChooseTitle(title, source.kind == LanercSourceKind.VIDEO) },
                                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 48.dp)) {
                                    Text(if (source.kind == LanercSourceKind.VIDEO) "搜索并关联番剧" else "搜索对应番剧")
                                }
                            }
                            if (related.isNotEmpty()) item { AnimeSectionTitle("来源推荐") }
                            items(related, key = { it.sourceId + ":" + it.id }) { item ->
                                AnimeBrowseTitleRow(item, onOpen = { selectedTitle = item })
                            }
                            if (relatedLoading) item { AnimeQuietLabel("正在读取相关内容") }
                            if (relatedError != null) item { AnimeMessage("相关内容未取得", relatedError,
                                action = "重试", onAction = { relatedRetry++ }) }
                        }
                    } else {
                        if (categories.isNotEmpty()) BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                            val labels = listOf("推荐") + categories.map { it.title }
                            val tabsWidth = maxWidth.coerceAtLeast((labels.size * 88).dp)
                            Box(Modifier.horizontalScroll(rememberScrollState())) {
                                MotionTabs(categories.indexOfFirst { it.key == categoryKey } + 1, labels,
                                    onSelect = {
                                        categoryKey = categories.getOrNull(it - 1)?.key
                                        filters = emptyMap(); requestedPage = 1
                                    }, modifier = Modifier.width(tabsWidth), controlHeight = 44.dp,
                                    treatment = MotionTabTreatment.Underline)
                            }
                        }
                        LazyColumn(Modifier.weight(1f, fill = false), state = scroll,
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (categoryKey == null) {
                                if (homeLoading && home.isEmpty()) item { AnimeMessage("正在读取来源发现") }
                                else if (categoriesLoading && home.isEmpty()) item { AnimeMessage("正在读取来源分类") }
                                if (homeError != null) item { AnimeMessage("发现内容未取得", homeError,
                                    action = "重试", onAction = { refresh++ }) }
                                if (categoryError != null) item { AnimeMessage("分类未取得", categoryError,
                                    action = "重试", onAction = { refresh++ }) }
                                home.forEachIndexed { sectionIndex, section ->
                                    item(key = "section:$sectionIndex") { AnimeSectionTitle(section.title) }
                                    items(section.items.distinctBy { it.sourceId to it.id },
                                        key = { "home:$sectionIndex:" + it.sourceId + ":" + it.id }) { item ->
                                        AnimeBrowseTitleRow(item, onOpen = { selectedTitle = item })
                                    }
                                }
                                if (!homeLoading && !categoriesLoading && home.isEmpty() && homeError == null) item {
                                    AnimeMessage("暂无推荐内容", if (categories.isNotEmpty()) "可以切换上方分类继续浏览。" else "这个来源尚未返回发现内容。")
                                }
                            } else {
                                category?.filters?.forEach { filter -> item(key = "filter:" + filter.key) {
                                    AnimeBrowseFilter(filter, filters[filter.key], backdrop, onSelect = { value ->
                                        filters = if (value == null) filters - filter.key else filters + (filter.key to value)
                                        requestedPage = 1
                                    })
                                } }
                                items(rows, key = { it.sourceId + ":" + it.id }) { item ->
                                    AnimeBrowseTitleRow(item, onOpen = { selectedTitle = item })
                                }
                                if (loading) item { AnimeQuietLabel("正在读取第 " + requestedPage + " 页", Modifier.padding(12.dp)) }
                                if (pageError != null) item { AnimeMessage("这页内容未取得", pageError,
                                    action = "重试这一页", onAction = { pageRetry++ }) }
                                else if (!loading && rows.isEmpty()) item { AnimeMessage("没有匹配的内容", "试试其他分类或筛选。") }
                                if (more && pageError == null) item { MotionTextButton(onClick = { requestedPage = page + 1 }, enabled = !loading,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("加载更多") } }
                            }
                        }
                    }
                }
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
                filter.options.distinctBy { it.value }.forEach { option -> CompactMenuAction(
                    if (option.value == value) Icons.Outlined.Check else Icons.Outlined.FilterList,
                    option.label.ifBlank { "不限" }, onClick = { onSelect(option.value); menu = false }) }
            }
        }
    }
}

/** Reuses the canonical row's cover geometry and shared press, without inventing a canonical id. */
@Composable
private fun AnimeBrowseTitleRow(title: AnimeSourceTitle, onOpen: () -> Unit) {
    val interaction = remember(title.sourceId, title.id) { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .motionPress(interaction, pressedScale = .99f)
        .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
        .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        AnimePoster(title.coverUrl, Modifier.width(52.dp).height(72.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            AnimeQuietLabel(animeBrowseSummary(title))
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun animeBrowseSummary(title: AnimeSourceTitle): String = listOfNotNull(title.year?.toString(),
    title.episodeCount?.takeIf { it > 0 }?.let { "$it 集" }).joinToString(" · ").ifBlank { "查看来源条目" }

private fun animeBrowseError(failure: Exception): String = if (failure is LanercCapabilityException)
    failure.message ?: "来源暂不支持这个操作。" else "来源内容未能读取，可重试或在来源管理中更新脚本。"
