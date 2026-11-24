package dev.mangaflow.anime.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.AnimeEpisode
import dev.mangaflow.anime.AnimeEpisodeAvailability
import dev.mangaflow.anime.AnimeWatchProgress
import dev.mangaflow.anime.downloads.AnimeDownloadStatus
import dev.mangaflow.anime.downloads.AnimeDownloadsState
import dev.mangaflow.ui.GlassSurfaceRole
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionSwap
import dev.mangaflow.ui.motion.motionPress

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

internal enum class AnimeDetailOffline { CHECKING, READY, MISSING }

/** This read never opens a file. The existing download owner validates bytes again at playback. */
internal fun animeDetailOffline(snapshot: AnimeDownloadsState, subjectId: String, progress: AnimeWatchProgress): AnimeDetailOffline = when {
    snapshot.loading -> AnimeDetailOffline.CHECKING
    snapshot.entries.any { row -> row.id == progress.localDownloadId && row.status == AnimeDownloadStatus.COMPLETED &&
        row.subject.id == subjectId && row.episode.subjectId == subjectId && row.episode.id == progress.episodeId &&
        row.assetId == progress.assetId && row.sourceId == progress.sourceId } -> AnimeDetailOffline.READY
    else -> AnimeDetailOffline.MISSING
}

internal enum class AnimeDetailPlayMode { PLAY, FIND, SOURCES, UPCOMING, WAIT }

internal data class AnimeDetailPlayControl(val title: String, val caption: String, val mode: AnimeDetailPlayMode)

internal fun animeDetailPlayControl(episode: AnimeEpisode?, previous: AnimeWatchProgress?,
    availability: AnimeEpisodeAvailability?, checking: Boolean, canRetry: Boolean,
    offline: AnimeDetailOffline?, resumePositionKnown: Boolean): AnimeDetailPlayControl? {
    // The catalogue already owns the empty/loading view. A saved record, however, stays discoverable.
    if (episode == null && previous == null) return null
    val next = previous != null && episode != null && episode.id != previous.episodeId
    val replay = !next && previous?.completed == true
    val progress = previous.takeUnless { next }
    val episodeLabel = episode?.let(::animeEpisodeLabel)
        ?: progress?.episodeTitle?.takeIf(String::isNotBlank) ?: "上次观看集"
    val resuming = progress != null && !replay && resumePositionKnown
    val time = progress?.positionMs?.takeIf { resuming && it > 0L }?.let(::animeTime)
    val caption = listOfNotNull(episodeLabel, time?.let { "看至 $it" },
        "上次位置未确认".takeIf { progress != null && !replay && !resuming && episode != null }).joinToString(" · ")
    val playback = when {
        next -> "播放下一集"
        replay -> "重看本集"
        resuming -> "继续观看"
        progress != null -> "播放本集"
        else -> "播放"
    }
    fun control(title: String, mode: AnimeDetailPlayMode, note: String? = null) =
        AnimeDetailPlayControl(title, listOfNotNull(caption, note).joinToString(" · "), mode)
    return when {
        offline == AnimeDetailOffline.CHECKING -> control("正在核对离线记录", AnimeDetailPlayMode.WAIT)
        offline == AnimeDetailOffline.READY -> control(playback, AnimeDetailPlayMode.PLAY, "离线")
        offline == AnimeDetailOffline.MISSING -> control(if (episode == null) "查看作品线路" else "查看本集线路",
            AnimeDetailPlayMode.SOURCES, "原离线记录暂不可用")
        episode == null && checking -> control("正在确认上次观看集", AnimeDetailPlayMode.WAIT)
        episode == null -> control("查看作品线路", AnimeDetailPlayMode.SOURCES, "上次观看集未确认")
        availability == AnimeEpisodeAvailability.UPCOMING -> control("尚未放送", AnimeDetailPlayMode.UPCOMING)
        availability == AnimeEpisodeAvailability.MATCHED -> control(playback, AnimeDetailPlayMode.PLAY)
        availability == AnimeEpisodeAvailability.RECENTLY_FAILED -> if (canRetry)
            control(when {
                next -> "重试播放下一集"
                resuming -> "重试续看"
                else -> "重试播放"
            }, AnimeDetailPlayMode.PLAY)
        else control("查看本集线路", AnimeDetailPlayMode.SOURCES, "线路暂不可用")
        else -> control(when {
            next -> "找片源后看下一集"
            replay -> "找片源后重看"
            resuming -> "找片源后续看"
            else -> "找片源并播放"
        }, AnimeDetailPlayMode.FIND)
    }
}

/** Adapted from the ordinary manga reading dock; one action, independent of the artwork scroll. */
@Composable
internal fun AnimeDetailPlayAction(
    control: AnimeDetailPlayControl,
    active: Boolean,
    compact: Boolean,
    backdrop: HazeState,
    modifier: Modifier = Modifier,
    onActivate: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val enabled = active && control.mode != AnimeDetailPlayMode.WAIT
    LiquidGlassSurface(backdrop, modifier.motionPress(interaction, enabled = enabled, pressedScale = .985f),
        cornerRadius = if (compact) 22.dp else 26.dp, role = GlassSurfaceRole.READING_DOCK, preferSource = true,
        interactionSource = interaction.takeIf { enabled }, elevation = 2.dp, softCard = true) {
        val ink = if (enabled) LocalContentColor.current else colors.onSurfaceVariant
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (compact) 48.dp else 56.dp)
                .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction,
                    indication = null, onClickLabel = if (control.mode == AnimeDetailPlayMode.UPCOMING)
                        "查看放送与本集线路" else control.title, onClick = onActivate)
                .padding(start = 10.dp, end = 14.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(if (compact) 28.dp else 32.dp).background(ink, RoundedCornerShape(50)),
                contentAlignment = Alignment.Center) {
                if (control.mode == AnimeDetailPlayMode.WAIT)
                    MotionBusyIndicator(Modifier.size(16.dp), color = colors.surface, strokeWidth = 1.5.dp)
                else Icon(when (control.mode) {
                    AnimeDetailPlayMode.FIND -> Icons.Outlined.Search
                    AnimeDetailPlayMode.SOURCES -> Icons.Outlined.Link
                    AnimeDetailPlayMode.UPCOMING -> Icons.Outlined.Schedule
                    else -> Icons.Filled.PlayArrow
                }, null, Modifier.size(19.dp), tint = colors.surface)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(control.title, color = ink, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(control.caption, style = MaterialTheme.typography.labelSmall,
                    color = ink, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
