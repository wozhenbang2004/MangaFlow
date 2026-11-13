// Bounded two-member adaptation of Haze Glass 2.1.0's merged field.
// Copyright 2026, Christopher Banes and the Haze project contributors
// SPDX-License-Identifier: Apache-2.0
package dev.mangaflow.ui.glass

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

internal data class GlassMembers(val first: Rect, val second: Rect, val corner: Float, val size: Size)

/** Geometry is published during measurement, before either capture or optics draws. */
internal class GlassMemberShape : Shape {
    var members: GlassMembers? by mutableStateOf(null)

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        members?.let { group ->
            val scaleX = size.width / group.size.width.coerceAtLeast(1f)
            val scaleY = size.height / group.size.height.coerceAtLeast(1f)
            listOf(group.first, group.second).forEach { original ->
                val rect = Rect(original.left * scaleX, original.top * scaleY,
                    original.right * scaleX, original.bottom * scaleY)
                if (rect.width > 0f && rect.height > 0f) path.addRoundRect(RoundRect(rect,
                    CornerRadius((group.corner * minOf(scaleX, scaleY))
                        .coerceAtMost(minOf(rect.width, rect.height) * .5f))))
            }
        }
        // Software/older-device fallback and the quiet external shadow keep separate pills.
        // On supported devices the optical signed-distance field also draws their soft neck.
        return Outline.Generic(path)
    }
}
