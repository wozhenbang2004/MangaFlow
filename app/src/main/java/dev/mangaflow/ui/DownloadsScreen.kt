package dev.mangaflow.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import coil3.compose.AsyncImage
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.DownloadStore
import dev.mangaflow.storage.DownloadStore.Companion.LOCAL_SOURCE_URL
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionSwap
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.rememberDelayedBusy
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class DownloadFilter(val label: String) { ALL("全部"), ACTIVE("未完成"), SAVED("已下载") }

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
    onRead: (DownloadEntry) -> Unit,
    onRetry: (DownloadEntry) -> Unit,
    backdrop: HazeState,
    navigation: @Composable () -> Unit,
    toolbar: @Composable (@Composable () -> Unit) -> Unit,
    refreshingIds: Set<String> = emptySet(),
) {
    val context = LocalContext.current
    val entries by store.entries.collectAsStateWithLifecycle()
    val loading by store.loading.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var filter by rememberSaveable { mutableStateOf(DownloadFilter.ALL) }
    var removingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var clearCompleted by remember { mutableStateOf(false) }
    var bulkBusy by remember { mutableStateOf(false) }
    var showImportSources by remember { mutableStateOf(false) }
    var showQueueActions by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<PendingLocalImport?>(null) }
    var importBusy by remember { mutableStateOf(false) }
    val allScroll = rememberLazyListState()
    val activeScroll = rememberLazyListState()
    val savedScroll = rememberLazyListState()
    val showLoading = rememberDelayedBusy(loading)
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    var headerHeightPx by remember { mutableIntStateOf(with(density) { 88.dp.toPx().toInt() }) }
    val contentTopPadding = with(density) { headerHeightPx.toDp() } + 4.dp
    val foreground = colors.onSurface
    val secondary = colors.onSurfaceVariant
    val activeEntries = entries.filter { it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.WAITING) }
    val resumableEntries = entries.filter {
        it.status in setOf(DownloadStatus.PAUSED, DownloadStatus.FAILED) && it.manga.sourceUrl != LOCAL_SOURCE_URL
    }
    val completedEntries = entries.filter { it.status == DownloadStatus.COMPLETED }
    val usedBytes = entries.sumOf { it.downloadedBytes }
    val availableBytes = remember(entries, loading) { store.availableStorageBytes() }

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

    Box(Modifier.fillMaxSize().background(if (LocalWallpaperActive.current) Color.Transparent else MaterialTheme.colorScheme.background)) {
        // Only the original scrolling lists feed the glass; controls remain foreground siblings.
        Box(Modifier.fillMaxSize().hazeSource(backdrop)) {
            // The filter owns the transition; progress updates keep the existing list in place.
            MotionTabContent(filter.ordinal, Modifier.fillMaxSize()) { index ->
                val pageFilter = DownloadFilter.entries[index]
                val visible = when (pageFilter) {
                    DownloadFilter.ALL -> entries
                    DownloadFilter.ACTIVE -> entries.filter { it.status != DownloadStatus.COMPLETED }
                    DownloadFilter.SAVED -> entries.filter { it.status == DownloadStatus.COMPLETED }
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
                        items(visible, key = DownloadEntry::id) { entry ->
                            DownloadSwipeRow(
                                entry = entry,
                                refreshing = entry.id in refreshingIds,
                                onRead = { onRead(entry) },
                                onPause = { store.pause(entry.id) },
                                onRetry = { onRetry(entry) },
                                onRemove = {
                                    if (entry.id !in removingIds) {
                                        removingIds = removingIds + entry.id
                                        scope.launch {
                                            try {
                                                store.remove(entry.id)
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (failure: Exception) {
                                                snackbar.showSnackbar(failure.message ?: "删除未完成，请重试")
                                            } finally {
                                                removingIds = removingIds - entry.id
                                            }
                                        }
                                    }
                                },
                            )
                            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = colors.outlineVariant.copy(alpha = .45f))
                        }
                    }
                }
            }
        }
        SegmentedGlassHeader(backdrop, navigation,
            modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 8.dp, vertical = 2.dp)
                .onSizeChanged { headerHeightPx = it.height },
        ) {
            Column(Modifier.fillMaxWidth().pointerInput(Unit) {}) {
                toolbar {
                    MotionIconButton(enabled = !importBusy, modifier = Modifier.size(44.dp),
                        onClick = { showImportSources = true }) {
                        if (importBusy) CircularProgressIndicator(Modifier.size(16.dp)
                            .semantics { contentDescription = "正在导入" }, strokeWidth = 1.5.dp, color = secondary)
                        else Icon(Icons.Outlined.Add, "导入漫画", Modifier.size(21.dp), tint = foreground)
                    }
                    Box {
                        MotionIconButton(onClick = { showQueueActions = true },
                            enabled = !bulkBusy, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Outlined.MoreHoriz, "下载详情与队列操作", Modifier.size(21.dp), tint = secondary)
                        }
                        DropdownMenu(expanded = showQueueActions,
                            onDismissRequest = { showQueueActions = false },
                            modifier = Modifier.widthIn(min = 164.dp, max = 240.dp),
                            shape = RoundedCornerShape(18.dp), containerColor = colors.surfaceContainerLow,
                            tonalElevation = 0.dp, shadowElevation = 4.dp) {
                            DropdownMenuItem(enabled = false, onClick = {}, text = {
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(if (loading) "正在恢复下载" else "${completedEntries.size} / ${entries.size} 章已下载",
                                        color = foreground, style = MaterialTheme.typography.labelMedium)
                                    Text("${downloadBytes(usedBytes)} · ${downloadBytes(availableBytes)} 可用",
                                        color = secondary, style = MaterialTheme.typography.labelSmall)
                                }
                            })
                            if (activeEntries.isNotEmpty()) DropdownMenuItem(
                                text = { Text("全部暂停") }, leadingIcon = { Icon(Icons.Outlined.Pause, null) },
                                enabled = !bulkBusy,
                                onClick = { showQueueActions = false; activeEntries.forEach { store.pause(it.id) } })
                            if (resumableEntries.isNotEmpty()) DropdownMenuItem(
                                text = { Text("继续未完成") }, leadingIcon = { Icon(Icons.Outlined.PlayArrow, null) },
                                enabled = !bulkBusy,
                                onClick = { showQueueActions = false; resumableEntries.forEach(onRetry) })
                            if (completedEntries.isNotEmpty()) DropdownMenuItem(
                                text = { Text("清理已下载") }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                                enabled = !bulkBusy,
                                onClick = { showQueueActions = false; clearCompleted = true })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    MotionTabs(
                        selected = filter.ordinal, labels = DownloadFilter.entries.map { it.label },
                        onSelect = { filter = DownloadFilter.entries[it] }, modifier = Modifier.weight(1f),
                        controlHeight = 44.dp, treatment = MotionTabTreatment.Underline,
                        foreground = foreground, muted = secondary, indicator = foreground.copy(alpha = .78f),
                        fontSize = MaterialTheme.typography.labelLarge.fontSize,
                        selectedFontWeight = FontWeight.Medium, unselectedFontWeight = FontWeight.Normal,
                    )
                    Spacer(Modifier.width(8.dp))
                    Row(Modifier.semantics(mergeDescendants = true) {
                        contentDescription = if (loading) "正在恢复下载" else "已下载 ${completedEntries.size} 章，共 ${entries.size} 章"
                    }, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (loading) CircularProgressIndicator(Modifier.size(12.dp), color = secondary, strokeWidth = 1.dp)
                        else Icon(if (entries.isNotEmpty() && completedEntries.size == entries.size) Icons.Outlined.Check
                            else Icons.Outlined.Download, null, Modifier.size(14.dp), tint = secondary)
                        Text("${completedEntries.size}/${entries.size}", color = secondary, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 12.dp))
    }

    if (clearCompleted) {
        AlertDialog(
            onDismissRequest = { if (!bulkBusy) clearCompleted = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("清理全部已下载章节？", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "${completedEntries.size} 章 · ${downloadBytes(completedEntries.sumOf { it.downloadedBytes })}\n阅读记录、收藏和未完成任务保留。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondary,
                )
            },
            confirmButton = {
                DownloadTextAction(enabled = !bulkBusy, onClick = {
                    val ids = completedEntries.map(DownloadEntry::id)
                    bulkBusy = true
                    scope.launch {
                        var failed = 0
                        ids.forEach { id ->
                            try { store.remove(id) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { failed++ }
                        }
                        bulkBusy = false
                        clearCompleted = false
                        if (failed > 0) snackbar.showSnackbar("$failed 个章节未能清理，请重试")
                    }
                }) {
                    Text(if (bulkBusy) "正在清理" else "清理", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                DownloadTextAction(enabled = !bulkBusy, onClick = { clearCompleted = false }) {
                    Text("取消", color = foreground)
                }
            },
        )
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
private fun DownloadSwipeRow(
    entry: DownloadEntry,
    refreshing: Boolean,
    onRead: () -> Unit,
    onPause: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    SwipeToRemove(onRemove = onRemove, label = "删除下载") {
        DownloadRow(entry, refreshing, onRead, onPause, onRetry)
    }
}

@Composable
private fun DownloadRow(entry: DownloadEntry, refreshing: Boolean, onRead: () -> Unit, onPause: () -> Unit, onRetry: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val foreground = colors.onSurface
    val secondary = colors.onSurfaceVariant
    val finished = entry.status == DownloadStatus.COMPLETED
    val localImport = entry.manga.sourceUrl == LOCAL_SOURCE_URL
    val inProgress = entry.status in setOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.WAITING)
    val interaction = remember { MutableInteractionSource() }
    val stateLabel = when (entry.status) {
        DownloadStatus.QUEUED -> "排队中"
        DownloadStatus.RUNNING -> "下载中"
        DownloadStatus.WAITING -> "等待恢复"
        DownloadStatus.PAUSED -> "已暂停"
        DownloadStatus.COMPLETED -> if (localImport) "本地" else "已下载"
        DownloadStatus.FAILED -> if (localImport) "文件损坏" else "未完成"
    }
    Box {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(
            interactionSource = interaction, indication = null, role = Role.Button,
            onClickLabel = "阅读 ${entry.chapter.title}", onClick = onRead,
        )) {
            Row(Modifier.fillMaxWidth().motionPress(interaction, pressedScale = .99f).padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = entry.manga.coverUrl,
                    contentDescription = null,
                    modifier = Modifier.size(width = 48.dp, height = 68.dp).clip(RoundedCornerShape(8.dp)).background(colors.surfaceVariant),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.manga.title, style = MaterialTheme.typography.titleSmall, color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(entry.chapter.title, style = MaterialTheme.typography.bodySmall, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Reserve this slot when the transfer completes so the row never jumps.
                    Box(Modifier.fillMaxWidth().height(7.dp).drawBehind {
                        if (!finished) {
                            val y = size.height / 2f
                            val fraction = if (entry.totalPages > 0)
                                (entry.completedPages.toFloat() / entry.totalPages).coerceIn(0f, 1f) else 0f
                            drawLine(secondary.copy(alpha = .12f), Offset(0f, y), Offset(size.width, y), 2.dp.toPx(), StrokeCap.Round)
                            if (fraction > 0f) drawLine(secondary.copy(alpha = .62f), Offset(0f, y), Offset(size.width * fraction, y), 2.dp.toPx(), StrokeCap.Round)
                        }
                    })
                    Text(
                        if (finished) "$stateLabel · ${entry.totalPages} 页 · ${downloadBytes(entry.downloadedBytes)}"
                        else "$stateLabel · ${entry.completedPages}/${entry.totalPages} 页",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (entry.status == DownloadStatus.FAILED) colors.error else secondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(4.dp))
                MotionIconButton(onClick = if (inProgress && !entry.needsSourceRefresh) onPause else onRetry,
                    enabled = !finished && !refreshing && !(localImport && entry.status == DownloadStatus.FAILED),
                    modifier = Modifier.size(44.dp)) {
                    MotionSwap(Triple(entry.status, entry.needsSourceRefresh, refreshing), Modifier.size(20.dp)) { (status, needsRefresh, busy) ->
                        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp, color = secondary)
                        else {
                            val active = status in setOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.WAITING)
                            Icon(when {
                                status == DownloadStatus.COMPLETED -> Icons.Outlined.Check
                                needsRefresh || status == DownloadStatus.FAILED -> Icons.Outlined.Refresh
                                active -> Icons.Outlined.Pause
                                else -> Icons.Outlined.PlayArrow
                            }, contentDescription = when {
                                status == DownloadStatus.COMPLETED -> "已下载"
                                needsRefresh -> "刷新图片地址并继续下载"
                                active -> "暂停下载"
                                status == DownloadStatus.FAILED && localImport -> "请删除后重新导入"
                                status == DownloadStatus.FAILED -> "重试下载"
                                else -> "继续下载"
                            }, modifier = Modifier.size(20.dp), tint = if (status == DownloadStatus.COMPLETED) secondary else foreground)
                        }
                    }
                }
            }
            if (entry.error.isNotBlank()) {
                Text(entry.error, Modifier.padding(start = 60.dp, end = 8.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.status == DownloadStatus.FAILED) colors.error else secondary)
            }
        }
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
        Row(Modifier.motionPress(interaction, enabled), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center, content = content)
    }
}

private fun downloadBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
