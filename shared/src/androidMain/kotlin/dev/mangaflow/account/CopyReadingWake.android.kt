package dev.mangaflow.account

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.mangaflow.MangaApplicationServices
import dev.mangaflow.platform.PlatformContext
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

internal actual fun scheduleCopyReadingWake(context: PlatformContext, delayMillis: Long) {
    val work = OneTimeWorkRequestBuilder<CopyReadingWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
        .build()
    WorkManager.getInstance(context.applicationContext).enqueueUniqueWork("copy-reading-outbox", ExistingWorkPolicy.KEEP, work)
}

internal actual fun cancelCopyReadingWake(context: PlatformContext) {
    WorkManager.getInstance(context.applicationContext).cancelUniqueWork("copy-reading-outbox")
}

/** One short wake; application and worker use the same durable executor, never a second writer. */
class CopyReadingWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val retry = MangaApplicationServices.get(applicationContext).readingOutbox.processPending()
        if (retry && runAttemptCount < 5) Result.retry() else Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { if (runAttemptCount < 5) Result.retry() else Result.failure() }
}
