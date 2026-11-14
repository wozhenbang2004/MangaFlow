package dev.mangaflow.desktop

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import dev.mangaflow.MangaViewModel
import dev.mangaflow.platform.storage.flushLocalPreferences
import dev.mangaflow.platform.storage.getSharedPreferences
import dev.mangaflow.platform.storage.Preferences
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.input.key.*
import dev.mangaflow.MangaFlowHost
import dev.mangaflow.initializeMangaFlow
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.update.DesktopUpdateExit
import dev.mangaflow.reader.ProvideDesktopReaderKeyboard
import dev.mangaflow.ui.MangaFlowTheme
import androidx.compose.material3.MaterialTheme
import java.io.File
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import kotlin.math.roundToInt

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
    val windowPreferences = DesktopWindowPreferences(context)
    var lastModel: MangaViewModel? = null
    try {
        application(exitProcessOnExit = false) {
            var model by remember { mutableStateOf<MangaViewModel?>(null) }
            var closing by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            val currentReading = model?.reading?.collectAsState()?.value
            val windowState = rememberWindowState(
                size = windowPreferences.initialSize,
                position = windowPreferences.initialPosition,
                placement = windowPreferences.initialPlacement,
            )
            var restoredPlacement by remember { mutableStateOf(windowPreferences.initialPlacement) }
            LaunchedEffect(windowState, windowPreferences) {
                snapshotFlow { windowState.desktopSnapshot(restoredPlacement) }.collectLatest { snapshot ->
                    // Capture immediately for close; persist only once movement has settled.
                    windowPreferences.capture(snapshot)
                    delay(400)
                    windowPreferences.save()
                }
            }
            val requestClose: () -> Unit = {
                if (!closing) {
                    closing = true
                    windowPreferences.capture(windowState.desktopSnapshot(restoredPlacement))
                    windowPreferences.save()
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
                CompositionLocalProvider(LocalMangaContext provides context) {
                    MangaFlowTheme {
                        DesktopWindowChrome(window, MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.onBackground)
                        ProvideDesktopReaderKeyboard(context) {
                            MangaFlowHost(context, onModelReady = { model = it; lastModel = it })
                        }
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

private data class DesktopWindowBounds(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun rectangle() = Rectangle(x, y, width, height)
}
private data class DesktopWorkArea(val id: String, val bounds: Rectangle)
private data class DesktopWindowSnapshot(
    val size: DpSize,
    val position: WindowPosition,
    val placement: WindowPlacement,
    val minimized: Boolean,
    val beforeFullscreen: WindowPlacement,
)
private fun WindowState.desktopSnapshot(beforeFullscreen: WindowPlacement) =
    DesktopWindowSnapshot(size, position, placement, isMinimized, beforeFullscreen)

/** Compose 1.12 maps these window units directly to AWT coordinates; never apply image-pixel scale. */
private fun desktopWorkAreas(): List<DesktopWorkArea> = runCatching {
    val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val primary = environment.defaultScreenDevice
    environment.screenDevices.sortedBy { if (it == primary) 0 else 1 }.mapNotNull { device ->
        val configuration = device.defaultConfiguration
        val bounds = Rectangle(configuration.bounds)
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
        bounds.x += insets.left
        bounds.y += insets.top
        bounds.width -= insets.left + insets.right
        bounds.height -= insets.top + insets.bottom
        if (bounds.width > 0 && bounds.height > 0) DesktopWorkArea(device.iDstring, bounds) else null
    }
}.getOrDefault(emptyList())

private fun List<DesktopWorkArea>.bestFor(bounds: DesktopWindowBounds): DesktopWorkArea? =
    maxByOrNull { area ->
        val intersection = area.bounds.intersection(bounds.rectangle())
        intersection.width.coerceAtLeast(0).toLong() * intersection.height.coerceAtLeast(0)
    }?.takeIf { it.bounds.intersects(bounds.rectangle()) } ?: firstOrNull()

private fun restoreWindowBounds(preferences: Preferences): DesktopWindowBounds? {
    val areas = desktopWorkAreas()
    if (areas.isEmpty()) return null
    return runCatching {
        val hasPosition = preferences.contains("normalX") && preferences.contains("normalY")
        val stored = DesktopWindowBounds(
            preferences.getInt("normalX"), preferences.getInt("normalY"),
            preferences.getInt("normalWidth", 1120).coerceAtLeast(320),
            preferences.getInt("normalHeight", 800).coerceAtLeast(320),
        )
        val area = areas.firstOrNull { it.id == preferences.getString("monitor") }
            ?: if (hasPosition) areas.bestFor(stored)!! else areas.first()
        val work = area.bounds
        val width = stored.width.coerceIn(minOf(320, work.width), work.width)
        val height = stored.height.coerceIn(minOf(320, work.height), work.height)
        val x = if (hasPosition) stored.x.coerceIn(work.x, work.x + work.width - width)
            else work.x + (work.width - width) / 2
        val y = if (hasPosition) stored.y.coerceIn(work.y, work.y + work.height - height)
            else work.y + (work.height - height) / 2
        DesktopWindowBounds(x, y, width, height)
    }.getOrElse {
        val work = areas.first().bounds
        val width = minOf(1120, work.width)
        val height = minOf(800, work.height)
        DesktopWindowBounds(work.x + (work.width - width) / 2,
            work.y + (work.height - height) / 2, width, height)
    }
}

private class DesktopWindowPreferences(context: PlatformContext) {
    private val disk = context.getSharedPreferences("mangaflow_desktop_window")
    private var normalBounds = restoreWindowBounds(disk)
    private var currentBounds = normalBounds
    private var maximized = runCatching { disk.getBoolean("maximized") }.getOrDefault(false)
    val initialSize = normalBounds?.let { DpSize(it.width.dp, it.height.dp) } ?: DpSize(1120.dp, 800.dp)
    val initialPosition = normalBounds?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition.PlatformDefault
    val initialPlacement = if (maximized) WindowPlacement.Maximized else WindowPlacement.Floating

    fun capture(snapshot: DesktopWindowSnapshot) {
        if (snapshot.minimized) return
        maximized = (if (snapshot.placement == WindowPlacement.Fullscreen)
            snapshot.beforeFullscreen else snapshot.placement) == WindowPlacement.Maximized
        val width = snapshot.size.width.value
        val height = snapshot.size.height.value
        val position = snapshot.position
        if (!width.isFinite() || !height.isFinite() || width < 320 || height < 320 ||
            !position.isSpecified || !position.x.value.isFinite() || !position.y.value.isFinite()) return
        val bounds = DesktopWindowBounds(position.x.value.roundToInt(), position.y.value.roundToInt(),
            width.roundToInt(), height.roundToInt())
        currentBounds = bounds
        // Maximized and fullscreen resize events must never overwrite the restore rectangle.
        if (snapshot.placement == WindowPlacement.Floating) normalBounds = bounds
    }

    fun save() {
        runCatching {
            val edit = disk.edit().putBoolean("maximized", maximized)
            normalBounds?.let { bounds ->
                edit.putInt("normalX", bounds.x).putInt("normalY", bounds.y)
                    .putInt("normalWidth", bounds.width).putInt("normalHeight", bounds.height)
            }
            currentBounds?.let { bounds -> desktopWorkAreas().bestFor(bounds)?.let { edit.putString("monitor", it.id) } }
            // Existing preference draining covers normal close and installer handoff.
            edit.apply()
        }.onFailure { System.err.println("漫流未能保存窗口布局。") }
    }
}
