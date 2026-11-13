@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.mangaflow.reader

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
internal actual fun ReaderWindow(keepScreenOn: Boolean, controlsVisible: Boolean, lightChrome: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    val window = remember(context) { context.activity()?.window }
    DisposableEffect(window, view) {
        val originalKeepOn = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val insets = ViewCompat.getRootWindowInsets(view)
        val oldStatusVisible = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
        val oldNavigationVisible = insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
        val oldLightStatus = controller?.isAppearanceLightStatusBars ?: false
        val oldLightNavigation = controller?.isAppearanceLightNavigationBars ?: false
        val oldBehavior = controller?.systemBarsBehavior ?: WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        onDispose {
            if (originalKeepOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller?.apply {
                isAppearanceLightStatusBars = oldLightStatus
                isAppearanceLightNavigationBars = oldLightNavigation
                systemBarsBehavior = oldBehavior
                if (oldStatusVisible) show(WindowInsetsCompat.Type.statusBars()) else hide(WindowInsetsCompat.Type.statusBars())
                if (oldNavigationVisible) show(WindowInsetsCompat.Type.navigationBars()) else hide(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }
    LaunchedEffect(window, view, keepScreenOn, controlsVisible, lightChrome) {
        window?.let {
            if (keepScreenOn) it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else it.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.getInsetsController(it, view).apply {
                isAppearanceLightStatusBars = lightChrome
                isAppearanceLightNavigationBars = lightChrome
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (controlsVisible) show(WindowInsetsCompat.Type.systemBars()) else hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
internal actual fun ConfigureReaderDialogWindow(dimAmount: Float) {
    val window = (LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
    androidx.compose.runtime.SideEffect { window?.setDimAmount(dimAmount.coerceIn(0f, 1f)) }
}

@Composable
internal actual fun readerNavigationInsets(): WindowInsets = WindowInsets.navigationBarsIgnoringVisibility
@Composable
internal actual fun readerStatusInsets(): WindowInsets = WindowInsets.statusBarsIgnoringVisibility
