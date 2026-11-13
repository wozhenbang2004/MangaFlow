package dev.mangaflow.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

@Immutable
internal data class PlatformAccessibilityPreferences(
    val motionEnabled: Boolean = true,
    val touchExploration: Boolean = false,
    val reduceTransparency: Boolean = false,
    val increaseContrast: Boolean = false,
)

internal val LocalPlatformAccessibilityPreferences = staticCompositionLocalOf { PlatformAccessibilityPreferences() }

@Composable
internal expect fun rememberPlatformAccessibilityPreferences(): PlatformAccessibilityPreferences

@Composable
internal expect fun rememberNativeFeelFeedback(): (FeelEvent) -> Boolean

/** Android disables its separate native window animation; other hosts animate the same Compose layer. */
@Composable
internal expect fun MotionDialogWindowPolicy(visible: Boolean)
