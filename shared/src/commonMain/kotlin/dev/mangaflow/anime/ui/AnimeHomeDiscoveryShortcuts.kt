package dev.mangaflow.anime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.platform.LocalDensity
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
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 5.dp)
        .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp))) {
        val links: @Composable (Modifier) -> Unit = { item ->
            onSchedule?.let { AnimeHomeShortcut("放送", scheduleCaption, Icons.Outlined.CalendarMonth, item, active, it) }
            onRanking?.let { AnimeHomeShortcut("榜单", "热门 · 高分", Icons.Outlined.Leaderboard, item, active, it) }
        }
        if (onSchedule != null && onRanking != null && maxWidth < 265.dp * LocalDensity.current.fontScale) {
            Column(Modifier.fillMaxWidth()) { links(Modifier.fillMaxWidth()) }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            links(Modifier.weight(1f))
        }
    }
}

@Composable
private fun AnimeHomeShortcut(title: String, supporting: String, icon: ImageVector,
    modifier: Modifier, active: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    Row(modifier.motionPress(interaction, enabled = active, pressedScale = .985f)
        .clip(shape)
        .clickable(interactionSource = interaction, indication = null, enabled = active, role = Role.Button,
            onClickLabel = "查看$title", onClick = onClick)
        .heightIn(min = 56.dp).padding(start = 11.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(18.dp), tint = colors.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
            Text(supporting, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
