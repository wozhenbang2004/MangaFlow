package dev.mangaflow.storage

import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull
import dev.mangaflow.net.NetworkClient
import dev.mangaflow.net.NetworkResponse
import dev.mangaflow.net.Request
import dev.mangaflow.platform.sha256
import dev.mangaflow.platform.storage.StorageAtomicFile
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.StorageOutput
import dev.mangaflow.platform.storage.syncStorageFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.IOException

/** Exact request identity survives pause/retry generations; generation still guards publication. */
internal fun downloadPagePartial(destination: StorageFile, url: String, headers: Map<String, String>): StorageFile {
    val identity = buildString {
        append(url.length).append(':').append(url)
        pageHeaders(headers).entries.sortedBy { it.key.lowercase() }.forEach { (name, value) ->
            append(name.length).append(':').append(name.lowercase())
            append(value.length).append(':').append(value)
        }
    }
    val digest = sha256(identity.encodeToByteArray()).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    return StorageFile(destination.parentFile, destination.name + ".resume-" + digest + ".part")
}

internal fun discardDownloadPartial(partial: StorageFile) {
    val metadata = resumeFile(partial)
    for (file in listOf(partial, metadata, StorageFile(metadata.path + ".new"), StorageFile(metadata.path + ".bak"))) {
        if (file.exists() && !file.delete()) throw DownloadStorageException("无法清理未完成的图片，请检查存储空间")
    }
}

/** Stream exact unencoded bytes; append only after a matching strong validator and range. */
internal suspend fun downloadPageFile(
    url: String, headers: Map<String, String>, partial: StorageFile, client: NetworkClient? = null,
): Pair<Int, Int> {
    var target = url.toHttpUrlOrNull()?.takeIf { it.isHttps }
        ?: throw PermanentDownloadException("图片地址不是 HTTPS，已停止下载")
    var resume = readResume(partial, url)
    if (resume == null) discardDownloadPartial(partial)
    var redirects = 0
    var restarted = false
    while (true) {
        currentCoroutineContext().ensureActive()
        val saved = resume?.takeIf { it.target == target.toString() }
        val offset = if (saved != null) partial.length() else 0L
        var writeOffset = offset
        var received = 0L
        var expectedBytes: Long? = null
        var expectedTotal: Long? = null
        var output: StorageOutput? = null
        val request = Request.Builder().url(target).apply {
            pageHeaders(headers).forEach { (name, value) -> header(name, value) }
            header("Accept-Encoding", "identity")
            if (saved != null && offset > 0L) {
                header("Range", "bytes=" + offset + "-")
                header("If-Range", saved.etag)
            }
        }.build()
        val response = try {
            try {
                (client ?: pageClient).downloadWithHeaders(request, MAX_PAGE_BYTES, onResponse = { response ->
                    if (response.code != 200 && response.code != 206) false
                    else {
                        val encoding = response.header("Content-Encoding")?.trim()
                        if (!encoding.isNullOrEmpty() && !encoding.equals("identity", true)) {
                            throw IOException("来源未提供可安全保存的原图编码")
                        }
                        val lengthHeader = response.header("Content-Length")
                        val length = lengthHeader?.toLongOrNull()
                        if (lengthHeader != null && (length == null || length < 0L)) {
                            throw InvalidRangeResponse("图片长度响应无效")
                        }
                        if (response.code == 206) {
                            val previous = saved ?: throw InvalidRangeResponse("来源返回了无对应断点的图片")
                            val range = parseRange(response.header("Content-Range"))
                                ?: throw InvalidRangeResponse("图片续传范围无效")
                            if (offset <= 0L || range.first != offset ||
                                previous.total != null && previous.total != range.total ||
                                length != null && length != range.last - range.first + 1L ||
                                response.header("ETag")?.trim()?.let { it != previous.etag } == true) {
                                throw InvalidRangeResponse("图片版本或续传范围已改变")
                            }
                            expectedBytes = range.last - range.first + 1L
                            expectedTotal = range.total
                            resume = previous.copy(total = range.total)
                            saveResume(partial, url, requireNotNull(resume))
                            output = writeToDisk { partial.outputStream(append = true) }
                        } else {
                            if (length != null && length > MAX_PAGE_BYTES) {
                                throw PermanentDownloadException("图片超过本地保存大小上限")
                            }
                            if (response.header("Content-Range") != null) {
                                throw InvalidRangeResponse("图片完整响应包含异常范围")
                            }
                            discardDownloadPartial(partial)
                            writeOffset = 0L
                            expectedBytes = length
                            expectedTotal = length
                            output = writeToDisk { partial.outputStream() }
                            resume = response.header("ETag")?.trim()?.takeIf(::strongEtag)?.let {
                                ResumeState(target.toString(), it, length)
                            }
                            resume?.let { saveResume(partial, url, it) }
                        }
                        true
                    }
                }, onChunk = { bytes, count ->
                    if (writeOffset + received + count > MAX_PAGE_BYTES ||
                        expectedBytes?.let { received + count > it } == true) {
                        throw InvalidRangeResponse("图片实际长度超出响应范围")
                    }
                    writeToDisk { requireNotNull(output).write(bytes, 0, count) }
                    received += count
                })
            } finally {
                output?.let { stream -> writeToDisk { stream.close(); syncStorageFile(partial) } }
            }
        } catch (invalid: InvalidRangeResponse) {
            discardDownloadPartial(partial)
            resume = null
            if (restarted) throw IOException(invalid.message ?: "图片续传失败，请重试", invalid)
            restarted = true
            continue
        }
        currentCoroutineContext().ensureActive()
        if (response.code in REDIRECT_CODES) {
            val next = response.header("Location")?.let(target::resolve)?.takeIf { it.isHttps }
            if (next == null || ++redirects > 4) {
                throw PermanentDownloadException("图片地址跳转无效，请刷新并重试", true)
            }
            target = next
            continue
        }
        if (response.code == 416 && saved != null && !restarted) {
            discardDownloadPartial(partial)
            resume = null
            restarted = true
            continue
        }
        if (response.code != 200 && response.code != 206) throw responseFailure(response.code)
        if (expectedBytes?.let { received != it } == true ||
            expectedTotal?.let { partial.length() != it } == true) {
            throw IOException("图片尚未传输完整，稍后继续下载")
        }
        val bounds = storageImageBounds(partial)
        if (bounds == null) {
            discardDownloadPartial(partial)
            throw IOException("来源返回的内容不是可读取的图片")
        }
        return bounds
    }
}

private data class ResumeState(val target: String, val etag: String, val total: Long?)
private data class ByteRange(val first: Long, val last: Long, val total: Long)
private class InvalidRangeResponse(message: String) : IOException(message)

private fun parseRange(value: String?): ByteRange? {
    val match = value?.trim()?.let { RANGE.matchEntire(it) } ?: return null
    val first = match.groupValues[1].toLongOrNull() ?: return null
    val last = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].toLongOrNull() ?: return null
    return ByteRange(first, last, total).takeIf { first >= 0L && last >= first && last < total && total <= MAX_PAGE_BYTES }
}

private fun strongEtag(value: String): Boolean = value.length in 2..4096 &&
    value.first() == '"' && value.last() == '"' &&
    value.substring(1, value.lastIndex).none { it == '"' || it == '\r' || it == '\n' }

private fun readResume(partial: StorageFile, url: String): ResumeState? = runCatching {
    val metadata = resumeFile(partial)
    if (!partial.isFile || partial.length() !in 1L..MAX_PAGE_BYTES) return@runCatching null
    if (!metadata.isFile || metadata.length() !in 1L..65536L) return@runCatching null
    val json = JSONObject(StorageAtomicFile(metadata).openRead().bufferedReader().use { it.readText() })
    if (json.optInt("schema") != 1 || json.optString("url") != url ||
        json.optString("partial") != partial.name) return@runCatching null
    val target = json.optString("target").toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return@runCatching null
    val etag = json.optString("etag").takeIf(::strongEtag) ?: return@runCatching null
    val total = json.optLong("total", -1L).takeIf { it >= 0L }
    if (total != null && (total > MAX_PAGE_BYTES || partial.length() > total)) return@runCatching null
    ResumeState(target.toString(), etag, total)
}.getOrNull()

private fun saveResume(partial: StorageFile, url: String, state: ResumeState) = writeToDisk {
    val atomic = StorageAtomicFile(resumeFile(partial))
    val stream = atomic.startWrite()
    try {
        stream.write(JSONObject().apply {
            put("schema", 1); put("url", url); put("partial", partial.name)
            put("target", state.target); put("etag", state.etag); put("total", state.total ?: -1L)
        }.toString().encodeToByteArray())
        atomic.finishWrite(stream)
    } catch (failure: Throwable) {
        atomic.failWrite(stream)
        throw failure
    }
}

private fun resumeFile(partial: StorageFile) = StorageFile(partial.path + ".json")
private fun pageHeaders(headers: Map<String, String>) = headers.filterKeys {
    it.equals("Referer", true) || it.equals("User-Agent", true) || it.equals("Accept", true)
}
private fun responseFailure(code: Int): IOException = when (code) {
    401, 403, 404 -> PermanentDownloadException("图片地址已失效（" + code + "），请刷新并重试", true)
    429 -> IOException("来源暂时限流，请稍后重试")
    else -> IOException("图片下载失败（" + code + "）")
}

internal class PermanentDownloadException(message: String, val needsSourceRefresh: Boolean = false) : IOException(message)
internal class DownloadStorageException(message: String, cause: Throwable? = null) : IOException(message, cause)
private inline fun <T> writeToDisk(action: () -> T): T = try { action() }
catch (failure: IOException) { throw DownloadStorageException("无法保存本地图片，请检查可用空间", failure) }
private const val MAX_PAGE_BYTES = 96L * 1024L * 1024L
private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
private val RANGE = Regex("^bytes ([0-9]+)-([0-9]+)/([0-9]+)$", RegexOption.IGNORE_CASE)
private val pageClient = NetworkClient(connectTimeoutMillis = 20_000, readTimeoutMillis = 45_000,
    callTimeoutMillis = 90_000, acceptCompression = false)
