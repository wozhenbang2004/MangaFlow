package dev.mangaflow

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import dev.mangaflow.ui.AppLaunchReveal
import dev.mangaflow.ui.MangaFlowApp
import dev.mangaflow.ui.MangaFlowTheme
import dev.mangaflow.ui.motion.MangaMotionProvider

class MainActivity : ComponentActivity() {
    private var openUpdatesRequest by mutableIntStateOf(0)
    private var openDownloadsRequest by mutableIntStateOf(0)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val animateLaunch = savedInstanceState == null &&
            !intent.getBooleanExtra("open_updates", false) && !intent.getBooleanExtra("open_downloads", false)
        consumeNavigationIntent(intent)
        setContent {
            MangaFlowTheme {
                MangaMotionProvider {
                    dev.mangaflow.ui.glass.HazeProviders {
                        AppLaunchReveal(play = animateLaunch) {
                            MangaFlowApp(openUpdatesRequest, openDownloadsRequest, ::requestNotificationPermission)
                        }
                    }
                }
            }
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
