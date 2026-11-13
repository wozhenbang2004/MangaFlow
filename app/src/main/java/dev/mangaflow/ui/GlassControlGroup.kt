package dev.mangaflow.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainHeight
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.ui.glass.GlassMemberShape
import dev.mangaflow.ui.glass.GlassMembers
import dev.mangaflow.ui.glass.glassLightTarget

/** Related browse/actions share one capture and optical field; navigation stays independent. */
@Composable
internal fun GlassControlGroup(
    backdrop: HazeState,
    controlsWidth: Dp,
    actionsWidth: Dp,
    alpha: () -> Float,
    actions: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = remember { GlassMemberShape() }
    LiquidGlassSurface(backdrop, Modifier.fillMaxWidth().graphicsLayer {
        this.alpha = alpha()
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }, cornerRadius = 22.dp, role = GlassSurfaceRole.NAVIGATION, trackPointer = false,
        memberShape = shape) {
        Layout(content = { Column(Modifier.glassLightTarget(), content = content)
            Box(Modifier.glassLightTarget()) { actions() } }) { children, constraints ->
            val width = constraints.maxWidth
            val gap = 6.dp.roundToPx()
            val actionWidth = actionsWidth.roundToPx().coerceIn(0, width)
            val controlWidth = controlsWidth.roundToPx().coerceIn(0, (width - actionWidth - gap).coerceAtLeast(0))
            val controls = children[0].measure(Constraints.fixedWidth(controlWidth))
            val tools = children[1].measure(Constraints.fixedWidth(actionWidth))
            val height = constraints.constrainHeight(maxOf(44.dp.roundToPx(), controls.height, tools.height))
            val controlX = if (layoutDirection == LayoutDirection.Ltr) 0 else width - controls.width
            val actionX = if (layoutDirection == LayoutDirection.Ltr) width - tools.width else 0
            shape.members = GlassMembers(
                Rect(controlX.toFloat(), 0f, (controlX + controls.width).toFloat(), controls.height.toFloat()),
                Rect(actionX.toFloat(), 0f, (actionX + tools.width).toFloat(), tools.height.toFloat()),
                22.dp.toPx(), Size(width.toFloat(), height.toFloat()),
            )
            layout(width, height) {
                controls.place(controlX, 0)
                tools.place(actionX, 0)
            }
        }
    }
}
