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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
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
) {
    val desktop = platformKind == PlatformKind.DESKTOP
    val actions = buildList {
        add(DesktopMenuAction("继续阅读", onClick = onRead))
        onDetails?.let { add(DesktopMenuAction("详情与目录", onClick = it)) }
        add(DesktopMenuAction("查看章节", onClick = onExpand))
        add(DesktopMenuAction("选择下载", onClick = onManage))
        onRemove?.let { add(DesktopMenuAction("删除下载", onClick = it)) }
    }
    val manga = entries.first().manga
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val allSaved = entries.all { it.status == DownloadStatus.COMPLETED }
    val failed = entries.count { it.status == DownloadStatus.FAILED }
    val saved = entries.count { it.status == DownloadStatus.COMPLETED }
    val pages = entries.sumOf { it.totalPages }
    val completedPages = entries.sumOf { it.completedPages }
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
                val bytes = downloadBytes(entries.sumOf { it.downloadedBytes })
                Text(mangaNumberText(when {
                    entries.size == 1 -> "${entries.first().chapter.title} · ${downloadPageProgress(completedPages, pages, allSaved)} · $bytes"
                    allSaved -> "${entries.size} 话 · $pages 页 · $bytes"
                    else -> "$saved/${entries.size} 话已保存 · ${downloadPageProgress(completedPages, pages)}"
                }), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = when {
                    failed > 0 -> "$failed 话未完成"
                    entries.any { it.status == DownloadStatus.RUNNING } -> "下载中"
                    entries.any { it.status == DownloadStatus.QUEUED } -> "排队中"
                    entries.any { it.status == DownloadStatus.WAITING } -> "等待恢复"
                    entries.any { it.status == DownloadStatus.PAUSED } -> "已暂停"
                    else -> null
                }
                if (status != null) Text(mangaNumberText(if (entries.size == 1) status else "$status · $bytes"),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (failed > 0) colors.error else colors.onSurfaceVariant.copy(alpha = .8f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                DownloadProgressLine(entries)
            }
            if (managing) DownloadSelection(selected, onSelect)
            else DownloadTransferAction(entries, refreshingIds, onPause, onRetry)
            if (entries.size > 1 || desktop) MotionIconButton(onClick = onExpand, modifier = Modifier.size(if (desktop) 36.dp else 40.dp)) {
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
        val failure = remember(entries) {
            entries.filter { it.error.isNotBlank() }.joinToString("\n\n") { it.chapter.title + "\n" + it.error }
        }
        if (!expanded && failure.isNotBlank()) DownloadFailureDetails(failure, Modifier.padding(start = 60.dp))

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
) {
    val desktop = platformKind == PlatformKind.DESKTOP
    val actions = buildList {
        add(DesktopMenuAction("阅读这一话", onClick = onRead))
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
        .clip(RoundedCornerShape(12.dp))
        .background(if (selected) colors.primary.copy(alpha = .10f) else colors.onSurface.copy(alpha = .035f))
        .padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.chapter.title, fontSize = if (desktop) 14.sp else 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
                    color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val source = dev.mangaflow.data.sourceDisplayName(
                    dev.mangaflow.data.SourceKeys.decodeChapter(chapterSourceMangaId(entry.manga.id, entry.chapter), entry.chapter.id).sourceId)
                Text(mangaNumberText(source + " · " + if (entry.status == DownloadStatus.COMPLETED) "${entry.totalPages} 页 · ${downloadBytes(entry.downloadedBytes)}"
                    else "${downloadStateLabel(entry)} · ${downloadPageProgress(entry.completedPages, entry.totalPages)}"),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entry.status == DownloadStatus.FAILED) colors.error else colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                DownloadProgressLine(listOf(entry))
            }
            if (managing) Icon(if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                if (selected) "已选择" else "未选择", Modifier.size(14.dp), tint = colors.primary)
            else DownloadTransferAction(listOf(entry), refreshingIds, onPause, onRetry)
            if (desktop) DesktopMoreButton(actions, "章节下载操作")
        }
        if (entry.error.isNotBlank()) DownloadFailureDetails(entry.error)
    }
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
            Text("原因", style = MaterialTheme.typography.labelSmall, color = colors.error)
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

private fun downloadPageProgress(completed: Int, total: Int, saved: Boolean = false): String = when {
    total <= 0 -> "已保存 $completed 页"
    saved -> "$total 页"
    else -> "$completed/$total 页"
}

@Composable
private fun DownloadSelection(selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    MotionIconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
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
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Check, "已下载", Modifier.size(14.dp), tint = colors.onSurfaceVariant.copy(alpha = .65f))
        }
        return
    }
    MotionIconButton(enabled = !busy && (pausable.isNotEmpty() || retryable.isNotEmpty()),
        modifier = Modifier.size(40.dp),
        onClick = { if (pausable.isNotEmpty()) pausable.forEach(onPause) else retryable.forEach(onRetry) }) {
        val state = when {
            busy -> "busy"
            pausable.isNotEmpty() -> "pause"
            retryable.any { it.needsSourceRefresh || it.status == DownloadStatus.FAILED } -> "retry"
            retryable.isNotEmpty() -> "resume"
            else -> "unavailable"
        }
        MotionSwap(state, Modifier.size(18.dp)) {
            when (it) {
                "busy" -> CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp, color = colors.primary)
                "pause" -> Icon(Icons.Outlined.Pause, "暂停下载", tint = colors.primary)
                "retry" -> Icon(Icons.Outlined.Refresh, "刷新并重试下载", tint = colors.error)
                "resume" -> Icon(Icons.Outlined.PlayArrow, "继续下载", tint = colors.primary)
                else -> Icon(Icons.Outlined.Info, "文件不可用，请删除后重新导入", tint = colors.error)
            }
        }
    }
}

@Composable
private fun DownloadProgressLine(entries: List<DownloadEntry>) {
    val total = entries.sumOf { it.totalPages }
    val target = if (total > 0) (entries.sumOf { it.completedPages }.toFloat() / total).coerceIn(0f, 1f) else 0f
    val progress by animateFloatAsState(target, tween(if (LocalMotionEnabled.current) MangaMotion.Quick else 0), label = "download-real-progress")
    val color = MaterialTheme.colorScheme.primary
    val visible = entries.any { it.status != DownloadStatus.COMPLETED }
    Box(Modifier.fillMaxWidth().height(6.dp).drawBehind {
        if (visible) {
            val y = size.height / 2f
            drawLine(color.copy(alpha = .12f), Offset(0f, y), Offset(size.width, y), 2.dp.toPx(), StrokeCap.Round)
            if (progress > 0f) drawLine(color.copy(alpha = .8f), Offset(0f, y), Offset(size.width * progress, y), 2.dp.toPx(), StrokeCap.Round)
        }
    })
}

private fun downloadStateLabel(entry: DownloadEntry): String = when (entry.status) {
    DownloadStatus.QUEUED -> "排队中"
    DownloadStatus.RUNNING -> "下载中"
    DownloadStatus.WAITING -> "等待恢复"
    DownloadStatus.PAUSED -> "已暂停"
    DownloadStatus.FAILED -> "未完成"
    DownloadStatus.COMPLETED -> "已保存"
}
