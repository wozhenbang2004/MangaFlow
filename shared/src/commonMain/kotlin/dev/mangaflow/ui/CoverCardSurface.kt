package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

import dev.mangaflow.platform.*

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mangaflow.ui.glass.softSurfaceShadow

/** The cover edge clips the picture. Its shadow can sit closer to the page in a dense catalogue. */
@Composable
internal fun Modifier.mangaCoverCard(cornerRadius: Dp = 12.dp, shadowRadius: Dp = cornerRadius * 1.5f): Modifier {
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    return softSurfaceShadow(shape, MaterialTheme.colorScheme.surface.luminance() > .5f,
        strength = .65f, radius = shadowRadius).clip(shape)
}
