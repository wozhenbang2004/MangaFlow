package dev.mangaflow.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.net.*
import dev.mangaflow.ui.motion.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** One explicit run produces a read-only review; only its selected, measured differences can be saved. */
@Composable
internal fun NetworkAutoCheckWorkspace(controller: NetworkAutoCheckController, active: Boolean) {
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val latestActive by rememberUpdatedState(active)
    val colors = MaterialTheme.colorScheme
    val list = rememberLazyListState()
    var selected by remember(controller) { mutableStateOf<Map<String, DownloadRouteMode>>(emptyMap()) }
    var appliedChoices by remember(controller) { mutableStateOf<Map<String, DownloadRouteMode>>(emptyMap()) }
    var expandedId by remember(controller) { mutableStateOf<String?>(null) }
    var notice by remember(controller) { mutableStateOf<String?>(null) }
    var saving by remember(controller) { mutableStateOf(false) }
    var starting by remember(controller) { mutableStateOf(false) }
    val canReview = state.complete && !state.applied && !state.stale && !state.running && !state.applying && !saving
    val applicable = selected.filter { (id, mode) ->
        state.rows.firstOrNull { it.id == id }?.let { row ->
            routeResult(row, mode).verified() && mode != (appliedChoices[id] ?: row.currentRoute?.mode)
        } == true
    }
    LaunchedEffect(controller, state.running, state.complete) {
        if (state.running) {
            selected = emptyMap(); appliedChoices = emptyMap(); expandedId = null; notice = null
        } else if (state.complete && !state.stale) {
            selected = state.rows.mapNotNull { row -> row.recommendation?.takeIf { mode ->
                routeResult(row, mode).verified() && mode != row.currentRoute?.mode
            }?.let { row.id to it } }.toMap()
            list.animateScrollToItem(0)
        }
    }
    fun run() {
        if (!latestActive || state.running || starting || saving || state.applying) return
        starting = true
        notice = null
        scope.launch {
            try { controller.run() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { notice = "检测未完成，可重新检测" }
            finally { starting = false }
        }
    }
    fun choose(row: NetworkAutoRow, mode: DownloadRouteMode) {
        if (!latestActive || !canReview || !routeResult(row, mode).verified()) return
        val current = appliedChoices[row.id] ?: row.currentRoute?.mode
        selected = if (mode == current) selected - row.id else selected + (row.id to mode)
        notice = null
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.running || starting) MotionBusyIndicator(Modifier.size(15.dp), strokeWidth = 1.4.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(when {
                    state.stale -> "结果已过期"
                    state.running || starting -> if (state.total > 0) "检测 ${state.completed}/${state.total}" else "准备检测项目"
                    state.applied -> "设置已保存"
                    state.complete -> "建议 ${state.rows.count { it.recommendation != null }} · 共 ${state.rows.size} 项"
                    state.rows.isNotEmpty() -> "检测已停止"
                    else -> "直连与代理分别检测"
                }, style = MaterialTheme.typography.labelMedium)
                Text(when {
                    state.stale -> state.staleReason ?: "配置或网络已变化，请重新检测"
                    state.running || starting -> "检测不修改配置"
                    state.applied -> "本次建议已处理，可重新检测"
                    state.complete -> if (applicable.isEmpty()) "保留当前设置" else "已选 ${applicable.size} 项调整"
                    state.rows.isNotEmpty() -> "保留已完成结果，未完成项不参与修改"
                    else -> "漫画源与下载服务 · 完成后逐项确认"
                }, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (state.running || starting) MotionIconButton(onClick = controller::cancel,
                enabled = active && !state.applying, modifier = Modifier.size(44.dp), tooltip = "取消检测") {
                Icon(Icons.Outlined.Close, "取消检测", Modifier.size(18.dp))
            } else MotionTextButton(onClick = ::run, enabled = active && !saving && !state.applying,
                contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(if (state.rows.isEmpty()) Icons.Outlined.PlayArrow else Icons.Outlined.Refresh,
                    null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (state.rows.isEmpty()) "一键检测" else "重新检测", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (state.rows.isNotEmpty()) {
            val groups = state.rows.groupBy { if (it.sourceId != null) "漫画源" else "下载与服务" }
            LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), state = list,
                contentPadding = PaddingValues(bottom = 4.dp)) {
                groups.forEach { (group, rows) ->
                    item("group-$group") { NetworkAutoGroupHeading("$group · ${rows.size}") }
                    items(rows, key = { it.id }) { row ->
                        val current = appliedChoices[row.id] ?: row.currentRoute?.mode
                        NetworkAutoCheckRow(row, current, selected[row.id],
                            stale = state.stale, enabled = active && canReview,
                            applied = row.id in appliedChoices, expanded = expandedId == row.id,
                            directRunning = "${row.id}:DIRECT" in state.activeProbeIds,
                            proxyRunning = "${row.id}:PROXY" in state.activeProbeIds,
                            onDetails = { expandedId = if (expandedId == row.id) null else row.id },
                            onToggle = {
                                if (row.id in selected) selected = selected - row.id
                                else row.recommendation?.let { choose(row, it) }
                            }, onChoose = { choose(row, it) })
                    }
                }
            }
        }
        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
        notice?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
        if (state.rows.isNotEmpty()) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (state.stale) "重新检测后可应用" else if (state.applied) "进行中任务保留原线路"
                else if (state.complete) "分类调整会影响跟随它的项目" else "尚未生成可应用建议",
                Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            MotionButton(onClick = {
                if (!latestActive || !canReview || applicable.isEmpty()) return@MotionButton
                val chosen = applicable.toMap()
                saving = true
                notice = null
                scope.launch {
                    try {
                        val result = controller.apply(chosen)
                        notice = result.message
                        if (result.success) {
                            appliedChoices = appliedChoices + chosen
                            selected = selected - chosen.keys
                        }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { notice = "设置未能更新，请重试" }
                    finally { saving = false }
                }
            }, enabled = active && canReview && applicable.isNotEmpty(), contentPadding = PaddingValues(horizontal = 14.dp)) {
                if (saving || state.applying) {
                    MotionBusyIndicator(Modifier.size(14.dp), strokeWidth = 1.4.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (saving || state.applying) "正在应用" else if (state.applied) "已保存" else "应用 ${applicable.size} 项")
            }
        }
    }
}

@Composable
private fun NetworkAutoGroupHeading(label: String) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 300.dp * LocalMangaWindow.current.fontScale.coerceAtLeast(1f)
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant)
            if (!stacked) {
                Text("直连", Modifier.width(58.dp), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Text("代理", Modifier.width(58.dp), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Spacer(Modifier.width(44.dp))
            }
        }
    }
}

@Composable
private fun NetworkAutoCheckRow(
    row: NetworkAutoRow, current: DownloadRouteMode?, selected: DownloadRouteMode?, stale: Boolean,
    enabled: Boolean, applied: Boolean, expanded: Boolean, directRunning: Boolean, proxyRunning: Boolean,
    onDetails: () -> Unit, onToggle: () -> Unit,
    onChoose: (DownloadRouteMode) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val recommended = row.recommendation?.takeIf { routeResult(row, it).verified() && it != current }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 300.dp * LocalMangaWindow.current.fontScale.coerceAtLeast(1f)
        Column(Modifier.fillMaxWidth().alpha(if (stale) .58f else 1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                MotionIconButton(onClick = onToggle, enabled = enabled && (selected != null || recommended != null),
                    modifier = Modifier.size(44.dp), tooltip = if (selected != null) "保留${row.label}当前设置" else "选择${row.label}建议") {
                    Icon(if (selected != null) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                        if (selected != null) "已选${row.label}" else "未选${row.label}", Modifier.size(17.dp),
                        tint = colors.onSurfaceVariant)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(row.label, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text((if (stale) "原设置 · " else if (applied) "已更新 · " else "当前 · ") +
                        (current?.let(::downloadRouteLabel) ?: "继承默认"), style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!stacked) {
                    NetworkAutoRouteCell(row.direct, DownloadRouteMode.DIRECT, selected, recommended,
                        enabled, false, directRunning, onChoose)
                    NetworkAutoRouteCell(row.proxy, DownloadRouteMode.PROXY, selected, recommended,
                        enabled, false, proxyRunning, onChoose)
                }
                MotionIconButton(onClick = onDetails, modifier = Modifier.size(44.dp),
                    tooltip = if (expanded) "收起${row.label}详情" else "${row.label}检测详情") {
                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        if (expanded) "收起详情" else "检测详情", Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                }
            }
            if (stacked) Row(Modifier.fillMaxWidth().padding(start = 44.dp, end = 44.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NetworkAutoRouteCell(row.direct, DownloadRouteMode.DIRECT, selected, recommended,
                    enabled, true, directRunning, onChoose, Modifier.weight(1f))
                NetworkAutoRouteCell(row.proxy, DownloadRouteMode.PROXY, selected, recommended,
                    enabled, true, proxyRunning, onChoose, Modifier.weight(1f))
            }
            CopyShelfReveal(expanded) {
                Column(Modifier.fillMaxWidth().padding(start = 44.dp, end = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.coverage, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    Text(row.reason, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    listOf(DownloadRouteMode.DIRECT to row.direct, DownloadRouteMode.PROXY to row.proxy).forEach { (mode, result) ->
                        Text(downloadRouteLabel(mode) + " · " + (result?.message ?: "尚未检测") +
                            (result?.samples?.takeIf { it > 0 }?.let { " · $it 次" } ?: "") +
                            (result?.httpStatus?.let { " · HTTP $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (result?.status == NetworkAutoRouteStatus.FAILED) colors.error else colors.onSurfaceVariant)
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
    onChoose: (DownloadRouteMode) -> Unit, modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val chosen = selected == mode
    MotionTextButton(onClick = { onChoose(mode) }, enabled = enabled && result.verified(),
        modifier = modifier.then(if (showRoute) Modifier else Modifier.width(58.dp)).heightIn(min = 44.dp)
            .background(if (chosen) colors.onSurface.copy(alpha = .055f) else colors.surface.copy(alpha = 0f),
                RoundedCornerShape(10.dp)).semantics {
                contentDescription = downloadRouteLabel(mode) + "，" + (if (running) "正在检测" else networkAutoResultLabel(result)) +
                    if (chosen) "，待应用" else if (recommended == mode) "，建议" else ""
            }, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 3.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            if (showRoute) Text(downloadRouteLabel(mode), style = MaterialTheme.typography.labelSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (running) MotionBusyIndicator(Modifier.size(13.dp), strokeWidth = 1.3.dp)
                else if (chosen) Icon(Icons.Outlined.Check, null, Modifier.size(11.dp), tint = colors.onSurface)
                Text(if (running) "检测中" else networkAutoResultLabel(result), style = MaterialTheme.typography.labelSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    color = if (result?.status == NetworkAutoRouteStatus.FAILED) colors.error else colors.onSurfaceVariant)
            }
            // Keep the same actual text line before and after a recommendation arrives.
            // The cell already announces its recommendation in its combined description.
            Text(if (recommended == mode) "建议" else "", Modifier.clearAndSetSemantics { },
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                minLines = 1, maxLines = 1)
        }
    }
}

private fun routeResult(row: NetworkAutoRow, mode: DownloadRouteMode): NetworkAutoRouteResult? = when (mode) {
    DownloadRouteMode.DIRECT -> row.direct
    DownloadRouteMode.PROXY -> row.proxy
    DownloadRouteMode.SYSTEM -> null
}
private fun NetworkAutoRouteResult?.verified(): Boolean = this != null && usable && samples == 2
private fun networkAutoResultLabel(result: NetworkAutoRouteResult?): String = when (result?.status) {
    null -> "待测"
    NetworkAutoRouteStatus.AVAILABLE -> result.millis?.let { "${it}ms" } ?: "可用"
    NetworkAutoRouteStatus.NOT_CONFIGURED -> "未配置"
    NetworkAutoRouteStatus.LOGIN_REQUIRED -> "需登录"
    NetworkAutoRouteStatus.UNSUPPORTED -> "未覆盖"
    NetworkAutoRouteStatus.CANCELLED -> "已取消"
    NetworkAutoRouteStatus.PARTIAL -> "部分响应"
    NetworkAutoRouteStatus.FAILED -> when {
        "超时" in result.message -> "超时"
        result.httpStatus == 429 -> "限流"
        result.httpStatus == 401 || result.httpStatus == 403 -> "拒绝访问"
        else -> "失败"
    }
}
