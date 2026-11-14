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

/** A chapter is complete only after every image has been saved and decoded for its bounds. */
class ChapterDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val chapter = inputData.getString(KEY_CHAPTER) ?: return Result.failure()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "漫画下载", NotificationManager.IMPORTANCE_LOW))
        // Protect preparation and queue waits, before the shared protocol starts transferring pages.
        setForeground(foreground(chapter))
        var previousCompleted = -1
        var previousTotal = -1
        return when (runChapterDownload(applicationContext, chapter, id.toString(), runAttemptCount) { entry ->
            if (entry.completedPages != previousCompleted || entry.totalPages != previousTotal) {
                setForeground(foreground(chapter, entry))
                previousCompleted = entry.completedPages
                previousTotal = entry.totalPages
            }
        }) {
            DownloadOutcome.SUCCESS -> Result.success()
            DownloadOutcome.RETRY -> Result.retry()
            DownloadOutcome.FAILURE -> Result.failure()
        }
    }

    private fun foreground(chapterId: String, entry: DownloadEntry? = null): ForegroundInfo {
        val total = entry?.totalPages ?: 0
        val completed = entry?.completedPages ?: 0
        val launch = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
            ?.putExtra("open_downloads", true)
            ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(entry?.manga?.title ?: "漫画下载")
            .setContentText(entry?.let { "${it.chapter.title} · ${it.completedPages}/${it.totalPages} 页" } ?: "正在准备下载")
            .setProgress(total, completed, total == 0)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply {
                launch?.let { setContentIntent(PendingIntent.getActivity(applicationContext, DOWNLOAD_REQUEST_CODE, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) }
            }
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(chapterId.hashCode().and(Int.MAX_VALUE), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(chapterId.hashCode().and(Int.MAX_VALUE), notification)
    }

    companion object {
        const val KEY_CHAPTER = "chapter_download_id"
        private const val CHANNEL_ID = "mangaflow-downloads"
        private const val DOWNLOAD_REQUEST_CODE = 84218
    }
}
