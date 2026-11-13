package dev.mangaflow.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.DownloadStore
import dev.mangaflow.storage.DownloadStore.Companion.LOCAL_SOURCE_URL
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.rememberDelayedBusy
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

private enum class DownloadFilter(val label: String) { ALL("全部"), ACTIVE("进行中"), SAVED("已下载") }

private sealed interface LocalImportSource {
    data class Archive(val uri: Uri) : LocalImportSource
    data class Images(val uris: List<Uri>) : LocalImportSource
    data class Folder(val uri: Uri) : LocalImportSource
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
    refreshingIds: Set<String> = emptySet(),
    history: List<ReadingProgress> = emptyList(),
    wallpaper: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val entries by store.entries.collectAsStateWithLifecycle()
    val loading by store.loading.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
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
    val filterWidth = morphGlassTabWidth(filterChoices)
    var pendingImport by remember { mutableStateOf<PendingLocalImport?>(null) }
    var importBusy by remember { mutableStateOf(false) }
    val allScroll = rememberLazyListState()
    val activeScroll = rememberLazyListState()
    val savedScroll = rememberLazyListState()
    val showLoading = rememberDelayedBusy(loading)
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    var headerHeightPx by remember { mutableIntStateOf(with(density) { 44.dp.toPx().toInt() }) }
    val contentTopPadding = with(density) { headerHeightPx.toDp() } + HeaderContentGap
    val foreground = colors.onSurface
    val secondary = colors.onSurfaceVariant
    val manageableEntries = entries.filterNot { it.id in removingIds }
    val selectedEntries = manageableEntries.filter { it.id in selectedIds }
    val motion = LocalMotionEnabled.current
    val activeEntries = manageableEntries.filter { it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.WAITING) }
    val resumableEntries = manageableEntries.filter {
        it.status in setOf(DownloadStatus.PAUSED, DownloadStatus.FAILED) && it.manga.sourceUrl != LOCAL_SOURCE_URL
    }
    val completedEntries = manageableEntries.filter { it.status == DownloadStatus.COMPLETED }
    val usedBytes = entries.sumOf { it.downloadedBytes }
    val availableBytes = remember(entries, loading) { store.availableStorageBytes() }

    BackHandler(enabled = managing) { managing = false; selectedIds = emptySet() }

    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val base = localDocumentName(context, it).substringBeforeLast('.').ifBlank { "本地漫画" }
            pendingImport = PendingLocalImport(LocalImportSource.Archive(it), base, "第 1 话")
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) pendingImport = PendingLocalImport(
            LocalImportSource.Images(uris), "本地漫画", "第 1 话",
        )
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            pendingImport = PendingLocalImport(
                LocalImportSource.Folder(it), localTreeName(it).ifBlank { "本地漫画" }, "第 1 话",
            )
        }
    }

    fun importLocal(pending: PendingLocalImport, mangaTitle: String, chapterTitle: String) {
        if (importBusy) return
        importBusy = true
        scope.launch {
            try {
                val entry = when (val source = pending.source) {
                    is LocalImportSource.Archive -> store.importArchive(source.uri, mangaTitle, chapterTitle)
                    is LocalImportSource.Images -> store.importImages(source.uris, mangaTitle, chapterTitle)
                    is LocalImportSource.Folder -> store.importFolder(source.uri, mangaTitle, chapterTitle)
                }
                pendingImport = null
                filter = DownloadFilter.SAVED
                snackbar.showSnackbar("已导入 ${entry.manga.title} · ${entry.chapter.title}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                snackbar.showSnackbar(failure.message ?: "本地漫画导入失败")
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

    Box(Modifier.fillMaxSize().background(if (LocalWallpaperActive.current) Color.Transparent else MaterialTheme.colorScheme.background)) {
        // The captured scene contains the wallpaper and scrolling list, never the header itself.
        Box(Modifier.fillMaxSize().hazeSource(backdrop).background(MaterialTheme.colorScheme.background)) {
            wallpaper()
            // The filter owns the transition; progress updates keep the existing list in place.
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
                LazyColumn(
                    modifier = Modifier.fillMaxSize(), state = scroll,
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = contentTopPadding, bottom = 32.dp),
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
                                Text("在目录下载，或导入 CBZ、ZIP、图片与文件夹", style = MaterialTheme.typography.bodySmall, color = secondary)
                            }
                        }
                    } else {
                        visible.groupBy { it.manga.id }.forEach { (mangaId, workEntries) ->
                            val chapters = workEntries.groupBy { it.chapter.group }.values.flatMap { group -> group.sortedBy { it.chapter.index } }
                            val expanded = mangaId in expandedBooks && chapters.size > 1
                            item(key = "work:$mangaId", contentType = "download-work") {
                                val placement = Modifier.animateItem(
                                    fadeInSpec = if (motion) tween(MangaMotion.Content) else null,
                                    placementSpec = if (motion) tween(MangaMotion.Content) else null,
                                    fadeOutSpec = if (motion) tween(MangaMotion.Quick) else null)
                                Box(placement) {
                                    SwipeToRemove(enabled = !managing,
                                        label = "删除这部作品的 ${chapters.size} 话下载", onRemove = { removeWithUndo(chapters) }) {
                                        DownloadBookRow(chapters, repository, expanded, managing,
                                            selected = chapters.all { it.id in selectedIds }, refreshingIds = refreshingIds,
                                            onRead = {
                                                val resume = history.firstOrNull { it.manga.id == mangaId }
                                                val target = chapters.firstOrNull { it.chapter.id == resume?.chapterId }
                                                    ?: chapters.firstOrNull { it.status == DownloadStatus.COMPLETED }
                                                    ?: chapters.firstOrNull { it.completedPages > 0 } ?: chapters.first()
                                                onRead(target)
                                            },
                                            onSelect = { toggleSelection(chapters) },
                                            onManage = { if (managing) toggleSelection(chapters) else { managing = true; selectedIds = chapters.map(DownloadEntry::id).toSet() } },
                                            onExpand = { expandedBooks = if (expanded) expandedBooks - mangaId else expandedBooks + mangaId },
                                            onPause = { store.pause(it.id) }, onRetry = onRetry)
                                    }
                                }
                            }
                            if (expanded) items(chapters, key = { "chapter:${it.id}" }, contentType = { "download-chapter" }) { entry ->
                                Box(Modifier.animateItem(
                                    fadeInSpec = if (motion) tween(MangaMotion.Content) else null,
                                    placementSpec = if (motion) tween(MangaMotion.Content) else null,
                                    fadeOutSpec = if (motion) tween(MangaMotion.Quick) else null)) {
                                    SwipeToRemove(enabled = !managing, label = "删除 ${entry.chapter.title} 下载",
                                        onRemove = { removeWithUndo(listOf(entry)) }) {
                                        DownloadChapterRow(entry, managing, entry.id in selectedIds, refreshingIds,
                                            onRead = { onRead(entry) }, onSelect = { toggleSelection(listOf(entry)) },
                                            onManage = { if (managing) toggleSelection(listOf(entry)) else { managing = true; selectedIds = setOf(entry.id) } },
                                            onPause = { store.pause(it.id) }, onRetry = onRetry)
                                    }
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
        }
        Column(Modifier.align(Alignment.TopCenter).padding(horizontal = 8.dp, vertical = 2.dp)
            .onSizeChanged { headerHeightPx = it.height }) {
            PageTitleHeader("下载", backdrop, navigation,
                leading = {
                    Spacer(Modifier.width(filterWidth).height(morphGlassTabHeight()))
                }, actions = {
                toolbar {
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
                                onDismiss = { showQueueActions = false }) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("存储空间", style = MaterialTheme.typography.bodySmall, color = secondary)
                                    Text(downloadBytes(usedBytes), style = MaterialTheme.typography.titleMedium, color = foreground)
                                    Text("${downloadBytes(availableBytes)} 可用", style = MaterialTheme.typography.bodySmall, color = secondary)
                                }
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
                                    Spacer(Modifier.height(4.dp))
                                    CompactMenuAction(Icons.Outlined.DeleteOutline, "清理已下载", destructive = true) {
                                        showQueueActions = false; pendingDeletion = completedEntries
                                    }
                                }
                            }
                        }
                    }
                }
            })

        }
        MorphGlassTabSelector(backdrop, filterChoices, filter.ordinal, filterWidth,
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

    if (showImportSources) {
        AlertDialog(
            onDismissRequest = { showImportSources = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("导入本地漫画", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    LocalImportChoice(Icons.Outlined.Inventory2, "CBZ / ZIP", "按文件名自然排序") {
                        showImportSources = false
                        archivePicker.launch(arrayOf(
                            "application/zip", "application/x-zip-compressed", "application/x-cbz",
                            "application/vnd.comicbook+zip", "application/octet-stream",
                        ))
                    }
                    LocalImportChoice(Icons.Outlined.Collections, "选择多张图片", "一次组成一个章节") {
                        showImportSources = false
                        imagePicker.launch(arrayOf("image/*"))
                    }
                    LocalImportChoice(Icons.Outlined.FolderOpen, "选择图片文件夹", "递归读取，合并为一个章节") {
                        showImportSources = false
                        folderPicker.launch(null)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                DownloadTextAction(onClick = { showImportSources = false }) {
                    Text("取消", color = foreground)
                }
            },
        )
    }

    LocalImportDetailsDialog(
        pending = pendingImport,
        busy = importBusy,
        onDismiss = { if (!importBusy) pendingImport = null },
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
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(
            interactionSource = interaction,
            indication = null,
            role = Role.Button,
            onClick = onClick,
        ).motionPress(interaction, pressedScale = .985f).padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = colors.onSurface)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun LocalImportDetailsDialog(
    pending: PendingLocalImport?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (PendingLocalImport, String, String) -> Unit,
) {
    if (pending == null) return
    var mangaTitle by remember(pending.source) { mutableStateOf(pending.mangaTitle) }
    var chapterTitle by remember(pending.source) { mutableStateOf(pending.chapterTitle) }
    val colors = MaterialTheme.colorScheme
    val sourceLabel = when (pending.source) {
        is LocalImportSource.Archive -> "压缩包"
        is LocalImportSource.Images -> "${pending.source.uris.size} 张图片"
        is LocalImportSource.Folder -> "图片文件夹"
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = RoundedCornerShape(24.dp),
        containerColor = colors.surface,
        title = { Text("确认本地章节", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("来源 · $sourceLabel", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                OutlinedTextField(
                    value = mangaTitle,
                    onValueChange = { mangaTitle = it.take(120) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    singleLine = true,
                    label = { Text("漫画名称") },
                )
                OutlinedTextField(
                    value = chapterTitle,
                    onValueChange = { chapterTitle = it.take(120) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    singleLine = true,
                    label = { Text("章节名称") },
                )
                Text(
                    "图片会复制到 MangaFlow 私有目录，原文件移动或删除后仍可阅读。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            DownloadTextAction(
                enabled = !busy && mangaTitle.isNotBlank() && chapterTitle.isNotBlank(),
                onClick = { onConfirm(pending, mangaTitle, chapterTitle) },
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp, color = colors.onSurface)
                    Spacer(Modifier.width(7.dp))
                }
                Text(if (busy) "正在导入" else "导入", color = colors.onSurface)
            }
        },
        dismissButton = {
            DownloadTextAction(enabled = !busy, onClick = onDismiss) {
                Text("取消", color = colors.onSurfaceVariant)
            }
        },
    )
}

private fun localDocumentName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull().orEmpty().ifBlank { Uri.decode(uri.lastPathSegment.orEmpty()).ifBlank { "本地漫画" } }

private fun localTreeName(uri: Uri): String = runCatching {
    Uri.decode(DocumentsContract.getTreeDocumentId(uri)).substringAfterLast(':').substringAfterLast('/')
}.getOrDefault("")


@Composable
private fun DownloadTextAction(
    onClick: () -> Unit,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    TextButton(onClick = onClick, enabled = enabled, contentPadding = contentPadding, interactionSource = interaction) {
        Row(Modifier.motionPress(interaction, enabled), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center, content = content)
    }
}

internal fun downloadBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

