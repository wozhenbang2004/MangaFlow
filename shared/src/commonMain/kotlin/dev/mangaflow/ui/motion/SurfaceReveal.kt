package dev.mangaflow.ui.motion

import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** One interruptible motion owns visibility, material and the closing window's lifetime. */
@Composable
internal fun Transition<Boolean>.surfaceReveal(motion: Boolean, compact: Boolean = false): State<Float> =
    animateFloat(transitionSpec = {
        if (!motion) snap() else spring(dampingRatio = 1f,
            stiffness = if (!targetState) MangaMotion.PressAttackStiffness
                        else if (compact) MangaMotion.PanelStiffness else MangaMotion.NavigationStiffness,
            visibilityThreshold = .007f)
    }, label = "surface-reveal") { if (it) 1f else 0f }

internal fun surfaceRevealAlpha(progress: Float): Float = (progress * 1.55f).coerceIn(0f, 1f)

/** Uncover real menu rows from their button or mouse position, at their final type size. */
internal fun Modifier.anchoredMenuReveal(progress: State<Float>, radius: Dp, origin: () -> TransformOrigin): Modifier =
    graphicsLayer {
        val visible = progress.value.coerceIn(0f, 1f)
        val anchor = origin()
        alpha = surfaceRevealAlpha(visible)
        translationY = (anchor.pivotFractionY * 2f - 1f) * 4.dp.toPx() * (1f - visible)
        clip = visible < 1f
        shape = if (clip) MenuRevealShape(visible, anchor, radius) else RectangleShape
    }

private data class MenuRevealShape(val progress: Float, val origin: TransformOrigin, val radius: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = with(density) {
        val hidden = 1f - progress
        val cutX = minOf(size.width * .28f, 46.dp.toPx()) * hidden
        val firstRow = maxOf(40.dp.toPx(), size.height * .24f).coerceAtMost(size.height)
        val cutY = (size.height - firstRow) * hidden
        val x = cutX * origin.pivotFractionX
        val y = cutY * origin.pivotFractionY
        val rect = Rect(x, y, x + size.width - cutX, y + size.height - cutY)
        val round = minOf(radius.toPx(), minOf(rect.width, rect.height) / 2f)
        Outline.Rounded(RoundRect(rect, CornerRadius(round)))
    }
}
