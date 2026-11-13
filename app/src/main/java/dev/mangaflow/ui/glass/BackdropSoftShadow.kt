/*
 * Adapted from Kyant0/AndroidLiquidGlass (Backdrop) 2.0.1, commit 65ab177.
 * ShadowModifier.kt, Shadow.kt and Android internal/Paint.kt.
 * Copyright 2025 Kyant. Licensed under the Apache License, Version 2.0.
 * See assets/licenses/Backdrop-APACHE-2.0.txt and THIRD_PARTY_NOTICES.md.
 */
package dev.mangaflow.ui.glass

import android.graphics.BlurMaskFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.roundToInt

/** The shadow is outside the surface; its cleared center never darkens captured glass. */
internal fun Modifier.softSurfaceShadow(shape: Shape, light: Boolean, strength: Float = 1f, radius: Dp = 24.dp,
    strengthScale: () -> Float = { 1f }): Modifier {
    if (strength <= 0f) return this
    return this
        .then(SoftShadowElement(shape, radius, radius / 4f, Color.Black.copy(alpha = (if (light) .10f else .28f) * strength), strengthScale))
        .then(SoftShadowElement(shape, radius * (5f / 24f), radius / 12f, Color.Black.copy(alpha = (if (light) .025f else .12f) * strength), strengthScale))
}

private data class SoftShadowElement(
    val shape: Shape,
    val radius: Dp,
    val offsetY: Dp,
    val color: Color,
    val strengthScale: () -> Float,
) : ModifierNodeElement<SoftShadowNode>() {
    override fun create() = SoftShadowNode(this)
    override fun update(node: SoftShadowNode) {
        node.spec = this
        node.invalidateDraw()
    }
    override fun InspectorInfo.inspectableProperties() { name = "softSurfaceShadow" }
}

private class SoftShadowNode(var spec: SoftShadowElement) : DrawModifierNode, Modifier.Node() {
    override val shouldAutoInvalidate = false
    private var mask: ImageBitmap? = null
    private var cachedSpec: SoftShadowElement? = null
    private var cachedSize = Size.Zero
    private var cachedDensity = 0f
    private var cachedFontScale = 0f
    private var cachedDirection = LayoutDirection.Ltr
    private var maskScale = 1f
    private var cachedMembers: GlassMembers? = null

    override fun ContentDrawScope.draw() {
        if (size.width <= 0f || size.height <= 0f) return drawContent()
        val radius = spec.radius.toPx()
        val offsetY = spec.offsetY.toPx()
        val members = (spec.shape as? GlassMemberShape)?.members
        if (mask == null || cachedSpec?.shape != spec.shape || cachedSpec?.radius != spec.radius ||
            cachedSpec?.offsetY != spec.offsetY || cachedMembers != members || cachedSize != size || cachedDensity != density ||
            cachedFontScale != fontScale || cachedDirection != layoutDirection) {
            val fullWidth = size.width + radius * 4f
            val fullHeight = size.height + radius * 4f + offsetY
            // Shadows have no fine content. Bound their cached A8 mask even for long expanded notes.
            maskScale = minOf(1f, 1.5f / density, 2048f / maxOf(fullWidth, fullHeight))
            val scale = maskScale
            val image = ImageBitmap(ceil(fullWidth * scale).toInt().coerceAtLeast(1),
                ceil(fullHeight * scale).toInt().coerceAtLeast(1), ImageBitmapConfig.Alpha8)
            val outline = spec.shape.createOutline(Size(size.width * scale, size.height * scale),
                layoutDirection, Density(density * scale, fontScale))
            val paint = Paint().apply {
                color = Color.White
                asFrameworkPaint().maskFilter = BlurMaskFilter(radius * scale, BlurMaskFilter.Blur.NORMAL)
            }
            // Canvas(ImageBitmap) is software: hardware Canvas does not support BlurMaskFilter.
            // Only this cached shadow mask uses software; the glass and artwork remain accelerated.
            val canvas = Canvas(image)
            canvas.translate(radius * 2f * scale, (radius * 2f + offsetY) * scale)
            canvas.drawOutline(outline, paint)
            canvas.translate(0f, -offsetY * scale)
            canvas.drawOutline(outline, ShadowMaskPaint)
            mask = image
            cachedSpec = spec
            cachedMembers = members
            cachedSize = size
            cachedDensity = density
            cachedFontScale = fontScale
            cachedDirection = layoutDirection
        }
        mask?.let { image ->
            drawImage(image, srcOffset = IntOffset.Zero, srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset((-radius * 2f).roundToInt(), (-radius * 2f).roundToInt()),
                dstSize = IntSize((image.width / maskScale).roundToInt(), (image.height / maskScale).roundToInt()),
                colorFilter = ColorFilter.tint(spec.color.copy(alpha =
                    (spec.color.alpha * spec.strengthScale()).coerceIn(0f, 1f))),
                filterQuality = FilterQuality.Low)
        }
        drawContent()
    }

    override fun onDetach() { mask = null }
}

private val ShadowMaskPaint = Paint().apply { blendMode = BlendMode.Clear }
