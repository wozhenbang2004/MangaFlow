package dev.mangaflow.ui.glass

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.LocalHazePerformanceMode
import dev.chrisbanes.haze.glass.LocalGlassAccessibilitySettings
import dev.mangaflow.ui.motion.LocalPlatformAccessibilityPreferences

/** Map host preferences without adding a rendering/skin settings panel. */
@Composable
fun HazeProviders(content: @Composable () -> Unit) {
    val preferences = LocalPlatformAccessibilityPreferences.current
    val inherited = LocalGlassAccessibilitySettings.current
    val surface = MaterialTheme.colorScheme.surface.copy(alpha = 1f)
    val accessibility = remember(inherited, surface, preferences) {
        inherited.copy(
            increaseContrast = inherited.increaseContrast || preferences.increaseContrast,
            reduceTransparency = inherited.reduceTransparency || preferences.reduceTransparency,
            reducedTransparencyFallbackColor = surface,
        )
    }
    CompositionLocalProvider(
        LocalHazePerformanceMode provides if (MangaHazeRuntime.isLowRamDevice)
            HazePerformanceMode.Balanced else HazePerformanceMode.Quality,
        LocalGlassAccessibilitySettings provides accessibility,
        content = content,
    )
}
