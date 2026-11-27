package dev.mangaflow.tracking

import dev.mangaflow.APP_DISPLAY_NAME
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull
import dev.mangaflow.platform.randomUuid
import dev.mangaflow.platform.storage.StorageLock
import dev.mangaflow.platform.wallTimeMillis
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

internal data class MangaTrackingAuthorization(
    val id: String,
    val provider: MangaTrackingProvider,
    val startUrl: String,
    val callbackUrl: String?,
    val state: String,
    val createdAt: Long,
)

internal sealed interface MangaTrackingNavigation {
    data object Allow : MangaTrackingNavigation
    data object Block : MangaTrackingNavigation
    data class Credential(val token: String) : MangaTrackingNavigation
    data class Error(val message: String) : MangaTrackingNavigation
}

internal const val MANGA_TRACKING_ANILIST_CALLBACK = "mangaflow://oauth/anilist"
internal const val MANGA_TRACKING_BANGUMI_CREATE = "https://next.bgm.tv/demo/access-token/create"
private const val BANGUMI_TOKEN_ENDPOINT = "https://next.bgm.tv/demo/access-tokens"
internal const val AUTHORIZATION_LIFETIME_MILLIS = 10 * 60 * 1000L

internal fun mangaTrackingAuthorizationNonce(): String =
    randomUuid().replace("-", "") + randomUuid().replace("-", "")

internal fun MangaTrackingAuthorization.isCurrent(): Boolean =
    (wallTimeMillis() - createdAt) in 0..AUTHORIZATION_LIFETIME_MILLIS

/**
 * One dedicated official website session. The host supplies its current main-frame URL,
 * rotates the document challenge on navigation, and destroys the web view/profile before
 * calling deliverAfterCleanup(). No token from a previous page or attempt is imported.
 */
internal class MangaTrackingWebsiteReceipt(
    val authorization: MangaTrackingAuthorization,
    private val onCancel: (String) -> Unit,
    private val onCredential: (String, String) -> Unit,
) {
    val nonce: String = mangaTrackingAuthorizationNonce()
    val initialUrl: String = authorization.startUrl
    val observerOrigins: Set<String> = if (authorization.provider == MangaTrackingProvider.BANGUMI)
        setOf("https://next.bgm.tv") else emptySet()

    private val lock = StorageLock()
    private var document = mangaTrackingAuthorizationNonce()
    private var ready = false
    private var submission = 0L
    private var terminal = false
    private var delivered = false
    private var cancelled = false
    private var acceptedHash: String? = null

    fun newDocument() = synchronized(lock) {
        if (!terminal) {
            document = mangaTrackingAuthorizationNonce()
            ready = false
            submission = 0
        }
    }

    fun navigation(url: String): MangaTrackingNavigation = synchronized(lock) {
        if (terminal) return@synchronized MangaTrackingNavigation.Block
        if (!authorization.isCurrent()) return@synchronized navigationError("本次授权已过期，请重新登录。")
        if (url.length > 32_768 || url.any { it.code < 0x20 || it.code == 0x7f })
            return@synchronized MangaTrackingNavigation.Block
        if (authorization.provider == MangaTrackingProvider.ANILIST &&
            url.startsWith("mangaflow:", ignoreCase = true)) {
            return@synchronized aniListCallback(url)
        }
        val parsed = url.toHttpUrlOrNull() ?: return@synchronized MangaTrackingNavigation.Block
        val expectedHost = when (authorization.provider) {
            MangaTrackingProvider.BANGUMI -> "next.bgm.tv"
            MangaTrackingProvider.ANILIST -> "anilist.co"
        }
        if (parsed.isOfficialHost(expectedHost)) MangaTrackingNavigation.Allow else MangaTrackingNavigation.Block
    }

    fun receive(raw: String, url: String, mainFrame: Boolean): String? = synchronized(lock) {
        if (terminal || !authorization.isCurrent() || authorization.provider != MangaTrackingProvider.BANGUMI ||
            !mainFrame || !isBangumiCreationPage(url) || raw.length > 4096) return@synchronized null
        val data = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return@synchronized null
        fun text(key: String): String? = (data[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (text("nonce") != nonce || text("document") != document) return@synchronized null
        when (text("kind")) {
            "ready" -> { ready = true; return@synchronized null }
            "submitted" -> {
                val sequence = (data["sequence"] as? JsonPrimitive)?.longOrNull ?: return@synchronized null
                if (ready && sequence == submission + 1 && sequence > 0) submission = sequence
                return@synchronized null
            }
            "credential" -> {
                val sequence = (data["sequence"] as? JsonPrimitive)?.longOrNull ?: return@synchronized null
                if (!ready || sequence <= 0 || sequence != submission || text("endpoint") != BANGUMI_TOKEN_ENDPOINT ||
                    (data["status"] as? JsonPrimitive)?.intOrNull != 200) return@synchronized null
                val token = text("token")?.takeIf { it.matches(Regex("[A-Za-z0-9]{40}")) }
                    ?: return@synchronized null
                accept(token)
                return@synchronized token
            }
            else -> return@synchronized null
        }
    }

    /**
     * Register at document start when supported, then call again on successful main-frame
     * load. A second injection rebinds the native document challenge without wrapping fetch
     * twice. The early wrapper cannot accept a response using an old document challenge.
     */
    fun observerScript(bridgeExpression: String): String? = synchronized(lock) {
        if (terminal || !authorization.isCurrent() || authorization.provider != MangaTrackingProvider.BANGUMI)
            return@synchronized null
        bangumiTokenObserver(nonce, document, bridgeExpression)
    }

    /** Only the exact accepted credential can cross the boundary, once native cleanup ends. */
    fun deliverAfterCleanup(token: String) {
        val send = synchronized(lock) {
            if (!terminal || delivered || cancelled || !authorization.isCurrent() ||
                acceptedHash == null || trackingHash(token) != acceptedHash) false
            else { delivered = true; acceptedHash = null; true }
        }
        if (send) onCredential(authorization.id, token)
    }

    fun close() {
        val cancel = synchronized(lock) {
            if (cancelled) false
            else {
                cancelled = true
                terminal = true
                acceptedHash = null
                !delivered
            }
        }
        if (cancel) onCancel(authorization.id)
    }

    private fun aniListCallback(url: String): MangaTrackingNavigation {
        if (authorization.callbackUrl != MANGA_TRACKING_ANILIST_CALLBACK ||
            url.substringBefore('#') != MANGA_TRACKING_ANILIST_CALLBACK || '#' !in url)
            return navigationError("授权回调地址不匹配，请重新登录。")
        val fragment = url.substringAfter('#')
        if ('#' in fragment) return navigationError("授权回执格式不完整，请重新登录。")
        val values = ("https://mangaflow.invalid/?" + fragment).toHttpUrlOrNull()
            ?: return navigationError("授权回执格式不完整，请重新登录。")
        if (values.querySize != values.queryParameterNames.size ||
            values.queryParameter("state") != authorization.state)
            return navigationError("授权回执与本次登录不匹配，请重新登录。")
        if (values.queryParameter("error") != null)
            return navigationError("AniList 未授予本次授权，可重新登录并确认授权。")
        val token = values.queryParameter("access_token")
        val expiry = values.queryParameter("expires_in")
        if (values.queryParameter("token_type")?.equals("Bearer", ignoreCase = true) != true ||
            token == null || token.length !in 16..16_384 || !token.matches(Regex("[A-Za-z0-9._~+/-]+=*")) ||
            (expiry != null && expiry.toLongOrNull()?.let { it > 0 } != true))
            return navigationError("授权回执缺少有效令牌，请重新登录。")
        accept(token)
        return MangaTrackingNavigation.Credential(token)
    }

    private fun accept(token: String) {
        terminal = true
        acceptedHash = trackingHash(token)
    }

    private fun navigationError(message: String): MangaTrackingNavigation.Error {
        terminal = true
        acceptedHash = null
        return MangaTrackingNavigation.Error(message)
    }
}

private fun HttpUrl.isOfficialHost(expected: String): Boolean =
    isHttps && host == expected && port == 443 && username.isEmpty() && password.isEmpty()

private fun isBangumiCreationPage(url: String): Boolean = url.toHttpUrlOrNull()?.let {
    it.isOfficialHost("next.bgm.tv") && it.encodedPath == "/demo/access-token/create" &&
        it.query == null && it.fragment == null
} == true

/**
 * Official source: bangumi/server-private a79f40ccd76689ba10cc8b2a3ea05c060b96abc7,
 * templates/token/create.liquid and routes/demo/token/index.ts (2026-11-22).
 * Observe only the JSON-string response to the user's own trusted form submission.
 * No request body, headers, cookie, password, saved-token list or unrelated response is read.
 */
private fun bangumiTokenObserver(nonce: String, document: String, bridgeExpression: String): String = """
    (() => {
      const officialPage = () => window.top === window && location.origin === 'https://next.bgm.tv' &&
        location.pathname === '/demo/access-token/create' && !location.search && !location.hash;
      if (!officialPage()) return;
      const bridge = __BRIDGE__;
      const nonce = '__NONCE__';
      const challenge = '__DOCUMENT__';
      const slot = '__mangaFlowBangumiAuthorizationV1';
      const prior = window[slot];
      if (prior) {
        if (prior.nonce === nonce && typeof prior.bind === 'function') prior.bind(challenge, bridge);
        return;
      }
      let binding = {document: challenge, bridge};
      let sequence = 0;
      let armed = null;
      const emit = (kind, extra, current = binding.document) => {
        if (!officialPage() || current !== binding.document) return;
        try { binding.bridge(JSON.stringify(Object.assign({kind, nonce, document: current}, extra))); } catch (_) {}
      };
      const bind = (next, callback) => {
        if (binding.document !== next) { sequence = 0; armed = null; }
        binding = {document: next, bridge: callback};
        emit('ready', {});
      };
      Object.defineProperty(window, slot, {value: {nonce, bind}, configurable: false, writable: false});
      const endpoint = value => {
        try {
          const u = new URL(value, location.href);
          return u.origin === 'https://next.bgm.tv' && u.pathname === '/demo/access-tokens' &&
            !u.search && !u.hash && !u.username && !u.password;
        } catch (_) { return false; }
      };
      document.addEventListener('submit', event => {
        if (!officialPage() || !event.isTrusted || !(event.target instanceof HTMLFormElement)) return;
        const form = event.target;
        if (!form.querySelector('#tokenName[name="name"]') || !form.querySelector('select[name="days"]')) return;
        const current = {document: binding.document, sequence: ++sequence, event, used: false};
        armed = current;
        emit('submitted', {sequence: current.sequence}, current.document);
        // A microtask can run between native listeners, before the official form handler.
        // Keep this event's receipt through dispatch; fetch also requires active dispatch.
        setTimeout(() => { if (armed === current) armed = null; }, 0);
      }, true);
      const originalFetch = window.fetch;
      window.fetch = function(input, init) {
        let current = null;
        try {
          const url = typeof input === 'string' || input instanceof URL ? String(input) : input.url;
          const method = (init && init.method) || (input && input.method) || 'GET';
          if (officialPage() && armed && !armed.used && armed.event.eventPhase !== 0 &&
              String(method).toUpperCase() === 'POST' && endpoint(url)) {
            current = armed;
            current.used = true;
          }
        } catch (_) {}
        return originalFetch.apply(this, arguments).then(response => {
          if (current && response.status === 200 && endpoint(response.url)) {
            response.clone().text().then(text => {
              if (!officialPage() || current.document !== binding.document || current.sequence !== sequence ||
                  typeof text !== 'string' || text.length > 128) return;
              try {
                const token = JSON.parse(text);
                if (typeof token === 'string' && /^[A-Za-z0-9]{40}$/.test(token))
                  emit('credential', {sequence: current.sequence, endpoint: 'https://next.bgm.tv/demo/access-tokens',
                    status: response.status, token}, current.document);
              } catch (_) {}
            }).catch(() => {});
          }
          return response;
        });
      };
      const prefillName = () => {
        if (!officialPage()) return;
        const name = document.querySelector('#tokenName[name="name"]');
        if (name && name.value === '') name.value = ${JsonPrimitive(APP_DISPLAY_NAME)};
      };
      if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', prefillName, {once: true});
      else prefillName();
      emit('ready', {});
    })();
    """.trimIndent().replace("__NONCE__", nonce).replace("__DOCUMENT__", document).replace("__BRIDGE__", bridgeExpression)
