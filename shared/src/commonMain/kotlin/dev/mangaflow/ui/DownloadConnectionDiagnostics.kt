package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.net.*
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.formatInstantMillis
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.reader.panels.PanelRuntimeId
import dev.mangaflow.reader.panels.PanelRuntimeState
import dev.mangaflow.reader.panels.PanelRuntimeStore
import dev.mangaflow.ui.motion.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch


@Composable
private fun NetworkAutoScheduleControls(controller: NetworkAutoCheckController, active: Boolean) {
    val schedule by controller.schedule.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    var expanded by rememberSaveable(controller) { mutableStateOf(false) }
    var minutes by key(expanded, schedule.intervalMinutes) { rememberSaveable { mutableStateOf(schedule.intervalMinutes.toString()) } }
    var workers by key(expanded, schedule.concurrency) { rememberSaveable { mutableStateOf(schedule.concurrency.toString()) } }
    var applyAutomatically by key(expanded, schedule.autoApply) { rememberSaveable { mutableStateOf(schedule.autoApply) } }
    var notice by rememberSaveable(controller) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            MotionIconButton(onClick = {
                if (!controller.updateSchedule(schedule.copy(enabled = !schedule.enabled)))
                    notice = "设置未能保存"
                else notice = null
            }, enabled = active, modifier = Modifier.size(44.dp),
                tooltip = if (schedule.enabled) "暂停自动线路检测" else "开启自动线路检测") {
                Icon(if (schedule.enabled) Icons.Outlined.Check else Icons.Outlined.Close,
                    null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("自动线路检测", style = MaterialTheme.typography.bodySmall)
                Text(if (schedule.enabled) "每 ${schedule.intervalMinutes} 分钟 · ${schedule.concurrency} 并发 · ${if (schedule.autoApply) "检测后自动应用" else "只记录"} · 锁定 ${schedule.lockedTargetIds.size}"
                    else "已暂停 · 手动检测仍可用",
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            MotionIconButton(onClick = { expanded = !expanded }, enabled = active,
                modifier = Modifier.size(44.dp), tooltip = "配置自动检测") {
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
            }
        }
        CopyShelfReveal(expanded) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedTextField(value = minutes,
                        onValueChange = { minutes = it.filter(Char::isDigit) },
                        modifier = Modifier.weight(1f), singleLine = true, enabled = active,
                        label = { Text("周期 · 分钟") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    androidx.compose.material3.OutlinedTextField(value = workers,
                        onValueChange = { workers = it.filter(Char::isDigit) },
                        modifier = Modifier.weight(1f), singleLine = true, enabled = active,
                        label = { Text("并发 · 个") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MotionTextButton(onClick = { applyAutomatically = !applyAutomatically }, enabled = active) {
                        Icon(if (applyAutomatically) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                            null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("自动应用验证结果")
                    }
                    Spacer(Modifier.weight(1f))
                    MotionTextButton(onClick = {
                        val interval = minutes.toIntOrNull()
                        val parallel = workers.toIntOrNull()
                        if (interval == null || interval <= 0 || parallel == null || parallel <= 0)
                            notice = "周期与并发须为正整数"
                        else if (controller.updateSchedule(schedule.copy(intervalMinutes = interval,
                                concurrency = parallel, autoApply = applyAutomatically))) {
                            notice = null; expanded = false
                        } else notice = "设置未能保存"
                    }, enabled = active) { Text("保存") }
                }
                Text("两次验证后自动应用未锁定的更优线路。锁定项只检测、不改动；Android 系统后台唤醒可能延后。",
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
        (notice ?: schedule.message)?.let { Text(it, style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp)) }
    }
}

/** Retain the real review and its authority; the result scene has no second settings navigation. */
@Composable
internal fun NetworkAutoCheckWorkspace(
    controller: NetworkAutoCheckController, active: Boolean,
    onEvidence: (String?) -> Unit, onSettings: () -> Unit, onReceipt: (String) -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val schedule by controller.schedule.collectAsStateWithLifecycle()
    val context = LocalMangaContext.current
    val runtimes = remember(context) { PanelRuntimeStore.get(context) }
    val runtimeStates by runtimes.states.collectAsStateWithLifecycle()
    val steam = runtimeStates[PanelRuntimeId.STEAM] ?: PanelRuntimeState()
    val scope = rememberCoroutineScope()
    val latestActive by rememberUpdatedState(active)
    val colors = MaterialTheme.colorScheme
    val list = rememberLazyListState()
    val selected = state.selection
    var expandedId by remember(controller) { mutableStateOf<String?>(null) }
    var notice by remember(controller) { mutableStateOf<String?>(null) }
    var saving by remember(controller) { mutableStateOf(false) }
    val canReview = state.complete && !state.historyOnly && !state.restoring && !state.applied &&
        !state.stale && !state.running && !state.applying && !saving
    fun current(row: NetworkAutoRow) = row.currentRoute?.mode
    val changes = state.rows.filterNot { it.id in schedule.lockedTargetIds }
        .mapNotNull { row -> recommendationChange(row, current(row))?.let { row.id to it } }.toMap()
    val applicable = selected.filter { (id, mode) -> id !in schedule.lockedTargetIds &&
        state.rows.firstOrNull { it.id == id }?.let { row ->
        row.canApply && routeResult(row, mode).verified() && mode != current(row)
    } == true }
    val desktop = platformKind == PlatformKind.DESKTOP
    val motion = LocalMotionEnabled.current
    var filter by remember(controller, state.runId) { mutableStateOf(CheckResultFilter.ALL) }
    val available = state.rows.count { it.direct.verified() || it.proxy.verified() }
    val unverified = state.rows.filter { !it.direct.verified() && !it.proxy.verified() }
    val visibleRows = if (!desktop || state.running || state.restoring) state.rows else when (filter) {
        CheckResultFilter.ALL -> state.rows
        CheckResultFilter.CHANGE -> state.rows.filter { it.id in changes }
        CheckResultFilter.UNVERIFIED -> unverified
    }
    val metrics = rememberAutoMetrics(state.rows)
    val summaryAbove by remember(list) { derivedStateOf { list.firstVisibleItemIndex > 1 } }
    LaunchedEffect(controller, state.runId, state.running, state.complete) {
        if (state.running) {
            expandedId = null; notice = null; onReceipt("")
        } else if (!desktop && state.complete && !state.historyOnly && !state.stale) {
            if (motion) list.animateScrollToItem(0) else list.scrollToItem(0)
        }
    }
    fun run() {
        if (!latestActive || state.restoring || state.running || saving || state.applying) return
        notice = null
        controller.start()
    }
    fun choose(row: NetworkAutoRow, mode: DownloadRouteMode) {
        if (!latestActive || !canReview || !row.canApply ||
            row.id in schedule.lockedTargetIds || !routeResult(row, mode).verified()) return
        controller.stage(row.id, mode.takeIf { it != current(row) })
        notice = null
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.weight(1f, fill = false).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxWidth().padding(end = if (desktop) 12.dp else 0.dp)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val edge = minOf(12.dp.toPx(), size.height / 3f)
                    if (edge > 0f && list.canScrollBackward) drawRect(Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black), endY = edge), blendMode = BlendMode.DstIn)
                    if (edge > 0f && list.canScrollForward) drawRect(Brush.verticalGradient(
                        listOf(Color.Black, Color.Transparent), startY = size.height - edge, endY = size.height), blendMode = BlendMode.DstIn)
                }, state = list, contentPadding = PaddingValues(bottom = 8.dp)) {
                item("schedule") { NetworkAutoScheduleControls(controller, active) }
                item("run") {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        if (state.running || state.restoring) MotionBusyIndicator(Modifier.size(14.dp), strokeWidth = 1.4.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(when {
                                state.restoring -> "读取上次检测"
                                state.running -> if (state.total > 0) "后台检测 ${state.completed}/${state.total}" else "正在准备检测"
                                state.historyOnly && state.interrupted -> if (state.rows.isEmpty())
                                    "上次检测已中断，请重新检测" else "上次检测中断，显示最近结果"
                                state.historyOnly -> "上次结果 · 重新检测后可修改"
                                state.stale -> "结果已失效，请重新检测"
                                state.applied -> "修改已保存"
                                state.complete -> "${state.rows.size} 项 · $available 可用 · ${changes.size} 建议"
                                state.rows.isNotEmpty() -> "已停止 · 保留已完成结果"
                                else -> "分别比较直连与代理"
                            }, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val timestamp = state.finishedAtMillis ?: state.startedAtMillis
                            if (timestamp != null && !state.restoring) Text(
                                (if (state.running || state.finishedAtMillis == null) "开始于 " else "检测于 ") +
                                    formatInstantMillis(timestamp, "MM-dd HH:mm"), style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant, maxLines = 1, softWrap = false)
                        }
                        if (state.running) MotionIconButton(controller::cancel, enabled = active,
                            modifier = Modifier.size(44.dp), tooltip = "停止检测") {
                            Icon(Icons.Outlined.Close, "停止检测", Modifier.size(17.dp))
                        } else if (state.rows.isEmpty()) MotionTextButton(::run, enabled = active && !state.restoring && !saving && !state.applying) {
                            Text("一键检测", style = MaterialTheme.typography.labelMedium)
                        } else MotionIconButton(::run, enabled = active && !state.restoring && !saving && !state.applying,
                            modifier = Modifier.size(44.dp), tooltip = "重新检测") {
                            Icon(Icons.Outlined.Refresh, "重新检测", Modifier.size(17.dp), tint = colors.onSurfaceVariant)
                        }
                    }
                }
                if (desktop && state.rows.isNotEmpty() && !state.running && !state.restoring) item("filter") {
                    val options = CheckResultFilter.entries
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        options.forEach { option ->
                            val count = when (option) {
                                CheckResultFilter.ALL -> state.rows.size
                                CheckResultFilter.CHANGE -> changes.size
                                CheckResultFilter.UNVERIFIED -> unverified.size
                            }
                            val label = if (option == CheckResultFilter.CHANGE && (state.historyOnly || state.stale)) "当时建议" else option.label
                            ManagementResultFilter("$label $count", option == filter, active) { filter = option }
                        }
                    }
                }
                if (state.rows.isNotEmpty() && visibleRows.isEmpty()) item("no-match") {
                    Text(if (filter == CheckResultFilter.CHANGE) "当前没有已验证的改线建议" else "每个项目都有已验证的可用线路",
                        Modifier.padding(horizontal = 8.dp, vertical = 20.dp),
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                NetworkAutoGroup.entries.forEach { group ->
                    val rows = visibleRows.filter { it.group == group }
                    if (rows.isNotEmpty()) {
                        item("group-$group") { NetworkAutoGroupHeading(group, rows.size, metrics) }
                        items(rows, key = { it.id }) { row ->
                            val value = current(row)
                            NetworkAutoCheckRow(row, value, selected[row.id], metrics,
                                stale = state.stale || state.historyOnly, historical = state.historyOnly,
                                active = active, enabled = active && canReview,
                                locked = row.id in schedule.lockedTargetIds,
                                expanded = expandedId == row.id,
                                directRunning = "${row.id}:DIRECT" in state.activeProbeIds,
                                proxyRunning = "${row.id}:PROXY" in state.activeProbeIds,
                                steam = steam, onDetails = { expandedId = if (expandedId == row.id) null else row.id },
                                onToggle = {
                                    if (row.id in selected) controller.stage(row.id, null)
                                    else recommendationChange(row, value)?.let { choose(row, it) }
                                }, onChoose = { choose(row, it) }, onEvidence = { onEvidence(row.id) },
                                onSettings = onSettings,
                                onLock = {
                                    if (!controller.lockTarget(row.id, row.id !in schedule.lockedTargetIds))
                                        notice = "来源自动锁定未保存"
                                    else notice = null
                                },
                                onPrepareSteam = {
                                    if (latestActive && steamNeedsPreparation(row, steam) && !steam.downloading) scope.launch {
                                        controller.invalidate("Steam 组件状态已变化，请准备完成后重新检测")
                                        try { runtimes.download(PanelRuntimeId.STEAM) }
                                        catch (cancel: CancellationException) { throw cancel }
                                        catch (_: Exception) { notice = "组件准备未完成，请查看详情" }
                                    }
                                }, onCancelSteam = { runtimes.cancel(PanelRuntimeId.STEAM) })
                        }
                    }
                }
            }
            if (desktop) DesktopVerticalScrollbar(list, Modifier.align(Alignment.CenterEnd))
        }
        if (state.error != null || state.staleReason != null || notice != null || !state.historySaved) Row(
            Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(notice ?: if (!state.historySaved) "检测记录未能保存" else if (state.historyOnly) "历史结果不能直接应用"
                else if (state.stale) "配置或网络已变化" else "检测未能完成", Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            MotionIconButton({ onEvidence(null) }, enabled = active, modifier = Modifier.size(44.dp), tooltip = "查看原因") {
                Icon(Icons.Outlined.Info, "查看原因", Modifier.size(15.dp), tint = colors.onSurfaceVariant)
            }
        }
        if (desktop && !state.restoring && state.total > 0 && !state.historyOnly) {
            key(state.runId, state.total) {
                ManagementProgressTrack(state.completed.toFloat() / state.total, "已检测项目",
                    if (state.stale || state.interrupted) colors.onSurfaceVariant else colors.primary,
                    Modifier.fillMaxWidth().height(3.dp))
            }
        }
        if (state.rows.isNotEmpty()) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when {
                state.historyOnly -> "历史结果 · 请先重测"
                state.stale -> "请先重测"
                state.applied -> "已保存"
                state.complete -> "已选 ${applicable.size} 项"
                state.running -> if (desktop && state.total > 0) "已检测 ${state.completed} / ${state.total} 项" else "离开面板后继续检测"
                else -> "未完成项保留原设置"
            }, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            if (desktop && summaryAbove && !state.running && !state.restoring) MotionTextButton(onClick = {
                scope.launch {
                    if (motion) list.animateScrollToItem(1) else list.scrollToItem(1)
                }
            }, enabled = active, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("查看摘要") }
            MotionButton(onClick = {
                if (!latestActive || !canReview || applicable.isEmpty()) return@MotionButton
                val chosen = applicable.toMap()
                saving = true; notice = null
                scope.launch {
                    try {
                        val result = controller.apply(chosen)
                        onReceipt(result.message)
                        notice = if (result.success) "已保存 ${result.applied} 项" else if (result.stale) "配置已变化，未修改" else "未能保存，请查看原因"
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { notice = "未能保存，请重试" }
                    finally { saving = false }
                }
            }, enabled = active && canReview && applicable.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 14.dp)) {
                if (saving || state.applying) { MotionBusyIndicator(Modifier.size(14.dp), strokeWidth = 1.4.dp); Spacer(Modifier.width(6.dp)) }
                Text(if (saving || state.applying) "保存中" else if (state.applied) "已保存" else "应用 ${applicable.size} 项")
            }
        }
    }
}

private enum class CheckResultFilter(val label: String) { ALL("全部"), CHANGE("建议改线"), UNVERIFIED("尚未验证可用") }

private data class AutoRowMetrics(val cell: Dp, val name: Dp)

/** Measure numbers and units separately; a route cell never wraps or truncates a measured latency. */
@Composable
private fun rememberAutoMetrics(rows: List<NetworkAutoRow>): AutoRowMetrics {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val numberStyle = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
    val unitStyle = MaterialTheme.typography.labelSmall
    val nameStyle = MaterialTheme.typography.bodySmall
    return remember(rows, density.density, density.fontScale, numberStyle, unitStyle, nameStyle) {
        val values = rows.flatMap { listOf(it.direct, it.proxy) }
        val unit = measurer.measure(AnnotatedString("ms"), style = unitStyle, softWrap = false, maxLines = 1).size.width
        val widths = values.map { value ->
            val number = value?.millis?.takeIf { value.status == NetworkAutoRouteStatus.AVAILABLE ||
                value.status == NetworkAutoRouteStatus.PARTIAL && value.samples > 0 }
            if (number != null) measurer.measure(AnnotatedString(number.toString()), style = numberStyle,
                softWrap = false, maxLines = 1).size.width + unit + with(density) { 3.dp.roundToPx() }
            else measurer.measure(AnnotatedString(networkAutoResultLabel(value)), style = unitStyle,
                softWrap = false, maxLines = 1).size.width
        }
        val statusWidth = measurer.measure(AnnotatedString("未配置"), style = unitStyle, softWrap = false, maxLines = 1).size.width
        // Reserve the bounded probe's five-digit duration from the first waiting frame;
        // longer actual values still increase the measured width and choose a safer layout.
        val durationWidth = measurer.measure(AnnotatedString("88888"), style = numberStyle,
            softWrap = false, maxLines = 1).size.width + unit + with(density) { 3.dp.roundToPx() }
        val minimum = maxOf(statusWidth, durationWidth)
        val name = measurer.measure(AnnotatedString("漫画来源"), style = nameStyle, softWrap = false, maxLines = 1).size.width
        with(density) { AutoRowMetrics(maxOf(64.dp, maxOf(minimum, widths.maxOrNull() ?: 0).toDp() + 23.dp),
            name.toDp() + 28.dp) }
    }
}

@Composable
private fun NetworkAutoGroupHeading(group: NetworkAutoGroup, count: Int, metrics: AutoRowMetrics) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val inline = maxWidth >= metrics.name + 44.dp + metrics.cell * 2
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(when (group) {
                NetworkAutoGroup.MANGA_SOURCES -> "漫画源"
                NetworkAutoGroup.ANIME_SOURCES -> "动漫源与服务"
                NetworkAutoGroup.RATING_SITES -> "评分站"
                NetworkAutoGroup.DOWNLOADS -> "下载与服务"
            } + " · $count", Modifier.weight(1f).padding(start = 8.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (inline) {
                Text("直连", Modifier.width(metrics.cell), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("代理", Modifier.width(metrics.cell), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NetworkAutoCheckRow(
    row: NetworkAutoRow, current: DownloadRouteMode?, selected: DownloadRouteMode?, metrics: AutoRowMetrics,
    stale: Boolean, historical: Boolean, active: Boolean, enabled: Boolean, locked: Boolean,
    expanded: Boolean, directRunning: Boolean, proxyRunning: Boolean,
    steam: PanelRuntimeState, onDetails: () -> Unit, onToggle: () -> Unit, onChoose: (DownloadRouteMode) -> Unit,
    onEvidence: () -> Unit, onSettings: () -> Unit, onLock: () -> Unit,
    onPrepareSteam: () -> Unit, onCancelSteam: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val recommended = recommendationChange(row, current).takeUnless { locked }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val inline = maxWidth >= metrics.name + 44.dp + metrics.cell * 2
        val sideBySide = maxWidth - 44.dp >= metrics.cell * 2 + 6.dp
        val longRoute = inline && maxWidth >= 440.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        Column(Modifier.fillMaxWidth().alpha(if (stale) .6f else 1f)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                if (row.canApply) MotionIconButton(onToggle, enabled = enabled && !locked &&
                    (selected != null || recommended != null),
                    modifier = Modifier.size(44.dp), tooltip = if (selected != null) "不修改${row.label}" else "选择${row.label}建议") {
                    Icon(if (selected != null) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                        if (selected != null) "已选${row.label}" else "未选${row.label}", Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                } else Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Info, "仅展示检测状态", Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                }
                MotionTextButton(onDetails, modifier = Modifier.weight(1f).heightIn(min = 44.dp), enabled = active,
                    contentPadding = PaddingValues(horizontal = 2.dp)) {
                    Text(networkAutoRowTitle(row), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        maxLines = if (inline) 1 else 2, overflow = TextOverflow.Ellipsis)
                    if (locked) Icon(Icons.Outlined.Lock, "禁止自动改线", Modifier.size(12.dp),
                        tint = colors.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text(if (longRoute) current?.let(::downloadRouteLabel) ?: "待设置" else when (current) { DownloadRouteMode.SYSTEM -> "系统"; DownloadRouteMode.DIRECT -> "直";
                        DownloadRouteMode.PROXY -> "代"; null -> "待设" }, style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant, maxLines = 1, softWrap = false,
                        modifier = Modifier.semantics { contentDescription = (if (historical) "检测时线路，" else "当前线路，") +
                            (current?.let(::downloadRouteLabel) ?: "未单独指定") })
                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null,
                        Modifier.size(12.dp), tint = colors.onSurfaceVariant)
                }
                if (inline) {
                    NetworkAutoRouteCell(row.direct, DownloadRouteMode.DIRECT, selected, recommended, enabled && !locked, false,
                        directRunning, onChoose, Modifier.width(metrics.cell))
                    NetworkAutoRouteCell(row.proxy, DownloadRouteMode.PROXY, selected, recommended, enabled && !locked, false,
                        proxyRunning, onChoose, Modifier.width(metrics.cell))
                }
            }
            if (!inline) {
                if (sideBySide) Row(Modifier.fillMaxWidth().padding(start = 44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NetworkAutoRouteCell(row.direct, DownloadRouteMode.DIRECT, selected, recommended, enabled && !locked, true,
                        directRunning, onChoose, Modifier.weight(1f))
                    NetworkAutoRouteCell(row.proxy, DownloadRouteMode.PROXY, selected, recommended, enabled && !locked, true,
                        proxyRunning, onChoose, Modifier.weight(1f))
                } else Column(Modifier.fillMaxWidth().padding(start = 44.dp)) {
                    NetworkAutoRouteCell(row.direct, DownloadRouteMode.DIRECT, selected, recommended, enabled && !locked, true,
                        directRunning, onChoose, Modifier.fillMaxWidth())
                    NetworkAutoRouteCell(row.proxy, DownloadRouteMode.PROXY, selected, recommended, enabled && !locked, true,
                        proxyRunning, onChoose, Modifier.fillMaxWidth())
                }
            }
            CopyShelfReveal(expanded) {
                Column(Modifier.fillMaxWidth().padding(start = 44.dp, end = 4.dp, bottom = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (locked) "已锁定自动修改 · 手动配置仍可更改" else networkAutoRowReason(row, current, steam),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (row.category == DownloadCategory.STEAM_WALLPAPERS && steamNeedsPreparation(row, steam) &&
                            platformKind == PlatformKind.ANDROID) {
                            if (steam.downloading) {
                                MotionBusyIndicator(Modifier.size(13.dp), strokeWidth = 1.3.dp)
                                Text(steam.progress?.takeIf { it.isFinite() && it in 0f..1f }?.let { "${(it * 100).toInt()}%" }
                                    ?: "准备中", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelSmall)
                                MotionIconButton(onCancelSteam, modifier = Modifier.size(44.dp), enabled = active, tooltip = "取消组件准备") {
                                    Icon(Icons.Outlined.Close, "取消组件准备", Modifier.size(15.dp))
                                }
                            } else MotionTextButton(onPrepareSteam, enabled = active) {
                                Text(if (steam.requiresUpdate) "更新组件" else "准备组件", style = MaterialTheme.typography.labelMedium)
                            }
                        } else if (listOfNotNull(row.direct, row.proxy).any { result -> result.status == NetworkAutoRouteStatus.NOT_CONFIGURED &&
                            listOf("代理", "授权", "端口").any { word -> word in result.message } }) {
                            MotionTextButton(onSettings, enabled = active) { Text("网络设置", style = MaterialTheme.typography.labelMedium) }
                        }
                        MotionIconButton(onClick = onLock, enabled = active && row.canApply,
                            modifier = Modifier.size(44.dp),
                            tooltip = if (locked) "解除自动改线锁定" else "锁定此项，自动检测不能修改线路") {
                            Icon(if (locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                                if (locked) "已锁定" else "允许自动优化", Modifier.size(17.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        MotionTextButton(onEvidence, enabled = active) { Text("详细依据", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkAutoRouteCell(
    result: NetworkAutoRouteResult?, mode: DownloadRouteMode, selected: DownloadRouteMode?,
    recommended: DownloadRouteMode?, enabled: Boolean, showRoute: Boolean, running: Boolean,
    onChoose: (DownloadRouteMode) -> Unit, modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val chosen = selected == mode
    val value = result?.millis?.takeIf { result.status == NetworkAutoRouteStatus.AVAILABLE ||
        result.status == NetworkAutoRouteStatus.PARTIAL && result.samples > 0 }
    MotionTextButton({ onChoose(mode) }, enabled = enabled && result.verified(),
        modifier = modifier.heightIn(min = 44.dp)
            .background(if (chosen) colors.onSurface.copy(alpha = .055f) else Color.Transparent, RoundedCornerShape(10.dp))
            .semantics { contentDescription = downloadRouteLabel(mode) + "，" + (if (running) "检测中，" else "") +
                (value?.let { "$it 毫秒" } ?: networkAutoResultLabel(result)) +
                (if (result?.confirming == true || result?.status == NetworkAutoRouteStatus.PARTIAL)
                    "，仅完成 ${result.samples} 次响应，尚未完整确认" else "") +
                if (chosen) "，待应用" else if (recommended == mode) "，建议调整" else "" },
        contentPadding = PaddingValues(horizontal = 3.dp, vertical = 3.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                if (showRoute) Text(downloadRouteLabel(mode), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, softWrap = false, color = colors.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.size(11.dp), contentAlignment = Alignment.Center) {
                        if (running) MotionBusyIndicator(Modifier.size(10.dp), strokeWidth = 1.2.dp)
                        else if (chosen) Icon(Icons.Outlined.Check, null, Modifier.size(11.dp), tint = colors.onSurface)
                        else if (result?.confirming == true || result?.status == NetworkAutoRouteStatus.PARTIAL)
                            Icon(Icons.Outlined.Info, "未完整确认", Modifier.size(10.dp), tint = colors.onSurfaceVariant)
                        else if (recommended == mode) Box(Modifier.size(4.dp).background(colors.onSurfaceVariant, RoundedCornerShape(50)))
                    }
                    if (value != null) {
                        Text(value.toString(), style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                            maxLines = 1, softWrap = false,
                            color = if (result.verified()) colors.onSurface else colors.onSurfaceVariant)
                        Text("ms", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false,
                            color = colors.onSurfaceVariant)
                    } else Text(networkAutoResultLabel(result), style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, softWrap = false,
                        color = if (result?.status == NetworkAutoRouteStatus.FAILED) colors.error else colors.onSurfaceVariant)
                }
            }
        }
    }
}

/** Full scope and evidence stay in the existing information PANEL, away from the scan/apply flow. */
@Composable
internal fun NetworkAutoEvidenceDetails(state: NetworkAutoCheckState, rowId: String?, receipt: String?) {
    val colors = MaterialTheme.colorScheme
    val context = LocalMangaContext.current
    val runtimes = remember(context) { PanelRuntimeStore.get(context) }
    val runtimeStates by runtimes.states.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.startedAtMillis?.let { Text("开始 · " + formatInstantMillis(it, "yyyy-MM-dd HH:mm"),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
        state.finishedAtMillis?.let { Text("结束 · " + formatInstantMillis(it, "yyyy-MM-dd HH:mm"),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
        if (state.historyOnly) Text("这是上次保留的检测记录，不能证明当前网络可用，也不能直接应用建议。",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        if (!state.historySaved) Text("检测记录未能写入本机；当前结果仍可查看，应用重启后可能无法恢复。",
            style = MaterialTheme.typography.bodySmall, color = colors.error)
        receipt?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
        state.staleReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
        Text("检测比较本次接口响应，不代表带宽、全部图片或公网出口。分类修改会影响跟随它的项目；进行中任务保留原线路。",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        val rows = if (rowId == null) state.rows else state.rows.filter { it.id == rowId }
        rows.forEach { row ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.label, style = MaterialTheme.typography.labelMedium)
                Text(row.coverage, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Text(row.reason, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                listOf(DownloadRouteMode.DIRECT to row.direct, DownloadRouteMode.PROXY to row.proxy).forEach { (mode, result) ->
                    Text(downloadRouteLabel(mode) + " · " + (result?.message ?: "尚未检测") +
                        (result?.millis?.let { " · $it ms" } ?: "") +
                        (result?.samples?.takeIf { it > 0 }?.let { " · $it 次" } ?: "") +
                        (result?.httpStatus?.let { " · HTTP $it" } ?: ""), style = MaterialTheme.typography.bodySmall,
                        color = if (result?.status == NetworkAutoRouteStatus.FAILED) colors.error else colors.onSurfaceVariant)
                }
                if (row.category == DownloadCategory.STEAM_WALLPAPERS) runtimeStates[PanelRuntimeId.STEAM]?.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error)
                }
            }
        }
    }
}

private fun networkAutoRowTitle(row: NetworkAutoRow): String = when (row.category) {
    DownloadCategory.STEAM_WALLPAPERS -> "Steam 壁纸"
    DownloadCategory.STEAM_COMPONENT -> "Steam 组件"
    DownloadCategory.SOURCE_PACKAGES -> "扩展源"
    DownloadCategory.WEBDAV -> "WebDAV"
    else -> row.label
}

private fun routeResult(row: NetworkAutoRow, mode: DownloadRouteMode): NetworkAutoRouteResult? = when (mode) {
    DownloadRouteMode.DIRECT -> row.direct; DownloadRouteMode.PROXY -> row.proxy; DownloadRouteMode.SYSTEM -> null
}
private fun NetworkAutoRouteResult?.verified(): Boolean = this != null && usable && samples == 2 && !confirming
private fun recommendationChange(row: NetworkAutoRow, current: DownloadRouteMode?): DownloadRouteMode? =
    row.recommendation?.takeIf { row.canApply && it != current && routeResult(row, it).verified() }
private fun steamNeedsPreparation(row: NetworkAutoRow, steam: PanelRuntimeState): Boolean =
    row.category == DownloadCategory.STEAM_WALLPAPERS && (!steam.installed || steam.requiresUpdate) &&
        listOfNotNull(row.direct, row.proxy).any { it.status == NetworkAutoRouteStatus.NOT_CONFIGURED &&
            "Steam" in it.message && "组件" in it.message }
private fun networkAutoRowReason(row: NetworkAutoRow, current: DownloadRouteMode?, steam: PanelRuntimeState): String = when {
    steamNeedsPreparation(row, steam) -> if (steam.requiresUpdate) "Steam 组件需要更新，原文件已保留" else "先准备 Steam 连接组件"
    row.direct.verified() && row.proxy.verified() -> recommendationChange(row, current)?.let { "建议${downloadRouteLabel(it)} · 本次响应更快" }
        ?: "两条线路均可用，保留当前选择"
    listOf(row.direct, row.proxy).any { it?.status == NetworkAutoRouteStatus.LOGIN_REQUIRED } -> "需要登录或通过站点验证后重测"
    listOf(row.direct, row.proxy).any { it?.httpStatus == 429 } -> "站点限流，稍后重新检测"
    listOf(row.direct, row.proxy).any { it?.status == NetworkAutoRouteStatus.UNSUPPORTED } -> "本次检测未覆盖此项，保留配置"
    row.direct == null && row.proxy == null -> "等待检测"
    else -> "直连 · ${networkAutoResultLabel(row.direct)}；代理 · ${networkAutoResultLabel(row.proxy)}"
}
private fun networkAutoResultLabel(result: NetworkAutoRouteResult?): String = when (result?.status) {
    null -> "待测"
    NetworkAutoRouteStatus.AVAILABLE -> "可用"
    NetworkAutoRouteStatus.NOT_CONFIGURED -> when {
        "组件" in result.message && "更新" in result.message -> "需更新"
        "组件" in result.message -> "需准备"
        else -> "未配置"
    }
    NetworkAutoRouteStatus.LOGIN_REQUIRED -> "需登录"
    NetworkAutoRouteStatus.UNSUPPORTED -> "未覆盖"
    NetworkAutoRouteStatus.CANCELLED -> "已取消"
    NetworkAutoRouteStatus.PARTIAL -> if (result.samples > 0) "${result.samples}次响应" else "未全测"
    NetworkAutoRouteStatus.FAILED -> when {
        result.httpStatus == 429 -> "限流"
        result.httpStatus == 401 || result.httpStatus == 403 -> "HTTP ${result.httpStatus}"
        "超时" in result.message -> "超时"
        "解析" in result.message -> "解析失败"
        "校验" in result.message -> "校验失败"
        else -> "失败"
    }
}
