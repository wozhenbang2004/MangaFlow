package dev.mangaflow.anime.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.anime.player.AnimePlayerPhase
import dev.mangaflow.anime.player.AnimePlayerState
import dev.mangaflow.ui.mangaMetricValueStyle
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** The pointer inspects a time without issuing a seek or registering an extra video observation. */
@Composable
internal fun AnimeTimelineSlot(desktop: Boolean, duration: Long, modifier: Modifier, content: @Composable (Modifier) -> Unit) {
    if (!desktop) {
        content(modifier)
        return
    }
    val pointer = remember(duration) { mutableStateOf<Float?>(null) }
    Box(modifier.pointerInput(pointer) {
        val inset = 14.dp.toPx()
        try {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val mouse = event.changes.firstOrNull { it.type == PointerType.Mouse }
                    pointer.value = if (mouse != null && !mouse.pressed && !mouse.previousPressed &&
                        (event.type == PointerEventType.Move || event.type == PointerEventType.Enter)) {
                        ((mouse.position.x - inset) / (size.width - 2f * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    } else null
                }
            }
        } finally { pointer.value = null }
    }) {
        content(Modifier.fillMaxWidth())
        AnimeTimelineHoverTime(pointer, duration)
    }
}

@Composable
private fun BoxScope.AnimeTimelineHoverTime(pointer: State<Float?>, duration: Long) {
    val visible by remember(pointer) { derivedStateOf { pointer.value != null } }
    if (!visible) return
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val label by remember(pointer, rtl, duration) { derivedStateOf {
        val physical = pointer.value ?: 0f
        animeTime(((if (rtl) 1f - physical else physical).toDouble() * duration).toLong())
    } }
    val colors = MaterialTheme.colorScheme
    // Both the marker and the label follow the same thumb-centre interval. Only text
    // crossing a second boundary recomposes; a subsecond move just places and draws.
    Canvas(Modifier.matchParentSize().clearAndSetSemantics { }) {
        val physical = pointer.value ?: return@Canvas
        val inset = minOf(14.dp.toPx(), size.width / 2f)
        val x = inset + (size.width - 2f * inset) * physical
        drawLine(colors.onSurfaceVariant.copy(alpha = .76f), Offset(x, center.y - 5.dp.toPx()),
            Offset(x, center.y + 5.dp.toPx()), strokeWidth = 1.5.dp.toPx())
    }
    Layout(modifier = Modifier.matchParentSize().clearAndSetSemantics { }, content = {
        Text(label, Modifier.background(colors.inverseSurface, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp),
            color = colors.inverseOnSurface, maxLines = 1, style = mangaMetricValueStyle(11.sp, 15.sp))
    }) { children, bounds ->
        val chip = children.single().measure(bounds.copy(minWidth = 0, minHeight = 0))
        layout(bounds.maxWidth, bounds.maxHeight) {
            val inset = minOf(14.dp.toPx(), bounds.maxWidth / 2f)
            val center = inset + (bounds.maxWidth - 2f * inset) * (pointer.value ?: 0f)
            chip.place((center - chip.width / 2f).roundToInt().coerceIn(0, bounds.maxWidth - chip.width), 0)
        }
    }
}

/** The two halves of the play triangle separate into the pause bars around the same centre. */
@Composable
internal fun AnimeTransportGlyph(playing: Boolean, label: String, color: Color, modifier: Modifier) {
    val motion = LocalMotionEnabled.current
    val separation = animateFloatAsState(if (playing) 1f else 0f,
        if (motion) spring(dampingRatio = .94f, stiffness = 880f) else snap(), label = "play-pause")
    val contour = remember { Path() }
    Canvas(modifier.semantics { contentDescription = label }) {
        val t = separation.value.coerceIn(0f, 1f)
        fun mix(play: Float, pause: Float) = play + (pause - play) * t
        val left = mix(6.4f, 5.4f)
        val leftEdge = mix(12.5f, 10.1f)
        val rightEdge = mix(12.5f, 13.9f)
        val right = mix(20f, 18.6f)
        val top = mix(7.59f, 4f)
        val bottom = 24f - top
        contour.reset()
        contour.moveTo(left, 4f)
        contour.lineTo(leftEdge, top)
        contour.lineTo(leftEdge, bottom)
        contour.lineTo(left, 20f)
        contour.close()
        contour.moveTo(rightEdge, top)
        contour.lineTo(right, mix(12f, 4f))
        contour.lineTo(right, mix(12f, 20f))
        contour.lineTo(rightEdge, bottom)
        contour.close()
        scale(size.minDimension / 24f, pivot = Offset.Zero) { drawPath(contour, color) }
    }
}

/** Event-only state owned by a single player, asset and episode. It never subscribes to autoplay. */
internal sealed interface AnimePlayerCue {
    class Seek(val positionMs: Long, val forward: Boolean?, val distanceMs: Long, val atLimit: Boolean,
        val point: Offset?, private val inputTimeline: Long, val acceptedTimeline: Long?, private val inputTime: Long) : AnimePlayerCue {
        fun ownsInput(from: Long, live: Long, now: Long): Boolean =
            acceptedTimeline == live && from in inputTimeline..live && now - inputTime in 0L..680L

        fun canContinue(timeline: Long, now: Long): Boolean =
            forward != null && ownsInput(timeline, timeline, now)
    }

    class Transport(val resume: Boolean, val restart: Boolean) : AnimePlayerCue
    class Volume(val percent: Int) : AnimePlayerCue
}

private fun seekDistanceLabel(distanceMs: Long, forward: Boolean): String {
    val tenths = abs(distanceMs) / 100L
    val seconds = when {
        tenths == 0L -> "<0.1"
        tenths % 10L == 0L -> (tenths / 10L).toString()
        else -> "${tenths / 10L}.${tenths % 10L}"
    }
    return (if (forward) "+" else "−") + seconds + " 秒"
}

@Composable
internal fun BoxScope.AnimePlayerFeedback(notice: MutableState<AnimePlayerCue?>, enabled: Boolean,
    desktop: Boolean, observations: State<AnimePlayerState>) {
    val cue = notice.value ?: return
    val motion = LocalMotionEnabled.current
    val alpha = remember { Animatable(if (motion) 0f else 1f) }
    val currentCue by remember(cue, observations) { derivedStateOf {
        val generation = (cue as? AnimePlayerCue.Seek)?.acceptedTimeline
        generation == null || observations.value.timelineGeneration <= generation
    } }
    val visible = enabled && currentCue
    LaunchedEffect(cue, visible, motion) {
        if (visible) {
            if (motion) alpha.animateTo(1f, tween(95, easing = MangaMotion.EnterEasing)) else alpha.snapTo(1f)
            delay(if (desktop) 860L else 520L)
            if (motion) alpha.animateTo(0f, tween(140, easing = MangaMotion.ExitEasing))
        }
        if (notice.value === cue) notice.value = null
    }
    if (!visible) return
    val colors = MaterialTheme.colorScheme
    val seek = cue as? AnimePlayerCue.Seek
    val forward = seek?.forward
    val x = (seek?.point?.x ?: when {
        forward == true -> .77f
        forward == false -> .23f
        cue is AnimePlayerCue.Volume -> .73f
        else -> .5f
    }).coerceIn(.08f, .92f)
    val y = seek?.point?.let { (it.y - .1f).coerceIn(.22f, .70f) } ?: when (cue) {
        is AnimePlayerCue.Volume -> .62f
        is AnimePlayerCue.Transport -> .48f
        is AnimePlayerCue.Seek -> if (forward == null) .63f else .42f
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val align = BiasAlignment((2f * x - 1f) * if (rtl) -1f else 1f, 2f * y - 1f)
    val label by remember(cue, observations, desktop) { derivedStateOf {
        when (cue) {
            is AnimePlayerCue.Seek -> when {
                !desktop || cue.forward == null -> animeTime(cue.positionMs)
                cue.atLimit -> if (cue.forward == true) "已在片尾" else "已在片头"
                else -> seekDistanceLabel(cue.distanceMs, cue.forward == true)
            }
            is AnimePlayerCue.Volume -> {
                val accepted = (observations.value.volume * 100f).roundToInt() == cue.percent
                if (!accepted) "目标音量 ${cue.percent}%"
                else if (cue.percent == 0) "静音" else "音量 ${cue.percent}%"
            }
            is AnimePlayerCue.Transport -> {
                val current = observations.value
                when {
                    current.playWhenReady != cue.resume -> if (cue.resume) "请求播放" else "请求暂停"
                    !cue.resume -> "暂停"
                    current.playbackSuppressed -> "等待恢复播放"
                    current.isBuffering || current.phase == AnimePlayerPhase.OPENING -> "等待缓冲"
                    cue.restart -> "从头播放"
                    else -> "继续播放"
                }
            }
        }
    } }
    val time = seek?.let { animeTime(it.positionMs) }
    val showTarget = desktop && seek?.forward != null
    val announcement = if (showTarget) "$label，目标 $time" else if (seek != null) "目标 $time" else label
    Row(Modifier.align(align).graphicsLayer {
        val amount = alpha.value
        this.alpha = amount
        translationX = if (motion) (1f - amount) * when (forward) {
            true -> -6.dp.toPx()
            false -> 6.dp.toPx()
            null -> 0f
        } else 0f
        translationY = if (motion && forward == null) (1f - amount) * 3.dp.toPx() else 0f
    }.background(colors.surface.copy(alpha = .95f), RoundedCornerShape(50))
        .clearAndSetSemantics { liveRegion = LiveRegionMode.Polite; contentDescription = announcement }
        .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val glyph = when (cue) {
            is AnimePlayerCue.Seek -> when (cue.forward) {
                true -> Icons.Outlined.FastForward
                false -> Icons.Outlined.FastRewind
                null -> Icons.Outlined.Schedule
            }
            is AnimePlayerCue.Transport -> when {
                cue.restart -> Icons.Outlined.Replay
                cue.resume -> Icons.Outlined.PlayArrow
                else -> Icons.Outlined.Pause
            }
            is AnimePlayerCue.Volume -> if (cue.percent == 0) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp
        }
        val stepMotion = if (desktop && motion && seek != null && forward != null && !seek.atLimit) {
            val step = remember { Animatable(0f) }
            LaunchedEffect(seek) {
                step.snapTo(0f)
                step.animateTo(1f, tween(MangaMotion.Quick, easing = MangaMotion.EnterEasing))
            }
            Modifier.graphicsLayer {
                translationX = (1f - step.value) * if (forward) -3.dp.toPx() else 3.dp.toPx()
            }
        } else Modifier
        Icon(glyph, null, Modifier.size(17.dp).then(stepMotion), tint = colors.primary)
        Text(label, color = colors.onSurface, style = mangaMetricValueStyle(), maxLines = 1)
        if (showTarget && time != null) Text(time, color = colors.onSurfaceVariant,
            style = mangaMetricValueStyle(12.sp, 16.sp), maxLines = 1)
        if (cue is AnimePlayerCue.Volume) {
            val volume = animateFloatAsState(cue.percent / 100f,
                if (motion) spring(.96f, MangaMotion.NavigationStiffness) else snap(), label = "player-volume")
            Canvas(Modifier.size(32.dp, 3.dp)) {
                val corners = CornerRadius(size.height / 2f)
                drawRoundRect(colors.onSurface.copy(alpha = .14f), cornerRadius = corners)
                val width = size.width * volume.value.coerceIn(0f, 1f)
                if (width > 0f) drawRoundRect(colors.primary,
                    topLeft = Offset(if (rtl) size.width - width else 0f, 0f),
                    size = Size(width, size.height), cornerRadius = corners)
            }
        }
    }
}
