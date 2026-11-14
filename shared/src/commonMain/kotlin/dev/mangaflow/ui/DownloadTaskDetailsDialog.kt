package dev.mangaflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.data.chapterSourceMangaId
import dev.mangaflow.data.sourceDisplayName
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.DownloadStore.Companion.LOCAL_SOURCE_URL
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionInputGate

/** The existing retained PANEL shows one live task; no polling request or second queue owns it. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun DownloadTaskDetailsDialog(
    entry: DownloadEntry?,
    active: Boolean,
    refreshing: Boolean,
    backdrop: HazeState,
    onDismiss: () -> Unit,
    onPause: (DownloadEntry) -> Unit,
    onRetry: (DownloadEntry) -> Unit,
    onRetryUsingLatestRoute: (DownloadEntry) -> Unit,
) {
    var retained by remember { mutableStateOf(entry) }
    SideEffect { if (entry != null) retained = entry }
    val shown = entry ?: retained ?: return
    val enabled = entry != null && active
    val now = downloadObservationTime(listOf(shown), enabled)
    val colors = MaterialTheme.colorScheme
    val local = shown.manga.sourceUrl == LOCAL_SOURCE_URL
    val pausable = shown.status in setOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.WAITING)
    val retryable = !local && shown.status in setOf(DownloadStatus.PAUSED, DownloadStatus.FAILED)
    val source = remember(shown.manga.id, shown.chapter) {
        runCatching { sourceDisplayName(SourceKeys.decodeChapter(
            chapterSourceMangaId(shown.manga.id, shown.chapter), shown.chapter.id).sourceId) }
            .getOrElse { sourceDisplayName(shown.chapter.sourceId) }
    }
    MotionDialog(visible = entry != null, onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false), maximumWidth = 520.dp) {
        Box(Modifier.padding(horizontal = 12.dp)) {
        LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), cornerRadius = 22.dp,
            role = GlassSurfaceRole.PANEL, elevation = 0.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = minOf(LocalMangaWindow.current.height * .86f, 580.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(shown.chapter.title, style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(shown.manga.title, style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭下载状态") {
                        Icon(Icons.Outlined.Close, "关闭下载状态", Modifier.size(20.dp))
                    }
                }
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                    .motionInputGate(enabled).padding(top = 14.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DownloadFactRow("状态", downloadTransferPhaseLabel(shown, now))
                    shown.progress.httpStatus?.let { DownloadFactRow("来源响应", "HTTP $it") }
                    DownloadFactRow("已保存", shown.completedPages.toString() +
                        (if (shown.totalPages > 0) " / ${shown.totalPages}" else "") +
                        " 页 · " + downloadBytes(shown.downloadedBytes))
                    downloadTransferByteLabel(shown, now).takeIf(String::isNotBlank)?.let {
                        DownloadFactRow("正在接收", it)
                    }
                    if (shown.progress.activePages > 1) DownloadFactRow("同时接收", "${shown.progress.activePages} 页")
                    if (!local) {
                        DownloadFactRow("来源", source)
                        DownloadFactRow("本任务线路", shown.progress.routeLabel.ifBlank { "线路尚未记录" })
                        Text("修改默认设置只影响新任务；在这里明确选择重试线路。",
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                    if (shown.error.isNotBlank()) SelectionContainer {
                        Text(shown.error, style = MaterialTheme.typography.bodySmall, color = colors.error)
                    }
                    if (!local && shown.status != DownloadStatus.COMPLETED) Text(when (platformKind) {
                        PlatformKind.DESKTOP -> "应用运行时继续下载；退出后暂停，重新打开会核对并恢复队列。"
                        PlatformKind.IOS -> "后台可能被系统挂起，重新打开后恢复；强制退出后不能保证继续下载。"
                        else -> "后台任务由系统调度；系统暂停时保留已保存内容和可用断点。"
                    }, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                if (pausable || retryable) FlowRow(Modifier.fillMaxWidth().motionInputGate(enabled),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (pausable) MotionTextButton(onClick = { onPause(shown) }, enabled = enabled && !refreshing) {
                        Icon(Icons.Outlined.Pause, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                        Text("暂停")
                    }
                    if (retryable) {
                        MotionTextButton(onClick = { onRetryUsingLatestRoute(shown) }, enabled = enabled && !refreshing) {
                            Text("使用当前设置重试", style = MaterialTheme.typography.labelMedium)
                        }
                        MotionTextButton(onClick = { onRetry(shown) }, enabled = enabled && !refreshing) {
                            Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                            Text(if (refreshing) "正在准备" else "原线路重试", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun DownloadFactRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(.75f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(mangaNumberText(value), Modifier.weight(1.5f), style = MaterialTheme.typography.bodySmall)
    }
}
