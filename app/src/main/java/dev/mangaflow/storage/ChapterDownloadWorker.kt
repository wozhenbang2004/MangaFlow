package dev.mangaflow.storage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A chapter is complete only after every image has been saved and decoded for its bounds. */
class ChapterDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(KEY_CHAPTER) ?: return@withContext Result.failure()
        val store = DownloadStore.get(applicationContext)
        store.awaitReady()
        val initial = store.record(id) ?: return@withContext Result.success()
        val workerId = this@ChapterDownloadWorker.id.toString()
        if (initial.workId != workerId) return@withContext Result.success()
        if (initial.entry.status == DownloadStatus.PAUSED || initial.entry.status == DownloadStatus.COMPLETED) return@withContext Result.success()
        if (initial.entry.needsSourceRefresh) {
            runCatching { store.update(id, workerId) { it.copy(status = DownloadStatus.FAILED, error = "图片地址需要刷新，请刷新并重试") } }
            return@withContext Result.failure()
        }

        try {
            store.prepareChapter(id)
            DownloadStore.transferGate.withLock {
                currentCoroutineContext().ensureActive()
                val record = store.beginTransfer(id, workerId, retryAttempt = runAttemptCount > 0) ?: return@withLock
                try {
                    setForeground(foreground(record.entry))
                    store.directory(id).mkdirs()
                    store.update(id, workerId) { it.copy(status = DownloadStatus.RUNNING, error = "") }
                    val visited = mutableSetOf<Int>()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val current = store.record(id) ?: return@withLock
                        if (current.workId != workerId || current.entry.status == DownloadStatus.PAUSED) return@withLock
                        if (current.entry.status == DownloadStatus.COMPLETED) break
                        val index = store.nextPageIndex(id, workerId, visited) ?: break
                        // The reader and worker await the same persistent per-page transfer.
                        // A visible page can proceed while another chapter owns the worker gate.
                        store.downloadPage(id, workerId, index) ?: return@withLock
                        visited.add(index)
                        store.record(id)?.entry?.let { setForeground(foreground(it)) }
                    }
                    currentCoroutineContext().ensureActive()
                    val finished = store.record(id) ?: return@withLock
                    if (finished.workId != workerId || finished.entry.status == DownloadStatus.PAUSED) return@withLock
                    if (finished.entry.status == DownloadStatus.FAILED) return@withLock
                    if (finished.entry.completedPages != finished.pages.size) throw IOException("本地章节尚未下载完整")
                    store.update(id, workerId) { it.copy(status = DownloadStatus.COMPLETED, error = "") }
                } finally {
                    store.endTransfer(id, workerId)
                }
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            runCatching { store.update(id, workerId) { it.copy(status = DownloadStatus.WAITING, error = "等待系统恢复下载") } }
            throw cancelled
        } catch (error: Exception) {
            val retryable = error is IOException && error !is PermanentDownloadException && error !is DownloadStorageException && runAttemptCount < 3
            runCatching { store.update(id, workerId) { it.copy(
                status = if (retryable) DownloadStatus.WAITING else DownloadStatus.FAILED,
                error = error.message ?: "下载未完成",
                needsSourceRefresh = (error as? PermanentDownloadException)?.needsSourceRefresh == true,
            ) } }
            if (retryable) Result.retry() else Result.failure()
        }
    }

    private fun foreground(entry: DownloadEntry): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "漫画下载", NotificationManager.IMPORTANCE_LOW))
        val launch = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
            ?.putExtra("open_downloads", true)
            ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(entry.manga.title)
            .setContentText("${entry.chapter.title} · ${entry.completedPages}/${entry.totalPages} 页")
            .setProgress(entry.totalPages, entry.completedPages, entry.totalPages == 0)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply {
                launch?.let { setContentIntent(PendingIntent.getActivity(applicationContext, DOWNLOAD_REQUEST_CODE, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) }
            }
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(entry.id.hashCode().and(Int.MAX_VALUE), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(entry.id.hashCode().and(Int.MAX_VALUE), notification)
    }

    companion object {
        const val KEY_CHAPTER = "chapter_download_id"
        private const val CHANNEL_ID = "mangaflow-downloads"
        private const val DOWNLOAD_REQUEST_CODE = 84218
    }
}
