package dev.mangaflow.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The existing Material History geometry, separated into a stationary ring and clock hands.
 * Adapted from Google Material Icons (Apache-2.0), commit d3d4aca5a7cf50bc68bbd401cefa708e364194e8:
 * src/action/history/materialiconsoutlined/24px.svg.
 * The parent button owns press/click semantics; only the hands perform one continuous rewind.
 */
@Composable
internal fun HistoryActionIcon(
    active: Boolean,
    description: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val motion = LocalMotionEnabled.current
    val rewind = remember { Animatable(0f) }
    LaunchedEffect(active, motion) {
        if (!motion) {
            rewind.snapTo(0f)
        } else if (active || rewind.value != 0f) {
            // Closing or reopening finishes the current turn from its current pose.
            // No reverse recoil, whole-icon rotation or elastic scale is layered on the press.
            val remaining = (360f + rewind.value).coerceIn(0f, 360f)
            rewind.animateTo(-360f, tween((560f * remaining / 360f).roundToInt().coerceAtLeast(1),
                easing = MangaMotion.SearchEasing))
            rewind.snapTo(0f) // A complete revolution is the identical neutral geometry.
        }
    }
    val ring = remember {
        Path().apply {
            moveTo(13f, 3f)
            cubicTo(8.03f, 3f, 4f, 7.03f, 4f, 12f)
            lineTo(1f, 12f)
            lineTo(4.89f, 15.89f)
            lineTo(4.96f, 16.03f)
            lineTo(9f, 12f)
            lineTo(6f, 12f)
            cubicTo(6f, 8.13f, 9.13f, 5f, 13f, 5f)
            cubicTo(16.87f, 5f, 20f, 8.13f, 20f, 12f)
            cubicTo(20f, 15.87f, 16.87f, 19f, 13f, 19f)
            cubicTo(11.07f, 19f, 9.32f, 18.21f, 8.06f, 16.94f)
            lineTo(6.64f, 18.36f)
            cubicTo(8.27f, 19.99f, 10.51f, 21f, 13f, 21f)
            cubicTo(17.97f, 21f, 22f, 16.97f, 22f, 12f)
            cubicTo(22f, 7.03f, 17.97f, 3f, 13f, 3f)
            close()
        }
    }
    val hands = remember {
        Path().apply {
            moveTo(12f, 8f)
            lineTo(12f, 13f)
            lineTo(16.25f, 15.52f)
            lineTo(17.02f, 14.24f)
            lineTo(13.5f, 12.15f)
            lineTo(13.5f, 8f)
            close()
        }
    }
    Canvas(modifier.size(24.dp).semantics { contentDescription = description }) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            drawPath(ring, tint)
            rotate(if (motion) rewind.value else 0f, pivot = Offset(13f, 12f)) {
                drawPath(hands, tint)
            }
        }
    }
}
