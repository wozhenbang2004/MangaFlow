package dev.mangaflow.anime.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.mangaflow.ui.mangaMetricValueStyle
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import kotlinx.coroutines.delay

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

/** A point is relative to the video itself, so resizing never leaves a stale pixel coordinate. */
internal class AnimeSeekCue(val positionMs: Long, val forward: Boolean, val point: Offset?)

@Composable
internal fun BoxScope.AnimeSeekFeedback(notice: MutableState<AnimeSeekCue?>, enabled: Boolean) {
    val cue = notice.value ?: return
    val motion = LocalMotionEnabled.current
    val alpha = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(cue, enabled, motion) {
        if (enabled) {
            if (motion) alpha.animateTo(1f, tween(95, easing = MangaMotion.EnterEasing)) else alpha.snapTo(1f)
            delay(520)
            if (motion) alpha.animateTo(0f, tween(140, easing = MangaMotion.ExitEasing))
        }
        if (notice.value === cue) notice.value = null
    }
    if (!enabled) return
    val forward = cue.forward
    val x = (cue.point?.x ?: if (forward) .77f else .23f).coerceIn(.08f, .92f)
    val y = ((cue.point?.y ?: .52f) - .10f).coerceIn(.22f, .70f)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val alignment = BiasAlignment((2f * x - 1f) * if (rtl) -1f else 1f, 2f * y - 1f)
    val time = animeTime(cue.positionMs)
    val colors = MaterialTheme.colorScheme
    Row(Modifier.align(alignment).graphicsLayer {
        val amount = alpha.value
        this.alpha = amount
        translationX = if (motion) (1f - amount) * (if (forward) -6.dp.toPx() else 6.dp.toPx()) else 0f
    }.background(colors.surface.copy(alpha = .94f), RoundedCornerShape(50))
        .clearAndSetSemantics { liveRegion = LiveRegionMode.Polite; contentDescription = "跳至 $time" }
        .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(if (forward) Icons.Outlined.FastForward else Icons.Outlined.FastRewind, null,
            Modifier.size(16.dp), tint = colors.primary)
        Text(time, color = colors.onSurface, style = mangaMetricValueStyle(), maxLines = 1)
    }
}
