package dev.mangaflow.ui.glass

import android.app.ActivityManager
import android.content.Context
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.LayerOutsets
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeFeatureFlags
import dev.chrisbanes.haze.HazeInputSharing
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/** Configure before modifier attachment; Haze checks native eligibility and owns fallback. */
@OptIn(ExperimentalHazeApi::class)
internal object MangaHazeRuntime {
    var isLowRamDevice = true
        private set

    fun initialize(context: Context) {
        isLowRamDevice = context.getSystemService(ActivityManager::class.java)?.isLowRamDevice != false
        HazeFeatureFlags.isPlatformBackdropEnabled = true
    }

    fun source(modifier: Modifier, state: HazeState): Modifier = modifier.hazeSource(
        state = state,
        inputSharing = if (isLowRamDevice) HazeInputSharing.Never else HazeInputSharing.Auto,
        inputSharingOutsets = LayerOutsets(left = 32.dp, top = 32.dp, right = 32.dp, bottom = 32.dp),
    )
}

internal fun Modifier.mangaHazeSource(state: HazeState): Modifier = MangaHazeRuntime.source(this, state)
