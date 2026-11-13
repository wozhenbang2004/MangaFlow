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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Saved press-stretch switch drawing; the caller retains its full accessible toggle row. */
@Composable
internal fun MotionSwitchThumb(checked: Boolean, interaction: MutableInteractionSource) {
    val colors = MaterialTheme.colorScheme
    val pressed by interaction.collectIsPressedAsState()
    val motion = LocalMotionEnabled.current
    val position = animateFloatAsState(if (checked) 1f else 0f,
        if (motion) spring(MangaMotion.SwitchDamping, MangaMotion.SwitchStiffness) else snap(), label = "switch-position")
    val width = animateFloatAsState(if (pressed && motion) 21f else 18f,
        if (motion) spring(MangaMotion.SwitchDamping, MangaMotion.SwitchStiffness) else snap(), label = "switch-press")
    Box(Modifier.width(36.dp).height(22.dp).drawBehind {
        val p = position.value.coerceIn(0f, 1f)
        drawRoundRect(lerp(colors.surfaceContainerHigh, colors.primary, p), cornerRadius = CornerRadius(size.height / 2f))
        val w = width.value.dp.toPx()
        val x = 2.dp.toPx() + (size.width - 4.dp.toPx() - w) * if (layoutDirection == LayoutDirection.Rtl) 1f - p else p
        drawRoundRect(lerp(colors.surface, colors.onPrimary, p), topLeft = Offset(x, 2.dp.toPx()),
            size = Size(w, 18.dp.toPx()), cornerRadius = CornerRadius(9.dp.toPx()))
    })
}
