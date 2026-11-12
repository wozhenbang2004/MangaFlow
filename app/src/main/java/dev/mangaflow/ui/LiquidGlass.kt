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
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.mangaflow.storage.GlassSkin
import dev.mangaflow.ui.glass.BackdropGlassOptics
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import kotlin.math.PI
import kotlin.math.min

internal val LocalGlassSkin = compositionLocalOf { GlassSkin.LIQUID }

internal enum class GlassSurfaceRole { CONTROL, ARTWORK, NAVIGATION, PANEL, THUMB, READING_DOCK, POPOVER }

/** Haze captures the background; Backdrop optics affect that layer only. */
@OptIn(ExperimentalHazeApi::class)
@Composable
internal fun LiquidGlassSurface(
    state: HazeState,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    role: GlassSurfaceRole = GlassSurfaceRole.CONTROL,
    skin: GlassSkin = LocalGlassSkin.current,
    interactionSource: MutableInteractionSource? = null,
    trackPointer: Boolean = false,
    elevation: Dp? = null,
    surfaceColor: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val baseColors = MaterialTheme.colorScheme
    val colors = if (surfaceColor == null) baseColors else baseColors.copy(surface = surfaceColor,
        onSurface = if (surfaceColor.luminance() > .5f) Color(0xFF242426) else Color.White)
    val light = colors.surface.luminance() > .5f
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val optics = remember(skin) {
        if (skin == GlassSkin.LIQUID && Build.VERSION.SDK_INT >= 33) runCatching { BackdropGlassOptics() }.getOrElse {
            Log.w("MangaFlowGlass", "Glass optics unavailable; using the frosted surface", it)
            null
        } else null
    }
    val liquid = skin == GlassSkin.LIQUID && Build.VERSION.SDK_INT >= 33 &&
        optics?.available == true && state.blurEnabled
    val interactions = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val dragged by interactions.collectIsDraggedAsState()
    var pointerDown by remember { mutableStateOf(false) }
    val pointerPosition = remember { mutableStateOf(Offset.Zero) }
    val motion = LocalMotionEnabled.current
    val engagement = animateFloatAsState(
        if (liquid && motion && (pointerDown || pressed || dragged)) 1f else 0f,
        tween(if (motion) MangaMotion.Quick else 0), label = "glass-engagement",
    )
    val style = remember(colors.surface, light, liquid, role) {
        HazeStyle(
            backgroundColor = colors.surface,
            // An explicit transparent tint prevents inheriting a frosted LocalHazeStyle.
            // Liquid's uniform readability tint is applied after the author's refraction.
            tints = listOf(HazeTint(if (liquid) Color.Transparent
                else colors.surface.copy(alpha = when (role) {
                    GlassSurfaceRole.READING_DOCK -> if (light) .40f else .52f
                    GlassSurfaceRole.POPOVER -> if (light) .64f else .72f
                    else -> if (light) .72f else .78f
                }))),
            blurRadius = when {
                role == GlassSurfaceRole.THUMB -> if (liquid) 1.dp else 4.dp
                role == GlassSurfaceRole.READING_DOCK -> if (liquid) 10.dp else 18.dp
                role == GlassSurfaceRole.POPOVER -> if (liquid) 16.dp else 24.dp
                role == GlassSurfaceRole.PANEL -> if (liquid) 14.dp else 24.dp
                role == GlassSurfaceRole.NAVIGATION -> if (liquid) 18.dp else 28.dp
                role == GlassSurfaceRole.ARTWORK -> if (liquid) 9.dp else 20.dp
                else -> if (liquid) 6.dp else 20.dp
            },
            noiseFactor = 0f,
            fallbackTint = HazeTint(colors.surface.copy(alpha = .96f)),
        )
    }
    // Decorative surfaces must stay outside hit testing so touches reach artwork beneath them.
    val pointerTracking = if (trackPointer && liquid && motion && role != GlassSurfaceRole.PANEL) {
        Modifier.pointerInput(skin, role, motion) {
            awaitEachGesture {
                try {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    pointerPosition.value = down.position
                    pointerDown = true
                    do {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes
                            .firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) break
                        pointerPosition.value = change.position
                    } while (true)
                } finally {
                    pointerDown = false
                }
            }
        }
    } else Modifier

    CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
        Box(modifier.shadow(elevation ?: if (liquid) 4.dp else 2.dp, shape, clip = false,
            ambientColor = Color.Black.copy(alpha = .04f),
            spotColor = Color.Black.copy(alpha = .065f))
            .clip(shape).then(pointerTracking)) {
            Box(Modifier.matchParentSize()
                .drawWithContent {
                    // Do not put an opaque card beneath a successfully captured backdrop.
                    // Keep the first/no-source frame and software drawing readable instead.
                    if (drawContext.canvas.nativeCanvas.isHardwareAccelerated &&
                        state.areas.any { it.contentLayer != null }) drawContent()
                    else drawRect(colors.surface.copy(alpha = .96f))
                }
                .graphicsLayer {
                    renderEffect = if (liquid && Build.VERSION.SDK_INT >= 33 && optics != null) {
                        optics.effect(size.width, size.height, cornerRadius.toPx(), density,
                            engagement.value, colors.surface, light, role)
                    } else null
                }
                .hazeEffect(state, style) { inputScale = HazeInputScale.Auto })
            Box(Modifier.matchParentSize().drawBehind {
                val stroke = .65.dp.toPx()
                val inset = stroke * .5f
                val radius = min(cornerRadius.toPx(), size.minDimension * .5f)
                val outlineSize = Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f))
                val outlineRadius = CornerRadius((radius - inset).coerceAtLeast(0f))
                drawRoundRect(colors.onSurface.copy(alpha = if (liquid) .09f else .055f),
                    topLeft = Offset(inset, inset), size = outlineSize,
                    cornerRadius = outlineRadius, style = Stroke(stroke))
                if (liquid && Build.VERSION.SDK_INT >= 33 && optics != null &&
                    drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                    val point = pointerPosition.value
                    val horizontal = (point.x / size.width.coerceAtLeast(1f) - .5f).coerceIn(-.5f, .5f)
                    val vertical = (point.y / size.height.coerceAtLeast(1f) - .5f).coerceIn(-.5f, .5f)
                    val angle = (PI * .25).toFloat() + engagement.value * (horizontal * .5f + vertical * .3f)
                    optics.highlight(size.width, size.height, radius, angle)?.let { brush ->
                        drawRoundRect(brush, topLeft = Offset(inset, inset), size = outlineSize,
                            cornerRadius = outlineRadius, style = Stroke(stroke),
                            alpha = (if (light) .65f else .40f) + engagement.value * .18f)
                    }
                }
            })
            content()
        }
    }
}
