package dev.mangaflow.anime

import dev.mangaflow.anime.player.animeHlsTransportOffset
import dev.mangaflow.anime.player.usesImagePrefixedAnimeHls
import dev.mangaflow.anime.sources.animeMediaUrl
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.NetworkClient
import dev.mangaflow.net.NetworkResponse
import dev.mangaflow.net.Request
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.platform.wallTimeMillis
import kotlinx.coroutines.CancellationException

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
private const val PROBE_TRACK_BYTES = 256 * 1024
private val HLS_HEIGHT = Regex("""(?i)\bRESOLUTION=\d+x(\d+)""")
private val DASH_HEIGHT = Regex("""(?i)\bheight\s*=\s*["'](\d+)["']""")
private val HLS_MAP = Regex("(?i)\\bURI=\"([^\"]+)\"")
private val HLS_MAP_RANGE = Regex("(?:^|,)BYTERANGE=\"([^\"]+)\"")

private data class ProbeSample(val url: HttpUrl, val response: NetworkResponse, val elapsedMs: Long) {
    val bytes get() = response.bytes
    val text get() = bytes.decodeToString().trimStart('\uFEFF', ' ', '\r', '\n', '\t')
    fun rate() = bytes.size.takeIf { it >= 16_384 && elapsedMs >= 50L }?.toLong()?.times(1_000L)?.div(elapsedMs)
}

internal suspend fun inspectAnimeMediaBeforePlayback(asset: AnimePlaybackAsset): AnimeMediaPreflight {
    val origin = publicProbeUrl(asset.url)
        ?: return AnimeMediaPreflight(wallTimeMillis(), failure = "媒体地址超出公开 HTTP(S) 探测范围")
    val client = NetworkClient(route = asset.route, publicMediaOnly = true, connectTimeoutMillis = 2_500,
        readTimeoutMillis = 4_000, callTimeoutMillis = 4_500)
    val startedAt = monotonicTimeMillis()
    fun result(code: Int? = null, verified: Boolean? = null, failure: String? = null,
        quality: String? = null, rate: Long? = null, height: Int? = null) =
        AnimeMediaPreflight(wallTimeMillis(), code, verified, failure, quality, rate,
            (monotonicTimeMillis() - startedAt).coerceAtLeast(1L), height)

    suspend fun sample(initial: HttpUrl, range: LongRange? = null, maximumBytes: Int = PROBE_BYTES): ProbeSample? {
        val deadline = minOf(monotonicTimeMillis() + 3_500L, startedAt + 8_000L)
        return try {
            var url = initial
            repeat(6) {
                val sameOrigin = url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port
                val request = Request.Builder().url(url).get().header("Accept-Encoding", "identity")
                // A whole HLS resource uses the player's ordinary GET. The transport
                // still ends the read at the sample limit even when no Range is needed.
                range?.let { request.header("Range", "bytes=${it.first}-${it.last}") }
                asset.headers.forEach { (name, value) ->
                    val key = name.lowercase()
                    if (key !in ignoredProbeHeaders && (sameOrigin || key in publicMediaHeaders)) request.header(name, value)
                }
                val start = monotonicTimeMillis()
                val maximum = range?.let { (it.last - it.first + 1L).toInt() } ?: maximumBytes
                val offset = range?.first ?: 0L
                val response = client.readPrefix(request.build(), maximum, deadline - start) { header ->
                    header.code == 200 && offset == 0L || header.code == 206 &&
                        header.header("Content-Range")?.startsWith("bytes $offset-", ignoreCase = true) == true
                } ?: return null
                when {
                    response.code in redirectCodes -> {
                        val next = response.header("Location")?.let(url::resolve)?.toString()?.let(::publicProbeUrl)
                            ?: return null
                        if (url.isHttps && !next.isHttps) return null
                        url = next
                    }
                    else -> return ProbeSample(url, response,
                        (monotonicTimeMillis() - start).coerceAtLeast(1L))
                }
            }
            null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
    }

    suspend fun sampleHlsPart(url: HttpUrl, rangeText: String?, maximumBytes: Int = PROBE_BYTES): ProbeSample? {
        val range = if (rangeText == null) null else firstHlsSampleRange(rangeText, maximumBytes) ?: return null
        return sample(url, range, maximumBytes)
    }

    suspend fun sampleMp4TrackHeight(root: ProbeSample): Int? {
        if (root.bytes.size < 8 || fourCc(root.bytes, 4) != "ftyp") return null
        val total = (if (root.response.code == 200) root.response.header("Content-Length")
            else root.response.header("Content-Range")?.substringAfterLast('/'))?.toLongOrNull() ?: Long.MAX_VALUE
        val start = mp4MetadataOffset(root.bytes, total) ?: return null
        val length = minOf(total - start, PROBE_TRACK_BYTES.toLong())
        return sample(root.url, start..(start + length - 1L), PROBE_TRACK_BYTES)
            ?.takeIf { it.response.code == 206 }?.let { mp4VideoHeight(it.bytes) }
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
            suspend fun samplePlaylist(media: ProbeSample?, height: Int?): AnimeMediaPreflight {
                val mediaLines = media?.text?.takeIf { it.startsWith("#EXTM3U") }.orEmpty()
                    .lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
                val firstSegment = mediaLines.indexOfFirst { !it.startsWith("#") }
                val prefix = if (firstSegment >= 0) mediaLines.take(firstSegment) else emptyList()
                val byteRange = prefix.lastOrNull { it.startsWith("#EXT-X-BYTERANGE:") }?.substringAfter(':')
                val initTag = prefix.lastOrNull { it.startsWith("#EXT-X-MAP:") }.orEmpty().substringAfter(':')
                val init = HLS_MAP.find(initTag)?.groupValues?.get(1)
                val part = media?.let { playlist ->
                    mediaLines.getOrNull(firstSegment)?.let { publicProbeChild(playlist.url, it) }
                }?.let { sampleHlsPart(it, byteRange, if (height == null) PROBE_TRACK_BYTES else PROBE_BYTES) }
                val goodPart = part?.takeIf { it.response.code in 200..299 && it.bytes.isNotEmpty() &&
                    (isVideoPrefix(it.bytes, asset.sourceId) || isVideoContentType(it.response)) }
                val headerHeight = if (height != null) null else goodPart?.let { videoSampleHeight(it.bytes, asset.sourceId) } ?:
                    media?.let { playlist -> init?.let { publicProbeChild(playlist.url, it) } }
                        ?.let { sampleHlsPart(it, HLS_MAP_RANGE.find(initTag)?.groupValues?.get(1)) }
                        ?.takeIf { it.response.code in 200..299 }?.let { videoSampleHeight(it.bytes, asset.sourceId) }
                val detail = when {
                    part != null && part.response.code !in 200..299 -> "清单可读 · 片段 ${probeHttpMessage(part.response.code)}"
                    media != null && media.response.code !in 200..299 -> "清单可读 · 子清单 ${probeHttpMessage(media.response.code)}"
                    goodPart == null -> "清单可读 · 未取得视频片段样本"
                    else -> null
                }
                return result(code, true, detail, height?.let { "${it}p" }, goodPart?.rate(), headerHeight)
            }
            if (variants.isEmpty()) return samplePlaylist(root, HLS_HEIGHT.maxHeight(text))
            // A broken high-resolution rendition does not invalidate the master playlist.
            var firstEvidence: AnimeMediaPreflight? = null
            val renditions = variants.mapNotNull { (tag, raw) ->
                publicProbeChild(root.url, raw)?.let { it to HLS_HEIGHT.maxHeight(tag) }
            }.sortedByDescending { it.second ?: 0 }.distinctBy { it.first }.take(3)
            for ((url, height) in renditions) {
                if (monotonicTimeMillis() >= startedAt + 8_000L) break
                val evidence = samplePlaylist(sample(url), height)
                if (evidence.failure == null) return evidence
                if (firstEvidence == null) firstEvidence = evidence
            }
            return firstEvidence ?: result(code, true, "清单可读 · 未取得视频片段样本")
        }
        if (dash) return result(code, true, "清单可读 · 尚未采样视频片段",
            quality = DASH_HEIGHT.maxHeight(text)?.let { "${it}p" })
        val recognized = isVideoPrefix(root.bytes, asset.sourceId)
        if (recognized) return result(code, true, rate = root.rate(),
            height = videoSampleHeight(root.bytes, asset.sourceId) ?: sampleMp4TrackHeight(root))
        val document = text.startsWith("<html", true) || text.startsWith("<!doctype", true) ||
            text.startsWith("{") || text.startsWith("[")
        if (document) return result(code, false, "媒体地址返回了网页或接口内容")
        if (isVideoContentType(root.response)) return result(code, failure = "媒体响应已收到，格式等待播放器确认", rate = root.rate())
        return result(code, failure = "未从样本识别媒体格式")
    } finally {
        client.close()
    }
}

private val ignoredProbeHeaders = setOf("host", "connection", "content-length", "transfer-encoding",
    "proxy-authorization", "proxy-authenticate", "upgrade", "range", "if-range", "accept-encoding")
private val publicMediaHeaders = setOf("user-agent", "referer", "origin", "accept", "accept-language")
private val redirectCodes = setOf(301, 302, 303, 307, 308)

private fun publicProbeUrl(value: String): HttpUrl? = runCatching { animeMediaUrl(value) }.getOrNull()
private fun publicProbeChild(parent: HttpUrl, value: String): HttpUrl? = parent.resolve(value)?.toString()?.let(::publicProbeUrl)
private fun firstHlsSampleRange(value: String, maximumBytes: Int): LongRange? {
    val length = value.substringBefore('@').toLongOrNull()?.takeIf { it > 0L } ?: return null
    val start = value.substringAfter('@', "").toLongOrNull()?.takeIf { it >= 0L } ?: return null
    val lastDelta = minOf(length, maximumBytes.toLong()) - 1L
    if (start > Long.MAX_VALUE - lastDelta) return null
    return start..(start + lastDelta)
}

private suspend fun videoSampleHeight(bytes: ByteArray, sourceId: String): Int? {
    val offset = if (usesImagePrefixedAnimeHls(sourceId)) animeHlsTransportOffset(bytes, bytes.size) else 0
    val media = if (offset > 0) bytes.copyOfRange(offset, bytes.size) else bytes
    return mp4VideoHeight(media) ?: platformAnimeSampleHeight(media)
}

internal expect suspend fun platformAnimeSampleHeight(bytes: ByteArray): Int?
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

private data class Mp4Box(val type: String, val offset: Int, val payload: Int, val end: Long)

private fun mp4Boxes(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): Sequence<Mp4Box> = sequence {
    var offset = start
    while (offset <= end - 8) {
        val size32 = bytes.int32(offset).toUInt().toLong()
        val header = if (size32 == 1L) 16 else 8
        if (offset > end - header) break
        val size = if (header == 16) {
            val high = bytes.int32(offset + 8).toLong()
            if (high < 0L) break
            (high shl 32) or bytes.int32(offset + 12).toUInt().toLong()
        } else size32
        if (size < header || size > Long.MAX_VALUE - offset) break
        val next = offset + size
        yield(Mp4Box(fourCc(bytes, offset + 4), offset, offset + header, next))
        if (next > end) break
        offset = next.toInt()
    }
}

/** Use the sizes of top-level boxes to seek past an incomplete media payload. */
private fun mp4MetadataOffset(bytes: ByteArray, total: Long): Long? {
    var next = 0L
    for (box in mp4Boxes(bytes)) {
        if (box.end > total) return null
        if (box.type == "moov") return box.offset.toLong().takeIf { box.end > bytes.size }
        next = box.end
    }
    return next.takeIf { it > 0L && it < total }
}

/** Only a complete track header and the video handler supply dimensions. */
private fun mp4VideoHeight(bytes: ByteArray): Int? {
    fun children(parent: Mp4Box) = mp4Boxes(bytes, parent.payload, minOf(parent.end, bytes.size.toLong()).toInt())
        .takeWhile { it.end <= parent.end }
    val movie = mp4Boxes(bytes).firstOrNull { it.type == "moov" } ?: return null
    return children(movie).filter { it.type == "trak" }.mapNotNull { track ->
        val boxes = children(track).associateBy { it.type }
        val media = boxes["mdia"] ?: return@mapNotNull null
        val handler = children(media).firstOrNull { it.type == "hdlr" } ?: return@mapNotNull null
        if (handler.end - handler.payload < 12 || bytes.size - handler.payload < 12 ||
            fourCc(bytes, handler.payload + 8) != "vide") return@mapNotNull null
        val header = boxes["tkhd"] ?: return@mapNotNull null
        if (header.end > bytes.size || header.end - header.payload < 80) return@mapNotNull null
        (bytes.int32(header.end.toInt() - 4) ushr 16).takeIf { it in 144..8640 }
    }.maxOrNull()
}
