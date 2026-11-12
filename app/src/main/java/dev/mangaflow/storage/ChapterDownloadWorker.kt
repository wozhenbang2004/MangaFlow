package dev.mangaflow.storage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** A chapter is complete only after every image has been saved and decoded for its bounds. */
class ChapterDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(KEY_CHAPTER) ?: return@withContext Result.failure()
        val store = DownloadStore.get(applicationContext)
        val initial = store.record(id) ?: return@withContext Result.success()
        val workerId = this@ChapterDownloadWorker.id.toString()
        if (initial.workId != workerId) return@withContext Result.success()
        if (initial.entry.status == DownloadStatus.PAUSED || initial.entry.status == DownloadStatus.COMPLETED) return@withContext Result.success()

        try {
            DownloadStore.transferGate.withLock {
                currentCoroutineContext().ensureActive()
                val record = store.record(id) ?: return@withLock
                if (record.workId != workerId || record.entry.status == DownloadStatus.PAUSED) return@withLock
                setForeground(foreground(record.entry))
                store.directory(id).mkdirs()
                store.update(id, workerId) { it.copy(status = DownloadStatus.RUNNING, error = "") }
                var completed = 0
                var bytes = 0L
                for ((index, page) in record.pages.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val current = store.record(id) ?: return@withLock
                    if (current.workId != workerId || current.entry.status == DownloadStatus.PAUSED) return@withLock
                    val destination = store.pageFile(id, index)
                    if (!isImage(destination)) download(page.url, record.headers, destination)
                    completed++
                    bytes += destination.length()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(destination.absolutePath, bounds)
                    store.finishPage(id, workerId, index, bounds.outWidth, bounds.outHeight, completed, bytes)
                    store.record(id)?.entry?.let { setForeground(foreground(it)) }
                }
                currentCoroutineContext().ensureActive()
                store.update(id, workerId) { it.copy(status = DownloadStatus.COMPLETED, completedPages = record.pages.size, error = "") }
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            runCatching { store.update(id, workerId) { it.copy(status = DownloadStatus.WAITING, error = "等待系统恢复下载") } }
            throw cancelled
        } catch (error: Exception) {
            val retryable = error is IOException && error !is PermanentDownloadException && runAttemptCount < 3
            runCatching { store.update(id, workerId) { it.copy(status = if (retryable) DownloadStatus.WAITING else DownloadStatus.FAILED, error = error.message ?: "下载未完成") } }
            if (retryable) Result.retry() else Result.failure()
        }
    }

    private suspend fun download(url: String, headers: Map<String, String>, destination: File) {
        if (!url.startsWith("https://")) throw PermanentDownloadException("图片地址不是 HTTPS，已停止下载")
        val request = Request.Builder().url(url).apply {
            headers.filterKeys { it.equals("Referer", true) || it.equals("User-Agent", true) || it.equals("Accept", true) }
                .forEach { (name, value) -> header(name, value) }
        }.build()
        val partial = File(destination.parentFile, "${destination.name}.part")
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val message = when (response.code) {
                        401, 403, 404 -> "图片地址已失效（${response.code}），请重新打开章节后下载"
                        429 -> "来源暂时限流，请稍后重试"
                        else -> "图片下载失败（${response.code}）"
                    }
                    if (response.code in listOf(401, 403, 404)) throw PermanentDownloadException(message)
                    throw IOException(message)
                }
                val body = response.body ?: throw IOException("图片响应为空")
                if (body.contentLength() > MAX_PAGE_BYTES) throw PermanentDownloadException("单页超过下载大小上限")
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            total += count
                            if (total > MAX_PAGE_BYTES) throw PermanentDownloadException("单页超过下载大小上限")
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                currentCoroutineContext().ensureActive()
                if (!isImage(partial)) throw IOException("来源返回的内容不是可读取的图片")
                if (destination.exists() && !destination.delete()) throw IOException("无法替换本地图片")
                if (!partial.renameTo(destination)) throw IOException("无法保存本地图片")
            }
        } finally { partial.delete() }
    }

    private fun foreground(entry: DownloadEntry): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "漫画下载", NotificationManager.IMPORTANCE_LOW))
        val launch = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(entry.manga.title)
            .setContentText("${entry.chapter.title} · ${entry.completedPages}/${entry.totalPages} 页")
            .setProgress(entry.totalPages, entry.completedPages, entry.totalPages == 0)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply {
                launch?.let { setContentIntent(PendingIntent.getActivity(applicationContext, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) }
            }
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(entry.id.hashCode().and(Int.MAX_VALUE), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(entry.id.hashCode().and(Int.MAX_VALUE), notification)
    }

    private fun isImage(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    companion object {
        const val KEY_CHAPTER = "chapter_download_id"
        private const val CHANNEL_ID = "mangaflow-downloads"
        private const val MAX_PAGE_BYTES = 96L * 1024L * 1024L
        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .build()
    }
}

private class PermanentDownloadException(message: String) : IOException(message)
