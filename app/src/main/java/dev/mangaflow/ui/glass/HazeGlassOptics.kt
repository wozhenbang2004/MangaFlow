// Copyright 2025, Christopher Banes and the Haze project contributors
// SPDX-License-Identifier: Apache-2.0
// Adapted from Haze Glass 2.1.0 at ca21ff2060e52e1882afdb94f9b055f7fc834bd9.
package dev.mangaflow.ui.glass

import android.graphics.BlendMode
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.toArgb
import kotlin.math.min
import android.graphics.RenderEffect as AndroidEffect

/** Haze 1.6 transports a padded sharp backdrop; this owns its optical response. */
@RequiresApi(33)
internal class HazeGlassOptics {
    private val programs = GlassPrograms.value
    private var previous: OpticalFrame? = null
    private var cached: RenderEffect? = null
    private var previousDepth: Pair<Float, Float>? = null
    private var cachedDepth: AndroidEffect? = null
    var available by mutableStateOf(programs != null)
        private set

    fun effect(
        width: Float, height: Float, padding: Float, corner: Float, density: Float,
        engagement: Float, pointer: Offset, surface: Color, material: GlassMaterial,
    ): RenderEffect? {
        val programs = programs ?: return null
        val w = width - 2f * padding
        val h = height - 2f * padding
        if (!available || w <= 0f || h <= 0f) return null
        val radius = corner.coerceIn(0f, min(w, h) * .5f)
        val point = if (pointer.isSpecified && pointer.x.isFinite() && pointer.y.isFinite()) pointer else Offset(w * .5f, h * .5f)
        val frame = OpticalFrame(width, height, padding, radius, density, engagement, point, surface, material)
        if (frame == previous) return cached
        return try {
            val shader = programs.optical
            shader.setFloatUniform("sampleSize", width, height)
            shader.setFloatUniform("materialOrigin", padding, padding)
            shader.setFloatUniform("materialSize", w, h)
            shader.setFloatUniform("sampleStep", 2f)
            shader.setFloatUniform("cornerRadii", radius, radius, radius, radius)
            shader.setFloatUniform("refractionStrength", material.refractionStrength)
            shader.setFloatUniform("refractionHeight", min(w, h) * material.heightFraction)
            shader.setFloatUniform("edgeRefractionWidth", material.edgeWidth.value * density)
            shader.setFloatUniform("refractionScale", material.displacement.value * density)
            shader.setFloatUniform("ambientResponse", material.ambient)
            shader.setFloatUniform("edgeSoftness", material.edgeSoftness.value * density)
            shader.setFloatUniform("chromaticAberrationStrength", material.dispersion)
            shader.setFloatUniform("contrast", material.contrast)
            shader.setFloatUniform("whitePoint", material.whitePoint)
            shader.setFloatUniform("chromaMultiplier", material.chroma)
            shader.setFloatUniform("contentNormalBlend", material.contentNormalBlend)
            shader.setFloatUniform("fresnelExponent", material.fresnelExponent)
            shader.setColorUniform("tintColor", surface.copy(alpha = material.tintOpacity).toArgb())
            shader.setFloatUniform("interactionPosition", point.x, point.y)
            shader.setFloatUniform("interactionRadius", min(w, h) * .7f)
            shader.setFloatUniform("engagement", engagement)
            val optical = AndroidEffect.createRuntimeShaderEffect(shader, "content")
            val depth = depthInput(material.blurRadius.value * density, material.depth)
            (if (depth != null) AndroidEffect.createChainEffect(optical, depth) else optical)
                .asComposeRenderEffect().also { cached = it; previous = frame }
        } catch (failure: RuntimeException) {
            disable(failure)
            null
        }
    }

    /** Invoke and draw together: a recorded brush snapshots these uniforms. */
    fun rim(
        width: Float, height: Float, corner: Float, density: Float,
        engagement: Float, pointer: Offset, material: GlassMaterial,
    ): ShaderBrush? {
        val programs = programs ?: return null
        if (!available || width <= 0f || height <= 0f) return null
        return try {
            val shader = programs.rim
            val radius = corner.coerceIn(0f, min(width, height) * .5f)
            val point = if (pointer.isSpecified && pointer.x.isFinite() && pointer.y.isFinite()) pointer else Offset(width * .5f, height * .5f)
            shader.setFloatUniform("materialSize", width, height)
            shader.setFloatUniform("sampleStep", 2f)
            shader.setFloatUniform("cornerRadii", radius, radius, radius, radius)
            shader.setFloatUniform("specularIntensity", material.specular)
            shader.setFloatUniform("specularExponent", material.specularExponent)
            shader.setFloatUniform("edgeSoftness", material.edgeSoftness.value * density)
            shader.setColorUniform("edgeShadow", Color.Black.copy(alpha = material.edgeShadow).toArgb())
            shader.setFloatUniform("lightPosition",
                width * .2f + (point.x - width * .2f) * engagement,
                -height * .4f + (point.y + height * .4f) * engagement)
            programs.rimBrush
        } catch (failure: RuntimeException) {
            disable(failure)
            null
        }
    }

    private fun depthInput(radius: Float, depth: Float): AndroidEffect? {
        val key = radius to depth
        if (key == previousDepth) return cachedDepth
        val effect = if (radius <= 0f || depth <= .0001f) null else {
            val blur = AndroidEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
            if (depth >= .9999f) blur else {
                // Scale alpha only: a color filter already premultiplies its result.
                fun scaled(alpha: Float, input: AndroidEffect?): AndroidEffect {
                    val matrix = ColorMatrix().apply { setScale(1f, 1f, 1f, alpha) }
                    val filter = ColorMatrixColorFilter(matrix)
                    return if (input == null) AndroidEffect.createColorFilterEffect(filter)
                    else AndroidEffect.createColorFilterEffect(filter, input)
                }
                AndroidEffect.createBlendModeEffect(scaled(1f - depth, null), scaled(depth, blur), BlendMode.PLUS)
            }
        }
        previousDepth = key
        cachedDepth = effect
        return effect
    }

    private fun disable(failure: RuntimeException) {
        available = false
        cached = null
        previous = null
        Log.w("MangaFlowGlass", "Glass optics unavailable; using the frosted surface", failure)
    }

    private data class OpticalFrame(
        val width: Float, val height: Float, val padding: Float, val radius: Float,
        val density: Float, val engagement: Float, val point: Offset, val surface: Color,
        val material: GlassMaterial,
    )
}

/** Compile once. Effect creation and immediate rim drawing snapshot the shared uniforms. */
@RequiresApi(33)
private object GlassPrograms {
    class Programs {
        val optical = RuntimeShader(HazeOpticalShader)
        val rim = RuntimeShader(HazeRimShader)
        val rimBrush = ShaderBrush(rim)
    }
    val value: Programs? by lazy(LazyThreadSafetyMode.NONE) {
        try { Programs() } catch (failure: RuntimeException) {
            Log.w("MangaFlowGlass", "Glass shader unavailable; using the frosted surface", failure)
            null
        }
    }
}
