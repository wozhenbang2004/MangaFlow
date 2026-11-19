package dev.mangaflow.anime

import dev.mangaflow.anime.sources.animePublicUrl
import dev.mangaflow.net.NetworkClient
import dev.mangaflow.net.Request
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.platform.wallTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okio.Buffer

/** One bounded HTTP range read, not a playable-frame claim or a sustained throughput benchmark. */
internal data class AnimeMediaPreflight(
    val observedAt: Long,
    val httpStatus: Int? = null,
    val verified: Boolean? = null,
    val failure: String? = null,
    val quality: String? = null,
    val sampleBytesPerSecond: Long? = null,
    val elapsedMs: Long? = null,
)

internal suspend fun inspectAnimeMediaBeforePlayback(asset: AnimePlaybackAsset): AnimeMediaPreflight {
    val now = wallTimeMillis()
    val url = runCatching { animePublicUrl(asset.url) }.getOrNull()
        ?: return AnimeMediaPreflight(now, failure = "媒体地址不符合公开 HTTPS 安全规则")
    val builder = Request.Builder().url(url).get()
        .header("Range", "bytes=0-65535").header("Accept-Encoding", "identity")
    asset.headers.forEach { (name, value) ->
        if (name.lowercase() !in setOf("range", "host", "content-length", "accept-encoding", "connection"))
            builder.header(name, value)
    }
    val client = NetworkClient(route = asset.route, connectTimeoutMillis = 2_500,
        readTimeoutMillis = 3_000, callTimeoutMillis = 3_500)
    val start = monotonicTimeMillis()
    val body = Buffer()
    try {
        var accepted = false
        val response = withTimeoutOrNull(3_500L) {
            client.downloadWithHeaders(builder.build(), maximumBytes = 65_536L,
                onResponse = { header ->
                    val length = header.header("Content-Length")?.toLongOrNull()
                    accepted = when (header.code) {
                        206 -> header.header("Content-Range")?.startsWith("bytes 0-", ignoreCase = true) == true &&
                            (length == null || length in 1L..65_536L)
                        200 -> length != null && length in 1L..65_536L
                        else -> false
                    }
                    accepted
                }, onChunk = { bytes, count -> body.write(bytes, 0, count) })
        } ?: return AnimeMediaPreflight(wallTimeMillis())
        val elapsed = (monotonicTimeMillis() - start).coerceAtLeast(1L)
        val failure = when (response.code) {
            401, 403 -> "HTTP ${response.code} · 当前媒体地址被拒绝"
            404, 410 -> "HTTP ${response.code} · 当前媒体地址已失效"
            429 -> "HTTP 429 · 来源暂时限流"
            else -> null
        }
        if (failure != null) return AnimeMediaPreflight(wallTimeMillis(), response.code,
            verified = false, failure = failure, elapsedMs = elapsed)
        if (!accepted) return AnimeMediaPreflight(wallTimeMillis(), response.code,
            verified = null, elapsedMs = elapsed)
        val bytes = body.readByteArray()
        val text = bytes.decodeToString().trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        val hls = text.startsWith("#EXTM3U") && text.contains("#EXT-X-")
        val dash = text.startsWith("<MPD") || (text.startsWith("<?xml") && text.contains("<MPD"))
        val mp4 = bytes.size >= 12 && bytes.copyOfRange(4, 8).decodeToString() == "ftyp"
        val matroska = bytes.size >= 4 && bytes[0] == 0x1a.toByte() &&
            bytes[1] == 0x45.toByte() && bytes[2] == 0xdf.toByte() && bytes[3] == 0xa3.toByte()
        val ts = bytes.size >= 188 * 5 && (0 until 5).all { bytes[it * 188] == 0x47.toByte() }
        val flv = bytes.size >= 9 && bytes.copyOfRange(0, 3).decodeToString() == "FLV"
        val recognized = hls || dash || mp4 || matroska || ts || flv
        val htmlOrJson = text.startsWith("<html", true) || text.startsWith("<!doctype html", true) ||
            text.startsWith("{") || text.startsWith("[")
        val heights = if (hls) Regex("""(?i)RESOLUTION=\d+x(\d+)""").findAll(text)
            .mapNotNull { it.groupValues[1].toIntOrNull()?.takeIf { n -> n in 144..8640 } }.toList() else emptyList()
        val quality = heights.maxOrNull()?.let { "${it}p" }
        val sampleRate = bytes.size.takeIf { recognized && it >= 16_384 && elapsed >= 150L }
            ?.let { it.toLong() * 1_000L / elapsed }
        return AnimeMediaPreflight(wallTimeMillis(), response.code, verified = recognized,
            failure = if (!recognized && htmlOrJson) "媒体请求返回网页或接口内容，未提供视频" else null,
            quality = quality, sampleBytesPerSecond = sampleRate, elapsedMs = elapsed)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        // An uninspectable range is inconclusive; the player may have a different media path.
        return AnimeMediaPreflight(wallTimeMillis())
    } finally {
        client.close()
    }
}
