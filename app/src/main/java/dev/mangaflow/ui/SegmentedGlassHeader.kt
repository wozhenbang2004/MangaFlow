package dev.mangaflow.ui

import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.LocalMotionEnabled
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
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
        role = GlassSurfaceRole.NAVIGATION,
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

/** Three visual segments: separate navigation and a paired browse/actions optical group. */
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
            Box(Modifier.weight(1f)) {
                GlassControlGroup(backdrop, controlsWidth, actionsWidth, controlsAlpha, actions, content)
            }
        }
        if (secondary != null) Box(Modifier.fillMaxWidth().graphicsLayer {
            alpha = controlsAlpha()
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }) { secondary() }
    }
}

/**
 * Adapt beUI's measured-trigger morph: one reversible progress owns surface bounds and reveal.
 * The full-size foreground keeps its typography; only its owning glass grows from the tab.
 */
@Composable
internal fun RankingsFromTab(
    backdrop: HazeState,
    expanded: Boolean,
    anchor: Rect,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val motion = LocalMotionEnabled.current
    val density = LocalDensity.current
    var origin by remember { mutableStateOf<Offset?>(null) }
    var contentHeight by remember(density) { mutableStateOf(44.dp) }
    val ready = origin != null && anchor.width > 0f && anchor.height > 0f
    val presence = remember { MutableTransitionState(false) }
    presence.targetState = expanded && ready
    val transition = updateTransition(presence, label = "ranking-from-tab")
    val reveal = transition.animateFloat(transitionSpec = {
        if (motion) spring(dampingRatio = 1f, stiffness = MangaMotion.NavigationStiffness) else snap()
    }, label = "ranking-tab-surface") { if (it) 1f else 0f }
    val progress = reveal.value.coerceIn(0f, 1f)
    val present = presence.currentState || presence.targetState || !presence.isIdle
    BoxWithConstraints(Modifier.fillMaxWidth().onGloballyPositioned { origin = it.positionInRoot() }
        .height((contentHeight + 2.dp) * progress)) {
        if (present) {
            val endLeft = 50.dp.coerceAtMost(maxWidth)
            val endWidth = (maxWidth - endLeft).coerceAtLeast(1.dp)
            val rootOrigin = origin ?: Offset.Zero
            val startLeft = with(density) { (anchor.left - rootOrigin.x).toDp() }
                .coerceIn(0.dp, maxWidth)
            val startTop = with(density) { (anchor.bottom - rootOrigin.y).toDp() } - 10.dp
            val startWidth = with(density) { anchor.width.toDp() }.coerceIn(1.dp, endWidth)
            val left = startLeft + (endLeft - startLeft) * progress
            val top = startTop + (2.dp - startTop) * progress
            val width = startWidth + (endWidth - startWidth) * progress
            val height = 10.dp + (contentHeight - 10.dp) * progress
            LiquidGlassSurface(backdrop, Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).offset {
                IntOffset(with(density) { left.roundToPx() }, with(density) { top.roundToPx() })
            }.width(width).height(height),
                cornerRadius = 5.dp + 17.dp * progress, role = GlassSurfaceRole.NAVIGATION,
                trackPointer = true, materialExpansion = { progress }) {
                Column(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).width(endWidth)
                    .onSizeChanged { contentHeight = with(density) { it.height.toDp() } }
                    .graphicsLayer { alpha = ((progress - .28f) / .60f).coerceIn(0f, 1f) }
                    .motionInputGate(enabled && expanded && progress >= .85f)) { content() }
            }
        }
    }
}
