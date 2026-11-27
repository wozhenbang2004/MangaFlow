package dev.mangaflow.ui.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Saved press-stretch switch drawing; the caller retains its full accessible toggle row. */
@Composable
internal fun MotionSwitchThumb(checked: Boolean, interaction: MutableInteractionSource) {
    val colors = MaterialTheme.colorScheme
    val pressed by interaction.collectIsPressedAsState()
    val motion = LocalMotionEnabled.current
    val dark = colors.surface.luminance() < .5f
    val contrast = LocalPlatformAccessibilityPreferences.current.increaseContrast
    val offTrack = lerp(colors.surfaceContainerHigh, colors.onSurfaceVariant, if (contrast) .5f else .18f)
    val offThumb = if (dark) colors.onSurfaceVariant else colors.surface
    val target = if (checked) 1f else 0f
    val leading = if (motion) spring<Float>(MangaMotion.PressDamping, MangaMotion.PressStiffness) else snap<Float>()
    val trailing = if (motion) spring<Float>(MangaMotion.GestureDamping, MangaMotion.GestureStiffness) else snap<Float>()
    val start = animateFloatAsState(target, if (checked) trailing else leading, label = "switch-start")
    val end = animateFloatAsState(target, if (checked) leading else trailing, label = "switch-end")
    val pressure = animateFloatAsState(if (pressed && motion) 1f else 0f,
        if (motion && pressed) spring(1f, MangaMotion.PressAttackStiffness) else trailing, label = "switch-press")
    Box(Modifier.width(36.dp).height(22.dp).drawBehind {
        val inset = minOf(2.dp.toPx(), size.minDimension / 2f)
        val diameter = (size.height - 2f * inset).coerceIn(0f, (size.width - 2f * inset).coerceAtLeast(0f))
        val travel = (size.width - diameter - 2f * inset).coerceAtLeast(0f)
        val from = start.value.coerceIn(0f, 1f)
        val to = end.value.coerceIn(0f, 1f)
        val position = (from + to) / 2f
        val contact = pressure.value.coerceIn(0f, 1f)
        val pressedWidth = 3.dp.toPx() * contact
        // At rest the outer end stays seated. On a throw, the near end arrives first
        // and the other catches it, including when the user reverses the switch.
        val left = (inset + travel * from - pressedWidth * position).coerceAtLeast(inset)
        val right = (inset + diameter + travel * to + pressedWidth * (1f - position)).coerceAtMost(size.width - inset)
        val h = diameter * (1f - .12f * abs(to - from) - .045f * contact)
        val x = if (layoutDirection == LayoutDirection.Rtl) size.width - right else left
        val y = (size.height - h) / 2f
        val thumbSize = Size((right - left).coerceAtLeast(0f), h)
        val radius = CornerRadius(h / 2f)
        drawRoundRect(lerp(offTrack, colors.primary, position), cornerRadius = CornerRadius(size.height / 2f))
        drawRoundRect(Color.Black.copy(alpha = if (dark) .22f else .13f),
            topLeft = Offset(x, y + .75.dp.toPx() * (1f - contact)), size = thumbSize, cornerRadius = radius)
        drawRoundRect(lerp(offThumb, colors.onPrimary, position), topLeft = Offset(x, y),
            size = thumbSize, cornerRadius = radius)
    })
}
