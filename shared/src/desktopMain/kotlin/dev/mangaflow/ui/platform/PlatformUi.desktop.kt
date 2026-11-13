package dev.mangaflow.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import coil3.BitmapImage
import coil3.Image
import coil3.request.ImageRequest
import dev.mangaflow.account.CopyWebsiteEndpoints
import dev.mangaflow.platform.PlatformContext
import javafx.application.Platform
import javafx.beans.value.ChangeListener
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.scene.web.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import netscape.javascript.JSObject
import java.awt.Desktop
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

private val fxLock = Any()
private var fxInitialized = false
internal fun initializeJavaFx() {
    synchronized(fxLock) {
        if (fxInitialized) return
        try { Platform.startup { Platform.setImplicitExit(false) } }
        catch (alreadyStarted: IllegalStateException) { Platform.setImplicitExit(false) }
        fxInitialized = true
    }
}
@Composable
actual fun rememberDocumentPicker(kind: DocumentPickerKind, onResult: (List<String>) -> Unit): DocumentPickerLauncher {
    val latest = rememberUpdatedState(onResult)
    val scope = rememberCoroutineScope()
    return remember(kind) {
        DocumentPickerLauncher { _, name ->
            SwingUtilities.invokeLater {
                val chooser = JFileChooser().apply {
                    dialogTitle = when (kind) {
                        DocumentPickerKind.FOLDER -> "导入漫画文件夹"
                        else -> "选择文件"
                    }
                    isMultiSelectionEnabled = kind == DocumentPickerKind.OPEN_MULTIPLE
                    fileSelectionMode = if (kind == DocumentPickerKind.FOLDER) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY
                    if (name != null) selectedFile = File(name)
                }
                val result = chooser.showOpenDialog(null)
                val selections = if (result == JFileChooser.APPROVE_OPTION) {
                    val files = if (kind == DocumentPickerKind.OPEN_MULTIPLE) chooser.selectedFiles.toList() else listOf(chooser.selectedFile)
                    files.map { it.toURI().toString() }
                } else emptyList()
                scope.launch { latest.value(selections) }
            }
        }
    }
}
@Composable
actual fun rememberTextDocumentSaver(onResult: (DocumentSaveResult) -> Unit): TextDocumentSaver {
    val latest = rememberUpdatedState(onResult)
    val scope = rememberCoroutineScope()
    return remember { TextDocumentSaver { name, text ->
        SwingUtilities.invokeLater {
            val chooser = JFileChooser().apply { dialogTitle = "导出阅读备份"; selectedFile = File(name) }
            if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) scope.launch { latest.value(DocumentSaveResult(false)) }
            else {
                val file = chooser.selectedFile
                if (file.exists() && JOptionPane.showConfirmDialog(null, "覆盖已存在的同名文件？", "导出备份",
                    JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) scope.launch { latest.value(DocumentSaveResult(false)) }
                else scope.launch {
                    val result = runCatching { withContext(Dispatchers.IO) {
                        val target = file.toPath().toAbsolutePath()
                        val temporary = Files.createTempFile(target.parent, ".mangaflow-export-", ".json")
                        try {
                            Files.newBufferedWriter(temporary, StandardCharsets.UTF_8).use { it.write(text) }
                            try { Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                                Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            }
                        } finally { Files.deleteIfExists(temporary) }
                    } }
                    latest.value(if (result.isSuccess) DocumentSaveResult(true) else DocumentSaveResult(false,
                        result.exceptionOrNull()?.message ?: "备份导出失败"))
                }
            }
        }
    } }
}

private fun documentPath(uri: String): Path = if (uri.startsWith("file:")) Path.of(URI(uri)) else Path.of(uri)
actual suspend fun readDocumentText(context: PlatformContext, uri: String, maxBytes: Int): String = withContext(Dispatchers.IO) {
    val path = documentPath(uri)
    require(Files.size(path) <= maxBytes) { "备份文件过大或格式异常" }
    Files.newInputStream(path).use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16384)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= maxBytes) { "备份文件过大或格式异常" }
            output.write(buffer, 0, count)
        }
        output.toByteArray().decodeToString()
    }
}
actual suspend fun writeDocumentText(context: PlatformContext, uri: String, text: String): Unit = withContext(Dispatchers.IO) {
    Files.newBufferedWriter(documentPath(uri), StandardCharsets.UTF_8).use { it.write(text) }
}
actual fun documentName(context: PlatformContext, uri: String): String =
    runCatching { documentPath(uri).fileName.toString() }.getOrDefault("本地漫画")
actual fun releasePickedDocuments(uris: List<String>) = Unit
actual fun copyText(context: PlatformContext, label: String, text: String): Boolean =
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null); true }.getOrDefault(false)
actual fun showPlatformMessage(context: PlatformContext, text: String) { PlatformNotices.show(text) }
actual fun notificationsEnabled(context: PlatformContext): Boolean = SystemTray.isSupported()
actual suspend fun queryNotificationsEnabled(context: PlatformContext): Boolean = notificationsEnabled(context)
actual fun openNotificationSettings(context: PlatformContext): Boolean = runCatching {
    ProcessBuilder("cmd", "/c", "start", "", "ms-settings:notifications").start()
    true
}.getOrDefault(false)

actual fun openUrl(context: PlatformContext, url: String): Boolean = runCatching {
    val uri = URI(url)
    require(uri.scheme == "https" || uri.scheme == "http")
    require(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
    Desktop.getDesktop().browse(uri); true
}.getOrDefault(false)
actual val modernPlatformBlur: Boolean get() = true
actual fun ImageRequest.Builder.softwareBitmap(): ImageRequest.Builder = this
actual fun imagePixelAt(image: Image, x: Int, y: Int): Int? = runCatching { (image as? BitmapImage)?.bitmap?.getColor(x, y) }.getOrNull()
@Composable
actual fun SystemBarAppearance(lightStatus: Boolean, lightNavigation: Boolean, active: Boolean) = Unit
actual fun availableCommentTextTool(context: PlatformContext, text: String, tool: CommentTextTool): Boolean =
    tool == CommentTextTool.TRANSLATE && Desktop.isDesktopSupported()
actual fun openCommentTextTool(context: PlatformContext, text: String, tool: CommentTextTool): Boolean =
    tool == CommentTextTool.TRANSLATE && openUrl(context,
        "https://translate.google.com/?sl=auto&tl=zh-CN&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8) + "&op=translate")

@Composable
actual fun WallpaperVideo(path: String, playing: Boolean, modifier: Modifier, onError: (String) -> Unit) {
    val latestError = rememberUpdatedState(onError)
    val scope = rememberCoroutineScope()
    val result = remember(path) { runCatching { FxWallpaper(path) { scope.launch { latestError.value(it) } } } }
    LaunchedEffect(result) { if (result.isFailure) latestError.value("视频运行环境无法初始化，已保留静态画面。") }
    val video = result.getOrNull() ?: return
    DisposableEffect(video) { onDispose { video.close() } }
    SwingPanel(background = Color.Transparent, factory = { video.panel }, modifier = modifier,
        update = { video.setPlaying(playing) })
}
private class FxWallpaper(path: String, private val onError: (String) -> Unit) {
    val panel = JFXPanel()
    private var player: MediaPlayer? = null
    private var closed = false
    private var playing = false
    init {
        initializeJavaFx()
        Platform.runLater {
            if (closed) return@runLater
            runCatching {
                val media = Media(File(path).toURI().toString())
                val next = MediaPlayer(media)
                player = next
                next.isMute = true
                next.volume = 0.0
                next.cycleCount = MediaPlayer.INDEFINITE
                val view = MediaView(next)
                val pane = StackPane(view)
                val clip = javafx.scene.shape.Rectangle()
                clip.widthProperty().bind(pane.widthProperty()); clip.heightProperty().bind(pane.heightProperty())
                pane.clip = clip
                fun fit() {
                    if (media.width <= 0 || media.height <= 0) return
                    val scale = maxOf(pane.width / media.width, pane.height / media.height)
                    view.fitWidth = media.width * scale; view.fitHeight = media.height * scale
                    view.isPreserveRatio = true
                }
                pane.widthProperty().addListener { _, _, _ -> fit() }
                pane.heightProperty().addListener { _, _, _ -> fit() }
                next.setOnReady { fit(); if (playing) next.play() }
                next.setOnError { onError("视频无法播放，已保留静态画面。请换用 MP4（H.264）。") }
                media.setOnError { onError("视频格式无法解码，已保留静态画面。") }
                panel.scene = Scene(pane, javafx.scene.paint.Color.TRANSPARENT)
            }.onFailure { onError("视频无法打开，已保留静态画面。") }
        }
    }
    fun setPlaying(value: Boolean) {
        playing = value
        Platform.runLater { if (!closed) { if (value) player?.play() else player?.pause() } }
    }
    fun close() {
        closed = true
        Platform.runLater { player?.dispose(); player = null; panel.scene = null }
    }
}

@Composable
actual fun NativeWebsiteLogin(
    onBegin: () -> Long, onCancel: (Long) -> Unit, onCredential: (Long, String) -> Unit,
    onLoading: (Boolean) -> Unit, onError: (String) -> Unit, modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val loading = rememberUpdatedState(onLoading)
    val failure = rememberUpdatedState(onError)
    val receipt = remember { WebsiteLoginReceipt(onBegin, onCancel, onCredential) }
    val result = remember { runCatching {
        FxWebsiteSession(receipt,
            onLoading = { scope.launch { loading.value(it) } },
            onError = { scope.launch { receipt.close(); failure.value(it) } },
            onDocument = { scope.launch { receipt.newDocument() } },
            onDeliver = { token -> scope.launch { receipt.deliverAfterCleanup(token) } },
            onMessage = { raw, url -> scope.launch {
                receipt.receive(raw, url, true)?.let { token ->
                    sessionCleanupAndDeliver(receipt, token)
                }
            } })
    } }
    DisposableEffect(receipt) { onDispose { receipt.close() } }
    LaunchedEffect(result) { if (result.isFailure) {
        receipt.close()
        failure.value("无法初始化安全登录窗口，请重新打开或使用现有凭据导入。")
    } }
    val session = result.getOrNull() ?: return
    DisposableEffect(session) { onDispose { session.close() } }
    SwingPanel(factory = { session.panel }, modifier = modifier)
}
/** Called on the Compose dispatcher, then completes only after the isolated profile is cleared. */
private fun sessionCleanupAndDeliver(receipt: WebsiteLoginReceipt, token: String) {
    DesktopWebsiteSessions.active?.takeIf { it.receipt === receipt }?.deliver(token)
}
private object DesktopWebsiteSessions {
    @Volatile var active: FxWebsiteSession? = null
}
/** A public JVM class is necessary for JavaFX's documented JSObject.setMember bridge. */
class DesktopAccountBridge(private val receiver: (String) -> Unit) {
    fun postMessage(value: String) { receiver(value) }
}
private class FxWebsiteSession(
    val receipt: WebsiteLoginReceipt,
    private val onLoading: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val onDocument: () -> Unit,
    private val onDeliver: (String) -> Unit,
    private val onMessage: (String, String) -> Unit,
) {
    val panel = JFXPanel()
    private val profile = Files.createTempDirectory("mangaflow-login-").toFile()
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    private var previousCookies: CookieHandler? = null
    private var view: WebView? = null
    private var bridge: DesktopAccountBridge? = null
    private var closed = false
    private var credentialPending = false
    init {
        initializeJavaFx()
        onLoading(true)
        Platform.runLater {
            if (closed) return@runLater
            val previous = DesktopWebsiteSessions.active
            if (previous != null) previous.close()
            DesktopWebsiteSessions.active = this
            previousCookies = CookieHandler.getDefault()
            CookieHandler.setDefault(cookies)
            runCatching {
                val browser = WebView()
                view = browser
                val engine = browser.engine
                engine.userDataDirectory = profile
                engine.isJavaScriptEnabled = true
                engine.setCreatePopupHandler { null }
                engine.setOnAlert { event ->
                    if (!closed && allowedWebsitePage(engine.location)) {
                        event.data?.trim()?.take(4096)?.takeIf { it.isNotEmpty() }?.let(PlatformNotices::show)
                    }
                }
                engine.locationProperty().addListener { _, _, url ->
                    if (!closed && !url.isNullOrBlank() && url != "about:blank" && !allowedWebsitePage(url)) {
                        engine.loadWorker.cancel()
                        fail("登录页跳转到了未允许的地址，已停止登录。")
                    }
                }
                engine.loadWorker.stateProperty().addListener(ChangeListener { _, _, state ->
                    if (closed) return@ChangeListener
                    when (state) {
                        Worker.State.SCHEDULED -> onLoading(true)
                        Worker.State.SUCCEEDED -> {
                            if (!allowedWebsitePage(engine.location)) return@ChangeListener
                            if (!isWebsiteLoginPage(engine.location)) { onLoading(false); return@ChangeListener }
                            onDocument()
                            runCatching {
                                val window = engine.executeScript("window") as JSObject
                                bridge = DesktopAccountBridge { raw -> onMessage(raw, engine.location) }
                                window.setMember("MangaFlowAccount", bridge)
                                engine.executeScript(websiteObserver(receipt.nonce, "window.MangaFlowAccount"))
                                onLoading(false)
                            }.onFailure { fail("无法建立安全登录会话，请重新打开登录。") }
                        }
                        Worker.State.FAILED -> fail("官网登录页未能加载，请检查网络后重试。")
                        else -> Unit
                    }
                })
                panel.scene = Scene(StackPane(browser))
                engine.load(CopyWebsiteEndpoints.LOGIN_PAGE)
            }.onFailure { fail("无法打开官网登录页，请重新打开登录。") }
        }
    }
    private fun cleanup(): Boolean {
        if (closed) return !credentialPending
        closed = true
        return runCatching {
            view?.engine?.apply {
                loadWorker.cancel()
                runCatching { executeScript("window.MangaFlowAccount = undefined; window.localStorage.clear(); window.sessionStorage.clear();") }
                load(null)
            }
            panel.scene = null
            view = null
            bridge = null
            cookies.cookieStore.removeAll()
            if (CookieHandler.getDefault() === cookies) CookieHandler.setDefault(previousCookies)
            if (DesktopWebsiteSessions.active === this) DesktopWebsiteSessions.active = null
            Files.walk(profile.toPath()).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
            true
        }.getOrDefault(false)
    }
    fun deliver(token: String) {
        credentialPending = true
        Platform.runLater {
            val clean = cleanup()
            credentialPending = false
            if (clean) {
                // Coroutine callbacks return to Compose, through the same receipt owner.
                onLoading(false)
                onDeliver(token)
            } else onError("临时登录数据清理未完成，账号尚未保存，请重新打开登录。")
        }
    }
    private fun fail(message: String) {
        cleanup()
        onLoading(false)
        onError(message)
    }
    fun close() {
        Platform.runLater { cleanup() }
    }
}
