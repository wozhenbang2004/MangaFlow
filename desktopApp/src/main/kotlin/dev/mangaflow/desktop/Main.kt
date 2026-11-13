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
import dev.mangaflow.MangaFlowHost
import dev.mangaflow.initializeMangaFlow
import dev.mangaflow.platform.PlatformContext
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
            Window(onCloseRequest = {
                if (!closing) {
                    closing = true
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) { model?.library?.flushPendingWrites() ?: flushLocalPreferences() }
                        if (saved) exitApplication()
                        else { closing = false; model?.notifyStorageFailure() }
                    }
                }
            }, title = "漫流 · MangaFlow",
                state = rememberWindowState(width = 1120.dp, height = 800.dp),
                icon = painterResource("mangaflow.png")) {
                window.minimumSize = java.awt.Dimension(320, 320)
                MangaFlowHost(context, onModelReady = { model = it; lastModel = it })
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
