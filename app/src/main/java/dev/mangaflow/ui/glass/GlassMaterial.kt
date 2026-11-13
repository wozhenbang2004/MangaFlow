// Copyright 2025, Christopher Banes and the Haze project contributors
// SPDX-License-Identifier: Apache-2.0
// Regular/Clear values adapted from Haze Glass 2.1.0 at ca21ff2060e52e1882afdb94f9b055f7fc834bd9.
package dev.mangaflow.ui.glass

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mangaflow.ui.GlassSurfaceRole

/** One resolved response shared by capture, depth, refraction and lighting. */
@Immutable
internal data class GlassMaterial(
    val blurRadius: Dp,
    val tintOpacity: Float = 0f,
    val depth: Float = 1f,
    val edgeWidth: Dp = 20.dp,
    val displacement: Dp = 48.dp,
    val refractionStrength: Float = .7f,
    val heightFraction: Float = .15f,
    val whitePoint: Float = .55f,
    val contrast: Float = 0f,
    val chroma: Float = 1.5f,
    val ambient: Float = .15f,
    val specular: Float = .48f,
    val edgeShadow: Float = .14f,
    val edgeSoftness: Dp = 1.dp,
    val dispersion: Float = 0f,
    val contentNormalBlend: Float = .15f,
    val specularExponent: Float = 24f,
    val fresnelExponent: Float = 3f,
)

internal fun glassMaterial(
    role: GlassSurfaceRole,
    light: Boolean,
    liquid: Boolean,
    softCard: Boolean,
    shortestSide: Dp,
): GlassMaterial {
    // The gallery caption is a continuation of the artwork, with no separate rim or lens wall.
    // Other glass roles retain their existing authored response.
    if (role == GlassSurfaceRole.GALLERY) return GlassMaterial(
        blurRadius = 18.dp, tintOpacity = if (liquid) .12f else .28f,
        depth = .92f, edgeWidth = 8.dp, displacement = 8.dp,
        refractionStrength = if (liquid) .12f else 0f, heightFraction = .12f,
        whitePoint = if (light) .10f else -.08f, contrast = 0f, chroma = 1.05f,
        ambient = .03f, specular = 0f, edgeShadow = 0f, dispersion = 0f,
        contentNormalBlend = .05f,
    )
    if (!liquid) {
        val tint = if (softCard && role == GlassSurfaceRole.NAVIGATION) {
            if (light) .54f else .62f
        } else if (softCard && role != GlassSurfaceRole.DRAWER) {
            if (light) .70f else .76f
        } else when (role) {
            GlassSurfaceRole.READING_DOCK -> if (light) .40f else .52f
            GlassSurfaceRole.POPOVER -> if (light) .64f else .72f
            GlassSurfaceRole.DRAWER -> if (light) .38f else .52f
            else -> if (light) .72f else .78f
        }
        val blur = when (role) {
            GlassSurfaceRole.THUMB -> 4.dp
            GlassSurfaceRole.READING_DOCK -> 18.dp
            GlassSurfaceRole.POPOVER, GlassSurfaceRole.PANEL -> 24.dp
            GlassSurfaceRole.NAVIGATION -> 28.dp
            else -> 20.dp
        }
        return GlassMaterial(blurRadius = blur, tintOpacity = tint)
    }

    val clear = role == GlassSurfaceRole.CONTROL || role == GlassSurfaceRole.ARTWORK ||
        role == GlassSurfaceRole.THUMB
    if (clear) return GlassMaterial(
        blurRadius = 1.25.dp, edgeWidth = 28.dp, displacement = 56.dp,
        refractionStrength = .85f, heightFraction = .35f,
        whitePoint = if (light) .17f else -.18f, contrast = if (light) .08f else .12f,
        chroma = if (light) 1.05f else 1f, ambient = if (light) .42f else .22f,
        specular = if (light) .55f else .42f, edgeShadow = if (light) .10f else .25f,
        dispersion = .04f, contentNormalBlend = .10f, specularExponent = 16f, fresnelExponent = 2.5f,
    )

    // Haze's responsive Regular response: 64dp -> 176dp -> 220dp.
    fun response(start: Float, end: Float): Float {
        val t = ((shortestSide.value - start) / (end - start)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
    val compactToPanel = response(64f, 176f)
    val panelToLarge = response(176f, 220f)
    val panel = role == GlassSurfaceRole.PANEL || role == GlassSurfaceRole.POPOVER ||
        role == GlassSurfaceRole.DRAWER
    return GlassMaterial(
        blurRadius = (20f + 4f * compactToPanel + panelToLarge).dp,
        depth = .65f + .35f * compactToPanel,
        tintOpacity = if (panel) .08f else 0f,
        refractionStrength = if (panel) .50f else .7f,
        whitePoint = if (light) .55f else -.22f,
        contrast = if (light) 0f else .08f,
        chroma = if (light) 1.5f else 1.1f,
        ambient = if (light) .15f else .08f,
        specular = if (light) .48f else .38f,
        edgeShadow = if (light) .14f else .32f,
    )
}
