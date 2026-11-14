package dev.mangaflow.storage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.net.Uri
import android.text.format.Formatter
import dev.mangaflow.platform.wallTimeMillis
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters

/** A chapter is complete only after every image has been saved and decoded for its bounds. */
class ChapterDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val chapter = inputData.getString(KEY_CHAPTER) ?: return Result.failure()
        ChapterDownloadNotifications.createChannel(applicationContext)
        // Protect preparation and queue waits, before the shared protocol starts transferring pages.
        setForeground(foreground(chapter))
        val updates = DownloadNotificationUpdates()
        return when (runChapterDownload(applicationContext, chapter, id.toString(), runAttemptCount) { entry ->
            if (updates.accept(entry)) {
                setForeground(foreground(chapter, entry))
            }
        }) {
            DownloadOutcome.SUCCESS -> Result.success()
            DownloadOutcome.RETRY -> Result.retry()
            DownloadOutcome.FAILURE -> Result.failure()
        }
    }

    private fun foreground(chapterId: String, entry: DownloadEntry? = null): ForegroundInfo {
        val notification = ChapterDownloadNotifications.notification(applicationContext, chapterId, id.toString(), entry)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(ChapterDownloadNotifications.id(chapterId), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(ChapterDownloadNotifications.id(chapterId), notification)
    }

    companion object {
        const val KEY_CHAPTER = "chapter_download_id"
    }
}

/** One notification contract for WorkManager and user-initiated transfer jobs. */
internal object ChapterDownloadNotifications {
    private const val CHANNEL_ID = "mangaflow-downloads"
    private const val DOWNLOAD_REQUEST_CODE = 84218

    fun id(chapterId: String): Int = chapterId.hashCode().and(Int.MAX_VALUE)

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "漫画下载", NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun notification(context: Context, chapterId: String, workId: String, entry: DownloadEntry? = null): Notification {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.putExtra("open_downloads", true)
            ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val progress = entry?.progress
        val stage = progress?.waitingReason?.label ?: progress?.phase?.label ?: "正在准备下载"
        val page = progress?.pageIndex?.let { "第 ${it + 1} 页" }
        val transfer = progress?.takeIf { it.receivedBytes > 0L }?.let {
            buildString {
                append(Formatter.formatShortFileSize(context, it.receivedBytes))
                it.expectedBytes?.takeIf { expected -> expected > 0L && it.activePages == 1 }?.let { expected ->
                    append(" / ").append(Formatter.formatShortFileSize(context, expected))
                }
                it.bytesPerSecond?.takeIf { speed -> speed > 0L && entry?.status == DownloadStatus.RUNNING &&
                    it.phase == DownloadPhase.TRANSFERRING &&
                    it.lastProgressAt?.let { at -> wallTimeMillis() - at in 0L..4_000L } == true }?.let { speed ->
                    append(" · ").append(Formatter.formatShortFileSize(context, speed)).append("/s")
                }
            }
        }
        val saved = entry?.let { "已保存 ${it.completedPages}/${it.totalPages} 页" }
        val details = listOfNotNull(entry?.chapter?.title, saved, listOfNotNull(stage, page).joinToString(" · "),
            transfer, progress?.routeLabel?.takeIf(String::isNotBlank)).joinToString("\n")
        val expected = progress?.takeIf { it.activePages == 1 }?.expectedBytes?.takeIf { it > 0L }
        val receiving = progress?.phase?.let { it in setOf(DownloadPhase.CONNECTING, DownloadPhase.FIRST_BYTE_WAIT,
            DownloadPhase.TRANSFERRING, DownloadPhase.VERIFYING, DownloadPhase.COMMITTING) } == true
        val pause = Intent(context, ChapterDownloadActionReceiver::class.java)
            .setAction(ChapterDownloadActionReceiver.ACTION_PAUSE)
            .setData(Uri.Builder().scheme("mangaflow").authority("download").appendPath(workId).appendPath(chapterId).build())
            .putExtra(ChapterDownloadActionReceiver.KEY_CHAPTER, chapterId)
            .putExtra(ChapterDownloadActionReceiver.KEY_WORK, workId)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(entry?.manga?.title ?: "漫画下载")
            .setContentText(listOfNotNull(stage, page, transfer).joinToString(" · "))
            .setSubText(saved)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setProgress(if (expected != null) 1000 else 0,
                if (expected != null) (((progress?.receivedBytes ?: 0L).toDouble() / expected) * 1000).toInt().coerceIn(0, 1000) else 0,
                expected == null && (entry == null || receiving))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(android.R.drawable.ic_media_pause, "暂停", PendingIntent.getBroadcast(context, DOWNLOAD_REQUEST_CODE,
                pause, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .apply {
                launch?.let { setContentIntent(PendingIntent.getActivity(context, DOWNLOAD_REQUEST_CODE, it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) }
            }
            .build()
    }
}

/** Bytes may arrive every few milliseconds; notify at most once per second unless a state changes. */
internal class DownloadNotificationUpdates {
    private var previous: NotificationState? = null
    private var notifiedAt = 0L
    fun accept(entry: DownloadEntry): Boolean {
        val state = NotificationState(entry.status, entry.completedPages, entry.totalPages,
            entry.progress.phase, entry.progress.waitingReason, entry.progress.pageIndex)
        val now = SystemClock.elapsedRealtime()
        if (state == previous && now - notifiedAt < 1_000L) return false
        previous = state
        notifiedAt = now
        return true
    }
    private data class NotificationState(val status: DownloadStatus, val completed: Int, val total: Int,
        val phase: DownloadPhase, val waiting: DownloadWaitReason?, val page: Int?)
}
