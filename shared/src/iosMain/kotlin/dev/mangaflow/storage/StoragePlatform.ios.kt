@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.StorageInput
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.AVFoundation.*
import platform.CoreFoundation.*
import platform.CoreGraphics.*
import platform.CoreMedia.*
import platform.Foundation.*
import platform.ImageIO.*
import platform.UIKit.*
import platform.darwin.*

internal actual fun openDocument(context: PlatformContext, uri: String): StorageInput = localDocumentFile(uri).inputStream()
internal actual fun documentDisplayName(context: PlatformContext, uri: String): String = localDocumentFile(uri).name
internal actual fun documentMimeType(context: PlatformContext, uri: String): String = when (localDocumentFile(uri).extension.lowercase()) {
    "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "avif", "bmp" -> "image/*"
    "mp4", "m4v", "mov", "webm" -> "video/*"
    else -> ""
}
internal actual fun retainDocumentPermission(context: PlatformContext, uri: String) { require(localDocumentFile(uri).exists()) { "所选文件不存在。" } }
internal actual suspend fun collectFolderImages(context: PlatformContext, uri: String): List<LocalDocument> = collectLocalFolderImages(uri)
internal actual fun storageImageBounds(file: StorageFile): Pair<Int, Int>? = runCatching {
    val path = CFStringCreateWithCString(null, file.path, kCFStringEncodingUTF8) ?: return@runCatching null
    val url = CFURLCreateWithFileSystemPath(null, path, kCFURLPOSIXPathStyle, false)
    CFRelease(path)
    if (url == null) return@runCatching null
    val source = CGImageSourceCreateWithURL(url, null)
    CFRelease(url)
    if (source == null) return@runCatching null
    try {
        val properties = CGImageSourceCopyPropertiesAtIndex(source, 0uL, null) ?: return@runCatching null
        try {
            memScoped {
                val width = alloc<IntVar>(); val height = alloc<IntVar>()
                val widthNumber = CFDictionaryGetValue(properties, kCGImagePropertyPixelWidth) ?: return@memScoped null
                val heightNumber = CFDictionaryGetValue(properties, kCGImagePropertyPixelHeight) ?: return@memScoped null
                val valid = CFNumberGetValue(widthNumber.reinterpret(), kCFNumberIntType, width.ptr) &&
                    CFNumberGetValue(heightNumber.reinterpret(), kCFNumberIntType, height.ptr)
                if (valid && width.value > 0 && height.value > 0) width.value to height.value else null
            }
        } finally { CFRelease(properties) }
    } finally { CFRelease(source) }
}.getOrNull()

internal actual suspend fun createWallpaperVideoPoster(media: StorageFile, poster: StorageFile) = withContext(Dispatchers.Default) {
    val asset = AVURLAsset(NSURL.fileURLWithPath(media.path), null)
    val generator = AVAssetImageGenerator(asset)
    generator.appliesPreferredTrackTransform = true
    generator.maximumSize = CGSizeMake(1080.0, 1920.0)
    val frame = generator.copyCGImageAtTime(CMTimeMake(0, 1), null, null)
        ?: error("此设备无法读取视频首帧，请尝试 MP4（H.264）。")
    try {
        val data = UIImageJPEGRepresentation(UIImage.imageWithCGImage(frame), .82)
            ?: error("无法生成视频预览。")
        check(data.writeToFile(poster.path, true)) { "无法保存视频预览。" }
    } finally { CGImageRelease(frame) }
}

internal actual class DownloadScheduler actual constructor(context: PlatformContext) {
    private val session = SessionDownloadScheduler(context)
    actual fun schedule(chapterId: String, workId: String) = session.schedule(chapterId, workId)
    actual suspend fun cancel(workId: String) = session.cancel(workId)
    actual suspend fun state(workId: String): DownloadTaskState? = session.state(workId)
}

internal actual fun beginDownloadExecution(onExpiration: () -> Unit): () -> Unit {
    var identifier = UIBackgroundTaskInvalid
    val start = {
        identifier = UIApplication.sharedApplication.beginBackgroundTaskWithName("MangaFlow下载", onExpiration)
    }
    if (NSThread.isMainThread) start() else dispatch_sync(dispatch_get_main_queue(), start)
    return {
        dispatch_async(dispatch_get_main_queue()) {
            if (identifier != UIBackgroundTaskInvalid) {
                UIApplication.sharedApplication.endBackgroundTask(identifier)
                identifier = UIBackgroundTaskInvalid
            }
        }
    }
}
