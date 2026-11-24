package dev.mangaflow.anime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Leaderboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mangaflow.anime.AnimeDiscoveryFeed
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.platform.localDateAt
import dev.mangaflow.ui.platform.minusDays
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber

/** Discovery previews retained facts; opening the shared schedule owns its actual load. */
@Composable
internal fun AnimeHomeDiscoveryShortcuts(calendar: AnimeDiscoveryFeed?, today: LocalDate, active: Boolean,
    onSchedule: (() -> Unit)?, onRanking: (() -> Unit)?) {
    if (onSchedule == null && onRanking == null) return
    val weekday = today.dayOfWeek.isoDayNumber
    val monday = today.minusDays((weekday - 1).toLong())
    val scheduleCaption = remember(calendar, today) {
        val loadedDate = calendar?.fetchedAt?.let(::localDateAt)
        val eligible = calendar?.query?.calendar == true && loadedDate != null && loadedDate in monday..today
        val todayItems = if (!eligible) emptyList() else calendar?.items.orEmpty().filter { work ->
            work.airWeekday == weekday || (work.airWeekday == null && calendar?.query?.weekday == weekday)
        }.distinctBy { it.key }
        when {
            todayItems.size == 1 -> todayItems.first().title
            todayItems.size > 1 -> "今日放送 · ${todayItems.size} 部"
            else -> "按周或季度查看"
        }
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        onSchedule?.let { AnimeHomeShortcut("放送", scheduleCaption, Icons.Outlined.CalendarMonth,
            Modifier.weight(1f), active, it) }
        onRanking?.let { AnimeHomeShortcut("榜单", "热门 · 高分", Icons.Outlined.Leaderboard,
            Modifier.weight(1f), active, it) }
    }
}

@Composable
private fun AnimeHomeShortcut(title: String, supporting: String, icon: ImageVector,
    modifier: Modifier, active: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    Row(modifier.motionPress(interaction, enabled = active, pressedScale = .985f)
        .clip(shape).background(colors.surfaceContainerHigh.copy(alpha = .68f))
        .clickable(interactionSource = interaction, indication = null, enabled = active, role = Role.Button,
            onClickLabel = "查看$title", onClick = onClick)
        .heightIn(min = 58.dp).padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, Modifier.size(18.dp), tint = colors.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
            Text(supporting, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(15.dp), tint = colors.onSurfaceVariant)
    }
}
