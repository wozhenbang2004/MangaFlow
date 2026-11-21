package dev.mangaflow.anime.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.AnimeEpisode
import dev.mangaflow.anime.AnimeEpisodeAvailability
import dev.mangaflow.anime.AnimeWatchProgress
import dev.mangaflow.ui.CompactMenuAction
import dev.mangaflow.ui.GlassSurfaceRole
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.motion.*

internal fun animeWatchFraction(progress: AnimeWatchProgress?): Float? = progress?.let {
    if (it.completed) 1f else if (it.durationMs > 0)
        (it.positionMs.toDouble() / it.durationMs).coerceIn(0.0, 1.0).toFloat() else null
}

internal fun animeWatchProgressDescription(progress: AnimeWatchProgress?, completed: Boolean = false): String = when {
    completed || progress?.completed == true -> "已看完"
    progress != null && progress.positionMs > 0 -> "看至 ${animeTime(progress.positionMs)}" +
        if (progress.durationMs > 0) "，共 ${animeTime(progress.durationMs)}" else ""
    else -> "未看"
}

/** Adapted from the actual manga ChapterReadMark; unknown duration never creates a percentage. */
@Composable
internal fun AnimeWatchProgressMark(progress: AnimeWatchProgress?, completed: Boolean,
    modifier: Modifier = Modifier, size: Dp = 14.dp) {
    if (progress == null && !completed) return
    val fraction = animeWatchFraction(progress)
    val color = MaterialTheme.colorScheme.primary
    val done = completed || progress?.completed == true
    val ringStroke = (size.value * .1f).coerceAtLeast(1f).dp
    val dotRadius = (size.value * .13f).dp
    MotionSwap(done, modifier.size(size).semantics {
        contentDescription = animeWatchProgressDescription(progress, completed)
    }) { complete ->
        if (complete) Icon(Icons.Outlined.DoneAll, null, Modifier.size(size), tint = color.copy(alpha = .72f))
        else Canvas(Modifier.size(size)) {
            val stroke = ringStroke.toPx()
            val inset = stroke / 2f
            drawCircle(color.copy(alpha = .18f), radius = (this.size.minDimension - stroke) / 2f, style = Stroke(stroke))
            if (fraction == null) drawCircle(color.copy(alpha = .72f), radius = dotRadius.toPx())
            else if (fraction > 0f) drawArc(color.copy(alpha = .72f), -90f, 360f * fraction, false,
                topLeft = Offset(inset, inset), size = Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

/** The manga DetailReadingDock's continuous size/position/material adaptation for exact video time. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AnimeDetailReadingDock(
    subjectId: String,
    episode: AnimeEpisode?,
    availability: AnimeEpisodeAvailability?,
    previous: AnimeWatchProgress?,
    persistent: Boolean,
    collapseProgress: () -> Float,
    backdrop: HazeState,
    active: Boolean,
    modifier: Modifier = Modifier,
    onHeightChanged: (Int) -> Unit,
    onContinue: () -> Unit,
    onSources: () -> Unit,
    onContents: () -> Unit,
) {
    if (episode == null && previous == null) return
    val motion = LocalMotionEnabled.current
    val colors = MaterialTheme.colorScheme
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val interaction = remember(subjectId) { MutableInteractionSource() }
    var actions by remember(subjectId) { mutableStateOf(false) }
    var actionAfterClose by remember(subjectId) { mutableIntStateOf(0) }
    LaunchedEffect(active) { if (!active) { actions = false; actionAfterClose = 0 } }
    val progress = previous?.takeIf { episode == null || episode.id == it.episodeId }
    val upcoming = availability == AnimeEpisodeAvailability.UPCOMING
    val action = when {
        upcoming -> "尚未放送"
        previous != null && episode != null && episode.id != previous.episodeId -> "播放下一集"
        episode != null && progress?.completed == true -> "重看本集"
        progress != null -> "继续观看"
        else -> "开始观看"
    }
    val episodeLabel = episode?.let(::animeEpisodeLabel) ?: progress?.episodeTitle?.takeIf { it.isNotBlank() } ?: "上次观看集"
    val fraction = animeWatchFraction(progress)
    val displayedFraction by animateFloatAsState(fraction ?: 0f,
        if (motion) tween(MangaMotion.Content, easing = MangaMotion.EnterEasing) else snap(), label = "anime-dock-position")
    val collapse = if (persistent) 0f else if (motion) collapseProgress().coerceIn(0f, 1f)
        else if (collapseProgress() < .5f) 0f else 1f
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val fullWidth = if (persistent) maxWidth else minOf(maxWidth, 280.dp + 40.dp * (scale - 1f).coerceIn(0f, 1f))
        val compactWidth = minOf(fullWidth, 104.dp * scale.coerceAtMost(1.6f))
        Box(Modifier.align(BiasAlignment(collapse, 1f)).width(lerp(fullWidth, compactWidth, collapse))
            .heightIn(min = 48.dp).onSizeChanged { onHeightChanged(it.height) }.motionInputGate(active)) {
            LiquidGlassSurface(backdrop, Modifier.fillMaxWidth().align(Alignment.Center),
                cornerRadius = lerp(28.dp, 18.dp, collapse), role = GlassSurfaceRole.READING_DOCK,
                interactionSource = interaction, trackPointer = true, softCard = true) {
                Row(Modifier.fillMaxWidth().combinedClickable(interactionSource = interaction, indication = null,
                    role = Role.Button, enabled = active, onClick = if (upcoming) onSources else onContinue,
                    onLongClickLabel = "观看选项", onLongClick = { actions = true })
                    .motionPress(interaction, enabled = active, pressedScale = .985f)
                    .heightIn(min = lerp(56.dp, 36.dp, collapse))
                    .padding(horizontal = lerp(16.dp, 10.dp, collapse), vertical = 4.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "$action，$episodeLabel，${animeWatchProgressDescription(progress)}"
                    }, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(lerp(32.dp, 20.dp, collapse)), contentAlignment = Alignment.Center) {
                        if (fraction != null) Canvas(Modifier.matchParentSize().padding(1.dp)) {
                            val stroke = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round)
                            drawArc(colors.primary.copy(alpha = .14f), -90f, 360f, false, style = stroke)
                            drawArc(colors.primary.copy(alpha = .85f), -90f, 360f * displayedFraction, false, style = stroke)
                        }
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(lerp(22.dp, 16.dp, collapse)), tint = colors.primary)
                    }
                    Spacer(Modifier.width(lerp(10.dp, 6.dp, collapse)))
                    Column(Modifier.weight(1f)) {
                        Text(if (collapse < .6f) action else if (upcoming) "待播" else if (progress != null) "继续" else "播放",
                            fontSize = (15f - 3f * collapse).sp, lineHeight = (21f - 4f * collapse).sp,
                            fontWeight = FontWeight.SemiBold, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val reveal = (1f - collapse / .6f).coerceIn(0f, 1f)
                        if (reveal > 0f) Box(Modifier.height(18.dp * scale * reveal).clipToBounds().graphicsLayer { alpha = reveal }) {
                            val time = progress?.takeUnless { it.completed }?.positionMs?.takeIf { it > 0 }?.let(::animeTime)
                            Text(listOfNotNull(episodeLabel, time).joinToString(" · "), fontSize = 12.sp, lineHeight = 17.sp,
                                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (persistent) MotionIconButton(onClick = { actions = true }, modifier = Modifier.size(36.dp), tooltip = "观看选项") {
                        Icon(Icons.Outlined.List, "观看选项", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
    MotionDialog(actions, onDismissRequest = { actions = false; actionAfterClose = 0 }, maximumWidth = 300.dp,
        onClosed = {
            val pending = actionAfterClose; actionAfterClose = 0
            if (active) when (pending) { 1 -> onContents(); 2 -> onSources() }
        }) {
        AnimePanelSurface(backdrop) {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                CompactMenuAction(Icons.Outlined.List, "定位上次观看集", onClick = { actionAfterClose = 1; actions = false })
                CompactMenuAction(Icons.Outlined.Link, "本集来源", onClick = { actionAfterClose = 2; actions = false })
            }
        }
    }
}
