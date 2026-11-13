package dev.mangaflow.ui.motion

import android.os.SystemClock
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat

/** Meaning belongs to the action, never to an animation frame or a network callback. */
internal enum class FeelEvent { Selection, Threshold, Confirm, Reject, Undo }

internal class FeelFeedback(private val view: View) {
    private var lastAt = Long.MIN_VALUE

    fun perform(event: FeelEvent) {
        val now = SystemClock.uptimeMillis()
        // One window owns nested controls too. Do not multiply a single action into two pulses.
        val previous = view.getTag(dev.mangaflow.R.id.feel_feedback_time) as? Long
        if (previous != null && now - previous < MangaMotion.HapticInterval) return
        if (lastAt != Long.MIN_VALUE && now - lastAt < MangaMotion.HapticInterval) return
        val constant = when (event) {
            FeelEvent.Selection -> HapticFeedbackConstantsCompat.SEGMENT_TICK
            FeelEvent.Threshold -> HapticFeedbackConstantsCompat.GESTURE_THRESHOLD_ACTIVATE
            FeelEvent.Confirm -> HapticFeedbackConstantsCompat.CONFIRM
            FeelEvent.Reject -> HapticFeedbackConstantsCompat.REJECT
            FeelEvent.Undo -> HapticFeedbackConstantsCompat.GESTURE_END
        }
        // ViewCompat supplies API/hardware fallback and honors system/view haptic settings.
        // Never use FLAG_IGNORE_GLOBAL_SETTING, a vibration pattern, or VIBRATE permission.
        if (ViewCompat.performHapticFeedback(view, constant)) {
            lastAt = now
            view.setTag(dev.mangaflow.R.id.feel_feedback_time, now)
        }
    }
}

@Composable
internal fun rememberFeelFeedback(): FeelFeedback {
    val view = LocalView.current
    return remember(view) { FeelFeedback(view) }
}
