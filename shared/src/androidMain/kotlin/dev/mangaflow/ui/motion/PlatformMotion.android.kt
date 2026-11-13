package dev.mangaflow.ui.motion

import android.app.UiModeManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.monotonicTimeMillis
import java.util.WeakHashMap

@Composable
internal actual fun rememberPlatformAccessibilityPreferences(): PlatformAccessibilityPreferences {
    val context = LocalMangaContext.current
    val resolver = context.contentResolver
    val accessibility = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var touchExploration by remember(accessibility) { mutableStateOf(accessibility?.isTouchExplorationEnabled == true) }
    var contrast by remember { mutableStateOf(false) }
    fun enabled() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    var motionEnabled by remember(resolver) { mutableStateOf(enabled()) }
    DisposableEffect(accessibility) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { touchExploration = it }
        accessibility?.addTouchExplorationStateChangeListener(listener)
        touchExploration = accessibility?.isTouchExplorationEnabled == true
        onDispose { accessibility?.removeTouchExplorationStateChangeListener(listener) }
    }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { motionEnabled = enabled() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        motionEnabled = enabled()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    DisposableEffect(context) {
        if (Build.VERSION.SDK_INT >= 34) {
            val manager = context.getSystemService(UiModeManager::class.java)
            contrast = (manager?.contrast ?: 0f) > 0f
            val listener = UiModeManager.ContrastChangeListener { contrast = it > 0f }
            manager?.addContrastChangeListener(ContextCompat.getMainExecutor(context), listener)
            onDispose { manager?.removeContrastChangeListener(listener) }
        } else onDispose { }
    }
    return PlatformAccessibilityPreferences(motionEnabled, touchExploration, increaseContrast = contrast)
}

// Weak window keys replace the former application R.id tag without retaining disposed windows.
private val windowFeedbackTimes = WeakHashMap<View, Long>()

@Composable
internal actual fun rememberNativeFeelFeedback(): (FeelEvent) -> Boolean {
    val view = LocalView.current
    return remember(view) {
        feedback@{ event ->
            val owner = view.rootView
            val now = monotonicTimeMillis()
            val previous = windowFeedbackTimes[owner]
            if (previous != null && now - previous < MangaMotion.HapticInterval) return@feedback false
            val constant = when (event) {
                FeelEvent.Selection -> HapticFeedbackConstantsCompat.SEGMENT_TICK
                FeelEvent.Threshold -> HapticFeedbackConstantsCompat.GESTURE_THRESHOLD_ACTIVATE
                FeelEvent.Confirm -> HapticFeedbackConstantsCompat.CONFIRM
                FeelEvent.Reject -> HapticFeedbackConstantsCompat.REJECT
                FeelEvent.Undo -> HapticFeedbackConstantsCompat.GESTURE_END
            }
            // Native fallback honors hardware and system/view haptic settings.
            ViewCompat.performHapticFeedback(view, constant).also { accepted ->
                if (accepted) windowFeedbackTimes[owner] = now
            }
        }
    }
}

@Composable
internal actual fun MotionDialogWindowPolicy(visible: Boolean) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect {
        window?.setWindowAnimations(0)
        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (visible) window?.clearFlags(flags) else window?.addFlags(flags)
    }
}
