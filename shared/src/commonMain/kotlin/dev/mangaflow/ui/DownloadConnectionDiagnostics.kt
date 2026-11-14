package dev.mangaflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.net.DownloadProxyType
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRoutingSettings
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.NetworkDiagnosis
import dev.mangaflow.net.NetworkProbeTarget
import dev.mangaflow.net.diagnoseNetwork
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.ui.motion.MotionTabs
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
    var expanded by remember { mutableStateOf(false) }
    val latestActive by rememberUpdatedState(active && expanded)
    var target by remember { mutableStateOf(NetworkProbeTarget.CATALOG) }
    var diagnosis by remember { mutableStateOf<NetworkDiagnosis?>(null) }
    var diagnosedSettings by remember { mutableStateOf<DownloadRoutingSettings?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun invalidate(clearNotice: Boolean = true) {
        generation += 1
        job?.cancel()
        job = null
        diagnosis = null
        diagnosedSettings = null
        if (clearNotice) notice = null
    }
    LaunchedEffect(settings) { invalidate(clearNotice = false) }
    LaunchedEffect(active, expanded) { if (!active || !expanded) invalidate() }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    RouteDisclosure("检测连接", if (job != null) "正在检测…" else "比较并推荐", expanded) {
        invalidate()
        expanded = !expanded
    }
    if (expanded) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MotionTabs(
                selected = target.ordinal,
                labels = listOf("漫画接口", "更新服务"),
                onSelect = { index ->
                    val next = NetworkProbeTarget.entries[index]
                    if (next != target) { invalidate(); target = next }
                },
                modifier = Modifier.fillMaxWidth(),
                treatment = MotionTabTreatment.Soft, fullWidthIndicator = true, controlHeight = 40.dp,
                track = colors.onSurface.copy(alpha = .045f), indicator = colors.surface,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                if (job != null) MotionTextButton(onClick = { invalidate() }) { Text("取消") }
                MotionTextButton(enabled = active && job == null, onClick = {
                    invalidate()
                    val started = generation
                    val saved = settings
                    val selectedTarget = target
                    job = scope.launch(start = CoroutineStart.LAZY) {
                        try {
                            val result = diagnoseNetwork(context, selectedTarget, saved.proxy)
                            if (generation == started && latestSettings == saved && store.state.value == saved && latestActive) {
                                diagnosedSettings = saved
                                diagnosis = result
                            }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            if (generation == started && latestActive) notice = "检测未完成，可重试"
                        } finally {
                            if (generation == started) job = null
                        }
                    }
                    job?.start()
                }) { Text(if (job != null) "正在检测…" else "检测并推荐") }
            }
            diagnosis?.let { result ->
                result.results.forEach { row ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(diagnosticRouteLabel(row.route), Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium)
                        Text(if (row.usable && row.millis != null) row.millis.toString() + " ms" else row.message,
                            Modifier.weight(1.5f), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                result.recommended?.let { recommended ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("建议：" + diagnosticRouteLabel(recommended), Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        MotionTextButton(enabled = active && job == null, onClick = {
                            if (store.state.value != diagnosedSettings) {
                                invalidate()
                                notice = "线路设置已变化，请重新检测"
                                return@MotionTextButton
                            }
                            try {
                                store.setDefault(recommended.mode)
                                diagnosis = null
                                notice = "已设为后续下载的默认线路，分类设置保留"
                            } catch (failure: Exception) {
                                notice = failure.message ?: "暂时无法保存线路"
                            }
                        }) { Text("用于下载默认") }
                    }
                } ?: Text("当前目标未找到可用线路", style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
                Text("两次接口响应的平均耗时，不代表下载带宽。此检测不验证 Steam 连接或是否已绕过 VPN。",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
            if (settings.proxy == null) {
                Text("保存代理地址后，可一同比较指定代理。", style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
            }
        }
    }
}

private fun diagnosticRouteLabel(route: DownloadRouteSnapshot): String = when (route.mode) {
    DownloadRouteMode.SYSTEM -> "系统网络"
    DownloadRouteMode.DIRECT -> "应用直连"
    DownloadRouteMode.PROXY -> if (route.proxy?.type == DownloadProxyType.SOCKS) "SOCKS5 代理" else "HTTP 代理"
}
