package dev.mangaflow

import android.Manifest
import android.animation.ValueAnimator
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
import androidx.compose.runtime.CompositionLocalProvider
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.ui.MangaFlowTheme
import dev.mangaflow.experience.ExperiencePolicyStore
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import android.view.accessibility.AccessibilityManager
import android.view.animation.PathInterpolator
import androidx.annotation.RequiresApi
import androidx.core.animation.doOnEnd
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
    private var launchExitAllowed = false
    private var launchExit: ValueAnimator? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The starting window owns the portrait. The Activity and its first Compose frame own the same plain background.
        setTheme(R.style.Theme_MangaFlow)
        super.onCreate(savedInstanceState)
        val firstMainWindow = !mainWindowCreated
        mainWindowCreated = true
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && firstMainWindow &&
            savedInstanceState == null && intent.isOrdinaryLauncherOpen()) {
            installLaunchExit()
        }
        consumeNavigationIntent(intent)
        (application as MangaFlowApplication).initializeServices()
        experience = ExperiencePolicyStore.get(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                experience?.state?.collect { preferDisplayRefreshRate() }
            }
        }
        setContent {
            MangaFlowHost(this@MainActivity, openUpdatesRequest, openDownloadsRequest,
                ::requestNotificationPermission, animateLaunch = false)
        }
    }

    private fun Intent.isOrdinaryLauncherOpen(): Boolean {
        // Both download notifications use CLEAR_TOP. The anime one has no navigation extra.
        val returning = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
        return action == Intent.ACTION_MAIN && hasCategory(Intent.CATEGORY_LAUNCHER) && data == null &&
            (flags and returning) == 0 && !getBooleanExtra("open_updates", false) && !getBooleanExtra("open_downloads", false)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun installLaunchExit() {
        launchExitAllowed = true
        splashScreen.setOnExitAnimationListener { splash ->
            val portrait = splash.iconView
            val accessibility = getSystemService(AccessibilityManager::class.java)
            if (!launchExitAllowed || isFinishing || isDestroyed || portrait == null || portrait.width == 0 ||
                portrait.height == 0 || !ValueAnimator.areAnimatorsEnabled() || accessibility?.isTouchExplorationEnabled == true) {
                splash.remove()
                return@setOnExitAnimationListener
            }
            // The platform calls this with content already drawn. Only its existing portrait and surface are animated.
            val exit = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 176L
                interpolator = PathInterpolator(.2f, 0f, .2f, 1f)
                addUpdateListener { animation ->
                    val fraction = animation.animatedFraction
                    val scale = 1f - .06f * fraction
                    portrait.scaleX = scale
                    portrait.scaleY = scale
                    splash.alpha = 1f - fraction
                }
                doOnEnd {
                    launchExit = null
                    splash.remove()
                }
            }
            launchExit = exit
            exit.start()
        }
    }

    private fun finishLaunchExit() {
        launchExitAllowed = false
        launchExit?.end()
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
        finishLaunchExit()
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
        finishLaunchExit()
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

    private companion object {
        var mainWindowCreated = false
    }
}
