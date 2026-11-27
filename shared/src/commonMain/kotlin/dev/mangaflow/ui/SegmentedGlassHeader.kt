package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

import dev.mangaflow.platform.*

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import dev.chrisbanes.haze.HazeState
import kotlin.math.roundToInt

internal val HeaderContentGap = 2.dp
private val DiscoveryIconInk = DpSize(22.dp, 22.dp)
private val DiscoveryMenuInk = HeaderInkCells(0.dp, listOf(DiscoveryIconInk))

/** Each cluster casts one diffuse shadow outside its shared material. */
@Composable
internal fun HeaderGlassSurface(backdrop: HazeState, modifier: Modifier,
    quietShadow: Boolean = false, preferSource: Boolean = true, ink: HeaderInkCells? = null,
    content: @Composable BoxScope.() -> Unit) {
    LiquidGlassSurface(backdrop, modifier, cornerRadius = 22.dp, role = GlassSurfaceRole.NAVIGATION,
        elevation = if (quietShadow) 8.dp else null, trackPointer = true, preferSource = preferSource,
        headerInk = ink, content = content)
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
        HeaderGlassSurface(backdrop, Modifier.size(44.dp), preferSource = false) { navigation() }
        if (leading != null) leading()
        if (summary != null) Box(Modifier.weight(1f)) { summary() }
        else Spacer(Modifier.weight(1f))
        if (actions != null) HeaderGlassSurface(backdrop, Modifier.widthIn(max = actionsRoom), preferSource = false) {
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
    quietShadow: Boolean = false,
    actions: @Composable () -> Unit,
    secondary: (@Composable () -> Unit)? = null,
    discoveryTabLabels: List<String>? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tabInk = if (discoveryTabLabels.isNullOrEmpty()) null else rememberBrowseTabInk(discoveryTabLabels)
    val menuInk = if (tabInk == null) null else DiscoveryMenuInk
    val actionInk = if (tabInk == null) null else remember(actionsWidth) {
        // The reserved search cell and the optional history cell sit inside 8dp end insets.
        val count = ((actionsWidth - 16.dp) / 44.dp).roundToInt().coerceAtLeast(1)
        HeaderInkCells(8.dp, List(count) { DiscoveryIconInk })
    }
    Column(modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().zIndex(1f)) {
        val stacked = maxWidth < 44.dp + 12.dp + controlsWidth + actionsWidth
        if (stacked) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    HeaderGlassSurface(backdrop, Modifier.size(44.dp), quietShadow, ink = menuInk) { navigation() }
                    Spacer(Modifier.weight(1f))
                    HeaderGlassSurface(backdrop, Modifier.width(actionsWidth).graphicsLayer {
                        alpha = controlsAlpha()
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }, quietShadow, ink = actionInk) { actions() }
                }
                HeaderGlassSurface(backdrop, Modifier.fillMaxWidth().graphicsLayer {
                    alpha = controlsAlpha()
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }, quietShadow, ink = tabInk) {
                    Column(Modifier.fillMaxWidth(), content = content)
                }
            }
        } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top) {
            HeaderGlassSurface(backdrop, Modifier.size(44.dp), quietShadow, ink = menuInk) { navigation() }
            Box(Modifier.weight(1f)) {
                GlassControlGroup(backdrop, controlsWidth, actionsWidth, controlsAlpha, actions, content,
                    quietShadow = quietShadow, tabInk = tabInk, actionInk = actionInk)
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
 * Adapt beUI's measured-trigger morph with one reversible geometry progress.
 * Ink clears before the shell reaches its tab, without an empty-shell waiting phase.
 * Surface, reserved space and primary-tab emphasis all follow that same progress.
 */
@Composable
internal fun RankingsFromTab(
    backdrop: HazeState,
    expanded: Boolean,
    anchor: Rect,
    enabled: Boolean,
    onEmphasisState: (State<Float>?) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val motion = LocalMotionEnabled.current
    val density = LocalDensity.current
    var origin by remember { mutableStateOf<Offset?>(null) }
    var contentHeight by remember(density) { mutableStateOf(44.dp) }
    var measuredWidth by remember(density) { mutableStateOf<Dp?>(null) }
    var returnAnchor by remember(density) { mutableStateOf<Rect?>(null) }
    val ready = origin != null && anchor.width > 0f && anchor.height > 0f
    val presence = remember { MutableTransitionState(false) }
    val reportEmphasis by rememberUpdatedState(onEmphasisState)
    BoxWithConstraints(Modifier.fillMaxWidth().onGloballyPositioned { origin = it.positionInRoot() }) {
        val viewportWidth = maxWidth
        // On a phone the open filter can use the area below the menu button.
        // Its origin still follows the measured ranking tab in both directions.
        val endLeft = if (viewportWidth < 480.dp) 0.dp else 50.dp
        val endWidth = (viewportWidth - endLeft).coerceAtLeast(1.dp)
        val relativeAnchor = if (ready) origin?.let { point ->
            Rect(anchor.left - point.x, anchor.top - point.y,
                anchor.right - point.x, anchor.bottom - point.y)
        } else null
        // The leaving tab's measured destination belongs to this exit, rather than
        // following subsequent selected-content or header measurement updates.
        SideEffect { if (expanded && relativeAnchor != null) returnAnchor = relativeAnchor }
        val startBounds = (if (expanded) relativeAnchor else returnAnchor ?: relativeAnchor) ?: Rect.Zero
        // Mount invisibly for natural-height measurement before the first expansion.
        // A later opening can reuse that measurement only at the same width/density.
        presence.targetState = expanded && ready && measuredWidth == endWidth
        val transition = updateTransition(presence, label = "ranking-from-tab")
        val reveal = transition.animateFloat(transitionSpec = {
            if (!motion) snap()
            else spring(dampingRatio = 1f, stiffness = MangaMotion.NavigationStiffness)
        }, label = "ranking-tab-surface") { if (it) 1f else 0f }
        val progress = reveal.value.coerceIn(0f, 1f)
        val present = presence.currentState || presence.targetState || !presence.isIdle
        val geometryEmphasis = remember(reveal) {
            derivedStateOf { reveal.value.coerceIn(0f, 1f) }
        }
        val contentEmphasis = remember(reveal) {
            derivedStateOf {
                val room = ((reveal.value.coerceIn(0f, 1f) - .44f) / .56f).coerceIn(0f, 1f)
                room * room * (3f - 2f * room)
            }
        }
        // Primary-tab emphasis follows the moving shell rather than the earlier
        // text fade, including during a quick reversal. Callers read this in drawing.
        DisposableEffect(geometryEmphasis) {
            reportEmphasis(geometryEmphasis)
            onDispose { reportEmphasis(null) }
        }
        Box(Modifier.fillMaxWidth().height((contentHeight + 2.dp) * progress)) {
            if (expanded || present) {
                val startWidth = with(density) { startBounds.width.toDp() }.coerceIn(1.dp, endWidth)
                val startLeft = with(density) { startBounds.left.toDp() }
                    .coerceIn(0.dp, (viewportWidth - startWidth).coerceAtLeast(0.dp))
                val startTop = with(density) { startBounds.top.toDp() }
                val startHeight = with(density) { startBounds.height.toDp() }.coerceAtLeast(1.dp)
                val startRadius = (startHeight / 2).coerceAtMost(22.dp)
                val left = startLeft + (endLeft - startLeft) * progress
                val top = startTop + (2.dp - startTop) * progress
                val width = startWidth + (endWidth - startWidth) * progress
                val height = startHeight + (contentHeight - startHeight) * progress
                LiquidGlassSurface(backdrop, Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).offset {
                    IntOffset(with(density) { left.roundToPx() }, with(density) { top.roundToPx() })
                }.width(width).height(height).graphicsLayer {
                    // Land smoothly beneath the existing tab instead of clipping off
                    // a final strip at a separate alpha threshold.
                    val merge = (progress / .22f).coerceIn(0f, 1f)
                    alpha = merge * merge * (3f - 2f * merge)
                },
                    cornerRadius = startRadius + (22.dp - startRadius) * progress, role = GlassSurfaceRole.NAVIGATION,
                    trackPointer = enabled && expanded && progress >= .85f, materialExpansion = { progress },
                    preferSource = true) {
                    Column(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).width(endWidth)
                        .onSizeChanged {
                            if (expanded && it.height > 0) {
                                contentHeight = with(density) { it.height.toDp() }
                                measuredWidth = endWidth
                            }
                        }.graphicsLayer { alpha = contentEmphasis.value }
                        .motionInputGate(enabled && expanded && progress >= .85f && contentEmphasis.value >= .95f)) { content() }
                }
            }
        }
    }
}
