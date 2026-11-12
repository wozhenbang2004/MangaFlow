package dev.mangaflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState

internal val HeaderContentGap = 2.dp

/** Page identity stays beside navigation. Only real actions receive a separate glass surface. */
@Composable
internal fun PageTitleHeader(
    title: String,
    backdrop: HazeState,
    navigation: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    actions: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LiquidGlassSurface(backdrop, Modifier.size(44.dp), cornerRadius = 22.dp,
            role = GlassSurfaceRole.NAVIGATION, trackPointer = true) { navigation() }
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() }.padding(vertical = 6.dp))
        if (actions != null) LiquidGlassSurface(backdrop, cornerRadius = 22.dp,
            role = GlassSurfaceRole.NAVIGATION, trackPointer = true) { actions() }
    }
}

/** Discovery keeps navigation separate from its actual browse/search controls. */
@Composable
internal fun SegmentedGlassHeader(
    backdrop: HazeState,
    navigation: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    controlsWidth: Dp,
    controlsAlpha: () -> Float = { 1f },
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top) {
        LiquidGlassSurface(backdrop, Modifier.size(44.dp), cornerRadius = 22.dp,
            role = GlassSurfaceRole.NAVIGATION, trackPointer = true) { navigation() }
        Box(Modifier.weight(1f), contentAlignment = Alignment.TopEnd) {
            LiquidGlassSurface(backdrop, Modifier.width(controlsWidth).graphicsLayer {
                alpha = controlsAlpha()
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }, cornerRadius = 22.dp, role = GlassSurfaceRole.NAVIGATION, trackPointer = true) {
                Column(Modifier.fillMaxWidth().pointerInput(Unit) {}, content = content)
            }
        }
    }
}
