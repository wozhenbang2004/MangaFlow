package dev.mangaflow.storage

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.work.*
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.StorageInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import java.util.UUID
import java.util.concurrent.TimeUnit

internal actual fun openDocument(context: PlatformContext, uri: String): StorageInput = StorageInput(
    (context.contentResolver.openInputStream(Uri.parse(uri)) ?: error("无法读取选择的文件，请重新选择。")) .source().buffer()
)
internal actual fun documentDisplayName(context: PlatformContext, uri: String): String = runCatching {
    context.contentResolver.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
}.getOrNull().orEmpty().ifBlank { Uri.parse(uri).lastPathSegment.orEmpty().ifBlank { "本地文件" } }
internal actual fun documentMimeType(context: PlatformContext, uri: String): String = context.contentResolver.getType(Uri.parse(uri)).orEmpty()
internal actual fun retainDocumentPermission(context: PlatformContext, uri: String) {
    runCatching { context.contentResolver.takePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
}
internal actual suspend fun collectFolderImages(context: PlatformContext, uri: String): List<LocalDocument> {
    val tree = Uri.parse(uri)
    val documents = mutableListOf<LocalDocument>()
    suspend fun visit(parentId: String, path: String, depth: Int) {
        currentCoroutineContext().ensureActive()
        require(depth <= 16) { "文件夹层级过深" }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val folders = mutableListOf<Pair<String, String>>()
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val id = cursor.getString(idColumn)
                val name = cursor.getString(nameColumn).orEmpty().ifBlank { id.substringAfterLast('/') }
                val childPath = if (path.isBlank()) name else "$path/$name"
                val mime = cursor.getString(mimeColumn).orEmpty()
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) folders += id to childPath
                else if (mime.startsWith("image/") || isImageName(name)) {
                    documents += LocalDocument(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), childPath)
                    require(documents.size <= 5_000) { "文件夹图片超过 5000 页" }
                }
            }
        } ?: error("无法读取所选文件夹")
        folders.sortedWith(compareByNatural { it.second }).forEach { (id, childPath) -> visit(id, childPath, depth + 1) }
    }
    visit(DocumentsContract.getTreeDocumentId(tree), "", 0)
    return documents.sortedWith(compareByNatural(LocalDocument::name))
}
internal actual fun storageImageBounds(file: StorageFile): Pair<Int, Int>? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, options)
    return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
}

@Suppress("DEPRECATION")
internal actual suspend fun createWallpaperVideoPoster(media: StorageFile, poster: StorageFile) = withContext(Dispatchers.IO) {
    val reader = MediaMetadataRetriever()
    val frame: Bitmap
    try {
        reader.setDataSource(media.path)
        val width = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        require(width > 0 && height > 0) { "此设备无法识别视频，请尝试 MP4（H.264）。" }
        val scale = minOf(1f, 1080f / width, 1920f / height)
        frame = (if (Build.VERSION.SDK_INT >= 27) reader.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
        else ThumbnailUtils.createVideoThumbnail(media.path, MediaStore.Images.Thumbnails.MINI_KIND))
            ?: error("视频首帧无法读取，请尝试 MP4（H.264）。")
    } finally { reader.release() }
    try { java.io.File(poster.path).outputStream().use { check(frame.compress(Bitmap.CompressFormat.JPEG, 82, it)) { "视频预览未能保存。" } } }
    finally { frame.recycle() }
}

internal actual class DownloadScheduler actual constructor(context: PlatformContext) {
    private val manager = WorkManager.getInstance(context.applicationContext)
    actual fun schedule(chapterId: String, workId: String) {
        val request = OneTimeWorkRequestBuilder<ChapterDownloadWorker>().setId(UUID.fromString(workId))
            .setInputData(workDataOf(ChapterDownloadWorker.KEY_CHAPTER to chapterId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(DownloadStore.DOWNLOAD_TAG).build()
        manager.enqueueUniqueWork("mangaflow-download-$chapterId", ExistingWorkPolicy.REPLACE, request).result.get(20, TimeUnit.SECONDS)
    }
    actual suspend fun cancel(workId: String) = withContext(Dispatchers.IO) {
        manager.cancelWorkById(UUID.fromString(workId)).result.get(20, TimeUnit.SECONDS)
        Unit
    }
    actual suspend fun state(workId: String): DownloadTaskState? = withContext(Dispatchers.IO) {
        manager.getWorkInfoById(UUID.fromString(workId)).get(20, TimeUnit.SECONDS)?.let {
            when (it.state) {
                WorkInfo.State.ENQUEUED -> DownloadTaskState.ENQUEUED
                WorkInfo.State.RUNNING -> DownloadTaskState.RUNNING
                WorkInfo.State.BLOCKED -> DownloadTaskState.BLOCKED
                else -> DownloadTaskState.FINISHED
            }
        }
    }
}
internal actual fun beginDownloadExecution(onExpiration: () -> Unit): () -> Unit = {}
