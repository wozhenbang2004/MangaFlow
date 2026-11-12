package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.DownloadStore
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionSwap
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.rememberDelayedBusy
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class DownloadFilter(val label: String) { ALL("全部"), ACTIVE("未完成"), SAVED("已下载") }

@Composable
fun DownloadsScreen(
    store: DownloadStore,
    onRead: (DownloadEntry) -> Unit,
    onRetry: (DownloadEntry) -> Unit,
    refreshingIds: Set<String> = emptySet(),
) {
    val entries by store.entries.collectAsStateWithLifecycle()
    val loading by store.loading.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var filter by rememberSaveable { mutableStateOf(DownloadFilter.ALL) }
    var remove by remember { mutableStateOf<DownloadEntry?>(null) }
    var removing by remember { mutableStateOf(false) }
    val allScroll = rememberLazyListState()
    val activeScroll = rememberLazyListState()
    val savedScroll = rememberLazyListState()
    val showLoading = rememberDelayedBusy(loading)
    val colors = MaterialTheme.colorScheme
    val foreground = colors.onSurface
    val secondary = colors.onSurfaceVariant

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp)) {
                Text("下载", style = MaterialTheme.typography.headlineLarge, color = foreground, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (entries.isEmpty()) "把喜欢的故事带在身边" else "${entries.count { it.status == DownloadStatus.COMPLETED }} 章已下载 · ${downloadBytes(entries.sumOf { it.downloadedBytes })}",
                    style = MaterialTheme.typography.bodyMedium, color = secondary,
                )
                Spacer(Modifier.height(20.dp))
                MotionTabs(
                    selected = filter.ordinal, labels = DownloadFilter.entries.map { it.label },
                    onSelect = { filter = DownloadFilter.entries[it] }, underline = true, compact = true,
                    foreground = foreground, muted = secondary, indicator = foreground,
                    fontSize = MaterialTheme.typography.labelLarge.fontSize,
                    selectedFontWeight = FontWeight.SemiBold, unselectedFontWeight = FontWeight.Normal,
                )
                Spacer(Modifier.height(12.dp))
            }
            // The filter owns the transition; progress updates keep the existing list in place.
            MotionTabContent(filter.ordinal, Modifier.weight(1f).fillMaxWidth()) { index ->
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
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
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
                                Text("在目录选择章节，下载时也能直接开始阅读", style = MaterialTheme.typography.bodySmall, color = secondary)
                            }
                        }
                    } else {
                        items(visible, key = DownloadEntry::id) { entry ->
                            DownloadRow(entry, refreshing = entry.id in refreshingIds, onRead = { onRead(entry) }, onPause = { store.pause(entry.id) }, onRetry = { onRetry(entry) }, onRemove = { remove = entry })
                            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = colors.outlineVariant.copy(alpha = .45f))
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 12.dp))
    }

    remove?.let { requested ->
        val entry = entries.firstOrNull { it.id == requested.id } ?: requested
        AlertDialog(
            onDismissRequest = { if (!removing) remove = null },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(if (entry.completedPages > 0) "删除本地章节？" else "移除下载任务？", style = MaterialTheme.typography.titleLarge) },
            text = { Text("${entry.manga.title}\n${entry.chapter.title}", style = MaterialTheme.typography.bodyMedium, color = secondary) },
            confirmButton = {
                DownloadTextAction(enabled = !removing, onClick = {
                    removing = true
                    scope.launch {
                        try {
                            store.remove(entry.id)
                            remove = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            remove = null
                            snackbar.showSnackbar(failure.message ?: "删除未完成，请重试")
                        } finally {
                            removing = false
                        }
                    }
                }) { Text(if (removing) "正在删除" else "删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { DownloadTextAction(enabled = !removing, onClick = { remove = null }) { Text("取消", color = foreground) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadRow(entry: DownloadEntry, refreshing: Boolean, onRead: () -> Unit, onPause: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val foreground = colors.onSurface
    val secondary = colors.onSurfaceVariant
    val finished = entry.status == DownloadStatus.COMPLETED
    val inProgress = entry.status in setOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.WAITING)
    val interaction = remember { MutableInteractionSource() }
    var actionsVisible by remember(entry.id) { mutableStateOf(false) }
    val stateLabel = when (entry.status) {
        DownloadStatus.QUEUED -> "排队中"
        DownloadStatus.RUNNING -> "下载中"
        DownloadStatus.WAITING -> "等待恢复"
        DownloadStatus.PAUSED -> "已暂停"
        DownloadStatus.COMPLETED -> "已下载"
        DownloadStatus.FAILED -> "未完成"
    }
    Box {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).combinedClickable(
            interactionSource = interaction, indication = null, role = Role.Button,
            onClickLabel = "阅读 ${entry.chapter.title}", onClick = onRead,
            onLongClickLabel = "管理下载", onLongClick = { actionsVisible = true },
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
                    enabled = !finished && !refreshing, modifier = Modifier.size(44.dp)) {
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
        RecordActionsMenu(actionsVisible, onDismiss = { actionsVisible = false },
            removeLabel = if (entry.completedPages > 0) "删除本地章节" else "移除下载任务",
            onRemove = onRemove)
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
