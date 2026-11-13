package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

import dev.mangaflow.platform.*

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import dev.mangaflow.ui.motion.MotionTextButton as TextButton
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onGloballyPositioned
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
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.data.sourceDisplayName
import dev.mangaflow.data.ChapterVariant
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.Page
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionSwap
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.LocalTabVisibilityScope
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.rememberDelayedBusy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
    downloadMode: Boolean,
    backdrop: HazeState,
    onReadVariant: (Chapter, ChapterVariant) -> Unit = { chapter, _ -> onRead(chapter) },
    onSetPreferredSource: (String) -> Unit = {},
    onClearChapterSource: (Chapter) -> Unit = {},
    onSplitVariant: (Chapter, ChapterVariant) -> Unit = { _, _ -> },
    onRelinkVariant: (Chapter, ChapterVariant, Chapter) -> Unit = { _, _, _ -> },
    onManageSources: () -> Unit = {},
    sourceLoading: Boolean = false,
    sourceError: String? = null,
    sourcesChecking: Boolean = false,
    onCancelSourceSelection: () -> Unit = {},
) {
    val groups = remember(chapters) { chapters.groupingBy(Chapter::group).eachCount() }
    val primarySourceId = remember(mangaId) { SourceKeys.decodeManga(mangaId).sourceId }
    var chosenGroup by rememberSaveable(mangaId) { mutableStateOf<String?>(null) }
    var groupVisit by rememberSaveable(mangaId) { mutableIntStateOf(0) }
    // A temporarily empty directory must not clear a restored group or its scroll position.
    val group = chosenGroup?.takeIf { groups.isEmpty() || it in groups }
    var shownError by remember { mutableStateOf<String?>(null) }
    var selectedChapterId by rememberSaveable(mangaId) { mutableStateOf<String?>(null) }
    var closingChapter by remember(mangaId) { mutableStateOf<Chapter?>(null) }
    var pendingSourceChapter by remember(mangaId) { mutableStateOf<Chapter?>(null) }
    var sourceChapter by remember(mangaId) { mutableStateOf<Chapter?>(null) }
    var sourceAnchor by remember(mangaId) { mutableStateOf<Rect?>(null) }
    var sourcePresent by remember(mangaId) { mutableStateOf(false) }
    var sourceCloseRequested by remember(mangaId) { mutableStateOf(false) }
    val sourceCount = remember(chapters) { chapters.flatMap { it.variants }.map { it.sourceId }.distinct().size }
    LaunchedEffect(active, chapters, sourceLoading) {
        val selected = sourceChapter
        val current = selected?.let { original ->
            chapters.firstOrNull { it.logicalId.ifBlank { it.id } == original.logicalId.ifBlank { original.id } }
        }
        if (selected != null && (!active || (!sourceLoading && current != null && current.id != selected.id))) {
            sourceCloseRequested = true
        }
    }
    val volumeCovers = remember(mangaId) { mutableStateMapOf<String, VolumeCoverState>() }
    val coverPermits = remember(mangaId) { Semaphore(2) }

    val presence = LocalTabVisibilityScope.current
    val flow = if (presence != null && LocalMotionEnabled.current) with(presence) {
        Modifier.animateEnterExit(
            enter = fadeIn(tween(MangaMotion.Content)) +
                slideInVertically(spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness)) { -it / 2 } +
                expandVertically(spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness),
                    expandFrom = Alignment.Top),
            exit = fadeOut(tween(MangaMotion.Quick)) +
                slideOutVertically(tween(MangaMotion.Quick)) { -it / 2 } +
                shrinkVertically(tween(MangaMotion.Quick), shrinkTowards = Alignment.Top))
    } else Modifier
    val showBusy = rememberDelayedBusy(loading)
    val shown = remember(chapters, group) {
        if (group == null) chapters else chapters.filter { it.group == group }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().then(flow).heightIn(min = 44.dp).padding(horizontal = 16.dp),
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
            } else {
                Text(if (sourceCount > 1) "已合并 $sourceCount 源" else "章节 ${chapters.size}",
                    color = DetailMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            }
            TextButton(onClick = onManageSources) {
                if (sourcesChecking || sourceLoading) {
                    MotionBusyIndicator(Modifier.size(11.dp), color = DetailMuted, strokeWidth = 1.2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (sourceCount > 1) "$sourceCount 源" else sourceDisplayName(primarySourceId),
                    color = DetailMuted, fontSize = 11.sp)
                Icon(Icons.Outlined.ExpandMore, "查看自动合并的漫画来源", Modifier.size(17.dp), tint = DetailSecondary)
            }
            if (error != null) {
                DetailAction(Icons.Outlined.Info, "目录加载提示", onClick = { shownError = error })
                DetailAction(Icons.Outlined.Refresh, "重新加载目录",
                    enabled = !loading, onClick = onRetry)
            }
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
            val paneActive = active && targetGroup == group && !sourcePresent
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
                val tileHeight = (40f * fontScale + 8f).dp
                // Source groups are consecutive. Keep that exact order and give each run
                // its own grid row so a book cover never stretches a normal chapter row.
                val runs = remember(visibleChapters) {
                    buildList<MutableList<Chapter>> {
                        visibleChapters.forEach { chapter ->
                            if (lastOrNull()?.lastOrNull()?.let { it.group == chapter.group &&
                                    isVolumeChapter(it) == isVolumeChapter(chapter) } == true) last().add(chapter)
                            else add(mutableListOf(chapter))
                        }
                    }
                }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    // Four compact columns on ordinary phones; only narrow windows or
                    // larger type reduce the count. Covers and chapter cells use the same slots.
                    val chapterColumns = MangaLayout.columns(maxWidth, 4, 64.dp * fontScale, 6.dp, 16.dp)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(chapterColumns),
                        state = grid, userScrollEnabled = paneActive,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 5.dp, bottom = bottomPadding),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        // Reading and source marks share the compact secondary line.
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        runs.forEachIndexed { runIndex, run ->
                            if (showGroup) item(key = "group:$runIndex:${run.first().group}",
                                span = { GridItemSpan(maxLineSpan) }, contentType = "group") {
                                Text(chapterGroupLabel(run.first().group), color = DetailMuted, fontSize = 11.sp,
                                    modifier = Modifier.padding(top = if (runIndex == 0) 2.dp else 10.dp, bottom = 3.dp))
                            }
                            if (!showGroup && runIndex > 0) item(key = "run-break:$runIndex",
                                span = { GridItemSpan(maxLineSpan) }, contentType = "run-break") { Spacer(Modifier.height(4.dp)) }
                            items(run, key = { it.logicalId.ifBlank { it.id } },
                                contentType = { if (isVolumeChapter(it)) "volume" else "chapter" }) { chapter ->
                                val showSources = chapter.variants.size > 1 ||
                                    (chapter.variants.isNotEmpty() && chapter.sourceId != primarySourceId)
                                if (isVolumeChapter(chapter)) {
                                    val cover = volumeCovers[chapter.id] ?: VolumeCoverState()
                                    LaunchedEffect(chapter.id, paneActive, cover.error, cover.attempt) {
                                        if (paneActive && cover.error != null && cover.automaticRetries < 2) {
                                            delay(900L * (cover.automaticRetries + 1))
                                            if (volumeCovers[chapter.id] === cover)
                                                volumeCovers[chapter.id] = VolumeCoverState(
                                                    attempt = cover.attempt + 1,
                                                    automaticRetries = cover.automaticRetries + 1)
                                        }
                                    }
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
                                                volumeCovers[chapter.id] = VolumeCoverState(attempt = pending.attempt, automaticRetries = pending.automaticRetries)
                                        }
                                    }
                                    Column(Modifier.padding(bottom = 6.dp)) {
                                        VolumeBookTile(
                                            mangaId = mangaId, chapter = chapter, cover = cover,
                                            imageHeaders = { volumeImageHeaders(chapter) },
                                            entry = chapterDownload(chapter, downloaded),
                                            progress = logicalChapterProgress(chapter, chapterPositions, progress),
                                            progressKnown = chapterPositions != null,
                                            current = chapterContainsProgress(chapter, progress),
                                            downloadMode = downloadMode,
                                            // One offline variant cannot disable choosing another source.
                                            enabled = true,
                                            onRead = { onRead(chapter) }, onDownload = { onDownload(chapter) },
                                            onActions = { selectedChapterId = chapter.logicalId.ifBlank { chapter.id } },
                                            onCoverError = {
                                                if (paneActive && volumeCovers[chapter.id] === cover)
                                                    volumeCovers[chapter.id] = cover.copy(error = "封面图片暂不可用")
                                            },
                                            onRetryCover = {
                                                volumeCovers[chapter.id] = VolumeCoverState(attempt = cover.attempt + 1)
                                            },
                                        )
                                        if (showSources) CatalogSourceEntry(
                                            chapter, Modifier.fillMaxWidth(),
                                            enabled = !sourceLoading,
                                            onOpen = { anchor ->
                                                if (downloadMode) onDownload(chapter) else {
                                                    sourceCloseRequested = false
                                                    sourceChapter = chapter; sourceAnchor = anchor
                                                }
                                            },
                                        )
                                    }
                                } else ChapterTile(
                                    chapter = chapter,
                                    entry = chapterDownload(chapter, downloaded),
                                    progress = logicalChapterProgress(chapter, chapterPositions, progress),
                                    current = chapterContainsProgress(chapter, progress),
                                    progressKnown = chapterPositions != null,
                                    downloadMode = downloadMode,
                                    height = tileHeight,
                                    showSources = showSources,
                                    sourceEnabled = !sourceLoading,
                                    onRead = { onRead(chapter) },
                                    onDownload = { onDownload(chapter) },
                                    onActions = { selectedChapterId = chapter.logicalId.ifBlank { chapter.id } },
                                    onSources = { anchor ->
                                        sourceCloseRequested = false
                                        sourceChapter = chapter; sourceAnchor = anchor
                                    },
                                )
                            }
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
            backdrop = backdrop,
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
    ChapterSourcePicker(
        chapter = sourceChapter, backdrop = backdrop,
        onDismiss = { sourceChapter = null; sourceAnchor = null; sourceCloseRequested = false },
        onReadVariant = onReadVariant, onSetPreferredSource = onSetPreferredSource,
        onClearChapterSource = onClearChapterSource, onSplitVariant = onSplitVariant,
        onRelinkVariant = onRelinkVariant, chapters = chapters, downloaded = downloaded,
        chapterPositions = chapterPositions, anchor = sourceAnchor,
        closeRequested = sourceCloseRequested,
        onCancelSelection = onCancelSourceSelection,
        loading = sourceLoading, error = sourceError, onPresenceChange = { sourcePresent = it },
    )
    (chapters.firstOrNull { it.logicalId.ifBlank { it.id } == selectedChapterId } ?: closingChapter)?.let { chapter ->
        val entry = chapterDownload(chapter, downloaded)
        MotionBottomSheet(
            onDismissRequest = {
                selectedChapterId = null
                closingChapter = null
                pendingSourceChapter?.let {
                    sourceCloseRequested = false
                    sourceChapter = it; sourceAnchor = null
                }
                pendingSourceChapter = null
            },
            backdrop = backdrop,
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
                    listOf(chapter.group.takeUnless(::isDefaultChapterGroup).orEmpty(), chapterStatus(entry,
                        chapterPositions?.get(chapter.id) ?: progress?.takeIf { it.chapterId == chapter.id }))
                        .filter(String::isNotBlank).joinToString(" · "),
                    color = DetailMuted, fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                TextButton(onClick = { dismissActions(); onRead(chapter) },
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (isVolumeChapter(chapter)) "阅读这一卷" else "阅读这一章", color = DetailForeground)
                }
                if (chapter.variants.isNotEmpty()) TextButton(onClick = {
                    pendingSourceChapter = chapter
                    dismissActions()
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("选择本章来源", color = DetailForeground)
                }
                TextButton(onClick = { dismissActions(); onDownload(chapter) },
                    modifier = Modifier.fillMaxWidth()) {
                    Text("选择来源下载", color = DetailForeground)
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
    val labels = choices.map { (name, _) -> name?.let(::chapterGroupLabel) ?: "全部" }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val textStyle = LocalTextStyle.current
    val minimumSlot = with(density) {
        choices.indices.maxOf { index ->
            measurer.measure(labels[index], textStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), maxLines = 1).size.width +
                measurer.measure(choices[index].second.toString(), textStyle.copy(fontSize = 10.sp), maxLines = 1).size.width
        }.toDp() + 20.dp
    }
    BoxWithConstraints(modifier) {
        val tabsWidth = maxOf(maxWidth, minimumSlot * choices.size)
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), contentAlignment = Alignment.CenterStart) {
            MotionTabs(
                modifier = Modifier.width(tabsWidth),
                selected = choices.indexOfFirst { it.first == selected }, labels = labels,
                onSelect = { onSelect(choices[it].first) },
                foreground = DetailForeground, muted = DetailSecondary, indicator = DetailForeground.copy(alpha = .045f),
                fullWidthIndicator = true, controlHeight = 40.dp, indicatorHeight = 28.dp, fontSize = 12.sp,
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

private val VolumeTitle = Regex(
    """^(?:第\s*)?[0-9０-９零〇一二三四五六七八九十百千两兩]+\s*[卷巻册冊](?=$|[\s（(:：·-])|^vol(?:ume)?\.?\s*[0-9]+(?=$|[\s（(:：·-])""",
    RegexOption.IGNORE_CASE,
)

private fun isVolumeChapter(chapter: Chapter): Boolean =
    isVolumeGroup(chapter.group) || VolumeTitle.containsMatchIn(chapter.title.trim())

private fun isVolumeGroup(name: String): Boolean = name.contains("单行本") || name.contains("單行本")

private fun isDefaultChapterGroup(name: String): Boolean =
    name.isBlank() || name == "默认" || name == "默認" || name.equals("default", ignoreCase = true)

private fun chapterGroupLabel(name: String): String = if (isDefaultChapterGroup(name)) "章节" else name

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
    showSources: Boolean,
    sourceEnabled: Boolean,
    onRead: () -> Unit,
    onDownload: () -> Unit,
    onActions: () -> Unit,
    onSources: (Rect?) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val complete = detailReadingFraction(progress) == 1f
    val readingColor = MaterialTheme.colorScheme.primary
    val hasDownloadMark = downloadMode || entry != null
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val markSize = 10.dp * fontScale
    Box(
        Modifier.fillMaxWidth().height(height.coerceAtLeast(48.dp))
            .combinedClickable(
                interactionSource = interaction, indication = null,
                role = Role.Button,
                onClickLabel = if (downloadMode) "选择来源下载章节" else "阅读章节",
                onLongClickLabel = "章节详情和操作",
                onClick = if (downloadMode) onDownload else onRead,
                onLongClick = onActions,
            )
            .semantics {
                contentDescription = chapter.title
                stateDescription = listOf(chapterReadingStatus(progress, progressKnown), chapterStatus(entry, null))
                    .filter(String::isNotBlank).joinToString("，")
            },
    ) {
        Column(Modifier.fillMaxSize().padding(vertical = 4.dp)
            .motionPress(interaction, pressedScale = .98f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (current) readingColor.copy(alpha = .055f) else Color.Transparent)
            .padding(horizontal = 6.dp)) {
            Box(Modifier.fillMaxWidth().height(20.dp * fontScale), contentAlignment = Alignment.CenterStart) {
                Text(
                    chapter.title, color = when {
                        current -> readingColor
                        complete -> DetailSecondary.copy(alpha = .65f)
                        else -> DetailForeground.copy(alpha = .90f)
                    },
                    fontSize = 13.sp, lineHeight = 20.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(Modifier.fillMaxWidth().height(20.dp * fontScale), verticalAlignment = Alignment.CenterVertically) {
                if (showSources) CatalogSourceEntry(chapter,
                    enabled = sourceEnabled,
                    onOpen = { anchor -> if (downloadMode) onDownload() else onSources(anchor) })
                Spacer(Modifier.weight(1f))
                Row(Modifier.width(markSize * 2f + 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(markSize), contentAlignment = Alignment.Center) {
                        if (progress != null) ChapterReadMark(progress, progressKnown, size = markSize)
                    }
                    Box(Modifier.size(markSize), contentAlignment = Alignment.Center) {
                        if (hasDownloadMark) ChapterDownloadMark(entry, downloadMode, size = markSize)
                    }
                }
            }
        }
    }
}

/** One quiet source entry replaces repeated competing marks inside every chapter. */
@Composable
private fun CatalogSourceEntry(chapter: Chapter, modifier: Modifier = Modifier,
    enabled: Boolean = true, onOpen: (Rect?) -> Unit) {
    val sources = remember(chapter.variants) { chapter.variants.distinctBy { it.sourceId } }
    if (sources.isEmpty()) return
    var anchor by remember(chapter.logicalId, chapter.id) { mutableStateOf<Rect?>(null) }
    val interaction = remember { MutableInteractionSource() }
    Row(modifier.heightIn(min = 20.dp).widthIn(min = 28.dp).onGloballyPositioned { coordinates ->
        val origin = coordinates.localToScreen(Offset.Zero)
        if (origin.isSpecified) anchor = Rect(origin, androidx.compose.ui.geometry.Size(
            coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
    }.clickable(enabled = enabled, interactionSource = interaction, indication = null,
        role = Role.Button, onClickLabel = "选择章节来源", onClick = { onOpen(anchor) })
        .motionPress(interaction, enabled = enabled, pressedScale = .97f)
        .semantics { contentDescription = chapter.title + "，" +
            sources.joinToString("、") { it.sourceName } + "，选择来源" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("${sources.size} 源", color = if (enabled) DetailMuted else DetailMuted.copy(alpha = .45f),
            fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1)
        Icon(Icons.Outlined.ExpandMore, null, tint = DetailMuted, modifier = Modifier.size(12.dp))
    }
}

private fun chapterContainsProgress(chapter: Chapter, progress: ReadingProgress?): Boolean =
    progress != null && (progress.chapterId == chapter.id || chapter.variants.any { it.storageId == progress.chapterId })

private fun logicalChapterProgress(
    chapter: Chapter,
    positions: Map<String, ReadingProgress>?,
    latest: ReadingProgress?,
): ReadingProgress? {
    val records = (chapter.variants.mapNotNull { positions?.get(it.storageId) } +
        listOfNotNull(positions?.get(chapter.id), latest?.takeIf { chapterContainsProgress(chapter, it) }))
        .distinctBy { it.chapterId }
    return records.firstOrNull { detailReadingFraction(it) == 1f }
        ?: records.firstOrNull { it.chapterId == chapter.id } ?: records.maxByOrNull { it.updatedAt }
}

private fun chapterDownload(chapter: Chapter, entries: Map<String, DownloadEntry>): DownloadEntry? =
    entries[chapter.id] ?: chapter.variants.mapNotNull { entries[it.storageId] }
        .let { variants -> variants.firstOrNull { it.status == DownloadStatus.COMPLETED } ?: variants.firstOrNull() }

@Composable
internal fun ChapterDownloadMark(entry: DownloadEntry?, showDownload: Boolean, size: Dp = 14.dp) {
    val fraction = entry?.takeIf { it.totalPages > 0 }?.let {
        (it.completedPages.toFloat() / it.totalPages).coerceIn(0f, 1f)
    }
    val downloadProgress by animateFloatAsState(fraction ?: 0f,
        animationSpec = if (LocalMotionEnabled.current) tween(MangaMotion.Content) else snap(),
        label = "chapter-download-mark")
    MotionSwap(entry?.status to showDownload, Modifier.size(size)) { (status, showDownload) ->
        when (status) {
            DownloadStatus.RUNNING -> Box(Modifier.size(size)) {
                Icon(Icons.Outlined.Download, "正在下载", tint = DetailMuted.copy(alpha = .3f), modifier = Modifier.size(size))
                Icon(Icons.Outlined.Download, null, tint = DetailForeground,
                    modifier = Modifier.size(size).drawWithContent {
                        clipRect(top = this.size.height * (1f - downloadProgress)) { this@drawWithContent.drawContent() }
                    })
            }
            DownloadStatus.QUEUED -> Icon(Icons.Outlined.Schedule, "等待下载",
                tint = DetailMuted, modifier = Modifier.size(size))
            DownloadStatus.COMPLETED -> Icon(Icons.Outlined.DownloadDone, "已下载",
                tint = DetailForeground, modifier = Modifier.size(size))
            DownloadStatus.PAUSED, DownloadStatus.WAITING -> Icon(Icons.Outlined.Pause,
                "下载暂停", tint = DetailMuted, modifier = Modifier.size(size))
            DownloadStatus.FAILED -> Icon(Icons.Outlined.Refresh, "下载失败",
                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(size))
            else -> if (showDownload) Icon(Icons.Outlined.Download, "下载",
                tint = DetailMuted, modifier = Modifier.size(size))
        }
    }
}

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
