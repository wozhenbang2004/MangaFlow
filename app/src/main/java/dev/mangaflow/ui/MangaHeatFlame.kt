package dev.mangaflow.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import dev.mangaflow.ui.motion.MotionSwap

/** Existing Material vectors change silhouette inside the caller's fixed 14:16 reference box. */
@Composable
internal fun MangaHeatFlame(
    value: Long,
    modifier: Modifier = Modifier,
    onDark: Boolean = MaterialTheme.colorScheme.onSurface.luminance() > .5f,
) {
    // Presentation thresholds for raw heat counts; never a normalized rating or a provider rank.
    val level = when {
        value >= 1_000_000L -> 2
        value >= 100_000L -> 1
        else -> 0
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        MotionSwap(level, Modifier.fillMaxSize()) { shape ->
            val vector = when (shape) {
                2 -> Icons.Filled.Whatshot
                1 -> Icons.Filled.LocalFireDepartment
                else -> Icons.Outlined.LocalFireDepartment
            }
            Icon(vector, contentDescription = null, modifier = Modifier.fillMaxSize(),
                tint = reviewSourceAccent("copy", onDark))
        }
    }
}
