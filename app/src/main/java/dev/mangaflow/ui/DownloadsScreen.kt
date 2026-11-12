package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.DownloadStore
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class DownloadFilter(val label: String) { ALL("全部"), ACTIVE("未完成"), SAVED("已下载") }

@Composable
fun DownloadsScreen(store: DownloadStore, onRead: (DownloadEntry) -> Unit) {
    val entries by store.entries.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var filter by remember { mutableStateOf(DownloadFilter.ALL) }
    var remove by remember { mutableStateOf<DownloadEntry?>(null) }
    var removing by remember { mutableStateOf(false) }
    val visible = when (filter) {
        DownloadFilter.ALL -> entries
        DownloadFilter.ACTIVE -> entries.filter { it.status != DownloadStatus.COMPLETED }
        DownloadFilter.SAVED -> entries.filter { it.status == DownloadStatus.COMPLETED }
    }
    val colors = MaterialTheme.colorScheme
    val foreground = colors.onSurface
    val secondary = colors.onSurfaceVariant

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 32.dp),
        ) {
            item {
                Text("下载", style = MaterialTheme.typography.headlineLarge, color = foreground, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (entries.isEmpty()) "把喜欢的故事带在身边" else "${entries.count { it.status == DownloadStatus.COMPLETED }} 章已下载 · ${downloadBytes(entries.sumOf { it.downloadedBytes })}",
                    style = MaterialTheme.typography.bodyMedium, color = secondary,
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DownloadFilter.entries.forEach { value ->
                        TextButton(onClick = { filter = value }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(value.label, color = if (value == filter) foreground else secondary, fontWeight = if (value == filter) FontWeight.SemiBold else FontWeight.Normal)
                                Spacer(Modifier.height(7.dp))
                                Box(Modifier.width(16.dp).height(2.dp).clip(CircleShape).background(if (value == filter) foreground else Color.Transparent))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            if (visible.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 72.dp, bottom = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(30.dp), tint = secondary)
                        }
                        Spacer(Modifier.height(18.dp))
                        Text(
                            when (filter) {
                                DownloadFilter.ALL -> "暂时没有下载"
                                DownloadFilter.ACTIVE -> "没有未完成的下载"
                                DownloadFilter.SAVED -> "还没有下载完成的章节"
                            },
                            style = MaterialTheme.typography.titleMedium, color = foreground,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("在漫画目录选择章节，下载后离线阅读", style = MaterialTheme.typography.bodySmall, color = secondary)
                    }
                }
            } else {
                items(visible, key = DownloadEntry::id) { entry ->
                    DownloadRow(entry, onRead = { onRead(entry) }, onPause = { store.pause(entry.id) }, onRetry = { store.retry(entry.id) }, onRemove = { remove = entry })
                    HorizontalDivider(Modifier.padding(vertical = 18.dp), color = colors.outlineVariant)
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 12.dp))
    }

    remove?.let { entry ->
        AlertDialog(
            onDismissRequest = { if (!removing) remove = null },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(if (entry.completedPages > 0) "删除本地章节？" else "移除下载任务？", style = MaterialTheme.typography.titleLarge) },
            text = { Text("${entry.manga.title}\n${entry.chapter.title}", style = MaterialTheme.typography.bodyMedium, color = secondary) },
            confirmButton = {
                TextButton(enabled = !removing, onClick = {
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
            dismissButton = { TextButton(enabled = !removing, onClick = { remove = null }) { Text("取消", color = foreground) } },
        )
    }
}

@Composable
private fun DownloadRow(entry: DownloadEntry, onRead: () -> Unit, onPause: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val foreground = colors.onSurface
    val secondary = colors.onSurfaceVariant
    val finished = entry.status == DownloadStatus.COMPLETED
    val inProgress = entry.status in setOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.WAITING)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            AsyncImage(
                model = entry.manga.coverUrl,
                contentDescription = null,
                modifier = Modifier.size(width = 54.dp, height = 76.dp).clip(RoundedCornerShape(10.dp)).background(colors.surfaceVariant),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).padding(top = 1.dp)) {
                Text(entry.manga.title, style = MaterialTheme.typography.titleSmall, color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Text(entry.chapter.title, style = MaterialTheme.typography.bodySmall, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (finished) {
                        Icon(Icons.Outlined.Check, contentDescription = null, tint = secondary, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(
                        when (entry.status) {
                            DownloadStatus.QUEUED -> "排队中"
                            DownloadStatus.RUNNING -> "正在下载"
                            DownloadStatus.WAITING -> "等待恢复"
                            DownloadStatus.PAUSED -> "已暂停"
                            DownloadStatus.COMPLETED -> "已下载"
                            DownloadStatus.FAILED -> "下载未完成"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (entry.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else secondary,
                    )
                }
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除 ${entry.chapter.title}", tint = secondary, modifier = Modifier.size(20.dp))
            }
        }
        if (!finished) {
            Spacer(Modifier.height(13.dp))
            LinearProgressIndicator(
                progress = { if (entry.totalPages > 0) (entry.completedPages.toFloat() / entry.totalPages).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                color = colors.primary, trackColor = colors.outlineVariant,
            )
        }
        if (entry.error.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(entry.error, style = MaterialTheme.typography.bodySmall, color = if (entry.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else secondary)
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${entry.completedPages} / ${entry.totalPages} 页 · ${downloadBytes(entry.downloadedBytes)}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = secondary)
            TextButton(onClick = if (finished) onRead else if (inProgress) onPause else onRetry, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                Icon(
                    if (inProgress) Icons.Outlined.Pause else if (entry.status == DownloadStatus.FAILED) Icons.Outlined.Refresh else Icons.Outlined.PlayArrow,
                    contentDescription = null, modifier = Modifier.size(17.dp), tint = foreground,
                )
                Spacer(Modifier.width(4.dp))
                Text(if (finished) "离线阅读" else if (inProgress) "暂停" else if (entry.status == DownloadStatus.FAILED) "重试" else "继续", style = MaterialTheme.typography.labelLarge, color = foreground)
            }
        }
    }
}

private fun downloadBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
