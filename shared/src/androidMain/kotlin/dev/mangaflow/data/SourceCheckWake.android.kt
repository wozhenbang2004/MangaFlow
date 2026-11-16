package dev.mangaflow.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.mangaflow.MangaApplicationServices
import dev.mangaflow.platform.PlatformContext
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

internal actual fun scheduleSourceCheckWake(context: PlatformContext, intervalMinutes: Int) {
    // Android enforces >=15 minutes for process-independent periodic work. The live timer
    // still honors a user's shorter period; Doze/constraints may postpone this OS wake.
    val request = PeriodicWorkRequestBuilder<SourceCheckWorker>(intervalMinutes.toLong().coerceAtLeast(15L), TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
        "source-catalogue-checks", ExistingPeriodicWorkPolicy.UPDATE, request)
}

internal actual fun cancelSourceCheckWake(context: PlatformContext) {
    WorkManager.getInstance(context.applicationContext).cancelUniqueWork("source-catalogue-checks")
}

class SourceCheckWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        MangaApplicationServices.get(applicationContext).sourceChecks.checkDue()
        Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { Result.retry() }
}
