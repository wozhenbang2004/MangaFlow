package dev.mangaflow.storage

import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Writes only a private temporary file; DownloadStore owns validation and publication of its generation. */
internal suspend fun downloadPageFile(url: String, headers: Map<String, String>, partial: File): Pair<Int, Int> = coroutineScope {
    if (!url.startsWith("https://")) throw PermanentDownloadException("图片地址不是 HTTPS，已停止下载")
    val request = Request.Builder().url(url).apply {
        headers.filterKeys { it.equals("Referer", true) || it.equals("User-Agent", true) || it.equals("Accept", true) }
            .forEach { (name, value) -> header(name, value) }
    }.build()
    val call = pageClient.newCall(request)
    val transfer = async(Dispatchers.IO) {
        call.execute().use { response ->
            if (!response.isSuccessful) {
                val message = when (response.code) {
                    401, 403, 404 -> "图片地址已失效（${response.code}），请刷新并重试"
                    429 -> "来源暂时限流，请稍后重试"
                    else -> "图片下载失败（${response.code}）"
                }
                if (response.code in listOf(401, 403, 404)) throw PermanentDownloadException(message, needsSourceRefresh = true)
                throw IOException(message)
            }
            val body = response.body ?: throw IOException("图片响应为空")
            if (body.contentLength() > MAX_PAGE_BYTES) throw PermanentDownloadException("单页超过下载大小上限")
            body.byteStream().use { input ->
                writeToDisk { partial.outputStream() }.use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count == -1) break
                        total += count
                        if (total > MAX_PAGE_BYTES) throw PermanentDownloadException("单页超过下载大小上限")
                        writeToDisk { output.write(buffer, 0, count) }
                    }
                    writeToDisk { output.fd.sync() }
                }
            }
            currentCoroutineContext().ensureActive()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(partial.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("来源返回的内容不是可读取的图片")
            bounds.outWidth to bounds.outHeight
        }
    }
    try {
        transfer.await()
    } finally {
        call.cancel()
        // Cancellation waits for the response and stream to close before deletion or refresh proceeds.
        withContext(NonCancellable) { transfer.join() }
    }
}

internal class PermanentDownloadException(message: String, val needsSourceRefresh: Boolean = false) : IOException(message)
internal class DownloadStorageException(message: String, cause: Throwable? = null) : IOException(message, cause)

private inline fun <T> writeToDisk(action: () -> T): T = try { action() }
catch (failure: IOException) { throw DownloadStorageException("无法保存本地图片，请检查可用空间", failure) }

private const val MAX_PAGE_BYTES = 96L * 1024L * 1024L
private val pageClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(45, TimeUnit.SECONDS)
    .callTimeout(90, TimeUnit.SECONDS)
    .followSslRedirects(false)
    .build()
