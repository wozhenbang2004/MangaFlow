package dev.mangaflow.ui

import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import dev.mangaflow.data.SearchTarget
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

/** Content-first source context, with one retained workspace for each browsing intent. */
@OptIn(ExperimentalLayoutApi::class)
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
    onOpenSearch: ((SearchTarget) -> Unit)? = null,
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
    var pendingFilter by remember(owner) { mutableStateOf<Int?>(null) }
    var hotTagsOpen by remember(owner) { mutableStateOf(false) }
    var pendingTag by remember(owner) { mutableStateOf<String?>(null) }
    val failed = if (inSearch && !browse.isSearch) emptyList()
        else browse.searchSources.values.filter { it.error != null }
    val selectedFeed = current.feeds.firstOrNull { it.id == current.feedId }
    val nativePeriods = if (current.selectedSource == "*") RankPeriod.entries
        else selectedFeed?.nativeRankPeriods().orEmpty()
    // Author/tag requests have their own source protocol and do not consume these options.
    val filters = when {
        !searching -> selectedFeed?.filters.orEmpty()
        browse.isSearch && browse.searchTarget == dev.mangaflow.data.SearchTarget.TITLE -> current.searchFilters
        else -> emptyList()
    }
    val visibleFilters = filters.withIndex().filterNot {
        (!searching && browse.mode == DiscoveryMode.RANKING && it.value.id == "period" && nativePeriods.isNotEmpty()) ||
            (searching && it.value.id == "target")
    }
    val modifiedFilters = visibleFilters.count { (index, filter) ->
        filter.normalize(current.options.getOrNull(index)) != filter.normalize()
    }
    val sourceName = current.sources.firstOrNull { it.id == current.selectedSource }?.name ?: "全部来源"
    val hasFeeds = !searching && current.feeds.size > 1
    val tagProvider = current.sources.firstOrNull {
        it.id == "jmcomic" && (current.selectedSource == "*" || current.selectedSource == it.id)
    }
    val hasHotTags = searching && browse.searchTarget == SearchTarget.TAG && tagProvider != null
    val hasFilters = visibleFilters.isNotEmpty()
    val selectionSummary = visibleFilters.filter { (index, filter) ->
        filter.normalize(current.options.getOrNull(index)) != filter.normalize()
    }.joinToString(" · ") { (index, filter) ->
        "${filter.title}：${sourceFilterSummary(filter, current.options.getOrNull(index))}"
    }
    var hotTags by remember(tagProvider) { mutableStateOf<List<String>>(emptyList()) }
    var hotTagsLoaded by remember(tagProvider) { mutableStateOf(false) }
    var hotTagsLoading by remember(tagProvider) { mutableStateOf(false) }
    var hotTagsError by remember(tagProvider) { mutableStateOf<String?>(null) }
    var hotTagsRevision by remember(tagProvider) { mutableIntStateOf(0) }
    val present = feedPicker || filtersVisible || pendingFilter != null ||
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
    LaunchedEffect(filtersVisible, pendingFilter) {
        if (!filtersVisible) {
            val chosen = pendingFilter
            pendingFilter = null
            if (currentOwner()) {
                if (chosen != null) filterIndex = chosen
            }
        }
    }
    LaunchedEffect(hotTagsOpen, tagProvider, hotTagsRevision, active) {
        val provider = tagProvider ?: return@LaunchedEffect
        if (!active || !hotTagsOpen || hotTagsLoaded) return@LaunchedEffect
        hotTagsLoading = true; hotTagsError = null
        try {
            hotTags = repository.sourceHotTags(provider.id).filter(String::isNotBlank).distinct()
            hotTagsLoaded = true
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { hotTagsError = "热门标签暂未读取" }
        finally { hotTagsLoading = false }
    }
    val colors = MaterialTheme.colorScheme
    val controlColors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface)
    // Only the restricted discovery header offers shortcuts into tag/author search.
    val compactHome = !searching && onOpenSearch != null
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
    val fontScale = LocalMangaWindow.current.fontScale.coerceAtLeast(1f)
    val failureCount = failed.size.takeIf { it > 0 }?.toString().orEmpty()
    val statusWidth = (30.dp + 9.dp * failureCount.length * fontScale).coerceAtLeast(44.dp)
    val sourceControl: @Composable (Modifier) -> Unit = { placement ->
        MotionTextButton(onClick = onSources, enabled = active && !present,
            modifier = placement.heightIn(min = 48.dp).semantics {
                stateDescription = "当前来源：$sourceName" + selectedFeed?.title?.let { "，$it" }.orEmpty()
            },
            colors = controlColors,
            contentPadding = PaddingValues(start = 2.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)) {
            if (compactHome) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (current.selectedSource == "*") Icon(Icons.Outlined.Layers, null, Modifier.size(20.dp))
                    else ChapterSourceLogo(current.selectedSource, sourceName, Modifier.size(20.dp))
                    Text(sourceName, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Outlined.SwapHoriz, null, Modifier.size(15.dp), tint = colors.onSurfaceVariant)
                }
            } else {
                if (current.selectedSource == "*") Icon(Icons.Outlined.Layers, null, Modifier.size(23.dp))
                else ChapterSourceLogo(current.selectedSource, sourceName, Modifier.size(23.dp))
                Column(Modifier.weight(1f).padding(start = 9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(sourceName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val context = if (searching) "搜索范围" else selectedFeed?.title?.takeIf { it != sourceName }
                        ?: when (browse.mode) {
                            DiscoveryMode.RECOMMENDED -> "站点选编与更新"
                            DiscoveryMode.LATEST -> "站点更新"
                            DiscoveryMode.RANKING -> "各站独立榜单"
                        }
                    Text(context, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Outlined.SwapHoriz, null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
            }
        }
    }
    val sourceTools: @Composable () -> Unit = {
        if (hasFeeds) MotionIconButton(onClick = { feedPicker = true },
            enabled = active && !current.loading && !present, modifier = Modifier.size(44.dp),
            tooltip = "${sourceName}分类与栏目") {
            Icon(Icons.Outlined.ViewModule, "${sourceName}分类与栏目", Modifier.size(19.dp))
        }
        if (compactHome && onOpenSearch != null) {
            MotionIconButton(onClick = { onOpenSearch(SearchTarget.TAG) }, enabled = active && !present,
                modifier = Modifier.size(44.dp), tooltip = "标签") {
                Icon(Icons.AutoMirrored.Outlined.Label, "标签", Modifier.size(19.dp), tint = colors.onSurfaceVariant)
            }
            MotionIconButton(onClick = { onOpenSearch(SearchTarget.AUTHOR) }, enabled = active && !present,
                modifier = Modifier.size(44.dp), tooltip = "作者") {
                Icon(Icons.Outlined.PersonOutline, "作者", Modifier.size(19.dp), tint = colors.onSurfaceVariant)
            }
        }
        if (hasHotTags) MotionIconButton(onClick = { hotTagsOpen = true },
            enabled = active && !present, modifier = Modifier.size(44.dp), tooltip = "禁漫热门标签") {
            Icon(Icons.Outlined.Tag, "禁漫热门标签", Modifier.size(19.dp))
        }
        if (hasFilters) Box {
            MotionIconButton(onClick = { filtersVisible = true },
                enabled = active && !current.loading && !present,
                modifier = Modifier.size(44.dp).semantics {
                    stateDescription = if (modifiedFilters == 0) "未调整筛选" else "$modifiedFilters 项筛选已调整"
                }, tooltip = "筛选与排序") {
                Icon(Icons.Outlined.Tune, "筛选与排序", Modifier.size(19.dp))
            }
            if (modifiedFilters > 0) Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(5.dp)
                .background(MaterialTheme.colorScheme.onSurface, CircleShape))
        }
        if (compactHome && status != null) {
            val statusLabel = "查看来源状态：$status"
            MotionIconButton(onClick = { failuresOpen = true }, enabled = active && !present,
                modifier = Modifier.width(statusWidth).heightIn(min = 44.dp), tooltip = statusLabel) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(Icons.Outlined.Info, statusLabel, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                    if (failureCount.isNotEmpty()) Text(failureCount, Modifier.clearAndSetSemantics { },
                        style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (compactHome) BoxWithConstraints(Modifier.fillMaxWidth()) {
            val iconCount = 2 + (if (hasFeeds) 1 else 0) + (if (hasFilters) 1 else 0)
            val toolsWidth = 46.dp * iconCount + (if (status != null) statusWidth + 6.dp else 0.dp)
            if (maxWidth >= toolsWidth + 52.dp + 56.dp * fontScale) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    sourceControl(Modifier.weight(1f))
                    sourceTools()
                }
            } else {
                // Keep all controls reachable without shrinking their hit areas at large text sizes.
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    sourceControl(Modifier.fillMaxWidth())
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End)) {
                        sourceTools()
                    }
                }
            }
        } else Row(Modifier.fillMaxWidth().heightIn(min = 52.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            sourceControl(Modifier.weight(1f))
            sourceTools()
        }
        if (searching && browse.searchTarget in listOf(SearchTarget.AUTHOR, SearchTarget.TAG)) {
            browse.searchSources.values.filter { it.coverage != null }.groupBy { it.coverage }.forEach { (coverage, lanes) ->
                val description = when (coverage) {
                    SourceSearchCoverage.NATIVE_INDEX -> if (browse.searchTarget == SearchTarget.AUTHOR) "站内作者索引" else "站内标签索引"
                    SourceSearchCoverage.CATEGORY_OR_KEYWORD -> "匹配站内分类时直达，其余筛选关键词结果"
                    SourceSearchCoverage.KEYWORD_METADATA -> "筛选关键词结果中的已知信息"
                    null -> return@forEach
                }
                Text(lanes.joinToString("、") { it.sourceName } + " · " + description,
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
        if (!compactHome && selectionSummary.isNotBlank()) Text(selectionSummary,
            Modifier.fillMaxWidth().padding(start = 34.dp, end = 4.dp, bottom = 5.dp),
            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!compactHome && status != null) MotionTextButton(onClick = { failuresOpen = true }, enabled = active && !present,
            modifier = Modifier.fillMaxWidth(), colors = controlColors,
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)) {
            Icon(Icons.Outlined.Info, null, Modifier.size(15.dp))
            Text(status, Modifier.weight(1f).padding(start = 7.dp), style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("查看", style = MaterialTheme.typography.labelMedium)
        }
        val selectedPeriod = nativePeriods.indexOf(browse.period)
        if (!searching && browse.mode == DiscoveryMode.RANKING && !current.loading &&
            nativePeriods.size > 1 && selectedPeriod >= 0) {
            MotionTabs(selected = selectedPeriod, labels = nativePeriods.map { it.label },
                onSelect = { if (active && !present) controller.selectDiscovery(DiscoveryMode.RANKING, period = nativePeriods[it]) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).motionInputGate(active && !present),
                controlHeight = 44.dp, fontSize = 12.sp, treatment = MotionTabTreatment.Capsule,
                role = Role.RadioButton, selectedFontWeight = FontWeight.SemiBold,
                tooltips = nativePeriods.map { period ->
                    if (current.selectedSource == "*") "${period.label} · 保留各站榜单口径" else period.label
                })
        }
    }
    if (filtersVisible) MotionBottomSheet(backdrop = backdrop, onDismissRequest = {
        filtersVisible = false
        val chosen = pendingOption
        pendingOption = null
        if (chosen != null && currentOwner()) controller.selectOption(chosen.first, chosen.second)
    }) { dismiss ->
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = (LocalMangaWindow.current.height - 96.dp).coerceAtLeast(120.dp)),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "browse-filter-heading") { SourceBrowseSheetHeading("筛选与排序", dismiss, sourceName) }
            items(visibleFilters, key = { "${it.index}:${it.value.id}" }) { (index, filter) ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(filter.title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        if (filter.multiple || filter.choices.size > 8) MotionTextButton(onClick = {
                            pendingFilter = index
                            dismiss()
                        }, enabled = active && pendingOption == null && pendingFilter == null, colors = controlColors) {
                            Text(if (filter.multiple) "选择多项" else "全部 ${filter.choices.size} 项",
                                style = MaterialTheme.typography.labelSmall)
                            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(15.dp))
                        }
                    }
                    if (filter.multiple) Text(sourceFilterSummary(filter, current.options.getOrNull(index)),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    else FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val selected = filter.selectedIds(current.options.getOrNull(index))
                        val choices = if (filter.choices.size <= 8) filter.choices else
                            (filter.choices.take(6) + filter.choices.filter { it.id in selected }).distinctBy { it.id }
                        choices.forEach { choice ->
                            SourceBrowseChoice(choice.title, choice.id in selected,
                                active && pendingOption == null && pendingFilter == null) {
                                pendingOption = index to choice.id
                                dismiss()
                            }
                        }
                    }
                }
            }
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
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = (LocalMangaWindow.current.height - 96.dp).coerceAtLeast(120.dp))
            .selectableGroup(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
            item(key = "feed-picker-header") { SourceBrowseSheetHeading("分类与栏目", dismiss, sourceName) }
            current.feeds.groupBy { it.kind }.forEach { (kind, entries) ->
                item(key = "feed-kind:" + kind.name) {
                    Text(when (kind) { SourceFeedKind.LATEST -> "更新"; SourceFeedKind.EXPLORE -> "发现"
                        SourceFeedKind.RANKING -> "排行"; SourceFeedKind.CATEGORY -> "分类" },
                        Modifier.padding(top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                entries.chunked(12).forEachIndexed { groupIndex, group ->
                    item(key = "feed-choices:${kind.name}:$groupIndex") {
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            group.forEach { feed ->
                                SourceBrowseChoice(feed.title, feed.id == current.feedId, active && pendingFeed == null) {
                                    pendingFeed = feed.id
                                    dismiss()
                                }
                            }
                        }
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
                    if (filter.multiple) {
                        MotionTextButton(onClick = {
                            selectedIds = if (chosen) selectedIds - choice.id else selectedIds + choice.id
                        }, enabled = active && pendingOption == null, colors = controlColors,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { selected = chosen },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)) {
                            Text(choice.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            if (chosen) Icon(Icons.Outlined.Check, null, Modifier.size(17.dp))
                        }
                    } else RankingMenuChoice(choice.title, chosen, active && pendingOption == null) {
                        pendingOption = index to choice.id
                        dismiss()
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
            item(key = "hot-tags-header") { SourceBrowseSheetHeading("热门标签", dismiss, tagProvider?.name) }
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
            items(hotTags.chunked(12), key = { "hot-tags:" + it.first() }) { tags ->
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    tags.forEach { tag ->
                        SourceBrowseChoice(tag, false, active && pendingTag == null) {
                            pendingTag = tag
                            dismiss()
                        }
                    }
                }
            }
        }
    }
}

private fun sourceAccountRequired(message: String?): Boolean = message?.contains("需要登录") == true

/** Same quiet selected surface and press response as the adopted source-choice rows. */
@Composable
private fun SourceBrowseChoice(label: String, chosen: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    MotionTextButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.heightIn(min = 44.dp).semantics { selected = chosen },
        shape = RoundedCornerShape(13.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface,
            containerColor = if (chosen) colors.onSurface.copy(alpha = .09f) else colors.onSurface.copy(alpha = .035f)),
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 9.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            fontWeight = if (chosen) FontWeight.Medium else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (chosen) Icon(Icons.Outlined.Check, null, Modifier.padding(start = 5.dp).size(14.dp))
    }
}

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
internal fun SourceCapabilityDetailsSheet(
    source: BodySourceInfo, repository: UnifiedMangaRepository, plugins: SourcePluginStore?,
    backdrop: HazeState?, manga: Manga? = null, onDismiss: () -> Unit, onSourceChanged: () -> Unit = {},
    initiallyShowLogin: Boolean = false,
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
                    }, initiallyShowLogin = initiallyShowLogin || accountFailure != null || offered.loginRequired)
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
