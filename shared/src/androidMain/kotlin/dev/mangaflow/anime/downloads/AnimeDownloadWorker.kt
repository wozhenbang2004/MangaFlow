package dev.mangaflow.anime.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.mangaflow.MangaApplicationServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Holds Android's dataSync foreground service while the application store drains video work. */
class AnimeDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val store = withContext(Dispatchers.Main.immediate) { MangaApplicationServices.get(applicationContext).animeDownloads }
        if (!store.hasPendingWork()) return Result.success()
        return try {
            applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "番剧下载", NotificationManager.IMPORTANCE_LOW),
            )
            setForeground(foreground())
            var shown: AnimeDownloadEntry? = null
            var lastUpdated = 0L
            store.runWithBackgroundExecution { entry ->
                val now = SystemClock.elapsedRealtime()
                if (entry.id != shown?.id || entry.status != shown?.status || entry.error != shown?.error || now - lastUpdated >= 1_000L) {
                    setForeground(foreground(entry))
                    shown = entry
                    lastUpdated = now
                }
            }
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: IllegalStateException) {
            // Foreground promotion can be rejected by a platform limit. The store keeps
            // the saved intent and will run in-app or when WorkManager is admitted again.
            Result.retry()
        } catch (_: SecurityException) {
            Result.retry()
        }
    }

    private fun foreground(entry: AnimeDownloadEntry? = null): ForegroundInfo {
        val context = applicationContext
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val running = entry?.status == AnimeDownloadStatus.DOWNLOADING
        val stage = when (entry?.status) {
            AnimeDownloadStatus.DOWNLOADING -> "正在下载"
            AnimeDownloadStatus.WAITING -> entry.error ?: "等待继续下载"
            else -> "正在准备下载"
        }
        val total = entry?.totalBytes?.takeIf { it > 0L }
        val progress = if (entry != null && total != null)
            ((entry.downloadedBytes.toDouble() / total) * 100).toInt().coerceIn(0, 100) else 0
        val transfer = entry?.downloadedBytes?.takeIf { it > 0L }?.let { bytes ->
            Formatter.formatShortFileSize(context, bytes) + (total?.let { " / " + Formatter.formatShortFileSize(context, it) } ?: "")
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(entry?.subject?.title ?: "番剧下载")
            .setContentText(listOfNotNull(entry?.episode?.title?.takeIf(String::isNotBlank), stage, transfer).joinToString(" · "))
            .setProgress(if (running && total != null) 100 else 0, if (running) progress else 0,
                total == null && (entry == null || running))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply {
                launch?.let { setContentIntent(PendingIntent.getActivity(context, NOTIFICATION_ID, it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) }
            }.build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL = "mangaflow-anime-downloads"
        const val NOTIFICATION_ID = -84237
    }
}
