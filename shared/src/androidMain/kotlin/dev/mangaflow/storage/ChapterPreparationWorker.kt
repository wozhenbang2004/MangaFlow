package dev.mangaflow.storage

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.mangaflow.MangaApplicationServices
import dev.mangaflow.platform.PlatformContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A short metadata request creates the existing persistent image-download task. */
class ChapterPreparationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val workId = inputData.getString(KEY_WORK) ?: return Result.failure()
        if (workId != id.toString()) return Result.failure()
        val preparation = MangaApplicationServices.get(applicationContext).preparations
        return when (preparation.runPending(workId)) {
            ChapterPreparationOutcome.SUCCESS -> Result.success()
            ChapterPreparationOutcome.RETRY -> Result.retry()
            ChapterPreparationOutcome.FAILURE -> Result.failure()
        }
    }

    internal companion object { const val KEY_WORK = "preparationWorkId" }
}

internal actual class ChapterPreparationScheduler actual constructor(context: PlatformContext) {
    private val manager = WorkManager.getInstance(context.applicationContext)
    actual val systemManaged: Boolean = true

    actual fun schedule(workId: String) {
        val request = OneTimeWorkRequestBuilder<ChapterPreparationWorker>()
            .setId(UUID.fromString(workId))
            .setInputData(workDataOf(ChapterPreparationWorker.KEY_WORK to workId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .addTag("mangaflow-chapter-preparation")
            .build()
        // KEEP attaches a restored application to an existing execution instead of replacing it.
        manager.enqueueUniqueWork("mangaflow-prepare-$workId", ExistingWorkPolicy.KEEP, request)
            .result.get(20L, TimeUnit.SECONDS)
    }

    actual suspend fun cancel(workId: String) = withContext(Dispatchers.IO) {
        manager.cancelWorkById(UUID.fromString(workId)).result.get(20L, TimeUnit.SECONDS)
        Unit
    }
}
