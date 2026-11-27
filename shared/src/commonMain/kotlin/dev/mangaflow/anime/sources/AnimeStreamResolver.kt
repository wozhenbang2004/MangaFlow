package dev.mangaflow.anime.sources

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import dev.mangaflow.anime.AnimeResolution
import dev.mangaflow.anime.AnimeSubtitle
import dev.mangaflow.anime.animeDeclaredHeight
import dev.mangaflow.data.sources.SourcePackedScript
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.json.JSONTokener
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.platform.decodeUrlComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal data class ResolvedAnimeStream(
    val url: String,
    val mimeType: String?,
    val referer: String,
    val subtitles: List<AnimeSubtitle> = emptyList(),
    val resolutions: List<AnimeResolution> = emptyList(),
)

/**
 * Data-only extraction from video/source tags, MacCMS player objects, AGE Vurl and
 * bounded player frames. Reuses the application's attributed Packer decoder; no arbitrary
 * remote JavaScript, browser session, cookies or filesystem bridge is exposed.
 */
internal class AnimeStreamResolver(private val http: AnimeHttp, private val sameOriginPages: Boolean = false) {
    private val anonymousPages = AnonymousPlayerPages(http)
    private fun mediaReferer(value: String): String =
        if (http.sourceId in setOf("anime-native-akianime", "anime-native-dmbus")) anonymousMediaReferer(value) else value
    suspend fun resolve(rawUrl: String, referer: String): ResolvedAnimeStream = withTimeoutOrNull(45_000L) {
        val visited = mutableSetOf<String>()
        resolvePage(animeMediaUrl(rawUrl), animePublicUrl(referer).toString(), visited, 0)
            ?: throw AnimeSourceException(http.sourceId, AnimeSourceStage.RESOLVE,
                "这条线路没有提供可直接播放的视频；可能依赖网页验证、动态脚本或已失效。请选择其他线路，或在来源页更正链接。")
    } ?: throw AnimeSourceException(http.sourceId, AnimeSourceStage.RESOLVE, "视频解析超时，请重试或切换线路。")

    private suspend fun resolvePage(
        url: HttpUrl,
        referer: String,
        visited: MutableSet<String>,
        depth: Int,
        akiDeclaredPage: Boolean = false,
    ): ResolvedAnimeStream? {
        animeMediaType(url.toString())?.let { return ResolvedAnimeStream(url.toString(), it, mediaReferer(referer)) }
        // Public player wrappers frequently carry a declared direct media URL.
        url.queryParameter("url")?.let { nested ->
            val direct = runCatching { animeMediaUrl(nested) }.getOrNull()
            if (direct != null && animeMediaType(direct.toString()) != null)
                return ResolvedAnimeStream(direct.toString(), animeMediaType(direct.toString()), mediaReferer(referer))
        }
        if (depth > 3 || !visited.add(url.toString())) return null
        val response = http.request(url, AnimeSourceStage.RESOLVE, referer = mediaReferer(referer),
            sameOriginRedirects = akiDeclaredPage || sameOriginPages)
        if (http.sourceId in setOf("anime-native-akianime", "anime-native-dmbus"))
            anonymousPages.rejectAccessPage(response.text)
        val document = withContext(Dispatchers.Default) { Ksoup.parse(response.text, response.url.toString()) }
        if (document.selectFirst("input[name=gate-answer], form[action*=/captcha/]") != null)
            throw AnimeSourceException(http.sourceId, AnimeSourceStage.RESOLVE, "这条线路要求在原站完成访问验证，请选择其他免验证线路。")
        val pageUrl = response.url.toString()
        val headers = mapOf("User-Agent" to AnimeHttp.USER_AGENT) +
            mediaReferer(pageUrl).takeIf(String::isNotBlank)?.let { mapOf("Referer" to it) }.orEmpty()
        fun subtitlesFor(video: Element) = video.select("track[src]").filter { it.parent() === video }.mapIndexedNotNull { index, element ->
            val track = runCatching { animeMediaAbsolute(pageUrl, element.attr("src")) }.getOrNull()
                ?: return@mapIndexedNotNull null
            AnimeSubtitle("track-$index", track, element.attr("label").ifBlank { "字幕 ${index + 1}" },
                element.attr("srclang").takeIf { it.isNotBlank() }, "text/vtt", headers)
        }
        val videos = document.select("video")
        for (video in videos) {
            val sources = sequenceOf(video) + video.select("source[src]").asSequence().filter { it.parent() === video }
            val candidates = sources.filter { it.attr("src").isNotBlank() }.mapNotNull { node ->
                val url = runCatching { animeMediaAbsolute(pageUrl, node.attr("src")) }.getOrNull() ?: return@mapNotNull null
                val mime = node.attr("type").substringBefore(';').trim().lowercase().takeIf { it in MEDIA_TYPES }
                    ?: animeMediaType(url) ?: return@mapNotNull null
                val height = QUALITY_ATTRIBUTES.mapNotNull { node.attr(it).takeIf(String::isNotBlank)?.let(::animeDeclaredHeight) }
                    .distinct().singleOrNull()
                val label = height?.let { "${it}p" }.orEmpty()
                AnimeResolution(animeHash(label + "\n" + animeContentIdentity(url)), label, url, mime, headers)
            }.distinctBy { it.id }.take(64).toList()
            val primary = candidates.firstOrNull() ?: continue
            val labelled = candidates.filter { it.label.isNotEmpty() }
            val options = if (labelled.isNotEmpty() && labelled.none { it.url == primary.url })
                listOf(primary.copy(label = "来源默认")) + labelled else labelled
            return ResolvedAnimeStream(primary.url, primary.mimeType, mediaReferer(pageUrl), subtitlesFor(video), options)
        }
        val subtitles = videos.singleOrNull()?.let(::subtitlesFor).orEmpty()

        anonymousPages.dmbusPublicMedia(response.url, response.text)?.let { target ->
            return ResolvedAnimeStream(target.url, animeMediaType(target.url), target.referer, subtitles)
        }
        if (akiDeclaredPage) {
            anonymousPages.akiPublicMedia(response.url, response.text)?.let { target ->
                resolvePage(animeMediaUrl(target), pageUrl, visited, depth + 1)?.let { return it }
            }
        }

        val frames = mutableListOf<String>()
        val scripts = document.select("script").map { it.data() }.filter { it.isNotBlank() }.toMutableList()
        // Existing decoder interprets only Packer's literal dictionary, never executes its body.
        for (script in scripts.toList()) {
            if (script.length <= 1_048_576 && script.contains("eval(function("))
                runCatching { SourcePackedScript.unpack(script) }.getOrNull()?.let(scripts::add)
        }
        for (script in scripts) {
            for (objectText in playerObjects(script)) {
                val config = runCatching { JSONTokener(objectText).nextValue() as? JSONObject }.getOrNull() ?: continue
                val raw = config.text("url", "src", "file")
                if (raw.isBlank()) continue
                val playerUrl = decodePlayerUrl(raw, config.optInt("encrypt")) ?: continue
                val decoded = if (isLmm85Source(http.sourceId))
                    LMM85_PLAY_HINT.matchEntire(playerUrl)?.groupValues?.get(1) ?: playerUrl else playerUrl
                if (http.sourceId == "anime-native-akianime" && response.url.host == "www.akianime.com" &&
                    !decoded.startsWith("https://", true) && !decoded.startsWith("http://", true) &&
                    !decoded.startsWith("//") && !decoded.startsWith("/")) {
                    val parser = anonymousPages.akiParser(config.optString("from"), decoded, response.url)
                    if (parser != null) resolvePage(parser, pageUrl, visited, depth + 1, akiDeclaredPage = true)?.let { return it }
                    continue // An opaque token is not a relative webpage address.
                }
                val candidate = runCatching { animeMediaAbsolute(pageUrl, decoded) }.getOrNull() ?: continue
                val mime = animeMediaType(candidate)
                if (mime != null) return ResolvedAnimeStream(candidate, mime, mediaReferer(pageUrl), subtitles)
                frames += candidate
            }
            for (match in MEDIA_ASSIGNMENT.findAll(script)) {
                val literal = match.groupValues[1]
                val raw = runCatching { JSONTokener("[$literal]").nextValue() as? JSONArray }
                    .getOrNull()?.optString(0).orEmpty()
                val candidate = runCatching { animeMediaAbsolute(pageUrl, raw) }.getOrNull() ?: continue
                animeMediaType(candidate)?.let { return ResolvedAnimeStream(candidate, it, mediaReferer(pageUrl), subtitles) }
            }
        }

        // Only actual player frames are followed. Ordinary links, scripts and advertisements are not crawled.
        frames += document.select("iframe[src]").filter { frame ->
            val signature = (frame.attr("id") + " " + frame.attr("class") + " " + frame.attr("src")).lowercase()
            listOf("player", "play", "video", "embed").any(signature::contains)
        }.map { it.attr("src") }
        var frameFailure: AnimeSourceException? = null
        for (raw in frames.distinct().take(8)) {
            val next = runCatching { animePublicUrl(animeAbsolute(pageUrl, raw)) }.getOrNull() ?: continue
            if (sameOriginPages && (next.host != response.url.host || next.port != response.url.port)) continue
            try {
                resolvePage(next, pageUrl, visited, depth + 1)?.let { return it }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: AnimeSourceException) {
                frameFailure = failure
            }
        }
        if (frameFailure != null) throw frameFailure
        if (http.sourceId == "anime-native-dmbus" && response.url.host == "hhjx.hhplayer.com" && response.text.contains("OKOK("))
            throw AnimeSourceException(http.sourceId, AnimeSourceStage.RESOLVE,
                "这条动漫巴士线路使用尚未接入的动态解析协议，未执行外部脚本或猜测密钥，请选择其他线路。")
        return null
    }

    private fun playerObjects(script: String): Sequence<String> = sequence {
        for (match in PLAYER_START.findAll(script).take(32)) {
            val start = script.indexOf('{', match.range.first)
            if (start < 0) continue
            var depth = 0
            var quote: Char? = null
            var escaped = false
            var index = start
            while (index < script.length && index - start <= 262_144) {
                val character = script[index]
                if (quote != null) {
                    if (escaped) escaped = false
                    else if (character == '\\') escaped = true
                    else if (character == quote) quote = null
                } else when (character) {
                    '"', '\'' -> quote = character
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) { yield(script.substring(start, index + 1)); break }
                    }
                }
                index++
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodePlayerUrl(value: String, encryption: Int): String? = runCatching {
        when (encryption) {
            0 -> value
            1 -> decodeUrlComponent(value)
            2 -> decodeUrlComponent(Base64.Default.decode(value).decodeToString())
            else -> return null
        }
    }.getOrNull()

    private companion object {
        val QUALITY_ATTRIBUTES = listOf("data-quality", "data-res", "data-resolution", "res", "label", "size")
        val PLAYER_START = Regex("""(?:\bplayer_[A-Za-z0-9_]+\s*=|\b(?:playerConfig|videoConfig)\s*=)\s*\{""")
        val MEDIA_ASSIGNMENT = Regex("""(?:\bVurl\b|\b(?:video_url|videoUrl|playUrl)\b)\s*[:=]\s*("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')""")
        val LMM85_PLAY_HINT = Regex("""([^?#]+\.m3u8)&t=hls(?:&ct=\d+)?""", RegexOption.IGNORE_CASE)
        val MEDIA_TYPES = setOf("video/mp4", "video/webm", "application/vnd.apple.mpegurl",
            "application/x-mpegURL", "application/dash+xml", "video/x-matroska")
    }
}

internal fun animeMediaType(url: String): String? = when (
    runCatching { animeMediaUrl(url).encodedPath.substringAfterLast('.').lowercase() }.getOrNull()
) {
    "m3u8" -> "application/vnd.apple.mpegurl"
    "mpd" -> "application/dash+xml"
    "mp4", "m4v" -> "video/mp4"
    "webm" -> "video/webm"
    "mkv" -> "video/x-matroska"
    else -> null
}
