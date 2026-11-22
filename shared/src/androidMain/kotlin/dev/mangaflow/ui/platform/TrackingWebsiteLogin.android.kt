package dev.mangaflow.ui.platform

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.tracking.AUTHORIZATION_LIFETIME_MILLIS
import dev.mangaflow.tracking.MangaTrackingAuthorization
import dev.mangaflow.tracking.MangaTrackingNavigation
import dev.mangaflow.tracking.MangaTrackingWebsiteReceipt
import java.util.UUID

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
    val session = remember(authorization.id) {
        AndroidTrackingWebsiteSession(
            authorization, { cancel.value(it) }, { id, token -> credential.value(id, token) },
            { loading.value(it) }, { error.value(it) },
        )
    }
    DisposableEffect(session) { onDispose { session.close() } }
    key(authorization.id) {
        AndroidView(factory = session::create, modifier = modifier, onReset = null, onRelease = { session.close() })
    }
}

/** WebView, its profile, the observer and every callback belong to one authorization attempt. */
private class AndroidTrackingWebsiteSession(
    authorization: MangaTrackingAuthorization,
    onCancel: (String) -> Unit,
    onCredential: (String, String) -> Unit,
    private val onLoading: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var credentialDelivered = false
    private val receipt = MangaTrackingWebsiteReceipt(authorization, onCancel) { id, token ->
        credentialDelivered = true
        onCredential(id, token)
    }
    private val handler = Handler(Looper.getMainLooper())
    private val profileName = PROFILE_PREFIX + UUID.randomUUID()
    private var view: WebView? = null
    private var profile: Profile? = null
    private var script: ScriptHandler? = null
    private var pendingCookies: CookieManager? = null
    private var closed = false
    private var cancelled = false
    private var waitingForCleanup = false
    private var cleanupSucceeded = true
    private val loadTimeout = Runnable { fail("官网登录页连接超时，请检查网络后重新打开。") }
    private val authorizationTimeout = Runnable { fail("本次授权已过期，请重新登录。") }
    private val cleanupTimeout = Runnable {
        if (waitingForCleanup) fail("临时登录数据清理超时，账号尚未保存，请重新打开登录。")
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: Context): FrameLayout = FrameLayout(context).also { container ->
        if (cancelled) return@also
        try {
            handler.postDelayed(authorizationTimeout,
                (AUTHORIZATION_LIFETIME_MILLIS - (wallTimeMillis() - receipt.authorization.createdAt)).coerceAtLeast(0L))
            val required = listOf(WebViewFeature.MULTI_PROFILE, WebViewFeature.WEB_MESSAGE_LISTENER,
                WebViewFeature.DOCUMENT_START_SCRIPT)
            if (required.any { !WebViewFeature.isFeatureSupported(it) }) {
                fail("请更新 Android System WebView 或 Chrome 后重新打开登录。")
                return@also
            }
            val profiles = ProfileStore.getInstance()
            profiles.allProfileNames.filter { it.startsWith(PROFILE_PREFIX) && it !in liveProfiles }.forEach { name ->
                try { profiles.deleteProfile(name) } catch (_: IllegalStateException) {
                    // Loaded profiles retain an empty shell until the next process.
                }
            }
            val browser = WebView(context)
            view = browser
            WebViewCompat.setProfile(browser, profileName)
            liveProfiles.add(profileName)
            profile = WebViewCompat.getProfile(browser)
            profile!!.cookieManager.setAcceptCookie(true)
            profile!!.cookieManager.setAcceptThirdPartyCookies(browser, false)
            browser.isSaveEnabled = false
            browser.isSaveFromParentEnabled = false
            browser.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            browser.webChromeClient = object : WebChromeClient() {
                // An official page can log responses; none of its console text reaches app logs.
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
            }
            browser.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean {
                    if (closed || cancelled) return true
                    return if (request.isForMainFrame) !navigate(request.url.toString())
                    else request.url.scheme != "https"
                }

                override fun onPageStarted(webView: WebView, url: String?, favicon: Bitmap?) {
                    if (closed || cancelled || !navigate(url.orEmpty())) return
                    receipt.newDocument()
                    onLoading(true)
                    handler.removeCallbacks(loadTimeout)
                    handler.postDelayed(loadTimeout, 45_000L)
                }

                override fun onPageFinished(webView: WebView, url: String?) {
                    if (closed || cancelled || webView !== view || url != webView.url || !navigate(url.orEmpty())) return
                    try {
                        // Document-start observes the official form. Bind this completed main
                        // document to its latest native challenge before accepting a submission.
                        receipt.observerScript(BRIDGE_EXPRESSION)?.let { webView.evaluateJavascript(it, null) }
                        handler.removeCallbacks(loadTimeout)
                        onLoading(false)
                    } catch (_: Exception) { fail("无法建立安全授权会话，请重新打开登录。") }
                }

                override fun onReceivedError(webView: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) fail("官网登录页连接失败，请检查网络后重新打开。")
                }

                override fun onReceivedHttpError(webView: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) fail("官网登录页暂时无法打开，请稍后重试。")
                }

                override fun onReceivedSslError(webView: WebView, sslHandler: SslErrorHandler, error: SslError) {
                    sslHandler.cancel()
                    fail("无法验证登录网站的安全连接，已停止授权。")
                }

                override fun onRenderProcessGone(webView: WebView, detail: RenderProcessGoneDetail): Boolean {
                    fail("官网登录页已关闭，请重新打开。")
                    return true
                }
            }
            val origins = receipt.observerOrigins
            if (origins.isNotEmpty()) {
                WebViewCompat.addWebMessageListener(browser, BRIDGE, origins) { webView, message, origin, mainFrame, _ ->
                    if (closed || cancelled || webView !== view || !mainFrame || !allowedOrigin(origin, origins)) {
                        return@addWebMessageListener
                    }
                    val raw = try { message.data } catch (_: IllegalStateException) { null }
                    if (raw == null || raw.length > 16_384) return@addWebMessageListener
                    receipt.receive(raw, webView.url.orEmpty(), true)?.let(::deliver)
                }
                receipt.observerScript(BRIDGE_EXPRESSION)?.let { observer ->
                    script = WebViewCompat.addDocumentStartJavaScript(browser, observer, origins)
                }
            }
            container.addView(browser, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (navigate(receipt.initialUrl)) {
                onLoading(true)
                handler.postDelayed(loadTimeout, 45_000L)
                browser.loadUrl(receipt.initialUrl)
            }
        } catch (_: Exception) { fail("无法打开官网登录页，请检查 Android System WebView 后重新打开。") }
    }

    private fun navigate(url: String): Boolean = when (val decision = receipt.navigation(url)) {
        MangaTrackingNavigation.Allow -> true
        MangaTrackingNavigation.Block -> { fail("登录页跳转到了未允许的地址，已停止授权。"); false }
        is MangaTrackingNavigation.Error -> { fail(decision.message); false }
        is MangaTrackingNavigation.Credential -> { deliver(decision.token); false }
    }

    private fun deliver(token: String) {
        if (closed || cancelled || waitingForCleanup) return
        val cookies = profile?.cookieManager ?: return fail("临时登录会话无法关闭，账号尚未保存。")
        waitingForCleanup = true
        pendingCookies = cookies
        onLoading(true)
        // Stop and destroy first: the page must not recreate cookies after their deletion.
        if (!release(clearCookies = false)) {
            fail("临时登录数据清理未完成，账号尚未保存，请重新打开登录。")
            return
        }
        handler.postDelayed(cleanupTimeout, 15_000L)
        try {
            cookies.removeAllCookies {
                if (waitingForCleanup && !cancelled) {
                    pendingCookies = null
                    handler.removeCallbacks(cleanupTimeout)
                    onLoading(false)
                    receipt.deliverAfterCleanup(token)
                    if (credentialDelivered) {
                        waitingForCleanup = false
                        handler.removeCallbacks(authorizationTimeout)
                    } else fail("本次授权已失效，账号尚未保存，请重新登录。")
                }
            }
        } catch (_: Exception) { fail("临时登录 Cookie 清理失败，账号尚未保存，请重新打开登录。") }
    }

    fun close() {
        stop()
        receipt.close()
    }

    private fun stop() {
        if (cancelled) return
        cancelled = true
        waitingForCleanup = false
        handler.removeCallbacks(loadTimeout)
        handler.removeCallbacks(cleanupTimeout)
        handler.removeCallbacks(authorizationTimeout)
        release(clearCookies = true)
        clean { pendingCookies?.removeAllCookies(null) }
        pendingCookies = null
    }

    private fun release(clearCookies: Boolean): Boolean {
        if (closed) return cleanupSucceeded
        closed = true
        handler.removeCallbacks(loadTimeout)
        val browser = view
        view = null
        clean { browser?.stopLoading() }
        clean { script?.remove() }
        script = null
        clean { browser?.let { WebViewCompat.removeWebMessageListener(it, BRIDGE) } }
        clean { profile?.webStorage?.deleteAllData() }
        val cookies = profile?.cookieManager
        profile = null
        clean { browser?.clearHistory() }
        clean { browser?.clearCache(true) }
        clean { (browser?.parent as? ViewGroup)?.removeView(browser) }
        clean { browser?.destroy() }
        if (clearCookies) clean { cookies?.removeAllCookies(null) }
        liveProfiles.remove(profileName)
        return cleanupSucceeded
    }

    private inline fun clean(action: () -> Unit) {
        try { action() } catch (_: Exception) { cleanupSucceeded = false }
    }

    private fun fail(message: String) {
        if (cancelled || closed && !waitingForCleanup) return
        stop()
        try {
            onLoading(false)
            onError(message + if (cleanupSucceeded) "" else "\n临时登录数据清理未完成。")
        } finally { receipt.close() }
    }

    companion object {
        private const val PROFILE_PREFIX = "mangaflow_tracking_login_"
        private const val BRIDGE = "MangaFlowTracking"
        private const val BRIDGE_EXPRESSION = "(raw) => window.MangaFlowTracking.postMessage(raw)"
        private val liveProfiles = mutableSetOf<String>() // All WebView callbacks run on the main thread.

        private fun allowedOrigin(origin: Uri, allowed: Set<String>): Boolean =
            origin.scheme == "https" && origin.port in setOf(-1, 443) && origin.userInfo == null &&
                allowed.any { expected -> Uri.parse(expected).host == origin.host }
    }
}
