package dev.mangaflow.anime.sources

import com.fleeksoft.ksoup.Ksoup
import dev.mangaflow.json.JSONObject
import dev.mangaflow.json.JSONArray
import dev.mangaflow.platform.decodeUrlComponent
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRoutingStore
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class AnonymousPlayerMedia(val url: String, val referer: String)

/** Original, bounded data-only reader for public Aki player declarations. No JS evaluator,
 * embedded keys, browser cookies, account tokens or challenge solving. The DMBus HHJX
 * adapter only returns page-issued parameters to its exact same-origin public parser.
 */
internal class AnonymousPlayerPages(private val http: AnimeHttp) {
    suspend fun akiParser(from: String, token: String, page: HttpUrl): HttpUrl? {
        if (http.sourceId != "anime-native-akianime" || page.host != "www.akianime.com") return null
        require(from.length <= 128 && token.length in 1..16384)
        val configUrl = animePublicUrl("https://www.akianime.com/static/js/playerconfig.js")
        val response = http.request(configUrl, AnimeSourceStage.RESOLVE, referer = anonymousMediaReferer(page.toString()), sameOriginRedirects = true)
        require(response.url.host == configUrl.host) { "来源播放器配置跳转到其他站点，已停止解析。" }
        rejectAccessPage(response.text)
        val list = withContext(Dispatchers.Default) { literalObject(response.text,
            Regex("\\b(?:MacPlayerConfig\\.)?player_list\\s*=\\s*\\{")) } ?: return null
        val player = list.optJSONObject(from) ?: return null
        if (player.optString("ps") != "1") return null
        val prefix = player.optString("parse")
        if (prefix.isBlank()) return null
        val base = animePublicUrl(animeAbsolute(page.toString(), prefix))
        require(base.host in setOf("www.akianime.com", "akianime.com") && base.port == 443) {
            "此线路指向尚未确认的外部解析站，未发送播放参数或提交表单，请选择其他线路。"
        }
        // Only a declared URL query slot can receive this episode's opaque media token.
        require(base.queryParameterNames.contains("url") && prefix.endsWith("url=")) {
            "播放器解析地址格式未确认，未猜测或提交线路参数。"
        }
        return animePublicUrl(base.toString() + token.encodeURLParameter())
    }

    suspend fun akiPublicMedia(page: HttpUrl, html: String): String? {
        check(http.sourceId == "anime-native-akianime")
        check(page.host in setOf("www.akianime.com", "akianime.com") && page.port == 443)
        rejectAccessPage(html)
        val config = withContext(Dispatchers.Default) { literalObject(html,
            Regex("\\b(?:var|let|const)\\s+config\\s*=\\s*\\{")) } ?: return null
        val raw = config.optString("url").takeIf { it.isNotBlank() && it.length <= 16384 } ?: return null
        // A public media declaration is data; opaque tokens/key/time require an unverified POST
        // protocol and are deliberately not submitted or evaluated.
        val media = runCatching { animeMediaAbsolute(page.toString(), raw) }.getOrNull()
        return media?.takeIf { animeMediaType(it) != null }
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun dmbusPublicMedia(page: HttpUrl, html: String): AnonymousPlayerMedia? {
        if (http.sourceId != "anime-native-dmbus" || page.host != "hhjx.hhplayer.com" ||
            page.scheme != "https" || page.port != 443 || html.length > 1_048_576) return null
        val bootstrap = withContext(Dispatchers.Default) { literalObject(html,
            Regex("""\bwindow\.__HHJX_BOOTSTRAP__\s*=\s*\{""")) }
        if (bootstrap != null) return dmbusDeclaredMedia(page, bootstrap)
        val match = Regex("""\b(?:var|let|const)\s+url\s*=\s*("(?:\\.|[^"\\])*")""").find(html)
        val raw = match?.let { runCatching { JSONArray("[${it.groupValues[1]}]").getString(0) }.getOrNull() }
        if (raw != null && raw.length in 1..16384) {
            val candidates = mutableListOf(raw)
            runCatching { decodeUrlComponent(raw) }.getOrNull()?.let(candidates::add)
            runCatching {
                val normalized = raw.replace('-', '+').replace('_', '/')
                Base64.decode(normalized + "=".repeat((4 - normalized.length % 4) % 4)).decodeToString(throwOnInvalidSequence = true)
            }.getOrNull()?.let { decoded ->
                candidates += decoded
                runCatching { decodeUrlComponent(decoded) }.getOrNull()?.let(candidates::add)
            }
            val media = candidates.firstNotNullOfOrNull { candidate ->
                runCatching { animeMediaAbsolute(page.toString(), candidate) }.getOrNull()
                    ?.takeIf { animeMediaType(it) != null }
            }
            if (media != null) return AnonymousPlayerMedia(media, anonymousMediaReferer(page.toString()))
        }
        return dmbusLegacyMedia(page, html)
    }

    private suspend fun dmbusDeclaredMedia(page: HttpUrl, data: JSONObject): AnonymousPlayerMedia? {
        // Observed public HHJX page contract: parameters belong to this exact episode URL.
        // Never retain them, forward them across origins, or execute the page's JavaScript.
        val opaque = data.optString("url")
        val key = data.optString("key")
        val time = data.optString("t").toLongOrNull()
        if (data.optString("error").isNotBlank() || data.has("act") ||
            opaque.length !in 1..16384 || opaque != page.queryParameter("url") ||
            !Regex("""[0-9a-fA-F]{64}""").matches(key) || time == null ||
            time !in 946_684_800L until 4_133_980_800L)
            throw AnimeSourceException(http.sourceId, AnimeSourceStage.RESOLVE,
                "此线路的公开解析参数已变化或需要验证，请刷新或选择其他线路。")
        if (http.route != DownloadRoutingStore.activeSourceSnapshot(http.sourceId, DownloadCategory.ANIME))
            throw AnimeSourceException(http.sourceId, AnimeSourceStage.RESOLVE, "动漫来源路由已变化，请重新读取。")
        val endpoint = animePublicUrl("https://hhjx.hhplayer.com/api/parse")
        val response = http.request(endpoint, AnimeSourceStage.RESOLVE, method = "POST",
            payload = JSONObject().put("url", opaque).put("t", time).put("key", key).put("client_fallback", false),
            referer = anonymousMediaReferer(page.toString()), sameOriginRedirects = true)
        val result = withContext(Dispatchers.Default) { JSONObject(response.text) }
        if (result.optInt("code") != 200) throw AnimeSourceException(http.sourceId, AnimeSourceStage.RESOLVE,
            "此线路的公开解析暂未成功，请刷新或选择其他线路。")
        // Only literal public media is supported. Browser instructions such as youku
        // remain unsupported, rather than being executed or mistaken for playable content.
        if (result.optString("ext").equals("youku", ignoreCase = true)) return null
        if (http.route != DownloadRoutingStore.activeSourceSnapshot(http.sourceId, DownloadCategory.ANIME))
            throw AnimeSourceException(http.sourceId, AnimeSourceStage.RESOLVE, "动漫来源路由已变化，请重新读取。")
        val media = runCatching { animeMediaUrl(result.optString("url")) }.getOrNull() ?: return null
        return media.toString().takeIf { animeMediaType(it) != null }
            ?.let { AnonymousPlayerMedia(it, anonymousMediaReferer(page.toString())) }
    }

    /** Published dmbus.js SHA-256 1268c4dce7b4e79d36a0516331f69eb472694f0591954eff9d621370ec7fdeb3:
     * play() reads this page's literal url/t/key and posts them once to /api.php. OKOK is
     * a public text encoding, not a stored key, browser challenge or executable page bridge.
     */
    private suspend fun dmbusLegacyMedia(page: HttpUrl, html: String): AnonymousPlayerMedia? {
        if (page.encodedPath != "/index.php") return null
        val declarations = withContext(Dispatchers.Default) {
            Ksoup.parse(html).select("script:not([src])").map { it.data() }
                .filter { HHJX_KEY_START.containsMatchIn(it) }
        }
        if (declarations.isEmpty()) return null
        if (declarations.size != 1 || page.querySize != 1 || page.queryParameterNames != setOf("url") ||
            page.queryParameter("url").orEmpty().length !in 1..16384)
            throw dmbusChanged()
        val script = declarations.single()
        val opaque = literalString(script, HHJX_URL) ?: throw dmbusChanged()
        val timeToken = literalString(script, HHJX_TIME) ?: throw dmbusChanged()
        val encodedKey = literalString(script, HHJX_KEY) ?: throw dmbusChanged()
        val key = decodeHhjxKey(encodedKey) ?: throw dmbusChanged()
        // The public parameters belong to the current episode page, never a cached or
        // caller-supplied form. Unlike __HHJX_BOOTSTRAP__, this published older contract
        // treats url and t as opaque page-issued strings; it does not assert query equality
        // or reinterpret t as Unix seconds.
        if (opaque.length !in 1..16384 || timeToken.length !in 1..1024)
            throw dmbusChanged()
        checkDmbusRoute()
        val response = http.request(animePublicUrl("https://hhjx.hhplayer.com/api.php"), AnimeSourceStage.RESOLVE,
            method = "POST", referer = page.toString(), sameOriginRedirects = true,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            formPayload = mapOf("url" to opaque, "t" to timeToken, "key" to key, "act" to "0", "play" to "1"))
        checkDmbusRoute()
        rejectAccessPage(response.text)
        val result = withContext(Dispatchers.Default) { runCatching { JSONObject(response.text) }.getOrNull() }
            ?: throw dmbusChanged()
        if (result.optInt("code") != 200 || result.optString("error").isNotBlank())
            throw AnimeSourceException(http.sourceId, AnimeSourceStage.RESOLVE,
                "此线路的公开解析暂未成功，请刷新或选择其他线路。")
        if (result.optString("ext").lowercase() in setOf("link", "youku"))
            throw AnimeSourceException(http.sourceId, AnimeSourceStage.RESOLVE,
                "这条动漫巴士线路仍需要网页播放器解析，请选择其他线路。")
        val raw = result.optString("url").replace("\\/", "/")
        if (raw.length !in 1..16384) throw dmbusChanged()
        // The published protocol also returns /cache/... paths. Resolve those against
        // this parser origin while preserving absolute media schemes and signed queries.
        val media = runCatching { animeMediaUrl(animeMediaAbsolute(page.toString(), raw)) }.getOrNull()
            ?.takeIf { animeMediaType(it.toString()) != null } ?: throw dmbusChanged()
        val declaredReferer = result.optString("referer")
        val referer = when {
            declaredReferer.isNotBlank() && declaredReferer != "never" -> {
                val value = runCatching { animeMediaUrl(declaredReferer) }.getOrNull() ?: throw dmbusChanged()
                value.newBuilder().apply {
                    value.queryParameterNames.forEach { removeAllQueryParameters(it) }
                    fragment(null)
                }.build().toString()
            }
            media.scheme == page.scheme && media.host == page.host && media.port == page.port ->
                anonymousMediaReferer(page.toString())
            else -> ""
        }
        checkDmbusRoute()
        return AnonymousPlayerMedia(media.toString(), referer)
    }

    private fun dmbusChanged() = AnimeSourceException(http.sourceId, AnimeSourceStage.RESOLVE,
        "此线路的公开解析参数已变化或需要验证，请刷新或选择其他线路。")

    private fun checkDmbusRoute() {
        if (http.route != DownloadRoutingStore.activeSourceSnapshot(http.sourceId, DownloadCategory.ANIME))
            throw AnimeSourceException(http.sourceId, AnimeSourceStage.RESOLVE, "动漫来源路由已变化，请重新读取。")
    }

    private fun literalString(script: String, pattern: Regex): String? {
        val matches = pattern.findAll(script).take(2).toList()
        if (matches.size != 1) return null
        return runCatching { JSONArray("[${matches.single().groupValues[1]}]").getString(0) }.getOrNull()
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeHhjxKey(encoded: String): String? {
        if (encoded.length !in 1..8192) return null
        val text = runCatching { Base64.decode(encoded).decodeToString(throwOnInvalidSequence = true) }.getOrNull()
            ?: return null
        if (text.any { it.code !in 0x20..0x7e }) return null
        val result = StringBuilder()
        var index = 0
        while (index < text.length && result.length <= 512) {
            val token = HHJX_TOKENS.entries.firstOrNull { text.startsWith(it.key, index) }
            if (token == null) result.append(text[index++])
            else { result.append(token.value); index += token.key.length }
        }
        return result.toString().takeIf { it.length in 1..512 && index == text.length }
    }

    suspend fun rejectAccessPage(html: String): Unit = withContext(Dispatchers.Default) {
        val doc = Ksoup.parse(html)
        if (doc.selectFirst("input[type=password], input[name=gate-answer], form[action*=/captcha/], iframe[src*=captcha], .g-recaptcha, .h-captcha, .cf-turnstile") != null ||
            html.contains("/cdn-cgi/challenge-platform/") || html.contains("cf-chl-"))
            throw AnimeSourceException(http.sourceId, AnimeSourceStage.RESOLVE,
                "此线路要求登录或完成原站访问验证，已停止自动解析，请选择其他线路。")
    }

    private fun literalObject(text: String, startPattern: Regex): JSONObject? {
        if (text.length > 1_048_576) return null
        val start = startPattern.find(text)?.range?.last ?: return null
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until minOf(text.length, start + 262144)) {
            val char = text[index]
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> if (--depth == 0) return runCatching { JSONObject(text.substring(start, index + 1)) }.getOrNull()
            }
        }
        return null
    }

    private companion object {
        val HHJX_KEY_START = Regex("""\b(?:var|let|const)\s+key\s*=\s*OKOK\s*\(""")
        val HHJX_URL = Regex("""\b(?:var|let|const)\s+url\s*=\s*("(?:\\.|[^"\\])*")\s*;""")
        val HHJX_TIME = Regex("""\b(?:var|let|const)\s+t\s*=\s*("(?:\\.|[^"\\])*")\s*;""")
        val HHJX_KEY = Regex("""\b(?:var|let|const)\s+key\s*=\s*OKOK\s*\(\s*("(?:\\.|[^"\\])*")\s*\)\s*;""")
        // Protocol alphabet from the pinned public source; no provider secret is bundled.
        val HHJX_TOKENS = mapOf(
            "0Oo0o0Oo" to 'a', "1O0bO001" to 'b', "1OoCcO1" to 'c', "3O0dO0O3" to 'd',
            "4OoEeO4" to 'e', "5O0fO0O5" to 'f', "6OoGgO6" to 'g', "7O0hO0O7" to 'h',
            "8OoIiO8" to 'i', "9O0jO0O9" to 'j', "0OoKkO0" to 'k', "1O0lO0O1" to 'l',
            "2OoMmO2" to 'm', "3O0nO0O3" to 'n', "4OoOoO4" to 'o', "5O0pO0O5" to 'p',
            "6OoQqO6" to 'q', "7O0rO0O7" to 'r', "8OoSsO8" to 's', "9O0tOoO9" to 't',
            "0OoUuO0" to 'u', "1O0vO0O1" to 'v', "2OoWwO2" to 'w', "3O0xO0O3" to 'x',
            "4OoYyO4" to 'y', "5O0zO0O5" to 'z', "0OoAAO0" to 'A', "1O0BBO1" to 'B',
            "2OoCCO2" to 'C', "3O0DDO3" to 'D', "4OoEEO4" to 'E', "5O0FFO5" to 'F',
            "6OoGGO6" to 'G', "7O0HHO7" to 'H', "8OoIIO8" to 'I', "9O0JJO9" to 'J',
            "0OoKKO0" to 'K', "1O0LLO1" to 'L', "2OoMMO2" to 'M', "3O0NNO3" to 'N',
            "4OoOOO4" to 'O', "5O0PPO5" to 'P', "6OoQQO6" to 'Q', "7O0RRO7" to 'R',
            "8OoSSO8" to 'S', "9O0TTO9" to 'T', "0OoUO0" to 'U', "1O0VVO1" to 'V',
            "2OoWWO2" to 'W', "3O0XXO3" to 'X', "4OoYYO4" to 'Y', "5O0ZZO5" to 'Z',
        )
    }
}

/** Never persist or forward an opaque parser query/fragment in media, subtitle or frame headers. */
internal fun anonymousMediaReferer(value: String): String = animePublicUrl(value).let { url ->
    url.newBuilder().apply { url.queryParameterNames.forEach { name -> removeAllQueryParameters(name) }; fragment(null) }.build().toString()
}
