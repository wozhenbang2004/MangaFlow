package dev.mangaflow.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.tracking.AUTHORIZATION_LIFETIME_MILLIS
import dev.mangaflow.tracking.MangaTrackingAuthorization
import dev.mangaflow.tracking.MangaTrackingNavigation
import dev.mangaflow.tracking.MangaTrackingWebsiteReceipt
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.web.WebView
import javafx.util.Duration
import netscape.javascript.JSObject
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.nio.file.Files
import javax.swing.SwingUtilities

internal actual fun trackingWebsiteLoginSupported(): Boolean = true

@Composable
internal actual fun NativeTrackingWebsiteLogin(
    authorization: MangaTrackingAuthorization,
    onCancel: (String) -> Unit,
    onCredential: (String, String) -> Unit,
    onLoading: (Boolean) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier,
) {
    val cancel = rememberUpdatedState(onCancel)
    val credential = rememberUpdatedState(onCredential)
    val loading = rememberUpdatedState(onLoading)
    val error = rememberUpdatedState(onError)
    val result = remember(authorization.id) { runCatching {
        DesktopTrackingWebsiteSession(authorization,
            { cancel.value(it) }, { id, token -> credential.value(id, token) },
            { loading.value(it) }, { error.value(it) })
    } }
    LaunchedEffect(result) {
        if (result.isFailure) {
            try {
                loading.value(false)
                error.value("无法初始化安全授权窗口，请重新打开或使用凭据导入。")
            } finally { cancel.value(authorization.id) }
        }
    }
    val session = result.getOrNull() ?: return
    DisposableEffect(session) { onDispose { session.close() } }
    key(authorization.id) { SwingPanel(factory = { session.panel }, modifier = modifier) }
}

/** JavaFX's JSObject bridge needs a public JVM class and a retained Java reference. */
class DesktopTrackingBridge(private val receiver: (String) -> Unit) {
    fun postMessage(value: String) { receiver(value) }
}

/** An official page with its own temporary DOM store and the shared single-owner cookie lane. */
private class DesktopTrackingWebsiteSession(
    authorization: MangaTrackingAuthorization,
    onCancel: (String) -> Unit,
    onCredential: (String, String) -> Unit,
    private val onLoading: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
) : DesktopWebsiteSessionOwner {
    private val receiptLock = Any()
    private var credentialDelivered = false
    private val receipt = MangaTrackingWebsiteReceipt(authorization,
        { id -> onUi { onCancel(id) } }, { id, token ->
            credentialDelivered = true
            onCredential(id, token)
        })
    val panel = JFXPanel()
    private val profile = Files.createTempDirectory("mangaflow-tracking-login-").toFile()
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    private var previousCookies: CookieHandler? = null
    private var view: WebView? = null
    private var bridge: DesktopTrackingBridge? = null
    private var loadTimeout: PauseTransition? = null
    private var authorizationTimeout: PauseTransition? = null
    @Volatile private var ownership = 0L
    @Volatile private var disposed = false
    @Volatile private var closed = false
    private var credentialPending = false
    private var cleanupSucceeded = true

    init {
        initializeJavaFx()
        Platform.runLater {
            if (disposed || closed) return@runLater
            try {
                ownership = DesktopWebsiteSessions.claim(this)
                previousCookies = CookieHandler.getDefault()
                CookieHandler.setDefault(cookies)
                loadTimeout = PauseTransition(Duration.seconds(45.0)).apply {
                    setOnFinished { fail("官网登录页连接超时，请检查网络后重新打开。") }
                }
                authorizationTimeout = PauseTransition(Duration.millis(
                    (AUTHORIZATION_LIFETIME_MILLIS - (wallTimeMillis() - authorization.createdAt))
                        .coerceAtLeast(0L).toDouble())).apply {
                    setOnFinished { fail("本次授权已过期，请重新登录。") }
                    playFromStart()
                }
                val browser = WebView()
                view = browser
                val engine = browser.engine
                engine.userDataDirectory = profile
                engine.isJavaScriptEnabled = true
                engine.setCreatePopupHandler { null }
                // Site alerts/status/exception text can contain credential URLs. Keep
                // platform diagnostics static and leave official form feedback in-page.
                engine.setOnAlert { }
                engine.setOnError { fail("无法建立隔离的登录环境，请重新打开登录。") }
                engine.locationProperty().addListener { _, _, url ->
                    if (!closed && !disposed && !url.isNullOrBlank() && url != "about:blank") navigate(url)
                }
                engine.loadWorker.stateProperty().addListener { _, _, state ->
                    if (closed || disposed) return@addListener
                    when (state) {
                        Worker.State.SCHEDULED -> {
                            synchronized(receiptLock) { receipt.newDocument() }
                            publishLoading(true)
                            loadTimeout?.playFromStart()
                        }
                        Worker.State.SUCCEEDED -> {
                            if (!navigate(engine.location)) return@addListener
                            try {
                                val observer = synchronized(receiptLock) { receipt.observerScript(BRIDGE_EXPRESSION) }
                                if (observer != null && observerOrigin(engine.location)) {
                                    val window = engine.executeScript("window") as JSObject
                                    bridge = DesktopTrackingBridge { raw ->
                                        if (raw.length <= 16_384 && acceptsPage() && observerOrigin(engine.location)) {
                                            // Only the main window receives this bridge; cross-origin
                                            // frames cannot reach it through the browser's origin policy.
                                            synchronized(receiptLock) { receipt.receive(raw, engine.location, true) }?.let(::deliver)
                                        }
                                    }
                                    window.setMember(BRIDGE, bridge)
                                    engine.executeScript(observer)
                                }
                                loadTimeout?.stop()
                                publishLoading(false)
                            } catch (_: Exception) { fail("无法建立安全授权会话，请重新打开登录。") }
                        }
                        // JavaFX's normal TLS verifier is retained. Certificate failures
                        // end the page load; no permissive verifier or exception is installed.
                        Worker.State.FAILED -> fail("官网登录页连接或安全验证失败，请检查网络后重新打开。")
                        else -> Unit
                    }
                }
                panel.scene = Scene(StackPane(browser))
                if (!closed && navigate(receipt.initialUrl)) {
                    publishLoading(true)
                    loadTimeout?.playFromStart()
                    engine.load(receipt.initialUrl)
                }
            } catch (_: Exception) {
                if (ownership == 0L) ownership = DesktopWebsiteSessions.generation
                fail("无法打开官网登录页，请重新打开登录。")
            }
        }
    }

    private fun acceptsResult(): Boolean = !disposed && DesktopWebsiteSessions.isCurrent(ownership)
    private fun acceptsPage(): Boolean = acceptsResult() && !closed && DesktopWebsiteSessions.active === this

    private fun publishLoading(value: Boolean) = onUi { if (acceptsResult()) onLoading(value) }

    /** No external browser or process ever receives the custom-scheme callback URL. */
    private fun navigate(url: String): Boolean {
        if (!acceptsPage()) return false
        return when (val decision = synchronized(receiptLock) { receipt.navigation(url) }) {
            MangaTrackingNavigation.Allow -> true
            MangaTrackingNavigation.Block -> { fail("登录页跳转到了未允许的地址，已停止授权。"); false }
            is MangaTrackingNavigation.Error -> { fail(decision.message); false }
            is MangaTrackingNavigation.Credential -> { deliver(decision.token); false }
        }
    }

    private fun observerOrigin(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.port in setOf(-1, 443) && uri.rawUserInfo == null &&
            receipt.observerOrigins.any { URI(it).host == uri.host }
    }.getOrDefault(false)

    private fun deliver(token: String) {
        if (!acceptsPage() || credentialPending) return
        credentialPending = true
        publishLoading(true)
        val cleaned = cleanup()
        onUi {
            synchronized(receiptLock) {
                if (!acceptsResult()) { receipt.close(); return@synchronized }
                onLoading(false)
                try {
                    if (cleaned) {
                        receipt.deliverAfterCleanup(token)
                        if (!credentialDelivered) onError("本次授权已失效，账号尚未保存，请重新登录。")
                    } else onError("临时登录数据清理未完成，账号尚未保存，请重新打开登录。")
                } finally { if (!credentialDelivered) receipt.close() }
            }
        }
    }

    /** Runs exclusively on JavaFX, including replacement by a Copy login window. */
    private fun cleanup(): Boolean {
        if (closed) return cleanupSucceeded
        closed = true
        val engine = view?.engine
        fun clean(action: () -> Unit) { try { action() } catch (_: Exception) { cleanupSucceeded = false } }
        clean { loadTimeout?.stop() }
        loadTimeout = null
        clean { authorizationTimeout?.stop() }
        authorizationTimeout = null
        clean { engine?.loadWorker?.cancel() }
        runCatching {
            (engine?.executeScript("window") as? JSObject)?.removeMember(BRIDGE)
            engine?.executeScript("window.localStorage.clear(); window.sessionStorage.clear();")
        }
        clean { engine?.load(null) }
        clean { panel.scene = null }
        view = null
        bridge = null
        clean { cookies.cookieStore.removeAll() }
        clean {
            if (DesktopWebsiteSessions.active === this && CookieHandler.getDefault() === cookies)
                CookieHandler.setDefault(previousCookies)
        }
        DesktopWebsiteSessions.release(this)
        if (!clearDesktopWebsiteProfile(profile)) cleanupSucceeded = false
        return cleanupSucceeded
    }

    private fun fail(message: String) {
        if (closed || disposed) return
        val cleaned = cleanup()
        onUi {
            if (acceptsResult()) {
                try {
                    onLoading(false)
                    onError(message + if (cleaned) "" else "\n临时登录数据清理未完成。")
                } finally { synchronized(receiptLock) { receipt.close() } }
            }
        }
    }

    fun close() {
        if (disposed) return
        disposed = true
        synchronized(receiptLock) { receipt.close() }
        Platform.runLater { cleanup() }
    }

    override fun closeForReplacement(): Boolean {
        disposed = true
        synchronized(receiptLock) { receipt.close() }
        return cleanup()
    }

    companion object {
        private const val BRIDGE = "MangaFlowTracking"
        private const val BRIDGE_EXPRESSION = "(raw) => window.MangaFlowTracking.postMessage(raw)"

        private fun onUi(action: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeLater(action)
        }
    }
}
