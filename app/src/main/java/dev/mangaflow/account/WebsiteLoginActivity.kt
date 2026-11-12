package dev.mangaflow.account

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import dev.mangaflow.ui.MangaFlowTheme
import java.io.ByteArrayInputStream

/** The website owns its form. The app never injects script or reads form contents. */
class WebsiteLoginActivity : ComponentActivity() {
    private var attemptId: String? = null
    private var webView by mutableStateOf<WebView?>(null)
    private var preparing by mutableStateOf(true)
    private var pageLoading by mutableStateOf(false)
    private var closing by mutableStateOf(false)
    private var pageError by mutableStateOf<String?>(null)
    private var notice by mutableStateOf<String?>(null)
    private var pageProgress by mutableStateOf(0f)
    private var foreground = false
    private var blocked = false
    private var resultReturned = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        attemptId = WebsiteLoginSession.begin { closeVisit(WebsiteLoginResult.Cancelled) }
        onBackPressedDispatcher.addCallback(this) {
            if (closing) return@addCallback
            val view = webView
            if (!blocked && view != null && view.canGoBack()) view.goBack()
            else closeVisit(WebsiteLoginResult.Cancelled)
        }
        setContent {
            MangaFlowTheme {
                val colors = MaterialTheme.colorScheme
                val dark = isSystemInDarkTheme()
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                Column(Modifier.fillMaxSize().background(colors.background).systemBarsPadding().imePadding()) {
                    Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { closeVisit(WebsiteLoginResult.Cancelled) }, enabled = !closing) {
                            Icon(Icons.Outlined.Close, "关闭官网登录", Modifier.size(22.dp))
                        }
                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                            Text("官网登录", style = MaterialTheme.typography.titleMedium)
                            Text("copy4000.com", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                        TextButton(onClick = { captureToken(manual = true) },
                            enabled = !preparing && !pageLoading && !closing && !blocked && webView != null) {
                            Text("完成登录")
                        }
                    }
                    when {
                        preparing || closing -> LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                        pageLoading -> LinearProgressIndicator(progress = { pageProgress }, modifier = Modifier.fillMaxWidth().height(2.dp))
                        else -> HorizontalDivider(color = colors.outlineVariant)
                    }
                    pageError?.let { error ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(error, style = MaterialTheme.typography.bodySmall, color = colors.error)
                            TextButton(onClick = ::openLoginPage, enabled = !preparing && !closing) { Text("重新打开登录页") }
                        }
                    }
                    notice?.let {
                        Text(it, Modifier.padding(horizontal = 24.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val current = webView
                        if (current != null) key(current) {
                            AndroidView(factory = { context ->
                                FrameLayout(context).apply {
                                    addView(current, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                                }
                            }, modifier = Modifier.fillMaxSize())
                        }
                        else if (preparing || closing) Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            Text(if (closing) "正在返回账号页…" else "正在打开官网…", style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        openLoginPage()
    }

    private fun openLoginPage() {
        val id = attemptId ?: return
        if (closing || !WebsiteLoginSession.isCurrent(id)) return
        preparing = true
        pageLoading = false
        blocked = false
        pageError = null
        notice = null
        destroyWebView()
        WebsiteLoginSession.clearWebViewData { cleared ->
            if (closing || isDestroyed || !WebsiteLoginSession.isCurrent(id)) return@clearWebViewData
            preparing = false
            if (!cleared) {
                pageError = "无法清理上次的网页登录状态，请重新打开。"
                return@clearWebViewData
            }
            try {
                check(WebsiteLoginPolicy.allowsMainFrame(WebsiteLoginPolicy.ENTRY_URL))
                val view = createWebView()
                webView = view
                pageLoading = true
                view.loadUrl(WebsiteLoginPolicy.ENTRY_URL)
            } catch (_: Exception) {
                destroyWebView()
                pageLoading = false
                pageError = "无法打开官网登录，请检查系统 Android WebView 是否可用。"
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun createWebView(): WebView {
        WebView.setWebContentsDebuggingEnabled(false)
        val created = WebView(this)
        webView = created
        return created.apply web@ {
            isSaveEnabled = false
            isSaveFromParentEnabled = false
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                safeBrowsingEnabled = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                setGeolocationEnabled(false)
                mediaPlaybackRequiresUserGesture = true
                saveFormData = false
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(this@web, false)
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (blocked && request.isForMainFrame) return true
                    if (!disallowed(request)) return false
                    if (request.isForMainFrame) failPage(view, "网页登录仅允许在拷贝官网内继续。")
                    return true
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (!disallowed(request)) return null
                    // Also covers main-frame POST requests, which do not reach shouldOverrideUrlLoading.
                    if (request.isForMainFrame) runOnUiThread { failPage(view, "网页登录仅允许在拷贝官网内继续。") }
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    if (!accepts(view)) return
                    if (blocked) {
                        view.stopLoading()
                        return
                    }
                    if (!WebsiteLoginPolicy.allowsMainFrame(url)) {
                        failPage(view, "网页登录仅允许在拷贝官网内继续。")
                        return
                    }
                    pageLoading = true
                    pageProgress = 0f
                    notice = null
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (!accepts(view) || blocked) return
                    if (!WebsiteLoginPolicy.allowsMainFrame(url) || !WebsiteLoginPolicy.allowsMainFrame(view.url)) {
                        failPage(view, "网页登录仅允许在拷贝官网内继续。")
                        return
                    }
                    pageLoading = false
                    captureToken(manual = false)
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    if (accepts(view) && !blocked && WebsiteLoginPolicy.allowsMainFrame(url)) captureToken(manual = false)
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    failPage(view, "官网的安全连接验证失败，请稍后重试。")
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) failPage(view, "官网登录页面加载失败，请检查网络后重试。")
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    if (request.isForMainFrame) failPage(view, "官网登录页面暂时无法打开（HTTP ${errorResponse.statusCode}）。")
                }

                override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) {
                    dontResend.sendToTarget()
                    if (accepts(view)) notice = "请在官网页面重新提交登录。"
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    if (accepts(view)) {
                        failPage(view, "官网登录页面已关闭，请重新打开。")
                        destroyWebView()
                    }
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    if (accepts(view)) pageProgress = (newProgress / 100f).coerceIn(0f, 1f)
                }
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean = false
                override fun onShowFileChooser(view: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: WebChromeClient.FileChooserParams): Boolean {
                    filePathCallback.onReceiveValue(null)
                    return true
                }
            }
            setDownloadListener { _, _, _, _, _ -> if (accepts(this)) notice = "此页面仅用于登录，请返回官网登录表单。" }
        }
    }

    private fun disallowed(request: WebResourceRequest): Boolean {
        val scheme = request.url.scheme?.lowercase()
        return if (request.isForMainFrame) !WebsiteLoginPolicy.allowsMainFrame(request.url.toString())
        else scheme !in setOf("https", "about", "data", "blob", "javascript")
    }

    private fun accepts(view: WebView): Boolean = !closing && !isDestroyed && webView === view &&
        attemptId?.let(WebsiteLoginSession::isCurrent) == true

    private fun failPage(view: WebView, message: String) {
        if (!accepts(view) || blocked) return
        blocked = true
        pageLoading = false
        pageError = message
        notice = null
        view.stopLoading()
    }

    private fun captureToken(manual: Boolean) {
        val view = webView ?: return
        if (!foreground || !accepts(view) || blocked || !WebsiteLoginPolicy.allowsMainFrame(view.url)) return
        try {
            val cookies = CookieManager.getInstance()
            val candidates = WebsiteLoginPolicy.COOKIE_URLS.flatMap { origin ->
                cookies.getCookie(origin).orEmpty().split(';').mapNotNull { part ->
                    val separator = part.indexOf('=')
                    if (separator < 0 || part.substring(0, separator).trim() != "token") null
                    else part.substring(separator + 1).trim().removeSurrounding("\"").takeIf(String::isNotEmpty)
                }
            }.distinct()
            // Keep the raw cookie value. AccountTokenInput alone owns its one URL-decode pass.
            val raw = candidates.singleOrNull()?.takeIf { value ->
                value.length in 8..6144 && value.none { it.code <= 0x20 || it.code >= 0x7f }
            }
            if (raw != null) closeVisit(WebsiteLoginResult.Cancelled, rawCookieValue = raw)
            else if (manual) notice = if (candidates.size > 1) "官网返回了不同的登录状态，请重新打开登录页。"
                else "请先在官网页面完成登录，再点“完成登录”。"
        } catch (_: Exception) {
            if (manual) notice = "暂时无法读取本次登录状态，请重新打开登录页。"
        }
    }

    private fun closeVisit(result: WebsiteLoginResult, rawCookieValue: String? = null) {
        if (closing || resultReturned) return
        closing = true
        preparing = false
        pageLoading = false
        pageError = null
        notice = null
        destroyWebView()
        WebsiteLoginSession.clearWebViewData { cleared ->
            val id = attemptId
            if (isDestroyed) {
                id?.let(WebsiteLoginSession::abandon)
                return@clearWebViewData
            }
            val finalResult = when {
                !cleared -> WebsiteLoginResult.Failed("官网登录页面清理未完成，请重新打开后重试。")
                rawCookieValue != null && id != null -> WebsiteLoginSession.handoff(id, rawCookieValue)
                    ?.let { WebsiteLoginResult.Completed(it) } ?: WebsiteLoginResult.Cancelled
                else -> result
            }
            id?.let(WebsiteLoginSession::abandon)
            val data = Intent()
            when (finalResult) {
                is WebsiteLoginResult.Completed -> data.putExtra(WebsiteLoginContract.EXTRA_HANDOFF, finalResult.handoffId)
                is WebsiteLoginResult.Failed -> data.putExtra(WebsiteLoginContract.EXTRA_FAILURE, finalResult.message)
                WebsiteLoginResult.Cancelled -> Unit
            }
            resultReturned = true
            setResult(if (finalResult is WebsiteLoginResult.Completed) RESULT_OK else RESULT_CANCELED, data)
            super.finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun destroyWebView() {
        val view = webView ?: return
        webView = null
        runCatching { view.stopLoading() }
        runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        runCatching { view.clearHistory(); view.clearCache(true); view.clearFormData() }
        runCatching { WebViewDatabase.getInstance(this).clearFormData() }
        runCatching { view.removeAllViews(); view.destroy() }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        webView?.onResume()
        if (!pageLoading) captureToken(manual = false)
    }

    override fun onPause() {
        foreground = false
        webView?.onPause()
        super.onPause()
    }

    override fun finish() {
        if (resultReturned) super.finish() else closeVisit(WebsiteLoginResult.Cancelled)
    }

    override fun onDestroy() {
        val id = attemptId
        val current = id != null && WebsiteLoginSession.isCurrent(id)
        if (!resultReturned) id?.let(WebsiteLoginSession::abandon)
        destroyWebView()
        if (!resultReturned && !closing && current) WebsiteLoginSession.clearWebViewData { }
        super.onDestroy()
    }
}
