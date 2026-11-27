package dev.mangaflow.ui

import androidx.compose.foundation.layout.*
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
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.account.AdultFavoriteChoice
import dev.mangaflow.account.AdultShelfSyncState
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionButton
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionSwap
import dev.mangaflow.ui.motion.MotionTextButton

/** Source-owned comparison. Read-only until an explicit per-row choice. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdultShelfSyncSheet(
    state: AdultShelfSyncState,
    sourceName: String,
    backdrop: HazeState?,
    onCheck: () -> Unit,
    onApply: (Long, Map<String, AdultFavoriteChoice>) -> Unit,
    onCancel: () -> Unit,
    onOpenAccount: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var cancelRequested by remember(state.revision) { mutableStateOf(false) }
    var chosen by remember(state.revision) { mutableStateOf<Map<String, AdultFavoriteChoice>>(emptyMap()) }
    var confirmRemoval by remember(state.revision) { mutableStateOf(false) }
    var openAccountAfterExit by remember { mutableStateOf(false) }
    val actions = chosen.filterValues { it != AdultFavoriteChoice.SKIP }
    val deletions = actions.values.count {
        it == AdultFavoriteChoice.REMOVE_REMOTE || it == AdultFavoriteChoice.REMOVE_LOCAL
    }
    val live = state.checked && !state.busy && state.owner != null
    fun select(id: String, choice: AdultFavoriteChoice) {
        if (!live) return
        chosen = if (chosen[id] == choice) chosen - id else chosen + (id to choice)
        confirmRemoval = false
    }
    MotionBottomSheet(backdrop = backdrop, desktopWidth = 736.dp, onDismissRequest = {
        onDismiss()
        if (openAccountAfterExit) onOpenAccount()
    }) { dismiss ->
        val hasDifferences = state.checked && state.differences.isNotEmpty()
        AccountDifferencePanel(header = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Sync, null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
                Text("${sourceName}收藏差异", Modifier.weight(1f).padding(start = 7.dp),
                    style = MaterialTheme.typography.titleMedium)
                if (state.busy) MotionTextButton(onClick = { cancelRequested = true; onCancel() }) {
                    Text("取消", fontSize = 12.sp)
                } else MotionIconButton(onClick = onCheck, tooltip = "重新读取${sourceName}收藏",
                    modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Refresh, "重新读取", Modifier.size(18.dp))
                }
                MotionIconButton(onClick = dismiss, tooltip = "关闭", modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Close, "关闭", Modifier.size(18.dp))
                }
            }
        }, summary = {
            state.owner?.let { owner -> Text("成人书架 · $owner", fontSize = 11.sp, color = colors.onSurfaceVariant) }
            if (state.checked) {
                Text("本机 ${state.localCount}  ·  $sourceName ${state.remoteCount}  ·  差异 ${state.differences.size}",
                    fontSize = 12.sp, color = colors.onSurfaceVariant)
            }
            if (platformKind == PlatformKind.DESKTOP) {
                val status = when {
                    state.busy -> PreferenceNotice(if (state.applying) "正在执行已确认的变更…" else "正在读取${sourceName}收藏…",
                        PreferenceNoticeKind.WORKING)
                    state.error != null -> PreferenceNotice(state.error,
                        if (cancelRequested) PreferenceNoticeKind.INFO else PreferenceNoticeKind.ERROR)
                    state.completed != null -> PreferenceNotice(state.completed, PreferenceNoticeKind.DONE)
                    state.checked && state.differences.isEmpty() -> PreferenceNotice("本机书架中的${sourceName}作品已与站点收藏对齐。",
                        PreferenceNoticeKind.DONE)
                    else -> null
                }
                PreferenceOperationFeedback(status)
            } else {
                if (state.busy) QuietLoading(if (state.applying) "正在执行已确认的变更…" else "正在读取${sourceName}收藏…")
                state.error?.let {
                    Text(it, fontSize = 12.sp, color = colors.error)
                }
                state.completed?.let {
                    Text(it, fontSize = 12.sp, color = colors.onSurfaceVariant)
                }
            }
            if (state.loginRequired && !state.busy) {
                MotionTextButton(onClick = { openAccountAfterExit = true; dismiss() }) { Text("登录${sourceName}", fontSize = 12.sp) }
            }
            if (platformKind != PlatformKind.DESKTOP && state.checked && state.differences.isEmpty()) {
                Text("本机书架中的${sourceName}作品已与站点收藏对齐。",
                    fontSize = 12.sp, color = colors.onSurfaceVariant)
            }
            if (hasDifferences) {
                Text("只操作已选中的项目；不自动删除本地或云端记录。",
                    fontSize = 11.sp, color = colors.onSurfaceVariant)
            }
            if (platformKind == PlatformKind.DESKTOP && !state.checked && !state.busy && !state.loginRequired &&
                state.completed == null && state.error == null) {
                MotionButton(onClick = onCheck) { Text("比较收藏", fontSize = 12.sp) }
            }
        }, hasDifferences = hasDifferences, mobileListMax = 440.dp, footer = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (platformKind == PlatformKind.DESKTOP && actions.isNotEmpty()) {
                    val counts = listOf(
                        AdultFavoriteChoice.IMPORT_LOCAL to "导入本机",
                        AdultFavoriteChoice.UPLOAD_REMOTE to "同步到$sourceName",
                        AdultFavoriteChoice.REMOVE_LOCAL to "移出本机",
                        AdultFavoriteChoice.REMOVE_REMOTE to "从${sourceName}移除",
                    ).mapNotNull { (choice, label) ->
                        actions.values.count { it == choice }.takeIf { it > 0 }?.let { "$label $it" }
                    }
                    Text(counts.joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant)
                }
                if (confirmRemoval && deletions > 0) {
                    Text("确认删除已选的 $deletions 项收藏（本机或$sourceName）。",
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
        }) {
            items(state.differences, key = { it.key }) { diff ->
                Column(Modifier.fillMaxWidth().accountReviewRow(chosen[diff.key] != null), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(diff.manga.title, Modifier.weight(1f), fontSize = 13.sp,
                            maxLines = if (platformKind == PlatformKind.DESKTOP) 2 else 1, overflow = TextOverflow.Ellipsis)
                        Text(if (diff.inLocal) "仅本机" else "仅$sourceName", fontSize = 10.sp,
                            color = colors.onSurfaceVariant)
                    }
                    val primary = if (diff.inLocal) AdultFavoriteChoice.UPLOAD_REMOTE
                        else AdultFavoriteChoice.IMPORT_LOCAL
                    val removal = if (diff.inLocal) AdultFavoriteChoice.REMOVE_LOCAL
                        else AdultFavoriteChoice.REMOVE_REMOTE
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        AdultShelfChoice(if (diff.inLocal) "同步到$sourceName" else "导入本机",
                            diff.manga.title, chosen[diff.key] == primary, live) { select(diff.key, primary) }
                        AdultShelfChoice(if (diff.inLocal) "移出本机" else "从${sourceName}移除",
                            diff.manga.title, chosen[diff.key] == removal, live, destructive = true) {
                            select(diff.key, removal)
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun AdultShelfChoice(
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
        if (platformKind == PlatformKind.DESKTOP) MotionSwap(chosen, Modifier.size(14.dp)) { marked ->
            if (marked) Icon(Icons.Outlined.Check, null, Modifier.size(13.dp))
        } else Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            if (chosen) Icon(Icons.Outlined.Check, null, Modifier.size(13.dp))
        }
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
