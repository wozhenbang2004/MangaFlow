package dev.mangaflow.ui

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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import dev.mangaflow.ui.motion.MotionBusyIndicator as CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.DownloadStore.Companion.LOCAL_SOURCE_URL
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionSwap
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
    onPause: (DownloadEntry) -> Unit,
    onRetry: (DownloadEntry) -> Unit,
    onDetails: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    inspectMode: Boolean = false,
    active: Boolean = true,
    onInspect: ((DownloadEntry) -> Unit)? = null,
) {
    val desktop = platformKind == PlatformKind.DESKTOP
    val actions = buildList {
        add(DesktopMenuAction("继续阅读", onClick = onRead))
        onDetails?.let { add(DesktopMenuAction("详情与目录", onClick = it)) }
        add(DesktopMenuAction("查看章节", onClick = onExpand))
        if (onInspect != null) downloadObservedEntry(entries)?.let { task ->
            add(DesktopMenuAction("下载状态与线路", onClick = { onInspect(task) }))
        }
        add(DesktopMenuAction("选择下载", onClick = onManage))
        onRemove?.let { add(DesktopMenuAction("删除下载", onClick = it)) }
    }
    val manga = entries.first().manga
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f,
        tween(if (LocalMotionEnabled.current) MangaMotion.Content else 0), label = "download-book-disclosure")
    DesktopContextMenuArea(actions) {
    Column {
        Row(Modifier.fillMaxWidth().then(if (desktop && inspectMode && expanded)
            Modifier.clip(RoundedCornerShape(12.dp)).background(colors.onSurface.copy(alpha = .045f))
            else Modifier).combinedClickable(
            interactionSource = interaction, indication = null,
            onClickLabel = if (managing) "选择这部作品的章节" else "继续阅读 ${manga.title}",
            onClick = if (managing) onSelect else onRead,
            onLongClickLabel = "管理这部作品的下载", onLongClick = onManage,
        ).motionPress(interaction, pressedScale = .99f).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (manga.sourceUrl == LOCAL_SOURCE_URL) {
                AsyncImage(manga.coverUrl, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp, 68.dp).mangaCoverCard(8.dp)
                        .background(colors.surfaceVariant))
            } else {
                MangaCover(manga, repository, Modifier.size(48.dp, 68.dp).mangaCoverCard(8.dp),
                    scale = ContentScale.Crop)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE), fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium,
                    color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                DownloadInlineTransferSummary(entries, active, if (managing) null else onInspect)
                DownloadProgressLine(entries)
            }
            if (managing) DownloadSelection(selected, onSelect)
            else DownloadTransferAction(entries, refreshingIds, onPause, onRetry)
            if (entries.size > 1 || desktop) MotionIconButton(onClick = onExpand, modifier = Modifier.size(if (desktop) 36.dp else 44.dp)) {
                Icon(if (inspectMode) Icons.Outlined.ChevronRight else Icons.Outlined.KeyboardArrowDown,
                    if (inspectMode) "查看章节" else if (expanded) "收起章节" else "展开 ${entries.size} 话",
                    Modifier.size(20.dp).graphicsLayer { rotationZ = if (inspectMode) 0f else rotation },
                    tint = if (inspectMode && expanded) colors.onSurface else colors.onSurfaceVariant)
            }
            if (desktop) {
                if (onDetails != null) MotionIconButton(onClick = onDetails, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.MenuBook, "详情与目录", Modifier.size(17.dp))
                }
                DesktopMoreButton(actions, "作品下载操作")
            }
        }
        // Multi-chapter failures belong to their disclosed chapter rows; do not copy every
        // complete error into the collapsed book or grow one failure row per work.
        if (!managing && onInspect == null && entries.size == 1 && entries.first().error.isNotBlank())
            DownloadFailureDetails(entries.first().error, Modifier.padding(start = 60.dp))

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
) {
    val desktop = platformKind == PlatformKind.DESKTOP
    val actions = buildList {
        add(DesktopMenuAction("阅读这一话", onClick = onRead))
        if (onInspect != null) add(DesktopMenuAction("下载状态与线路", onClick = { onInspect(entry) }))
        add(DesktopMenuAction("选择这一话", onClick = onManage))
        onRemove?.let { add(DesktopMenuAction("删除这一话", onClick = it)) }
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
        .background(if (selected) colors.primary.copy(alpha = .10f) else colors.onSurface.copy(alpha = .025f))
        .padding(start = 10.dp, end = 2.dp, top = 3.dp, bottom = 3.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.chapter.title, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium,
                    color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val source = dev.mangaflow.data.sourceDisplayName(
                    dev.mangaflow.data.SourceKeys.decodeChapter(chapterSourceMangaId(entry.manga.id, entry.chapter), entry.chapter.id).sourceId)
                DownloadInlineTransferSummary(listOf(entry), active, if (managing) null else onInspect, source)
                DownloadProgressLine(listOf(entry))
            }
            if (managing) DownloadSelection(selected, onSelect)
            else DownloadTransferAction(listOf(entry), refreshingIds, onPause, onRetry)
            if (desktop) DesktopMoreButton(actions, "章节下载操作")
        }
        if (!managing && onInspect == null && entry.error.isNotBlank()) DownloadFailureDetails(entry.error)
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
        Text(mangaNumberText(detail), style = MaterialTheme.typography.labelSmall,
            color = if (entry.status == DownloadStatus.FAILED) colors.error else colors.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DownloadFailureDetails(message: String, modifier: Modifier = Modifier) {
    var expanded by remember(message) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotionEnabled.current
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
                rotationZ = if (expanded) 180f else 0f
            }, tint = colors.onSurfaceVariant)
        }
        AnimatedVisibility(expanded,
            enter = fadeIn(tween(if (motion) MangaMotion.Quick else 0)) +
                expandVertically(tween(if (motion) MangaMotion.Content else 0)),
            exit = fadeOut(tween(if (motion) MangaMotion.Quick else 0)) +
                shrinkVertically(tween(if (motion) MangaMotion.Content else 0))) {
            SelectionContainer {
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
    MotionIconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Icon(if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            if (selected) "取消选择" else "选择", Modifier.size(20.dp),
            tint = if (selected) colors.primary else colors.onSurfaceVariant.copy(alpha = .6f))
    }
}

/** Transfer state retains its current pause/source-refresh/retry behavior, now without a full row menu. */
@Composable
private fun DownloadTransferAction(entries: List<DownloadEntry>, refreshingIds: Set<String>,
    onPause: (DownloadEntry) -> Unit, onRetry: (DownloadEntry) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val finished = entries.all { it.status == DownloadStatus.COMPLETED }
    val pausable = entries.filter { it.status in setOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.WAITING) && !it.needsSourceRefresh }
    val retryable = entries.filter { it.status != DownloadStatus.COMPLETED && it.manga.sourceUrl != LOCAL_SOURCE_URL }
    val busy = entries.any { it.id in refreshingIds }
    if (finished) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            CompactDownloadIcon(CompactDownloadState.COMPLETE, contentDescription = "已下载",
                tint = colors.onSurfaceVariant.copy(alpha = .65f))
        }
        return
    }
    MotionIconButton(enabled = !busy && (pausable.isNotEmpty() || retryable.isNotEmpty()),
        modifier = Modifier.size(44.dp),
        onClick = { if (pausable.isNotEmpty()) pausable.forEach(onPause) else retryable.forEach(onRetry) }) {
        val state = when {
            busy -> "busy"
            pausable.isNotEmpty() -> "pause"
            retryable.any { it.needsSourceRefresh || it.status == DownloadStatus.FAILED } -> "retry"
            retryable.isNotEmpty() -> "resume"
            else -> "unavailable"
        }
        MotionSwap(state, Modifier.size(24.dp)) {
            when (it) {
                "busy" -> CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp, color = colors.primary)
                "pause" -> CompactDownloadIcon(
                    if (pausable.any { entry -> entry.status == DownloadStatus.RUNNING }) CompactDownloadState.DOWNLOADING
                        else CompactDownloadState.WAITING,
                    progress = downloadKnownProgress(entries),
                    contentDescription = "暂停下载", tint = colors.primary)
                "retry" -> CompactDownloadIcon(CompactDownloadState.ERROR,
                    contentDescription = "刷新并重试下载", tint = colors.error)
                "resume" -> CompactDownloadIcon(CompactDownloadState.PAUSED,
                    progress = downloadKnownProgress(entries), contentDescription = "继续下载", tint = colors.primary)
                else -> Icon(Icons.Outlined.Info, "文件不可用，请删除后重新导入", tint = colors.error)
            }
        }
    }
}

@Composable
private fun DownloadProgressLine(entries: List<DownloadEntry>) {
    val knownProgress = downloadCurrentPageFraction(entries)
    val target = knownProgress ?: 0f
    val progress by animateFloatAsState(target, tween(if (LocalMotionEnabled.current) MangaMotion.Quick else 0), label = "download-real-progress")
    val color = MaterialTheme.colorScheme.primary
    val visible = knownProgress != null && entries.any { it.status != DownloadStatus.COMPLETED }
    Box(Modifier.fillMaxWidth().height(6.dp).semantics {
        if (visible) {
            contentDescription = "当前图片接收进度"
            progressBarRangeInfo = ProgressBarRangeInfo(knownProgress!!, 0f..1f)
        }
    }.drawBehind {
        if (visible) {
            val y = size.height / 2f
            drawLine(color.copy(alpha = .12f), Offset(0f, y), Offset(size.width, y), 2.dp.toPx(), StrokeCap.Round)
            if (progress > 0f) drawLine(color.copy(alpha = .8f), Offset(0f, y), Offset(size.width * progress, y), 2.dp.toPx(), StrokeCap.Round)
        }
    })
}

private fun downloadKnownProgress(entries: List<DownloadEntry>): Float? {
    if (entries.isEmpty() || entries.any { it.totalPages <= 0 }) return null
    val total = entries.sumOf { it.totalPages }
    return (entries.sumOf { it.completedPages }.toFloat() / total).coerceIn(0f, 1f)
}
