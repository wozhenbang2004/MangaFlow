package dev.mangaflow.net

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.mangaflow.MangaApplicationServices
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

internal actual fun scheduleNetworkAutoWake(context: Context, intervalMinutes: Int) {
    val request = PeriodicWorkRequestBuilder<NetworkAutoWorker>(
        intervalMinutes.toLong().coerceAtLeast(15L), TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
        "network-auto-route-checks", ExistingPeriodicWorkPolicy.UPDATE, request)
}

internal actual fun cancelNetworkAutoWake(context: Context) {
    WorkManager.getInstance(context.applicationContext).cancelUniqueWork("network-auto-route-checks")
}

class NetworkAutoWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        MangaApplicationServices.get(applicationContext).networkChecks.checkDue()
        Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { Result.retry() }
}
