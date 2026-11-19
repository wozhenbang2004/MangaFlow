package dev.mangaflow.ui

import coil3.compose.AsyncImage
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.Manga
import dev.mangaflow.data.DiscoveryMode
import dev.mangaflow.data.RankPeriod
import dev.mangaflow.data.SourceFailureKind
import dev.mangaflow.data.SourceHealth
import dev.mangaflow.data.UnifiedMangaRepository
import dev.mangaflow.data.plugins.SourcePluginStore
import dev.mangaflow.data.plugins.StarterSourceInstaller
import dev.mangaflow.data.sources.*
import dev.mangaflow.json.JSONArray
import dev.mangaflow.ui.motion.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** One contextual toolbar; native directories and filters keep their existing owners. */
@Composable
internal fun SourceCapabilityBrowseControls(
    controller: ScopedBrowseController,
    repository: UnifiedMangaRepository,
    onSources: () -> Unit,
    onInspect: (String) -> Unit,
    starters: StarterSourceInstaller? = null,
    backdrop: HazeState,
    active: Boolean = true,
    onSearchTag: (String) -> Unit,
    onPresenceChange: (Boolean) -> Unit = {},
    inSearch: Boolean = false,
    onOpenSourceAccount: ((String) -> Unit)? = null,
) {
    val current by controller.controls.collectAsStateWithLifecycle()
    val browse by controller.state.collectAsStateWithLifecycle()
    val preparation = starters?.state?.collectAsStateWithLifecycle()?.value
    val revision = current.sources.firstOrNull { it.id == current.selectedSource }?.revision
    val searching = inSearch || browse.isSearch
    val owner = listOf(current.selectedSource, revision, current.feedId, searching, browse.searchTarget)
    var feedPicker by remember(owner) { mutableStateOf(false) }
    var pendingFeed by remember(owner) { mutableStateOf<String?>(null) }
    var pendingOption by remember(owner) { mutableStateOf<Pair<Int, String>?>(null) }
    var failuresOpen by remember(owner) { mutableStateOf(false) }
    var pendingInspect by remember(owner) { mutableStateOf<Pair<String, Boolean>?>(null) }
    var filterIndex by remember(owner) { mutableStateOf<Int?>(null) }
    var filtersVisible by remember(owner) { mutableStateOf(false) }
    var filtersPresent by remember(owner) { mutableStateOf(false) }
    var pendingFilter by remember(owner) { mutableStateOf<Int?>(null) }
    var hotTagsOpen by remember(owner) { mutableStateOf(false) }
    var pendingTag by remember(owner) { mutableStateOf<String?>(null) }
    val failed = if (inSearch && !browse.isSearch) emptyList()
        else browse.searchSources.values.filter { it.error != null }
    val selectedFeed = current.feeds.firstOrNull { it.id == current.feedId }
    // Author/tag requests have their own source protocol and do not consume these options.
    val filters = when {
        !searching -> selectedFeed?.filters.orEmpty()
        browse.isSearch && browse.searchTarget == dev.mangaflow.data.SearchTarget.TITLE -> current.searchFilters
        else -> emptyList()
    }
    val visibleFilters = filters.withIndex().filterNot {
        !searching && browse.mode == DiscoveryMode.RANKING && it.value.id == "period"
    }
    val modifiedFilters = visibleFilters.count { (index, filter) ->
        filter.normalize(current.options.getOrNull(index)) != filter.normalize()
    }
    val sourceName = current.sources.firstOrNull { it.id == current.selectedSource }?.name ?: "全部来源"
    var hotTags by remember(current.selectedSource, revision) { mutableStateOf<List<String>>(emptyList()) }
    var hotTagsLoaded by remember(current.selectedSource, revision) { mutableStateOf(false) }
    var hotTagsLoading by remember(current.selectedSource, revision) { mutableStateOf(false) }
    var hotTagsError by remember(current.selectedSource, revision) { mutableStateOf<String?>(null) }
    var hotTagsRevision by remember(current.selectedSource, revision) { mutableIntStateOf(0) }
    val present = feedPicker || filtersVisible || filtersPresent || pendingFilter != null ||
        filterIndex != null || failuresOpen || hotTagsOpen
    val reportPresence by rememberUpdatedState(onPresenceChange)
    SideEffect { reportPresence(present) }
    DisposableEffect(Unit) { onDispose { reportPresence(false) } }
    fun currentOwner(): Boolean = active && controller.sourceId == current.selectedSource &&
        controller.controls.value.feedId == current.feedId &&
        controller.controls.value.sources.firstOrNull { it.id == current.selectedSource }?.revision == revision &&
        (inSearch || controller.state.value.isSearch) == searching && controller.state.value.searchTarget == browse.searchTarget
    LaunchedEffect(active) {
        if (!active) {
            pendingFeed = null; pendingOption = null; pendingFilter = null
            pendingInspect = null; pendingTag = null
            feedPicker = false; filtersVisible = false; filterIndex = null
            failuresOpen = false; hotTagsOpen = false
        }
    }
    LaunchedEffect(filtersVisible, filtersPresent, pendingFilter) {
        if (!filtersVisible && !filtersPresent) {
            val chosen = pendingFilter
            pendingFilter = null
            if (chosen != null && currentOwner()) filterIndex = chosen
        }
    }
    LaunchedEffect(hotTagsOpen, current.selectedSource, revision, hotTagsRevision, active) {
        if (!active || !hotTagsOpen || hotTagsLoaded || current.selectedSource != "jmcomic") return@LaunchedEffect
        hotTagsLoading = true; hotTagsError = null
        try {
            hotTags = repository.sourceHotTags(current.selectedSource).filter(String::isNotBlank).distinct()
            hotTagsLoaded = true
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { hotTagsError = "热门标签暂未读取" }
        finally { hotTagsLoading = false }
    }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MotionTextButton(onClick = onSources, enabled = active && !present,
                modifier = Modifier.heightIn(min = 44.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                if (current.selectedSource == "*") Icon(Icons.Outlined.Layers, null, Modifier.size(17.dp))
                else ChapterSourceLogo(current.selectedSource, sourceName, Modifier.size(17.dp))
                Text(sourceName, Modifier.widthIn(max = 122.dp).padding(start = 7.dp),
                    style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Outlined.ExpandMore, "选择来源", Modifier.padding(start = 3.dp).size(15.dp))
            }
            if (!searching && current.feeds.size > 1) {
                MotionTextButton(onClick = { feedPicker = true }, enabled = active && !present && !current.loading,
                    modifier = Modifier.heightIn(min = 44.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                    Text(selectedFeed?.title ?: "分类", Modifier.widthIn(max = 106.dp),
                        style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Outlined.ExpandMore, "分类与栏目", Modifier.padding(start = 3.dp).size(15.dp))
                }
            }
            if (!searching && current.selectedSource == "jmcomic") {
                MotionIconButton(onClick = { hotTagsOpen = true }, enabled = active && !present,
                    modifier = Modifier.size(44.dp), tooltip = "热门标签") {
                    Icon(Icons.Outlined.Tag, "热门标签", Modifier.size(18.dp))
                }
            }
            if (visibleFilters.isNotEmpty()) Box {
                MotionIconButton(onClick = {
                    if (visibleFilters.size == 1) filterIndex = visibleFilters.first().index
                    else filtersVisible = !filtersVisible
                }, enabled = active && !current.loading && (!present || filtersVisible),
                    modifier = Modifier.size(44.dp).semantics {
                        stateDescription = if (modifiedFilters == 0) "默认筛选" else "$modifiedFilters 项筛选已调整"
                    }, tooltip = "筛选") {
                    Icon(Icons.Outlined.Tune, "筛选", Modifier.size(18.dp),
                        tint = if (modifiedFilters > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (modifiedFilters > 0) Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(5.dp)
                    .background(MaterialTheme.colorScheme.onSurface, CircleShape))
                CompactGlassPopover(visible = filtersVisible, backdrop = backdrop,
                    onDismiss = { filtersVisible = false }, onPresenceChange = { filtersPresent = it }) {
                    Text("筛选", Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleSmall)
                    visibleFilters.forEach { (index, filter) ->
                        MotionTextButton(onClick = { pendingFilter = index; filtersVisible = false },
                            enabled = active && pendingFilter == null,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(filter.title, style = MaterialTheme.typography.labelLarge)
                                Text(sourceFilterSummary(filter, current.options.getOrNull(index)),
                                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        val status = when {
            failed.size == 1 -> failed.single().let { lane ->
                lane.sourceName + if (sourceAccountRequired(lane.error)) "需要登录" else "暂不可用"
            }
            failed.size > 1 -> "${failed.size} 个来源暂不可用"
            current.error != null -> "筛选暂未读取"
            preparation?.error != null -> "来源准备未完成"
            preparation?.running == true && current.sources.isEmpty() -> "正在准备来源"
            else -> null
        }
        if (status != null) MotionTextButton(onClick = { failuresOpen = true }, enabled = active && !present,
            modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
            Icon(Icons.Outlined.Info, null, Modifier.size(15.dp))
            Text(status, Modifier.weight(1f).padding(start = 7.dp), style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("查看", style = MaterialTheme.typography.labelMedium)
        }
        if (!searching && browse.mode == DiscoveryMode.RANKING) {
            MotionTabs(selected = browse.period.ordinal, labels = RankPeriod.entries.map { it.label },
                onSelect = { if (active && !present) controller.selectDiscovery(DiscoveryMode.RANKING, period = RankPeriod.entries[it]) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).motionInputGate(active && !present),
                compact = true, controlHeight = 44.dp, fontSize = 12.sp,
                tooltips = RankPeriod.entries.map { period ->
                    if (period == RankPeriod.TOTAL && current.selectedSource == "*") "总榜 · 仅展示实际提供总榜的来源" else period.label
                })
        }
    }
    if (failuresOpen) MotionBottomSheet(backdrop = backdrop, onDismissRequest = {
        failuresOpen = false
        val chosen = pendingInspect; pendingInspect = null
        if (chosen != null && currentOwner()) {
            val (id, needsLogin) = chosen
            if (needsLogin && onOpenSourceAccount != null) onOpenSourceAccount(id) else onInspect(id)
        }
    }) { dismiss ->
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = (LocalMangaWindow.current.height - 96.dp).coerceAtLeast(120.dp)),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
            item(key = "source-status-header") {
                SourceBrowseSheetHeading("来源状态", dismiss)
            }
            if (starters != null) item(key = "source-preparation") { StarterSourcePreparation(starters) }
            current.error?.let { message -> item(key = "source-options-error") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MotionTextButton(onClick = controller::refresh, enabled = active && !current.loading) { Text("重试") }
                }
            } }
            if (current.loading) item(key = "source-options-loading") {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MotionBusyIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                    Text("正在读取来源选项", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(failed, key = { it.sourceId }) { lane ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val needsLogin = sourceAccountRequired(lane.error)
                    MotionTextButton(onClick = { pendingInspect = lane.sourceId to needsLogin; dismiss() },
                        enabled = active && pendingInspect == null,
                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 0.dp, vertical = 10.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(lane.sourceName, style = MaterialTheme.typography.labelLarge)
                            Text(lane.error.orEmpty(), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(if (needsLogin) "登录" else "设置", Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.labelMedium)
                    }
                    if (!needsLogin) MotionIconButton(onClick = { controller.retrySource(lane.sourceId) },
                        enabled = active && !browse.loading && !browse.loadingMore && !browse.refreshing,
                        modifier = Modifier.size(44.dp), tooltip = "重试${lane.sourceName}") {
                        Icon(Icons.Outlined.Refresh, "重试${lane.sourceName}", Modifier.size(18.dp))
                    }
                }
            }
            if (failed.isEmpty() && current.error == null && !current.loading && preparation?.running != true && preparation?.error == null)
                item(key = "source-status-empty") {
                    Text(if (browse.loading || browse.loadingMore || browse.refreshing ||
                        browse.searchSources.values.any { it.loading }) "正在读取来源…" else "没有待处理的来源问题",
                        Modifier.padding(vertical = 16.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
        }
    }
    if (feedPicker) MotionBottomSheet(backdrop = backdrop, onDismissRequest = {
        feedPicker = false
        val chosen = pendingFeed; pendingFeed = null
        if (chosen != null && currentOwner()) controller.selectFeed(chosen)
    }) { dismiss ->
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = (LocalMangaWindow.current.height - 96.dp).coerceAtLeast(120.dp)),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
            item(key = "feed-picker-header") { SourceBrowseSheetHeading("分类与栏目", dismiss, sourceName) }
            current.feeds.groupBy { it.kind }.forEach { (kind, entries) ->
                item(key = "feed-kind:" + kind.name) {
                    Text(when (kind) { SourceFeedKind.LATEST -> "更新"; SourceFeedKind.EXPLORE -> "发现"
                        SourceFeedKind.RANKING -> "排行"; SourceFeedKind.CATEGORY -> "分类" },
                        Modifier.padding(top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(entries, key = { "feed:" + it.id }) { feed ->
                    MotionTextButton(onClick = { pendingFeed = feed.id; dismiss() }, enabled = active && pendingFeed == null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { selected = feed.id == current.feedId },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)) {
                        Text(feed.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (feed.id == current.feedId) FontWeight.Medium else FontWeight.Normal)
                        if (feed.id == current.feedId) Icon(Icons.Outlined.Check, null, Modifier.size(17.dp))
                    }
                }
            }
        }
    }
    filterIndex?.let { index -> filters.getOrNull(index)?.let { filter ->
        var selectedIds by remember(owner, filter.id) {
            mutableStateOf(filter.selectedIds(current.options.getOrNull(index)))
        }
        MotionBottomSheet(backdrop = backdrop, onDismissRequest = {
            filterIndex = null
            val chosen = pendingOption; pendingOption = null
            if (chosen != null && currentOwner()) controller.selectOption(chosen.first, chosen.second)
        }) { dismiss ->
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = (LocalMangaWindow.current.height - 96.dp).coerceAtLeast(120.dp)),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
                item(key = "filter-header") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(filter.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (filter.multiple) MotionTextButton(onClick = {
                            pendingOption = index to JSONArray(filter.choices.filter { it.id in selectedIds }.map { it.id }).toString()
                            dismiss()
                        }, enabled = active && pendingOption == null) { Text("完成", fontSize = 13.sp) }
                        MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp), tooltip = "关闭筛选") {
                            Icon(Icons.Outlined.Close, "关闭筛选", Modifier.size(18.dp))
                        }
                    }
                }
                items(filter.choices, key = { it.id }) { choice ->
                    val chosen = choice.id in selectedIds
                    MotionTextButton(onClick = {
                        if (filter.multiple) selectedIds = if (choice.id in selectedIds) selectedIds - choice.id else selectedIds + choice.id
                        else { pendingOption = index to choice.id; dismiss() }
                    }, enabled = active && pendingOption == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .semantics { selected = chosen }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)) {
                        Text(choice.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        if (chosen) Icon(Icons.Outlined.Check, null, Modifier.size(17.dp))
                    }
                }
            }
        }
    } }
    if (hotTagsOpen) MotionBottomSheet(backdrop = backdrop, onDismissRequest = {
        hotTagsOpen = false
        val chosen = pendingTag; pendingTag = null
        if (chosen != null && currentOwner()) onSearchTag(chosen)
    }) { dismiss ->
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = (LocalMangaWindow.current.height - 96.dp).coerceAtLeast(120.dp)),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
            item(key = "hot-tags-header") { SourceBrowseSheetHeading("热门标签", dismiss, sourceName) }
            if (hotTagsLoading) item(key = "hot-tags-loading") {
                Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                    MotionBusyIndicator(Modifier.size(18.dp), strokeWidth = 1.5.dp)
                }
            }
            if (hotTagsError != null || hotTagsLoaded && hotTags.isEmpty()) item(key = "hot-tags-state") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(hotTagsError ?: "暂无热门标签", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MotionTextButton(onClick = { hotTagsLoaded = false; hotTagsRevision++ }, enabled = active && !hotTagsLoading) {
                        Text("重试", fontSize = 12.sp)
                    }
                }
            }
            items(hotTags, key = { it }) { tag ->
                MotionTextButton(onClick = { pendingTag = tag; dismiss() }, enabled = active && pendingTag == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)) {
                    Icon(Icons.Outlined.Tag, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(tag, Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp))
                }
            }
        }
    }
}

private fun sourceAccountRequired(message: String?): Boolean = message?.contains("需要登录") == true

@Composable
private fun SourceBrowseSheetHeading(title: String, onDismiss: () -> Unit, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭$title") {
            Icon(Icons.Outlined.Close, "关闭$title", Modifier.size(18.dp))
        }
    }
}

private fun sourceFilterSummary(filter: SourceFilter, value: String?): String {
    val selected = filter.selectedIds(value)
    return filter.choices.filter { it.id in selected }.joinToString(" · ") { it.title }.ifBlank { "未选择" }
}

@Composable
internal fun StarterSourcePreparation(installer: StarterSourceInstaller) {
    val state by installer.state.collectAsStateWithLifecycle()
    val delayed = rememberDelayedBusy(state.running)
    val motion = LocalMotionEnabled.current
    AnimatedVisibility(delayed || state.error != null,
        enter = fadeIn(tween(if (motion) MangaMotion.Quick else 0)) + expandVertically(tween(if (motion) MangaMotion.Content else 0)),
        exit = fadeOut(tween(if (motion) MangaMotion.Quick else 0)) + shrinkVertically(tween(if (motion) MangaMotion.Content else 0))) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.running) MotionBusyIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp)
        Text(state.error ?: (state.phase + (state.progress?.let { " · $it%" } ?: " · ${state.completed}/${state.total}")),
            Modifier.weight(1f), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.running) MotionTextButton(onClick = installer::cancel) { Text("暂停", fontSize = 11.sp) }
        else if (state.error != null) MotionTextButton(onClick = { installer.start(retry = true) }) { Text("重试", fontSize = 11.sp) }
    }
    }
}

@Composable
internal fun SourceCapabilityComments(
    repository: UnifiedMangaRepository, manga: Manga, modifier: Modifier,
    bottomPadding: Dp, openRevision: Int, active: Boolean,
) {
    val scope = rememberCoroutineScope()
    var rows by remember(manga.id) { mutableStateOf<List<SourceComment>>(emptyList()) }
    var page by remember(manga.id) { mutableIntStateOf(0) }
    var more by remember(manga.id) { mutableStateOf(false) }
    var loading by remember(manga.id) { mutableStateOf(false) }
    var error by remember(manga.id) { mutableStateOf<String?>(null) }
    var failedPage by remember(manga.id) { mutableIntStateOf(0) }
    var job by remember(manga.id) { mutableStateOf<Job?>(null) }
    var generation by remember(manga.id) { mutableLongStateOf(0L) }
    fun load(next: Int) {
        if (loading) return
        loading = true; error = null
        generation++; val expected = generation
        job = scope.launch {
            try {
                val result = repository.sourceComments(manga.sourceId, manga.id, next)
                if (expected != generation) return@launch
                rows = (if (next == 0) result.items else rows + result.items).distinctBy { it.id }
                page = next; more = result.hasMore
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (expected == generation) { failedPage = next; error = repository.sourceHealth.value[manga.sourceId]?.message ?: "评论读取失败" } }
            finally { if (expected == generation) loading = false }
        }
    }
    LaunchedEffect(manga.id, openRevision, active) {
        generation++; job?.cancel(); loading = false
        if (active) load(0)
    }
    LazyColumn(modifier, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomPadding + 16.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)) {
        if (rows.isEmpty() && loading) item { QuietLoading("正在读取评论…") }
        if (rows.isEmpty() && !loading && error == null) item { Text("暂无评论", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(rows, key = { it.id }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (row.avatar.isNotBlank()) AsyncImage(model = row.avatar, contentDescription = null,
                    modifier = Modifier.size(28.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(listOf(row.author, manga.sourceName, row.time).filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(row.content, style = MaterialTheme.typography.bodyMedium)
                if (row.likes > 0) Text("♡ ${row.likes}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        error?.let { message -> item {
            Text(message, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            MotionTextButton(onClick = { load(failedPage) }, enabled = !loading) { Text("重试") }
        } }
        if (more && error == null) item {
            MotionTextButton(onClick = { load(page + 1) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                Text(if (loading) "正在读取…" else "更多评论", fontSize = 12.sp)
            }
        }
    }
}

@Composable
internal fun SourceCapabilityDetailsSheet(
    source: BodySourceInfo, repository: UnifiedMangaRepository, plugins: SourcePluginStore?,
    backdrop: HazeState?, manga: Manga? = null, onDismiss: () -> Unit, onSourceChanged: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val health by repository.sourceHealth.collectAsStateWithLifecycle()
    var capabilities by remember(source.id, source.revision) { mutableStateOf<SourceCapabilities?>(null) }
    var settings by remember(source.id, source.revision) { mutableStateOf<List<SourceSetting>>(emptyList()) }
    var account by remember(source.id, source.revision) { mutableStateOf<SourceAccountState?>(null) }
    var folders by remember(source.id) { mutableStateOf<List<SourceFavoriteFolder>>(emptyList()) }
    var accountRevision by remember(source.id) { mutableIntStateOf(0) }
    var accountRead by remember(source.id) { mutableIntStateOf(0) }
    var accountReadFailed by remember(source.id, source.revision) { mutableStateOf(false) }
    var settingsReadFailed by remember(source.id, source.revision) { mutableStateOf(false) }
    var busy by remember(source.id) { mutableStateOf(false) }
    var message by remember(source.id) { mutableStateOf<String?>(null) }
    var updateAvailable by remember(source.id) { mutableStateOf(false) }
    fun operate(operation: suspend () -> Unit) {
        if (busy) return
        busy = true; message = null
        scope.launch {
            try { operation() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { message = "操作未完成，已保留原来的来源与本机记录。" }
            finally { busy = false }
        }
    }
    val lastHealth = health[source.id]
    val accountFailure = lastHealth?.takeIf { it.failure == SourceFailureKind.LOGIN }?.operationId
    LaunchedEffect(source.id, source.revision, accountRead, accountFailure) {
        accountReadFailed = false
        try {
            val offered = repository.sourceCapabilities(source.id)
            capabilities = offered
            if (offered.supports(SourceFeature.ACCOUNT) || offered.browserAccount != null)
                account = repository.sourceAccountState(source.id)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { accountReadFailed = true }
    }
    LaunchedEffect(source.id, source.revision, accountRead) {
        try {
            settings = repository.sourceSettings(source.id)
            settingsReadFailed = false
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { settingsReadFailed = true }
    }
    LaunchedEffect(source.id, source.revision, manga?.id, account, accountRevision, capabilities) {
        folders = emptyList()
        if (manga != null && account?.signedIn == true && capabilities?.supports(SourceFeature.FAVORITES) == true) {
            try {
                val loaded = repository.sourceFavoriteFolders(source.id)
                currentCoroutineContext().ensureActive()
                folders = loaded
            }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { message = "站点收藏夹未能读取，请重新打开来源设置重试。" }
        }
    }
    MotionBottomSheet(backdrop = backdrop, onDismissRequest = onDismiss) { dismiss ->
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalMangaWindow.current.height - 96.dp).coerceAtLeast(120.dp))
            .imePadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChapterSourceLogo(source.id, source.name, Modifier.size(22.dp))
                Text(source.name, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
                MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp), tooltip = "关闭") {
                    Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp))
                }
            }
            val offered = capabilities
            val accountReceipt = account
            if (accountReadFailed) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("账号能力或状态暂未读取", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                MotionTextButton(onClick = { accountRead++ }, enabled = !busy) { Text("重试", fontSize = 12.sp) }
            } else if (offered == null) SourceAccountLoading("正在读取来源信息…")
            else if (offered.supports(SourceFeature.ACCOUNT) || offered.browserAccount != null) {
                if (accountReceipt == null) SourceAccountLoading("正在读取账号状态…")
                else SourceAccountControls(source, repository, offered, accountReceipt, enabled = !busy,
                    onBusy = { busy = it }, onAccountChanged = {
                        account = it; folders = emptyList(); accountRevision++; onSourceChanged()
                    }, initiallyShowLogin = accountFailure != null || offered.loginRequired)
                HorizontalDivider()
            } else if (offered.websiteLoginDeclared) {
                Text("此来源的网页登录暂未接入。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SourceHealthSummary(lastHealth)
            SourceNetworkSettingsControl(source.id, enabled = !busy)
            SourceConnectionChecks(repository, listOf(source), active = !busy)
            if (source.id.startsWith("js_") && plugins != null) Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MotionTextButton(onClick = { operate { updateAvailable = plugins.checkUpdate(source.id) != null
                        message = if (updateAvailable) "有可用更新" else "已是当前仓库版本" } }, enabled = !busy) { Text("检查更新", fontSize = 12.sp) }
                    if (updateAvailable) MotionTextButton(onClick = { operate { plugins.update(source.id); updateAvailable = false; message = "来源已更新"; onSourceChanged() } }, enabled = !busy) { Text("更新", fontSize = 12.sp) }
                }
            }
            if (manga != null && accountReceipt?.signedIn == true && offered?.supports(SourceFeature.FAVORITES) == true) {
                Column { Text("站点收藏", style = MaterialTheme.typography.titleSmall)
                    Text("与本机书架分别保存", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                folders.forEach { folder -> key("favorite:" + folder.id) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(folder.title, Modifier.weight(1f), fontSize = 12.sp)
                        MotionTextButton(onClick = { operate { repository.sourceSetFavorite(source.id, manga.id, folder.id, true); message = "已加入站点收藏" } }, enabled = !busy) { Text("加入", fontSize = 11.sp) }
                        MotionTextButton(onClick = { operate { repository.sourceSetFavorite(source.id, manga.id, folder.id, false); message = "已移出站点收藏，本机书架保留" } }, enabled = !busy) { Text("移出", fontSize = 11.sp) }
                    }
                } }
            }
            if (settings.isNotEmpty()) Column { Text("来源设置", style = MaterialTheme.typography.titleSmall) }
            if (settingsReadFailed) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("来源选项暂未读取", Modifier.weight(1f), fontSize = 11.sp)
                MotionTextButton(onClick = { accountRead++ }, enabled = !busy) { Text("重试", fontSize = 12.sp) }
            }
            settings.forEach { setting -> key("setting:" + setting.key) {
                var value by remember(source.id, setting.key, setting.value) { mutableStateOf(setting.value) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(setting.title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (setting.type == "switch") {
                        val interaction = remember { MutableInteractionSource() }
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
                            .toggleable(value == "true", enabled = !busy, role = Role.Switch,
                                interactionSource = interaction, indication = null,
                                onValueChange = { value = it.toString() }),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(if (value == "true") "已开启" else "已关闭", fontSize = 12.sp,
                                modifier = Modifier.weight(1f))
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                MotionSwitchThumb(value == "true", interaction)
                            }
                        }
                    } else if (setting.choices.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState())) {
                        setting.choices.forEach { choice -> MotionTextButton(onClick = { value = choice.id }, enabled = !busy) {
                            Text(choice.title, fontSize = 12.sp, color = if (value == choice.id) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        } }
                    } else BasicTextField(value, { if (it.length <= 8192) value = it },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .045f)).padding(12.dp),
                        singleLine = true, textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                        visualTransformation = if (setting.type.lowercase() in setOf("password", "secret", "token", "apikey") || Regex("(?i)token|password|secret|api.?key|cookie|authorization|auth.?key|bearer").containsMatchIn(setting.key)) PasswordVisualTransformation() else VisualTransformation.None)
                    if (value != setting.value) MotionTextButton(onClick = { operate {
                        repository.saveSourceSetting(source.id, setting.key, value); settings = repository.sourceSettings(source.id); message = "设置已保存"; onSourceChanged() } }, enabled = !busy) { Text("保存", fontSize = 12.sp) }
                }
            } }
            message?.let { Column { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
    }
}

@Composable
private fun SourceHealthSummary(health: SourceHealth?) {
    val label = when {
        health == null -> "尚无连接记录"
        health.checking -> health.stage.label + " · 正在读取"
        else -> health.message.ifBlank { health.stage.label + " · 已读取" }
    }
    Text(label, fontSize = 11.sp, color = if (health?.failure != null) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant)
}
