package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Tv
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.storage.BodySourceRegistryEntry
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.LocalPlatformAccessibilityPreferences
import dev.mangaflow.ui.motion.motionInputGate

internal enum class ContentProfile(val key: String, val label: String, val title: String, val icon: ImageVector) {
    ORDINARY("ordinary", "普通", "普通漫画", Icons.AutoMirrored.Outlined.MenuBook),
    RESTRICTED("restricted", "成人", "成人漫画", Icons.Outlined.Lock),
    ANIME("anime", "番剧", "番剧专区", Icons.Outlined.Tv),
}

/** Content areas own their navigation; this control selects the area above its destinations. */
@Composable
internal fun ContentProfileSwitch(
    profile: ContentProfile,
    onSelect: (ContentProfile) -> Unit,
    onManage: (() -> Unit)?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    enabled: Boolean = true,
) {
    val scopeName = profile.title
    val colors = MaterialTheme.colorScheme
    val accessibility = LocalPlatformAccessibilityPreferences.current
    val activeInk = if (accessibility.increaseContrast) colors.onSurface else colors.primary
    val track = lerp(colors.surface, colors.primary, if (accessibility.increaseContrast) .22f else .11f)
    val selectedPaper = colors.surface
    val sourceAction = if (profile == ContentProfile.ANIME) "管理片源" else "当前分区来源"
    val manage: @Composable () -> Unit = {
        if (onManage != null) MotionIconButton(onClick = onManage, enabled = enabled,
            modifier = Modifier.size(44.dp), tooltip = sourceAction,
        ) {
            Icon(Icons.Outlined.Tune, sourceAction, Modifier.size(18.dp),
                tint = colors.onSurfaceVariant)
        }
    }
    if (compact) {
        Column(modifier.fillMaxWidth().semantics { stateDescription = scopeName },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Column(Modifier.background(track, RoundedCornerShape(16.dp)).padding(vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                ContentProfile.entries.forEach { entry ->
                    val current = entry == profile
                    MotionIconButton(
                        onClick = { if (!current) onSelect(entry) },
                        modifier = Modifier.size(44.dp).semantics { selected = current },
                        enabled = enabled, tooltip = entry.title,
                    ) {
                        Box(Modifier.size(35.dp).background(if (current) selectedPaper else Color.Transparent,
                            RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Icon(entry.icon, entry.title, Modifier.size(19.dp),
                                tint = if (current) activeInk else colors.onSurfaceVariant)
                        }
                    }
                }
            }
            manage()
        }
    } else {
        Row(modifier.fillMaxWidth().semantics { stateDescription = scopeName },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BoxWithConstraints(Modifier.weight(1f)) {
                val fontScale = LocalDensity.current.fontScale
                val showIcons = fontScale <= 1.3f &&
                    maxWidth >= (32.dp + 28.dp * fontScale) * ContentProfile.entries.size
                MotionTabs(
                    selected = profile.ordinal,
                    labels = ContentProfile.entries.map { it.label },
                    onSelect = { index ->
                        val next = ContentProfile.entries[index]
                        if (enabled && next != profile) onSelect(next)
                    },
                    modifier = Modifier.fillMaxWidth().motionInputGate(enabled),
                    foreground = activeInk, muted = colors.onSurfaceVariant, track = track, indicator = selectedPaper,
                    fullWidthIndicator = true, treatment = MotionTabTreatment.Soft,
                    selectedFontWeight = FontWeight.SemiBold, unselectedFontWeight = FontWeight.Normal,
                    controlHeight = 44.dp, fontSize = 13.sp,
                    itemContent = if (!showIcons) null else { index, current, ink ->
                        val entry = ContentProfile.entries[index]
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(entry.icon, null, Modifier.size(15.dp), tint = ink)
                            Text(entry.label, style = MaterialTheme.typography.labelLarge, fontSize = 13.sp,
                                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal, color = ink,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                )
            }
            manage()
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
        backdrop = backdrop, desktopWidth = 592.dp,
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
        val desktop = platformKind == PlatformKind.DESKTOP
        val height = LocalMangaWindow.current.height
        val pinned = desktop && height >= 310.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        val heading: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("漫画来源", style = MaterialTheme.typography.titleMedium)
                    val selectedName = sources.firstOrNull { it.source.id == selectedId }?.source?.name
                    if (desktop && sources.size > 4 && (selectedId == "*" || selectedName != null)) {
                        Text("当前 · " + (selectedName ?: "全部来源"), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp), tooltip = "关闭") {
                    Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp))
                }
            }
        }
        val management: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth().padding(top = if (desktop) 4.dp else 16.dp),
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
        Column(Modifier.fillMaxWidth().heightIn(max = if (desktop) height.coerceAtMost(660.dp)
            else (height - 96.dp).coerceAtLeast(1.dp)).padding(horizontal = 20.dp)) {
            if (pinned) heading()
            AccountReviewList(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(bottom = if (pinned) 6.dp else 20.dp)) {
                if (!pinned) item(key = "profile-source-header") { heading() }
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
                if (!pinned) item(key = "profile-source-management") { management() }
            }
            if (pinned) management()
        }
    }
}
