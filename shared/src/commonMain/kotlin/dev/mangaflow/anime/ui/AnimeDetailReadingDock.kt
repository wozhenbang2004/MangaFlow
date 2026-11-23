package dev.mangaflow.anime.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mangaflow.anime.AnimeEpisode
import dev.mangaflow.anime.AnimeEpisodeAvailability
import dev.mangaflow.anime.AnimeWatchProgress
import dev.mangaflow.ui.motion.MotionButton
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionSwap

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

/** The same action is used at phone, tablet and desktop sizes. */
@Composable
internal fun AnimeDetailPlayAction(
    episode: AnimeEpisode?,
    availability: AnimeEpisodeAvailability?,
    previous: AnimeWatchProgress?,
    loading: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier,
    onContinue: () -> Unit,
    onSources: () -> Unit,
) {
    if (episode == null && previous == null && !loading) return
    val colors = MaterialTheme.colorScheme
    val progress = previous?.takeIf { episode == null || episode.id == it.episodeId }
    val upcoming = availability == AnimeEpisodeAvailability.UPCOMING
    val pending = episode == null && previous == null
    val action = when {
        pending -> "正在加载剧集"
        upcoming -> "尚未放送"
        previous != null && episode != null && episode.id != previous.episodeId -> "播放下一集"
        episode != null && progress?.completed == true -> "重看本集"
        progress != null -> "继续观看"
        else -> "播放"
    }
    val episodeLabel = episode?.let(::animeEpisodeLabel)
        ?: progress?.episodeTitle?.takeIf { it.isNotBlank() }
        ?: "上次观看集".takeIf { progress != null }
    val time = progress?.takeUnless { it.completed }?.positionMs?.takeIf { it > 0L }?.let(::animeTime)
    val caption = listOfNotNull(episodeLabel, time?.let { "看至 $it" }).joinToString(" · ")
    val ink = if (upcoming || pending) colors.onSurfaceVariant else colors.surface
    MotionButton(
        onClick = if (upcoming) onSources else onContinue,
        modifier = modifier.heightIn(min = 56.dp),
        enabled = active && !pending,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (upcoming) colors.surfaceContainerHigh else colors.onSurface,
            contentColor = ink,
            disabledContainerColor = colors.surfaceContainerHigh,
            disabledContentColor = colors.onSurfaceVariant,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
    ) {
        if (pending) MotionBusyIndicator(Modifier.size(18.dp), color = ink, strokeWidth = 1.5.dp)
        else Icon(if (upcoming) Icons.Outlined.Schedule else Icons.Filled.PlayArrow, null, Modifier.size(21.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(action, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (caption.isNotEmpty()) Text(caption, style = MaterialTheme.typography.labelSmall,
                color = ink.copy(alpha = .78f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
