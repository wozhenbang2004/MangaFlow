package dev.mangaflow.anime.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.mangaflow.enhancement.VideoEnhancementVersion
import dev.mangaflow.enhancement.EnhancementTaskPhase
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.anime.downloads.*
import dev.mangaflow.typography.MangaFontScene
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
                    Text("离线下载", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
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
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var removing by remember { mutableStateOf<String?>(null) }
    var actionsOpen by remember { mutableStateOf(false) }
    var actionsPresent by remember { mutableStateOf(false) }
    val positions = rememberSaveableStateHolder()
    val removal = downloads.entries.firstOrNull { it.id == removing }
    val rows = remember(downloads.entries, filter) {
        downloads.entries.filter { entry ->
            when (filter) {
                1 -> entry.status != AnimeDownloadStatus.COMPLETED
                2 -> entry.status == AnimeDownloadStatus.COMPLETED
                else -> true
            }
        }.sortedByDescending { it.createdAt }
    }
    val downloadGroups = remember(rows) { rows.groupBy { it.subject.id } }
    val versionsByDownload = remember(enhancement.entries) { enhancement.entries.groupBy { it.downloadId } }
    val readyRows = remember(rows) { rows.filter { it.status == AnimeDownloadStatus.COMPLETED } }
    Column(modifier.fillMaxWidth()) {
        if (removal != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AnimePoster(removal.subject, Modifier.width(40.dp).height(58.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(removal.subject.title, style = MaterialTheme.typography.titleSmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        AnimeQuietLabel(listOfNotNull(animeEpisodeLabel(removal.episode),
                            animeEpisodeSubtitle(removal.episode)).joinToString(" · "))
                    }
                }
                Text("移除这集的下载任务与本地视频。", style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MotionTextButton(onClick = { removing = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
                    MotionButton(onClick = { controller.removeDownload(removal.id); removing = null },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError)) { Text("移除下载") }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MotionTabs(filter, listOf("全部", "待完成", "可离线"), onSelect = { filter = it },
                    modifier = Modifier.weight(1f).motionInputGate(!actionsPresent), controlHeight = 44.dp,
                    treatment = MotionTabTreatment.Soft, fullWidthIndicator = true)
                Box {
                    MotionIconButton(onClick = { actionsOpen = true }, enabled = !actionsPresent,
                        modifier = Modifier.size(44.dp), tooltip = "下载选项") {
                        Icon(Icons.Outlined.MoreHoriz, "下载选项", Modifier.size(20.dp))
                    }
                    CompactGlassPopover(actionsOpen, backdrop, onDismiss = { actionsOpen = false },
                        onPresenceChange = { actionsPresent = it }) {
                        AnimeQuietLabel(when {
                            controller.downloads.capabilities.systemBackgroundExecution -> "Android 可在后台继续下载。"
                            controller.downloads.capabilities.processBackgroundExecution -> "窗口在后台可继续下载，退出后可手动续传。"
                            else -> "返回应用后可以继续下载。"
                        }, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        if (readyRows.isNotEmpty()) {
                            CompactMenuAction(Icons.Outlined.AutoAwesome, "增强当前列表的已下载剧集", onClick = {
                                actionsOpen = false
                                readyRows.forEach { controller.enhanceDownload(it.id) }
                            })
                        }
                    }
                }
            }
            Box(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                positions.SaveableStateProvider(filter) {
                    val scroll = rememberLazyListState()
                    LazyColumn(Modifier.fillMaxWidth().motionInputGate(!actionsPresent), state = scroll,
                        userScrollEnabled = !actionsPresent, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        downloads.notice?.let { notice ->
                            item(key = "download-notice") { AnimeDiscoveryInlineStatus(notice) }
                        }
                        enhancement.notice?.let { notice ->
                            item(key = "enhancement-notice") { AnimeDiscoveryInlineStatus(notice) }
                        }
                        when {
                            downloads.loading && rows.isEmpty() -> item(key = "loading") {
                                AnimeMessage("正在读取下载任务", icon = Icons.Outlined.Download)
                            }
                            rows.isEmpty() -> item(key = "empty") {
                                val (title, detail) = when (filter) {
                                    1 -> "没有待完成的下载" to null
                                    2 -> "还没有可离线观看的剧集" to "下载完成的剧集会出现在这里。"
                                    else -> "还没有离线下载" to "在播放器设置中选择下载当前集。"
                                }
                                AnimeMessage(title, detail, icon = Icons.Outlined.Download)
                            }
                        }
                        downloadGroups.forEach { (subjectId, episodes) ->
                            item(key = "work:$subjectId") {
                                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    val subject = episodes.first().subject
                                    AnimePoster(subject, Modifier.width(42.dp).height(62.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(subject.title, Modifier.semantics { heading() },
                                            style = MaterialTheme.typography.titleSmall,
                                            fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
                                            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        val readyCount = episodes.count { it.status == AnimeDownloadStatus.COMPLETED }
                                        AnimeQuietLabel(when (filter) {
                                            1 -> "${episodes.size} 集待完成"
                                            2 -> "${episodes.size} 集可离线"
                                            else -> "${episodes.size} 集 · $readyCount 集可离线"
                                        })
                                    }
                                }
                            }
                            items(episodes, key = { "download:${it.id}" }) { entry ->
                                AnimeDownloadRow(entry, screen, backdrop, onPlay = { onPlay(AnimeDownloadSelection(entry.id)) },
                                    onPause = { controller.pauseDownload(entry.id) }, onResume = { controller.resumeDownload(entry.id) },
                                    onRemove = { removing = entry.id }, versions = versionsByDownload[entry.id].orEmpty(),
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
    var versionsExpanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val completed = entry.status == AnimeDownloadStatus.COMPLETED
    val active = entry.status == AnimeDownloadStatus.DOWNLOADING ||
        entry.status == AnimeDownloadStatus.QUEUED || entry.status == AnimeDownloadStatus.WAITING
    val action = when {
        completed -> onPlay
        active -> onPause
        else -> onResume
    }
    val actionLabel = when {
        completed -> "播放离线视频"
        active -> "暂停下载"
        entry.status == AnimeDownloadStatus.FAILED -> "重试下载"
        else -> "继续下载"
    }
    val interaction = remember(entry.id) { MutableInteractionSource() }
    val shape = RoundedCornerShape(14.dp)
    val titleAction = if (completed) Modifier.motionPress(interaction, !menuPresent)
        .clickable(enabled = !menuPresent, interactionSource = interaction, indication = null, role = Role.Button,
            onClickLabel = actionLabel, onClick = action) else Modifier
    Column(Modifier.fillMaxWidth().clip(shape).background(colors.surfaceContainerLow).padding(start = 10.dp, end = 3.dp)
        .padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.weight(1f).heightIn(min = 44.dp).then(titleAction),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(listOfNotNull(animeEpisodeLabel(entry.episode), animeEpisodeSubtitle(entry.episode)).joinToString(" · "),
                        style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val source = entry.sourceName.ifBlank { animeSourceName(entry.sourceId, screen) }
                    Text(listOf(animeDownloadStatus(entry.status), source, entry.edition)
                        .filter { it.isNotBlank() }.distinct().joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (completed) Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(32.dp).background(colors.onSurface, CircleShape),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(20.dp), tint = colors.surface)
                    }
                }
            }
            if (!completed) MotionIconButton(onClick = action, enabled = !menuPresent,
                modifier = Modifier.size(44.dp), tooltip = actionLabel) {
                Box(Modifier.size(32.dp).background(colors.surfaceContainerHigh, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(when {
                        active -> Icons.Outlined.Pause
                        entry.status == AnimeDownloadStatus.FAILED -> Icons.Outlined.Refresh
                        else -> Icons.Outlined.Download
                    }, actionLabel, Modifier.size(19.dp), tint = colors.onSurface)
                }
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
        if (fraction != null && !completed) AnimeDownloadProgress(fraction, Modifier.padding(end = 7.dp))
        val sizeLabel = if (total != null && !completed) {
            animeDownloadBytes(entry.downloadedBytes) + " / " + animeDownloadBytes(total)
        } else entry.downloadedBytes.takeIf { it > 0L }?.let(::animeDownloadBytes)
        val progressLabel = listOfNotNull(
            fraction?.takeIf { !completed }?.let { "${(it * 100).toInt()}%" },
            sizeLabel,
            entry.totalFiles?.takeIf { !completed && it > 1 }?.let { "${entry.completedFiles} / $it 个文件" },
        ).joinToString(" · ")
        if (progressLabel.isNotBlank()) AnimeQuietLabel(progressLabel, Modifier.padding(end = 8.dp))
        entry.error?.takeIf { it.isNotBlank() }?.let { AnimeDiscoveryInlineStatus(it, showIssue = true) }
        if (versions.isNotEmpty()) {
            val preparing = versions.count { version ->
                version.phase == EnhancementTaskPhase.QUEUED || version.phase == EnhancementTaskPhase.WAITING ||
                    version.phase == EnhancementTaskPhase.RUNNING
            }
            val versionLabel = when {
                versionsExpanded -> "收起增强版本"
                preparing > 0 -> "增强版本 · $preparing 个处理中"
                else -> "增强版本 · ${versions.size}"
            }
            MotionTextButton(onClick = { versionsExpanded = !versionsExpanded }, enabled = !menuPresent,
                modifier = Modifier.heightIn(min = 44.dp)) {
                Icon(if (versionsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null,
                    Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(versionLabel)
            }
        }
        if (versionsExpanded) versions.forEach { version ->
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
private fun AnimeDownloadProgress(fraction: Float, modifier: Modifier = Modifier) {
    val motion = LocalMotionEnabled.current
    val progress by animateFloatAsState(fraction,
        if (motion) tween(MangaMotion.Content, easing = MangaMotion.EnterEasing) else snap(), label = "anime-download-progress")
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.fillMaxWidth().height(3.dp).semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
    }) {
        val start = Offset(0f, center.y)
        drawLine(color.copy(alpha = .12f), start, Offset(size.width, center.y), 3.dp.toPx(), StrokeCap.Round)
        drawLine(color.copy(alpha = .70f), start, Offset(size.width * progress, center.y), 3.dp.toPx(), StrokeCap.Round)
    }
}

private fun animeDownloadStatus(status: AnimeDownloadStatus): String = when (status) {
    AnimeDownloadStatus.QUEUED -> "排队中"
    AnimeDownloadStatus.DOWNLOADING -> "下载中"
    AnimeDownloadStatus.WAITING -> "等待继续"
    AnimeDownloadStatus.PAUSED -> "已暂停"
    AnimeDownloadStatus.COMPLETED -> "可离线"
    AnimeDownloadStatus.FAILED -> "下载失败"
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
