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
    val readability: Float = .55f,
    val environmentStrength: Float = .65f,
    val interactionLight: Float = .14f,
)

internal fun glassMaterial(
    role: GlassSurfaceRole,
    light: Boolean,
    liquid: Boolean,
    softCard: Boolean,
    shortestSide: Dp,
    expansion: Float = 0f,
): GlassMaterial {
    val expanded = expansion.coerceIn(0f, 1f)
    // The gallery caption is a continuation of the artwork, with no separate rim or lens wall.
    // Other glass roles retain their existing authored response.
    if (role == GlassSurfaceRole.GALLERY) return GlassMaterial(
        blurRadius = 18.dp, tintOpacity = if (liquid) .12f else .28f,
        depth = .92f, edgeWidth = 8.dp, displacement = 8.dp,
        refractionStrength = if (liquid) .12f else 0f, heightFraction = .12f,
        whitePoint = if (light) .10f else -.08f, contrast = 0f, chroma = 1.05f,
        ambient = .03f, specular = 0f, edgeShadow = 0f, dispersion = 0f,
        contentNormalBlend = .05f,
        readability = 0f, environmentStrength = .16f, interactionLight = .04f,
    )
    if (!liquid) {
        val tint = if (softCard && role == GlassSurfaceRole.NAVIGATION) {
            if (light) .30f else .38f
        } else if (softCard && role != GlassSurfaceRole.DRAWER) {
            if (light) .40f else .48f
        } else when (role) {
            GlassSurfaceRole.READING_DOCK -> if (light) .40f else .52f
            GlassSurfaceRole.POPOVER -> if (light) .38f else .46f
            GlassSurfaceRole.DRAWER -> if (light) .26f else .36f
            else -> if (light) .42f else .50f
        }
        val blur = when (role) {
            GlassSurfaceRole.THUMB -> 4.dp
            GlassSurfaceRole.READING_DOCK -> 18.dp
            GlassSurfaceRole.POPOVER, GlassSurfaceRole.PANEL -> 24.dp
            GlassSurfaceRole.NAVIGATION -> 28.dp
            else -> 20.dp
        }
        return GlassMaterial(blurRadius = blur, tintOpacity = tint,
            depth = 0f, displacement = 0.dp, refractionStrength = 0f,
            whitePoint = if (light) .12f else -.12f, contrast = 0f,
            chroma = 1.08f, ambient = 0f, specular = 0f, edgeShadow = 0f,
            contentNormalBlend = 0f, readability = .72f,
            environmentStrength = .80f, interactionLight = 0f)
    }

    // A small artwork action needs one thin lens, not a control-sized wall across its centre.
    if (role == GlassSurfaceRole.ARTWORK) return GlassMaterial(
        blurRadius = 3.dp, tintOpacity = if (light) .08f else .12f,
        depth = .80f, edgeWidth = 2.5.dp, displacement = 3.dp,
        refractionStrength = .18f, heightFraction = .12f,
        whitePoint = if (light) .04f else -.04f, contrast = 0f, chroma = 1.02f,
        ambient = .025f, specular = .06f, edgeShadow = 0f, dispersion = 0f,
        contentNormalBlend = .025f, readability = .22f,
        environmentStrength = .22f, interactionLight = .045f,
    )

    val clear = role == GlassSurfaceRole.CONTROL ||
        role == GlassSurfaceRole.THUMB
    if (clear) return GlassMaterial(
        blurRadius = (1.25f + 8f * expanded).dp, edgeWidth = 14.dp, displacement = 20.dp,
        refractionStrength = .60f + .10f * expanded, heightFraction = .28f,
        whitePoint = if (light) .13f else -.16f, contrast = .02f,
        chroma = 1.05f, ambient = if (light) .20f else .12f,
        specular = if (light) .30f else .24f, edgeShadow = if (light) .08f else .16f,
        dispersion = 0f, contentNormalBlend = .08f, specularExponent = 16f, fresnelExponent = 2.5f,
        readability = .35f + .25f * expanded,
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
        blurRadius = (12f + 3f * compactToPanel + panelToLarge + 4f * expanded).dp,
        depth = .78f + .16f * compactToPanel + .06f * expanded,
        tintOpacity = .06f + .10f * expanded,
        edgeWidth = (12f + 4f * expanded).dp,
        displacement = (20f + 4f * expanded).dp,
        refractionStrength = (if (panel) .38f else .52f) + .08f * expanded,
        whitePoint = if (light) .20f else -.18f,
        contrast = .02f,
        chroma = if (light) 1.12f else 1.06f,
        ambient = if (light) .12f else .06f,
        specular = (if (light) .28f else .22f) + .04f * expanded,
        edgeShadow = (if (light) .09f else .20f) + .03f * expanded,
        readability = .62f + .12f * expanded,
    )
}
