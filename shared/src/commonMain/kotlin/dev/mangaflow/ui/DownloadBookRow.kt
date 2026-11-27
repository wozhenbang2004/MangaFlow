package dev.mangaflow.ui

import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.ViewList
import dev.mangaflow.typography.MangaFontScene

import dev.mangaflow.data.chapterSourceMangaId
import dev.mangaflow.ui.platform.*

import dev.mangaflow.platform.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import dev.mangaflow.ui.motion.MotionBusyIndicator as CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.enhancement.EnhancementTaskPhase
import dev.mangaflow.enhancement.ReaderChapterEnhancementTask
import dev.mangaflow.storage.DownloadStore.Companion.LOCAL_SOURCE_URL
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.motionPress

/** One cover per actual work; disclosure and queue actions keep separate tap targets. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DownloadBookRow(
    entries: List<DownloadEntry>,
    repository: MangaRepository,
    expanded: Boolean,
    managing: Boolean,
    selected: Boolean,
    refreshingIds: Set<String>,
    onRead: () -> Unit,
    onSelect: () -> Unit,
    onManage: () -> Unit,
    onExpand: () -> Unit,
    onPause: (List<DownloadEntry>) -> Unit,
    onRetry: (List<DownloadEntry>) -> Unit,
    onDetails: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    inspectMode: Boolean = false,
    active: Boolean = true,
    onInspect: ((DownloadEntry) -> Unit)? = null,
    enhancementTasks: List<ReaderChapterEnhancementTask> = emptyList(),
) {
    val desktop = platformKind == PlatformKind.DESKTOP
    val actions = buildList {
        add(DesktopMenuAction("继续阅读", icon = Icons.Outlined.AutoStories, onClick = onRead))
        onDetails?.let { add(DesktopMenuAction("详情与目录", icon = Icons.Outlined.ViewList, onClick = it)) }
        add(DesktopMenuAction("查看章节", icon = Icons.Outlined.ViewList, onClick = onExpand))
        if (onInspect != null) downloadObservedEntry(entries)?.let { task ->
            add(DesktopMenuAction("下载状态与线路", icon = Icons.Outlined.Timeline, onClick = { onInspect(task) }))
        }
        add(DesktopMenuAction("选择下载", icon = Icons.Outlined.CheckBox, onClick = onManage))
        onRemove?.let { add(DesktopMenuAction("删除下载", icon = Icons.Outlined.DeleteOutline, destructive = true, onClick = it)) }
    }
    val manga = entries.first().manga
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val coverFrame = if (inspectMode) Modifier.size(38.dp, 54.dp).mangaCoverCard(7.dp, shadowRadius = 5.dp)
        else Modifier.size(52.dp, 74.dp).mangaCoverCard(9.dp, shadowRadius = 6.dp)
    val chosen = if (managing) selected else expanded
    val directoryRow = if (!inspectMode) Modifier else Modifier.clip(RoundedCornerShape(9.dp))
        .then(if (chosen) Modifier.background(colors.secondaryContainer) else Modifier)
        .semantics { this.selected = chosen }
    val rotation by animateFloatAsState(if (expanded && !inspectMode) 180f else 0f,
        if (LocalMotionEnabled.current) spring(MangaMotion.NavigationDamping, MangaMotion.PanelStiffness) else snap(),
        label = "download-book-disclosure")
    DesktopContextMenuArea(actions) {
    Column {
        Row(Modifier.fillMaxWidth().then(directoryRow).combinedClickable(
            enabled = active,
            interactionSource = interaction, indication = null,
            onClickLabel = if (managing) "选择这部作品的章节" else if (inspectMode) "查看作品下载" else "继续阅读 ${manga.title}",
            onClick = if (managing) onSelect else if (inspectMode) onExpand else onRead,
            onDoubleClick = if (inspectMode && !managing) onRead else null,
            onLongClickLabel = "管理这部作品的下载", onLongClick = onManage,
        ).motionPress(interaction, pressedScale = .99f).padding(horizontal = if (inspectMode) 8.dp else 0.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (manga.sourceUrl == LOCAL_SOURCE_URL) {
                AsyncImage(manga.coverUrl, null, contentScale = ContentScale.Crop,
                    modifier = coverFrame
                        .background(colors.surfaceVariant))
            } else {
                MangaCover(manga, repository, coverFrame,
                    scale = ContentScale.Crop)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE), fontSize = if (inspectMode) 15.sp else 17.sp,
                    lineHeight = if (inspectMode) 21.sp else 23.sp, fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (inspectMode) {
                    val complete = entries.count { it.status == DownloadStatus.COMPLETED }
                    val observed = downloadObservedEntry(entries)
                    val phase = observed?.takeUnless { complete == entries.size }?.let {
                        downloadTransferPhaseLabel(it, downloadObservationTime(entries, active))
                    }
                    Text(mangaNumberText(listOfNotNull("$complete/${entries.size} 话已保存", phase).joinToString(" · ")),
                        style = MaterialTheme.typography.labelSmall, color = if (observed?.status == DownloadStatus.FAILED)
                            colors.error else colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                } else DownloadInlineTransferSummary(entries, active, if (managing) null else onInspect)
                DownloadProgressLine(entries)
                if (!inspectMode) DownloadEnhancementSummary(entries, enhancementTasks, if (managing) null else onInspect)
            }
            if (managing) DownloadSelection(selected, onSelect)
            else DownloadTransferAction(entries, refreshingIds, onPause, onRetry)
            if (!inspectMode && (entries.size > 1 || desktop)) MotionIconButton(onClick = onExpand, modifier = Modifier.size(if (desktop) 36.dp else 44.dp)) {
                Icon(if (inspectMode) Icons.Outlined.ChevronRight else Icons.Outlined.KeyboardArrowDown,
                    if (inspectMode) "查看章节" else if (expanded) "收起章节" else "展开 ${entries.size} 话",
                    Modifier.size(20.dp).graphicsLayer { rotationZ = rotation },
                    tint = if (inspectMode && expanded) colors.onSurface else colors.onSurfaceVariant)
            }
            if (desktop) {
                if (!inspectMode && onDetails != null) MotionIconButton(onClick = onDetails, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.MenuBook, "详情与目录", Modifier.size(17.dp))
                }
                DesktopMoreButton(actions, "作品下载操作")
            }
        }
        // Multi-chapter failures belong to their disclosed chapter rows; do not copy every
        // complete error into the collapsed book or grow one failure row per work.
        if (!managing && onInspect == null && entries.size == 1 && entries.first().error.isNotBlank())
            DownloadFailureDetails(entries.first().error, Modifier.padding(start = 65.dp))

    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DownloadChapterRow(
    entry: DownloadEntry,
    managing: Boolean,
    selected: Boolean,
    refreshingIds: Set<String>,
    onRead: () -> Unit,
    onSelect: () -> Unit,
    onManage: () -> Unit,
    onPause: (DownloadEntry) -> Unit,
    onRetry: (DownloadEntry) -> Unit,
    onRemove: (() -> Unit)? = null,
    active: Boolean = true,
    onInspect: ((DownloadEntry) -> Unit)? = null,
    enhancementTasks: List<ReaderChapterEnhancementTask> = emptyList(),
) {
    val desktop = platformKind == PlatformKind.DESKTOP
    val actions = buildList {
        add(DesktopMenuAction("阅读这一话", icon = Icons.Outlined.AutoStories, onClick = onRead))
        if (onInspect != null) add(DesktopMenuAction("下载状态与线路", icon = Icons.Outlined.Timeline, onClick = { onInspect(entry) }))
        add(DesktopMenuAction("选择这一话", icon = Icons.Outlined.CheckBox, onClick = onManage))
        onRemove?.let { add(DesktopMenuAction("删除这一话", icon = Icons.Outlined.DeleteOutline, destructive = true, onClick = it)) }
    }
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    DesktopContextMenuArea(actions) {
    Column(Modifier.fillMaxWidth().combinedClickable(interactionSource = interaction, indication = null,
        onClickLabel = if (managing) "选择 ${entry.chapter.title}" else "阅读 ${entry.chapter.title}",
        onClick = if (managing) onSelect else onRead,
        onLongClickLabel = "管理这一话", onLongClick = onManage)
        .motionPress(interaction, pressedScale = .99f)
        .clip(RoundedCornerShape(10.dp))
        .background(if (selected) colors.primaryContainer else colors.surfaceContainerLow)
        .padding(start = 13.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.chapter.title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                    color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val source = dev.mangaflow.data.sourceDisplayName(
                    dev.mangaflow.data.SourceKeys.decodeChapter(chapterSourceMangaId(entry.manga.id, entry.chapter), entry.chapter.id).sourceId)
                DownloadInlineTransferSummary(listOf(entry), active, if (managing) null else onInspect, source)
                DownloadProgressLine(listOf(entry))
                DownloadEnhancementSummary(listOf(entry), enhancementTasks, if (managing) null else onInspect)
            }
            if (managing) DownloadSelection(selected, onSelect)
            else DownloadTransferAction(listOf(entry), refreshingIds, { group -> group.forEach(onPause) }, { group -> group.forEach(onRetry) })
            if (desktop) DesktopMoreButton(actions, "章节下载操作")
        }
        if (!managing && onInspect == null && entry.error.isNotBlank()) DownloadFailureDetails(entry.error)
    }
    }
}

internal fun chapterEnhancementFor(entry: DownloadEntry, tasks: List<ReaderChapterEnhancementTask>) =
    tasks.firstOrNull { it.mangaId == entry.manga.id && it.chapterId == entry.chapter.id &&
        it.downloadCreatedAt == entry.createdAt }

internal fun chapterEnhancementPhase(phase: EnhancementTaskPhase): String = when (phase) {
    EnhancementTaskPhase.QUEUED -> "排队中"
    EnhancementTaskPhase.WAITING -> "等待资源"
    EnhancementTaskPhase.RUNNING -> "处理中"
    EnhancementTaskPhase.PAUSED -> "已暂停"
    EnhancementTaskPhase.COMPLETED -> "已处理"
    EnhancementTaskPhase.FAILED -> "未完成"
    EnhancementTaskPhase.CANCELLED -> "已取消"
}

/** Enhancement has its own page progress; original download bytes never imply AI completion. */
@Composable
private fun DownloadEnhancementSummary(entries: List<DownloadEntry>, tasks: List<ReaderChapterEnhancementTask>,
    onInspect: ((DownloadEntry) -> Unit)?) {
    val pairs = entries.mapNotNull { entry -> chapterEnhancementFor(entry, tasks)?.let { entry to it } }
    if (pairs.isEmpty()) return
    val observed = pairs.firstOrNull { it.second.phase in setOf(EnhancementTaskPhase.RUNNING,
        EnhancementTaskPhase.WAITING, EnhancementTaskPhase.QUEUED) } ?: pairs.first()
    val total = pairs.sumOf { it.second.totalPages.toLong() }.takeIf { pairs.all { (_, task) -> task.totalPages > 0 } }
    val completed = pairs.sumOf { it.second.completedPages.toLong() }
    val pages = if (total != null) "$completed / $total 页" else "已处理 $completed 页"
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Column(Modifier.fillMaxWidth().then(if (onInspect != null) Modifier.clickable(
        interactionSource = interaction, indication = null, role = Role.Button,
        onClickLabel = "查看画质增强进度", onClick = { onInspect(observed.first) })
        .motionPress(interaction, pressedScale = .99f) else Modifier)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
            Text(mangaNumberText("增强 $pages · ${chapterEnhancementPhase(observed.second.phase)}"),
                style = MaterialTheme.typography.labelSmall, color = if (observed.second.phase == EnhancementTaskPhase.FAILED)
                    colors.error else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (total != null && pairs.any { it.second.phase != EnhancementTaskPhase.COMPLETED }) {
            key(pairs.map { (_, task) -> Triple(task.id, task.generation, task.totalPages) }) {
                ManagementProgressTrack(completed.toFloat() / total, "整章增强已完成页面", colors.primary,
                    Modifier.fillMaxWidth().padding(vertical = 1.dp).height(2.dp))
            }
        }
    }
}

/** The existing metadata area is the details target, so no extra permanent action column is added. */
@Composable
private fun DownloadInlineTransferSummary(
    entries: List<DownloadEntry>,
    active: Boolean,
    onInspect: ((DownloadEntry) -> Unit)?,
    source: String? = null,
) {
    val entry = downloadObservedEntry(entries) ?: return
    val now = downloadObservationTime(entries, active)
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val saved = entries.count { it.status == DownloadStatus.COMPLETED }
    val allSaved = saved == entries.size
    val signal = downloadStatusColor(entry.status)
    val title = when {
        source != null -> source + " · " + downloadPageProgress(entries)
        entries.size == 1 -> entry.chapter.title + " · " + downloadPageProgress(entries)
        else -> "$saved/${entries.size} 话已保存 · " + downloadPageProgress(entries)
    }
    val detail = if (allSaved) downloadBytes(entries.sumOf { it.downloadedBytes })
        else listOfNotNull(
            downloadTransferByteLabel(entry, now).takeIf(String::isNotBlank),
            (if (entries.size > 1) entry.chapter.title + " · " else "") + downloadTransferPhaseLabel(entry, now),
        ).joinToString(" · ")
    Column(Modifier.fillMaxWidth().heightIn(min = if (allSaved) 44.dp else 52.dp).then(if (onInspect != null)
        Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button,
            onClickLabel = "查看 ${entry.chapter.title} 下载状态与线路", onClick = { onInspect(entry) })
            .motionPress(interaction, pressedScale = .99f) else Modifier),
        verticalArrangement = Arrangement.Center) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(mangaNumberText(title), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (onInspect != null) Icon(Icons.Outlined.Info, null, Modifier.size(12.dp),
                tint = colors.onSurfaceVariant.copy(alpha = .7f))
        }
        Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(5.dp).background(signal, RoundedCornerShape(50)))
            Text(mangaNumberText(detail), style = MaterialTheme.typography.labelSmall,
                color = if (entry.status == DownloadStatus.FAILED) colors.error else colors.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DownloadFailureDetails(message: String, modifier: Modifier = Modifier) {
    var expanded by remember(message) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotionEnabled.current
    val rotation by animateFloatAsState(if (expanded) 180f else 0f,
        if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.PanelStiffness) else snap(), label = "download-error-angle")
    val interaction = remember { MutableInteractionSource() }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                onClickLabel = if (expanded) "收起失败原因" else "查看完整失败原因",
                onClick = { expanded = !expanded })
            .motionPress(interaction, pressedScale = .99f),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Outlined.Info, null, Modifier.size(14.dp), tint = colors.error)
            Text(remember(message) { message.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty() },
                Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = colors.error,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(13.dp).graphicsLayer {
                rotationZ = rotation
            }, tint = colors.onSurfaceVariant)
        }
        AnimatedVisibility(expanded,
            enter = fadeIn(tween(if (motion) MangaMotion.Quick else 0)) +
                expandVertically(if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.PanelStiffness) else snap(),
                    expandFrom = Alignment.Top),
            exit = fadeOut(tween(if (motion) MangaMotion.Quick else 0)) +
                shrinkVertically(if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.PanelStiffness) else snap(),
                    shrinkTowards = Alignment.Top)) {
            SelectionContainer(Modifier.motionInputGate(expanded)) {
                Text(message, Modifier.padding(bottom = 8.dp), color = colors.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun downloadPageProgress(entries: List<DownloadEntry>): String {
    val completed = entries.sumOf { it.completedPages }
    val total = entries.sumOf { it.totalPages }
    return when {
        entries.any { it.totalPages <= 0 } -> if (completed > 0) "已保存 $completed 页" else "页数待获取"
        entries.all { it.status == DownloadStatus.COMPLETED } -> "$total 页"
        else -> "$completed/$total 页"
    }
}

@Composable
private fun DownloadSelection(selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val fill = animateFloatAsState(if (selected) 1f else 0f,
        if (LocalMotionEnabled.current) spring(MangaMotion.NavigationDamping, MangaMotion.PanelStiffness) else snap(),
        label = "download-selection")
    MotionIconButton(onClick = onClick, modifier = Modifier.size(44.dp).semantics {
        this.selected = selected
        contentDescription = if (selected) "取消选择" else "选择"
    }) {
        Box(Modifier.size(20.dp).drawBehind {
            val amount = fill.value.coerceIn(0f, 1f)
            val stroke = 1.65.dp.toPx()
            val radius = size.minDimension * .5f
            drawCircle(colors.onSurfaceVariant.copy(alpha = .65f * (1f - amount)), radius - stroke / 2f,
                style = Stroke(stroke))
            if (amount > 0f) drawCircle(colors.primary, radius * amount)
        }, contentAlignment = Alignment.Center) {
            DownloadCheckMark({ (fill.value - .2f) / .8f }, colors.onPrimary, Modifier.size(15.dp))
        }
    }
}

/** Transfer state retains its current pause/source-refresh/retry behavior, now without a full row menu. */
@Composable
private fun DownloadTransferAction(entries: List<DownloadEntry>, refreshingIds: Set<String>,
    onPause: (List<DownloadEntry>) -> Unit, onRetry: (List<DownloadEntry>) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val finished = entries.all { it.status == DownloadStatus.COMPLETED }
    val pausable = entries.filter { it.status in setOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.WAITING) && !it.needsSourceRefresh }
    val retryable = entries.filter { it.status != DownloadStatus.COMPLETED && it.manga.sourceUrl != LOCAL_SOURCE_URL }
    val busy = entries.any { it.id in refreshingIds }
    val state = when {
        finished -> CompactDownloadState.COMPLETE
        pausable.any { it.status == DownloadStatus.RUNNING } -> CompactDownloadState.DOWNLOADING
        pausable.isNotEmpty() -> CompactDownloadState.WAITING
        retryable.any { it.needsSourceRefresh || it.status == DownloadStatus.FAILED } -> CompactDownloadState.ERROR
        retryable.isNotEmpty() -> CompactDownloadState.PAUSED
        else -> null
    }
    val interaction = remember { MutableInteractionSource() }
    val enabled = !busy && !finished && state != null
    val action = if (finished) Modifier else Modifier.clip(RoundedCornerShape(50)).clickable(
        enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button,
        onClick = { if (pausable.isNotEmpty()) onPause(pausable) else onRetry(retryable) })
        .motionPress(interaction, enabled)
    Box(Modifier.size(44.dp).then(action), contentAlignment = Alignment.Center) {
        when {
            busy && !finished -> CircularProgressIndicator(Modifier.size(15.dp).semantics {
                contentDescription = "正在刷新下载来源"
            }, strokeWidth = 1.5.dp, color = colors.primary)
            state != null -> CompactDownloadIcon(state, downloadKnownProgress(entries),
                contentDescription = when (state) {
                    CompactDownloadState.COMPLETE -> "已下载"
                    CompactDownloadState.ERROR -> "刷新并重试下载"
                    CompactDownloadState.PAUSED -> "继续下载"
                    else -> "暂停下载"
                }, tint = when (state) {
                    CompactDownloadState.COMPLETE -> colors.onSurfaceVariant.copy(alpha = .75f)
                    CompactDownloadState.ERROR -> colors.error
                    else -> colors.primary
                })
            else -> Icon(Icons.Outlined.Info, "文件不可用，请删除后重新导入", tint = colors.error)
        }
    }
}

@Composable
internal fun DownloadProgressLine(entries: List<DownloadEntry>) {
    val knownProgress = downloadCurrentPageFraction(entries) ?: return
    val observed = downloadObservedEntry(entries) ?: return
    key(observed.id, observed.createdAt, observed.progress.pageIndex, observed.progress.generation,
        observed.progress.transferId, observed.progress.expectedBytes) {
        ManagementProgressTrack(knownProgress, "当前图片接收进度", mangaStatusColors().activity,
            Modifier.fillMaxWidth().padding(bottom = 3.dp).height(3.dp))
    }
}

private fun downloadKnownProgress(entries: List<DownloadEntry>): Float? {
    if (entries.isEmpty() || entries.any { it.totalPages <= 0 }) return null
    val total = entries.sumOf { it.totalPages.toLong() }
    return (entries.sumOf { it.completedPages.toLong() }.toFloat() / total).coerceIn(0f, 1f)
}
