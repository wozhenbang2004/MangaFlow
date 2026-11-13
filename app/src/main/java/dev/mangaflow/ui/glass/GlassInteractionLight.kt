// Interaction lighting adapted to the existing Haze Glass interaction response.
// Haze 2.1.0 ca21ff2060e52e1882afdb94f9b055f7fc834bd9, Apache-2.0.
package dev.mangaflow.ui.glass

import androidx.compose.runtime.Stable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import dev.mangaflow.ui.motion.LocalMotionEnabled

/** One finite touch-light event for neighbouring controls in the app window. */
@Stable
internal class GlassInteractionLight {
    var owner: Any? by mutableStateOf(null)
        private set
    var positionInWindow by mutableStateOf(Offset.Unspecified)
        private set

    fun update(token: Any, position: Offset) {
        positionInWindow = position
        owner = token
    }

    fun release(token: Any) {
        if (owner === token) owner = null
        // Keep the last point while the finite release animation settles.
    }
}

internal val LocalGlassInteractionLight = staticCompositionLocalOf<GlassInteractionLight?> { null }

/** Observe only a real member's hit area; the transparent space between members stays free. */
@Composable
internal fun Modifier.glassLightTarget(): Modifier {
    val group = LocalGlassInteractionLight.current ?: return this
    val motion = LocalMotionEnabled.current
    val token = remember { Any() }
    val origin = remember { mutableStateOf(Offset.Unspecified) }
    DisposableEffect(group, token, motion) { onDispose { group.release(token) } }
    if (!motion) return this
    return onGloballyPositioned { origin.value = it.positionInWindow() }.pointerInput(group) {
        awaitEachGesture {
            try {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (origin.value.isSpecified) group.update(token, origin.value + down.position)
                var eligible = true
                do {
                    val change = awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) break
                    val slop = viewConfiguration.touchSlop
                    if ((change.isConsumed && change.position != change.previousPosition) ||
                        change.position.x < -slop || change.position.y < -slop ||
                        change.position.x > size.width + slop || change.position.y > size.height + slop) eligible = false
                    if (eligible && origin.value.isSpecified) group.update(token, origin.value + change.position)
                    else group.release(token)
                } while (true)
            } finally { group.release(token) }
        }
    }
}
