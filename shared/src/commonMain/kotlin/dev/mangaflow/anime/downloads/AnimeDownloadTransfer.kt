package dev.mangaflow.anime.downloads

import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.anime.player.playbackIdentity
import dev.mangaflow.anime.sources.animeMediaUrl
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.NetworkClient
import dev.mangaflow.net.Request
import dev.mangaflow.platform.sha256
import dev.mangaflow.platform.storage.StorageAtomicFile
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.StorageOutput
import dev.mangaflow.platform.storage.syncStorageFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.IOException
import okio.Buffer

internal data class AnimeResourcePlan(
    val name: String,
    val url: String,
    val mimeType: String? = null,
    val audioRendition: Boolean = false,
)
internal data class AnimeSavedResource(val name: String, val mimeType: String, val bytes: Long)
internal class AnimeHlsDetected : IOException("该线路返回 HLS 清单")
private class InvalidAnimeRange(message: String) : IOException(message)
private data class Resume(val identity: String, val target: String, val etag: String, val total: Long?)
private data class Range(val start: Long, val end: Long, val total: Long)

internal fun animeDigest(value: String): String = sha256(value.encodeToByteArray())
    .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

internal fun checkedAnimeDownloadUrl(value: String): HttpUrl = animeMediaUrl(value)

private fun checkedDownloadRedirect(origin: HttpUrl, location: String?): HttpUrl {
    val target = location?.let(origin::resolve)?.let { checkedAnimeDownloadUrl(it.toString()) }
        ?: error("媒体地址跳转无效，请重新解析线路")
    require(!origin.isHttps || target.isHttps) { "媒体地址不允许从 HTTPS 跳转到 HTTP，请重新解析线路" }
    return target
}

internal fun downloadHeaders(asset: AnimePlaybackAsset, target: HttpUrl): Map<String, String> {
    val origin = checkedAnimeDownloadUrl(asset.url)
    val sameOrigin = origin.scheme == target.scheme && origin.host == target.host && origin.port == target.port
    val managed = setOf("host", "connection", "content-length", "transfer-encoding", "proxy-authorization",
        "proxy-connection", "upgrade", "range", "if-range", "accept-encoding")
    val publicCrossOrigin = setOf("user-agent", "referer", "origin", "accept", "accept-language")
    return asset.headers.filterKeys { name ->
        name.lowercase() !in managed && (sameOrigin || name.lowercase() in publicCrossOrigin)
    }
}

internal fun requestIdentity(asset: AnimePlaybackAsset, url: String): String = animeDigest(buildString {
    append(asset.playbackIdentity()).append('\n').append(url).append('\n')
    asset.headers.entries.sortedBy { it.key.lowercase() }.forEach { (key, value) ->
        append(key.length).append(':').append(key.lowercase()).append(value.length).append(':').append(value)
    }
})

internal fun writeAnimeJson(file: StorageFile, value: JSONObject) {
    val atomic = StorageAtomicFile(file)
    val stream = atomic.startWrite()
    try { stream.write(value.toString().encodeToByteArray()); atomic.finishWrite(stream) }
    catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
}

/** No request URL or request header enters the resume record; appending requires a strong validator. */
internal suspend fun downloadAnimeResource(
    asset: AnimePlaybackAsset, plan: AnimeResourcePlan, directory: StorageFile, client: NetworkClient,
    guard: () -> Unit, onProgress: (Long, Long?) -> Unit, validateVideo: Boolean = false,
): AnimeSavedResource {
    val initialTarget = checkedAnimeDownloadUrl(plan.url)
    val identity = requestIdentity(asset, plan.url)
    val complete = StorageFile(directory, plan.name)
    val receipt = StorageFile(directory, plan.name + ".done.json")
    val partial = StorageFile(directory, plan.name + ".part")
    val resumeFile = StorageFile(directory, plan.name + ".resume.json")
    fun discard() {
        guard()
        for (file in listOf(partial, resumeFile, complete, receipt)) {
            check(!file.exists() || file.delete()) { "无法清理旧的离线断点，请检查存储空间" }
        }
    }
    val savedComplete = runCatching {
        require(receipt.length() in 1L..65_536L)
        val json = JSONObject(receipt.readText())
        val size = json.getLong("bytes")
        if (json.getString("identity") == identity && complete.isFile && size > 0 && complete.length() == size)
            AnimeSavedResource(plan.name, json.getString("mime"), size) else null
    }.getOrNull()
    if (savedComplete != null) { guard(); onProgress(savedComplete.bytes, savedComplete.bytes); return savedComplete }
    var resume = runCatching {
        require(resumeFile.length() in 1L..65_536L)
        val json = JSONObject(resumeFile.readText())
        val value = Resume(json.getString("identity"), json.getString("target"), json.getString("etag"),
            json.optLong("total", -1L).takeIf { it >= 0 })
        value.takeIf { it.identity == identity && strongEtag(it.etag) && partial.isFile && partial.length() > 0 &&
            (it.total == null || partial.length() <= it.total) }
    }.getOrNull()
    if (resume == null) discard()
    var target = initialTarget
    var redirects = 0
    var restarted = false
    while (true) {
        currentCoroutineContext().ensureActive(); guard()
        val saved = resume?.takeIf { it.target == animeDigest(target.toString()) }
        val offset = if (saved != null) partial.length() else 0L
        var received = 0L
        var retained = offset
        var expectedBytes: Long? = null
        var total: Long? = saved?.total
        var output: StorageOutput? = null
        val prefix = Buffer()
        var checkedPrefix = offset > 0
        val request = Request.Builder().url(target).apply {
            downloadHeaders(asset, target).forEach { (name, value) -> header(name, value) }
            header("Accept-Encoding", "identity")
            if (offset > 0 && saved != null) { header("Range", "bytes=$offset-"); header("If-Range", saved.etag) }
        }.build()
        onProgress(offset, total)
        val response = try {
            try {
                client.downloadWithHeaders(request, Long.MAX_VALUE, onResponse = { response ->
                    if (response.code != 200 && response.code != 206) false else {
                        guard()
                        val encoding = response.header("Content-Encoding")
                        require(encoding.isNullOrBlank() || encoding.equals("identity", true)) {
                            "来源未提供可安全续传的原始媒体数据"
                        }
                        val contentType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                        if (contentType?.contains("mpegurl") == true) {
                            if (validateVideo) throw AnimeHlsDetected()
                            else error("HLS 分段又引用了远程清单，当前离线保存不支持这种嵌套资源")
                        }
                        require(contentType !in setOf("text/html", "application/xhtml+xml", "application/dash+xml")) {
                            "来源返回了网页或不支持离线保存的 DASH 清单，请更换线路"
                        }
                        val rawLength = response.header("Content-Length")
                        val length = rawLength?.toLongOrNull()
                        if (rawLength != null && (length == null || length < 0)) throw InvalidAnimeRange("媒体长度无效")
                        if (response.code == 206) {
                            val previous = saved ?: throw InvalidAnimeRange("来源返回了没有对应断点的媒体片段")
                            val range = parseRange(response.header("Content-Range"))
                                ?: throw InvalidAnimeRange("媒体续传范围无效")
                            if (offset <= 0 || range.start != offset ||
                                previous.total?.let { it != range.total } == true ||
                                length?.let { it != range.end - range.start + 1 } == true ||
                                response.header("ETag")?.trim() != previous.etag) {
                                throw InvalidAnimeRange("媒体版本或续传范围已改变")
                            }
                            expectedBytes = range.end - range.start + 1
                            total = range.total
                            resume = previous.copy(total = total)
                            output = partial.outputStream(append = true)
                        } else {
                            if (response.header("Content-Range") != null) throw InvalidAnimeRange("完整媒体响应包含异常范围")
                            retained = 0
                            checkedPrefix = false
                            expectedBytes = length
                            total = length
                            check(!resumeFile.exists() || resumeFile.delete()) { "无法更新离线断点" }
                            output = partial.outputStream()
                            resume = response.header("ETag")?.trim()?.takeIf(::strongEtag)?.let {
                                Resume(identity, animeDigest(target.toString()), it, total)
                            }
                        }
                        val space = directory.usableSpace
                        check(space <= 0 || total == null || total!! - retained <= space) { "剩余空间不足以保存这集，请清理后继续" }
                        resume?.let { value -> writeAnimeJson(resumeFile, JSONObject().put("identity", value.identity)
                            .put("target", value.target).put("etag", value.etag).put("total", value.total ?: -1L)) }
                        onProgress(retained, total)
                        true
                    }
                }, onChunk = { bytes, count ->
                    guard()
                    if (received > Long.MAX_VALUE - count || expectedBytes?.let { received + count > it } == true)
                        throw InvalidAnimeRange("媒体数据超出了声明的响应长度")
                    if (!checkedPrefix) {
                        prefix.write(bytes, 0, count)
                        if (prefix.size >= 4096) {
                            val buffered = prefix.readByteArray()
                            rejectRemotePlaylist(buffered, validateVideo)
                            sniffAnimeBytes(buffered, fragment = !validateVideo)
                            requireNotNull(output).write(buffered)
                            checkedPrefix = true
                        }
                    } else requireNotNull(output).write(bytes, 0, count)
                    received += count
                    onProgress(retained + received, total)
                }).also {
                    if (prefix.size > 0 && output != null) {
                        val buffered = prefix.readByteArray()
                        rejectRemotePlaylist(buffered, validateVideo)
                        sniffAnimeBytes(buffered, fragment = !validateVideo)
                        requireNotNull(output).write(buffered)
                    }
                }
            } finally {
                output?.close()
                if (output != null && partial.isFile) syncStorageFile(partial)
            }
        } catch (hls: AnimeHlsDetected) {
            discard()
            throw hls
        } catch (invalid: InvalidAnimeRange) {
            discard(); resume = null
            if (restarted) throw invalid
            restarted = true
            continue
        }
        currentCoroutineContext().ensureActive(); guard()
        if (response.code in setOf(301, 302, 303, 307, 308)) {
            require(++redirects <= 8) { "媒体地址跳转次数过多，请重新解析线路" }
            target = checkedDownloadRedirect(target, response.header("Location"))
            continue
        }
        if (response.code == 416 && !restarted) { discard(); resume = null; restarted = true; continue }
        require(response.code == 200 || response.code == 206) { when (response.code) {
            401, 403 -> "来源授权或下载地址已失效（HTTP ${response.code}），请重新打开线路后下载"
            404, 410 -> "来源已移除这个媒体资源（HTTP ${response.code}），请更换线路"
            429 -> "来源暂时限制下载频率，请稍后手动继续"
            else -> "媒体下载失败（HTTP ${response.code}），可稍后继续"
        } }
        check(expectedBytes == null || expectedBytes == received) { "媒体尚未传输完整，可继续下载；来源不支持续传时会重新下载" }
        check(partial.length() > 0) { "来源返回了空媒体文件" }
        if (total != null && partial.length() < total!! && resume != null) continue
        check(total == null || partial.length() == total) { "媒体大小不匹配，请继续下载或重新解析同一来源" }
        val detectedMime = sniffAnimeVideo(partial, fragment = !validateVideo)
        val actualMime = if (plan.audioRendition && detectedMime == "video/mp4") "audio/mp4" else detectedMime
        guard()
        check(partial.renameTo(complete)) { "无法完成离线文件，请检查存储空间" }
        val result = AnimeSavedResource(plan.name, actualMime, complete.length())
        writeAnimeJson(receipt, JSONObject().put("identity", identity).put("bytes", result.bytes).put("mime", result.mimeType))
        resumeFile.delete()
        onProgress(result.bytes, result.bytes)
        return result
    }
}

internal suspend fun fetchAnimePlaylist(asset: AnimePlaybackAsset, client: NetworkClient,
    initialUrl: HttpUrl = checkedAnimeDownloadUrl(asset.url)): Pair<HttpUrl, String> {
    var target = initialUrl
    repeat(9) {
        currentCoroutineContext().ensureActive()
        val request = Request.Builder().url(target).apply {
            downloadHeaders(asset, target).forEach { (name, value) -> header(name, value) }
            header("Accept-Encoding", "identity")
        }.build()
        val response = client.execute(request, 2L * 1024 * 1024)
        if (response.code in setOf(301, 302, 303, 307, 308)) {
            target = checkedDownloadRedirect(target, response.header("Location"))
        } else {
            check(response.code == 200) { "HLS 清单无法读取（HTTP ${response.code}），请刷新线路" }
            return target to response.text()
        }
    }
    error("HLS 清单跳转次数过多")
}

private fun strongEtag(value: String): Boolean = value.length in 2..4096 && value.first() == '"' && value.last() == '"' &&
    value.substring(1, value.lastIndex).none { it == '"' || it == '\r' || it == '\n' }

private fun parseRange(value: String?): Range? {
    val match = value?.trim()?.let { Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(it) } ?: return null
    val first = match.groupValues[1].toLongOrNull() ?: return null
    val last = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].toLongOrNull() ?: return null
    return Range(first, last, total).takeIf { first >= 0 && last >= first && total > last }
}

private fun rejectRemotePlaylist(bytes: ByteArray, mayDetectHls: Boolean) {
    val text = bytes.decodeToString().trimStart('\uFEFF', ' ', '\r', '\n', '\t')
    if (text.startsWith("#EXTM3U")) {
        if (mayDetectHls) throw AnimeHlsDetected()
        error("HLS 分段引用了另一份清单，当前离线保存不支持")
    }
    require(!text.startsWith('<')) { "来源返回了网页或 XML，未保存为离线视频" }
}

internal fun sniffAnimeVideo(file: StorageFile, fragment: Boolean = false): String {
    val bytes = ByteArray(4096)
    val count = file.inputStream().use { it.read(bytes) }.coerceAtLeast(0)
    return sniffAnimeBytes(bytes.copyOf(count), fragment)
}

private fun sniffAnimeBytes(bytes: ByteArray, fragment: Boolean): String {
    val count = bytes.size
    val text = bytes.decodeToString().trimStart('\uFEFF', ' ', '\r', '\n', '\t')
    if (text.startsWith("#EXTM3U")) throw AnimeHlsDetected()
    fun ascii(offset: Int, value: String) = count >= offset + value.length &&
        value.indices.all { (bytes[offset + it].toInt() and 255) == value[it].code }
    return when {
        ascii(4, "ftyp") || ascii(4, "styp") || fragment && listOf("moof", "sidx", "emsg", "free").any { ascii(4, it) } -> "video/mp4"
        !fragment && count >= 4 && (bytes[0].toInt() and 255) == 0x1a && (bytes[1].toInt() and 255) == 0x45 &&
            (bytes[2].toInt() and 255) == 0xdf && (bytes[3].toInt() and 255) == 0xa3 -> "video/x-matroska"
        (0 until minOf(188, count)).any { start -> count > start + 376 &&
            bytes[start] == 0x47.toByte() && bytes[start + 188] == 0x47.toByte() && bytes[start + 376] == 0x47.toByte() } -> "video/mp2t"
        else -> error(if (fragment) "HLS 分段不是当前支持的 TS 或 fMP4 媒体，请更换线路"
            else "下载内容不是当前支持的 MP4、MKV、WebM 或 TS 视频，请更换线路")
    }
}
