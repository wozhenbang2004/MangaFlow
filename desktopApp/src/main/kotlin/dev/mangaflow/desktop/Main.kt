package dev.mangaflow.desktop

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import dev.mangaflow.MangaViewModel
import dev.mangaflow.platform.storage.flushLocalPreferences
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.input.key.*
import dev.mangaflow.MangaFlowHost
import dev.mangaflow.initializeMangaFlow
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.update.DesktopUpdateExit
import dev.mangaflow.reader.ProvideDesktopReaderKeyboard
import dev.mangaflow.ui.MangaFlowTheme
import androidx.compose.material3.MaterialTheme
import java.io.File

fun main() {
    System.setProperty("compose.interop.blending", "true")
    System.setProperty("java.net.useSystemProxies", "true")
    val userDirectory = System.getProperty("user.home")
    check(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "此桌面发行版支持 Windows。"
    }
    val localData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
        ?: File(userDirectory, "AppData/Local").absolutePath
    val data = File(localData, "MangaFlow")
    val cache = File(data, "cache")
    check(data.isDirectory || data.mkdirs()) { "无法创建漫流数据目录：$data" }
    check(cache.isDirectory || cache.mkdirs()) { "无法创建漫流缓存目录：$cache" }
    val context = object : PlatformContext(data.absolutePath, cache.absolutePath) {}
    initializeMangaFlow(context)
    var lastModel: MangaViewModel? = null
    try {
        application(exitProcessOnExit = false) {
            var model by remember { mutableStateOf<MangaViewModel?>(null) }
            var closing by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            val currentReading = model?.reading?.collectAsState()?.value
            val windowState = rememberWindowState(width = 1120.dp, height = 800.dp)
            var restoredPlacement by remember { mutableStateOf(WindowPlacement.Floating) }
            val requestClose: () -> Unit = {
                if (!closing) {
                    closing = true
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) { model?.library?.flushPendingWrites() ?: flushLocalPreferences() }
                        if (saved) exitApplication()
                        else { closing = false; model?.notifyStorageFailure() }
                    }
                }
            }
            DisposableEffect(requestClose) {
                DesktopUpdateExit.requestExit = requestClose
                onDispose { DesktopUpdateExit.requestExit = null }
            }
            Window(onCloseRequest = requestClose, title = currentReading?.manga?.title?.takeIf { it.isNotBlank() }?.let { "$it · 漫流" } ?: "漫流",
                state = windowState,
                onPreviewKeyEvent = { event ->
                    when {
                        event.key == Key.F11 -> {
                            // Commit on release: holding F11 cannot toggle repeatedly.
                            if (event.type == KeyEventType.KeyUp) {
                                if (windowState.placement == WindowPlacement.Fullscreen) {
                                    windowState.placement = restoredPlacement
                                } else {
                                    restoredPlacement = windowState.placement
                                    windowState.placement = WindowPlacement.Fullscreen
                                }
                            }
                            true
                        }
                        event.key == Key.Escape &&
                            windowState.placement == WindowPlacement.Fullscreen -> {
                            // Consume the complete Escape stroke before the reader's Back.
                            if (event.type == KeyEventType.KeyUp) windowState.placement = restoredPlacement
                            true
                        }
                        else -> false
                    }
                },
                icon = painterResource("mangaflow.png")) {
                window.minimumSize = java.awt.Dimension(320, 320)
                MangaFlowTheme {
                    DesktopWindowChrome(window, MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.onBackground)
                    ProvideDesktopReaderKeyboard(context) {
                        MangaFlowHost(context, onModelReady = { model = it; lastModel = it })
                    }
                }
            }
        }
    } finally {
        // Reader disposal can publish its final position after the window-close preflight.
        val saved = runBlocking(Dispatchers.IO) { lastModel?.library?.flushPendingWrites() ?: flushLocalPreferences() }
        // Embedded website/video windows belong to this application process.
        runCatching { Class.forName("javafx.application.Platform").getMethod("exit").invoke(null) }
        if (!saved) System.err.println("漫流未能保存关闭时的最终记录，请检查存储空间。")
        kotlin.system.exitProcess(if (saved) 0 else 1)
    }
}
