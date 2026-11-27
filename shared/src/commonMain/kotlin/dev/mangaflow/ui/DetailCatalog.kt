package dev.mangaflow.ui

import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Tune
import dev.mangaflow.ui.platform.*

import dev.mangaflow.platform.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.mangaflow.ui.motion.MotionTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.sourceDisplayName
import dev.mangaflow.data.ChapterVariant
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.Page
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.ui.motion.BackHandler
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionSwap
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.rememberDelayedBusy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private data class ChapterLanding(val index: Int, val chapterId: String, val revision: Int)

/**
 * Group panes share one fixed toolbar. Changing download mode never inserts a row
 * or shifts chapters, and each pane owns its grid while a group change animates.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    onDownload: (Chapter, Rect?) -> Unit,
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
    sourceLoading: Boolean = false,
    sourceError: String? = null,
    onCancelSourceSelection: () -> Unit = {},
    repository: MangaRepository? = null,
    locateRevision: Int = 0,
    onLocate: () -> Unit = {},
    onLocateSources: (() -> Unit)? = null,
    desktopFindRequest: Int = 0,
    onBatchDownload: ((List<Chapter>) -> Unit)? = null,
) {
    val desktop = platformKind == PlatformKind.DESKTOP
    val groups = remember(chapters) { chapters.groupingBy(Chapter::group).eachCount() }
    val emptyCatalog = chapters.isEmpty()
    val catalogFailure = error?.let(::detailCatalogFailureLabel)
    var chosenGroup by rememberSaveable(mangaId) { mutableStateOf<String?>(null) }
    var groupVisit by rememberSaveable(mangaId) { mutableIntStateOf(0) }
    var appliedLocate by rememberSaveable(mangaId) { mutableIntStateOf(0) }
    var chapterQuery by rememberSaveable(mangaId) { mutableStateOf("") }
    val search = chapterQuery.trim().takeIf { desktop }.orEmpty()
    val searching = search.isNotEmpty()
    val searchResults = rememberMatchingChapters(chapters, search)
    val locatePending = locateRevision != appliedLocate && progress != null
    LaunchedEffect(locateRevision) { if (locatePending) chapterQuery = "" }
    val locateChapter = remember(chapters, progress?.chapterId, locatePending) {
        if (locatePending) chapters.firstOrNull { chapterContainsProgress(it, progress) } else null
    }
    // A temporarily empty directory must not clear a restored group or its scroll position.
    val group = locateChapter?.group ?: chosenGroup?.takeIf { groups.isEmpty() || it in groups }
    var shownError by remember { mutableStateOf<String?>(null) }
    var selectedChapterId by rememberSaveable(mangaId) { mutableStateOf<String?>(null) }
    var selectedChapterAnchor by remember(mangaId) { mutableStateOf<Rect?>(null) }
    var closingChapter by remember(mangaId) { mutableStateOf<Chapter?>(null) }
    var pendingSourceChapter by remember(mangaId) { mutableStateOf<Chapter?>(null) }
    var sourceChapter by remember(mangaId) { mutableStateOf<Chapter?>(null) }
    var sourceAnchor by remember(mangaId) { mutableStateOf<Rect?>(null) }
    var sourcePresent by remember(mangaId) { mutableStateOf(false) }
    var sourceCloseRequested by remember(mangaId) { mutableStateOf(false) }
    LaunchedEffect(active, chapters, sourceLoading) {
        val selected = sourceChapter
        val current = selected?.let { original ->
            chapters.firstOrNull { it.logicalId.ifBlank { it.id } == original.logicalId.ifBlank { original.id } }
        }
        if (selected != null && (!active || (!sourceLoading && current != null && current.id != selected.id))) {
            sourceCloseRequested = true
        }
    }
    // Each book observes only its own cover state; completing one image does not invalidate its siblings.
    val volumeCovers = remember(mangaId) { linkedMapOf<String, MutableState<VolumeCoverState>>() }
    val coverPermits = remember(mangaId) { Semaphore(2) }

    val motion = LocalMotionEnabled.current
    val showBusy = rememberDelayedBusy(loading)
    Column(Modifier.fillMaxSize()) {
        if (desktop) BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, bottom = 5.dp)) {
            val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
            val roomy = maxWidth >= 390.dp * fontScale
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val single = searchResults.singleOrNull()?.takeUnless { downloadMode }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    ChapterSearchField(chapterQuery, { chapterQuery = it }, desktopFindRequest,
                        active && !sourcePresent, Modifier.widthIn(max = 440.dp * fontScale).fillMaxWidth(),
                        onOpenMatch = single?.let { match -> { onRead(match) } }, showFindShortcut = roomy)
                }
                if (searching) Text("${searchResults.size} 章", color = DetailMuted,
                    style = MaterialTheme.typography.labelSmall)
                if (progress != null) {
                    if (roomy) TextButton(onClick = onLocate, enabled = active && !sourcePresent,
                        contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Outlined.MyLocation, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("上次阅读", style = MaterialTheme.typography.labelMedium)
                    } else DetailAction(Icons.Outlined.MyLocation, "定位上次阅读",
                        enabled = active && !sourcePresent, onClick = onLocate)
                }
            }
        }
        if (desktop && downloadMode && onBatchDownload != null) {
            val scope = remember(chapters, group, search, searchResults) {
                if (searching) searchResults else if (group == null) chapters else chapters.filter { it.group == group }
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (searching) "搜索结果 · ${scope.size} 章" else "${group?.let(::chapterGroupLabel) ?: "全部章节"} · ${scope.size} 章",
                    Modifier.weight(1f), color = DetailSecondary, style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { onBatchDownload(scope) }, enabled = active && !sourcePresent && scope.isNotEmpty()) {
                    Text("多选下载", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (groups.size > 1 && !searching || !desktop && progress != null) Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (groups.size > 1) ChapterGroups(
                groups = groups, total = chapters.size, selected = group, active = active,
                onSelect = {
                    if (group != it) groupVisit++
                    appliedLocate = locateRevision
                    chosenGroup = it
                }, modifier = Modifier.weight(1f),
            ) else Spacer(Modifier.weight(1f))
            if (!desktop && progress != null) DetailAction(Icons.Outlined.MyLocation, "定位上次阅读",
                enabled = active && !sourcePresent, onClick = onLocate)
        }
        if (locatePending && locateChapter == null && !loading && !sourceLoading) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("当前目录没有上次阅读的章节", Modifier.weight(1f), color = DetailMuted, fontSize = 12.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!emptyCatalog) {
                    if (onLocateSources != null) DetailAction(Icons.Outlined.Public, "查看可用来源",
                        enabled = active, onClick = onLocateSources)
                    DetailAction(Icons.Outlined.Refresh, "重查上次阅读章节", enabled = active, onClick = onRetry)
                }
                DetailAction(Icons.Outlined.Close, "关闭定位提示", enabled = active,
                    onClick = { appliedLocate = locateRevision })
            }
        }
        if (catalogFailure != null && !emptyCatalog) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            DetailAction(Icons.Outlined.Info, "目录加载提示", enabled = active && !sourcePresent,
                onClick = { shownError = catalogFailure })
            DetailAction(Icons.Outlined.Refresh, "重新加载目录", enabled = active && !sourcePresent && !loading,
                onClick = onRetry)
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
            val catalogPosition = rememberLazyGridState()
            val searchPosition = if (desktop) rememberLazyGridState() else catalogPosition
            val grid = if (searching) searchPosition else catalogPosition
            var landing by remember(grid) { mutableStateOf<ChapterLanding?>(null) }
            val locateLight = remember(grid) { Animatable(0f) }
            val locateInk = MaterialTheme.colorScheme.primary
            LaunchedEffect(landing, paneActive, motion) {
                val destination = landing ?: return@LaunchedEffect
                if (!paneActive) { landing = null; return@LaunchedEffect }
                try {
                    locateLight.snapTo(0f)
                    val layout = grid.layoutInfo
                    val visible = layout.visibleItemsInfo
                    val target = visible.firstOrNull { it.index == destination.index }
                    val inView = target != null && target.offset.y >= layout.viewportStartOffset &&
                        target.offset.y + target.size.height <= layout.viewportEndOffset
                    if (!inView) {
                        val reach = visible.size
                        val near = visible.isNotEmpty() && destination.index in
                            (visible.first().index - reach)..(visible.last().index + reach)
                        if (motion && near) grid.animateScrollToItem(destination.index)
                        else grid.scrollToItem(destination.index)
                    }
                    locateLight.snapTo(1f)
                    if (motion) locateLight.animateTo(0f, tween(640, easing = MangaMotion.ExitEasing))
                    else delay(420)
                } finally {
                    if (landing == destination) landing = null
                }
            }
            var appliedSearch by rememberSaveable(mangaId) { mutableStateOf("") }
            LaunchedEffect(search, paneActive) {
                if (paneActive && search != appliedSearch) {
                    appliedSearch = search
                    if (searching) searchPosition.requestScrollToItem(0)
                }
            }
            SideEffect {
                if (paneActive) onScrollTarget(grid)
            }
            val visibleChapters = remember(chapters, targetGroup, searching, searchResults) {
                if (searching) searchResults else if (targetGroup == null) chapters else chapters.filter { it.group == targetGroup }
            }
            // Comments/reader restoration keeps this visit; even a quick A→B→A is a new visit.
            var appliedVisit by rememberSaveable(mangaId) { mutableIntStateOf(groupVisit) }
            LaunchedEffect(paneActive) { if (!paneActive) grid.stopScroll() }
            if (visibleChapters.isEmpty()) {
                Box(Modifier.fillMaxSize()
                    .scrollable(rememberScrollableState { 0f }, Orientation.Vertical, enabled = paneActive),
                    contentAlignment = Alignment.Center) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(when {
                            loading && emptyCatalog -> "正在读取章节"
                            catalogFailure != null && emptyCatalog -> catalogFailure
                            searching -> "没有符合条件的章节"
                            else -> "暂无章节"
                        }, color = DetailMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
                        if (emptyCatalog && !loading) {
                            val enabled = paneActive && !sourceLoading
                            val colors = ButtonDefaults.textButtonColors(contentColor = DetailForeground,
                                disabledContentColor = DetailMuted)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                                if (onLocateSources != null) TextButton(onClick = onLocateSources,
                                    modifier = Modifier.heightIn(min = 44.dp), enabled = enabled, colors = colors) {
                                    Text("查看来源")
                                }
                                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 44.dp),
                                    enabled = enabled, colors = colors) {
                                    Text("重试目录")
                                }
                            }
                        }
                    }
                }
            } else {
                val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                val showGroup = (searching || targetGroup == null) && groups.size > 1
                val tileHeight = ((if (desktop) 60f else 40f) * fontScale + 8f).dp
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
                val locateIndex = remember(runs, targetGroup, showGroup, locateChapter?.id) {
                    if (locateChapter == null || targetGroup != locateChapter.group) null else {
                        var itemIndex = 0
                        var found: Int? = null
                        for ((runIndex, run) in runs.withIndex()) {
                            if (runIndex > 0 || showGroup && !isDefaultChapterGroup(run.first().group)) itemIndex++
                            val index = run.indexOfFirst { it.id == locateChapter.id }
                            if (index >= 0) { found = itemIndex + index; break }
                            itemIndex += run.size
                        }
                        found
                    }
                }
                LaunchedEffect(paneActive, groupVisit, locateRevision, locateIndex, searching) {
                    if (!paneActive || searching) return@LaunchedEffect
                    if (locateIndex != null && locateChapter != null && appliedLocate != locateRevision) {
                        landing = ChapterLanding(locateIndex, locateChapter.id, locateRevision)
                        chosenGroup = targetGroup
                        appliedVisit = groupVisit
                        appliedLocate = locateRevision
                    } else if (appliedVisit != groupVisit) {
                        grid.scrollToItem(0)
                        appliedVisit = groupVisit
                    }
                }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    // Phones retain their compact four-column layout. Desktop uses the
                    // remaining pane width, including the actual scrollbar gutter.
                    val gridWidth = (maxWidth - (if (desktop) 12.dp else 0.dp)).coerceAtLeast(1.dp)
                    val chapterColumns = if (desktop)
                        MangaLayout.columns(gridWidth, 3, 235.dp * fontScale, 16.dp, 16.dp)
                    else MangaLayout.columns(gridWidth, 4, 64.dp * fontScale, 6.dp, 16.dp)
                    val volumeColumns = if (desktop)
                        MangaLayout.columns(gridWidth, 6, 132.dp * fontScale, 16.dp, 16.dp)
                    else chapterColumns
                    // Separate book/text densities share one scroll state and full-width group breaks.
                    val gridUnits = detailCatalogGridUnits(chapterColumns, volumeColumns)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(gridUnits),
                        state = grid, userScrollEnabled = paneActive,
                        modifier = Modifier.fillMaxSize().padding(end = if (desktop) 12.dp else 0.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 5.dp, bottom = bottomPadding),
                        horizontalArrangement = Arrangement.spacedBy(if (desktop) 16.dp else 6.dp),
                        // Reading and source marks share the compact secondary line.
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        runs.forEachIndexed { runIndex, run ->
                            if (showGroup && (runIndex > 0 || !isDefaultChapterGroup(run.first().group))) item(key = "group:$runIndex:${run.first().group}",
                                span = { GridItemSpan(maxLineSpan) }, contentType = "group") {
                                val heading = chapterGroupLabel(run.first().group)
                                if (desktop) Row(Modifier.fillMaxWidth().padding(top = if (runIndex == 0) 4.dp else 14.dp,
                                    bottom = 7.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(heading, Modifier.weight(1f, fill = false).alignByBaseline(), color = DetailForeground,
                                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(run.size.toString() + if (isVolumeChapter(run.first())) " 册" else " 章",
                                        Modifier.alignByBaseline(), style = mangaMetricValueStyle(12.sp, 16.sp), color = DetailMuted)
                                } else Text(heading, color = DetailSecondary,
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = if (runIndex == 0) 3.dp else 14.dp, bottom = 5.dp))
                            }
                            if (!showGroup && runIndex > 0) item(key = "run-break:$runIndex",
                                span = { GridItemSpan(maxLineSpan) }, contentType = "run-break") { Spacer(Modifier.height(4.dp)) }
                            items(run, key = { it.logicalId.ifBlank { it.id } },
                                span = { chapter -> GridItemSpan(gridUnits /
                                    (if (isVolumeChapter(chapter)) volumeColumns else chapterColumns)) },
                                contentType = { if (isVolumeChapter(it)) "volume" else "chapter" }) { chapter ->
                                val downloadAnchor = remember(chapter.logicalId, chapter.id) { PopupAnchor() }
                                val downloadAnchorModifier = downloadAnchor.modifier.then(if (landing?.chapterId != chapter.id) Modifier else Modifier.drawWithContent {
                                    drawContent()
                                    val inset = 4.dp.toPx()
                                    drawRoundRect(locateInk.copy(alpha = .17f * locateLight.value),
                                        topLeft = Offset(0f, inset),
                                        size = Size(size.width, (size.height - inset * 2f).coerceAtLeast(0f)),
                                        cornerRadius = CornerRadius(10.dp.toPx()))
                                })
                                if (isVolumeChapter(chapter)) {
                                    val coverState = remember(mangaId, chapter.id) {
                                        val retained = volumeCovers.remove(chapter.id) ?: mutableStateOf(VolumeCoverState())
                                        volumeCovers[chapter.id] = retained
                                        while (volumeCovers.size > 128) volumeCovers.remove(volumeCovers.keys.first())
                                        retained
                                    }
                                    val cover = coverState.value
                                    LaunchedEffect(chapter.id, paneActive, cover.attempt) {
                                        if (!paneActive || cover.page != null || cover.error != null) return@LaunchedEffect
                                        val pending = cover.copy(loading = true)
                                        coverState.value = pending
                                        try {
                                            val page = coverPermits.withPermit {
                                                loadVolumeCover(chapter, cover.attempt > 0)
                                            }
                                            currentCoroutineContext().ensureActive()
                                            if (coverState.value === pending)
                                                coverState.value = pending.copy(page = page, loading = false)
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (failure: Exception) {
                                            if (coverState.value === pending)
                                                coverState.value = pending.copy(loading = false,
                                                    error = failure.message ?: "这一卷封面暂不可用")
                                        } finally {
                                            if (coverState.value === pending)
                                                coverState.value = VolumeCoverState(attempt = pending.attempt)
                                        }
                                    }
                                    Column(downloadAnchorModifier.padding(bottom = 6.dp)) {
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
                                            onRead = { onRead(chapter) }, onDownload = { onDownload(chapter, downloadAnchor.bounds()) },
                                            onActions = { selectedChapterId = chapter.logicalId.ifBlank { chapter.id }; selectedChapterAnchor = downloadAnchor.bounds() },
                                            sourcesEnabled = !sourceLoading && chapter.variants.isNotEmpty(),
                                            onSources = {
                                                sourceCloseRequested = false
                                                sourceChapter = chapter; sourceAnchor = downloadAnchor.bounds()
                                            },
                                            onCoverError = {
                                                if (paneActive && coverState.value === cover)
                                                    coverState.value = cover.copy(error = "封面图片暂不可用")
                                            },
                                            onRetryCover = {
                                                coverState.value = VolumeCoverState(attempt = cover.attempt + 1)
                                            },
                                            sourceMarks = {
                                                CatalogSourceEntry(
                                                    chapter, Modifier.fillMaxWidth(),
                                                    enabled = !sourceLoading,
                                                    onOpen = { anchor ->
                                                        if (downloadMode) onDownload(chapter, anchor) else {
                                                            sourceCloseRequested = false
                                                            sourceChapter = chapter; sourceAnchor = anchor
                                                        }
                                                    },
                                                )
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
                                    sourceEnabled = !sourceLoading,
                                    modifier = downloadAnchorModifier,
                                    onRead = { onRead(chapter) },
                                    onDownload = { onDownload(chapter, downloadAnchor.bounds()) },
                                    onActions = { selectedChapterId = chapter.logicalId.ifBlank { chapter.id }; selectedChapterAnchor = downloadAnchor.bounds() },
                                    onSources = { anchor ->
                                        sourceCloseRequested = false
                                        sourceChapter = chapter; sourceAnchor = anchor
                                    },
                                )
                            }
                        }
                    }
                    if (desktop) DesktopVerticalScrollbar(grid,
                        Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
    }

    // Keep the opened message until dismissal finishes, even when retry clears the source error.
    shownError?.let { message ->
        MotionBottomSheet(
            onDismissRequest = { shownError = null },
            backdrop = backdrop, desktopCloseButton = true,
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
        repository = repository, workMangaId = mangaId,
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
            backdrop = backdrop, desktopCloseButton = true,
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
                TextButton(onClick = { dismissActions(); onDownload(chapter, selectedChapterAnchor) },
                    modifier = Modifier.fillMaxWidth()) {
                    Text("选择来源下载", color = DetailForeground)
                }
            }
        }
    }
}

/** Directory failures can carry upstream text, so display only fixed app wording. */
private fun detailCatalogFailureLabel(error: String): String = when (error) {
    "此来源已移除，请重新导入该源。" -> "此来源已移除，可在来源设置中重新导入"
    "这个正文来源尚未安装或当前版本不兼容。" -> "此来源尚未安装或当前版本不兼容"
    "Komiic 要求登录后访问；当前公开来源没有附加账号。" -> "此来源要求登录后查看目录"
    "再漫画隐藏了本作品的章节目录，匿名访问不能确认完整目录。",
    "再漫画限制了本作品的匿名访问，请查看原站权限说明。" -> "此来源未向匿名访问开放完整目录"
    "包子要求站点验证或拒绝当前访问；当前来源没有自动验证能力。" -> "此来源要求站点验证或限制当前访问"
    "包子要求先完成站点验证，未把验证页当成空目录。" -> "此来源要求完成站点验证"
    "章节目录 超时，请检查网络后重试。" -> "等待章节目录超时"
    "章节目录 连接或数据读取失败，请检查网络后重试。" -> "未能连接来源或完整读取目录"
    "章节目录 来源暂时繁忙，请稍后重试。" -> "来源暂时繁忙"
    "来源章节目录为空或不完整，请稍后刷新。",
    "如漫画没有返回完整章节目录。" -> "来源未返回完整目录"
    "MangaDex 暂无可直接读取的中文或英语章节；外链、下架章节不计入可读目录。" -> "MangaDex 暂无可直接阅读的中文或英语章节"
    "如漫画未返回公开章节目录。", "如漫画暂未返回公开可读章节。", "MangaBZ 暂无公开可读目录。" ->
        "此来源暂未返回公开章节"
    else -> "未能读取此来源的章节目录"
}

/** Least-common slots let two semantic grids retain independent widths without nested scrolling. */
private fun detailCatalogGridUnits(chapters: Int, volumes: Int): Int {
    var a = chapters
    var b = volumes
    while (b != 0) {
        val remainder = a % b
        a = b
        b = remainder
    }
    return chapters / a * volumes
}

/** The selected group keeps its anchor while other groups reveal inside this control row. */
@Composable
private fun ChapterGroups(
    groups: Map<String, Int>,
    total: Int,
    selected: String?,
    active: Boolean,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val allChoices = remember(groups, total) {
        buildList<Pair<String?, Int>> {
            add(null to total)
            groups.forEach { (name, count) -> add(name to count) }
        }
    }
    var expanded by remember { mutableStateOf(false) }
    var closeAfterChoice by remember { mutableStateOf(false) }
    val presence = remember { MutableTransitionState(false) }
    presence.targetState = expanded && active
    val present = presence.currentState || presence.targetState || !presence.isIdle
    // A selected row must survive its own exit; excluding the new selection
    // during the fold otherwise reorders the strip underneath the user's tap.
    var choices by remember { mutableStateOf(allChoices.filter { it.first != selected }) }
    val motion = LocalMotionEnabled.current
    val scroll = rememberScrollState()
    val interaction = remember { MutableInteractionSource() }
    val selectedLabel = selected?.let(::chapterGroupLabel) ?: "全部"
    val turn by animateFloatAsState(if (expanded) 180f else 0f,
        if (motion) tween(MangaMotion.Content) else snap(), label = "chapter-group-disclosure")
    BackHandler(active && expanded) { closeAfterChoice = false; expanded = false }
    LaunchedEffect(active) { if (!active) { closeAfterChoice = false; expanded = false } }
    LaunchedEffect(present, allChoices, selected) {
        if (!present) {
            choices = allChoices.filter { it.first != selected }
            scroll.scrollTo(0)
        }
    }
    LaunchedEffect(closeAfterChoice, selected, motion) {
        if (closeAfterChoice) {
            if (motion) delay(MangaMotion.Quick.toLong())
            expanded = false
            closeAfterChoice = false
        }
    }
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp, max = 176.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = active, interactionSource = interaction, indication = null, role = Role.Button,
                onClickLabel = if (expanded) "收起章节分组" else "展开章节分组",
                onClick = {
                    closeAfterChoice = false
                    if (!expanded && !present) choices = allChoices.filter { it.first != selected }
                    expanded = !expanded
                })
            .motionPress(interaction, enabled = active, pressedScale = .98f)
            .semantics {
                contentDescription = "章节分组，当前$selectedLabel"
                stateDescription = if (expanded) "已展开" else "已收起"
            }.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MotionTabContent(selected = selectedLabel,
                contentKey = { it }, order = { value ->
                    allChoices.indexOfFirst { (name, _) -> (name?.let(::chapterGroupLabel) ?: "全部") == value }
                }, fillContent = false, modifier = Modifier.weight(1f, fill = false)) { label ->
                Text(label, color = DetailSecondary, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(14.dp).graphicsLayer { rotationZ = turn },
                tint = DetailMuted)
        }
        AnimatedVisibility(presence,
            modifier = Modifier.weight(1f),
            enter = fadeIn(tween(if (motion) MangaMotion.Quick else 0)) +
                expandHorizontally(tween(if (motion) MangaMotion.Content else 0,
                    easing = MangaMotion.EnterEasing), expandFrom = Alignment.Start),
            exit = fadeOut(tween(if (motion) MangaMotion.Quick else 0)) +
                shrinkHorizontally(tween(if (motion) MangaMotion.Content else 0,
                    easing = MangaMotion.ExitEasing), shrinkTowards = Alignment.Start)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                choices.forEach { (name, count) ->
                    androidx.compose.runtime.key(name) {
                        val label = name?.let(::chapterGroupLabel) ?: "全部"
                        val rowInteraction = remember { MutableInteractionSource() }
                        val chosen = selected == name
                        val choiceBackground by animateColorAsState(
                            if (chosen) DetailForeground.copy(alpha = .075f) else Color.Transparent,
                            if (motion) tween(MangaMotion.Quick) else snap(), label = "chapter-choice-background")
                        val choiceColor by animateColorAsState(if (chosen) DetailForeground else DetailSecondary,
                            if (motion) tween(MangaMotion.Quick) else snap(), label = "chapter-choice-label")
                        Row(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
                            .background(choiceBackground)
                            .selectable(selected = chosen, enabled = active && expanded, role = Role.Tab,
                                interactionSource = rowInteraction, indication = null, onClick = {
                                    onSelect(name)
                                    if (motion && !chosen) closeAfterChoice = true
                                    else { closeAfterChoice = false; expanded = false }
                                })
                            .motionPress(rowInteraction, enabled = active && expanded, pressedScale = .985f)
                            .semantics { contentDescription = "$label，$count 章" }
                            .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(label, color = choiceColor, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(count.toString(), color = DetailMuted, fontSize = 11.sp)
                        }
                    }
                }
            }
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

internal fun chapterGroupLabel(name: String): String = if (isDefaultChapterGroup(name)) "章节" else name

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ChapterTile(
    chapter: Chapter,
    entry: DownloadEntry?,
    progress: ReadingProgress?,
    current: Boolean,
    progressKnown: Boolean,
    downloadMode: Boolean,
    height: Dp,
    sourceEnabled: Boolean,
    modifier: Modifier = Modifier,
    onRead: () -> Unit,
    onDownload: () -> Unit,
    onActions: () -> Unit,
    onSources: (Rect?) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val desktop = platformKind == PlatformKind.DESKTOP
    val desktopActions = if (desktop) listOf(
        DesktopMenuAction("阅读章节", icon = Icons.Outlined.AutoStories, onClick = onRead),
        DesktopMenuAction("选择来源下载", icon = Icons.Outlined.Download, onClick = onDownload),
        DesktopMenuAction("选择章节来源", icon = Icons.Outlined.Tune, enabled = sourceEnabled && chapter.variants.isNotEmpty()) { onSources(null) },
        DesktopMenuAction("章节信息", icon = Icons.Outlined.Info, onClick = onActions),
    ) else emptyList()
    val complete = detailReadingFraction(progress) == 1f
    val readingColor = MaterialTheme.colorScheme.primary
    val hasDownloadMark = downloadMode || entry != null
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val markSize = 10.dp * fontScale
    if (desktop) {
        val hovered by interaction.collectIsHoveredAsState()
        val focused by interaction.collectIsFocusedAsState()
        val colors = MaterialTheme.colorScheme
        val fill = animateColorAsState(when {
            current -> readingColor.copy(alpha = if (hovered || focused) .14f else .085f)
            hovered || focused -> colors.onSurface.copy(alpha = .065f)
            else -> Color.Transparent
        }, if (LocalMotionEnabled.current) tween(MangaMotion.Quick) else snap(), label = "chapter-row")
        val reading = when {
            complete -> "已读完"
            progress != null -> detailReadingPercent(progress)?.let { "读至 $it%" } ?: "阅读中"
            current -> "上次阅读"
            else -> null
        }
        DesktopContextMenuArea(desktopActions) {
            Row(modifier.fillMaxWidth().padding(vertical = 2.dp).heightIn(min = height)
                .clip(RoundedCornerShape(9.dp)).drawBehind {
                    drawRect(fill.value)
                    if (current) {
                        val w = 3.dp.toPx()
                        val h = minOf(25.dp.toPx(), size.height)
                        val x = if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - w
                        drawRoundRect(readingColor, Offset(x, (size.height - h) / 2f), Size(w, h), CornerRadius(w / 2f))
                    }
                }
                .combinedClickable(interactionSource = interaction, indication = null, role = Role.Button,
                    onClickLabel = if (downloadMode) "选择来源下载章节" else "阅读章节",
                    onClick = if (downloadMode) onDownload else onRead)
                .motionPress(interaction, pressedScale = .99f).semantics {
                    contentDescription = chapter.title
                    stateDescription = listOf(if (current) "当前阅读章节" else "",
                        chapterReadingStatus(progress, progressKnown), chapterStatus(entry, null))
                        .filter(String::isNotBlank).joinToString("，")
                }.padding(start = 12.dp, end = 3.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(mangaNumberText(chapter.title), color = when {
                        current -> readingColor
                        complete -> DetailSecondary
                        else -> DetailForeground
                    }, style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (current) FontWeight.SemiBold
                        else FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        if (reading != null) Row(Modifier.align(Alignment.CenterVertically),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            ChapterReadMark(progress, progressKnown, size = 11.dp)
                            Text(reading, color = DetailSecondary, style = mangaMetricValueStyle(11.sp, 15.sp))
                        }
                        CatalogSourceEntry(chapter, Modifier.align(Alignment.CenterVertically), enabled = sourceEnabled,
                            onOpen = { anchor -> if (downloadMode) onDownload() else onSources(anchor) })
                        if (hasDownloadMark) Box(Modifier.align(Alignment.CenterVertically)) {
                            ChapterDownloadMark(entry, downloadMode, size = 12.dp)
                        }
                    }
                }
                Box(Modifier.size(32.dp)) { DesktopMoreButton(desktopActions, label = chapter.title + "的操作") }
            }
        }
        return
    }
    Box(modifier.fillMaxWidth().height(height.coerceAtLeast(48.dp))) {
        DesktopContextMenuArea(desktopActions) {
            Box(
                Modifier.fillMaxSize()
                    .combinedClickable(
                        interactionSource = interaction, indication = null,
                        role = Role.Button,
                        onClickLabel = if (downloadMode) "选择来源下载章节" else "阅读章节",
                        onLongClickLabel = "章节详情和操作",
                        onClick = if (downloadMode) onDownload else onRead,
                        onLongClick = if (desktop) null else onActions,
                    )
                    .semantics {
                        contentDescription = chapter.title
                        stateDescription = listOf(if (current) "当前阅读章节" else "",
                            chapterReadingStatus(progress, progressKnown), chapterStatus(entry, null))
                            .filter(String::isNotBlank).joinToString("，")
                    },
            ) {
                Column(Modifier.fillMaxSize().padding(vertical = 4.dp)
                    .motionPress(interaction, pressedScale = .98f)
                    .clip(RoundedCornerShape(10.dp))
                    .then(if (current) Modifier.background(readingColor.copy(alpha = .09f)).drawBehind {
                        val width = 2.dp.toPx()
                        val height = minOf(size.height, 14.dp.toPx())
                        val x = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
                        drawRoundRect(readingColor, Offset(x, (size.height - height) / 2f),
                            Size(width, height), CornerRadius(width / 2f))
                    } else Modifier)
                    .padding(horizontal = 7.dp)) {
                    Box(Modifier.fillMaxWidth().height((if (desktop) 24.dp else 20.dp) * fontScale), contentAlignment = Alignment.CenterStart) {
                        Text(
                            mangaNumberText(chapter.title), color = when {
                                current -> readingColor
                                complete -> DetailSecondary
                                else -> DetailForeground
                            },
                            fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(Modifier.fillMaxWidth().height((if (desktop) 32.dp else 20.dp) * fontScale), verticalAlignment = Alignment.CenterVertically) {
                        CatalogSourceEntry(chapter,
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
                        if (desktop) Box(Modifier.size(32.dp)) {
                            DesktopMoreButton(desktopActions, label = chapter.title + "的操作")
                        }
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
    if (chapter.variants.isEmpty()) {
        Box(modifier.height(20.dp), contentAlignment = Alignment.CenterStart) {
            ChapterSourceLogo(chapter.sourceId, sourceDisplayName(chapter.sourceId),
                Modifier.size(13.dp), muted = true)
        }
        return
    }
    val anchor = remember(chapter.logicalId, chapter.id) { PopupAnchor() }
    if (platformKind == PlatformKind.DESKTOP) {
        val interaction = remember(chapter.id) { MutableInteractionSource() }
        val source = remember(chapter.id, chapter.sourceId, chapter.variants) {
            chapter.variants.firstOrNull { it.storageId == chapter.id }
                ?: chapter.variants.firstOrNull { it.sourceId == chapter.sourceId } ?: chapter.variants.first()
        }
        val label = remember(chapter.variants) {
            val sources = chapter.variants.distinctBy { it.sourceId }.size
            when {
                sources > 1 -> "$sources 个来源"
                chapter.variants.size > 1 -> "${chapter.variants.size} 个版本"
                else -> "来源"
            }
        }
        Row(modifier.heightIn(min = 22.dp).clip(RoundedCornerShape(5.dp))
            .clickable(enabled = enabled, interactionSource = interaction, indication = null,
                role = Role.Button, onClickLabel = "选择本章来源", onClick = { onOpen(anchor.bounds()) })
            .motionPress(interaction, enabled = enabled, pressedScale = .97f)
            .semantics(mergeDescendants = true) { contentDescription = source.sourceName + "，" + label },
            horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            ChapterSourceLogo(source.sourceId, source.sourceName, Modifier.size(13.dp).then(anchor.modifier), muted = true)
            Text(label, color = DetailSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(11.dp), tint = DetailMuted)
        }
        return
    }
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        ReaderSourceStack(chapter, compact = true, enabled = enabled,
            modifier = anchor.modifier, onOpen = { onOpen(anchor.bounds()) })
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
