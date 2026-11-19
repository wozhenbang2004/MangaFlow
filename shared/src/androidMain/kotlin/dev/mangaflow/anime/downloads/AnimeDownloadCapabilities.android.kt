package dev.mangaflow.anime.downloads

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.mangaflow.platform.PlatformContext
import java.util.concurrent.TimeUnit

internal actual fun animeOfflineVideoContainers(): Set<String> = setOf("MP4", "MKV", "WebM", "TS")
internal actual fun animeSystemDownloadExecution() = true
internal actual fun animeProcessDownloadExecution() = false

internal actual fun scheduleAnimeDownloadExecution(context: PlatformContext, recover: Boolean) {
    val request = OneTimeWorkRequestBuilder<AnimeDownloadWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
        .build()
    // Recovery attaches to a scheduled drain. An explicit enqueue must also wake the queue
    // when the current drain has read its final snapshot but has yet to return to WorkManager.
    WorkManager.getInstance(context.applicationContext).enqueueUniqueWork("mangaflow-anime-downloads",
        if (recover) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        .result.get(20L, TimeUnit.SECONDS)
}
