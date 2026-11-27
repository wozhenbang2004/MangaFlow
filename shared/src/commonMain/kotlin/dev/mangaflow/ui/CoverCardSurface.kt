package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

import dev.mangaflow.platform.*

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mangaflow.ui.glass.softSurfaceShadow
import kotlin.math.ceil

/** The cover edge clips the picture. Its shadow can sit closer to the page in a dense catalogue. */
@Composable
internal fun Modifier.mangaCoverCard(cornerRadius: Dp = 12.dp, shadowRadius: Dp = cornerRadius * 1.5f): Modifier {
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val light = MaterialTheme.colorScheme.surface.luminance() > .5f
    if (platformKind == PlatformKind.DESKTOP) {
        // One resizable outline draws the shadow and clips the poster. A live grid resize
        // doesn't need a pair of new software blur masks for each distinct cell size.
        val ink = Color.Black.copy(alpha = if (light) .22f else .36f)
        return shadow(elevation = minOf(shadowRadius * .65f, 6.dp), shape = shape, clip = true,
            ambientColor = ink, spotColor = ink)
    }
    return softSurfaceShadow(shape, light, strength = .65f, radius = shadowRadius).clip(shape)
}

/** Keep the actual cell layout fluid, retargeting the decoder in physical-pixel steps. */
internal fun Density.coverDecodeSize(width: Dp, aspectRatio: Float): IntSize {
    val pixels = width.roundToPx().coerceAtLeast(1)
    val step = if (pixels < 256) 32 else 64
    val bucket = ((pixels - 1) / step + 1) * step
    return IntSize(bucket, ceil(bucket / aspectRatio).toInt())
}
