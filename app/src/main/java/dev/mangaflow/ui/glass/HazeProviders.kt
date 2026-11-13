package dev.mangaflow.ui.glass

import android.app.UiModeManager
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.LocalHazePerformanceMode
import dev.chrisbanes.haze.glass.LocalGlassAccessibilitySettings

/** Map host preferences without adding a rendering/skin settings panel. */
@Composable
internal fun HazeProviders(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var contrast by remember { mutableFloatStateOf(0f) }
    DisposableEffect(context) {
        if (Build.VERSION.SDK_INT >= 34) {
            val manager = context.getSystemService(UiModeManager::class.java)
            contrast = manager?.contrast ?: 0f
            val listener = UiModeManager.ContrastChangeListener { contrast = it }
            manager?.addContrastChangeListener(ContextCompat.getMainExecutor(context), listener)
            onDispose { manager?.removeContrastChangeListener(listener) }
        } else onDispose { }
    }
    val inherited = LocalGlassAccessibilitySettings.current
    val surface = MaterialTheme.colorScheme.surface.copy(alpha = 1f)
    val accessibility = remember(inherited, surface, contrast) {
        inherited.copy(increaseContrast = inherited.increaseContrast || contrast > 0f,
            reducedTransparencyFallbackColor = surface)
    }
    CompositionLocalProvider(
        LocalHazePerformanceMode provides if (MangaHazeRuntime.isLowRamDevice)
            HazePerformanceMode.Balanced else HazePerformanceMode.Quality,
        LocalGlassAccessibilitySettings provides accessibility,
        content = content,
    )
}
