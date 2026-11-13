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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.mangaflow.ui.glass.HazeGlassOptics
import dev.mangaflow.ui.glass.GlassMemberShape
import dev.mangaflow.ui.glass.LocalGlassInteractionLight
import dev.mangaflow.ui.glass.glassMaterial
import dev.mangaflow.ui.glass.softSurfaceShadow
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import kotlin.math.min
import kotlin.math.max
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
    materialExpansion: () -> Float = { 0f },
    memberShape: GlassMemberShape? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val baseColors = MaterialTheme.colorScheme
    val colors = if (surfaceColor == null) baseColors else baseColors.copy(surface = surfaceColor,
        onSurface = if (surfaceColor.luminance() > .5f) Color(0xFF242426) else Color.White)
    val light = colors.surface.luminance() > .5f
    val surfaceTint = if (softCard && surfaceColor == null) lerp(baseColors.background, colors.surface, .70f)
        else colors.surface
    val shape = memberShape ?: remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    // Material selection belongs to the UI role, not a user-controlled app skin.
    val wantsLiquid = when (role) {
        GlassSurfaceRole.CONTROL, GlassSurfaceRole.ARTWORK,
        GlassSurfaceRole.NAVIGATION, GlassSurfaceRole.THUMB,
        GlassSurfaceRole.READING_DOCK, GlassSurfaceRole.GALLERY -> true
        GlassSurfaceRole.PANEL, GlassSurfaceRole.POPOVER,
        GlassSurfaceRole.DRAWER -> false
    }
    val optics = remember(state) {
        if (Build.VERSION.SDK_INT >= 33) runCatching { HazeGlassOptics() }.getOrElse {
            Log.w("MangaFlowGlass", "Glass optics unavailable; using the frosted surface", it)
            null
        } else null
    }
    val adaptive = Build.VERSION.SDK_INT >= 33 &&
        optics?.available == true && state.blurEnabled
    val liquid = wantsLiquid && adaptive
    val interactions = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val dragged by interactions.collectIsDraggedAsState()
    var pointerDown by remember { mutableStateOf(false) }
    var hasPointer by remember { mutableStateOf(false) }
    val pointerPosition = remember { mutableStateOf(Offset.Unspecified) }
    val lightGroup = LocalGlassInteractionLight.current
    val lightToken = remember { Any() }
    val windowPosition = remember { mutableStateOf(Offset.Unspecified) }
    DisposableEffect(lightGroup, lightToken) {
        onDispose { lightGroup?.release(lightToken) }
    }
    LaunchedEffect(interactions, pressed || dragged) {
        // A new keyboard/source interaction uses the centre. Retain the last raw
        // point after release so the optical light can fade out continuously.
        if ((pressed || dragged) && !hasPointer) pointerPosition.value = Offset.Unspecified
    }
    val displayDensity = LocalDensity.current
    val motion = LocalMotionEnabled.current
    val engagement = animateFloatAsState(
        if (liquid && motion && (pointerDown || pressed || dragged)) 1f else 0f,
        tween(if (motion) MangaMotion.Quick else 0), label = "glass-engagement",
    )
    val groupEngagement = animateFloatAsState(
        if (liquid && motion && lightGroup?.owner != null) 1f else 0f,
        tween(if (motion) MangaMotion.Quick else 0), label = "glass-shared-light",
    )
    val material = remember(role, light, liquid, softCard) {
        glassMaterial(role, light, liquid, softCard, 64.dp)
    }
    // Native Gaussian blur needs its source outside the visible silhouette. Reserve its
    // three-sigma kernel plus the bounded ray displacement on the background child only.
    // Reserve the largest authored kernel once. Morphing must not resize the capture's
    // padding or replace its HazeStyle on every frame, nor lag the geometry by one layout.
    val opticalPadding = remember(role, light, liquid, softCard, displayDensity) {
        if (liquid) with(displayDensity) {
            val envelope = glassMaterial(role, light, true, softCard, 220.dp, expansion = 1f)
            (3f * envelope.blurRadius.toPx() + envelope.displacement.toPx() * envelope.refractionStrength +
                envelope.edgeSoftness.toPx()).roundToInt()
        } else 0
    }
    val fallback = surfaceTint.copy(alpha = if (light) .82f else .88f)
    val style = remember(surfaceTint, material, liquid, adaptive, fallback) {
        HazeStyle(
            backgroundColor = surfaceTint,
            // Liquid transports a sharp image; the optical owner composes its depth/blur.
            // An explicit transparent tint also prevents inherited frosted tints.
            tints = listOf(HazeTint(if (adaptive) Color.Transparent
                else surfaceTint.copy(alpha = material.tintOpacity.coerceAtLeast(if (light) .64f else .72f)))),
            blurRadius = if (liquid) 0.dp else material.blurRadius,
            noiseFactor = 0f,
            fallbackTint = HazeTint(fallback),
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
                    if (windowPosition.value.isSpecified) lightGroup?.update(lightToken, windowPosition.value + down.position)
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
                        if (pointerDown && windowPosition.value.isSpecified)
                            lightGroup?.update(lightToken, windowPosition.value + change.position)
                        else lightGroup?.release(lightToken)
                    } while (true)
                } finally {
                    pointerDown = false
                    hasPointer = false
                    lightGroup?.release(lightToken)
                }
            }
        }
    } else Modifier

    CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
        // Backdrop's explicit blurred shadow stays outside the optical/content clipping layer.
        val shadow = if (softCard) Modifier.softSurfaceShadow(shape, light,
            strength = elevation?.let { (it.value / 16f).coerceIn(0f, 1f) } ?: 1f,
            strengthScale = { 1f + .28f * materialExpansion().coerceIn(0f, 1f) })
        else Modifier.shadow(elevation ?: if (liquid) 4.dp else 2.dp, shape, clip = false,
            ambientColor = Color.Black.copy(alpha = .04f), spotColor = Color.Black.copy(alpha = .065f))
        Box(modifier.then(shadow)
            // A merged field owns its complete silhouette, including the narrow joining neck.
            .then(if (memberShape != null && adaptive) Modifier else Modifier.clip(shape))
            .then(pointerTracking).onGloballyPositioned {
                windowPosition.value = it.positionInWindow()
            }) {
            Box(Modifier.matchParentSize()
                .drawWithContent {
                    // Do not put an opaque card beneath a successfully captured backdrop.
                    // Keep the first/no-source frame and software drawing readable instead.
                    if (drawContext.canvas.nativeCanvas.isHardwareAccelerated &&
                        state.areas.any { it.contentLayer?.isReleased == false }) drawContent()
                    else drawOutline(shape.createOutline(size, layoutDirection, this), fallback)
                }
                .layout { measurable, constraints ->
                    val width = constraints.minWidth
                    val height = constraints.minHeight
                    val child = measurable.measure(Constraints.fixed(
                        width + 2 * opticalPadding, height + 2 * opticalPadding))
                    layout(width, height) { child.place(-opticalPadding, -opticalPadding) }
                }
                .graphicsLayer {
                    renderEffect = if (adaptive && Build.VERSION.SDK_INT >= 33 && optics != null) {
                        val side = materialReferenceSize ?: ((min(size.width, size.height) - 2f * opticalPadding) / density).coerceAtLeast(0f).dp
                        val response = glassMaterial(role, light, liquid, softCard, side,
                            materialExpansion().coerceIn(0f, 1f))
                        val sharedPoint = if (liquid && groupEngagement.value > 0f)
                            lightGroup?.positionInWindow?.takeIf { it.isSpecified && windowPosition.value.isSpecified }
                            ?.let { it - windowPosition.value }
                        else null
                        val visibleWidth = size.width - 2f * opticalPadding
                        val visibleHeight = size.height - 2f * opticalPadding
                        val distance = sharedPoint?.let { p ->
                            (p - Offset(p.x.coerceIn(0f, visibleWidth.coerceAtLeast(0f)),
                                p.y.coerceIn(0f, visibleHeight.coerceAtLeast(0f)))).getDistance()
                        } ?: Float.POSITIVE_INFINITY
                        val sharedStrength = groupEngagement.value * (1f - distance / (96f * density)).coerceIn(0f, 1f)
                        val pressure = max(engagement.value, sharedStrength)
                        val point = when {
                            pressure <= 0f -> Offset.Unspecified
                            engagement.value > 0f -> pointerPosition.value
                            else -> sharedPoint ?: Offset.Unspecified
                        }
                        optics.effect(size.width, size.height, opticalPadding.toFloat(), cornerRadius.toPx(), density,
                            pressure, point, surfaceTint, response, blurredInput = !liquid,
                            members = memberShape?.members, mergeWidth = if (liquid) 24f * density * pressure else 0f)
                    } else null
                }
                .hazeEffect(state, style) { inputScale = if (liquid) HazeInputScale.None else HazeInputScale.Auto })
            content()
        }
    }
}
