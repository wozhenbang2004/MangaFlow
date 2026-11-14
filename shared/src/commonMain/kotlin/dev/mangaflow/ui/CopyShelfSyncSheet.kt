package dev.mangaflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.account.CopyShelfSyncState
import dev.mangaflow.data.Manga
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionButton
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton

/** Reuse the retained Steam/network workspace: one scrolling comparison and one explicit commit. */
@Composable
internal fun CopyShelfSyncSheet(
    visible: Boolean,
    state: CopyShelfSyncState,
    backdrop: HazeState,
    onCheck: () -> Unit,
    onImport: () -> Unit,
    onUpload: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val removalIds = remember(state.toRemove) { state.toRemove.map { it.id }.toSet() }
    var selectedRemovals by remember(state.owner, visible, removalIds) { mutableStateOf(removalIds) }
    var showScope by remember(state.owner, visible) { mutableStateOf(false) }
    var showIncoming by remember(state.owner, visible) { mutableStateOf(false) }
    var showAdditions by remember(state.owner, visible) { mutableStateOf(false) }
    var showRemovals by remember(state.owner, visible) { mutableStateOf(true) }
    val canAct = state.owner != null && state.checked && !state.busy && state.error == null
    val canUpload = canAct && state.hasFullImport && (state.toAdd.isNotEmpty() || selectedRemovals.isNotEmpty())
    MotionDialog(visible, onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false), maximumWidth = 520.dp) {
        BoxWithConstraints(Modifier.padding(horizontal = 12.dp)) {
            // Checking/importing may reveal a longer comparison; keep the same retained viewport.
            val panelHeight = minOf(maxHeight, LocalMangaWindow.current.height * .88f, 440.dp)
            LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), cornerRadius = 22.dp,
                role = GlassSurfaceRole.PANEL, elevation = 0.dp) {
                Column(Modifier.fillMaxWidth().height(panelHeight).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.SwapHoriz, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        Text("拷贝收藏", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        MotionIconButton(onClick = { showScope = !showScope }, modifier = Modifier.size(44.dp),
                            tooltip = "同步范围") {
                            Icon(Icons.Outlined.Info, "同步范围", Modifier.size(17.dp), tint = colors.onSurfaceVariant)
                        }
                        if (state.owner != null && state.checked && state.error == null) MotionIconButton(onClick = onCheck, enabled = !state.busy,
                            modifier = Modifier.size(44.dp), tooltip = "重新检查收藏变化") {
                            Icon(Icons.Outlined.Refresh, "重新检查收藏变化", Modifier.size(19.dp))
                        }
                        MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp),
                            tooltip = if (state.busy) "收起同步窗口" else "关闭同步窗口") {
                            Icon(Icons.Outlined.Close, "收起同步窗口", Modifier.size(20.dp))
                        }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (showScope) item(key = "sync-scope") {
                            Text("只处理收藏。阅读进度在漫画详情中比较和采用；本机独有漫画、下载和每章位置都保留。",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        if (state.busy) item(key = "sync-busy") {
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                MotionBusyIndicator(Modifier.size(17.dp), strokeWidth = 1.6.dp)
                                Text(when {
                                    state.importing -> "正在导入…"
                                    state.uploading -> "正在提交到拷贝…"
                                    else -> "正在检查…"
                                }, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                        }
                        state.error?.let { error -> item(key = "sync-error") {
                            Text(error, style = MaterialTheme.typography.bodySmall, color = colors.error)
                        } }
                        state.message?.takeIf { it.isNotBlank() }?.let { message -> item(key = "sync-message") {
                            Text(message, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        } }
                        when {
                            state.owner == null -> item(key = "sync-login") {
                                Text("请先在拷贝账号页登录。", style = MaterialTheme.typography.bodyMedium)
                            }
                            !state.checked -> item(key = "sync-unchecked") {
                                Text("检查后查看收藏变化。",
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            !state.hasFullImport -> item(key = "sync-first-import") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("首次导入 · ${state.cloudFavorites.size} 本", style = MaterialTheme.typography.titleSmall)
                                    Text("仅导入收藏，保留本机书架与阅读记录。",
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                    Text("完成后可主动提交本机增删。",
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                }
                            }
                            else -> {
                                if (state.cloudAdded.isNotEmpty()) {
                                    item(key = "sync-incoming") {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            SyncGroupHeading(Icons.Outlined.ArrowDownward, "导入本机", state.cloudAdded.size.toString(), showIncoming,
                                                { showIncoming = !showIncoming }, Modifier.weight(1f))
                                            MotionTextButton(onClick = onImport, enabled = canAct) { Text("导入") }
                                        }
                                    }
                                    if (showIncoming) items(state.cloudAdded, key = { "incoming:${it.id}" }) { manga ->
                                        SyncBookTitle(manga)
                                    }
                                }
                                if (state.toAdd.isNotEmpty()) {
                                    item(key = "sync-additions") {
                                        SyncGroupHeading(Icons.Outlined.BookmarkAdd, "加入拷贝", state.toAdd.size.toString(), showAdditions,
                                            { showAdditions = !showAdditions })
                                    }
                                    if (showAdditions) items(state.toAdd, key = { "addition:${it.id}" }) { manga ->
                                        SyncBookTitle(manga)
                                    }
                                }
                                if (state.toRemove.isNotEmpty()) {
                                    item(key = "sync-removals") {
                                        SyncGroupHeading(Icons.Outlined.BookmarkRemove, "移出拷贝", "${selectedRemovals.size}/${state.toRemove.size}", showRemovals,
                                            { showRemovals = !showRemovals })
                                    }
                                    if (showRemovals) items(state.toRemove, key = { "removal:${it.id}" }) { manga ->
                                        val selected = manga.id in selectedRemovals
                                        MotionTextButton(onClick = {
                                            selectedRemovals = if (selected) selectedRemovals - manga.id else selectedRemovals + manga.id
                                        }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()
                                            .semantics { this.selected = selected },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                                            Icon(if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                                if (selected) "取消移出${manga.title}" else "选择移出${manga.title}",
                                                Modifier.size(17.dp), tint = if (selected) colors.error else colors.onSurfaceVariant)
                                            Text(manga.title, Modifier.weight(1f).padding(start = 10.dp),
                                                style = MaterialTheme.typography.bodyMedium, color = colors.onSurface,
                                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                                if (state.localOnlyCount > 0) item(key = "sync-local-only") {
                                    Row(Modifier.fillMaxWidth().semantics {
                                        contentDescription = "${state.localOnlyCount} 本仅保留在本机，不参与拷贝同步"
                                    }, verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(Icons.Outlined.Smartphone, null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                                        Text("仅本机 ${state.localOnlyCount}", style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant)
                                    }
                                }
                                if (state.cloudAdded.isEmpty() && state.toAdd.isEmpty() && state.toRemove.isEmpty() && state.error == null) {
                                    item(key = "sync-no-changes") {
                                        Text("暂无待同步更改", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                    if (canUpload) Text("加入 ${state.toAdd.size} · 移出 ${selectedRemovals.size}",
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    when {
                        state.owner == null -> MotionTextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("完成") }
                        !state.checked || state.error != null -> MotionButton(onClick = onCheck, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth()) { Text(if (state.error != null) "重新检查" else "检查拷贝书架") }
                        !state.hasFullImport -> MotionButton(onClick = onImport, enabled = canAct,
                            modifier = Modifier.fillMaxWidth()) { Text("完整导入到本机") }
                        state.toAdd.isNotEmpty() || state.toRemove.isNotEmpty() -> MotionButton(
                            onClick = { onUpload(selectedRemovals.intersect(removalIds)) }, enabled = canUpload,
                            modifier = Modifier.fillMaxWidth()) { Text("同步到拷贝") }
                        state.cloudAdded.isNotEmpty() -> MotionButton(onClick = onImport, enabled = canAct,
                            modifier = Modifier.fillMaxWidth()) { Text("导入 ${state.cloudAdded.size} 本到本机") }
                        else -> MotionTextButton(onClick = onDismiss, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth()) { Text("完成") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncGroupHeading(icon: ImageVector, label: String, count: String, expanded: Boolean,
    onToggle: () -> Unit, modifier: Modifier = Modifier) {
    MotionTextButton(onClick = onToggle, modifier = modifier.fillMaxWidth().heightIn(min = 44.dp),
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(count, Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.Outlined.ChevronRight, if (expanded) "收起书目" else "查看书目",
            Modifier.size(18.dp).graphicsLayer { rotationZ = if (expanded) 90f else 0f },
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SyncBookTitle(manga: Manga) {
    Text(manga.title, Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
}
