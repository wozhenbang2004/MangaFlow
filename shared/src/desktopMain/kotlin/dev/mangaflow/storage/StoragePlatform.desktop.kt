package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.StorageInput
import dev.mangaflow.ui.platform.initializeJavaFx
import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.scene.image.WritableImage
import javafx.animation.PauseTransition
import javafx.util.Duration
import javafx.scene.Group
import javafx.scene.Scene
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import javax.imageio.ImageIO
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal actual fun openDocument(context: PlatformContext, uri: String): StorageInput = localDocumentFile(uri).inputStream()
internal actual fun documentDisplayName(context: PlatformContext, uri: String): String = localDocumentFile(uri).name
internal actual fun documentMimeType(context: PlatformContext, uri: String): String = java.nio.file.Files.probeContentType(java.nio.file.Paths.get(localDocumentFile(uri).path)).orEmpty()
internal actual fun retainDocumentPermission(context: PlatformContext, uri: String) { require(localDocumentFile(uri).exists()) { "所选文件不存在。" } }
internal actual suspend fun collectFolderImages(context: PlatformContext, uri: String): List<LocalDocument> = collectLocalFolderImages(uri)
internal actual fun storageImageBounds(file: StorageFile): Pair<Int, Int>? {
    val header = runCatching {
        ImageIO.createImageInputStream(java.io.File(file.path))?.use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) null else {
                val reader = readers.next()
                try {
                    reader.input = input
                    val width = reader.getWidth(0); val height = reader.getHeight(0)
                    if (width > 0 && height > 0) width to height else null
                } finally { reader.dispose() }
            }
        }
    }.getOrNull()
    if (header != null) return header
    return runCatching {
    require(file.length() in 1..128L * 1024 * 1024)
    Data.makeFromBytes(file.readBytes()).use { data -> Codec.makeFromData(data).use { codec ->
        codec.imageInfo.takeIf { it.width > 0 && it.height > 0 }?.let { it.width to it.height }
    } }
    }.getOrNull()
}

internal actual suspend fun createWallpaperVideoPoster(media: StorageFile, poster: StorageFile) = withTimeout(30_000) {
    initializeJavaFx()
    suspendCancellableCoroutine<Unit> { continuation ->
        Platform.runLater {
            var player: MediaPlayer? = null
            try {
                val native = MediaPlayer(Media(media.toURI()))
                player = native
                continuation.invokeOnCancellation { Platform.runLater { native.dispose() } }
                native.setOnError {
                    native.dispose()
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("此电脑无法解码该视频，请尝试 MP4（H.264）。", native.error))
                }
                native.setOnReady {
                    if (!continuation.isActive) { native.dispose(); return@setOnReady }
                    try {
                        val source = native.media
                        require(source.width > 0 && source.height > 0) { "视频没有可读取的画面。" }
                        val scale = minOf(1.0, 1080.0 / source.width, 1920.0 / source.height)
                        val view = MediaView(native).apply { fitWidth = source.width * scale; fitHeight = source.height * scale; isPreserveRatio = true }
                        Scene(Group(view), source.width * scale, source.height * scale)
                        // Let the native decoder present its first frame before taking the snapshot.
                        native.setOnPlaying {
                            if (!continuation.isActive) { native.dispose(); return@setOnPlaying }
                            PauseTransition(Duration.millis(150.0)).apply {
                                setOnFinished {
                                if (!continuation.isActive) { native.dispose(); return@setOnFinished }
                                native.pause()
                                try {
                                    val image = view.snapshot(null, WritableImage((source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1)))
                                    check(ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", java.io.File(poster.path))) { "无法保存视频预览。" }
                                    if (continuation.isActive) continuation.resume(Unit)
                                } catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
                                finally { native.dispose() }
                                }
                                play()
                            }
                        }
                        native.isMute = true
                        native.play()
                    } catch (failure: Exception) {
                        native.dispose()
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }
            } catch (failure: Exception) { player?.dispose(); if (continuation.isActive) continuation.resumeWithException(failure) }
        }
    }
}

internal actual class DownloadScheduler actual constructor(context: PlatformContext) {
    private val session = SessionDownloadScheduler(context)
    actual fun schedule(chapterId: String, workId: String) = session.schedule(chapterId, workId)
    actual suspend fun cancel(workId: String) = session.cancel(workId)
    actual suspend fun state(workId: String): DownloadTaskState? = session.state(workId)
}
internal actual fun beginDownloadExecution(onExpiration: () -> Unit): () -> Unit = {}
