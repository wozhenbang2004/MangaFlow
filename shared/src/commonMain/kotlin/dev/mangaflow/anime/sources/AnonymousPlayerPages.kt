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
        val media = runCatching { animePublicUrl(animeAbsolute(page.toString(), raw)).toString() }.getOrNull()
        return media?.takeIf { animeMediaType(it) != null }
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun dmbusPublicMedia(page: HttpUrl, html: String): String? {
        if (http.sourceId != "anime-native-dmbus" || page.host != "hhjx.hhplayer.com" ||
            page.scheme != "https" || page.port != 443 || html.length > 1_048_576) return null
        val bootstrap = withContext(Dispatchers.Default) { literalObject(html,
            Regex("""\bwindow\.__HHJX_BOOTSTRAP__\s*=\s*\{""")) }
        if (bootstrap != null) return dmbusDeclaredMedia(page, bootstrap)
        val match = Regex("""\b(?:var|let|const)\s+url\s*=\s*("(?:\\.|[^"\\])*")""").find(html) ?: return null
        val raw = runCatching { JSONArray("[${match.groupValues[1]}]").getString(0) }.getOrNull() ?: return null
        if (raw.length !in 1..16384) return null
        val candidates = mutableListOf(raw)
        runCatching { decodeUrlComponent(raw) }.getOrNull()?.let(candidates::add)
        runCatching {
            val normalized = raw.replace('-', '+').replace('_', '/')
            Base64.decode(normalized + "=".repeat((4 - normalized.length % 4) % 4)).decodeToString(throwOnInvalidSequence = true)
        }.getOrNull()?.let { decoded ->
            candidates += decoded
            runCatching { decodeUrlComponent(decoded) }.getOrNull()?.let(candidates::add)
        }
        return candidates.firstNotNullOfOrNull { candidate ->
            runCatching { animePublicUrl(animeAbsolute(page.toString(), candidate)).toString() }.getOrNull()
                ?.takeIf { animeMediaType(it) != null }
        }
    }

    private suspend fun dmbusDeclaredMedia(page: HttpUrl, data: JSONObject): String? {
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
        // Only literal public HTTPS media is supported. Browser instructions such as youku
        // remain unsupported, rather than being executed or mistaken for playable content.
        if (result.optString("ext").equals("youku", ignoreCase = true)) return null
        if (http.route != DownloadRoutingStore.activeSourceSnapshot(http.sourceId, DownloadCategory.ANIME))
            throw AnimeSourceException(http.sourceId, AnimeSourceStage.RESOLVE, "动漫来源路由已变化，请重新读取。")
        val media = runCatching { animePublicUrl(result.optString("url")) }.getOrNull() ?: return null
        return media.toString().takeIf { media.scheme == "https" && animeMediaType(it) != null }
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
}

/** Never persist or forward an opaque parser query/fragment in media, subtitle or frame headers. */
internal fun anonymousMediaReferer(value: String): String = animePublicUrl(value).let { url ->
    url.newBuilder().apply { url.queryParameterNames.forEach { name -> removeAllQueryParameters(name) }; fragment(null) }.build().toString()
}
