package dev.mangaflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.account.JmFavoriteChoice
import dev.mangaflow.account.JmShelfSyncState
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionButton
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton

/** Single adult-profile JM comparison sheet. Read-only until an explicit per-row choice. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun JmShelfSyncSheet(
    state: JmShelfSyncState,
    backdrop: HazeState?,
    onCheck: () -> Unit,
    onApply: (Long, Map<String, JmFavoriteChoice>) -> Unit,
    onCancel: () -> Unit,
    onOpenAccount: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var chosen by remember(state.revision) { mutableStateOf<Map<String, JmFavoriteChoice>>(emptyMap()) }
    var confirmRemoval by remember(state.revision) { mutableStateOf(false) }
    val actions = chosen.filterValues { it != JmFavoriteChoice.SKIP }
    val deletions = actions.values.count {
        it == JmFavoriteChoice.REMOVE_REMOTE || it == JmFavoriteChoice.REMOVE_LOCAL
    }
    val live = state.checked && !state.busy && state.owner != null
    fun select(id: String, choice: JmFavoriteChoice) {
        if (!live) return
        chosen = if (chosen[id] == choice) chosen - id else chosen + (id to choice)
        confirmRemoval = false
    }
    MotionBottomSheet(backdrop = backdrop, onDismissRequest = onDismiss) { dismiss ->
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalMangaWindow.current.height - 64.dp).coerceAtLeast(160.dp))
            .padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Sync, null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
                Text("禁漫收藏差异", Modifier.weight(1f).padding(start = 7.dp),
                    style = MaterialTheme.typography.titleMedium)
                if (state.busy) MotionTextButton(onClick = onCancel) {
                    Text("取消", fontSize = 12.sp)
                } else MotionIconButton(onClick = onCheck, tooltip = "重新读取 JM 收藏",
                    modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Refresh, "重新读取", Modifier.size(18.dp))
                }
                MotionIconButton(onClick = dismiss, tooltip = "关闭", modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Close, "关闭", Modifier.size(18.dp))
                }
            }
            Text(if (state.owner == null) "成人书架 · JM 账号未连接"
                else "成人书架 · " + state.owner,
                fontSize = 11.sp, color = colors.onSurfaceVariant)
            if (state.checked) {
                Text("本机 ${state.localCount}  ·  JM ${state.remoteCount}  ·  差异 ${state.differences.size}",
                    fontSize = 12.sp, color = colors.onSurfaceVariant)
            }
            if (state.busy) QuietLoading(if (state.applying) "正在执行已确认的变更…" else "正在完整读取禁漫收藏…")
            state.error?.let {
                Text(it, fontSize = 12.sp, color = colors.error)
            }
            state.completed?.let {
                Text(it, fontSize = 12.sp, color = colors.onSurfaceVariant)
            }
            if (state.owner == null && !state.busy) {
                MotionTextButton(onClick = onOpenAccount) { Text("去成人区登录禁漫", fontSize = 12.sp) }
            }
            if (state.checked && state.differences.isEmpty()) {
                Text("JM 与成人书架中的禁漫作品已对齐。其他成人来源不会上传到 JM。",
                    fontSize = 12.sp, color = colors.onSurfaceVariant)
            }
            if (state.checked && state.differences.isNotEmpty()) {
                Text("只操作已选中的项目；不自动删除本地或云端记录。",
                    fontSize = 11.sp, color = colors.onSurfaceVariant)
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.differences, key = { it.key }) { diff ->
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(diff.manga.title, Modifier.weight(1f), fontSize = 13.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (diff.inLocal) "仅本机" else "仅 JM", fontSize = 10.sp,
                                    color = colors.onSurfaceVariant)
                            }
                            val primary = if (diff.inLocal) JmFavoriteChoice.UPLOAD_REMOTE
                                else JmFavoriteChoice.IMPORT_LOCAL
                            val removal = if (diff.inLocal) JmFavoriteChoice.REMOVE_LOCAL
                                else JmFavoriteChoice.REMOVE_REMOTE
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                JmShelfChoice(if (diff.inLocal) "同步到 JM" else "导入本机",
                                    diff.manga.title, chosen[diff.key] == primary, live) { select(diff.key, primary) }
                                JmShelfChoice(if (diff.inLocal) "移出本机" else "从 JM 移除",
                                    diff.manga.title, chosen[diff.key] == removal, live, destructive = true) {
                                    select(diff.key, removal)
                                }
                            }
                        }
                    }
                }
                if (confirmRemoval && deletions > 0) {
                    Text("将删除 ${deletions} 项已选择的收藏（本机或 JM）。此操作不可撤销，其他收藏不会改变。",
                        fontSize = 11.sp, color = colors.error)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    MotionTextButton(onClick = { chosen = emptyMap(); confirmRemoval = false },
                        enabled = actions.isNotEmpty() && live) {
                        Text("清除选择", fontSize = 12.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    MotionButton(onClick = {
                        if (deletions > 0 && !confirmRemoval) confirmRemoval = true
                        else onApply(state.revision, actions)
                    }, enabled = live && actions.isNotEmpty()) {
                        Text(if (confirmRemoval && deletions > 0) "确认删除并应用"
                            else "应用 ${actions.size} 项", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}


@Composable
private fun JmShelfChoice(
    label: String, title: String, chosen: Boolean, enabled: Boolean,
    destructive: Boolean = false, onChoose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    MotionTextButton(onClick = onChoose, enabled = enabled,
        modifier = Modifier.heightIn(min = 44.dp).semantics {
            selected = chosen
            contentDescription = "$title，$label"
        },
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (chosen && destructive) colors.onErrorContainer else colors.onSurface,
            containerColor = when {
                !chosen -> Color.Transparent
                destructive -> colors.errorContainer
                else -> colors.surfaceVariant
            }),
        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp)) {
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            if (chosen) Icon(Icons.Outlined.Check, null, Modifier.size(13.dp))
        }
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
