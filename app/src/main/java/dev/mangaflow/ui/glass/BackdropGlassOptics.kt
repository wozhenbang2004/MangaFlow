/*
 * Copyright 2025 Kyant
 * Licensed under the Apache License, Version 2.0.
 * See app/src/main/assets/licenses/Backdrop-APACHE-2.0.txt.
 *
 * Adapted from Backdrop 2.0.1 Lens.kt and HighlightStyle.kt at
 * 65ab177e90e5c1d8c62e70cf7755841982da65f6.
 * Changes: Android RuntimeShader bindings for Haze 1.6's captured background;
 * uniform corners, bounded reader presets, interaction light and an independent
 * uniform readability tint after refraction. No foreground content enters this effect.
 */
package dev.mangaflow.ui.glass

import android.graphics.RuntimeShader
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.toArgb
import dev.mangaflow.ui.GlassSurfaceRole
import kotlin.math.min

/** The caller guards both API level and the actual drawing Canvas. */
@RequiresApi(33)
internal class BackdropGlassOptics {
    private val lens = RuntimeShader(ReadableRefraction)
    private val highlight = RuntimeShader(DefaultHighlightShaderString)
    private var previous: LensFrame? = null
    private var cached: RenderEffect? = null
    var available by mutableStateOf(true)
        private set

    fun effect(
        width: Float, height: Float, corner: Float, density: Float,
        engagement: Float, surface: Color, light: Boolean, role: GlassSurfaceRole,
        softCard: Boolean = false,
    ): RenderEffect? {
        if (!available || width <= 0f || height <= 0f) return null
        val shortSide = min(width, height)
        val radius = corner.coerceIn(0f, shortSide * .5f)
        val frame = LensFrame(width, height, radius, density, engagement, surface.toArgb(), light, role, softCard)
        if (frame == previous) return cached
        return try {
            val rimDp = if (softCard) 10f else when (role) {
                GlassSurfaceRole.CONTROL -> 14f
                GlassSurfaceRole.ARTWORK -> 16f
                GlassSurfaceRole.NAVIGATION -> 18f
                GlassSurfaceRole.PANEL -> 18f
                GlassSurfaceRole.THUMB -> 7f
                GlassSurfaceRole.READING_DOCK -> 12f
                GlassSurfaceRole.POPOVER -> 14f
                GlassSurfaceRole.DRAWER -> 18f
            }
            val rim = min(rimDp * density, shortSide * .28f).coerceAtLeast(.1f)
            val depth = if (softCard) .28f else when (role) {
                GlassSurfaceRole.CONTROL -> .82f
                GlassSurfaceRole.ARTWORK -> .72f
                GlassSurfaceRole.NAVIGATION -> .52f
                GlassSurfaceRole.PANEL -> .62f
                GlassSurfaceRole.THUMB -> 1.05f
                GlassSurfaceRole.READING_DOCK -> .9f
                GlassSurfaceRole.POPOVER -> .65f
                GlassSurfaceRole.DRAWER -> .62f
            }
            lens.setFloatUniform("size", width, height)
            lens.setFloatUniform("offset", 0f, 0f)
            lens.setFloatUniform("cornerRadii", radius, radius, radius, radius)
            lens.setFloatUniform("refractionHeight", rim)
            // Backdrop's Lens binding uses the negative amount for inward sampling.
            lens.setFloatUniform("refractionAmount", -rim * (depth + (if (softCard) .06f else .16f) * engagement))
            lens.setFloatUniform("depthEffect", 0f)
            lens.setColorUniform("surfaceColor", surface.copy(alpha = 1f).toArgb())
            lens.setFloatUniform("tintOpacity", if (softCard && role != GlassSurfaceRole.DRAWER) { if (light) .78f else .82f } else when (role) {
                GlassSurfaceRole.CONTROL -> if (light) .60f else .72f
                GlassSurfaceRole.ARTWORK -> if (light) .60f else .70f
                GlassSurfaceRole.NAVIGATION -> if (light) .68f else .76f
                GlassSurfaceRole.PANEL -> if (light) .74f else .80f
                GlassSurfaceRole.THUMB -> if (light) .22f else .38f
                GlassSurfaceRole.READING_DOCK -> if (light) .20f else .34f
                GlassSurfaceRole.POPOVER -> if (light) .44f else .60f
                GlassSurfaceRole.DRAWER -> if (light) .28f else .46f
            })
            android.graphics.RenderEffect.createRuntimeShaderEffect(lens, "content")
                .asComposeRenderEffect().also { cached = it; previous = frame }
        } catch (failure: Exception) {
            disable(failure)
            null
        }
    }

    fun highlight(width: Float, height: Float, corner: Float, angle: Float): ShaderBrush? {
        if (!available || width <= 0f || height <= 0f) return null
        return try {
            val radius = min(corner, min(width, height) * .5f)
            highlight.setFloatUniform("size", width, height)
            highlight.setFloatUniform("cornerRadii", radius, radius, radius, radius)
            // Alpha is applied by DrawScope, as in the upstream highlight binding.
            highlight.setColorUniform("color", android.graphics.Color.WHITE)
            highlight.setFloatUniform("angle", angle)
            highlight.setFloatUniform("falloff", 1.5f)
            ShaderBrush(highlight)
        } catch (failure: Exception) {
            disable(failure)
            null
        }
    }

    private fun disable(failure: Exception) {
        available = false
        cached = null
        previous = null
        Log.w("MangaFlowGlass", "Glass optics unavailable; using the frosted surface", failure)
    }

    private data class LensFrame(
        val width: Float, val height: Float, val radius: Float, val density: Float,
        val engagement: Float, val surface: Int, val light: Boolean, val role: GlassSurfaceRole,
        val softCard: Boolean,
    )

    companion object {
        // Preserve the author's lens calculation. A single, restrained tint prevents
        // the previous edge-to-center wash from drawing an opaque white inner panel.
        private val ReadableRefraction = RoundedRectRefractionShaderString.replace(
            "half4 main(float2 coord)", "half4 refractBackdrop(float2 coord)",
        ) + """
        layout(color) uniform half4 surfaceColor;
        uniform float tintOpacity;

        half4 main(float2 coord) {
            half4 sampled = refractBackdrop(coord);
            half opacity = half(tintOpacity);
            half4 wash = half4(surfaceColor.rgb * opacity, opacity);
            return wash + sampled * (1.0 - opacity);
        }
        """
    }
}
