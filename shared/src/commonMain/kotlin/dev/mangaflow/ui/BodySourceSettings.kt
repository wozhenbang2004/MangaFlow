package dev.mangaflow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.storage.BodySourceRegistry
import dev.mangaflow.storage.BodySourceRegistryEntry
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionSwitchThumb
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionPress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Registry edits affect source discovery, while the source's existing records stay intact. */
@Composable
internal fun BodySourceSettingsSheet(
    registry: BodySourceRegistry,
    backdrop: HazeState?,
    onDismiss: () -> Unit,
) {
    val entries by registry.entries.collectAsStateWithLifecycle()
    val added = entries.filterNot { it.removed }
    val removed = entries.filter { it.removed }
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    fun change(entry: BodySourceRegistryEntry, action: String, operation: suspend () -> Unit) {
        if (busyId != null) return
        busyId = entry.source.id
        notice = null
        failed = false
        scope.launch {
            try {
                operation()
                notice = when (action) {
                    "remove" -> "已移除${entry.source.name}，阅读记录和下载保留。"
                    "restore" -> "已添加${entry.source.name}。"
                    else -> null
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                failed = true
                notice = failure.message?.takeIf { it.isNotBlank() } ?: "来源设置未完成，请重试。"
            } finally {
                busyId = null
            }
        }
    }

    MotionBottomSheet(onDismissRequest = onDismiss, backdrop = backdrop,
        containerColor = colors.surface.copy(alpha = .97f)) { dismiss ->
        Column(Modifier.fillMaxWidth().heightIn(max = LocalMangaWindow.current.height * .78f)
            .navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("漫画源", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("已启用 ${added.count { it.enabled }} · 已添加 ${added.size}", fontSize = 11.sp,
                        color = colors.onSurfaceVariant)
                }
                MotionIconButton(onClick = dismiss) {
                    Icon(Icons.Outlined.Close, "关闭漫画源设置", Modifier.size(20.dp))
                }
            }
            Text("关闭后不参与搜索与自动匹配，阅读记录和下载保留。核心拷贝可以关闭，不能移除。",
                fontSize = 11.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp))
            notice?.let { message ->
                Text(message, fontSize = 11.sp, lineHeight = 17.sp,
                    color = if (failed) colors.error else colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite })
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(start = 20.dp, end = 12.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(added, key = { "added:${it.source.id}" }) { entry ->
                    BodySourceSettingsRow(entry, enabled = busyId == null, busy = busyId == entry.source.id,
                        onToggle = { value -> change(entry, "toggle") { registry.setEnabled(entry.source.id, value) } },
                        onRemove = { change(entry, "remove") { registry.remove(entry.source.id) } })
                }
                item(key = "add-sources") {
                    val interaction = remember { MutableInteractionSource() }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                        .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                            onClickLabel = if (adding) "收起可添加漫画源" else "添加内置漫画源",
                            onClick = { adding = !adding })
                        .motionPress(interaction, pressedScale = .99f).padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(24.dp), tint = colors.onSurfaceVariant)
                        Text("添加内置源", Modifier.weight(1f), fontSize = 13.sp)
                        Icon(if (adding) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                    }
                }
                if (adding) {
                    if (removed.isEmpty()) item(key = "no-removed-sources") {
                        Text("所有内置源均已添加。", fontSize = 11.sp, color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(start = 36.dp, bottom = 8.dp))
                    }
                    items(removed, key = { "removed:${it.source.id}" }) { entry ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ChapterSourceLogo(entry.source.id, entry.source.name, Modifier.size(24.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(entry.source.name, fontSize = 13.sp)
                                Text(entry.source.language, fontSize = 11.sp, color = colors.onSurfaceVariant)
                            }
                            if (busyId == entry.source.id) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                MotionBusyIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
                            } else MotionTextButton(enabled = busyId == null,
                                onClick = { change(entry, "restore") { registry.restore(entry.source.id) } }) {
                                Text("添加", fontSize = 12.sp)
                            }
                        }
                    }
                }
                item(key = "builtin-source-note") {
                    Text("当前为内置源；扩展导入尚未接入。移除仅停用并隐藏该源，不删除阅读记录与下载。",
                        fontSize = 11.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, end = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun BodySourceSettingsRow(
    entry: BodySourceRegistryEntry,
    enabled: Boolean,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember(entry.source.id) { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ChapterSourceLogo(entry.source.id, entry.source.name, Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(entry.source.name, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(entry.source.language, if (entry.enabled) "已启用" else "已关闭",
                if (entry.isProtected) "核心" else "内置").filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 11.sp, color = colors.onSurfaceVariant)
        }
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
            .toggleable(entry.enabled, enabled = enabled, interactionSource = interaction, indication = null,
                role = Role.Switch, onValueChange = onToggle)
            .semantics { contentDescription = "${entry.source.name}参与搜索与自动匹配" },
            contentAlignment = Alignment.Center) {
            if (busy) MotionBusyIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
            else MotionSwitchThumb(entry.enabled, interaction)
        }
        if (entry.isProtected) Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Lock, "核心源不可移除", Modifier.size(17.dp), tint = colors.onSurfaceVariant.copy(alpha = .65f))
        } else MotionIconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Outlined.DeleteOutline, "移除${entry.source.name}", Modifier.size(19.dp), tint = colors.onSurfaceVariant)
        }
    }
}
