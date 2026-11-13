package dev.mangaflow.account

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.ConsoleMessage
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
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.UUID

/** A disposable official-page session. Passwords and cookies never cross its bridge. */
internal class CopyWebsiteLogin(
    private val onBegin: () -> Long,
    private val onCancel: (Long) -> Unit,
    private val onCredential: (Long, String) -> Unit,
    private val onLoading: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val nonce = UUID.randomUUID().toString()
    private val profileName = PROFILE_PREFIX + UUID.randomUUID()
    private val handler = Handler(Looper.getMainLooper())
    private var view: WebView? = null
    private var profile: Profile? = null
    private var script: ScriptHandler? = null
    private var attempt: Long? = null
    private var document: String? = null
    private var submission = 0L
    private var closed = false
    private var cleanupSucceeded = true
    private var awaitingCredential = false
    private val timeout = Runnable {
        fail(if (submission > 0) "未能接收官网登录结果，请重新打开登录。" else "登录页面连接超时，请检查网络后重新打开。")
    }
    private val cookieTimeout = Runnable {
        if (awaitingCredential) credentialFailed("临时登录会话关闭超时，请重新打开登录。")
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: Context): FrameLayout = FrameLayout(context).also { container ->
        try {
            val required = listOf(WebViewFeature.MULTI_PROFILE, WebViewFeature.WEB_MESSAGE_LISTENER,
                WebViewFeature.DOCUMENT_START_SCRIPT)
            if (required.any { !WebViewFeature.isFeatureSupported(it) }) {
                fail("请更新 Android System WebView 或 Chrome 后重新打开登录。")
                return@also
            }
            // Only this app's orphaned login profiles are removed. A fresh profile
            // never inherits another attempt's Cookie, localStorage or logged-in user.
            val profiles = ProfileStore.getInstance()
            profiles.allProfileNames.filter { it.startsWith(PROFILE_PREFIX) && it !in liveProfiles }.forEach { name ->
                try { profiles.deleteProfile(name) } catch (_: IllegalStateException) {
                    // Already-loaded profiles can only be deleted in a later process.
                    // close() still clears their own cookies and DOM storage now.
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
                // The verified /web form is the desktop site. Keep this installed
                // engine's real Chrome version; never advertise an invented Edge build.
                // Replace only the platform comment; retain (KHTML, like Gecko).
                userAgentString = userAgentString.replaceFirst(Regex("\\([^)]*\\)"), "(X11; Linux x86_64)")
                    .replace(" Version/4.0", "").replace(" Mobile", "")
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            browser.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
            }
            browser.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean =
                    if (request.isForMainFrame) !allowedPage(request.url) else request.url.scheme != "https"

                override fun onPageStarted(webView: WebView, url: String?, favicon: Bitmap?) {
                    if (closed) return
                    if (!allowedPage(Uri.parse(url.orEmpty()))) {
                        fail("登录页面跳转到了不支持的地址，已停止连接。")
                        return
                    }
                    if (isLoginPage(Uri.parse(url.orEmpty()))) {
                        document = null
                        submission = 0
                        onLoading(true)
                        handler.removeCallbacks(timeout)
                        handler.postDelayed(timeout, 45_000)
                    } else {
                        // A normal success redirect must not suppress its queued
                        // bridge result, nor silently leave a website-only session.
                        onLoading(true)
                        handler.removeCallbacks(timeout)
                        handler.postDelayed(timeout, 10_000)
                    }
                }

                override fun onPageFinished(webView: WebView, url: String?) {
                    if (!closed && document != null && isLoginPage(Uri.parse(url.orEmpty()))) {
                        handler.removeCallbacks(timeout)
                        onLoading(false)
                    }
                }

                override fun onReceivedError(webView: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) fail("登录页面连接失败，请检查网络后重新打开。")
                }

                override fun onReceivedHttpError(webView: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) fail("登录页面未能打开（HTTP ${response.statusCode}），请稍后重新打开。")
                }

                override fun onReceivedSslError(webView: WebView, sslHandler: SslErrorHandler, error: SslError) {
                    sslHandler.cancel()
                    fail("无法验证登录网站的安全连接，已停止登录。")
                }

                override fun onRenderProcessGone(webView: WebView, detail: RenderProcessGoneDetail): Boolean {
                    fail("登录页面已关闭，请重新打开。")
                    return true
                }
            }
            WebViewCompat.addWebMessageListener(browser, BRIDGE, setOf(ORIGIN)) { webView, message, origin, mainFrame, _ ->
                if (closed || !mainFrame || !sameOrigin(origin) || !allowedPage(Uri.parse(webView.url.orEmpty()))) {
                    return@addWebMessageListener
                }
                val raw = try { message.data } catch (_: IllegalStateException) { null }
                if (raw == null || raw.length > 8192) return@addWebMessageListener
                val data = try { JSONObject(raw) } catch (_: Exception) { return@addWebMessageListener }
                if (data.optString("nonce") != nonce) return@addWebMessageListener
                val documentId = data.optString("document")
                if (documentId.length !in 8..80) return@addWebMessageListener
                if (data.optString("kind") == "ready") {
                    if (document == null && isLoginPage(Uri.parse(webView.url.orEmpty()))) document = documentId
                    return@addWebMessageListener
                }
                if (documentId != document) return@addWebMessageListener
                val sequence = data.optLong("sequence", -1)
                when (data.optString("kind")) {
                    "submitted" -> if (sequence == submission + 1) submission = sequence
                    "credential" -> {
                        if (sequence <= 0 || sequence != submission || data.optString("endpoint") != LOGIN_ENDPOINT ||
                            data.optInt("status") != 200 || data.optInt("code") != 200) return@addWebMessageListener
                        val token = try { AccountTokenInput.validate(data.optString("token")) }
                            catch (_: Exception) { fail("官网没有返回有效登录凭据，请重新登录。"); return@addWebMessageListener }
                        deliverCredential(token)
                    }
                }
            }
            script = WebViewCompat.addDocumentStartJavaScript(browser, observerScript(), setOf(ORIGIN))
            attempt = onBegin()
            container.addView(browser, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            handler.postDelayed(timeout, 45_000)
            browser.loadUrl(LOGIN_PAGE)
        } catch (_: Exception) {
            fail("无法打开登录页面，请检查 Android System WebView 后重新打开。")
        }
    }

    fun close(): Boolean {
        handler.removeCallbacks(cookieTimeout)
        if (awaitingCredential) {
            awaitingCredential = false
            attempt?.let(onCancel)
        }
        return release(cancelAttempt = true, clearCookies = true)
    }

    private fun deliverCredential(token: String) {
        val expected = attempt ?: return
        val cookies = profile?.cookieManager ?: return fail("无法关闭临时登录会话，请重新打开登录。")
        awaitingCredential = true
        onLoading(true)
        // Stop/destroy the page before clearing so its normal success handler
        // cannot write a token Cookie after our deletion has already finished.
        if (!release(cancelAttempt = false, clearCookies = false)) {
            credentialFailed("临时登录数据清理未完成，账号尚未保存，请重新打开登录。")
            return
        }
        handler.postDelayed(cookieTimeout, 15_000)
        try {
            cookies.removeAllCookies {
                // false means there were no cookies, not a failed deletion.
                if (awaitingCredential) {
                    awaitingCredential = false
                    handler.removeCallbacks(cookieTimeout)
                    onCredential(expected, token)
                }
            }
        } catch (_: Exception) {
            credentialFailed("无法清理临时登录 Cookie，账号尚未保存，请重新打开登录。")
        }
    }

    private fun credentialFailed(message: String) {
        awaitingCredential = false
        handler.removeCallbacks(cookieTimeout)
        attempt?.let(onCancel)
        onLoading(false)
        onError(message)
    }

    private fun release(cancelAttempt: Boolean, clearCookies: Boolean): Boolean {
        if (closed) return cleanupSucceeded
        closed = true
        handler.removeCallbacks(timeout)
        if (cancelAttempt) attempt?.let(onCancel)
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
        // WebKit may retain the loaded Profile until the process ends. Its data
        // is cleared above; the next process deletes any remaining profile shell.
        return cleanupSucceeded
    }

    private inline fun clean(action: () -> Unit) {
        try { action() } catch (_: Exception) { cleanupSucceeded = false }
    }

    private fun fail(message: String) {
        if (closed) return
        val cleaned = close()
        onLoading(false)
        onError(message + if (cleaned) "" else "\n临时登录数据清理未完成。")
    }

    private fun observerScript(): String = """
        (() => {
          if (window.top !== window || location.origin !== '$ORIGIN' || !location.pathname.startsWith('/web/login/')) return;
          const bridge = window.$BRIDGE;
          // Fit the existing official form to the phone; keep its own validation,
          // password controls, submission logic and source errors untouched.
          document.addEventListener('DOMContentLoaded', () => {
            let viewport = document.querySelector('meta[name="viewport"]');
            if (!viewport) { viewport = document.createElement('meta'); viewport.name = 'viewport'; document.head.appendChild(viewport); }
            viewport.content = 'width=device-width, initial-scale=1';
            const style = document.createElement('style');
            style.textContent = 'html,body,#app,#loginV2{min-width:0!important;width:100%!important;margin:0!important}#loginV2>.el-header,#loginV2>.el-footer{display:none!important}#loginV2>.el-main{padding:0!important}#loginV2>.el-main>section{width:100%!important;min-width:0!important}#loginV2 .el-form{width:100%!important}#loginV2 .el-tabs--border-card{border:0!important;box-shadow:none!important}#loginV2 .el-tabs__content{padding:12px!important}' +
            // Element UI mounts its message box under body, outside #loginV2.
            // Keep source rejection text and its existing confirmation action visible.
            '.el-message-box{width:calc(100vw - 32px)!important;max-width:420px!important;box-sizing:border-box!important}' +
            '.el-message-box__wrapper{overflow:auto!important}' +
            '.el-message-box__content,.el-message-box__message{min-width:0!important}' +
            '.el-message-box__message,.el-message-box__message p{white-space:pre-wrap!important;overflow-wrap:anywhere!important;word-break:break-word!important}' +
            '.el-message-box__btns{white-space:normal!important}';
            document.head.appendChild(style);
          }, {once: true});
          const documentId = Array.from(crypto.getRandomValues(new Uint32Array(4)), x => x.toString(16).padStart(8, '0')).join('');
          let sequence = 0;
          const emit = (kind, extra) => bridge.postMessage(JSON.stringify(Object.assign({kind, nonce: '$nonce', document: documentId}, extra)));
          const endpoint = value => { try { const u = new URL(value, location.href); return u.origin === '$ORIGIN' && u.pathname === '/api/kb/web/login' && !u.search && !u.hash && !u.username && !u.password; } catch (_) { return false; } };
          const receive = (url, status, text, current) => {
            if (current !== sequence || status !== 200 || !endpoint(url) || typeof text !== 'string' || text.length > 65536) return;
            try { const r = JSON.parse(text); if (r.code === 200 && r.results && typeof r.results.token === 'string') emit('credential', {sequence: current, endpoint: '$LOGIN_ENDPOINT', status, code: r.code, token: r.results.token}); } catch (_) {}
          };
          const open = XMLHttpRequest.prototype.open;
          const send = XMLHttpRequest.prototype.send;
          const requests = new WeakMap();
          XMLHttpRequest.prototype.open = function(method, url) {
            requests.set(this, {login: String(method).toUpperCase() === 'POST' && endpoint(url), sequence: 0, sent: false});
            // Register before the site's response handler can redirect its page.
            this.addEventListener('readystatechange', () => {
              const r = requests.get(this);
              if (!r || !r.login || r.sent || this.readyState !== 4) return;
              r.sent = true;
              let body;
              try { body = this.responseType === 'json' ? JSON.stringify(this.response) : this.responseText; } catch (_) { return; }
              receive(this.responseURL, this.status, body, r.sequence);
            });
            return open.apply(this, arguments);
          };
          XMLHttpRequest.prototype.send = function() {
            const r = requests.get(this);
            if (r && r.login) { r.sequence = ++sequence; emit('submitted', {sequence}); }
            return send.apply(this, arguments);
          };
          const fetch = window.fetch;
          window.fetch = function(input, init) {
            const url = typeof input === 'string' || input instanceof URL ? String(input) : input.url;
            const method = (init && init.method) || (input && input.method) || 'GET';
            const login = String(method).toUpperCase() === 'POST' && endpoint(url);
            const current = login ? ++sequence : 0;
            if (login) emit('submitted', {sequence: current});
            return fetch.apply(this, arguments).then(response => {
              if (login && response.status === 200 && endpoint(response.url)) response.clone().text().then(text => receive(response.url, response.status, text, current)).catch(() => {});
              return response;
            });
          };
          emit('ready', {});
        })();
    """.trimIndent()

    companion object {
        const val ORIGIN = "https://www.copy4000.com"
        private const val LOGIN_PAGE = "$ORIGIN/web/login/loginByAccount?url=person%2Fhome"
        private const val LOGIN_ENDPOINT = "$ORIGIN/api/kb/web/login"
        private const val BRIDGE = "MangaFlowAccount"
        private const val PROFILE_PREFIX = "mangaflow_login_"
        private val liveProfiles = mutableSetOf<String>() // WebView callbacks and ownership stay on the main thread.

        private fun sameOrigin(uri: Uri): Boolean = uri.scheme == "https" && uri.host == "www.copy4000.com" &&
            uri.port in setOf(-1, 443) && uri.userInfo == null

        private fun isLoginPage(uri: Uri): Boolean = sameOrigin(uri) && uri.path.orEmpty().startsWith("/web/login/")

        private fun allowedPage(uri: Uri): Boolean = sameOrigin(uri) && (isLoginPage(uri) ||
            uri.path.orEmpty() in setOf("", "/", "/web/person", "/web/person/", "/web/person/home", "/web/person/userManager"))
    }
}
