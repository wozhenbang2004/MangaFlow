package dev.mangaflow.anime.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
) {
    if (checks.isEmpty()) return
    var open by remember(ownerId) { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) open = false }
    val colors = MaterialTheme.colorScheme
    val matched = checks.count { it.status == AnimeDiscussionCheckStatus.READY }
    val checking = checks.count { it.status == AnimeDiscussionCheckStatus.CHECKING }
    val unavailable = checks.count { it.status == AnimeDiscussionCheckStatus.UNAVAILABLE }
    val summary = when {
        checking > 0 -> "$checking 站检查中"
        unavailable > 0 -> "$unavailable 站待重试"
        matched > 0 -> "$matched 站已匹配"
        checks.any { it.status == AnimeDiscussionCheckStatus.WAITING } -> "等待检查"
        else -> "未找到匹配"
    }
    Box(modifier) {
        MotionTextButton(onClick = { open = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics {
                contentDescription = "评论来源检查，$summary，查看各站状态"
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
        CompactGlassPopover(open, backdrop, onDismiss = { open = false }, onPresenceChange = onPresence,
            alignToStart = true, maximumWidth = 340.dp) {
            AnimeSectionTitle("评论来源")
            checks.forEach { check ->
                val status = when (check.status) {
                    AnimeDiscussionCheckStatus.WAITING -> "待检查"
                    AnimeDiscussionCheckStatus.CHECKING -> "检查中"
                    AnimeDiscussionCheckStatus.READY -> "已匹配"
                    AnimeDiscussionCheckStatus.NO_MATCH -> "未匹配"
                    AnimeDiscussionCheckStatus.UNAVAILABLE -> "读取失败"
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    ReviewSourceMark(check.sourceId, size = 20.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(check.name, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(status, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        if (check.message.isNotBlank()) Text(check.message,
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    if (check.status == AnimeDiscussionCheckStatus.CHECKING) {
                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                            MotionBusyIndicator(Modifier.size(16.dp))
                        }
                    } else if (canRetry && check.status != AnimeDiscussionCheckStatus.WAITING) {
                        val action = if (check.status == AnimeDiscussionCheckStatus.READY) "刷新" else "重新检查"
                        MotionIconButton(onClick = { onRetry(check.sourceId) }, enabled = enabled,
                            modifier = Modifier.size(44.dp), tooltip = "$action ${check.name}") {
                            Icon(Icons.Outlined.Refresh, "$action ${check.name}", Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}
