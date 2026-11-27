package dev.mangaflow.anime.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.lerp
import dev.chrisbanes.haze.glass.LocalGlassAccessibilitySettings
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState
import dev.mangaflow.anime.*
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import dev.mangaflow.ui.*
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.mangaflow.ui.motion.*

@Stable
internal class AnimeLibraryViewState internal constructor(
    filterState: MutableIntState,
    folderState: MutableState<String?>,
    orderState: MutableIntState,
    val positions: SaveableStateHolder,
) {
    var filter by filterState
    var folder by folderState
    var order by orderState
}

/** Keep view choices above the retained route so details and sibling columns cannot reset them. */
@Composable
internal fun rememberAnimeLibraryViewState(): AnimeLibraryViewState {
    val filter = rememberSaveable { mutableIntStateOf(0) }
    val folder = rememberSaveable { mutableStateOf<String?>(null) }
    val order = rememberSaveable { mutableIntStateOf(0) }
    val positions = rememberSaveableStateHolder()
    return remember(filter, folder, order, positions) { AnimeLibraryViewState(filter, folder, order, positions) }
}


@Composable
internal fun AnimeLibrary(
    state: AnimeScreenState,
    scroll: LazyListState,
    controller: AnimeController,
    backdrop: HazeState,
    viewState: AnimeLibraryViewState,
    onOpen: (AnimeSubject) -> Unit,
    onResume: (AnimeLibraryEntry) -> Unit,
    onResumeEpisode: (AnimeLibraryEntry, AnimeWatchProgress) -> Unit,
    onDiscover: () -> Unit,
    onReview: () -> Unit,
    active: Boolean = true,
    contentBottomPadding: Dp = 24.dp,
) {
    var optionsOpen by remember { mutableStateOf(false) }
    var optionsPresent by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var entryPresent by remember { mutableStateOf(false) }
    var historyId by remember { mutableStateOf<String?>(null) }
    var pendingEntryAction by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val positions = viewState.positions
    val snackbar = remember { SnackbarHostState() }
    val removalScope = rememberCoroutineScope()
    var pendingHistory by remember { mutableStateOf<Set<String>>(emptySet()) }
    val latestState by rememberUpdatedState(state)
    val contentActive = active && !optionsPresent && !entryPresent && historyId == null
    fun removeHistory(entry: AnimeLibraryEntry) {
        val original = entry.resume ?: return
        val originalEpoch = controller.libraryRestoreEpoch
        if (entry.subject.id in pendingHistory) return
        pendingHistory = pendingHistory + entry.subject.id
        snackbar.currentSnackbarData?.dismiss()
        removalScope.launch {
            var undone = false
            try {
                undone = snackbar.showSnackbar("已移除观看记录", actionLabel = "撤销",
                    withDismissAction = true, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed
            } finally {
                // A newer watch or restored record must not be erased by an older swipe.
                val current = controller.state.value.library.firstOrNull { it.subject.id == entry.subject.id }
                if (!undone && current != null && current.resume == original && current.revision == entry.revision &&
                    current.catalogDetail == entry.catalogDetail && current.visibleWatchMillis == entry.visibleWatchMillis
                ) withContext(NonCancellable) {
                    controller.clearWatchHistory(entry.subject.id, expectedResume = original,
                        expectedRevision = entry.revision, expectedRestoreEpoch = originalEpoch,
                        expectedVisibleWatchMillis = entry.visibleWatchMillis)?.join()
                }
                pendingHistory = pendingHistory - entry.subject.id
            }
        }
    }
    val filterLabels = listOf("全部", "已追", "历史")
    val orderLabels = listOf("最近观看", "作品名称", "年份")
    val filter = viewState.filter.coerceIn(filterLabels.indices)
    val showingHistory = filter == 2
    // The history shortcut selects this tab without resetting the saved library filters.
    val folder = viewState.folder.takeUnless { showingHistory }
    val order = if (showingHistory) 0 else viewState.order.coerceIn(orderLabels.indices)
    LaunchedEffect(active, showingHistory) {
        if (!active || showingHistory) optionsOpen = false
        if (!active) {
            selectedId = null
            historyId = null
            pendingEntryAction = null
        }
    }
    val historyEntry = historyId?.takeIf { active && !state.libraryReadUnavailable && it !in pendingHistory }
        ?.let { id -> state.library.firstOrNull { it.subject.id == id && it.resume?.belongsToWatchHistory(id) == true } }
    LaunchedEffect(historyId, historyEntry) {
        if (historyId != null && historyEntry == null) historyId = null
    }
    val folders = remember(state.library) {
        state.library.map { it.folder }.filter { it.isNotBlank() }.distinct().sorted()
    }
    val entries = remember(state.library, pendingHistory, filter, folder, order, state.libraryReadUnavailable) {
        if (showingHistory) return@remember if (state.libraryReadUnavailable) emptyList() else
            state.library.filter { it.subject.id !in pendingHistory && it.resume?.belongsToWatchHistory(it.subject.id) == true }
                .sortedWith(compareByDescending<AnimeLibraryEntry> { it.resume?.updatedAt ?: 0L }
                    .thenBy { it.subject.id })
        val sorted = compareByDescending<AnimeLibraryEntry> { it.pinned }
        val byPreference = when (order) {
            1 -> sorted.thenBy { it.subject.title }
            2 -> sorted.thenByDescending { it.subject.year ?: 0 }.thenBy { it.subject.title }
            else -> sorted.thenByDescending { it.resume?.updatedAt ?: it.updatedAt }
        }
        state.library.asSequence().filter { folder == null || it.folder == folder }
            .map { entry -> if (entry.subject.id in pendingHistory) entry.copy(resume = null) else entry }
            .filter { when (filter) {
                1 -> it.followed
                else -> it.followed || it.resume != null
            } }.sortedWith(byPreference).toList()
    }
    val showContinuation = filter == 0 && folder == null && order == 0 && !state.libraryReadUnavailable
    val continuation = remember(entries, showContinuation, controller, state.detail) {
        if (!showContinuation) null else entries.asSequence().filter { it.resume != null }
            .sortedByDescending { it.resume?.updatedAt }.firstOrNull { entry ->
                val progress = entry.resume ?: return@firstOrNull false
                !progress.completed ||
                    controller.continuationEpisode(entry.subject.id)?.id?.let { it != progress.episodeId } == true
            }
    }
    val works = remember(entries, continuation) {
        if (continuation == null) entries else entries.filterNot { it.subject.id == continuation.subject.id }
    }
    val recordedWatchMillis = remember(showingHistory, entries) {
        if (!showingHistory) 0L else entries.fold(0L) { total, entry ->
            total + minOf(entry.visibleWatchMillis, Long.MAX_VALUE - total)
        }
    }
    fun playHistoryEpisode(entry: AnimeLibraryEntry, choice: AnimeWatchProgress) {
        if (!active || entry.subject.id in pendingHistory) return
        // A modal may finish closing after a record was removed or the library changed.
        val latest = controller.state.value.takeUnless { it.libraryReadUnavailable } ?: return
        val id = entry.subject.id
        val owner = latest.library.firstOrNull { it.subject.id == id } ?: return
        val record = latest.episodeProgress[choice.episodeId]?.takeIf { it.subjectId == id }
            ?: owner.resume?.takeIf { it.episodeId == choice.episodeId && it.belongsToWatchHistory(id) }
            ?: return
        if (animeHistoryPlayback(owner, record, latest.episodeProgress, latest.detail) != AnimeHistoryPlayback.UNAVAILABLE)
            onResumeEpisode(owner, record)
    }
    val surface = MaterialTheme.colorScheme.surface
    val listBackground = remember(surface) {
        Brush.verticalGradient(listOf(surface.copy(alpha = .40f), surface.copy(alpha = .65f)))
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = MangaLayout.ListWidth).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                MotionTabs(filter, filterLabels, onSelect = { viewState.filter = it },
                    modifier = Modifier.weight(1f).motionInputGate(contentActive),
                    controlHeight = 44.dp, treatment = MotionTabTreatment.Underline,
                    tooltips = listOf("全部番剧", "已追番", "观看历史"))
                if (!showingHistory || optionsPresent) Box {
                    MotionIconButton(onClick = { optionsOpen = true }, enabled = contentActive,
                        modifier = Modifier.size(44.dp), tooltip = "片单选项") {
                        Icon(if (folder == null) Icons.Outlined.FilterList else Icons.Outlined.Folder,
                            "片单选项", Modifier.size(20.dp))
                    }
                    CompactGlassPopover(optionsOpen && active && !showingHistory, backdrop, onDismiss = { optionsOpen = false },
                        onPresenceChange = { optionsPresent = it }) {
                        if (state.canUndo) CompactMenuAction(Icons.Outlined.Undo, "撤销上次修改",
                            onClick = { optionsOpen = false; controller.undo() })
                        AnimeQuietLabel("文件夹", Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        CompactMenuAction(if (folder == null) Icons.Outlined.Check else Icons.Outlined.Folder, "全部文件夹",
                            onClick = { viewState.folder = null; optionsOpen = false })
                        CompactMenuAction(if (folder == "") Icons.Outlined.Check else Icons.Outlined.FolderOpen, "未分组",
                            onClick = { viewState.folder = ""; optionsOpen = false })
                        folders.forEach { name ->
                            CompactMenuAction(if (folder == name) Icons.Outlined.Check else Icons.Outlined.Folder, name,
                                onClick = { viewState.folder = name; optionsOpen = false })
                        }
                        AnimeQuietLabel("排序", Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        orderLabels.forEachIndexed { index, label ->
                            CompactMenuAction(if (order == index) Icons.Outlined.Check else Icons.Outlined.Sort, label,
                                onClick = { viewState.order = index; optionsOpen = false })
                        }
                    }
                }
            }
            if (folder != null || order != 0) Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimeQuietLabel(listOfNotNull(folder?.ifBlank { "未分组" }, orderLabels[order].takeIf { order != 0 })
                    .joinToString(" · "), Modifier.weight(1f))
                MotionIconButton(onClick = { viewState.folder = null; viewState.order = 0 },
                    enabled = contentActive, modifier = Modifier.size(44.dp),
                    tooltip = "重置筛选和排序") { Icon(Icons.Outlined.Close, "重置筛选和排序", Modifier.size(16.dp)) }
            }
            val positionKey = "$filter:${folder?.length ?: -1}:$folder:$order"
            Box(Modifier.weight(1f).fillMaxWidth().mangaHazeSource(backdrop).background(listBackground)) {
                positions.SaveableStateProvider(positionKey) {
                    val position = if (filter != 1 && folder == null && order == 0) scroll else rememberLazyListState()
                    LazyColumn(Modifier.fillMaxSize().motionInputGate(contentActive),
                        state = position, userScrollEnabled = contentActive,
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = contentBottomPadding),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (state.libraryReadUnavailable) item(key = "library-unavailable") {
                            AnimeMessage(if (showingHistory) "观看历史暂时无法读取" else "片单暂时无法读取",
                                "本地记录已保留。存储恢复前，新观看进度无法保存。",
                                action = "去发现", onAction = onDiscover)
                        }
                        if (continuation != null) {
                            item(key = continuation.subject.id, contentType = "resume-feature") {
                                val target = controller.continuationEpisode(continuation.subject.id)
                                AnimeLibraryWorkRow(continuation, animeContinuationLabel(continuation, target),
                                    onOpen = { onResume(continuation) },
                                    featured = true, active = contentActive,
                                    onManage = { entryPresent = true; selectedId = continuation.subject.id })
                            }
                        }
                        if (!showingHistory && state.reviews.isNotEmpty()) item(key = "reviews") {
                            AnimeSelection("待核对的追番记录", "${state.reviews.size} 项待确认", false, onReview)
                        }
                        if (entries.isEmpty() && !state.libraryReadUnavailable) item(key = "empty-library") {
                            val canClear = !showingHistory && (folder != null || (filter != 0 && state.library.any {
                                it.followed || it.resume != null
                            }))
                            AnimeMessage(when {
                                folder != null -> "这个文件夹里暂时没有番剧"
                                filter == 2 -> "还没有观看记录"
                                filter == 1 -> "还没有追番"
                                else -> "片单还是空的"
                            }, when {
                                folder != null -> null
                                filter == 2 -> "看过的番剧和进度会出现在这里。"
                                else -> "在番剧详情页点追番，把想看的作品收进来。"
                            }, action = if (canClear) "查看片单" else "去发现", onAction = {
                                if (canClear) {
                                    viewState.filter = 0
                                    viewState.folder = null
                                } else onDiscover()
                            })
                        }
                        if (works.isNotEmpty()) item(key = "list-title") {
                            AnimeLibrarySection(when (filter) {
                                1 -> "已追番"
                                2 -> "最近观看"
                                else -> if (continuation == null) "我的番剧" else "其他番剧"
                            }, count = works.size.takeUnless { showingHistory || state.libraryReadUnavailable },
                                detail = recordedWatchMillis.takeIf { it > 0L }?.let { "已记录 ${animeWatchElapsed(it)}" })
                        }
                        items(works, key = { it.subject.id }, contentType = { "library-work" }) { entry ->
                            val record = entry.resume
                            val historyRecord = record?.takeIf { showingHistory }
                            val historyPlayback = historyRecord?.let {
                                animeHistoryPlayback(entry, it, state.episodeProgress, state.detail)
                            }
                            val action = when (historyPlayback) {
                                AnimeHistoryPlayback.UNAVAILABLE -> "查看详情"
                                null -> if (record?.completed != true) animeContinuationLabel(entry, null)
                                else remember(controller, entry, state.detail) {
                                    animeContinuationLabel(entry, controller.continuationEpisode(entry.subject.id))
                                }
                                else -> historyPlayback.label
                            }
                            SwipeToRemove(onRemove = {
                                if (showingHistory) removeHistory(entry)
                                else controller.setFollowed(entry.subject, false)
                            }, enabled = contentActive && !state.libraryReadUnavailable && filter != 0,
                                label = if (showingHistory) "移除观看记录" else "取消追番", transparentBackground = true) {
                                AnimeLibraryWorkRow(entry, action,
                                    onOpen = {
                                        if (historyRecord != null && historyPlayback != AnimeHistoryPlayback.UNAVAILABLE)
                                            playHistoryEpisode(entry, historyRecord)
                                        else if (record == null || showingHistory) onOpen(entry.subject)
                                        else onResume(entry)
                                    },
                                    onHistory = if (historyRecord != null) {
                                        { historyId = entry.subject.id }
                                    } else null,
                                    onManage = { entryPresent = true; selectedId = entry.subject.id },
                                    showRatings = !showingHistory, active = contentActive, historyPlayback = historyPlayback)
                            }
                        }
                        if (showingHistory && works.isNotEmpty()) item(key = "visible-watch-time") {
                            AnimeQuietLabel(when {
                                recordedWatchMillis > 0L -> "时长仅计新版 Android 可见播放，旧记录不补算"
                                platformKind == PlatformKind.ANDROID -> "尚无计时记录 · 旧观看进度不补算"
                                else -> "观看计时暂仅支持 Android"
                            }, Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp))
                        }
                    }
                    if (platformKind == PlatformKind.DESKTOP) DesktopVerticalScrollbar(position,
                        Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp))
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp)
            .padding(bottom = contentBottomPadding))
    }

    AnimeLibraryEntryPanel(selectedId?.takeIf { active }?.let { id -> state.library.firstOrNull { it.subject.id == id } },
        controller, backdrop, folders,
        onOpen = { pendingEntryAction = it.subject.id to false; selectedId = null },
        onHistory = { pendingEntryAction = it.subject.id to true; selectedId = null },
        onDismiss = { selectedId = null }, onClosed = {
            entryPresent = false
            val next = pendingEntryAction
            pendingEntryAction = null
            if (active && next != null) latestState.library.firstOrNull { it.subject.id == next.first }?.let { entry ->
                if (next.second) historyId = entry.subject.id else onOpen(entry.subject)
            }
        })
    historyEntry?.let { entry ->
        AnimeEpisodeHistorySheet(entry, state.episodeProgress, state.detail, backdrop,
            onDismiss = { historyId = null },
            onContinue = { progress -> playHistoryEpisode(entry, progress) })
    }
}

@Composable
private fun AnimeLibrarySection(title: String, count: Int? = null, detail: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        val subtitle = detail ?: count?.let { "$it 部" }
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End, modifier = if (detail == null) Modifier else Modifier.weight(.9f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AnimeLibraryWorkRow(
    entry: AnimeLibraryEntry,
    actionLabel: String,
    onOpen: () -> Unit,
    onHistory: (() -> Unit)? = null,
    onManage: (() -> Unit)? = null,
    featured: Boolean = false,
    showRatings: Boolean = false,
    active: Boolean = true,
    historyPlayback: AnimeHistoryPlayback? = null,
) {
    val colors = MaterialTheme.colorScheme
    val resume = entry.resume
    val interaction = remember(entry.subject.id) { MutableInteractionSource() }
    val shape = RoundedCornerShape(if (featured) 22.dp else 14.dp)
    val location = remember(entry.subject.id, entry.catalogDetail, resume) {
        resume?.let { animeWatchLocation(entry, it) }
    }
    val extra = remember(entry.status, entry.followed, entry.folder, entry.pinned) {
        listOfNotNull(
            animeStatusLabel(entry.status).takeIf { entry.followed },
            entry.folder.takeIf { it.isNotBlank() },
            "置顶".takeIf { entry.pinned },
        ).joinToString(" · ")
    }
    Row(Modifier.fillMaxWidth().clip(shape)
        .then(if (featured) Modifier.background(lerp(colors.surface, colors.primary, .07f)) else Modifier),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clip(shape).motionPress(interaction, enabled = active, pressedScale = .99f)
            .combinedClickable(enabled = active, interactionSource = interaction, indication = null, role = Role.Button,
                onClickLabel = actionLabel, onClick = onOpen,
                onLongClickLabel = if (onManage != null) "管理番剧" else null, onLongClick = onManage)
            .padding(start = if (featured) 8.dp else 4.dp, end = 4.dp,
                top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (featured) {
                // Only the lead poster supplies pixels to its own play lens.
                val artwork = rememberHazeState()
                Box(Modifier.size(70.dp, 105.dp), contentAlignment = Alignment.BottomEnd) {
                    AnimePoster(entry.subject, Modifier.fillMaxSize().mangaHazeSource(artwork))
                    LiquidGlassSurface(artwork, Modifier.padding(4.dp).size(28.dp),
                        cornerRadius = 14.dp, role = GlassSurfaceRole.ARTWORK,
                        trackPointer = true, elevation = 0.dp, preferSource = true,
                        surfaceColor = colors.surface, contentColor = colors.onSurface) {
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.align(Alignment.Center).size(19.dp))
                    }
                }
            } else AnimePoster(entry.subject, Modifier.size(50.dp, 75.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (featured) Text(actionLabel, style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(entry.subject.title, style = animeWorkTitleStyle(large = featured),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (location != null && resume != null) {
                    Text(location, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (resume.completed && !featured && onHistory == null) {
                        Text(actionLabel, style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else if (!resume.completed) animeWatchFraction(resume)?.let { fraction ->
                        AnimeWatchProgressLine(fraction, Modifier.widthIn(max = if (featured) 188.dp else 128.dp)
                            .padding(vertical = 2.dp))
                    }
                }
                if (historyPlayback != null && resume != null)
                    AnimeHistoryActionLine(historyPlayback, resume.updatedAt)
                if (extra.isNotBlank() && !featured && historyPlayback == null) Text(extra, style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (showRatings) AnimeSubjectRatings(entry.subject, enabled = active)
            }
        }
        if (onHistory != null) MotionIconButton(onClick = onHistory, enabled = active,
            modifier = Modifier.size(44.dp), tooltip = "逐集观看记录") {
            HistoryActionIcon(active = false, description = "逐集观看记录", modifier = Modifier.size(20.dp))
        }
        if (onManage != null) MotionIconButton(onClick = onManage, enabled = active,
            modifier = Modifier.size(44.dp), tooltip = "管理这部番剧") {
            Icon(Icons.Outlined.MoreHoriz, "管理番剧", Modifier.size(19.dp))
        }
    }
}

@Composable
internal fun AnimeWatchProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    val progress = fraction.coerceIn(0f, 1f)
    AnimeProgressTrack(progress, modifier.semantics {
        contentDescription = "本集观看进度"
        progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
    })
}

@Composable
internal fun AnimeProgressTrack(fraction: Float, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val contrast = LocalGlassAccessibilitySettings.current.increaseContrast
    Canvas(modifier.fillMaxWidth().height(3.dp)) {
        val corner = CornerRadius(size.height / 2f)
        val width = size.width * fraction.coerceIn(0f, 1f)
        drawRoundRect(colors.onSurface.copy(alpha = if (contrast) .32f else .11f), cornerRadius = corner)
        if (width > 0f) drawRoundRect(if (contrast) colors.onSurface else colors.primary,
            topLeft = Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f, 0f),
            size = Size(width, size.height), cornerRadius = corner)
    }
}

@Composable
private fun AnimeLibraryEntryPanel(entry: AnimeLibraryEntry?, controller: AnimeController,
    backdrop: HazeState, folders: List<String>, onOpen: (AnimeLibraryEntry) -> Unit,
    onHistory: (AnimeLibraryEntry) -> Unit, onDismiss: () -> Unit, onClosed: () -> Unit) {
    var retained by remember { mutableStateOf<AnimeLibraryEntry?>(null) }
    SideEffect { if (entry != null) retained = entry }
    val shown = entry ?: retained
    var section by remember(shown?.subject?.id) { mutableIntStateOf(0) }
    var folderDraft by remember(shown?.subject?.id) { mutableStateOf(shown?.folder.orEmpty()) }
    MotionDialog(entry != null, onDismiss, maximumWidth = 480.dp, onClosed = { retained = null; onClosed() }) {
        if (shown != null) AnimePanelSurface(backdrop) {
            Column(Modifier.fillMaxWidth().heightIn(max = LocalMangaWindow.current.panelHeight).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (section != 0) MotionIconButton(onClick = { section = 0 }, modifier = Modifier.size(44.dp), tooltip = "返回管理") {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", Modifier.size(20.dp))
                    }
                    if (section == 0) {
                        AnimePoster(shown.subject, Modifier.width(42.dp).height(62.dp))
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(when (section) {
                            1 -> "移动到文件夹"
                            2 -> "清除观看记录"
                            3 -> "移除本地记录"
                            else -> shown.subject.title
                        }, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (section == 0 && shown.followed) AnimeQuietLabel(animeStatusLabel(shown.status))
                    }
                    MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭") {
                        Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp))
                    }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                    when (section) {
                        0 -> {
                            CompactMenuAction(Icons.Outlined.Info, "详情与选集", onClick = { onOpen(shown) })
                            if (shown.resume != null) CompactMenuAction(Icons.Outlined.History, "逐集观看记录",
                                onClick = { onHistory(shown) })
                            CompactMenuAction(Icons.Outlined.PushPin, if (shown.pinned) "取消置顶" else "置顶", onClick = {
                                controller.setPinned(shown.subject.id, !shown.pinned); onDismiss()
                            })
                            CompactMenuAction(Icons.Outlined.Folder, shown.folder.ifBlank { "移动到文件夹" }, onClick = { section = 1 })
                            CompactMenuAction(Icons.Outlined.BookmarkBorder, if (shown.followed) "取消追番" else "加入追番", onClick = {
                                controller.setFollowed(shown.subject, !shown.followed); onDismiss()
                            })
                            CompactMenuAction(Icons.Outlined.History, "清除观看记录", enabled = shown.resume != null,
                                onClick = { section = 2 })
                            CompactMenuAction(Icons.Outlined.DeleteOutline, "移除这部番剧", destructive = true,
                                onClick = { section = 3 })
                        }
                        1 -> {
                            AnimeTextField(folderDraft, { folderDraft = it }, "文件夹名称，留空放入未分组", Modifier.padding(vertical = 10.dp))
                            folders.forEach { name -> AnimeSelection(name, selected = folderDraft == name,
                                onClick = { folderDraft = name }) }
                            MotionButton(onClick = { controller.setFolder(shown.subject.id, folderDraft.trim()); onDismiss() },
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 48.dp)) { Text("保存文件夹") }
                        }
                        else -> {
                            Text(if (section == 2) "清除这部番的逐集观看位置和已记录时长。追番和已看标记保留。"
                                else "移除这部番的本地追番、观看记录与来源关联。此操作不会删除追番平台的数据。",
                                Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium)
                            MotionButton(onClick = {
                                if (section == 2) controller.clearWatchHistory(shown.subject.id)
                                else controller.removeLibraryEntry(shown.subject.id)
                                onDismiss()
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(if (section == 2) "确认清除观看记录" else "确认移除本地记录")
                            }
                        }
                    }
                }
            }
        }
    }
}
