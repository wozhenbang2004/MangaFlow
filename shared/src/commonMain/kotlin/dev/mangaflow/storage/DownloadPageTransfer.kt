package dev.mangaflow.storage

import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull
import dev.mangaflow.net.NetworkClient
import dev.mangaflow.net.Request
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.syncStorageFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.IOException

/** Every platform streams to a private generation-specific file before validation/publication. */
internal suspend fun downloadPageFile(url: String, headers: Map<String, String>, partial: StorageFile): Pair<Int, Int> {
    var target = url.toHttpUrlOrNull()?.takeIf { it.isHttps }
        ?: throw PermanentDownloadException("图片地址不是 HTTPS，已停止下载")
    repeat(5) { attempt ->
        val request = Request.Builder().url(target).apply {
            headers.filterKeys { it.equals("Referer", true) || it.equals("User-Agent", true) || it.equals("Accept", true) }
                .forEach { (name, value) -> header(name, value) }
        }.build()
        val response = writeToDisk { partial.outputStream() }.use { output ->
            pageClient.download(request, MAX_PAGE_BYTES, onChunk = { bytes, count -> writeToDisk { output.write(bytes, 0, count) } })
                .also { writeToDisk { output.flush() } }
        }
        currentCoroutineContext().ensureActive()
        if (response.code in setOf(301, 302, 303, 307, 308)) {
            val redirected = response.header("Location")?.let(target::resolve)?.takeIf { it.isHttps }
            if (redirected != null && attempt < 4) { target = redirected; return@repeat }
            throw PermanentDownloadException("图片地址跳转无效，请刷新并重试", true)
        }
        if (!response.isSuccessful) {
            val message = when (response.code) {
                401, 403, 404 -> "图片地址已失效（${response.code}），请刷新并重试"
                429 -> "来源暂时限流，请稍后重试"
                else -> "图片下载失败（${response.code}）"
            }
            if (response.code in setOf(401, 403, 404)) throw PermanentDownloadException(message, true)
            throw IOException(message)
        }
        writeToDisk { syncStorageFile(partial) }
        return storageImageBounds(partial) ?: throw IOException("来源返回的内容不是可读取的图片")
    }
    throw PermanentDownloadException("图片地址跳转过多，请刷新并重试", true)
}

internal class PermanentDownloadException(message: String, val needsSourceRefresh: Boolean = false) : IOException(message)
internal class DownloadStorageException(message: String, cause: Throwable? = null) : IOException(message, cause)
private inline fun <T> writeToDisk(action: () -> T): T = try { action() }
catch (failure: IOException) { throw DownloadStorageException("无法保存本地图片，请检查可用空间", failure) }
private const val MAX_PAGE_BYTES = 96L * 1024L * 1024L
private val pageClient = NetworkClient(connectTimeoutMillis = 20_000, readTimeoutMillis = 45_000, callTimeoutMillis = 90_000)
