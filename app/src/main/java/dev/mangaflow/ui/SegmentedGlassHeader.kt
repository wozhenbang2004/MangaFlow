package dev.mangaflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import dev.chrisbanes.haze.HazeState

internal val HeaderContentGap = 2.dp

/** Every root menu button uses the same liquid role and fixed optical geometry. */
@Composable
private fun HeaderNavigationGlass(backdrop: HazeState, navigation: @Composable () -> Unit) {
    LiquidGlassSurface(backdrop, Modifier.size(44.dp), cornerRadius = 22.dp,
        role = GlassSurfaceRole.NAVIGATION, materialReferenceSize = 44.dp,
        trackPointer = true) { navigation() }
}

/** Root identity is already in the drawer; the header holds navigation and actual actions. */
@Composable
internal fun PageTitleHeader(
    title: String,
    backdrop: HazeState,
    navigation: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    actions: (@Composable () -> Unit)? = null,
    summary: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp).semantics { paneTitle = title }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        HeaderNavigationGlass(backdrop, navigation)
        if (leading != null) leading()
        if (summary != null) Box(Modifier.weight(1f)) { summary() }
        else Spacer(Modifier.weight(1f))
        if (actions != null) LiquidGlassSurface(backdrop, cornerRadius = 22.dp,
            role = GlassSurfaceRole.NAVIGATION, trackPointer = true) { actions() }
    }
}

/** Discovery groups menu, browse tabs and tools into three independent glass surfaces. */
@Composable
internal fun SegmentedGlassHeader(
    backdrop: HazeState,
    navigation: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    controlsWidth: Dp,
    actionsWidth: Dp,
    controlsAlpha: () -> Float = { 1f },
    actions: @Composable () -> Unit,
    secondary: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top) {
            HeaderNavigationGlass(backdrop, navigation)
            BoxWithConstraints(Modifier.weight(1f), contentAlignment = Alignment.TopStart) {
                LiquidGlassSurface(backdrop, Modifier.width(controlsWidth.coerceAtMost(maxWidth)).graphicsLayer {
                    alpha = controlsAlpha()
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }, cornerRadius = 22.dp, role = GlassSurfaceRole.NAVIGATION, trackPointer = true) {
                    Column(Modifier.fillMaxWidth().pointerInput(Unit) {}, content = content)
                }
            }
            LiquidGlassSurface(backdrop, Modifier.width(actionsWidth).graphicsLayer {
                alpha = controlsAlpha()
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }, cornerRadius = 22.dp, role = GlassSurfaceRole.NAVIGATION, trackPointer = true) {
                actions()
            }
        }
        if (secondary != null) Box(Modifier.fillMaxWidth().graphicsLayer {
            alpha = controlsAlpha()
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }) { secondary() }
    }
}
