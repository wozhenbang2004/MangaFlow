package dev.mangaflow.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.Page
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionSwap
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.rememberDelayedBusy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Group panes share one fixed toolbar. Changing download mode never inserts a row
 * or shifts chapters, and each pane owns its grid while a group change animates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailCatalog(
    mangaId: String,
    chapters: List<Chapter>,
    downloaded: Map<String, DownloadEntry>,
    progress: ReadingProgress?,
    chapterPositions: Map<String, ReadingProgress>?,
    loading: Boolean,
    error: String?,
    active: Boolean,
    onScrollTarget: (LazyGridState) -> Unit,
    onRetry: () -> Unit,
    onRead: (Chapter) -> Unit,
    onDownload: (Chapter) -> Unit,
    loadVolumeCover: suspend (Chapter, Boolean) -> Page,
    volumeImageHeaders: (Chapter) -> Map<String, String>,
    bottomPadding: Dp = 16.dp,
) {
    val groups = remember(chapters) { chapters.groupingBy(Chapter::group).eachCount() }
    var chosenGroup by rememberSaveable(mangaId) { mutableStateOf<String?>(null) }
    var groupVisit by rememberSaveable(mangaId) { mutableIntStateOf(0) }
    // A temporarily empty directory must not clear a restored group or its scroll position.
    val group = chosenGroup?.takeIf { groups.isEmpty() || it in groups }
    var downloadMode by rememberSaveable(mangaId) { mutableStateOf(false) }
    var shownError by remember { mutableStateOf<String?>(null) }
    var selectedChapterId by rememberSaveable(mangaId) { mutableStateOf<String?>(null) }
    var closingChapter by remember(mangaId) { mutableStateOf<Chapter?>(null) }
    val volumeCovers = remember(mangaId) { mutableStateMapOf<String, VolumeCoverState>() }
    val coverPermits = remember(mangaId) { Semaphore(2) }

    val showBusy = rememberDelayedBusy(loading)
    val shown = remember(chapters, group) {
        if (group == null) chapters else chapters.filter { it.group == group }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (groups.size > 1) {
                ChapterGroups(
                    groups = groups, total = chapters.size, selected = group,
                    onSelect = {
                        if (group != it) groupVisit++
                        chosenGroup = it
                    }, modifier = Modifier.weight(1f),
                )
            } else if (downloadMode) {
                Text("点按立即下载", color = DetailForeground,
                    fontSize = 12.sp, modifier = Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (error != null) {
                DetailAction(Icons.Outlined.Info, "目录加载提示", onClick = { shownError = error })
                DetailAction(Icons.Outlined.Refresh, "重新加载目录",
                    enabled = !loading, onClick = onRetry)
            }
            DetailAction(
                if (downloadMode) Icons.Outlined.Check else Icons.Outlined.Download,
                if (downloadMode) "退出连续下载" else "连续下载章节",
                selected = downloadMode,
                onClick = { downloadMode = !downloadMode },
            )
        }
        Box(Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 16.dp)) {
            if (showBusy) LinearProgressIndicator(
                modifier = Modifier.fillMaxSize(), color = DetailSecondary, trackColor = Color.Transparent)
        }
        MotionTabContent(
            selected = group, modifier = Modifier.weight(1f).fillMaxWidth(),
            contentKey = { if (it == null) "all" else "group:$it" },
            order = { if (it == null) 0 else groups.keys.indexOf(it) + 1 },
        ) { targetGroup ->
            val paneActive = active && targetGroup == group
            val grid = rememberLazyGridState()
            SideEffect {
                if (paneActive) onScrollTarget(grid)
            }
            val visibleChapters = remember(chapters, targetGroup) {
                if (targetGroup == null) chapters else chapters.filter { it.group == targetGroup }
            }
            // Comments/reader restoration keeps this visit; even a quick A→B→A is a new visit.
            var appliedVisit by rememberSaveable(mangaId) { mutableIntStateOf(groupVisit) }
            LaunchedEffect(paneActive, groupVisit) {
                if (!paneActive) grid.stopScroll()
                else if (appliedVisit != groupVisit) {
                    grid.scrollToItem(0)
                    appliedVisit = groupVisit
                }
            }
            if (visibleChapters.isEmpty()) {
                Box(Modifier.fillMaxSize()
                    .scrollable(rememberScrollableState { 0f }, Orientation.Vertical, enabled = paneActive),
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
                val showGroup = targetGroup == null && groups.size > 1
                val volumesOnly = visibleChapters.all { isVolumeGroup(it.group) }
                val tileHeight = (54f * fontScale).dp
                // Source groups are consecutive. Keep that exact order and give each run
                // its own grid row so a book cover never stretches a normal chapter row.
                val runs = remember(visibleChapters) {
                    buildList<MutableList<Chapter>> {
                        visibleChapters.forEach { chapter ->
                            if (lastOrNull()?.lastOrNull()?.group == chapter.group) last().add(chapter)
                            else add(mutableListOf(chapter))
                        }
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = ((if (volumesOnly) 96f else 84f) * fontScale).dp),
                    state = grid, userScrollEnabled = paneActive,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 5.dp, bottom = bottomPadding),
                    horizontalArrangement = Arrangement.spacedBy(if (volumesOnly) 12.dp else 5.dp),
                    verticalArrangement = Arrangement.spacedBy(if (volumesOnly) 14.dp else 5.dp),
                ) {
                    runs.forEachIndexed { runIndex, run ->
                        if (showGroup) item(key = "group:$runIndex:${run.first().group}",
                            span = { GridItemSpan(maxLineSpan) }, contentType = "group") {
                            Text(run.first().group, color = DetailMuted, fontSize = 11.sp,
                                modifier = Modifier.padding(top = if (runIndex == 0) 2.dp else 10.dp, bottom = 3.dp))
                        }
                        items(run, key = Chapter::id,
                            contentType = { if (isVolumeGroup(it.group)) "volume" else "chapter" }) { chapter ->
                            if (isVolumeGroup(chapter.group)) {
                                val cover = volumeCovers[chapter.id] ?: VolumeCoverState()
                                LaunchedEffect(chapter.id, paneActive, cover.attempt) {
                                    if (!paneActive || cover.page != null || cover.error != null) return@LaunchedEffect
                                    val pending = cover.copy(loading = true)
                                    volumeCovers[chapter.id] = pending
                                    try {
                                        val page = coverPermits.withPermit {
                                            loadVolumeCover(chapter, cover.attempt > 0)
                                        }
                                        currentCoroutineContext().ensureActive()
                                        if (volumeCovers[chapter.id] === pending)
                                            volumeCovers[chapter.id] = pending.copy(page = page, loading = false)
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (failure: Exception) {
                                        if (volumeCovers[chapter.id] === pending)
                                            volumeCovers[chapter.id] = pending.copy(loading = false,
                                                error = failure.message ?: "这一卷封面暂不可用")
                                    } finally {
                                        if (volumeCovers[chapter.id] === pending)
                                            volumeCovers[chapter.id] = VolumeCoverState(attempt = pending.attempt)
                                    }
                                }
                                VolumeBookTile(
                                    mangaId = mangaId, chapter = chapter, cover = cover,
                                    imageHeaders = { volumeImageHeaders(chapter) },
                                    entry = downloaded[chapter.id],
                                    progress = chapterPositions?.get(chapter.id) ?: progress?.takeIf { it.chapterId == chapter.id },
                                    progressKnown = chapterPositions != null,
                                    current = progress?.chapterId == chapter.id,
                                    downloadMode = downloadMode,
                                    enabled = !downloadMode || canDownload(downloaded[chapter.id]),
                                    onRead = { onRead(chapter) }, onDownload = { onDownload(chapter) },
                                    onActions = { selectedChapterId = chapter.id },
                                    onCoverError = {
                                        if (paneActive && volumeCovers[chapter.id] === cover)
                                            volumeCovers[chapter.id] = cover.copy(error = "封面图片暂不可用")
                                    },
                                    onRetryCover = {
                                        volumeCovers[chapter.id] = VolumeCoverState(attempt = cover.attempt + 1)
                                    },
                                )
                            } else ChapterTile(
                                chapter = chapter,
                                entry = downloaded[chapter.id],
                                progress = chapterPositions?.get(chapter.id) ?: progress?.takeIf { it.chapterId == chapter.id },
                                current = progress?.chapterId == chapter.id,
                                progressKnown = chapterPositions != null,
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
                        chapterPositions?.get(chapter.id) ?: progress?.takeIf { it.chapterId == chapter.id }))
                        .filter(String::isNotBlank).joinToString(" · "),
                    color = DetailMuted, fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                TextButton(onClick = { dismissActions(); onRead(chapter) },
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (isVolumeGroup(chapter.group)) "阅读这一卷" else "阅读这一章", color = DetailForeground)
                }
                TextButton(onClick = { dismissActions(); onDownload(chapter) },
                    enabled = canDownload(entry), modifier = Modifier.fillMaxWidth()) {
                    Text(if (entry?.status == DownloadStatus.COMPLETED) "已下载" else
                        if (isVolumeGroup(chapter.group)) "下载这一卷" else "下载这一章",
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
    val labels = choices.map { (name, _) -> name?.ifBlank { "未分组" } ?: "全部" }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val textStyle = LocalTextStyle.current
    val minimumSlot = with(density) {
        choices.indices.maxOf { index ->
            measurer.measure(labels[index], textStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), maxLines = 1).size.width +
                measurer.measure(choices[index].second.toString(), textStyle.copy(fontSize = 10.sp), maxLines = 1).size.width
        }.toDp() + 32.dp
    }
    BoxWithConstraints(modifier) {
        val tabsWidth = maxOf(maxWidth, minimumSlot * choices.size)
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), contentAlignment = Alignment.CenterStart) {
            MotionTabs(
                modifier = Modifier.width(tabsWidth),
                selected = choices.indexOfFirst { it.first == selected }, labels = labels,
                onSelect = { onSelect(choices[it].first) },
                foreground = DetailForeground, muted = DetailSecondary, indicator = DetailForeground.copy(alpha = .065f),
                fullWidthIndicator = true, controlHeight = 40.dp, fontSize = 12.sp,
                treatment = MotionTabTreatment.Soft,
                itemContent = { index, chosen, color ->
                    val label = labels[index]
                    val count = choices[index].second
                    Row(Modifier.semantics { contentDescription = "$label，$count " + if (isVolumeGroup(label)) "卷" else "话" },
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = color, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium,
                            fontSize = 12.sp, maxLines = 1)
                        Text(count.toString(), color = DetailMuted, fontSize = 10.sp, maxLines = 1)
                    }
                },
            )
        }
    }
}

private fun isVolumeGroup(name: String): Boolean = name.contains("单行本") || name.contains("單行本")

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterTile(
    chapter: Chapter,
    entry: DownloadEntry?,
    progress: ReadingProgress?,
    current: Boolean,
    progressKnown: Boolean,
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
            .background(DetailForeground.copy(alpha = when {
                current -> .08f
                detailReadingFraction(progress) == 1f -> .025f
                else -> .045f
            }))
            .semantics {
                contentDescription = chapter.title
                stateDescription = listOf(chapterReadingStatus(progress, progressKnown), chapterStatus(entry, null))
                    .filter(String::isNotBlank).joinToString("，")
            }
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                chapter.title, color = if (current) DetailForeground else if (enabled) DetailSecondary else DetailMuted,
                fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(3.dp))
            // Reserve the icon's space even before download: no resizing when a job starts.
            ChapterDownloadMark(entry, downloadMode)
        }
        // One compact state line belongs to the chapter itself, without another badge/container.
        Box(Modifier.fillMaxWidth().height((14f * LocalDensity.current.fontScale).dp)) {
            ChapterReadingStatus(progress, progressKnown)
        }
    }
}

@Composable
internal fun ChapterDownloadMark(entry: DownloadEntry?, showDownload: Boolean) {
    MotionSwap(entry?.status to showDownload, Modifier.size(14.dp)) { (status, showDownload) ->
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

private fun canDownload(entry: DownloadEntry?): Boolean = entry?.status !in setOf(
    DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.COMPLETED,
)

internal fun chapterStatus(entry: DownloadEntry?, progress: ReadingProgress?): String = listOfNotNull(
    progress?.let { chapterReadingStatus(it) },
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
