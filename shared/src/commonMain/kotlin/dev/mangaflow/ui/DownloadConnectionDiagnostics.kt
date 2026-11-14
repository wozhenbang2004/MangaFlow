package dev.mangaflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.net.DownloadProxyType
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRoutingSettings
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.NetworkDiagnosis
import dev.mangaflow.net.NetworkProbeTarget
import dev.mangaflow.net.NetworkProbeResult
import dev.mangaflow.net.diagnoseNetwork
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.MotionTextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A user-owned check reuses the same stored endpoint and real download transport. */
@Composable
internal fun DownloadConnectionDiagnostics(store: DownloadRoutingStore, active: Boolean) {
    val context = LocalMangaContext.current
    val settings by store.state.collectAsStateWithLifecycle()
    val latestSettings by rememberUpdatedState(settings)
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val latestActive by rememberUpdatedState(active)
    var target by remember { mutableStateOf(NetworkProbeTarget.CATALOG) }
    var diagnosis by remember { mutableStateOf<NetworkDiagnosis?>(null) }
    var diagnosedSettings by remember { mutableStateOf<DownloadRoutingSettings?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf<DownloadRouteMode?>(null) }
    var showScope by remember { mutableStateOf(false) }

    fun invalidate(clearNotice: Boolean = true) {
        generation += 1
        job?.cancel()
        job = null
        diagnosis = null
        diagnosedSettings = null
        details = null
        if (clearNotice) notice = null
    }
    LaunchedEffect(settings) { invalidate(clearNotice = false) }
    LaunchedEffect(active) { if (!active) invalidate() }
    DisposableEffect(Unit) { onDispose { generation += 1; job?.cancel() } }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("服务响应", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant)
            MotionIconButton(onClick = { showScope = !showScope }, enabled = active,
                modifier = Modifier.size(44.dp), tooltip = if (showScope) "收起检查范围" else "检查范围") {
                Icon(Icons.Outlined.Info, if (showScope) "收起检查范围" else "检查范围",
                    Modifier.size(16.dp), tint = colors.onSurfaceVariant)
            }
        }
        // Adapt the existing settings disclosure: scope belongs on demand, not in every result row.
        MotionTabContent(showScope, Modifier.fillMaxWidth(), contentKey = { it },
            order = { if (it) 1 else 0 }, fillContent = false, soft = true) { expanded ->
            if (expanded) Text("仅在点击后请求所选服务，比较两次接口响应的平均耗时；不代表下载速度，也不识别外部软件或验证 VPN 绕行。",
                Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant)
        }
        MotionTabs(
            selected = target.ordinal,
            labels = listOf("拷贝", "GitHub"),
            onSelect = { index ->
                if (latestActive) {
                    val next = NetworkProbeTarget.entries[index]
                    if (next != target) { invalidate(); target = next }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            treatment = MotionTabTreatment.Soft, fullWidthIndicator = true, controlHeight = 36.dp,
            track = colors.onSurface.copy(alpha = .045f), indicator = colors.surface,
        )
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            if (job != null) {
                MotionBusyIndicator(Modifier.size(14.dp), strokeWidth = 1.4.dp)
                Text("比较连接方式…", Modifier.weight(1f).padding(start = 7.dp),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                MotionIconButton(onClick = { invalidate() }, enabled = active,
                    modifier = Modifier.size(44.dp), tooltip = "取消检查") {
                    Icon(Icons.Outlined.Close, "取消检查", Modifier.size(17.dp))
                }
            } else {
                Text(if (diagnosis == null) "手动检查所选服务" else "本次接口响应", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                MotionTextButton(enabled = active, contentPadding = PaddingValues(horizontal = 8.dp), onClick = {
                    if (!latestActive) return@MotionTextButton
                    invalidate()
                    val started = generation
                    val saved = settings
                    val selectedTarget = target
                    job = scope.launch(start = CoroutineStart.LAZY) {
                        try {
                            val result = diagnoseNetwork(context, selectedTarget, saved.proxy)
                            if (generation == started && latestSettings == saved && store.state.value == saved && latestActive &&
                                result.target == selectedTarget) {
                                diagnosedSettings = saved
                                diagnosis = result
                            }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            if (generation == started && latestActive) notice = "检查未完成，可重试"
                        } finally {
                            if (generation == started) job = null
                        }
                    }
                    job?.start()
                }) {
                    Icon(Icons.Outlined.PlayArrow, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (diagnosis == null) "检查" else "重新检查")
                }
            }
        }
        diagnosis?.let { result ->
            result.results.forEach { row ->
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(if (row.usable) Icons.Outlined.Check else Icons.Outlined.Info,
                            if (row.usable) "接口可用" else "查看检查结果",
                            Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                        Text(diagnosticRouteLabel(row.route), Modifier.weight(1.1f),
                            style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        Text(diagnosticResultLabel(row), Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
                        MotionIconButton(onClick = { details = if (details == row.route.mode) null else row.route.mode },
                            enabled = active, modifier = Modifier.size(44.dp),
                            tooltip = if (details == row.route.mode) "收起连接详情" else "连接详情") {
                            Icon(if (details == row.route.mode) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                if (details == row.route.mode) "收起连接详情" else "连接详情", Modifier.size(16.dp),
                                tint = colors.onSurfaceVariant)
                        }
                    }
                    MotionTabContent(details == row.route.mode, Modifier.fillMaxWidth(), contentKey = { it },
                        order = { if (it) 1 else 0 }, fillContent = false, soft = true) { expanded ->
                        if (expanded) Column(Modifier.padding(start = 24.dp, end = 8.dp, bottom = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(row.message, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            row.route.proxy?.let { endpoint ->
                                val host = if (':' in endpoint.host) "[" + endpoint.host + "]" else endpoint.host
                                Text(host + ":" + endpoint.port, style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            result.recommended?.let { recommended ->
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("建议 · " + diagnosticRouteLabel(recommended), Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (recommended.mode == settings.defaultMode) {
                        Icon(Icons.Outlined.Check, null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                        Text("当前默认", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant)
                    } else MotionTextButton(enabled = active && job == null, onClick = {
                        if (!latestActive) return@MotionTextButton
                        if (store.state.value != diagnosedSettings) {
                            invalidate()
                            notice = "连接设置已变化，请重新检查"
                            return@MotionTextButton
                        }
                        try {
                            store.setDefault(recommended.mode)
                            diagnosis = null
                            notice = "默认已更新，分类与进行中的下载保留原设置"
                        } catch (failure: Exception) {
                            notice = failure.message ?: "暂时无法保存连接设置"
                        }
                    }) { Text("设为默认") }
                }
            } ?: Text("本次未找到可用连接", style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant)
        }
        if (settings.proxy == null) Row(Modifier.fillMaxWidth().heightIn(min = 32.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Remove, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
            Text("指定代理未参与 · 尚未配置地址", style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant)
        }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
    }
}

private fun diagnosticRouteLabel(route: DownloadRouteSnapshot): String = when (route.mode) {
    DownloadRouteMode.SYSTEM -> downloadRouteLabel(route.mode)
    DownloadRouteMode.DIRECT -> downloadRouteLabel(route.mode)
    DownloadRouteMode.PROXY -> if (route.proxy?.type == DownloadProxyType.SOCKS) "SOCKS5 代理" else "HTTP 代理"
}


/** Summaries preserve the difference between a remote rejection and a transport failure. */
private fun diagnosticResultLabel(result: NetworkProbeResult): String = when {
    result.usable && result.millis != null -> result.millis.toString() + " ms"
    result.usable -> "接口可用"
    "超时" in result.message -> "连接超时"
    "拒绝访问" in result.message -> "已响应 · 拒绝访问"
    "限流" in result.message -> "已响应 · 限流"
    "需要跳转" in result.message -> "已响应 · 需跳转"
    "内容与接口不符" in result.message -> "已响应 · 内容不符"
    "不支持" in result.message -> "此方式不支持"
    "代理" in result.message && listOf("配置", "填写", "端口", "地址", "主机").any { it in result.message } -> "检查代理配置"
    result.message.startsWith("服务返回 HTTP") -> result.message.removePrefix("服务返回 ")
    else -> "未能连接"
}
