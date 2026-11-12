package dev.mangaflow.ui

import android.graphics.RuntimeShader
import android.os.Build
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

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
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val radiusPx = with(density) { cornerRadius.toPx() }
    val lens = remember {
        if (Build.VERSION.SDK_INT >= 33) runCatching { GlassRefraction() }.getOrNull() else null
    }
    val refraction = remember(size, radiusPx, density.density) {
        if (Build.VERSION.SDK_INT >= 33 && size.width > 0 && size.height > 0) {
            lens?.create(size, radiusPx, density.density)
        } else null
    }
    val style = remember(colors.surface, light) {
        HazeStyle(
            backgroundColor = colors.surface,
            tints = listOf(HazeTint(colors.surface.copy(alpha = if (light) .68f else .64f))),
            blurRadius = 12.dp,
            noiseFactor = 0f,
            fallbackTint = HazeTint(colors.surface.copy(alpha = .96f)),
        )
    }
    val edge = remember(light) {
        Brush.linearGradient(listOf(
            Color.White.copy(alpha = if (light) .72f else .32f),
            Color.White.copy(alpha = .08f),
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
        Box(modifier.shadow(8.dp, shape, clip = false,
            ambientColor = Color.Black.copy(alpha = .045f),
            spotColor = Color.Black.copy(alpha = .07f)).clip(shape)) {
            Box(Modifier.matchParentSize().onSizeChanged { size = it }
                .graphicsLayer { renderEffect = refraction }
                .hazeEffect(state, style) { inputScale = HazeInputScale.Auto })
            Box(Modifier.matchParentSize().background(reflection).border(.7.dp, edge, shape))
            content()
        }
    }
}

/** A shallow rounded lens, limited to the rim so artwork and text remain easy to read. */
@RequiresApi(33)
private class GlassRefraction {
    // Compile once; IME and window resizing update uniforms without compiling on every frame.
    private val shader = RuntimeShader(Shader)

    fun create(size: IntSize, radius: Float, density: Float): RenderEffect? = runCatching {
        shader.setFloatUniform("resolution", size.width.toFloat(), size.height.toFloat())
        shader.setFloatUniform("radius", radius.coerceAtMost(minOf(size.width, size.height) / 2f))
        shader.setFloatUniform("density", density)
        android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "backdrop").asComposeRenderEffect()
    }.getOrNull()

    companion object {
        private const val Shader = """
        uniform shader backdrop;
        uniform float2 resolution;
        uniform float radius;
        uniform float density;

        float roundedDistance(float2 p) {
            float2 q = abs(p - resolution * 0.5) - (resolution * 0.5 - radius);
            return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
        }

        half4 main(float2 p) {
            float distance = roundedDistance(p);
            float band = max(min(radius, 20.0 * density), 1.0);
            float rim = 1.0 - smoothstep(0.0, band, max(-distance, 0.0));
            float2 normal = float2(
                roundedDistance(p + float2(1.0, 0.0)) - roundedDistance(p - float2(1.0, 0.0)),
                roundedDistance(p + float2(0.0, 1.0)) - roundedDistance(p - float2(0.0, 1.0))
            );
            normal /= max(length(normal), 0.001);
            float2 samplePoint = p - normal * (3.0 * density * rim * rim);
            samplePoint = clamp(samplePoint, float2(0.5), resolution - float2(0.5));
            return backdrop.eval(samplePoint);
        }
    """
    }
}
