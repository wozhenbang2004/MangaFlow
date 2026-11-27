package dev.mangaflow.anime.ui

import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.anime.AnimeEpisode
import dev.mangaflow.anime.AnimeEpisodeKind
import dev.mangaflow.anime.AnimeEpisodeAvailability
import dev.mangaflow.anime.AnimeWatchProgress
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.DesktopContextMenuArea
import dev.mangaflow.ui.DesktopMenuAction
import dev.mangaflow.ui.mangaMetricValueStyle
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
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
    titleFirst: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember(episode.id) { MutableInteractionSource() }
    val selectedEpisode = selectionLabel != null
    val motion = LocalMotionEnabled.current
    val selection = key(episode.subjectId, episode.id) {
        animateFloatAsState(if (selectedEpisode) 1f else 0f,
            if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else snap(),
            label = "episode-selection")
    }
    val showPlayMark by remember(selection) { derivedStateOf { selection.value > .005f } }
    val label = animeEpisodeLabel(episode)
    val showNumber = !listRow && episode.kind == AnimeEpisodeKind.MAIN && !episode.subjectId.startsWith("native:")
    val subtitle = animeEpisodeSubtitle(episode)
    val sourceState = when (availability) {
        AnimeEpisodeAvailability.UPCOMING -> "尚未放送"
        AnimeEpisodeAvailability.RECENTLY_FAILED -> "线路未就绪"
        AnimeEpisodeAvailability.CHECKING -> "正在找线路"
        AnimeEpisodeAvailability.UNMATCHED -> "暂无匹配线路"
        AnimeEpisodeAvailability.MATCHED -> null
    }
    val headline = if (titleFirst) subtitle ?: label else if (showNumber) animeEpisodeNumber(episode.number) else label
    val caption = if (titleFirst && subtitle != null) listOfNotNull(label, sourceState ?: selectionLabel).joinToString(" · ")
    else when (availability) {
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
    val ink = when {
        selectedEpisode -> colors.onPrimaryContainer
        watched -> colors.onSurfaceVariant
        else -> colors.onSurface
    }
    val shape = RoundedCornerShape(12.dp)
    val surface = if (listRow) Color.Transparent else lerp(colors.surface, colors.onSurface, .035f)
    val playMark: @Composable (Dp) -> Unit = { side ->
        if (showPlayMark) Icon(Icons.Filled.PlayArrow, null, Modifier.size(side).graphicsLayer {
            val amount = selection.value.coerceIn(0f, 1f)
            alpha = amount
            scaleX = .72f + .28f * amount
            scaleY = scaleX
        }, tint = colors.onPrimaryContainer)
    }
    val sourcesLabel = "选择本集线路"
    Box(modifier) {
        DesktopContextMenuArea(if (!enabled || platformKind != PlatformKind.DESKTOP) emptyList() else buildList {
            add(DesktopMenuAction(sourcesLabel, icon = Icons.Outlined.Tune, onClick = onSources))
            if (onMore != null) add(DesktopMenuAction(menuLabel, icon = Icons.Outlined.MoreHoriz, onClick = onMore))
        }) {
            Box(Modifier.fillMaxWidth().motionPress(interaction, enabled = enabled, pressedScale = .98f)
                .clip(shape).drawBehind {
                    drawRect(surface)
                    val amount = selection.value.coerceIn(0f, 1f)
                    if (amount > 0f) {
                        val width = size.width * amount
                        drawRoundRect(colors.primaryContainer,
                            topLeft = Offset(if (layoutDirection == LayoutDirection.Ltr) size.width - width else 0f, 0f),
                            size = Size(width, size.height), cornerRadius = CornerRadius(12.dp.toPx()), alpha = amount)
                    }
                }
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
                        Text(headline, Modifier.weight(1f),
                            style = if (showNumber) mangaMetricValueStyle(19.sp, 24.sp) else MaterialTheme.typography.labelLarge,
                            fontWeight = if (selectedEpisode) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = if (titleFirst || episode.subjectId.startsWith("native:")) 2 else 1,
                            color = ink, overflow = TextOverflow.Ellipsis)
                        if (listRow) playMark(12.dp)
                        if (listRow && hasProgress) AnimeWatchProgressMark(progress, watched,
                            Modifier.clearAndSetSemantics { }, size = 12.dp)
                    }
                    if (caption != null || !listRow && hasProgress) Row(Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        if (caption != null) Text(caption, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                            color = if (selectedEpisode) colors.onPrimaryContainer else colors.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        else Spacer(Modifier.weight(1f))
                        if (!listRow) playMark(10.dp)
                        if (!listRow && hasProgress) AnimeWatchProgressMark(progress, watched,
                            Modifier.clearAndSetSemantics { }, size = 11.dp)
                    }
                }
            }
        }
    }
}
