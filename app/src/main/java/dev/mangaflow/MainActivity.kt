package dev.mangaflow

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import dev.mangaflow.MangaFlowHost
import dev.mangaflow.initializeMangaFlow
import androidx.compose.runtime.CompositionLocalProvider
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.ui.MangaFlowTheme

class MainActivity : ComponentActivity() {
    private var refreshRateBeforeResume: Float? = null
    private var displayPreferenceActive = false
    private var openUpdatesRequest by mutableIntStateOf(0)
    private var openDownloadsRequest by mutableIntStateOf(0)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LaunchFailure.recoveryReport?.let { report ->
            setContent {
                CompositionLocalProvider(LocalMangaContext provides this@MainActivity) {
                  MangaFlowTheme {
                    dev.mangaflow.ui.LaunchFailureMessage(report) {
                        LaunchFailure.retry()
                        (application as MangaFlowApplication).initializeServices()
                        recreate()
                    }
                  }
                }
            }
            return
        }
        enableEdgeToEdge()
        // Keep the real app visible behind three-button navigation as well as gestures.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        val animateLaunch = savedInstanceState == null &&
            !intent.getBooleanExtra("open_updates", false) && !intent.getBooleanExtra("open_downloads", false)
        consumeNavigationIntent(intent)
        initializeMangaFlow(this)
        setContent {
            MangaFlowHost(this@MainActivity, openUpdatesRequest, openDownloadsRequest,
                ::requestNotificationPermission, animateLaunch)
        }
    }

    override fun onResume() {
        super.onResume()
        displayPreferenceActive = true
        preferDisplayRefreshRate()
    }

    override fun onPause() {
        displayPreferenceActive = false
        refreshRateBeforeResume?.let { original ->
            val attributes = window.attributes
            if (attributes.preferredRefreshRate != original) {
                attributes.preferredRefreshRate = original
                window.attributes = attributes
            }
        }
        refreshRateBeforeResume = null
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) preferDisplayRefreshRate()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        preferDisplayRefreshRate()
    }

    private fun preferDisplayRefreshRate() {
        if (!displayPreferenceActive) return
        val display = window.decorView.display ?: return
        val current = display.mode
        val refreshRate = display.supportedModes.asSequence()
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .map { it.refreshRate }.filter { it.isFinite() && it > 0f }.maxOrNull() ?: return
        val attributes = window.attributes
        if (refreshRateBeforeResume == null) refreshRateBeforeResume = attributes.preferredRefreshRate
        // A rate hint keeps resolution and system power/thermal decisions with Android.
        // Per-draw Compose votes complement this window hint on adaptive-refresh devices.
        if (attributes.preferredRefreshRate != refreshRate) {
            attributes.preferredRefreshRate = refreshRate
            window.attributes = attributes
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeNavigationIntent(intent)
    }

    private fun consumeNavigationIntent(intent: Intent) {
        if (intent.getBooleanExtra("open_updates", false)) openUpdatesRequest++
        else if (intent.getBooleanExtra("open_downloads", false)) openDownloadsRequest++
        intent.removeExtra("open_updates")
        intent.removeExtra("open_downloads")
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
