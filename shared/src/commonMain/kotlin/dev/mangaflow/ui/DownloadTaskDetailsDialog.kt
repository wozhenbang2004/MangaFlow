package dev.mangaflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import dev.mangaflow.enhancement.EnhancementTaskPhase
import dev.mangaflow.enhancement.ReaderChapterEnhancementTask
import dev.mangaflow.ui.motion.MotionButton
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionSwap
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionInputGate

/** A single task facts/control surface for the queue pane and the retained narrow/modal PANEL. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun DownloadTaskDetails(
    entry: DownloadEntry?,
    active: Boolean,
    refreshing: Boolean,
    backdrop: HazeState,
    inPane: Boolean,
    onDismiss: () -> Unit,
    onPause: (DownloadEntry) -> Unit,
    onRetry: (DownloadEntry) -> Unit,
    onRetryUsingLatestRoute: (DownloadEntry) -> Unit,
    enhancement: ReaderChapterEnhancementTask? = null,
    onEnhance: ((DownloadEntry) -> Unit)? = null,
    onPauseEnhancement: (String) -> Unit = {},
    onResumeEnhancement: (String) -> Unit = {},
    onCancelEnhancement: (String) -> Unit = {},
    onQualitySettings: (() -> Unit)? = null,
    onClosed: (() -> Unit)? = null,
) {
    if (inPane && entry == null) return
    var retained by remember { mutableStateOf(entry) }
    var retainedEnhancement by remember { mutableStateOf(enhancement) }
    SideEffect { if (entry != null) { retained = entry; retainedEnhancement = enhancement } }
    val shown = entry ?: retained ?: return
    val enhanced = (if (entry != null) enhancement else retainedEnhancement)?.takeIf {
        it.mangaId == shown.manga.id && it.chapterId == shown.chapter.id && it.downloadCreatedAt == shown.createdAt
    }
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
    val content: @Composable () -> Unit = {
        val bounds = if (inPane) Modifier else Modifier.heightIn(max = minOf(LocalMangaWindow.current.height * .86f, 580.dp))
        Column(Modifier.fillMaxWidth().then(bounds).padding(horizontal = if (inPane) 0.dp else 18.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                if (inPane) {
                    val backFocus = remember { FocusRequester() }
                    LaunchedEffect(backFocus) {
                        withFrameNanos { }
                        backFocus.requestFocus()
                    }
                    MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp).focusRequester(backFocus),
                        tooltip = "返回章节") {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回章节", Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(shown.chapter.title, style = MaterialTheme.typography.titleLarge,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!inPane) Text(shown.manga.title, style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!inPane) MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭下载状态") {
                    Icon(Icons.Outlined.Close, "关闭下载状态", Modifier.size(20.dp))
                }
            }
            val scroll = key(shown.id, shown.createdAt) { rememberScrollState() }
            val facts: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier.fillMaxWidth().verticalScroll(scroll).motionInputGate(enabled)
                    .padding(top = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val tone = downloadStatusColor(shown.status)
                    Column(Modifier.fillMaxWidth().background(colors.surfaceContainerLow, RoundedCornerShape(14.dp))
                        .padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val mark: @Composable (DownloadStatus) -> Unit = { phase ->
                                Icon(when (phase) {
                                    DownloadStatus.COMPLETED -> Icons.Outlined.CheckCircle
                                    DownloadStatus.FAILED -> Icons.Outlined.ErrorOutline
                                    DownloadStatus.PAUSED -> Icons.Outlined.Pause
                                    DownloadStatus.QUEUED, DownloadStatus.WAITING -> Icons.Outlined.Schedule
                                    DownloadStatus.RUNNING -> Icons.Outlined.Download
                                }, null, Modifier.size(18.dp), tint = downloadStatusColor(phase))
                            }
                            if (platformKind == PlatformKind.DESKTOP) key(shown.id, shown.createdAt) {
                                MotionSwap(shown.status, Modifier.size(18.dp), content = mark)
                            } else mark(shown.status)
                            Text(downloadTransferPhaseLabel(shown, now), style = MaterialTheme.typography.titleSmall,
                                color = tone)
                        }
                        FlowRow(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(shown.completedPages.toString(), Modifier.alignByBaseline(),
                                style = mangaMetricValueStyle(29.sp, 35.sp), color = colors.onSurface)
                            val saved = if (shown.totalPages > 0) "/ ${shown.totalPages} 页已保存" else "页已保存"
                            Text(mangaNumberText(saved), Modifier.alignByBaseline(),
                                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        }
                        if (shown.totalPages > 0) {
                            val savedFraction = (shown.completedPages.toFloat() / shown.totalPages).coerceIn(0f, 1f)
                            if (platformKind == PlatformKind.DESKTOP) key(shown.id, shown.createdAt, shown.totalPages) {
                                ManagementProgressTrack(savedFraction, "章节已保存页数", tone, Modifier.fillMaxWidth().height(4.dp))
                            } else LinearProgressIndicator(progress = { savedFraction }, modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = tone, trackColor = colors.onSurface.copy(alpha = .09f))
                        }
                        Text("原始下载 · ${downloadBytes(shown.downloadedBytes)}", style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant)
                    }
                    if (shown.error.isNotBlank() || shown.status == DownloadStatus.FAILED) {
                        val failed = shown.status == DownloadStatus.FAILED
                        val ink = if (failed) colors.onErrorContainer else colors.onSurface
                        Column(Modifier.fillMaxWidth().background(if (failed) colors.errorContainer.copy(alpha = .6f)
                            else colors.surfaceContainerLow, RoundedCornerShape(10.dp))
                            .padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(if (failed) "失败详情" else "最近一次传输", style = MaterialTheme.typography.titleSmall, color = ink)
                            val cause = shown.error.ifBlank { shown.progress.failureReason?.label ?: "未记录具体原因" }
                            SelectionContainer { Text(cause, style = MaterialTheme.typography.bodySmall, color = ink) }
                            shown.progress.httpStatus?.let { Text("HTTP $it", style = MaterialTheme.typography.labelMedium, color = ink) }
                        }
                    } else shown.progress.httpStatus?.let { DownloadFactRow("来源响应", "HTTP $it") }
                    downloadTransferByteLabel(shown, now).takeIf(String::isNotBlank)?.let {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            val receiving = shown.status == DownloadStatus.RUNNING && shown.progress.activePages > 0
                            DownloadFactRow(if (receiving) "正在接收" else "已接收数据", it)
                            DownloadProgressLine(listOf(shown))
                        }
                    }
                    if (shown.progress.activePages > 1) DownloadFactRow("同时接收", "${shown.progress.activePages} 页")
                    if (!local) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("任务线路", style = MaterialTheme.typography.titleSmall)
                            DownloadFactRow("来源", source)
                            DownloadFactRow("已记录线路", shown.progress.routeLabel.ifBlank { "尚未记录" })
                            if (retryable) Text("原线路重试沿用任务记录。使用当前设置会重新选择线路。",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                    if (!local && shown.status != DownloadStatus.COMPLETED) Text(when (platformKind) {
                        PlatformKind.DESKTOP -> "应用运行时继续下载；退出后暂停，重新打开会核对并恢复队列。"
                        PlatformKind.IOS -> "后台可能被系统挂起，重新打开后恢复；强制退出后不能保证继续下载。"
                        else -> "后台任务由系统调度；系统暂停时保留已保存内容和可用断点。"
                    }, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    if (shown.status == DownloadStatus.COMPLETED || enhanced != null) {
                        HorizontalDivider(color = colors.onSurface.copy(alpha = .08f))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                            Text("画质增强", style = MaterialTheme.typography.titleSmall)
                        }
                        if (enhanced == null) DownloadFactRow("增强进度", "尚未建立任务") else {
                            DownloadFactRow("增强进度", "${enhanced.completedPages} / ${enhanced.totalPages} 页 · ${chapterEnhancementPhase(enhanced.phase)}")
                            DownloadFactRow("处理目标", "${enhanced.policy.tier.label} · ${enhanced.policy.scale}×")
                            enhanced.reason?.takeIf(String::isNotBlank)?.let { reason -> SelectionContainer {
                                Text(reason, style = MaterialTheme.typography.bodySmall,
                                    color = if (enhanced.phase == EnhancementTaskPhase.FAILED) colors.error else colors.onSurfaceVariant)
                            } }
                        }
                        Text(if (platformKind == PlatformKind.DESKTOP)
                            "增强单独保存，原始下载保留。应用运行时处理；重启后可从已完成页面继续。"
                            else "增强单独保存，原图始终可读。后台受系统限制；应用退出后可从已完成页面继续。",
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            onQualitySettings?.let { open -> MotionTextButton(onClick = open, enabled = enabled) {
                                Text("画质设置", style = MaterialTheme.typography.labelMedium)
                            } }
                            if (enhanced == null || enhanced.phase == EnhancementTaskPhase.COMPLETED) {
                                onEnhance?.let { start -> MotionTextButton(onClick = { start(shown) },
                                    enabled = enabled && shown.status == DownloadStatus.COMPLETED) {
                                    Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                                    Text(if (enhanced == null) "增强整章" else "重新检查并增强")
                                } }
                            } else when (enhanced.phase) {
                                EnhancementTaskPhase.QUEUED, EnhancementTaskPhase.WAITING, EnhancementTaskPhase.RUNNING -> {
                                    MotionTextButton(onClick = { onCancelEnhancement(enhanced.id) }, enabled = enabled) { Text("取消增强") }
                                    MotionTextButton(onClick = { onPauseEnhancement(enhanced.id) }, enabled = enabled) {
                                        Icon(Icons.Outlined.Pause, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("暂停增强")
                                    }
                                }
                                EnhancementTaskPhase.PAUSED, EnhancementTaskPhase.FAILED, EnhancementTaskPhase.CANCELLED -> {
                                    onEnhance?.let { start -> MotionTextButton(onClick = { start(shown) },
                                        enabled = enabled && shown.status == DownloadStatus.COMPLETED) {
                                        Text("按当前设置重建", style = MaterialTheme.typography.labelMedium)
                                    } }
                                    if (enhanced.phase != EnhancementTaskPhase.CANCELLED)
                                        MotionTextButton(onClick = { onCancelEnhancement(enhanced.id) }, enabled = enabled) { Text("取消增强") }
                                    MotionTextButton(onClick = { onResumeEnhancement(enhanced.id) }, enabled = enabled) {
                                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("继续增强")
                                    }
                                }
                                EnhancementTaskPhase.COMPLETED -> Unit
                            }
                        }
                    }
                }
            }
            if (platformKind == PlatformKind.DESKTOP || inPane) {
                Box(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                    facts(Modifier.padding(end = 12.dp))
                    DesktopVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd))
                }
            } else facts(Modifier.weight(1f, fill = false))
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
                    MotionButton(onClick = { onRetry(shown) }, enabled = enabled && !refreshing) {
                        if (refreshing) MotionBusyIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp)
                        else Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (refreshing) "正在准备" else "原线路重试")
                    }
                }
            }
        }
    }
    if (inPane) content() else MotionDialog(visible = entry != null, onDismissRequest = onDismiss, onClosed = onClosed,
        properties = DialogProperties(usePlatformDefaultWidth = false), maximumWidth = 520.dp) {
        Box(Modifier.padding(horizontal = 12.dp)) {
            LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), cornerRadius = 22.dp,
                role = GlassSurfaceRole.PANEL, elevation = 0.dp) { content() }
        }
    }
}

@Composable
private fun DownloadFactRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(.75f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(mangaNumberText(value), Modifier.weight(1.5f), style = MaterialTheme.typography.bodyMedium)
    }
}
