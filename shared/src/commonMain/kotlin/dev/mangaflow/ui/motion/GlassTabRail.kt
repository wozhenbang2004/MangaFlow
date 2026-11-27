package dev.mangaflow.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.LocalGlassAccessibilitySettings
import dev.mangaflow.ui.GlassSurfaceRole
import dev.mangaflow.ui.LiquidGlassSurface
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private class TabRelease(val position: Float, val velocity: Float)

internal val GlassTabHorizontalInset = 3.dp
internal val GlassTabVerticalInset = 4.dp

/** The lens and its fill share a measured slot, behind the stationary input and text row. */
@Composable
internal fun GlassTabRail(
    modifier: Modifier,
    backdrop: HazeState?,
    labels: List<String>,
    selected: Int,
    selectionVisible: Boolean,
    color: Color,
    opacity: State<Float>,
    pressedIndex: Int?,
    onDetent: () -> Unit,
    onSelect: (Int) -> Unit,
    content: @Composable (Modifier, Int?) -> Unit,
) {
    if (backdrop == null) {
        content(modifier, null)
        return
    }
    val motion = LocalMotionEnabled.current
    val access = LocalGlassAccessibilitySettings.current
    val useLens = motion && !access.reduceTransparency && !access.increaseContrast &&
        !LocalTouchExplorationEnabled.current
    val index = rememberUpdatedState(selected)
    val front = remember(labels) { Animatable(selected.toFloat()) }
    val tail = remember(labels) { Animatable(selected.toFloat()) }
    var finger by remember(labels) { mutableStateOf<Float?>(null) }
    var release by remember(labels) { mutableStateOf<TabRelease?>(null) }
    var previewIndex by remember(labels) { mutableIntStateOf(-1) }
    val dragging by remember(labels) { derivedStateOf { finger != null && release == null } }

    LaunchedEffect(labels, motion) {
        var lastRelease: TabRelease? = null
        snapshotFlow { Triple(index.value, dragging, release) }.collect { (target, held, up) ->
            if (held) {
                front.stop()
            } else {
                var velocity = front.velocity
                if (up != null && up !== lastRelease && finger != null) {
                    front.snapTo(up.position)
                    if (release !== up) return@collect
                    finger = null
                    velocity = up.velocity
                    lastRelease = up
                }
                if (motion) {
                    launch {
                        front.animateTo(target.toFloat(), spring(MangaMotion.GestureDamping, MangaMotion.TabLeadingStiffness),
                            initialVelocity = velocity)
                    }
                } else front.snapTo(target.toFloat())
            }
        }
    }
    // The leading side follows raw drag samples. The trailing side catches up on a softer spring.
    LaunchedEffect(labels, motion) {
        snapshotFlow { finger ?: index.value.toFloat() }.collect { target ->
            val velocity = tail.velocity
            if (motion) {
                launch {
                    tail.animateTo(target, spring(MangaMotion.PressDamping, MangaMotion.TabTrailingStiffness),
                        initialVelocity = velocity)
                }
            } else tail.snapTo(target)
        }
    }
    // A neighbour press draws just the near shoulder towards it. Releasing activates
    // the tab; scrolling away unloads the pressure and leaves the current page in place.
    val shoulderTarget = if (useLens && !dragging && pressedIndex != null)
        .11f * (pressedIndex - selected).coerceIn(-1, 1) else 0f
    val shoulder = animateFloatAsState(shoulderTarget,
        if (useLens) spring(1f, MangaMotion.PressAttackStiffness) else snap(), label = "glass-tab-reach")
    val pressureTarget = when {
        !useLens -> 0f
        dragging || pressedIndex == selected -> 1f
        pressedIndex != null -> .42f
        else -> 0f
    }
    val pressure = animateFloatAsState(pressureTarget,
        if (useLens) spring(MangaMotion.PressDamping, MangaMotion.PressStiffness) else snap(), label = "glass-tab-pressure")
    val leading = remember(labels, shoulder) { derivedStateOf {
        finger ?: (front.value + shoulder.value).coerceIn(0f, labels.lastIndex.toFloat())
    } }
    val travel = remember(leading, tail, motion) { derivedStateOf {
        if (motion) (leading.value - tail.value).coerceIn(-.70f, .70f) else 0f
    } }
    val lensAmount = remember(travel, pressure, opacity, useLens) { derivedStateOf {
        if (useLens) maxOf(pressure.value, abs(travel.value) * 2.4f).coerceIn(0f, 1f) * opacity.value else 0f
    } }
    val hasLens by remember(lensAmount) { derivedStateOf { lensAmount.value > .012f } }

    val gesture = if (selectionVisible) Modifier.glassTabScrub(labels, selected,
        currentPosition = { leading.value },
        onPreview = { position ->
            release = null
            finger = position
            val previous = if (previewIndex >= 0) previewIndex else index.value
            // A small dead band stops the lettering and the detent from chattering at a seam.
            previewIndex = if (abs(position - previous) > .56f) position.roundToInt() else previous
            if (previewIndex != previous) onDetent()
        },
        onFinish = { at, target, velocity ->
            finger = at
            release = TabRelease(at, velocity)
            if (target != null && target != index.value) {
                if (target != previewIndex) onDetent()
                onSelect(target)
            }
            previewIndex = -1
        }) else Modifier
    Box(modifier.then(gesture)) {
        Box(Modifier.matchParentSize().layout { measurable, bounds ->
            val slot = bounds.maxWidth.toFloat() / labels.size
            val rest = (slot - 2 * GlassTabHorizontalInset.toPx()).coerceAtLeast(0f)
            val stretch = abs(travel.value)
            val width = (rest + slot * .53f * stretch + 3.dp.toPx() * pressure.value)
                .roundToInt().coerceIn(0, bounds.maxWidth)
            val height = ((bounds.maxHeight - 2 * GlassTabVerticalInset.toPx()) * (1f - .16f * stretch) - 2.dp.toPx() * pressure.value)
                .roundToInt().coerceIn(0, bounds.maxHeight)
            val thumb = measurable.measure(Constraints.fixed(width, height))
            layout(bounds.maxWidth, bounds.maxHeight) {
                val center = leading.value + .5f - .24f * travel.value
                val x = (slot * center - width / 2f).roundToInt().coerceIn(0, bounds.maxWidth - width)
                thumb.placeRelative(x, (bounds.maxHeight - height) / 2)
            }
        }.drawBehind {
            drawRoundRect(color, cornerRadius = CornerRadius(size.height / 2f),
                alpha = opacity.value * (1f - .55f * lensAmount.value))
        }) {
            if (hasLens) {
                LiquidGlassSurface(backdrop, Modifier.fillMaxSize(), role = GlassSurfaceRole.TAB_LENS,
                    surfaceShape = CircleShape, elevation = 0.dp, preferSource = true,
                    materialExpansion = { lensAmount.value }, lensTravel = { travel.value / .70f }) {}
            }
        }
        content(Modifier.fillMaxSize(), previewIndex.takeIf { dragging && it >= 0 })
    }
}
