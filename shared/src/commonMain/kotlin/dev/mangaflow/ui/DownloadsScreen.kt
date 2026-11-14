package dev.mangaflow.ui

import androidx.compose.foundation.layout.WindowInsetsSides

import androidx.compose.foundation.layout.windowInsetsPadding

import androidx.compose.foundation.layout.only

import androidx.compose.foundation.layout.asPaddingValues

import kotlinx.coroutines.IO

import dev.mangaflow.ui.platform.*

import dev.mangaflow.ui.motion.BackHandler

import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import dev.mangaflow.ui.motion.MotionBusyIndicator as CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import dev.mangaflow.ui.motion.MotionTextButton as TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import dev.mangaflow.platform.LocalMangaContext as LocalContext
import dev.mangaflow.platform.*
import dev.mangaflow.platform.storage.applicationContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.mangaflow.data.Manga
import dev.mangaflow.typography.MangaFontScene
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.DownloadStore
import dev.mangaflow.storage.DownloadStore.Companion.LOCAL_SOURCE_URL
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionButton
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.rememberDelayedBusy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

private enum class DownloadFilter(val label: String) { ALL("全部"), ACTIVE("进行中"), SAVED("已下载") }

// Layout depends on membership/order/completion, never on page or byte progress.
private data class DownloadDirectoryEntry(
    val id: String, val mangaId: String, val group: String, val chapterIndex: Int, val saved: Boolean,
)

private sealed interface LocalImportSource {
    data class Archive(val uri: String) : LocalImportSource
    data class Images(val uris: List<String>) : LocalImportSource
    data class Folder(val uri: String) : LocalImportSource
}

private fun LocalImportSource.documents(): List<String> = when (this) {
    is LocalImportSource.Archive -> listOf(uri)
    is LocalImportSource.Images -> uris
    is LocalImportSource.Folder -> listOf(uri)
}

private data class PendingLocalImport(
    val source: LocalImportSource,
    val mangaTitle: String,
    val chapterTitle: String,
)

@Composable
fun DownloadsScreen(
    store: DownloadStore,
    repository: MangaRepository,
    onRead: (DownloadEntry) -> Unit,
    onRetry: (DownloadEntry) -> Unit,
    backdrop: HazeState,
    navigation: @Composable () -> Unit,
    toolbar: @Composable (@Composable () -> Unit) -> Unit,
    active: Boolean = true,
    refreshingIds: Set<String> = emptySet(),
    history: List<ReadingProgress> = emptyList(),
    wallpaper: @Composable () -> Unit = {},
    backgroundInParent: Boolean = false,
    excludedSourceIds: Set<String> = emptySet(),
    onDetails: ((Manga) -> Unit)? = null,
) {
    val desktop = platformKind == PlatformKind.DESKTOP
    val context = LocalContext.current
    val entries by store.entries.collectAsStateWithLifecycle()
    val loading by store.loading.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptics = dev.mangaflow.ui.motion.rememberFeelFeedback()
    val snackbar = remember { SnackbarHostState() }
    var filter by rememberSaveable { mutableStateOf(DownloadFilter.ALL) }
    var removingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDeletion by remember { mutableStateOf<List<DownloadEntry>?>(null) }
    var managing by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expandedBooks by rememberSaveable { mutableStateOf(listOf<String>()) }
    var showImportSources by remember { mutableStateOf(false) }
    var showQueueActions by remember { mutableStateOf(false) }
    var filterMenuExpanded by remember { mutableStateOf(false) }
    val filterChoices = remember { listOf(GlassTabChoice("全部", Icons.Outlined.Inventory2), GlassTabChoice("进行中", Icons.Outlined.Downloading), GlassTabChoice("已下载", Icons.Outlined.CheckCircleOutline)) }
    val preferredFilterWidth = morphGlassTabWidth(filterChoices)
    var pendingImport by remember { mutableStateOf<PendingLocalImport?>(null) }
    var importBusy by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    val allScroll = rememberLazyListState()
    val activeScroll = rememberLazyListState()
    val savedScroll = rememberLazyListState()
    val showLoading = rememberDelayedBusy(loading)
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val contentInsets = screenFitContentInsets().asPaddingValues()
    val topInset = contentInsets.calculateTopPadding()
    val bottomInset = contentInsets.calculateBottomPadding()
    var headerHeightPx by remember(density, topInset) { mutableIntStateOf(with(density) { (48.dp + topInset).roundToPx() }) }
    val contentTopPadding = with(density) { headerHeightPx.toDp() } + HeaderContentGap
    val foreground = colors.onSurface
    val secondary = colors.onSurfaceVariant
    val visibleEntries = remember(entries, excludedSourceIds) { entries.filterNot { it.manga.sourceId in excludedSourceIds } }
    val manageableEntries = remember(visibleEntries, removingIds) { visibleEntries.filterNot { it.id in removingIds } }
    val selectedEntries = remember(manageableEntries, selectedIds) { manageableEntries.filter { it.id in selectedIds } }
    val entriesById = remember(visibleEntries) { visibleEntries.associateBy(DownloadEntry::id) }
    val historyById = remember(history) { history.associateBy { it.manga.id } }
    val directory = remember(manageableEntries) {
        manageableEntries.map { DownloadDirectoryEntry(it.id, it.manga.id, it.chapter.group,
            it.chapter.index, it.status == DownloadStatus.COMPLETED) }
    }
    val orderedBooks = remember(directory) {
        DownloadFilter.entries.associateWith { page ->
            directory.filter { entry -> when (page) {
                DownloadFilter.ALL -> true
                DownloadFilter.ACTIVE -> !entry.saved
                DownloadFilter.SAVED -> entry.saved
            } }.groupBy(DownloadDirectoryEntry::mangaId).mapValues { (_, chapters) ->
                chapters.groupBy(DownloadDirectoryEntry::group).values.flatMap { group ->
                    group.sortedBy(DownloadDirectoryEntry::chapterIndex).map(DownloadDirectoryEntry::id)
                }
            }
        }
    }
    val motion = LocalMotionEnabled.current
    val activeEntries = manageableEntries.filter { it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.WAITING) }
    val resumableEntries = manageableEntries.filter {
        it.status in setOf(DownloadStatus.PAUSED, DownloadStatus.FAILED) && it.manga.sourceUrl != LOCAL_SOURCE_URL
    }
    val completedEntries = manageableEntries.filter { it.status == DownloadStatus.COMPLETED }
    val usedBytes = visibleEntries.sumOf { it.downloadedBytes }
    var availableBytes by remember(store) { mutableStateOf<Long?>(null) }
    LaunchedEffect(showQueueActions, store) {
        if (showQueueActions) availableBytes = withContext(Dispatchers.IO) { store.availableStorageBytes() }
    }

    BackHandler(enabled = managing) { managing = false; selectedIds = emptySet() }

    val archivePicker = rememberOpenDocumentPicker() { uri ->
        uri?.let {
            val base = documentName(context, it).substringBeforeLast('.').ifBlank { "本地漫画" }
            pendingImport = PendingLocalImport(LocalImportSource.Archive(it), base, "第 1 话")
        }
    }
    val imagePicker = rememberMultipleDocumentPicker() { uris ->
        if (uris.isNotEmpty()) pendingImport = PendingLocalImport(
            LocalImportSource.Images(uris), "本地漫画", "第 1 话",
        )
    }
    val folderPicker = rememberFolderPicker() { uri ->
        uri?.let {
            pendingImport = PendingLocalImport(
                LocalImportSource.Folder(it), documentName(context, it).ifBlank { "本地漫画" }, "第 1 话",
            )
        }
    }

    fun importLocal(pending: PendingLocalImport, mangaTitle: String, chapterTitle: String) {
        if (importBusy) return
        importBusy = true
        importError = null
        scope.launch {
            try {
                val entry = when (val source = pending.source) {
                    is LocalImportSource.Archive -> store.importArchive(source.uri, mangaTitle, chapterTitle)
                    is LocalImportSource.Images -> store.importImages(source.uris, mangaTitle, chapterTitle)
                    is LocalImportSource.Folder -> store.importFolder(source.uri, mangaTitle, chapterTitle)
                }
                releasePickedDocuments(pending.source.documents())
                pendingImport = null
                filter = DownloadFilter.SAVED
                snackbar.showSnackbar("已导入 ${entry.manga.title} · ${entry.chapter.title}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                importError = failure.message ?: "本地漫画导入失败"
            } finally {
                importBusy = false
            }
        }
    }

    fun toggleSelection(targets: List<DownloadEntry>) {
        val ids = targets.map(DownloadEntry::id).toSet()
        selectedIds = if (ids.all { it in selectedIds }) selectedIds - ids else selectedIds + ids
    }

    fun removeWithUndo(targets: List<DownloadEntry>) {
        val ids = targets.map(DownloadEntry::id).toSet() - removingIds
        if (ids.isEmpty()) return
        removingIds = removingIds + ids
        snackbar.currentSnackbarData?.dismiss()
        scope.launch {
            var undone = false
            var failed = 0
            try {
                undone = snackbar.showSnackbar("已移除 ${ids.size} 话", actionLabel = "撤销",
                    withDismissAction = true, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed
                if (undone) haptics.perform(dev.mangaflow.ui.motion.FeelEvent.Undo)
            } finally {
                // Page navigation commits an already requested removal; undo never recreates deleted files.
                if (!undone) withContext(NonCancellable) {
                    ids.forEach { id ->
                        try { store.remove(id) }
                        catch (_: Exception) { failed++ }
                    }
                }
                removingIds = removingIds - ids
                if (failed > 0 && currentCoroutineContext().isActive)
                    snackbar.showSnackbar("$failed 话未能删除，请重试")
            }
        }
    }

    val queueActions: @Composable () -> Unit = {
                    if (managing) {
                        MotionIconButton(onClick = {
                            val visible = when (filter) {
                                DownloadFilter.ALL -> manageableEntries
                                DownloadFilter.ACTIVE -> manageableEntries.filter { it.status != DownloadStatus.COMPLETED }
                                DownloadFilter.SAVED -> completedEntries
                            }
                            toggleSelection(visible)
                        }, modifier = Modifier.size(44.dp)) { Icon(Icons.Outlined.SelectAll, "全选当前列表", Modifier.size(20.dp), tint = foreground) }
                        MotionIconButton(enabled = selectedEntries.isNotEmpty(), onClick = { pendingDeletion = selectedEntries },
                            modifier = Modifier.size(44.dp)) { Icon(Icons.Outlined.DeleteOutline, "删除选中的 ${selectedEntries.size} 话", Modifier.size(20.dp),
                                tint = if (selectedEntries.isNotEmpty()) colors.error else secondary.copy(alpha = .4f)) }
                        MotionIconButton(onClick = { managing = false; selectedIds = emptySet() }, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Outlined.Close, "退出管理", Modifier.size(20.dp), tint = secondary)
                        }
                    } else {
                        MotionIconButton(enabled = !importBusy, modifier = Modifier.size(44.dp),
                            onClick = { showImportSources = true }) {
                            if (importBusy) CircularProgressIndicator(Modifier.size(16.dp)
                                .semantics { contentDescription = "正在导入" }, strokeWidth = 1.5.dp, color = secondary)
                            else Icon(Icons.Outlined.Add, "导入漫画", Modifier.size(21.dp), tint = foreground)
                        }
                        Box {
                            MotionIconButton(onClick = { showQueueActions = !showQueueActions }, modifier = Modifier.size(44.dp)) {
                                Icon(Icons.Outlined.MoreHoriz, "存储与下载管理", Modifier.size(21.dp), tint = secondary)
                            }
                            CompactGlassPopover(visible = showQueueActions, backdrop = backdrop,
                                onDismiss = { showQueueActions = false },
                                maximumWidth = 216.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.35f)) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text("已下载", fontSize = 11.sp, color = secondary)
                                        Spacer(Modifier.weight(1f))
                                        Text(downloadBytes(usedBytes), fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium, color = foreground)
                                    }
                                    Text(availableBytes?.let { "${downloadBytes(it)} 可用" } ?: "正在读取可用空间…",
                                        fontSize = 11.sp, color = secondary)
                                }
                                HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    color = foreground.copy(alpha = .07f))
                                CompactMenuAction(Icons.Outlined.Inventory2, "管理下载", enabled = manageableEntries.isNotEmpty()) {
                                    showQueueActions = false; managing = true; selectedIds = emptySet()
                                }
                                if (activeEntries.isNotEmpty()) CompactMenuAction(Icons.Outlined.Pause, "全部暂停") {
                                    showQueueActions = false; activeEntries.forEach { store.pause(it.id) }
                                }
                                if (resumableEntries.isNotEmpty()) CompactMenuAction(Icons.Outlined.PlayArrow, "继续未完成") {
                                    showQueueActions = false; resumableEntries.forEach(onRetry)
                                }
                                if (completedEntries.isNotEmpty()) {
                                    HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                        color = foreground.copy(alpha = .07f))
                                    CompactMenuAction(Icons.Outlined.DeleteOutline, "清理已下载", destructive = true) {
                                        showQueueActions = false; pendingDeletion = completedEntries
                                    }
                                }
                            }
                        }
                    }
    }

    BoxWithConstraints(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (!desktop || event.type != KeyEventType.KeyDown) false
        else if ((event.isCtrlPressed || event.isMetaPressed) && event.key == Key.A && manageableEntries.isNotEmpty()) {
            val currentEntries = when (filter) {
                DownloadFilter.ALL -> manageableEntries
                DownloadFilter.ACTIVE -> manageableEntries.filter { it.status != DownloadStatus.COMPLETED }
                DownloadFilter.SAVED -> completedEntries
            }
            if (currentEntries.isEmpty()) false else {
                managing = true
                selectedIds = currentEntries.map(DownloadEntry::id).toSet()
                true
            }
        } else if (managing && event.key == Key.Delete && selectedEntries.isNotEmpty()) {
            pendingDeletion = selectedEntries
            true
        } else false
    }.background(if (LocalWallpaperActive.current) Color.Transparent else MaterialTheme.colorScheme.background)) {
        val filterWidth = preferredFilterWidth.coerceAtMost(
            (maxWidth - if (managing) 224.dp else 180.dp).coerceAtLeast(70.dp))
        val contentInset = MangaLayout.sidePadding(maxWidth)
        val desktopSplit = desktop && maxWidth >= 1040.dp && orderedBooks.getValue(filter).isNotEmpty()
        val visibleBookIds = orderedBooks.getValue(filter).keys
        val inspectedBook = expandedBooks.lastOrNull { it in visibleBookIds } ?: visibleBookIds.firstOrNull()
        val inspectedChapters = inspectedBook?.let { orderedBooks.getValue(filter)[it]?.mapNotNull(entriesById::get) }.orEmpty()
        val downloadChapterColumns = if (desktop) 1 else MangaLayout.columns(maxWidth, 4,
            96.dp * LocalDensity.current.fontScale.coerceAtLeast(1f), 6.dp, contentInset + 4.dp)
        // The captured scene contains the wallpaper and scrolling list, never the header itself.
        Box(Modifier.fillMaxSize().mangaHazeSource(backdrop).background(if (backgroundInParent) Color.Transparent else MaterialTheme.colorScheme.background)) {
            wallpaper()
            // Work selection and chapter disclosure share the current filtered queue.
            Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(if (desktopSplit) .46f else 1f).fillMaxSize()) {
            MotionTabContent(filter.ordinal, Modifier.fillMaxSize()) { index ->
                val pageFilter = DownloadFilter.entries[index]
                val visible = when (pageFilter) {
                    DownloadFilter.ALL -> manageableEntries
                    DownloadFilter.ACTIVE -> manageableEntries.filter { it.status != DownloadStatus.COMPLETED }
                    DownloadFilter.SAVED -> manageableEntries.filter { it.status == DownloadStatus.COMPLETED }
                }
                val scroll = when (pageFilter) {
                    DownloadFilter.ALL -> allScroll
                    DownloadFilter.ACTIVE -> activeScroll
                    DownloadFilter.SAVED -> savedScroll
                }
                LaunchedEffect(active, index, filter) {
                    if (!active || index != filter.ordinal) scroll.stopScroll()
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(), state = scroll,
                    contentPadding = PaddingValues(start = contentInset, end = contentInset, top = contentTopPadding, bottom = 32.dp + bottomInset),
                ) {
                    if (loading) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                                    if (showLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = foreground)
                                }
                                Spacer(Modifier.width(10.dp))
                                Text("正在恢复下载", style = MaterialTheme.typography.bodyMedium, color = secondary)
                            }
                        }
                    } else if (visible.isEmpty()) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(top = 72.dp, bottom = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(30.dp), tint = secondary)
                                }
                                Spacer(Modifier.height(18.dp))
                                Text(
                                    when (pageFilter) {
                                        DownloadFilter.ALL -> "暂时没有下载"
                                        DownloadFilter.ACTIVE -> "没有未完成的下载"
                                        DownloadFilter.SAVED -> "还没有下载完成的章节"
                                    },
                                    style = MaterialTheme.typography.titleMedium, color = foreground,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(when (pageFilter) {
                                    DownloadFilter.ALL -> "从漫画目录下载章节，或添加本地漫画。"
                                    DownloadFilter.ACTIVE -> if (completedEntries.isNotEmpty()) "已完成的章节保存在“已下载”中。" else "从漫画目录选择章节，即可开始下载。"
                                    DownloadFilter.SAVED -> if (manageableEntries.isNotEmpty()) "任务尚未完成，可在“进行中”查看。" else "下载完成后，就能在这里离线阅读。"
                                }, style = MaterialTheme.typography.bodySmall, color = secondary)
                                if (pageFilter == DownloadFilter.ACTIVE && completedEntries.isNotEmpty()) {
                                    TextButton(onClick = { filter = DownloadFilter.SAVED }) { Text("查看已下载") }
                                } else if (pageFilter == DownloadFilter.SAVED && manageableEntries.isNotEmpty()) {
                                    TextButton(onClick = { filter = DownloadFilter.ACTIVE }) { Text("查看进行中") }
                                } else if (pageFilter == DownloadFilter.ALL) {
                                    TextButton(onClick = { showImportSources = true }) { Text("添加本地漫画") }
                                }
                            }
                        }
                    } else {
                        orderedBooks.getValue(pageFilter).forEach { (mangaId, chapterIds) ->
                            val chapters = chapterIds.mapNotNull(entriesById::get)
                            val expanded = if (desktopSplit) mangaId == inspectedBook
                                else mangaId in expandedBooks && chapters.size > 1
                            item(key = "work:$mangaId", contentType = "download-work") {
                                val placement = Modifier.animateItem(
                                    fadeInSpec = if (motion) tween(MangaMotion.Content) else null,
                                    placementSpec = if (motion) tween(MangaMotion.Content) else null,
                                    fadeOutSpec = if (motion) tween(MangaMotion.Quick) else null)
                                Box(placement) {
                                    SwipeToRemove(enabled = !managing && !desktop,
                                        label = "删除这部作品的 ${chapters.size} 话下载", onRemove = { removeWithUndo(chapters) }) {
                                        DownloadBookRow(chapters, repository, expanded, managing,
                                            selected = chapters.all { it.id in selectedIds }, refreshingIds = refreshingIds,
                                            onRead = {
                                                val resume = historyById[mangaId]
                                                val target = chapters.firstOrNull { it.chapter.id == resume?.chapterId }
                                                    ?: chapters.firstOrNull { it.status == DownloadStatus.COMPLETED }
                                                    ?: chapters.firstOrNull { it.completedPages > 0 } ?: chapters.first()
                                                onRead(target)
                                            },
                                            onSelect = { toggleSelection(chapters) },
                                            onManage = { if (managing) toggleSelection(chapters) else { managing = true; selectedIds = chapters.map(DownloadEntry::id).toSet() } },
                                            onExpand = { expandedBooks = if (desktopSplit) listOf(mangaId)
                                                else if (expanded) expandedBooks - mangaId else expandedBooks + mangaId },
                                            onPause = { store.pause(it.id) }, onRetry = onRetry,
                                            onDetails = onDetails?.let { open -> { open(chapters.first().manga) } },
                                            onRemove = { pendingDeletion = chapters }, inspectMode = desktopSplit)
                                    }
                                }
                            }
                            if (expanded && !desktopSplit) items(chapters.chunked(downloadChapterColumns),
                                key = { "chapters:" + it.first().id }, contentType = { "download-chapter-grid" }) { row ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    row.forEach { entry ->
                                        Box(Modifier.weight(1f)) {
                                            SwipeToRemove(enabled = !managing && !desktop, label = "删除 ${entry.chapter.title} 下载",
                                                onRemove = { removeWithUndo(listOf(entry)) }) {
                                                DownloadChapterRow(entry, managing, entry.id in selectedIds, refreshingIds,
                                                    onRead = { onRead(entry) }, onSelect = { toggleSelection(listOf(entry)) },
                                                    onManage = { if (managing) toggleSelection(listOf(entry)) else {
                                                        managing = true; selectedIds = setOf(entry.id) } },
                                                    onPause = { store.pause(it.id) }, onRetry = onRetry,
                                                    onRemove = { pendingDeletion = listOf(entry) })
                                            }
                                        }
                                    }
                                    repeat(downloadChapterColumns - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                            item(key = "space:$mangaId", contentType = "download-separator") {
                                HorizontalDivider(Modifier.padding(start = 60.dp, top = 2.dp, bottom = 2.dp),
                                    color = foreground.copy(alpha = .05f))
                            }
                        }
                    }
                }
            }
            if (desktop) DesktopVerticalScrollbar(when (filter) {
                DownloadFilter.ALL -> allScroll
                DownloadFilter.ACTIVE -> activeScroll
                DownloadFilter.SAVED -> savedScroll
            }, Modifier.align(Alignment.CenterEnd).padding(top = contentTopPadding, bottom = bottomInset))
            }
            if (desktopSplit) {
                DesktopDownloadChapterPane(
                    entries = inspectedChapters, managing = managing, selectedIds = selectedIds,
                    refreshingIds = refreshingIds, resumeChapterId = inspectedBook?.let { historyById[it]?.chapterId },
                    topPadding = contentTopPadding,
                    bottomPadding = 32.dp + bottomInset,
                    modifier = Modifier.weight(.54f).fillMaxSize(),
                    onRead = onRead, onSelect = { toggleSelection(listOf(it)) },
                    onManage = { managing = true; selectedIds = setOf(it.id) },
                    onRemove = { pendingDeletion = listOf(it) },
                    onPause = { store.pause(it.id) }, onRetry = onRetry,
                )
            }
            }
        }
        if (desktop) {
            val stackedTools = maxWidth < 840.dp * density.fontScale.coerceAtLeast(1f)
            DesktopManagementToolbar("下载", Modifier.align(Alignment.TopCenter)
                .onSizeChanged { headerHeightPx = it.height },
                navigation = navigation, minimumToolsWidth = 840.dp) {
                MotionTabs(filter.ordinal, DownloadFilter.entries.map { it.label }, { index ->
                    filter = DownloadFilter.entries[index]; selectedIds = emptySet()
                }, modifier = if (stackedTools) Modifier.width(360.dp * density.fontScale.coerceAtLeast(1f))
                    else Modifier.widthIn(max = 420.dp).weight(1f, fill = false),
                    treatment = MotionTabTreatment.Soft, controlHeight = 36.dp)
                if (!stackedTools) Spacer(Modifier.weight(1f))
                if (!managing) TextButton(enabled = manageableEntries.isNotEmpty(),
                    onClick = { managing = true; selectedIds = emptySet() }) { Text("选择") }
                queueActions()
            }
        } else Column(Modifier.align(Alignment.TopCenter).onSizeChanged { headerHeightPx = it.height }
            .windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Top))
            .padding(horizontal = 8.dp, vertical = 2.dp)) {
            PageTitleHeader("下载", backdrop, navigation,
                leadingWidth = filterWidth,
                leading = {
                    Spacer(Modifier.width(filterWidth).height(morphGlassTabHeight()))
                }, actions = {
                toolbar {
                    queueActions()
                }
            })

        }

        if (!desktop) MorphGlassTabSelector(backdrop, filterChoices, filter.ordinal, filterWidth,
            maximumHeight = maxHeight,
            onToggle = { filterMenuExpanded = !filterMenuExpanded; showQueueActions = false },
            expanded = filterMenuExpanded, onDismiss = { filterMenuExpanded = false },
            onSelect = { index ->
                filterMenuExpanded = false
                filter = DownloadFilter.entries[index]
                selectedIds = emptySet()
            })
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 12.dp))
    }

    pendingDeletion?.let { targets ->
        AlertDialog(onDismissRequest = { pendingDeletion = null }, shape = RoundedCornerShape(22.dp),
            containerColor = colors.surface,
            title = { Text("删除 ${targets.size} 话下载？", style = MaterialTheme.typography.titleMedium) },
            text = {
                val titles = targets.map { it.manga.title }.distinct()
                Text("${titles.take(3).joinToString("、")}${if (titles.size > 3) "等 ${titles.size} 部作品" else ""}\n" +
                    "${targets.size} 话 · ${downloadBytes(targets.sumOf { it.downloadedBytes })}\n阅读记录和收藏保留。",
                    style = MaterialTheme.typography.bodyMedium, color = secondary)
            },
            confirmButton = {
                DownloadTextAction(onClick = {
                    pendingDeletion = null
                    managing = false
                    selectedIds = emptySet()
                    removeWithUndo(targets)
                }) { Text("删除", color = colors.error) }
            }, dismissButton = { DownloadTextAction(onClick = { pendingDeletion = null }) { Text("取消", color = secondary) } })
    }

    MotionDialog(visible = showImportSources, onDismissRequest = { showImportSources = false }) {
        LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), cornerRadius = 24.dp,
            role = GlassSurfaceRole.PANEL, elevation = 0.dp) {
            var showFormats by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().heightIn(max = LocalMangaWindow.current.height)
                .verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("导入漫画", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    MotionIconButton(onClick = { showImportSources = false }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Close, "取消导入", Modifier.size(20.dp), tint = secondary)
                    }
                }
                LocalImportChoice(Icons.Outlined.Inventory2, "压缩包", "CBZ · ZIP") {
                    showImportSources = false
                    archivePicker.launch(arrayOf(
                        "application/zip", "application/x-zip-compressed", "application/x-cbz",
                        "application/vnd.comicbook+zip", "application/octet-stream",
                    ))
                }
                LocalImportChoice(Icons.Outlined.Collections, "图片", "多选") {
                    showImportSources = false
                    imagePicker.launch(arrayOf("image/*"))
                }
                LocalImportChoice(Icons.Outlined.FolderOpen, "文件夹", "图片目录") {
                    showImportSources = false
                    folderPicker.launch(null)
                }
                TextButton(onClick = { showFormats = !showFormats }) {
                    Icon(Icons.Outlined.Info, null, Modifier.size(16.dp), tint = secondary)
                    Spacer(Modifier.width(6.dp))
                    Text("格式说明", style = MaterialTheme.typography.labelMedium, color = secondary)
                }
                if (showFormats) Text(
                    "按文件名自然排序。多张图片或文件夹会合成一个章节；导入后保留原文件。",
                    style = MaterialTheme.typography.bodySmall, color = secondary)
            }
        }
    }

    LocalImportDetailsDialog(
        pending = pendingImport,
        busy = importBusy,
        error = importError,
        backdrop = backdrop,
        onDismiss = { if (!importBusy) {
            pendingImport?.source?.documents()?.let(::releasePickedDocuments)
            pendingImport = null
            importError = null
        } },
        onConfirm = { pending, mangaTitle, chapterTitle -> importLocal(pending, mangaTitle, chapterTitle) },
    )

}

@Composable
private fun LocalImportChoice(
    icon: ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
        .motionPress(interaction, pressedScale = .985f).heightIn(min = 52.dp).padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = colors.onSurfaceVariant)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
        Text(detail, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp),
            tint = colors.onSurfaceVariant.copy(alpha = .65f))
    }
}


/** A selected work's real chapters stay beside the queue on wide desktop windows. */
@Composable
private fun DesktopDownloadChapterPane(
    entries: List<DownloadEntry>,
    managing: Boolean,
    selectedIds: Set<String>,
    refreshingIds: Set<String>,
    resumeChapterId: String?,
    topPadding: Dp,
    bottomPadding: Dp,
    modifier: Modifier,
    onRead: (DownloadEntry) -> Unit,
    onSelect: (DownloadEntry) -> Unit,
    onManage: (DownloadEntry) -> Unit,
    onRemove: (DownloadEntry) -> Unit,
    onPause: (DownloadEntry) -> Unit,
    onRetry: (DownloadEntry) -> Unit,
) {
    val scroll = rememberLazyListState()
    val workId = entries.firstOrNull()?.manga?.id
    LaunchedEffect(workId) { scroll.scrollToItem(0) }
    val colors = MaterialTheme.colorScheme
    Column(modifier.padding(start = 16.dp, end = 20.dp, top = topPadding)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entries.firstOrNull()?.manga?.title.orEmpty(),
                    fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE), style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${entries.size} 话 · ${downloadBytes(entries.sumOf { it.downloadedBytes })}",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            if (entries.isNotEmpty() && !managing) TextButton(onClick = {
                val target = entries.firstOrNull { it.chapter.id == resumeChapterId }
                    ?: entries.firstOrNull { it.status == DownloadStatus.COMPLETED }
                    ?: entries.firstOrNull { it.completedPages > 0 } ?: entries.first()
                onRead(target)
            }) { Text("阅读") }
        }
        HorizontalDivider(color = colors.onSurface.copy(alpha = .06f))
        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize(), state = scroll,
            contentPadding = PaddingValues(top = 10.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(entries, key = DownloadEntry::id, contentType = { "desktop-download-chapter" }) { entry ->
                DownloadChapterRow(entry, managing, entry.id in selectedIds, refreshingIds,
                    onRead = { onRead(entry) }, onSelect = { onSelect(entry) },
                    onManage = { onManage(entry) }, onPause = onPause, onRetry = onRetry,
                    onRemove = { onRemove(entry) })
            }
        }
        DesktopVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd))
        }
    }
}


@Composable
private fun LocalImportDetailsDialog(
    pending: PendingLocalImport?,
    busy: Boolean,
    error: String?,
    backdrop: HazeState,
    onDismiss: () -> Unit,
    onConfirm: (PendingLocalImport, String, String) -> Unit,
) {
    var retained by remember { mutableStateOf(pending) }
    SideEffect { if (pending != null) retained = pending }
    val displayed = pending ?: retained ?: return
    var mangaTitle by remember(displayed.source) { mutableStateOf(displayed.mangaTitle) }
    var chapterTitle by remember(displayed.source) { mutableStateOf(displayed.chapterTitle) }
    var showStorageNote by remember(displayed.source) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val sourceIcon = when (displayed.source) {
        is LocalImportSource.Archive -> Icons.Outlined.Inventory2
        is LocalImportSource.Images -> Icons.Outlined.Collections
        is LocalImportSource.Folder -> Icons.Outlined.FolderOpen
    }
    val sourceLabel = when (displayed.source) {
        is LocalImportSource.Archive -> "CBZ / ZIP"
        is LocalImportSource.Images -> "${displayed.source.uris.size} 张图片"
        is LocalImportSource.Folder -> "图片文件夹"
    }
    fun submit() {
        if (!busy && pending != null && mangaTitle.isNotBlank() && chapterTitle.isNotBlank()) {
            focus.clearFocus(force = true)
            onConfirm(displayed, mangaTitle, chapterTitle)
        }
    }
    MotionDialog(visible = pending != null, onDismissRequest = { if (!busy) onDismiss() }) {
        LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), cornerRadius = 24.dp,
            role = GlassSurfaceRole.PANEL, elevation = 0.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = LocalMangaWindow.current.height)
                .verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("导入漫画", style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(sourceIcon, null, Modifier.size(15.dp), tint = colors.onSurfaceVariant)
                            Text(sourceLabel, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                    MotionIconButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Close, "取消导入", Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                    }
                }
                LocalImportNameField("漫画", mangaTitle, !busy, ImeAction.Next,
                    onChange = { mangaTitle = it.take(120) },
                    onAction = { focus.moveFocus(FocusDirection.Next) })
                LocalImportNameField("章节", chapterTitle, !busy, ImeAction.Done,
                    onChange = { chapterTitle = it.take(120) }, onAction = ::submit)
                if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = colors.error)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !busy, onClick = { showStorageNote = !showStorageNote }) {
                        Icon(Icons.Outlined.Info, "导入说明", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    MotionButton(enabled = !busy && mangaTitle.isNotBlank() && chapterTitle.isNotBlank(),
                        onClick = ::submit, shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.onSurface,
                            contentColor = colors.surface,
                            disabledContainerColor = colors.onSurface.copy(alpha = .10f),
                            disabledContentColor = colors.onSurfaceVariant)) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp, color = colors.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (busy) "正在导入" else if (error != null) "重试" else "导入")
                    }
                }
                if (showStorageNote) Text("保存为离线章节，原文件保留。移动或删除原文件不影响已导入内容。",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** Same quiet input surface as the existing source importer, without an outlined form card. */
@Composable
private fun LocalImportNameField(
    label: String,
    value: String,
    enabled: Boolean,
    action: ImeAction,
    onChange: (String) -> Unit,
    onAction: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        BasicTextField(value, onValueChange = onChange, enabled = enabled, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.onSurface),
            keyboardOptions = KeyboardOptions(imeAction = action),
            keyboardActions = KeyboardActions(onNext = { onAction() }, onDone = { onAction() }),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(colors.onSurface.copy(alpha = .045f))
                .semantics { contentDescription = "${label}名称" },
            decorationBox = { input ->
                Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart) { input() }
            })
    }
}


@Composable
private fun DownloadTextAction(
    onClick: () -> Unit,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    TextButton(onClick = onClick, enabled = enabled, contentPadding = contentPadding, interactionSource = interaction) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center, content = content)
    }
}

internal fun downloadBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> formatDecimal(bytes / (1024.0 * 1024 * 1024), 1) + " GB"
    bytes >= 1024L * 1024 -> formatDecimal(bytes / (1024.0 * 1024), 1) + " MB"
    bytes >= 1024 -> formatDecimal(bytes / 1024.0, 0) + " KB"
    else -> "$bytes B"
}
