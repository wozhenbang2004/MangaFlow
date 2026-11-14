package dev.mangaflow.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.data.SourceException
import dev.mangaflow.data.UnifiedMangaRepository
import dev.mangaflow.data.sources.BodySourceInfo
import dev.mangaflow.net.*
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.ui.motion.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

private data class SourceConnectionResult(
    val source: BodySourceInfo,
    val route: DownloadRouteSnapshot?,
    val running: Boolean = false,
    val complete: Boolean = false,
    val usable: Boolean = false,
    val message: String = "等待检查",
    val millis: Long? = null,
)

/** Explicit, bounded catalogue checks use the same frozen source route as real browsing. */
@Composable
internal fun SourceConnectionChecks(
    repository: UnifiedMangaRepository,
    sources: List<BodySourceInfo>,
    active: Boolean = true,
    animateDetails: Boolean = true,
) {
    val context = LocalMangaContext.current
    val store = remember(context) { DownloadRoutingStore.get(context) }
    val settings by store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val latestActive by rememberUpdatedState(active)
    val targets = sources.distinctBy { it.id }
    val sourceKey = targets.joinToString("|") { it.id + ":" + it.revision }
    val latestSourceKey by rememberUpdatedState(sourceKey)
    val configuration = repository.sourceConfigurationKey()
    var rows by remember(repository) { mutableStateOf<List<SourceConnectionResult>>(emptyList()) }
    var checkedSettings by remember(repository) { mutableStateOf<DownloadRoutingSettings?>(null) }
    var checkedSources by remember(repository) { mutableStateOf<String?>(null) }
    var checkedConfiguration by remember(repository) { mutableStateOf<String?>(null) }
    var job by remember(repository) { mutableStateOf<Job?>(null) }
    var generation by remember(repository) { mutableIntStateOf(0) }
    var expanded by remember(repository) { mutableStateOf(false) }
    var showAll by remember(repository) { mutableStateOf(false) }
    var notice by remember(repository) { mutableStateOf<String?>(null) }
    val motion = LocalMotionEnabled.current
    val detailsAngle by animateFloatAsState(if (expanded) 180f else 0f,
        tween(if (motion) MangaMotion.Content else 0, easing = MangaMotion.EnterEasing), label = "source-check-disclosure")

    fun cancel(clear: Boolean) {
        generation++
        job?.cancel()
        job = null
        rows = if (clear) emptyList() else rows.map { if (it.complete) it else it.copy(running = false, message = "已取消") }
        if (clear) { checkedSettings = null; checkedSources = null; checkedConfiguration = null; showAll = false }
    }
    LaunchedEffect(settings, sourceKey, configuration) {
        if (checkedSettings != null && (checkedSettings !== store.state.value ||
            checkedSources != sourceKey || checkedConfiguration != configuration)) {
            cancel(clear = true)
            notice = "设置或来源已变化，请重新检查"
        }
    }
    LaunchedEffect(active) { if (!active) cancel(clear = false) }
    DisposableEffect(repository) { onDispose { generation++; job?.cancel() } }

    fun start(selected: List<BodySourceInfo>, replace: Boolean) {
        if (!latestActive || selected.isEmpty() || job != null) return
        val capturedSettings = store.state.value
        val capturedSources = sourceKey
        val capturedConfiguration = repository.sourceConfigurationKey()
        val sameEvidence = checkedSettings === capturedSettings && checkedSources == capturedSources &&
            checkedConfiguration == capturedConfiguration
        cancel(clear = replace || !sameEvidence)
        val run = generation
        val queued = selected.map { source ->
            SourceConnectionResult(source, runCatching { store.sourceSnapshot(source.id) }.getOrNull())
        }
        rows = if (replace || !sameEvidence) queued else rows.filter { row -> selected.none { it.id == row.source.id } } + queued
        checkedSettings = capturedSettings
        checkedSources = capturedSources
        checkedConfiguration = capturedConfiguration
        notice = null
        fun current(): Boolean = generation == run && latestActive &&
            store.state.value === capturedSettings && latestSourceKey == capturedSources &&
            repository.sourceConfigurationKey() == capturedConfiguration
        fun publish(row: SourceConnectionResult) {
            if (current()) rows = rows.map { if (it.source.id == row.source.id) row else it }
        }
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val gate = Semaphore(2)
                coroutineScope {
                    queued.map { queuedRow -> async {
                        gate.withPermit {
                            if (!current()) return@withPermit
                            val route = queuedRow.route
                            if (route == null) {
                                publish(queuedRow.copy(complete = true, message = "线路配置不可用"))
                                return@withPermit
                            }
                            val started = monotonicTimeMillis()
                            publish(queuedRow.copy(running = true, message = "正在读取目录"))
                            val result = try {
                                withTimeout(25_000) { repository.sourceProbe(queuedRow.source.id, route) }
                                queuedRow.copy(complete = true, usable = true, message = "目录可用")
                            } catch (_: TimeoutCancellationException) {
                                queuedRow.copy(complete = true, message = "目录检查超时")
                            } catch (cancel: CancellationException) {
                                if (!current() || !currentCoroutineContext().isActive) throw cancel
                                queuedRow.copy(complete = true, message = "来源已停止本次检查")
                            } catch (failure: Exception) {
                                queuedRow.copy(complete = true, message = sourceConnectionFailure(failure))
                            }
                            publish(result.copy(millis = (monotonicTimeMillis() - started).coerceAtLeast(0)))
                        }
                    } }.awaitAll()
                }
            } finally {
                if (generation == run) {
                    job = null
                    if (!current()) notice = "设置或来源已变化，请重新检查"
                    else rows = rows.map { if (it.complete) it else it.copy(running = false, message = "检查已停止") }
                }
            }
        }
        job?.start()
    }

    val valid = checkedSettings === store.state.value && checkedSources == sourceKey &&
        checkedConfiguration == repository.sourceConfigurationKey()
    val shown = if (valid) rows.sortedBy { row -> targets.indexOfFirst { it.id == row.source.id }.let { if (it < 0) Int.MAX_VALUE else it } } else emptyList()
    val completed = shown.count { it.complete }
    val succeeded = shown.count { it.complete && it.usable }
    val failed = shown.count { it.complete && !it.usable }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (targets.size == 1) "当前线路检查" else "各源连接", style = MaterialTheme.typography.labelMedium)
                Text(if (shown.isEmpty()) "按各源设置 · 仅检查目录" else completed.toString() + "/" + shown.size +
                    " · 可用 " + succeeded + " · 失败 " + failed,
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (job != null) {
                MotionBusyIndicator(Modifier.size(14.dp), strokeWidth = 1.4.dp)
                MotionIconButton(onClick = { cancel(clear = false) }, enabled = active,
                    modifier = Modifier.size(44.dp), tooltip = "取消来源检查") {
                    Icon(Icons.Outlined.Close, "取消来源检查", Modifier.size(17.dp))
                }
            } else MotionTextButton(onClick = { start(targets, replace = true) }, enabled = active && targets.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (targets.size == 1) "检查目录" else "一键检查", style = MaterialTheme.typography.labelMedium)
            }
            if (shown.isNotEmpty()) MotionIconButton(onClick = { expanded = !expanded }, enabled = active,
                modifier = Modifier.size(44.dp), tooltip = if (expanded) "收起各源结果" else "查看各源结果") {
                Icon(Icons.Outlined.ExpandMore, if (expanded) "收起各源结果" else "查看各源结果",
                    Modifier.size(16.dp).graphicsLayer { rotationZ = detailsAngle })
            }
        }
        val detailsContent: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            (if (showAll) shown else shown.take(4)).forEach { row ->
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                        if (row.running) MotionBusyIndicator(Modifier.size(13.dp), strokeWidth = 1.4.dp)
                        else Icon(if (row.usable) Icons.Outlined.Check else Icons.Outlined.Info, null, Modifier.size(15.dp),
                            tint = if (row.complete && !row.usable) colors.error else colors.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(row.source.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(row.route?.let { downloadRouteLabel(it.mode) }, row.message,
                            row.millis?.let { it.toString() + " ms" }).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    if (!row.running) MotionIconButton(onClick = { start(listOf(row.source), replace = false) },
                        enabled = active && job == null, modifier = Modifier.size(44.dp), tooltip = "重新检查" + row.source.name) {
                        Icon(Icons.Outlined.Refresh, "重新检查" + row.source.name, Modifier.size(16.dp))
                    }
                }
            }
            if (shown.size > 4) MotionTextButton(onClick = { showAll = !showAll }, enabled = active) {
                Text(if (showAll) "收起其余结果" else "其余 " + (shown.size - 4) + " 个来源", style = MaterialTheme.typography.labelSmall)
            }
            Text("目录结果不证明图片 CDN 可用，也不代表下载速度。", style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant)
            }
        }
        if (animateDetails) CopyShelfReveal(expanded && shown.isNotEmpty()) { detailsContent() }
        else if (expanded && shown.isNotEmpty()) detailsContent()
        if (!valid && rows.isNotEmpty()) Text("设置或来源已变化，请重新检查",
            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        else notice?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
    }
}

/** Only allowlisted summaries reach the UI; arbitrary source URLs, bodies and secrets stay private. */
private fun sourceConnectionFailure(failure: Exception): String {
    val status = (failure as? SourceException)?.httpStatus
    val message = failure.message.orEmpty()
    return when {
        status == 401 || status == 403 -> "站点拒绝访问，可能需要登录或验证"
        status == 429 -> "站点限制请求，请稍后重试"
        status == 503 -> "站点暂时繁忙"
        status != null -> "目录响应 HTTP " + status
        "HTTP" in message && ("不支持" in message || "支持 SOCKS" in message) -> "此来源不支持 HTTP 代理，请选择 SOCKS5"
        failure is DownloadRouteException -> "此线路配置不可用"
        networkFailureKind(failure) == NetworkFailureKind.TRANSFER_TIMEOUT ||
            networkFailureKind(failure) == NetworkFailureKind.REQUEST_TIMEOUT -> "目录响应超时"
        networkFailureKind(failure) != null -> requireNotNull(networkFailureKind(failure)).message
        "登录" in message -> "需要登录后检查目录"
        "不支持" in message || "不兼容" in message -> "此来源暂不支持当前检查方式"
        else -> "目录读取失败，可重试或检查来源设置"
    }
}
