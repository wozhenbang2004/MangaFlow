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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import dev.mangaflow.ui.*
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

/** Followed works, viewing history and folders share one collection and local filter row. */
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
                    current.catalogDetail == entry.catalogDetail) withContext(NonCancellable) {
                    controller.clearWatchHistory(entry.subject.id, expectedResume = original,
                        expectedRevision = entry.revision, expectedRestoreEpoch = originalEpoch)?.join()
                }
                pendingHistory = pendingHistory - entry.subject.id
            }
        }
    }
    val filterLabels = listOf("全部", "追番", "历史")
    val orderLabels = listOf("最近观看", "作品名称", "年份")
    val filter = viewState.filter.coerceIn(filterLabels.indices)
    val folder = viewState.folder
    val order = viewState.order.coerceIn(orderLabels.indices)
    LaunchedEffect(active) {
        if (!active) {
            optionsOpen = false
            selectedId = null
            historyId = null
            pendingEntryAction = null
        }
    }
    LaunchedEffect(historyId, state.library) {
        if (historyId != null && state.library.none { it.subject.id == historyId }) historyId = null
    }
    val folders = state.library.map { it.folder }.filter { it.isNotBlank() }.distinct().sorted()
    val entries = state.library.map { entry ->
        if (entry.subject.id in pendingHistory) entry.copy(resume = null) else entry
    }.filter { entry ->
        (when (filter) { 1 -> entry.followed; 2 -> entry.resume != null; else -> entry.followed || entry.resume != null }) &&
            (folder == null || entry.folder == folder)
    }.let { rows ->
        when (order) {
            1 -> rows.sortedWith(compareByDescending<AnimeLibraryEntry> { it.pinned }.thenBy { it.subject.title })
            2 -> rows.sortedWith(compareByDescending<AnimeLibraryEntry> { it.pinned }
                .thenByDescending { it.subject.year ?: 0 }.thenBy { it.subject.title })
            else -> rows.sortedWith(compareByDescending<AnimeLibraryEntry> { it.pinned }
                .thenByDescending { it.resume?.updatedAt ?: it.updatedAt })
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = MangaLayout.ListWidth).fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MotionTabs(filter, filterLabels, onSelect = { viewState.filter = it },
                modifier = Modifier.weight(1f).motionInputGate(contentActive),
                controlHeight = 44.dp, treatment = MotionTabTreatment.Soft,
                fullWidthIndicator = true, tooltips = listOf("全部作品", "已追番", "观看历史"))
            Box {
                MotionIconButton(onClick = { optionsOpen = true }, enabled = contentActive,
                    modifier = Modifier.size(44.dp), tooltip = "文件夹与排序") {
                    Icon(if (folder == null) Icons.Outlined.FilterList else Icons.Outlined.Folder,
                        "文件夹与排序", Modifier.size(20.dp))
                }
                CompactGlassPopover(optionsOpen && active, backdrop, onDismiss = { optionsOpen = false },
                    onPresenceChange = { optionsPresent = it }) {
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
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically) {
            AnimeQuietLabel(listOfNotNull(folder?.ifBlank { "未分组" }, orderLabels[order]).joinToString(" · "),
                Modifier.weight(1f))
            Text(if (state.libraryReadUnavailable) "— 部" else "${entries.size} 部",
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (folder != null) MotionIconButton(onClick = { viewState.folder = null },
                enabled = contentActive, modifier = Modifier.size(44.dp),
                tooltip = "清除文件夹筛选") { Icon(Icons.Outlined.Close, "清除文件夹筛选", Modifier.size(16.dp)) }
        }
        val positionKey = "$filter:${folder?.length ?: -1}:$folder:$order"
        Box(Modifier.weight(1f).fillMaxWidth()) {
            positions.SaveableStateProvider(positionKey) {
                val position = if (filter != 1 && folder == null && order == 0) scroll else rememberLazyListState()
                LazyColumn(Modifier.fillMaxSize().motionInputGate(contentActive),
                    state = position, userScrollEnabled = contentActive,
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = contentBottomPadding)) {
                    if (state.reviews.isNotEmpty()) item(key = "reviews") {
                        AnimeSelection("待核对 " + state.reviews.size + " 项", "状态与已看集数逐项选择", false, onReview)
                    }
                    if (state.libraryReadUnavailable) item(key = "library-unavailable") {
                        AnimeMessage("本地追番记录暂不可用",
                            "原文件已保留，这不代表收藏或历史为空。可以继续浏览和播放，但本次进度不会保存。请检查存储空间或通过备份恢复。",
                            action = "去发现", onAction = onDiscover)
                    }
                    if (entries.isEmpty() && !state.libraryReadUnavailable) item(key = "empty-library") {
                        val filtered = filter != 0 || folder != null
                        AnimeMessage(when {
                            filter == 2 -> "还没有观看记录"
                            filter == 1 && folder == null -> "还没有追番"
                            filtered -> "当前筛选下没有番剧"
                            else -> "你的追番从这里开始"
                        }, action = if (state.library.isNotEmpty() && filtered) "查看全部" else "去发现", onAction = {
                            if (state.library.isNotEmpty() && filtered) {
                                viewState.filter = 0
                                viewState.folder = null
                            } else onDiscover()
                        })
                    }
                    items(entries, key = { it.subject.id }) { entry ->
                        val target = controller.continuationEpisode(entry.subject.id)
                        val continueLabel = animeContinuationLabel(entry, target)
                        val subtitle = listOfNotNull(
                            entry.resume?.let { animeWatchLocation(entry, it) },
                            animeStatusLabel(entry.status).takeIf { entry.followed },
                            entry.folder.takeIf { it.isNotBlank() },
                            "置顶".takeIf { entry.pinned },
                        ).joinToString(" · ")
                        SwipeToRemove(onRemove = {
                            if (filter == 2) removeHistory(entry)
                            else controller.setFollowed(entry.subject, false)
                        }, enabled = contentActive && !state.libraryReadUnavailable && filter != 0,
                            label = if (filter == 2) "移除观看记录" else "取消追番", transparentBackground = true) {
                            AnimeSubjectRow(entry.subject, subtitle,
                                onOpen = { if (entry.resume != null) onResume(entry) else onOpen(entry.subject) },
                                onOpenLabel = continueLabel,
                                onLongClick = { entryPresent = true; selectedId = entry.subject.id },
                                ratings = { AnimeSubjectRatings(entry.subject, enabled = contentActive) },
                                supporting = {
                                    val resume = entry.resume
                                    if (resume != null && !resume.completed && resume.durationMs > 0L)
                                        AnimeWatchProgressLine((resume.positionMs.toFloat() / resume.durationMs).coerceIn(0f, 1f),
                                            Modifier.padding(top = 3.dp))
                                }, trailing = {
                                    if (entry.resume != null) MotionIconButton(onClick = { historyId = entry.subject.id },
                                        enabled = contentActive, modifier = Modifier.size(44.dp), tooltip = "逐集观看记录") {
                                        HistoryActionIcon(active = historyId == entry.subject.id,
                                            description = "逐集观看记录", modifier = Modifier.size(20.dp))
                                    } else MotionIconButton(onClick = { entryPresent = true; selectedId = entry.subject.id },
                                        enabled = contentActive, modifier = Modifier.size(44.dp), tooltip = "管理这部番剧") {
                                        Icon(Icons.Outlined.MoreHoriz, "管理番剧", Modifier.size(19.dp))
                                    }
                                })
                        }
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
    historyId?.takeIf { active }?.let { id -> state.library.firstOrNull { it.subject.id == id } }?.let { entry ->
        AnimeEpisodeHistorySheet(entry, state.episodeProgress, backdrop,
            onDismiss = { historyId = null },
            onContinue = { progress -> if (active) onResumeEpisode(entry, progress) })
    }
}

@Composable
internal fun AnimeWatchProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.fillMaxWidth().height(2.dp).semantics {
        contentDescription = "本集观看进度"
        progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
    }) {
        drawLine(ink.copy(alpha = .10f), androidx.compose.ui.geometry.Offset.Zero,
            androidx.compose.ui.geometry.Offset(size.width, 0f), 2.dp.toPx(), StrokeCap.Round)
        drawLine(ink.copy(alpha = .55f), androidx.compose.ui.geometry.Offset.Zero,
            androidx.compose.ui.geometry.Offset(size.width * fraction, 0f), 2.dp.toPx(), StrokeCap.Round)
    }
}

/** Adapted from DetailReadingDock's material, press, progress and compact geometry. */
@Composable
internal fun AnimeContinueWatching(entry: AnimeLibraryEntry, onResume: () -> Unit,
    onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val resume = entry.resume ?: return
    val interaction = remember(entry.subject.id) { MutableInteractionSource() }
    val motion = LocalMotionEnabled.current
    val fraction = if (resume.durationMs > 0L) (resume.positionMs.toFloat() / resume.durationMs).coerceIn(0f, 1f) else 0f
    val progress by animateFloatAsState(fraction,
        if (motion) tween(MangaMotion.Content, easing = MangaMotion.EnterEasing) else snap(), label = "anime-resume-progress")
    val color = MaterialTheme.colorScheme.onSurface
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(colors.surfaceContainerLow)
        .padding(bottom = 6.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .motionPress(interaction, pressedScale = .99f)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onResume)
                .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                AnimePoster(entry.subject, Modifier.width(33.dp).height(46.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(entry.subject.title, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    AnimeQuietLabel("继续观看 · " + animeTime(resume.positionMs))
                }
                Icon(Icons.Outlined.PlayArrow, null, Modifier.size(24.dp), tint = colors.primary)
                MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "隐藏这条续看") {
                    Icon(Icons.Outlined.Close, "隐藏这条续看", Modifier.size(17.dp))
                }
            }
            if (resume.durationMs > 0L) Canvas(Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 20.dp)
                .semantics { contentDescription = "观看进度 " + (fraction * 100).toInt() + "%" }) {
                drawLine(color.copy(alpha = .08f), start = androidx.compose.ui.geometry.Offset.Zero,
                    end = androidx.compose.ui.geometry.Offset(size.width, 0f), strokeWidth = 2.dp.toPx())
                drawLine(color.copy(alpha = .6f), start = androidx.compose.ui.geometry.Offset.Zero,
                    end = androidx.compose.ui.geometry.Offset(size.width * progress, 0f),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
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
                    Text(shown.subject.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭") {
                        Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp))
                    }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
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
                            CompactMenuAction(Icons.Outlined.History, "清除观看位置", enabled = shown.resume != null,
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
                            Text(if (section == 2) "清除这部番的逐集观看位置。追番和已看标记保留。"
                                else "移除这部番的本地追番、观看记录与来源关联。此操作不会删除追番平台的数据。",
                                Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium)
                            MotionButton(onClick = {
                                if (section == 2) controller.clearWatchHistory(shown.subject.id)
                                else controller.removeLibraryEntry(shown.subject.id)
                                onDismiss()
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(if (section == 2) "确认清除观看位置" else "确认移除本地记录")
                            }
                        }
                    }
                }
            }
        }
    }
}
