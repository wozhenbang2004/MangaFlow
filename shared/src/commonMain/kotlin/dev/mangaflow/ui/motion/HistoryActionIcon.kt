package dev.mangaflow.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Finite action feedback on the existing History vector, using the app's beUI
 * press attack/easing and spring return. An open sheet is never a rotated rest state.
 * The enclosing MotionIconButton keeps click semantics, hit size and pointer press.
 */
@Composable
internal fun HistoryActionIcon(
    active: Boolean,
    description: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val motion = LocalMotionEnabled.current
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(active, motion) {
        if (!motion) {
            pulse.snapTo(0f)
        } else if (active) {
            // Backward clock nudge, a small recoil, then settle at the exact neutral pose.
            // A new active/close event cancels this sequence at its current value/velocity.
            pulse.animateTo(1f, tween(MangaMotion.Quick, easing = MangaMotion.EnterEasing))
            pulse.animateTo(-.16f, tween(MangaMotion.Quick, easing = MangaMotion.EnterEasing))
            pulse.animateTo(0f, spring(MangaMotion.PressDamping, MangaMotion.PressStiffness))
        } else {
            pulse.animateTo(0f, spring(MangaMotion.PressDamping, MangaMotion.PressStiffness))
        }
    }
    Icon(Icons.Outlined.History, description,
        modifier = modifier.graphicsLayer {
            val progress = if (motion) pulse.value else 0f
            rotationZ = -18f * progress
            val pressure = (1f - .035f * progress).coerceIn(.96f, 1.01f)
            scaleX = pressure
            scaleY = pressure
            alpha = if (!motion && active) .78f else 1f
        },
        tint = tint)
}
