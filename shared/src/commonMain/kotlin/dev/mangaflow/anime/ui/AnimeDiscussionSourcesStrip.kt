package dev.mangaflow.anime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.sources.AnimeDiscussionCheckStatus
import dev.mangaflow.anime.sources.AnimeDiscussionSourceCheck
import dev.mangaflow.ui.CompactGlassPopover
import dev.mangaflow.ui.ReviewSourceMark
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton

/** The manga filter keeps the reading task; this disclosure explains every provider's real state. */
@Composable
internal fun AnimeDiscussionSourcesStrip(
    ownerId: String,
    checks: List<AnimeDiscussionSourceCheck>,
    backdrop: HazeState,
    enabled: Boolean,
    canRetry: Boolean,
    onRetry: (String) -> Unit,
    onPresence: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    if (checks.isEmpty()) return
    var open by remember(ownerId) { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) open = false }
    val colors = MaterialTheme.colorScheme
    val matched = checks.count { it.status == AnimeDiscussionCheckStatus.READY }
    val checking = checks.count { it.status == AnimeDiscussionCheckStatus.CHECKING }
    val unavailable = checks.count { it.status == AnimeDiscussionCheckStatus.UNAVAILABLE }
    val unmatched = checks.count { it.status == AnimeDiscussionCheckStatus.NO_MATCH }
    val waiting = checks.size - matched - checking - unavailable - unmatched
    val summary = listOfNotNull(
        "$matched 站已匹配".takeIf { matched > 0 },
        "$checking 站检查中".takeIf { checking > 0 },
        "$unavailable 站读取失败".takeIf { unavailable > 0 },
        "$unmatched 站未匹配".takeIf { unmatched > 0 },
        "$waiting 站待检查".takeIf { waiting > 0 },
    ).joinToString(" · ")
    Box(modifier) {
        if (compact) MotionIconButton(onClick = { open = !open }, enabled = enabled,
            modifier = Modifier.size(44.dp).semantics {
                contentDescription = "作品评论来源，$summary"
                stateDescription = if (open) "来源状态已展开" else "来源状态已收起"
            }, tooltip = "作品评论来源") {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Public, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                if (checking > 0) MotionBusyIndicator(Modifier.align(Alignment.BottomEnd).size(8.dp),
                    color = colors.onSurfaceVariant, strokeWidth = 1.dp)
                else if (unavailable > 0) Icon(Icons.Outlined.ErrorOutline, null,
                    Modifier.align(Alignment.BottomEnd).size(10.dp), tint = colors.error)
            }
        } else MotionTextButton(onClick = { open = !open }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics {
                contentDescription = "作品评论来源，$summary"
                stateDescription = if (open) "来源状态已展开" else "来源状态已收起"
            }, contentPadding = PaddingValues(0.dp)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                checks.forEach { ReviewSourceMark(it.sourceId, size = 15.dp) }
                Text(summary, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (checking > 0) MotionBusyIndicator(Modifier.size(13.dp))
            else Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
        }
        CompactGlassPopover(open && enabled, backdrop, onDismiss = { open = false }, onPresenceChange = onPresence,
            alignToStart = !compact, maximumWidth = 352.dp) {
            Column(Modifier.fillMaxWidth().padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("作品评论来源", style = MaterialTheme.typography.titleSmall)
                    Text(summary, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                checks.forEach { check ->
                    AnimeDiscussionSourceRow(check,
                        onRetry = { onRetry(check.sourceId) }.takeIf { canRetry && enabled })
                }
            }
        }
    }
}

@Composable
private fun AnimeDiscussionSourceRow(check: AnimeDiscussionSourceCheck, onRetry: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val failure = check.status == AnimeDiscussionCheckStatus.UNAVAILABLE
    val status = when (check.status) {
        AnimeDiscussionCheckStatus.WAITING -> "待检查"
        AnimeDiscussionCheckStatus.CHECKING -> "检查中"
        AnimeDiscussionCheckStatus.READY -> "已匹配"
        AnimeDiscussionCheckStatus.NO_MATCH -> "未找到对应作品"
        AnimeDiscussionCheckStatus.UNAVAILABLE -> "读取失败"
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
        .background(colors.onSurface.copy(alpha = .045f)).padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        ReviewSourceMark(check.sourceId, size = 19.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(check.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val result = check.message.ifBlank { status }
            Text(if (failure && result != status) "$status · $result" else result, style = MaterialTheme.typography.bodySmall,
                color = if (failure) colors.error else colors.onSurfaceVariant)
        }
        when {
            check.status == AnimeDiscussionCheckStatus.CHECKING ->
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    MotionBusyIndicator(Modifier.size(16.dp))
                }
            onRetry != null && check.status != AnimeDiscussionCheckStatus.WAITING -> {
                val action = when (check.status) {
                    AnimeDiscussionCheckStatus.READY -> "刷新 ${check.name} 评论"
                    AnimeDiscussionCheckStatus.UNAVAILABLE -> "重试 ${check.name} 评论"
                    else -> "重新匹配 ${check.name} 作品"
                }
                MotionIconButton(onClick = onRetry, modifier = Modifier.size(44.dp), tooltip = action) {
                    Icon(Icons.Outlined.Refresh, action, Modifier.size(18.dp),
                        tint = if (failure) colors.error else colors.onSurfaceVariant)
                }
            }
            else -> Unit
        }
    }
}
