package dev.mangaflow.anime.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun AnimePlayerOrientation(fullscreen: Boolean, active: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.playerActivity() } ?: return
    val originalOrientation = rememberSaveable { activity.requestedOrientation }
    var returnOrientation by rememberSaveable { mutableStateOf<Int?>(null) }

    DisposableEffect(activity, active) {
        onDispose {
            if (active) activity.requestedOrientation = originalOrientation
        }
    }
    LaunchedEffect(activity, active, fullscreen) {
        if (!active) return@LaunchedEffect
        // Rotation is asynchronous; repeated toggles must not overwrite the original return direction.
        if (fullscreen && returnOrientation == null) {
            returnOrientation = when (activity.resources.configuration.orientation) {
                Configuration.ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                Configuration.ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else -> originalOrientation
            }
        }
        // Keep the pre-fullscreen direction across PiP without retaining its window request.
        val orientation = if (fullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else returnOrientation ?: originalOrientation
        if (activity.requestedOrientation != orientation) activity.requestedOrientation = orientation
    }
}

private tailrec fun Context.playerActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.playerActivity()
    else -> null
}
