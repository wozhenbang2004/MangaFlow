package dev.mangaflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import dev.chrisbanes.haze.HazeState

/** Menu navigation and page controls are two independent pieces of captured glass. */
@Composable
internal fun SegmentedGlassHeader(
    backdrop: HazeState,
    navigation: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    controlsWidth: Dp? = null,
    controlsAlpha: () -> Float = { 1f },
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top) {
        LiquidGlassSurface(backdrop, Modifier.size(44.dp), cornerRadius = 22.dp,
            role = GlassSurfaceRole.NAVIGATION, trackPointer = true) { navigation() }
        Box(Modifier.weight(1f), contentAlignment = Alignment.TopEnd) {
            LiquidGlassSurface(backdrop, (if (controlsWidth == null) Modifier.fillMaxWidth() else Modifier.width(controlsWidth)).graphicsLayer {
                alpha = controlsAlpha()
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }, cornerRadius = 22.dp, role = GlassSurfaceRole.NAVIGATION, trackPointer = true) {
                Column(Modifier.fillMaxWidth().pointerInput(Unit) {}, content = content)
            }
        }
    }
}
