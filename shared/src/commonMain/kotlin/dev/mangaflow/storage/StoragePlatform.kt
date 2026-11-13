package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.StorageInput

internal data class LocalDocument(val uri: String, val name: String)
internal expect fun openDocument(context: PlatformContext, uri: String): StorageInput
internal expect fun documentDisplayName(context: PlatformContext, uri: String): String
internal expect fun documentMimeType(context: PlatformContext, uri: String): String
internal expect fun retainDocumentPermission(context: PlatformContext, uri: String)
internal expect suspend fun collectFolderImages(context: PlatformContext, uri: String): List<LocalDocument>
internal expect fun storageImageBounds(file: StorageFile): Pair<Int, Int>?
internal expect suspend fun createWallpaperVideoPoster(media: StorageFile, poster: StorageFile)

internal enum class DownloadTaskState { ENQUEUED, RUNNING, BLOCKED, FINISHED }
internal expect class DownloadScheduler(context: PlatformContext) {
    fun schedule(chapterId: String, workId: String)
    suspend fun cancel(workId: String)
    suspend fun state(workId: String): DownloadTaskState?
}

/** A native execution lease does not replace the persisted chapter queue. */
internal expect fun beginDownloadExecution(onExpiration: () -> Unit): () -> Unit
