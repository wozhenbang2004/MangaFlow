@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.mangaflow.anime.player

import androidx.media3.common.C
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal data class AnimeRemoteMediaInspection(
    val mediaType: Int? = null,
    val failure: String? = null,
    val mayRefreshSource: Boolean = true,
)

/** Called only after a startup parser error, through the exact player's routed client. */
internal suspend fun inspectAnimeRemoteMedia(client: OkHttpClient, url: String): AnimeRemoteMediaInspection? =
    withTimeoutOrNull(4_500L) {
        suspendCancellableCoroutine<AnimeRemoteMediaInspection?> { continuation ->
            val call = client.newCall(Request.Builder().url(url).build())
            call.timeout().timeout(4L, TimeUnit.SECONDS)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val observation = try { response.use(::inspectResponse) } catch (_: IOException) { null }
                    if (continuation.isActive) continuation.resume(observation)
                }
            })
        }
    }

private const val ROOT_SAMPLE_BYTES = 4_096L

private fun inspectResponse(response: Response): AnimeRemoteMediaInspection? {
    if (response.code !in setOf(200, 206)) return AnimeRemoteMediaInspection(
        failure = when (response.code) {
            401, 403 -> "来源拒绝了这条播放地址（HTTP ${response.code}）。"
            404, 410 -> "来源上的视频地址已不存在。"
            else -> "重新读取播放地址时，来源返回 HTTP ${response.code}。"
        },
        mayRefreshSource = response.code in 500..599 && response.header("Retry-After") == null,
    )
    if (response.code == 206 && response.header("Content-Range")?.startsWith("bytes 0-", ignoreCase = true) != true) return null
    if (response.header("Content-Encoding")?.equals("identity", ignoreCase = true) == false) return null
    val bytes = response.peekBody(ROOT_SAMPLE_BYTES).bytes()
    val text = bytes.decodeToString().trimStart('\uFEFF', ' ', '\r', '\n', '\t')
    if (text.lineSequence().firstOrNull()?.trimEnd() == "#EXTM3U" && text.lineSequence().any { it.startsWith("#EXT-X-") }) {
        return AnimeRemoteMediaInspection(mediaType = C.CONTENT_TYPE_HLS)
    }
    val xml = text.removePrefix("<?xml").let { if (text.startsWith("<?xml")) it.substringAfter("?>").trimStart() else it }
    if (xml.startsWith("<MPD ") || xml.startsWith("<MPD\n") || xml.startsWith("<MPD>")) {
        return AnimeRemoteMediaInspection(mediaType = C.CONTENT_TYPE_DASH)
    }
    if (looksLikeVideoContainer(bytes)) return AnimeRemoteMediaInspection(mediaType = C.CONTENT_TYPE_OTHER)
    val responseType = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
    if (text.startsWith("<!doctype html", ignoreCase = true) || text.startsWith("<html", ignoreCase = true) ||
        ((responseType == "text/html" || responseType == "application/xhtml+xml") && text.startsWith('<')) ||
        ((responseType == "application/json" || responseType.endsWith("+json")) &&
            (text.startsWith('{') || text.startsWith('[')))) {
        return AnimeRemoteMediaInspection(failure = "来源返回了网页或接口提示，该地址没有给出可播放的媒体。",
            mayRefreshSource = false)
    }
    return null
}

private fun looksLikeVideoContainer(bytes: ByteArray): Boolean {
    if (bytes.size >= 12 && bytes.copyOfRange(4, 8).decodeToString() == "ftyp" &&
        bytes.copyOfRange(8, 12).decodeToString() in setOf("isom", "iso2", "iso5", "iso6", "avc1", "mp41", "mp42", "qt  ", "dash")) return true
    if (bytes.size >= 4 && bytes[0].toInt() == 0x1a && bytes[1].toInt() == 0x45 &&
        (bytes[2].toInt() and 0xff) == 0xdf && (bytes[3].toInt() and 0xff) == 0xa3) return true
    if (bytes.size >= 9 && bytes[0] == 'F'.code.toByte() && bytes[1] == 'L'.code.toByte() && bytes[2] == 'V'.code.toByte()) return true
    if (bytes.size < 188 * 5) return false
    return (0 until 5).all { packet ->
        val offset = packet * 188
        bytes[offset] == 0x47.toByte() && (bytes[offset + 1].toInt() and 0x80) == 0 &&
            (bytes[offset + 3].toInt() and 0x30) != 0
    }
}
