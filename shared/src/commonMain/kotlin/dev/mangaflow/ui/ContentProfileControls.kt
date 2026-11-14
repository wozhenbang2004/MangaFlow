package dev.mangaflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.storage.BodySourceRegistryEntry
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionButton
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionInputGate

/** The existing tab motion selects a content scope, without creating a second navigation. */
@Composable
internal fun ContentProfileSwitch(
    restricted: Boolean,
    onSelect: (Boolean) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    enabled: Boolean = true,
) {
    val scopeName = if (restricted) "成人分区" else "普通分区"
    if (compact) {
        val description = "当前$scopeName，切换到" + if (restricted) "普通分区" else "成人分区"
        MotionIconButton(
            onClick = { onSelect(!restricted) },
            modifier = modifier.size(44.dp).semantics { stateDescription = scopeName },
            enabled = enabled, tooltip = description,
        ) {
            Icon(Icons.Outlined.Lock, description, Modifier.size(20.dp))
        }
    } else {
        Row(modifier.fillMaxWidth().semantics { stateDescription = scopeName },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MotionTabs(
                selected = if (restricted) 1 else 0,
                labels = listOf("普通", "成人"),
                onSelect = { index ->
                    val next = index == 1
                    if (enabled && next != restricted) onSelect(next)
                },
                modifier = Modifier.weight(1f).motionInputGate(enabled),
                compact = true, controlHeight = 44.dp, fontSize = 13.sp,
            )
            MotionIconButton(
                onClick = onManage, enabled = enabled,
                modifier = Modifier.size(44.dp), tooltip = "当前分区来源",
            ) {
                Icon(Icons.Outlined.Tune, "当前分区来源", Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Confirming first completes the retained sheet exit; only then may its caller switch scope. */
@Composable
internal fun ContentProfileConfirmation(
    backdrop: HazeState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var accepted by remember { mutableStateOf(false) }
    var delivered by remember { mutableStateOf(false) }
    MotionBottomSheet(
        backdrop = backdrop,
        onDismissRequest = {
            if (!delivered) {
                delivered = true
                onDismiss()
                if (accepted) onConfirm()
            }
        },
    ) { dismiss ->
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = (LocalMangaWindow.current.height - 96.dp).coerceAtLeast(1.dp))
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text("仅限成年人", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp), tooltip = "关闭") {
                    Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp))
                }
            }
            Text("确认后切换内容范围，沿用当前界面与阅读方式。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            MotionButton(
                onClick = {
                    if (!accepted) {
                        accepted = true
                        dismiss()
                    }
                },
                enabled = !accepted,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("我已年满18周岁") }
            MotionTextButton(onClick = dismiss, enabled = !accepted,
                modifier = Modifier.fillMaxWidth()) { Text("返回") }
        }
    }
}

/** Source availability is supplied by the registry. This sheet never enables or contacts a source. */
@Composable
internal fun ContentProfileSourceSheet(
    entries: List<BodySourceRegistryEntry>,
    selectedId: String,
    backdrop: HazeState,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
    unavailableReasons: Map<String, String> = emptyMap(),
) {
    val sources = remember(entries) { entries.distinctBy { it.source.id } }
    var pendingSource by remember { mutableStateOf<String?>(null) }
    var manageAfterDismiss by remember { mutableStateOf(false) }
    var delivered by remember { mutableStateOf(false) }
    val choosing = pendingSource != null || manageAfterDismiss
    MotionBottomSheet(
        backdrop = backdrop,
        onDismissRequest = {
            if (!delivered) {
                delivered = true
                onDismiss()
                if (manageAfterDismiss) onManage()
                else pendingSource?.let { id ->
                    if (id !in unavailableReasons &&
                        sources.any { it.source.id == id && it.enabled && it.isInstalled && !it.removed })
                        onSelect(id)
                }
            }
        },
    ) { dismiss ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth()
                .heightIn(max = (LocalMangaWindow.current.height - 96.dp).coerceAtLeast(1.dp)),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
        ) {
            item(key = "profile-source-header") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("当前分区来源", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp), tooltip = "关闭") {
                        Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp))
                    }
                }
            }
            if (sources.isEmpty()) item(key = "profile-source-empty") {
                Text("尚未添加来源", Modifier.padding(vertical = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(sources, key = { "profile-source:" + it.source.id }) { entry ->
                val hasReason = entry.source.id in unavailableReasons
                val reason = unavailableReasons[entry.source.id]?.ifBlank { "暂不可用" }
                val available = !hasReason && entry.enabled && entry.isInstalled && !entry.removed
                val selected = entry.source.id == selectedId
                val status = when {
                    hasReason -> reason.orEmpty()
                    !entry.isInstalled -> "未安装"
                    entry.removed -> "已移除"
                    !entry.enabled -> "已停用"
                    selected -> "当前"
                    else -> "已启用"
                }
                MotionTextButton(
                    onClick = {
                        if (pendingSource == null && !manageAfterDismiss && available) {
                            pendingSource = entry.source.id
                            dismiss()
                        }
                    },
                    enabled = available && !choosing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                        .semantics { stateDescription = status },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(entry.source.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
                        if (hasReason) Text(status, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!hasReason) {
                        Spacer(Modifier.width(10.dp))
                        Text(status, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (selected && available) {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Outlined.Check, null, Modifier.size(16.dp))
                    }
                }
            }
            item(key = "profile-source-management") {
                MotionTextButton(
                    onClick = {
                        if (pendingSource == null && !manageAfterDismiss) {
                            manageAfterDismiss = true
                            dismiss()
                        }
                    },
                    enabled = !choosing,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Icon(Icons.Outlined.Tune, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("管理来源", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
