package dev.mangaflow.anime

import dev.mangaflow.anime.player.animeHlsTransportOffset
import dev.mangaflow.anime.player.usesImagePrefixedAnimeHls
import dev.mangaflow.anime.sources.animePublicUrl
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.NetworkClient
import dev.mangaflow.net.NetworkResponse
import dev.mangaflow.net.Request
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.platform.wallTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** A bounded media inspection, independent of decoded first frames and sustained playback rate. */
internal data class AnimeMediaPreflight(
    val observedAt: Long,
    val httpStatus: Int? = null,
    val verified: Boolean? = null,
    val failure: String? = null,
    val quality: String? = null,
    val sampleBytesPerSecond: Long? = null,
    val elapsedMs: Long? = null,
    val headerHeight: Int? = null,
)

private const val PROBE_BYTES = 64 * 1024
private val HLS_HEIGHT = Regex("""(?i)\bRESOLUTION=\d+x(\d+)""")
private val DASH_HEIGHT = Regex("""(?i)\bheight\s*=\s*["'](\d+)["']""")
private val HLS_MAP = Regex("(?i)\\bURI=\"([^\"]+)\"")

private data class ProbeSample(val url: HttpUrl, val response: NetworkResponse, val elapsedMs: Long) {
    val bytes get() = response.bytes
    val text get() = bytes.decodeToString().trimStart('\uFEFF', ' ', '\r', '\n', '\t')
    fun rate() = bytes.size.takeIf { it >= 16_384 && elapsedMs >= 50L }?.toLong()?.times(1_000L)?.div(elapsedMs)
}

internal suspend fun inspectAnimeMediaBeforePlayback(asset: AnimePlaybackAsset): AnimeMediaPreflight {
    val origin = publicProbeUrl(asset.url)
        ?: return AnimeMediaPreflight(wallTimeMillis(), failure = "媒体地址超出公开 HTTPS 探测范围")
    val client = NetworkClient(route = asset.route, connectTimeoutMillis = 2_500,
        readTimeoutMillis = 3_000, callTimeoutMillis = 3_500)
    val startedAt = monotonicTimeMillis()
    fun result(code: Int? = null, verified: Boolean? = null, failure: String? = null,
        quality: String? = null, rate: Long? = null, height: Int? = null) =
        AnimeMediaPreflight(wallTimeMillis(), code, verified, failure, quality, rate,
            (monotonicTimeMillis() - startedAt).coerceAtLeast(1L), height)

    suspend fun sample(initial: HttpUrl): ProbeSample? = try {
        withTimeoutOrNull(minOf(3_500L, 8_000L - (monotonicTimeMillis() - startedAt))) {
            var url = initial
            var useRange = true
            repeat(6) {
                val sameOrigin = url.host == origin.host && url.port == origin.port
                val request = Request.Builder().url(url).get().header("Accept-Encoding", "identity")
                if (useRange) request.header("Range", "bytes=0-${PROBE_BYTES - 1}")
                asset.headers.forEach { (name, value) ->
                    val key = name.lowercase()
                    if (key !in ignoredProbeHeaders && (sameOrigin || key in publicMediaHeaders)) request.header(name, value)
                }
                val start = monotonicTimeMillis()
                val response = client.readPrefix(request.build(), PROBE_BYTES) { header ->
                    header.code == 200 || header.code == 206 &&
                        header.header("Content-Range")?.startsWith("bytes 0-", ignoreCase = true) == true
                }
                when {
                    response.code in redirectCodes -> {
                        url = response.header("Location")?.let(url::resolve)?.toString()?.let(::publicProbeUrl)
                            ?: return@withTimeoutOrNull null
                    }
                    response.code == 416 && useRange -> useRange = false
                    else -> return@withTimeoutOrNull ProbeSample(url, response,
                        (monotonicTimeMillis() - start).coerceAtLeast(1L))
                }
            }
            null
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        null
    }

    try {
        val root = sample(origin) ?: return result(failure = "媒体限量读取未完成")
        val code = root.response.code
        if (code !in 200..299) return result(code, when (code) {
            401, 403, 404, 410, 429 -> false
            else -> null
        }, probeHttpMessage(code))
        val text = root.text
        val hls = text.startsWith("#EXTM3U") && text.contains("#EXT-X-")
        val dash = text.startsWith("<MPD") || (text.startsWith("<?xml") && text.contains("<MPD"))
        if (hls) {
            val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            val variants = lines.zipWithNext().filter { (tag, uri) ->
                tag.startsWith("#EXT-X-STREAM-INF:") && !uri.startsWith("#")
            }
            val quality = HLS_HEIGHT.maxHeight(text)
            // Keep the manifest evidence when sampling a rendition or a first segment fails.
            val media = if (variants.isEmpty()) root else variants.maxByOrNull { (tag, _) ->
                HLS_HEIGHT.maxHeight(tag) ?: 0
            }?.second?.let { publicProbeChild(root.url, it) }?.let { sample(it) }
            val mediaText = media?.text?.takeIf { it.startsWith("#EXTM3U") }.orEmpty()
            val segment = mediaText.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            val init = HLS_MAP.find(mediaText.lineSequence().firstOrNull { it.startsWith("#EXT-X-MAP:") }.orEmpty())
                ?.groupValues?.get(1)
            val part = media?.let { playlist -> segment?.let { publicProbeChild(playlist.url, it) } }?.let { sample(it) }
            val goodPart = part?.takeIf { it.response.code in 200..299 &&
                (isVideoPrefix(it.bytes, asset.sourceId) || isVideoContentType(it.response)) }
            val headerHeight = if (quality != null) null else goodPart?.let { mp4VideoHeight(it.bytes) } ?:
                media?.let { playlist -> init?.let { publicProbeChild(playlist.url, it) } }?.let { sample(it) }
                    ?.takeIf { it.response.code in 200..299 }?.let { mp4VideoHeight(it.bytes) }
            val detail = when {
                part != null && part.response.code !in 200..299 -> "清单可读 · 片段 ${probeHttpMessage(part.response.code)}"
                media != null && media.response.code !in 200..299 -> "清单可读 · 子清单 ${probeHttpMessage(media.response.code)}"
                else -> null
            }
            return result(code, true, detail, quality?.let { "${it}p" }, goodPart?.rate(), headerHeight)
        }
        if (dash) return result(code, true, quality = DASH_HEIGHT.maxHeight(text)?.let { "${it}p" })
        val recognized = isVideoPrefix(root.bytes, asset.sourceId)
        if (recognized) return result(code, true, rate = root.rate(), height = mp4VideoHeight(root.bytes))
        val document = text.startsWith("<html", true) || text.startsWith("<!doctype", true) ||
            text.startsWith("{") || text.startsWith("[")
        if (document) return result(code, false, "媒体地址返回了网页或接口内容")
        if (isVideoContentType(root.response)) return result(code, failure = "媒体响应已收到，格式等待播放器确认", rate = root.rate())
        return result(code, failure = "未从样本识别媒体格式")
    } finally {
        client.close()
    }
}

private val ignoredProbeHeaders = setOf("range", "host", "content-length", "accept-encoding", "connection")
private val publicMediaHeaders = setOf("user-agent", "referer", "origin", "accept", "accept-language")
private val redirectCodes = setOf(301, 302, 303, 307, 308)

private fun publicProbeUrl(value: String): HttpUrl? = runCatching { animePublicUrl(value) }.getOrNull()
private fun publicProbeChild(parent: HttpUrl, value: String): HttpUrl? = parent.resolve(value)?.toString()?.let(::publicProbeUrl)
private fun Regex.maxHeight(text: String): Int? = findAll(text).mapNotNull {
    it.groupValues[1].toIntOrNull()?.takeIf { height -> height in 144..8640 }
}.maxOrNull()
private fun probeHttpMessage(code: Int): String = when (code) {
    401, 403 -> "HTTP $code · 媒体地址被拒绝"
    404, 410 -> "HTTP $code · 媒体地址已失效"
    429 -> "HTTP 429 · 来源限流"
    else -> "HTTP $code · 未取得媒体样本"
}
private fun isVideoContentType(response: NetworkResponse) =
    response.header("Content-Type")?.startsWith("video/", ignoreCase = true) == true

private fun isVideoPrefix(bytes: ByteArray, sourceId: String): Boolean =
    bytes.size >= 12 && fourCc(bytes, 4) in setOf("ftyp", "styp", "moof") ||
        bytes.size >= 4 && bytes.int32(0) == 0x1a45dfa3 ||
        bytes.size >= 188 * 5 && (0 until 5).all { bytes[it * 188] == 0x47.toByte() } ||
        bytes.size >= 9 && bytes.decodeToString(0, 3) == "FLV" ||
        usesImagePrefixedAnimeHls(sourceId) && animeHlsTransportOffset(bytes, bytes.size) > 0

private fun fourCc(bytes: ByteArray, offset: Int) = bytes.decodeToString(offset, offset + 4)
private fun ByteArray.int32(offset: Int): Int =
    ((this[offset].toInt() and 255) shl 24) or ((this[offset + 1].toInt() and 255) shl 16) or
        ((this[offset + 2].toInt() and 255) shl 8) or (this[offset + 3].toInt() and 255)

/** Read dimensions only from a complete MP4 video track header in the bounded sample. */
private fun mp4VideoHeight(bytes: ByteArray): Int? {
    fun boxes(start: Int, end: Int): Sequence<Pair<String, IntRange>> = sequence {
        var offset = start
        while (offset <= end - 8) {
            val size = bytes.int32(offset)
            if (size < 8 || size > end - offset) break
            yield(fourCc(bytes, offset + 4) to (offset + 8 until offset + size))
            offset += size
        }
    }
    val movie = boxes(0, bytes.size).firstOrNull { it.first == "moov" }?.second ?: return null
    return boxes(movie.first, movie.last + 1).filter { it.first == "trak" }.mapNotNull { (_, track) ->
        val children = boxes(track.first, track.last + 1).toMap()
        val media = children["mdia"] ?: return@mapNotNull null
        val handler = boxes(media.first, media.last + 1).firstOrNull { it.first == "hdlr" }?.second ?: return@mapNotNull null
        if (handler.last - handler.first < 11 || fourCc(bytes, handler.first + 8) != "vide") return@mapNotNull null
        val header = children["tkhd"] ?: return@mapNotNull null
        if (header.last - header.first < 79) return@mapNotNull null
        (bytes.int32(header.last - 3) ushr 16).takeIf { it in 144..8640 }
    }.maxOrNull()
}
