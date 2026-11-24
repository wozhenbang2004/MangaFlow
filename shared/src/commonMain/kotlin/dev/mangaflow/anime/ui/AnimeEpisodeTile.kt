package dev.mangaflow.anime.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mangaflow.anime.AnimeEpisode
import dev.mangaflow.anime.AnimeEpisodeAvailability
import dev.mangaflow.anime.AnimeWatchProgress
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.DesktopContextMenuArea
import dev.mangaflow.ui.DesktopMenuAction
import dev.mangaflow.ui.motion.motionPress

internal fun animeEpisodeGridColumns(available: Dp, fontScale: Float, wide: Boolean = false): Int {
    val gap = 8.dp
    val minimum = (if (wide) 98.dp else 64.dp) * fontScale.coerceAtLeast(1f)
    return ((available + gap) / (minimum + gap)).toInt().coerceIn(1, if (wide) 7 else 4)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AnimeEpisodeTile(
    episode: AnimeEpisode,
    availability: AnimeEpisodeAvailability,
    progress: AnimeWatchProgress?,
    watched: Boolean,
    selectionLabel: String?,
    enabled: Boolean,
    onPlay: () -> Unit,
    onSources: () -> Unit,
    modifier: Modifier = Modifier,
    listRow: Boolean = false,
    onMore: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember(episode.id) { MutableInteractionSource() }
    val selectedEpisode = selectionLabel != null
    val label = animeEpisodeLabel(episode)
    val subtitle = animeEpisodeSubtitle(episode)
    val sourceState = when (availability) {
        AnimeEpisodeAvailability.UPCOMING -> "尚未放送"
        AnimeEpisodeAvailability.RECENTLY_FAILED -> "线路未就绪"
        AnimeEpisodeAvailability.CHECKING -> "正在找线路"
        AnimeEpisodeAvailability.UNMATCHED -> "暂无匹配线路"
        AnimeEpisodeAvailability.MATCHED -> null
    }
    val caption = when (availability) {
        AnimeEpisodeAvailability.UPCOMING, AnimeEpisodeAvailability.RECENTLY_FAILED -> sourceState
        else -> subtitle ?: selectionLabel ?: sourceState.takeIf { listRow }
    }
    val menuLabel = if (onMore != null) "本集操作" else "选择本集线路"
    val menu = onMore ?: onSources
    val actionLabel = when (availability) {
        AnimeEpisodeAvailability.UPCOMING -> "查看 $label 的放送与线路"
        AnimeEpisodeAvailability.CHECKING, AnimeEpisodeAvailability.UNMATCHED -> "查找 $label 的片源并播放"
        AnimeEpisodeAvailability.RECENTLY_FAILED -> "尝试播放 $label"
        AnimeEpisodeAvailability.MATCHED -> "播放 $label"
    }
    val ink = if (watched && !selectedEpisode) colors.onSurfaceVariant else colors.onSurface
    val shape = RoundedCornerShape(14.dp)
    val surface = when {
        selectedEpisode -> colors.onSurface.copy(alpha = .105f)
        listRow -> Color.Transparent
        else -> colors.surfaceContainerLow
    }
    val sourcesLabel = "选择本集线路"
    Box(modifier) {
        DesktopContextMenuArea(if (!enabled || platformKind != PlatformKind.DESKTOP) emptyList() else buildList {
            add(DesktopMenuAction(sourcesLabel, onClick = onSources))
            if (onMore != null) add(DesktopMenuAction(menuLabel, onClick = onMore))
        }) {
            Box(Modifier.fillMaxWidth().motionPress(interaction, enabled = enabled, pressedScale = .98f)
                .clip(shape).background(surface)
                .combinedClickable(interactionSource = interaction, indication = null, enabled = enabled,
                    role = Role.Button, onClickLabel = actionLabel,
                    onClick = if (availability == AnimeEpisodeAvailability.UPCOMING) onSources else onPlay,
                    onLongClickLabel = menuLabel, onLongClick = menu)
                .semantics(mergeDescendants = true) {
                    selected = selectedEpisode
                    contentDescription = listOfNotNull(label, subtitle).joinToString("，")
                    stateDescription = listOfNotNull(selectionLabel, animeWatchProgressDescription(progress, watched),
                        sourceState).joinToString("，")
                    if (enabled) customActions = listOf(CustomAccessibilityAction(sourcesLabel) { onSources(); true })
                }
                .heightIn(min = if (listRow) 52.dp else 48.dp)
                .padding(horizontal = if (listRow) 12.dp else 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.CenterStart) {
                val hasProgress = watched || progress != null
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        if (listRow && selectedEpisode) Icon(Icons.Filled.PlayArrow, null, Modifier.size(12.dp), tint = ink)
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum",
                            fontWeight = if (selectedEpisode) FontWeight.Bold else FontWeight.Medium),
                            maxLines = if (episode.subjectId.startsWith("native:")) 2 else 1,
                            color = ink, overflow = TextOverflow.Ellipsis)
                        if (listRow && hasProgress) AnimeWatchProgressMark(progress, watched,
                            Modifier.clearAndSetSemantics { }, size = 12.dp)
                    }
                    if (caption != null || !listRow && hasProgress) Row(Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        if (!listRow && selectedEpisode) Icon(Icons.Filled.PlayArrow, null, Modifier.size(10.dp), tint = ink)
                        if (caption != null) Text(caption, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        else Spacer(Modifier.weight(1f))
                        if (!listRow && hasProgress) AnimeWatchProgressMark(progress, watched,
                            Modifier.clearAndSetSemantics { }, size = 11.dp)
                    }
                }
            }
        }
    }
}
