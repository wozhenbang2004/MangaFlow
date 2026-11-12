package dev.mangaflow.ui

import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlin.math.min

/** The sampled backdrop is separate from the foreground: labels never blur or refract. */
@OptIn(ExperimentalHazeApi::class)
@Composable
internal fun LiquidGlassSurface(
    state: HazeState,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val light = colors.surface.luminance() > .5f
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val lens = remember {
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching { GlassRefraction() }.getOrElse {
                Log.w("MangaFlowGlass", "Rim shader unavailable; retaining the readable backdrop", it)
                null
            }
        } else null
    }
    val style = remember(colors.surface, light) {
        HazeStyle(
            backgroundColor = colors.surface,
            tints = listOf(HazeTint(colors.surface.copy(alpha = if (light) .68f else .76f))),
            blurRadius = 12.dp,
            noiseFactor = 0f,
            fallbackTint = HazeTint(colors.surface.copy(alpha = .96f)),
        )
    }
    val edge = remember(light) {
        Brush.linearGradient(listOf(
            Color.White.copy(alpha = if (light) .64f else .30f),
            Color.White.copy(alpha = .08f),
            Color.Black.copy(alpha = .045f),
            Color.White.copy(alpha = if (light) .36f else .12f),
        ))
    }
    val reflection = remember(light) {
        Brush.verticalGradient(listOf(
            Color.White.copy(alpha = if (light) .16f else .06f),
            Color.Transparent,
            Color.White.copy(alpha = .025f),
        ))
    }
    CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
        Box(modifier.shadow(4.dp, shape, clip = false,
            ambientColor = Color.Black.copy(alpha = .045f),
            spotColor = Color.Black.copy(alpha = .07f)).clip(shape)) {
            Box(Modifier.matchParentSize()
                .graphicsLayer {
                    // Read geometry during layer updates, not through a layout-to-state feedback loop.
                    // Haze's fallback must stay plain when background blurring is disabled.
                    renderEffect = if (Build.VERSION.SDK_INT >= 33 && lens != null &&
                        state.blurEnabled && size.width > 0f && size.height > 0f) {
                        lens.effect(size.width, size.height, cornerRadius.toPx(), density)
                    } else null
                }
                .hazeEffect(state, style) { inputScale = HazeInputScale.Auto })
            Box(Modifier.matchParentSize().background(reflection).border(.7.dp, edge, shape))
            content()
        }
    }
}

/** One original AGSL lens per surface, cached by geometry; no bitmap readback or animation clock. */
@RequiresApi(33)
private class GlassRefraction {
    private val shader = RuntimeShader(Shader)
    private var width = -1f
    private var height = -1f
    private var corner = -1f
    private var scale = -1f
    private var cached: RenderEffect? = null
    private var unavailable = false

    fun effect(w: Float, h: Float, radius: Float, density: Float): RenderEffect? {
        if (unavailable) return null
        if (cached != null && w == width && h == height && radius == corner && density == scale) return cached
        return try {
            shader.setFloatUniform("resolution", w, h)
            shader.setFloatUniform("radius", min(radius, min(w, h) * .5f))
            shader.setFloatUniform("rimWidth", min(16f * density, min(w, h) * .32f).coerceAtLeast(1f))
            shader.setFloatUniform("strength", 4f * density)
            shader.setFloatUniform("dispersion", .18f * density)
            val effect = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "backdrop")
                .asComposeRenderEffect()
            width = w
            height = h
            corner = radius
            scale = density
            cached = effect
            effect
        } catch (failure: Exception) {
            // Disable only the decorative lens for this surface, not content or interaction.
            unavailable = true
            cached = null
            Log.w("MangaFlowGlass", "Rim effect unavailable; retaining the readable backdrop", failure)
            null
        }
    }

    companion object {
        // A rounded-rectangle normal bends only the inner rim; the center remains flat.
        private const val Shader = """
        uniform shader backdrop;
        uniform float2 resolution;
        uniform float radius;
        uniform float rimWidth;
        uniform float strength;
        uniform float dispersion;

        half4 main(float2 p) {
            float2 centered = p - resolution * 0.5;
            float2 q = abs(centered) - (resolution * 0.5 - float2(radius));
            float2 outside = max(q, float2(0.0));
            float distance = length(outside) + min(max(q.x, q.y), 0.0) - radius;
            float2 normal;
            if (length(outside) > 0.001) {
                normal = normalize(outside) * sign(centered);
            } else if (q.x > q.y) {
                normal = float2(sign(centered.x), 0.0);
            } else {
                normal = float2(0.0, sign(centered.y));
            }
            float rim = 1.0 - smoothstep(0.0, rimWidth, max(-distance, 0.0));
            float bend = strength * rim * (1.0 - 0.4 * rim);
            float2 uv = clamp(p - normal * bend, float2(0.5), resolution - float2(0.5));
            half4 centerColor = backdrop.eval(uv);
            if (rim < 0.001) return centerColor;
            float2 spread = normal * dispersion * rim;
            half red = backdrop.eval(clamp(uv + spread, float2(0.5), resolution - float2(0.5))).r;
            half blue = backdrop.eval(clamp(uv - spread, float2(0.5), resolution - float2(0.5))).b;
            return half4(red, centerColor.g, blue, centerColor.a);
        }
    """
    }
}
