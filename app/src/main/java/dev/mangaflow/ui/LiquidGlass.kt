package dev.mangaflow.ui

import android.os.Build
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.mangaflow.ui.glass.HazeGlassOptics
import dev.mangaflow.ui.glass.glassMaterial
import dev.mangaflow.ui.glass.softSurfaceShadow
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class GlassSurfaceRole { CONTROL, ARTWORK, NAVIGATION, PANEL, THUMB, READING_DOCK, POPOVER, DRAWER, GALLERY }

/** Haze captures the background; the adapted Haze Glass optics affect that layer only. */
@OptIn(ExperimentalHazeApi::class)
@Composable
internal fun LiquidGlassSurface(
    state: HazeState,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    role: GlassSurfaceRole = GlassSurfaceRole.CONTROL,
    interactionSource: MutableInteractionSource? = null,
    trackPointer: Boolean = false,
    elevation: Dp? = null,
    surfaceColor: Color? = null,
    softCard: Boolean = role == GlassSurfaceRole.NAVIGATION || role == GlassSurfaceRole.DRAWER,
    materialReferenceSize: Dp? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val baseColors = MaterialTheme.colorScheme
    val colors = if (surfaceColor == null) baseColors else baseColors.copy(surface = surfaceColor,
        onSurface = if (surfaceColor.luminance() > .5f) Color(0xFF242426) else Color.White)
    val light = colors.surface.luminance() > .5f
    val surfaceTint = if (softCard && surfaceColor == null) lerp(baseColors.background, colors.surface, .70f)
        else colors.surface
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    // Material selection belongs to the UI role, not a user-controlled app skin.
    val wantsLiquid = when (role) {
        GlassSurfaceRole.CONTROL, GlassSurfaceRole.ARTWORK,
        GlassSurfaceRole.NAVIGATION, GlassSurfaceRole.THUMB,
        GlassSurfaceRole.READING_DOCK, GlassSurfaceRole.GALLERY -> true
        GlassSurfaceRole.PANEL, GlassSurfaceRole.POPOVER,
        GlassSurfaceRole.DRAWER -> false
    }
    val optics = remember(wantsLiquid) {
        if (wantsLiquid && Build.VERSION.SDK_INT >= 33) runCatching { HazeGlassOptics() }.getOrElse {
            Log.w("MangaFlowGlass", "Glass optics unavailable; using the frosted surface", it)
            null
        } else null
    }
    val liquid = wantsLiquid && Build.VERSION.SDK_INT >= 33 &&
        optics?.available == true && state.blurEnabled
    val interactions = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val dragged by interactions.collectIsDraggedAsState()
    var pointerDown by remember { mutableStateOf(false) }
    var hasPointer by remember { mutableStateOf(false) }
    val pointerPosition = remember { mutableStateOf(Offset.Unspecified) }
    LaunchedEffect(interactions, pressed || dragged) {
        // A new keyboard/source interaction uses the centre. Retain the last raw
        // point after release so the optical light can fade out continuously.
        if ((pressed || dragged) && !hasPointer) pointerPosition.value = Offset.Unspecified
    }
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    val displayDensity = LocalDensity.current
    val shortestSide = materialReferenceSize ?: if (surfaceSize.width > 0 && surfaceSize.height > 0) with(displayDensity) {
        min(surfaceSize.width, surfaceSize.height).toDp()
    } else 64.dp
    val motion = LocalMotionEnabled.current
    val engagement = animateFloatAsState(
        if (liquid && motion && (pointerDown || pressed || dragged)) 1f else 0f,
        tween(if (motion) MangaMotion.Quick else 0), label = "glass-engagement",
    )
    val material = remember(role, light, liquid, softCard, shortestSide) {
        glassMaterial(role, light, liquid, softCard, shortestSide)
    }
    // Native Gaussian blur needs its source outside the visible silhouette. Reserve its
    // three-sigma kernel plus the bounded ray displacement on the background child only.
    val opticalPadding = if (liquid) with(displayDensity) {
        val maximumDisplacement = material.displacement.toPx() * material.refractionStrength
        // A resizing selector reserves its full travel once; no delayed measurement may
        // change its capture origin or blur material halfway through the same morph.
        val displacement = if (materialReferenceSize != null) maximumDisplacement
            else min(maximumDisplacement, shortestSide.toPx() * .5f)
        (3f * material.blurRadius.toPx() + displacement + material.edgeSoftness.toPx()).roundToInt()
    } else 0
    val style = remember(surfaceTint, material, liquid) {
        HazeStyle(
            backgroundColor = surfaceTint,
            // Liquid transports a sharp image; the optical owner composes its depth/blur.
            // An explicit transparent tint also prevents inherited frosted tints.
            tints = listOf(HazeTint(if (liquid) Color.Transparent
                else surfaceTint.copy(alpha = material.tintOpacity))),
            blurRadius = if (liquid) 0.dp else material.blurRadius,
            noiseFactor = 0f,
            fallbackTint = HazeTint(surfaceTint.copy(alpha = .96f)),
        )
    }
    // Decorative surfaces must stay outside hit testing so touches reach artwork beneath them.
    val pointerTracking = if (trackPointer && liquid && motion && role != GlassSurfaceRole.PANEL) {
        Modifier.pointerInput(role, motion) {
            awaitEachGesture {
                try {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    hasPointer = true
                    pointerPosition.value = down.position
                    pointerDown = true
                    do {
                        val change = awaitPointerEvent(PointerEventPass.Main).changes
                            .firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) break
                        // Observe after children: a scroll owns its drag, so raw glass
                        // pressing must end without consuming or delaying that gesture.
                        if (change.isConsumed && change.position != change.previousPosition) {
                            pointerDown = false
                        }
                        val slop = viewConfiguration.touchSlop
                        if (change.position.x < -slop || change.position.y < -slop ||
                            change.position.x > size.width + slop || change.position.y > size.height + slop) {
                            pointerDown = false
                        }
                        pointerPosition.value = change.position
                    } while (true)
                } finally {
                    pointerDown = false
                    hasPointer = false
                }
            }
        }
    } else Modifier

    CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
        // Backdrop's explicit blurred shadow stays outside the optical/content clipping layer.
        val shadow = if (softCard) Modifier.softSurfaceShadow(shape, light,
            strength = elevation?.let { (it.value / 16f).coerceIn(0f, 1f) } ?: 1f)
        else Modifier.shadow(elevation ?: if (liquid) 4.dp else 2.dp, shape, clip = false,
            ambientColor = Color.Black.copy(alpha = .04f), spotColor = Color.Black.copy(alpha = .065f))
        Box(modifier.then(shadow)
            .clip(shape).then(if (materialReferenceSize == null)
                Modifier.onSizeChanged { surfaceSize = it } else Modifier).then(pointerTracking)) {
            Box(Modifier.matchParentSize()
                .drawWithContent {
                    // Do not put an opaque card beneath a successfully captured backdrop.
                    // Keep the first/no-source frame and software drawing readable instead.
                    if (drawContext.canvas.nativeCanvas.isHardwareAccelerated &&
                        state.areas.any { it.contentLayer != null }) drawContent()
                    else drawRect(surfaceTint.copy(alpha = .96f))
                }
                .layout { measurable, constraints ->
                    val width = constraints.minWidth
                    val height = constraints.minHeight
                    val child = measurable.measure(Constraints.fixed(
                        width + 2 * opticalPadding, height + 2 * opticalPadding))
                    layout(width, height) { child.place(-opticalPadding, -opticalPadding) }
                }
                .graphicsLayer {
                    renderEffect = if (liquid && Build.VERSION.SDK_INT >= 33 && optics != null) {
                        optics.effect(size.width, size.height, opticalPadding.toFloat(), cornerRadius.toPx(), density,
                            engagement.value, if (engagement.value > 0f) pointerPosition.value
                                else Offset.Unspecified, surfaceTint, material)
                    } else null
                }
                .hazeEffect(state, style) { inputScale = if (liquid) HazeInputScale.None else HazeInputScale.Auto })
            if (role != GlassSurfaceRole.GALLERY && (liquid || !softCard)) Box(Modifier.matchParentSize().drawBehind {
                val stroke = .65.dp.toPx()
                val inset = stroke * .5f
                val radius = min(cornerRadius.toPx(), size.minDimension * .5f)
                val outlineSize = Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f))
                val outlineRadius = CornerRadius((radius - inset).coerceAtLeast(0f))
                if (!liquid) drawRoundRect(colors.onSurface.copy(alpha = .055f),
                    topLeft = Offset(inset, inset), size = outlineSize,
                    cornerRadius = outlineRadius, style = Stroke(stroke))
                if (liquid && Build.VERSION.SDK_INT >= 33 && optics != null &&
                    drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                    optics.rim(size.width, size.height, radius, density,
                        engagement.value, if (engagement.value > 0f) pointerPosition.value
                            else Offset.Unspecified, material)?.let { brush ->
                        drawRect(brush)
                    }
                }
            })
            content()
        }
    }
}
