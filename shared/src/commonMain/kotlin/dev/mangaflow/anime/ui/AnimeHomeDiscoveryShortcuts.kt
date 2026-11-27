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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.glass.LocalGlassAccessibilitySettings
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
            todayItems.size == 1 -> "今日 · ${todayItems.first().title}"
            todayItems.size > 1 -> "今日放送 · ${todayItems.size} 部"
            else -> "周历与季度"
        }
    }
    val colors = MaterialTheme.colorScheme
    val accessibility = LocalGlassAccessibilitySettings.current
    val solid = accessibility.increaseContrast || accessibility.reduceTransparency
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        val links: @Composable (Modifier) -> Unit = { item ->
            val surface = if (solid) item.background(colors.surface, RoundedCornerShape(14.dp)) else item
            onSchedule?.let {
                AnimeHomeShortcut("放送", scheduleCaption, Icons.Outlined.CalendarMonth,
                    if (solid) colors.onPrimaryContainer else colors.primary,
                    if (solid) colors.primaryContainer else colors.primary.copy(alpha = .11f), surface, active, it)
            }
            onRanking?.let {
                AnimeHomeShortcut("榜单", "热门 · 高分", Icons.Outlined.Leaderboard,
                    if (solid) colors.onTertiaryContainer else colors.tertiary,
                    if (solid) colors.tertiaryContainer else colors.tertiary.copy(alpha = .11f), surface, active, it)
            }
        }
        if (onSchedule != null && onRanking != null && maxWidth < 255.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)) {
            Column(Modifier.fillMaxWidth()) { links(Modifier.fillMaxWidth()) }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            links(Modifier.weight(1f))
        }
    }
}

@Composable
private fun AnimeHomeShortcut(title: String, supporting: String, icon: ImageVector,
    ink: Color, tile: Color, modifier: Modifier, active: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(modifier.clip(RoundedCornerShape(14.dp))
        .clickable(interactionSource = interaction, indication = null, enabled = active, role = Role.Button,
            onClickLabel = "查看$title", onClick = onClick)
        .motionPress(interaction, enabled = active, pressedScale = .985f)
        .heightIn(min = 54.dp).padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.size(32.dp).background(tile, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(19.dp), tint = ink)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface, fontWeight = FontWeight.SemiBold)
            Text(supporting, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
