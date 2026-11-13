package dev.mangaflow.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.mangaflow.platform.monotonicTimeMillis

/** Meaning belongs to the action, never to an animation frame or a network callback. */
internal enum class FeelEvent { Selection, Threshold, Confirm, Reject, Undo }

internal class FeelFeedback(private val nativeFeedback: (FeelEvent) -> Boolean) {
    private var lastAt = Long.MIN_VALUE

    fun perform(event: FeelEvent) {
        val now = monotonicTimeMillis()
        if (lastAt != Long.MIN_VALUE && now - lastAt < MangaMotion.HapticInterval) return
        if (nativeFeedback(event)) lastAt = now
    }
}

@Composable
internal fun rememberFeelFeedback(): FeelFeedback {
    val nativeFeedback = rememberNativeFeelFeedback()
    return remember(nativeFeedback) { FeelFeedback(nativeFeedback) }
}
