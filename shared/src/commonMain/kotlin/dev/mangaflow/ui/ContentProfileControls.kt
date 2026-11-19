package dev.mangaflow.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.storage.BodySourceRegistryEntry
import dev.mangaflow.ui.motion.MotionBottomSheet
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
    onAccount: (() -> Unit)? = null,
    onInspect: ((String) -> Unit)? = null,
    accountRequiredIds: Set<String> = emptySet(),
    onOpenSourceAccount: ((String) -> Unit)? = null,
) {
    val sources = remember(entries) { entries.distinctBy { it.source.id } }
    val sourceGroups = remember(sources, unavailableReasons, accountRequiredIds) {
        sources.groupBy { entry -> when {
            !entry.enabled || !entry.isInstalled || entry.removed -> 2
            entry.source.id in accountRequiredIds -> 1
            entry.source.id in unavailableReasons -> 2
            else -> 0
        } }.entries.sortedBy { it.key }
    }
    val colors = MaterialTheme.colorScheme
    var pendingSource by remember { mutableStateOf<String?>(null) }
    var manageAfterDismiss by remember { mutableStateOf(false) }
    var accountAfterDismiss by remember { mutableStateOf(false) }
    var pendingAccount by remember { mutableStateOf<String?>(null) }
    var pendingInspect by remember { mutableStateOf<String?>(null) }
    var delivered by remember { mutableStateOf(false) }
    val choosing = pendingSource != null || pendingInspect != null || pendingAccount != null ||
        manageAfterDismiss || accountAfterDismiss
    fun canChoose() = !delivered && pendingSource == null && pendingInspect == null && pendingAccount == null &&
        !manageAfterDismiss && !accountAfterDismiss
    MotionBottomSheet(
        backdrop = backdrop,
        onDismissRequest = {
            if (!delivered) {
                delivered = true
                onDismiss()
                if (pendingAccount != null) pendingAccount?.let { id ->
                    if (onOpenSourceAccount != null) onOpenSourceAccount(id)
                    else if (onInspect != null) onInspect(id)
                    else onAccount?.invoke()
                }
                else if (pendingInspect != null) pendingInspect?.let { onInspect?.invoke(it) }
                else if (accountAfterDismiss) onAccount?.invoke()
                else if (manageAfterDismiss) onManage()
                else pendingSource?.let { id ->
                    if (id == "*" || id !in unavailableReasons && id !in accountRequiredIds &&
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
                    Text("漫画来源", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp), tooltip = "关闭") {
                        Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp))
                    }
                }
            }
            if (sources.any { it.enabled && it.isInstalled && !it.removed && it.source.id !in accountRequiredIds }) item(key = "all-sources") {
                MotionTextButton(onClick = {
                    if (canChoose()) { pendingSource = "*"; dismiss() }
                }, enabled = !choosing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .semantics { selected = selectedId == "*" },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = if (selectedId == "*") colors.onSurface.copy(alpha = .055f) else Color.Transparent,
                        contentColor = colors.onSurface,
                    ), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
                    Icon(Icons.Outlined.ViewModule, null, Modifier.size(24.dp), tint = colors.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text("全部来源", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selectedId == "*") FontWeight.Medium else FontWeight.Normal)
                    if (selectedId == "*") Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                }
            }
            if (sources.isEmpty()) item(key = "profile-source-empty") {
                Text("尚未添加来源", Modifier.padding(vertical = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            sourceGroups.forEach { (group, groupSources) ->
                item(key = "profile-source-list-heading:$group") {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 18.dp, bottom = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(when (group) { 0 -> "浏览来源"; 1 -> "登录后浏览"; else -> "待处理" },
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        Text(groupSources.size.toString(), style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant.copy(alpha = .65f))
                    }
                }
                items(groupSources, key = { "profile-source:" + it.source.id }) { entry ->
                    val hasReason = entry.source.id in unavailableReasons
                    val reason = unavailableReasons[entry.source.id]?.ifBlank { "暂不可用" }
                    val usable = entry.enabled && entry.isInstalled && !entry.removed
                    val requiresAccount = usable && entry.source.id in accountRequiredIds
                    val canOpenAccount = requiresAccount && (onOpenSourceAccount != null || onInspect != null || onAccount != null)
                    val available = !hasReason && usable && !requiresAccount
                    val isSelected = entry.source.id == selectedId
                    val status = when {
                        entry.removed -> "已移除"
                        !entry.isInstalled -> "未安装"
                        !entry.enabled -> "已停用"
                        requiresAccount -> "浏览需要登录"
                        hasReason -> reason.orEmpty()
                        isSelected -> "当前"
                        else -> "已启用"
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .background(if (isSelected) colors.onSurface.copy(alpha = .055f) else Color.Transparent,
                            RoundedCornerShape(14.dp)), verticalAlignment = Alignment.CenterVertically) {
                        MotionTextButton(
                            onClick = {
                                if (canChoose() && (available || canOpenAccount)) {
                                    if (canOpenAccount) pendingAccount = entry.source.id else pendingSource = entry.source.id
                                    dismiss()
                                }
                            },
                            enabled = (available || canOpenAccount) && !choosing,
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                                .semantics { selected = isSelected; stateDescription = status },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface),
                            contentPadding = PaddingValues(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                        ) {
                            ChapterSourceLogo(entry.source.id, entry.source.name,
                                Modifier.size(24.dp).clearAndSetSemantics { }, muted = !available && !canOpenAccount)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(entry.source.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal)
                                if (!available) Text(status, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(8.dp))
                            if (canOpenAccount) Text("登录", style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
                            else if (isSelected) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp),
                                tint = colors.onSurfaceVariant)
                            else Spacer(Modifier.size(18.dp))
                        }
                        if (onInspect != null && entry.isInstalled && !entry.removed) {
                            MotionIconButton(onClick = {
                                if (canChoose()) { pendingInspect = entry.source.id; dismiss() }
                            }, enabled = !choosing, modifier = Modifier.size(48.dp), tooltip = "${entry.source.name}状态与设置") {
                                Icon(Icons.Outlined.Tune, "${entry.source.name}状态与设置", Modifier.size(18.dp),
                                    tint = colors.onSurfaceVariant)
                            }
                        } else if (onInspect != null) Spacer(Modifier.size(48.dp))
                    }
                }
            }
            item(key = "profile-source-management") {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onAccount != null) MotionTextButton(
                        onClick = {
                            if (canChoose()) { accountAfterDismiss = true; dismiss() }
                        }, enabled = !choosing, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    ) {
                        Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("账号登录", style = MaterialTheme.typography.labelLarge)
                    }
                    MotionTextButton(
                        onClick = {
                            if (canChoose()) { manageAfterDismiss = true; dismiss() }
                        }, enabled = !choosing, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    ) {
                        Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("管理来源", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
