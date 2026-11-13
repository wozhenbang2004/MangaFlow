package dev.mangaflow.ui

import androidx.compose.animation.core.animateDpAsState
import dev.mangaflow.ui.platform.*

import dev.mangaflow.platform.*

import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.hazeGlassContainer
import dev.mangaflow.ui.glass.glassGroupPress
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion

/** Native Haze members retain separate layout/hit regions and share one container runtime. */
@Composable
internal fun GlassControlGroup(
    backdrop: HazeState,
    controlsWidth: Dp,
    actionsWidth: Dp,
    alpha: () -> Float,
    actions: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val motion = LocalMotionEnabled.current
    // This source owns proximity only. Each SDK member observes its own pointer
    // coordinates; broadcasting a sibling's local Press would light the wrong edge.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val mergeDistance by animateDpAsState(
        targetValue = if (motion && pressed) 16.dp else 0.dp,
        animationSpec = if (motion) tween(if (pressed) MangaMotion.Quick else MangaMotion.Content,
            easing = MangaMotion.EnterEasing) else snap(),
        label = "glass-group-connection",
    )
    Row(Modifier.fillMaxWidth().graphicsLayer {
        this.alpha = alpha()
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }.hazeGlassContainer(
        mergeDistance = mergeDistance,
        memberTransitionSpec = if (motion) spring(dampingRatio = 1f,
            stiffness = MangaMotion.NavigationStiffness) else snap(),
    ), verticalAlignment = Alignment.Top) {
        BoxWithConstraints(Modifier.weight(1f)) {
            LiquidGlassSurface(backdrop, Modifier.width(controlsWidth.coerceAtMost(maxWidth))
                .glassGroupPress(interaction), cornerRadius = 22.dp, role = GlassSurfaceRole.NAVIGATION,
                trackPointer = true) {
                Column(Modifier.fillMaxWidth(), content = content)
            }
        }
        Spacer(Modifier.width(6.dp))
        LiquidGlassSurface(backdrop, Modifier.width(actionsWidth).glassGroupPress(interaction),
            cornerRadius = 22.dp, role = GlassSurfaceRole.NAVIGATION,
            trackPointer = true) { actions() }
    }
}
