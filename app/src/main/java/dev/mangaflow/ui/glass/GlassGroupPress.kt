package dev.mangaflow.ui.glass

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import dev.mangaflow.ui.motion.LocalMotionEnabled

/** Publish a shared SDK interaction from actual member bounds; never consume input. */
@Composable
internal fun Modifier.glassGroupPress(source: MutableInteractionSource): Modifier {
    if (!LocalMotionEnabled.current) return this
    return pointerInput(source) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val press = PressInteraction.Press(down.position)
            var active = source.tryEmit(press)
            try {
                do {
                    val change = awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) break
                    val slop = viewConfiguration.touchSlop
                    if (active && ((change.isConsumed && change.position != change.previousPosition) ||
                            change.position.x < -slop || change.position.y < -slop ||
                            change.position.x > size.width + slop || change.position.y > size.height + slop)) {
                        source.tryEmit(PressInteraction.Cancel(press))
                        active = false
                    }
                } while (true)
            } finally {
                if (active) source.tryEmit(PressInteraction.Release(press))
            }
        }
    }
}
