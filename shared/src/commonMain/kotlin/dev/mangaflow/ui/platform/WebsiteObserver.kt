package dev.mangaflow.ui.platform

import dev.mangaflow.account.AccountTokenInput
import dev.mangaflow.account.CopyWebsiteEndpoints
import dev.mangaflow.platform.randomUuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The original Android document/submission ownership contract, reused by native web views. */
internal class WebsiteLoginReceipt(
    onBegin: () -> Long,
    private val onCancel: (Long) -> Unit,
    private val onCredential: (Long, String) -> Unit,
) {
    val nonce = randomUuid()
    val attempt = onBegin()
    private var document: String? = null
    private var submission = 0L
    private var terminal = false
    private var delivered = false
    private var cancelled = false

    fun newDocument() { if (!terminal) { document = null; submission = 0 } }
    fun receive(raw: String, url: String, mainFrame: Boolean): String? {
        if (terminal || !mainFrame || !allowedWebsitePage(url) || raw.length > 8192) return null
        val data = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        fun text(key: String) = (data[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (text("nonce") != nonce) return null
        val incoming = text("document")
        if (incoming.length !in 8..80) return null
        if (text("kind") == "ready") {
            if (document == null && isWebsiteLoginPage(url)) document = incoming
            return null
        }
        if (incoming != document) return null
        val sequence = (data["sequence"] as? JsonPrimitive)?.longOrNull ?: return null
        if (text("kind") == "submitted") {
            if (sequence == submission + 1) submission = sequence
            return null
        }
        if (text("kind") != "credential" || sequence <= 0 || sequence != submission ||
            text("endpoint") != CopyWebsiteEndpoints.ORIGIN + "/api/kb/web/login" ||
            (data["status"] as? JsonPrimitive)?.intOrNull != 200 || (data["code"] as? JsonPrimitive)?.intOrNull != 200) return null
        val token = runCatching { AccountTokenInput.validate(text("token")) }.getOrNull() ?: return null
        terminal = true
        return token
    }
    /** Native view/cookies/profile must be destroyed before this transfers the opaque credential. */
    fun deliverAfterCleanup(token: String) {
        if (!terminal || delivered || cancelled) return
        delivered = true
        onCredential(attempt, token)
    }
    fun close() {
        if (cancelled) return
        cancelled = true
        terminal = true
        if (!delivered) onCancel(attempt)
    }
}

internal fun allowedWebsitePage(url: String): Boolean {
    val prefix = CopyWebsiteEndpoints.ORIGIN
    val rest = when {
        url.startsWith(prefix + "/") -> url.removePrefix(prefix)
        url.startsWith(prefix + ":443/") -> url.removePrefix(prefix + ":443")
        url == prefix -> "/"
        else -> return false
    }
    val path = rest.substringBefore('?').substringBefore('#')
    return path.startsWith("/web/login/") || path in setOf("/", "/web/person", "/web/person/", "/web/person/home", "/web/person/userManager")
}
internal fun isWebsiteLoginPage(url: String): Boolean = allowedWebsitePage(url) &&
    url.substringAfter("://").substringAfter('/').substringBefore('?').substringBefore('#').startsWith("web/login/")

/** Adapted from the app's original strict WebView observer; it never reads passwords or cookies. */
internal fun websiteObserver(nonce: String, bridgeExpression: String): String {
    require(nonce.matches(Regex("[A-Za-z0-9-]{8,80}")))
    return """

        (() => {
          if (window.top !== window || location.origin !== 'https://www.copy4000.com' || !location.pathname.startsWith('/web/login/')) return;
          const bridge = __BRIDGE_EXPRESSION__;
          // Fit the existing official form to the phone; keep its own validation,
          // password controls, submission logic and source errors untouched.
          const fit = () => {
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
          }; if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fit, {once: true}); else fit();
          const documentId = Array.from(crypto.getRandomValues(new Uint32Array(4)), x => x.toString(16).padStart(8, '0')).join('');
          let sequence = 0;
          const emit = (kind, extra) => bridge.postMessage(JSON.stringify(Object.assign({kind, nonce: '__NONCE__', document: documentId}, extra)));
          const endpoint = value => { try { const u = new URL(value, location.href); return u.origin === 'https://www.copy4000.com' && u.pathname === '/api/kb/web/login' && !u.search && !u.hash && !u.username && !u.password; } catch (_) { return false; } };
          const receive = (url, status, text, current) => {
            if (current !== sequence || status !== 200 || !endpoint(url) || typeof text !== 'string' || text.length > 65536) return;
            try { const r = JSON.parse(text); if (r.code === 200 && r.results && typeof r.results.token === 'string') emit('credential', {sequence: current, endpoint: 'https://www.copy4000.com/api/kb/web/login', status, code: r.code, token: r.results.token}); } catch (_) {}
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
        """.trimIndent().replace("__NONCE__", nonce).replace("__BRIDGE_EXPRESSION__", bridgeExpression)
}
