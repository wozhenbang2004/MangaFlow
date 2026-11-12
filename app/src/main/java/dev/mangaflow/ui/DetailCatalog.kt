package dev.mangaflow.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.data.Chapter
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionSwap
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.rememberDelayedBusy

/**
 * One stable, lazily-composed chapter grid, with groups and download selection in a
 * fixed toolbar. Changing download mode never inserts a row or shifts the chapters.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailCatalog(
    mangaId: String,
    chapters: List<Chapter>,
    downloaded: Map<String, DownloadEntry>,
    progress: ReadingProgress?,
    loading: Boolean,
    error: String?,
    scrolling: NestedScrollConnection,
    onRetry: () -> Unit,
    onRead: (Chapter) -> Unit,
    onDownload: (Chapter) -> Unit,
) {
    val groups = remember(chapters) { chapters.groupingBy(Chapter::group).eachCount() }
    var chosenGroup by rememberSaveable(mangaId) { mutableStateOf<String?>(null) }
    // A temporarily empty directory must not clear a restored group or its scroll position.
    val group = chosenGroup?.takeIf { groups.isEmpty() || it in groups }
    var downloadMode by rememberSaveable(mangaId) { mutableStateOf(false) }
    var shownError by remember { mutableStateOf<String?>(null) }
    var selectedChapterId by rememberSaveable(mangaId) { mutableStateOf<String?>(null) }
    var closingChapter by remember(mangaId) { mutableStateOf<Chapter?>(null) }

    val grid = rememberLazyGridState()
    val showBusy = rememberDelayedBusy(loading)
    val shown = remember(chapters, group) {
        if (group == null) chapters else chapters.filter { it.group == group }
    }

    // Restore the same grid position after visiting Comments or the reader.
    // Only a different chapter group returns to the start of the directory.
    var appliedGroup by rememberSaveable(mangaId) { mutableStateOf(group) }
    LaunchedEffect(group) {
        if (appliedGroup != group) {
            grid.scrollToItem(0)
            appliedGroup = group
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (groups.size > 1) {
                ChapterGroups(
                    groups = groups, total = chapters.size, selected = group,
                    onSelect = { chosenGroup = it }, modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    if (downloadMode) "点选章节下载" else (shown.size.toString() + " 话"),
                    color = if (downloadMode) DetailForeground else DetailMuted,
                    fontSize = 12.sp, modifier = Modifier.weight(1f),
                )
            }
            if (error != null) {
                DetailAction(Icons.Outlined.Info, "目录加载提示", onClick = { shownError = error })
                DetailAction(Icons.Outlined.Refresh, "重新加载目录",
                    enabled = !loading, onClick = onRetry)
            }
            DetailAction(
                if (downloadMode) Icons.Outlined.Check else Icons.Outlined.Download,
                if (downloadMode) "完成选择下载" else "选择章节下载",
                selected = downloadMode,
                onClick = { downloadMode = !downloadMode },
            )
        }
        Box(Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 16.dp)) {
            if (showBusy) LinearProgressIndicator(
                modifier = Modifier.fillMaxSize(), color = DetailSecondary, trackColor = Color.Transparent)
        }
        if (shown.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().nestedScroll(scrolling)
                .scrollable(rememberScrollableState { 0f }, Orientation.Vertical),
                contentAlignment = Alignment.Center) {
                Text(
                    when {
                        loading && chapters.isEmpty() -> "正在读取章节"
                        error != null && chapters.isEmpty() -> "目录暂不可用，点击顶部重试"
                        else -> "暂无章节"
                    },
                    color = DetailMuted, fontSize = 13.sp,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
            val showGroup = group == null && groups.size > 1
            val tileHeight = (if (showGroup) 58f else 47f).times(fontScale).dp
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = (74f * fontScale).dp),
                state = grid,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)
                    .nestedScroll(scrolling),
                contentPadding = PaddingValues(top = 5.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                items(shown, key = Chapter::id) { chapter ->
                    ChapterTile(
                        chapter = chapter,
                        entry = downloaded[chapter.id],
                        progress = progress?.takeIf { it.chapterId == chapter.id },
                        showGroup = showGroup,
                        downloadMode = downloadMode,
                        height = tileHeight,
                        onRead = { onRead(chapter) },
                        onDownload = { onDownload(chapter) },
                        onActions = { selectedChapterId = chapter.id },
                    )
                }
            }
        }
    }

    // Keep the opened message until dismissal finishes, even when retry clears the source error.
    shownError?.let { message ->
        MotionBottomSheet(
            onDismissRequest = { shownError = null },
            containerColor = DetailSurface, contentColor = DetailForeground,
        ) { dismiss ->
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text("目录加载提示", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(message, color = DetailSecondary, fontSize = 14.sp, lineHeight = 23.sp,
                    modifier = Modifier.padding(vertical = 16.dp))
                TextButton(onClick = { dismiss(); onRetry() }, enabled = !loading) {
                    Text("重新加载", color = DetailForeground)
                }
            }
        }
    }
    (chapters.firstOrNull { it.id == selectedChapterId } ?: closingChapter)?.let { chapter ->
        val entry = downloaded[chapter.id]
        MotionBottomSheet(
            onDismissRequest = { selectedChapterId = null; closingChapter = null },
            containerColor = DetailSurface, contentColor = DetailForeground,
        ) { dismiss ->
            val dismissActions = {
                // A route change can cancel hide(); never restore the already-dismissed menu.
                closingChapter = chapter
                selectedChapterId = null
                dismiss()
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text(chapter.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
                Text(
                    listOf(chapter.group, chapterStatus(entry,
                        progress?.takeIf { it.chapterId == chapter.id }))
                        .filter(String::isNotBlank).joinToString(" · "),
                    color = DetailMuted, fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                TextButton(onClick = { dismissActions(); onRead(chapter) },
                    modifier = Modifier.fillMaxWidth()) {
                    Text("阅读这一章", color = DetailForeground)
                }
                TextButton(onClick = { dismissActions(); onDownload(chapter) },
                    enabled = canDownload(entry), modifier = Modifier.fillMaxWidth()) {
                    Text(if (entry?.status == DownloadStatus.COMPLETED) "已下载" else "下载这一章",
                        color = if (canDownload(entry)) DetailForeground else DetailMuted)
                }
            }
        }
    }
}

@Composable
private fun ChapterGroups(
    groups: Map<String, Int>,
    total: Int,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val choices = remember(groups, total) {
        buildList<Pair<String?, Int>> {
            add(null to total)
            groups.forEach { (name, count) -> add(name to count) }
        }
    }
    val motion = LocalMotionEnabled.current
    Row(
        modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        choices.forEach { (name, count) ->
            key(name) {
                val active = selected == name
                val label = name?.ifBlank { "未分组" } ?: "全部"
                val interaction = remember { MutableInteractionSource() }
                val tint by animateColorAsState(
                    if (active) DetailForeground.copy(alpha = .075f) else Color.Transparent,
                    animationSpec = if (motion) tween(MangaMotion.Quick) else snap(),
                    label = "chapter-group-tint",
                )
                Box(
                    Modifier.height(48.dp).widthIn(min = 48.dp)
                        .selectable(
                            selected = active, role = Role.Tab,
                            interactionSource = interaction, indication = null,
                            onClick = { onSelect(name) },
                        ).semantics { contentDescription = "$label，$count 话" },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        Modifier.motionPress(interaction)
                            .clip(RoundedCornerShape(9.dp)).background(tint)
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, color = if (active) DetailForeground else DetailSecondary,
                            fontWeight = FontWeight.Medium, fontSize = 12.sp, maxLines = 1)
                        Text(count.toString(), color = DetailMuted, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterTile(
    chapter: Chapter,
    entry: DownloadEntry?,
    progress: ReadingProgress?,
    showGroup: Boolean,
    downloadMode: Boolean,
    height: Dp,
    onRead: () -> Unit,
    onDownload: () -> Unit,
    onActions: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val enabled = !downloadMode || canDownload(entry)
    Column(
        Modifier.fillMaxWidth().height(height)
            .combinedClickable(
                interactionSource = interaction, indication = null, enabled = enabled,
                role = Role.Button,
                onClickLabel = if (downloadMode) "下载章节" else "阅读章节",
                onLongClickLabel = "章节详情和操作",
                onClick = if (downloadMode) onDownload else onRead,
                onLongClick = onActions,
            )
            .motionPress(interaction, enabled = enabled)
            .clip(RoundedCornerShape(9.dp))
            .background(DetailForeground.copy(alpha = if (progress != null) .12f else .055f))
            .semantics {
                contentDescription = chapter.title + if (showGroup) "，" + chapter.group else ""
                stateDescription = chapterStatus(entry, progress)
            }
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                chapter.title, color = if (progress != null) DetailForeground else if (enabled) DetailSecondary else DetailMuted,
                fontSize = 11.sp, lineHeight = 15.sp, maxLines = if (showGroup) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (progress != null) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(3.dp))
            // Reserve the icon's space even before download: no resizing when a job starts.
            MotionSwap(entry?.status to (downloadMode && entry == null), Modifier.size(14.dp)) { (status, showDownload) ->
                when (status) {
                    DownloadStatus.RUNNING -> if (entry != null && entry.totalPages > 0) {
                        CircularProgressIndicator(
                            progress = { (entry.completedPages.toFloat() / entry.totalPages).coerceIn(0f, 1f) },
                            modifier = Modifier.size(12.dp), color = DetailSecondary, strokeWidth = 1.dp)
                    } else Icon(Icons.Outlined.Download, "正在准备下载",
                        tint = DetailMuted, modifier = Modifier.size(14.dp))
                    DownloadStatus.QUEUED -> Icon(Icons.Outlined.Schedule, "等待下载",
                        tint = DetailMuted, modifier = Modifier.size(14.dp))
                    DownloadStatus.COMPLETED -> Icon(Icons.Outlined.Check, "已下载",
                        tint = DetailForeground, modifier = Modifier.size(14.dp))
                    DownloadStatus.PAUSED, DownloadStatus.WAITING -> Icon(Icons.Outlined.Pause,
                        "下载暂停", tint = DetailMuted, modifier = Modifier.size(14.dp))
                    DownloadStatus.FAILED -> Icon(Icons.Outlined.Refresh, "下载失败",
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                    else -> if (showDownload) Icon(Icons.Outlined.Download, "下载",
                        tint = DetailMuted, modifier = Modifier.size(14.dp))
                }
            }
        }
        if (showGroup) Text(
            chapter.group, color = DetailMuted, fontSize = 9.sp, lineHeight = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private fun canDownload(entry: DownloadEntry?): Boolean = entry?.status !in setOf(
    DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.COMPLETED,
)

private fun chapterStatus(entry: DownloadEntry?, progress: ReadingProgress?): String = listOfNotNull(
    progress?.let { "上次读到第 " + (it.pageIndex + 1) + " 页" },
    when (entry?.status) {
        DownloadStatus.RUNNING -> "正在下载 " + entry.completedPages + "/" + entry.totalPages
        DownloadStatus.QUEUED -> "等待下载"
        DownloadStatus.WAITING -> "等待恢复下载"
        DownloadStatus.PAUSED -> "下载已暂停"
        DownloadStatus.FAILED -> "下载未完成，可重试"
        DownloadStatus.COMPLETED -> "已下载"
        else -> null
    },
).joinToString("，")
