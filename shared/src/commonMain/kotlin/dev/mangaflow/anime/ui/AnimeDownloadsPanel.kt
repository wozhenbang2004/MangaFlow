package dev.mangaflow.anime.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.mangaflow.enhancement.VideoEnhancementVersion
import dev.mangaflow.enhancement.EnhancementTaskPhase
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.anime.downloads.*
import dev.mangaflow.ui.*
import dev.mangaflow.ui.motion.*

internal data class AnimeDownloadSelection(
    val downloadId: String,
    val versionId: String? = null,
    val originalOnly: Boolean = false,
)

@Composable
internal fun AnimeDownloadsPanel(visible: Boolean, controller: AnimeController, state: AnimeScreenState,
    backdrop: HazeState, onDismiss: () -> Unit, onPlay: (AnimeDownloadSelection) -> Unit) {
    var pendingPlayback by remember { mutableStateOf<AnimeDownloadSelection?>(null) }
    LaunchedEffect(visible) { if (visible) pendingPlayback = null }
    MotionDialog(visible, onDismissRequest = onDismiss, maximumWidth = 640.dp, onClosed = {
        val request = pendingPlayback
        pendingPlayback = null
        if (request != null) onPlay(request)
    }) {
        AnimePanelSurface(backdrop) {
            Column(Modifier.fillMaxWidth().heightIn(max = LocalMangaWindow.current.panelHeight)) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("离线下载", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭离线下载") {
                        Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp))
                    }
                }
                AnimeDownloadsContent(controller, state, backdrop,
                    onPlay = { pendingPlayback = it; onDismiss() }, modifier = Modifier.weight(1f, fill = false))
            }
        }
    }
}

/** Shared by library and player settings, keeping actions in the panel that owns the task. */
@Composable
internal fun AnimeDownloadsContent(controller: AnimeController, screen: AnimeScreenState, backdrop: HazeState,
    onPlay: (AnimeDownloadSelection) -> Unit, modifier: Modifier = Modifier) {
    val downloads by controller.downloads.state.collectAsStateWithLifecycle()
    val enhancement by controller.enhancements.state.collectAsStateWithLifecycle()
    var filter by remember { mutableIntStateOf(0) }
    var removing by remember { mutableStateOf<String?>(null) }
    val removal = downloads.entries.firstOrNull { it.id == removing }
    val rows = downloads.entries.filter { when (filter) {
        1 -> it.status != AnimeDownloadStatus.COMPLETED
        2 -> it.status == AnimeDownloadStatus.COMPLETED
        else -> true
    } }.sortedByDescending { it.createdAt }
    Column(modifier.fillMaxWidth()) {
        if (removal != null) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(removal.subject.title, style = MaterialTheme.typography.titleSmall)
                AnimeQuietLabel(animeEpisodeLabel(removal.episode) + " · " + removal.episode.title)
                Text("移除这集的下载任务与本地视频。收藏和观看记录保留。", style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MotionTextButton(onClick = { removing = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
                    MotionButton(onClick = { controller.removeDownload(removal.id); removing = null },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("移除下载") }
                }
            }
        } else {
            MotionTabs(filter, listOf("全部", "未完成", "已完成"), onSelect = { filter = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), controlHeight = 44.dp,
                treatment = MotionTabTreatment.Soft)
            LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { AnimeQuietLabel(when {
                    controller.downloads.capabilities.systemBackgroundExecution -> "Android 可在后台继续下载，系统暂停时会等待恢复。"
                    controller.downloads.capabilities.processBackgroundExecution -> "窗口切到后台可继续下载，退出应用后可在下次手动继续。"
                    else -> "返回应用后继续下载。"
                }) }
                if (downloads.notice != null) item { AnimeQuietLabel(downloads.notice.orEmpty()) }
                if (enhancement.notice != null) item { AnimeQuietLabel(enhancement.notice.orEmpty()) }
                if (rows.any { it.status == AnimeDownloadStatus.COMPLETED }) item {
                    MotionTextButton(onClick = { rows.filter { it.status == AnimeDownloadStatus.COMPLETED }
                        .forEach { controller.enhanceDownload(it.id) } }) {
                        Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text("增强已下载剧集")
                    }
                }
                if (downloads.loading && rows.isEmpty()) item { AnimeMessage("正在读取下载任务") }
                else if (rows.isEmpty()) item { AnimeMessage(if (downloads.entries.isEmpty()) "准备离线看番" else "这里暂无下载",
                    if (downloads.entries.isEmpty()) "在播放设置中选择“下载当前集”，保留已确认的版本与来源。" else null) }
                rows.groupBy { it.subject.id }.forEach { (subjectId, episodes) ->
                    item(key = "work:$subjectId") {
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AnimePoster(episodes.first().subject, Modifier.width(46.dp).height(66.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(episodes.first().subject.title, style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                AnimeQuietLabel("${episodes.size} 集 · ${episodes.count { it.status == AnimeDownloadStatus.COMPLETED }} 集已完成")
                            }
                        }
                    }
                    items(episodes, key = { it.id }) { entry ->
                        AnimeDownloadRow(entry, screen, backdrop, onPlay = { onPlay(AnimeDownloadSelection(entry.id)) },
                            onPause = { controller.pauseDownload(entry.id) }, onResume = { controller.resumeDownload(entry.id) },
                            onRemove = { removing = entry.id },
                            versions = enhancement.entries.filter { it.downloadId == entry.id },
                            onEnhance = { controller.enhanceDownload(entry.id) },
                            onOriginal = { onPlay(AnimeDownloadSelection(entry.id, originalOnly = true)) },
                            onPlayVersion = { onPlay(AnimeDownloadSelection(entry.id, versionId = it)) },
                            onPauseVersion = controller.enhancements::pause, onResumeVersion = controller.enhancements::resume,
                            onCancelVersion = controller.enhancements::cancel,
                            onRemoveVersion = { controller.enhancements.removeVersion(it) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimeDownloadRow(entry: AnimeDownloadEntry, screen: AnimeScreenState, backdrop: HazeState,
    onPlay: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onRemove: () -> Unit,
    versions: List<VideoEnhancementVersion>, onEnhance: () -> Unit, onOriginal: () -> Unit,
    onPlayVersion: (String) -> Unit, onPauseVersion: (String) -> Unit, onResumeVersion: (String) -> Unit,
    onCancelVersion: (String) -> Unit, onRemoveVersion: (String) -> Unit) {
    var menu by remember(entry.id) { mutableStateOf(false) }
    var menuPresent by remember(entry.id) { mutableStateOf(false) }
    var removeRequested by remember(entry.id) { mutableStateOf(false) }
    var originalRequested by remember(entry.id) { mutableStateOf(false) }
    val active = entry.status in setOf(AnimeDownloadStatus.DOWNLOADING, AnimeDownloadStatus.QUEUED, AnimeDownloadStatus.WAITING)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(listOfNotNull(animeEpisodeLabel(entry.episode), animeEpisodeSubtitle(entry.episode)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                AnimeQuietLabel(listOf(animeDownloadStatus(entry.status), animeSourceName(entry.sourceId, screen), entry.edition)
                    .filter { it.isNotBlank() }.distinct().joinToString(" · "))
            }
            MotionIconButton(onClick = when {
                entry.status == AnimeDownloadStatus.COMPLETED -> onPlay
                active -> onPause
                else -> onResume
            }, enabled = !menuPresent, modifier = Modifier.size(44.dp), tooltip = when {
                entry.status == AnimeDownloadStatus.COMPLETED -> "播放离线视频"
                active -> "暂停下载"
                entry.status == AnimeDownloadStatus.FAILED -> "重试下载"
                else -> "继续下载"
            }) {
                Icon(when {
                    entry.status == AnimeDownloadStatus.COMPLETED -> Icons.Outlined.PlayArrow
                    active -> Icons.Outlined.Pause
                    entry.status == AnimeDownloadStatus.FAILED -> Icons.Outlined.Refresh
                    else -> Icons.Outlined.Download
                }, when {
                    entry.status == AnimeDownloadStatus.COMPLETED -> "播放离线视频"
                    active -> "暂停下载"
                    else -> "继续下载"
                }, Modifier.size(21.dp))
            }
            Box {
                MotionIconButton(onClick = { menuPresent = true; menu = true }, enabled = !menuPresent,
                    modifier = Modifier.size(44.dp), tooltip = "管理下载") {
                    Icon(Icons.Outlined.MoreHoriz, "管理下载", Modifier.size(19.dp))
                }
                CompactGlassPopover(menu, backdrop, onDismiss = { menu = false }, onPresenceChange = { present ->
                    menuPresent = present
                    if (!present) {
                        if (removeRequested) { removeRequested = false; onRemove() }
                        else if (originalRequested) { originalRequested = false; onOriginal() }
                    }
                }) {
                    if (entry.status == AnimeDownloadStatus.COMPLETED) {
                        CompactMenuAction(Icons.Outlined.AutoAwesome, "增强此集", onClick = { menu = false; onEnhance() })
                        CompactMenuAction(Icons.Outlined.PlayArrow, "播放原始版",
                            onClick = { originalRequested = true; menu = false })
                    }
                    CompactMenuAction(Icons.Outlined.DeleteOutline, "移除下载", destructive = true,
                        onClick = { removeRequested = true; menu = false })
                }
            }
        }
        val total = entry.totalBytes?.takeIf { it > 0L }
        val fraction = total?.let { (entry.downloadedBytes.toFloat() / it).coerceIn(0f, 1f) }
        if (fraction != null && entry.status != AnimeDownloadStatus.COMPLETED) AnimeDownloadProgress(fraction)
        AnimeQuietLabel(listOfNotNull(
            fraction?.takeIf { entry.status != AnimeDownloadStatus.COMPLETED }?.let { "${(it * 100).toInt()}%" },
            animeDownloadBytes(entry.downloadedBytes) + (total?.let { " / " + animeDownloadBytes(it) } ?: " · 总量未知"),
            entry.totalFiles?.takeIf { it > 1 }?.let { "${entry.completedFiles} / $it 个文件" },
        ).joinToString(" · "))
        if (!entry.error.isNullOrBlank()) AnimeDiscoveryInlineStatus(entry.error, showIssue = true)
        versions.forEach { version ->
            var versionMenu by remember(version.id) { mutableStateOf(false) }
            var versionMenuPresent by remember(version.id) { mutableStateOf(false) }
            var removeVersionRequested by remember(version.id) { mutableStateOf(false) }
            var cancelVersionRequested by remember(version.id) { mutableStateOf(false) }
            val working = version.phase in setOf(EnhancementTaskPhase.QUEUED, EnhancementTaskPhase.WAITING, EnhancementTaskPhase.RUNNING)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text("增强 · ${version.label}", style = MaterialTheme.typography.labelMedium)
                    AnimeQuietLabel(version.phase.label + (version.progress?.processingFps?.let {
                        " · 处理 ${(it * 10).toInt() / 10.0} 帧/秒" } ?: ""))
                }
                if (version.phase != EnhancementTaskPhase.CANCELLED) MotionIconButton(onClick = {
                    when { version.phase == EnhancementTaskPhase.COMPLETED -> onPlayVersion(version.id)
                        working -> onPauseVersion(version.id); else -> onResumeVersion(version.id) }
                }, enabled = !menuPresent && !versionMenuPresent,
                    tooltip = when { version.phase == EnhancementTaskPhase.COMPLETED -> "播放增强版"
                    working -> "暂停增强"; else -> "继续增强" }, modifier = Modifier.size(44.dp)) {
                    Icon(when { version.phase == EnhancementTaskPhase.COMPLETED -> Icons.Outlined.PlayArrow
                        working -> Icons.Outlined.Pause; else -> Icons.Outlined.Refresh }, null, Modifier.size(19.dp))
                }
                Box {
                    MotionIconButton(onClick = { versionMenuPresent = true; versionMenu = true },
                        enabled = !menuPresent && !versionMenuPresent, tooltip = "管理增强版本", modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.MoreHoriz, null, Modifier.size(18.dp))
                    }
                    CompactGlassPopover(versionMenu, backdrop, onDismiss = { versionMenu = false }, onPresenceChange = { present ->
                        versionMenuPresent = present
                        if (!present) {
                            if (removeVersionRequested) { removeVersionRequested = false; onRemoveVersion(version.id) }
                            else if (cancelVersionRequested) { cancelVersionRequested = false; onCancelVersion(version.id) }
                        }
                    }) {
                        if (working || version.phase == EnhancementTaskPhase.PAUSED)
                            CompactMenuAction(Icons.Outlined.Close, "取消增强", onClick = { cancelVersionRequested = true; versionMenu = false })
                        if (version.phase == EnhancementTaskPhase.COMPLETED)
                            CompactMenuAction(Icons.Outlined.DeleteOutline, "清理增强版", destructive = true,
                                onClick = { removeVersionRequested = true; versionMenu = false })
                    }
                }
            }
            val completed = version.progress?.completed
            val enhancedTotal = version.progress?.total?.takeIf { it > 0 }
            if (working && completed != null && enhancedTotal != null) AnimeDownloadProgress(
                (completed.toDouble() / enhancedTotal).toFloat().coerceIn(0f, 1f))
            version.reason?.let { AnimeQuietLabel(it) }
        }
    }
}

@Composable
private fun AnimeDownloadProgress(fraction: Float) {
    val motion = LocalMotionEnabled.current
    val progress by animateFloatAsState(fraction,
        if (motion) tween(MangaMotion.Content, easing = MangaMotion.EnterEasing) else snap(), label = "anime-download-progress")
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxWidth().height(3.dp).semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
    }) {
        drawLine(color.copy(alpha = .08f), Offset.Zero, Offset(size.width, 0f), 3.dp.toPx(), StrokeCap.Round)
        drawLine(color.copy(alpha = .6f), Offset.Zero, Offset(size.width * progress, 0f), 3.dp.toPx(), StrokeCap.Round)
    }
}

private fun animeDownloadStatus(status: AnimeDownloadStatus): String = when (status) {
    AnimeDownloadStatus.QUEUED -> "排队中"
    AnimeDownloadStatus.DOWNLOADING -> "下载中"
    AnimeDownloadStatus.WAITING -> "等待继续"
    AnimeDownloadStatus.PAUSED -> "已暂停"
    AnimeDownloadStatus.COMPLETED -> "已完成"
    AnimeDownloadStatus.FAILED -> "下载未完成"
}

private fun animeDownloadBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L)
    val (unit, divisor) = when {
        value >= 1_073_741_824L -> "GB" to 1_073_741_824L
        value >= 1_048_576L -> "MB" to 1_048_576L
        value >= 1_024L -> "KB" to 1_024L
        else -> "B" to 1L
    }
    val tenths = if (divisor == 1L) value else (value.toDouble() / divisor * 10).toLong()
    return (if (divisor == 1L) tenths.toString() else (tenths / 10).toString() + "." + tenths % 10) + " " + unit
}
