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
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
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
    leadingWidth: Dp = 0.dp,
) {
    BoxWithConstraints(modifier.fillMaxWidth().semantics { paneTitle = title }) {
    val actionsRoom = (maxWidth - 44.dp - leadingWidth - if (leading != null) 30.dp else 20.dp)
        .coerceAtLeast(44.dp)
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        HeaderNavigationGlass(backdrop, navigation)
        if (leading != null) leading()
        if (summary != null) Box(Modifier.weight(1f)) { summary() }
        else Spacer(Modifier.weight(1f))
        if (actions != null) LiquidGlassSurface(backdrop, Modifier.widthIn(max = actionsRoom), cornerRadius = 22.dp,
            role = GlassSurfaceRole.NAVIGATION, trackPointer = true) {
            Row(Modifier.horizontalScroll(rememberScrollState())) { actions() }
        }
    }
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
        BoxWithConstraints(Modifier.fillMaxWidth().zIndex(1f)) {
        val stacked = maxWidth < 44.dp + 12.dp + controlsWidth + actionsWidth
        if (stacked) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    HeaderNavigationGlass(backdrop, navigation)
                    Spacer(Modifier.weight(1f))
                    LiquidGlassSurface(backdrop, Modifier.width(actionsWidth).graphicsLayer {
                        alpha = controlsAlpha()
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }, cornerRadius = 22.dp, role = GlassSurfaceRole.NAVIGATION, trackPointer = true) { actions() }
                }
                LiquidGlassSurface(backdrop, Modifier.fillMaxWidth().graphicsLayer {
                    alpha = controlsAlpha()
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }, cornerRadius = 22.dp, role = GlassSurfaceRole.NAVIGATION, trackPointer = true) {
                    Column(Modifier.fillMaxWidth(), content = content)
                }
            }
        } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top) {
            HeaderNavigationGlass(backdrop, navigation)
            Box(Modifier.weight(1f)) {
                GlassControlGroup(backdrop, controlsWidth, actionsWidth, controlsAlpha, actions, content)
            }
        }
        }
        }
        if (secondary != null) Box(Modifier.fillMaxWidth().graphicsLayer {
            alpha = controlsAlpha()
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }) { secondary() }
    }
}

/**
 * Adapt beUI's measured-trigger morph and distinct exit curve. A fixed-size foreground leaves
 * before the surface returns behind the tab; reserved list space follows the final return phase.
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
        if (!motion) snap()
        else if (targetState) spring(dampingRatio = 1f, stiffness = MangaMotion.NavigationStiffness)
        else tween(200, easing = MangaMotion.EnterEasing)
    }, label = "ranking-tab-surface") { if (it) 1f else 0f }
    val foreground = transition.animateFloat(transitionSpec = {
        if (!motion) snap()
        else if (targetState) tween(120, delayMillis = 60, easing = MangaMotion.EnterEasing)
        else tween(70, easing = MangaMotion.EnterEasing)
    }, label = "ranking-tab-foreground") { if (it) 1f else 0f }
    val reservedSpace = transition.animateFloat(transitionSpec = {
        if (!motion) snap()
        else if (targetState) tween(260, easing = MangaMotion.EnterEasing)
        else tween(100, delayMillis = 100, easing = MangaMotion.EnterEasing)
    }, label = "ranking-tab-reserved-space") { if (it) 1f else 0f }
    val progress = reveal.value.coerceIn(0f, 1f)
    val present = presence.currentState || presence.targetState || !presence.isIdle
    BoxWithConstraints(Modifier.fillMaxWidth().onGloballyPositioned { origin = it.positionInRoot() }
        .height((contentHeight + 2.dp) * reservedSpace.value.coerceIn(0f, 1f))) {
        if (present) {
            val endLeft = 50.dp.coerceAtMost(maxWidth)
            val endWidth = (maxWidth - endLeft).coerceAtLeast(1.dp)
            val rootOrigin = origin ?: Offset.Zero
            val startWidth = with(density) { anchor.width.toDp() }.coerceIn(1.dp, endWidth)
            val startLeft = with(density) { (anchor.left - rootOrigin.x).toDp() }
                .coerceIn(0.dp, (maxWidth - startWidth).coerceAtLeast(0.dp))
            val startTop = with(density) { (anchor.top - rootOrigin.y).toDp() }
            val startHeight = with(density) { anchor.height.toDp() }.coerceAtLeast(1.dp)
            val startRadius = (startHeight / 2).coerceAtMost(22.dp)
            val left = startLeft + (endLeft - startLeft) * progress
            val top = startTop + (2.dp - startTop) * progress
            val width = startWidth + (endWidth - startWidth) * progress
            val height = startHeight + (contentHeight - startHeight) * progress
            LiquidGlassSurface(backdrop, Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).offset {
                IntOffset(with(density) { left.roundToPx() }, with(density) { top.roundToPx() })
            }.width(width).height(height).graphicsLayer {
                // Keep the shell readable through its return; remove only the final overlapping remnant.
                alpha = (reveal.value / .15f).coerceIn(0f, 1f)
            },
                cornerRadius = startRadius + (22.dp - startRadius) * progress, role = GlassSurfaceRole.NAVIGATION,
                trackPointer = true, materialExpansion = { progress }) {
                Column(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).width(endWidth)
                    .onSizeChanged { contentHeight = with(density) { it.height.toDp() } }
                    .graphicsLayer { alpha = foreground.value }
                    .motionInputGate(enabled && expanded && progress >= .85f && foreground.value >= .95f)) { content() }
            }
        }
    }
}
