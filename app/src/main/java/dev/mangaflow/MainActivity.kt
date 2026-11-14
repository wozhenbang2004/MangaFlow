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
import dev.mangaflow.experience.ExperiencePolicyStore
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

class MainActivity : ComponentActivity() {
    private var refreshRateBeforeResume: Float? = null
    private var displayPreferenceActive = false
    private var experience: ExperiencePolicyStore? = null
    private var frameObserverActive = false
    private val frameObserver = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        experience?.recordFrame(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
    }
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
        experience = ExperiencePolicyStore.get(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                experience?.state?.collect { preferDisplayRefreshRate() }
            }
        }
        setContent {
            MangaFlowHost(this@MainActivity, openUpdatesRequest, openDownloadsRequest,
                ::requestNotificationPermission, animateLaunch)
        }
    }

    override fun onResume() {
        super.onResume()
        displayPreferenceActive = true
        if (experience != null && !frameObserverActive) {
            window.addOnFrameMetricsAvailableListener(frameObserver, Handler(Looper.getMainLooper()))
            frameObserverActive = true
        }
        preferDisplayRefreshRate()
    }

    override fun onPause() {
        displayPreferenceActive = false
        if (frameObserverActive) {
            window.removeOnFrameMetricsAvailableListener(frameObserver)
            frameObserverActive = false
        }
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
        val budget = experience?.state?.value
        val supported = display.supportedModes.asSequence()
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .map { it.refreshRate }.filter { it.isFinite() && it > 0f }.toList()
        val refreshRate = if (budget?.preferHighRefresh == true) {
            val cap = budget.refreshHz
            (if (cap == null) supported else supported.filter { it <= cap + .5f }).maxOrNull() ?: 0f
        } else 0f
        val attributes = window.attributes
        if (refreshRateBeforeResume == null) refreshRateBeforeResume = attributes.preferredRefreshRate
        // Idle/eco/protection explicitly release the vote. Resolution stays system-owned.
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
