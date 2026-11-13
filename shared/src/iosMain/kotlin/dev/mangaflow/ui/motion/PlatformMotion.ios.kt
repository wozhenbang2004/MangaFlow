@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.mangaflow.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.mangaflow.platform.monotonicTimeMillis
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIAccessibilityDarkerSystemColorsStatusDidChangeNotification
import platform.UIKit.UIAccessibilityIsDarkerSystemColorsEnabled
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIAccessibilityIsReduceTransparencyEnabled
import platform.UIKit.UIAccessibilityIsVoiceOverRunning
import platform.UIKit.UIAccessibilityReduceMotionStatusDidChangeNotification
import platform.UIKit.UIAccessibilityReduceTransparencyStatusDidChangeNotification
import platform.UIKit.UIAccessibilityVoiceOverStatusDidChangeNotification
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import platform.UIKit.UISelectionFeedbackGenerator

private fun readAccessibilityPreferences() = PlatformAccessibilityPreferences(
    motionEnabled = !UIAccessibilityIsReduceMotionEnabled(),
    touchExploration = UIAccessibilityIsVoiceOverRunning(),
    reduceTransparency = UIAccessibilityIsReduceTransparencyEnabled(),
    increaseContrast = UIAccessibilityIsDarkerSystemColorsEnabled(),
)

@Composable
internal actual fun rememberPlatformAccessibilityPreferences(): PlatformAccessibilityPreferences {
    var preferences by remember { mutableStateOf(readAccessibilityPreferences()) }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val observers = listOf(
            UIAccessibilityReduceMotionStatusDidChangeNotification,
            UIAccessibilityVoiceOverStatusDidChangeNotification,
            UIAccessibilityReduceTransparencyStatusDidChangeNotification,
            UIAccessibilityDarkerSystemColorsStatusDidChangeNotification,
        ).map { name ->
            center.addObserverForName(name, null, NSOperationQueue.mainQueue) {
                preferences = readAccessibilityPreferences()
            }
        }
        preferences = readAccessibilityPreferences()
        onDispose { observers.forEach { center.removeObserver(it) } }
    }
    return preferences
}

private var lastNativeFeedback = Long.MIN_VALUE

@Composable
internal actual fun rememberNativeFeelFeedback(): (FeelEvent) -> Boolean {
    val selection = remember { UISelectionFeedbackGenerator() }
    val impact = remember { UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight) }
    val notification = remember { UINotificationFeedbackGenerator() }
    return remember(selection, impact, notification) {
        feedback@{ event ->
            val now = monotonicTimeMillis()
            if (lastNativeFeedback != Long.MIN_VALUE && now - lastNativeFeedback < MangaMotion.HapticInterval) return@feedback false
            when (event) {
                FeelEvent.Selection -> selection.selectionChanged()
                FeelEvent.Threshold, FeelEvent.Undo -> impact.impactOccurred()
                FeelEvent.Confirm -> notification.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
                FeelEvent.Reject -> notification.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeError)
            }
            lastNativeFeedback = now
            true
        }
    }
}

@Composable
internal actual fun MotionDialogWindowPolicy(visible: Boolean) {
    // Compose's iOS dialog has no second native-window entrance animation. The shared
    // presence gate/focus policy owns outgoing content until its actual disposal.
}
