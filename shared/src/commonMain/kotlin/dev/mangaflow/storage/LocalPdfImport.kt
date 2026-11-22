package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.syncStorageFile
import kotlin.math.floor
import kotlin.math.sqrt

internal val localPdfImportSupported: Boolean get() = platformKind != PlatformKind.IOS

/** Static, safe messages may also survive in the application-owned import status. */
internal enum class LocalPdfFailure(val userMessage: String) {
    ENCRYPTED("此 PDF 需要密码或使用了不支持的加密方式，请先另存为未加密 PDF 后导入。"),
    UNSUPPORTED_IMAGE("此 PDF 含当前无法解码的图片（例如 JPEG 2000），未导入任何章节。请先另存为兼容 PDF。"),
    INVALID_DOCUMENT("此 PDF 已损坏或无法完整读取，未导入任何章节。"),
    INVALID_PAGE("此 PDF 含无法读取的页面，未导入任何章节。"),
    TOO_MANY_PAGES("此 PDF 超过本地导入的页数上限，未导入任何章节。"),
    PAGE_TOO_LARGE("此 PDF 的单页超过可保存的大小限制，未导入任何章节。"),
    TOTAL_TOO_LARGE("此 PDF 超过本地导入的总大小限制，未导入任何章节。"),
    STORAGE("PDF 未能完整保存，请检查可用空间后重新导入。"),
    UNAVAILABLE("此平台暂不支持 PDF 导入。"),
}

internal class LocalPdfImportException(val failure: LocalPdfFailure, cause: Throwable? = null) :
    IllegalStateException(failure.userMessage, cause)

internal data class LocalPdfPage(val width: Int, val height: Int)
internal data class LocalPdfRaster(val width: Int, val height: Int, val scale: Float)

internal data class LocalPdfLimits(val maxPages: Int, val maxPageBytes: Long, val maxTotalBytes: Long) {
    fun checkPageCount(count: Int) {
        if (count <= 0) throw LocalPdfImportException(LocalPdfFailure.INVALID_DOCUMENT)
        if (count > maxPages) throw LocalPdfImportException(LocalPdfFailure.TOO_MANY_PAGES)
    }

    fun pageByteLimit(completedBytes: Long): Long {
        val remaining = maxTotalBytes - completedBytes
        if (remaining <= 0L) throw LocalPdfImportException(LocalPdfFailure.TOTAL_TOO_LARGE)
        return minOf(maxPageBytes, remaining)
    }

    fun byteLimitFailure(completedBytes: Long): LocalPdfFailure =
        if (maxTotalBytes - completedBytes <= maxPageBytes) LocalPdfFailure.TOTAL_TOO_LARGE
        else LocalPdfFailure.PAGE_TOO_LARGE
}

/** 216 dpi for ordinary pages; large or very long pages scale down before allocation. */
internal fun localPdfRaster(widthPoints: Double, heightPoints: Double): LocalPdfRaster {
    if (!widthPoints.isFinite() || !heightPoints.isFinite() || widthPoints <= 0.0 || heightPoints <= 0.0) {
        throw LocalPdfImportException(LocalPdfFailure.INVALID_PAGE)
    }
    val scale = minOf(3.0, 8192.0 / maxOf(widthPoints, heightPoints),
        sqrt(8_388_608.0 / (widthPoints * heightPoints))).toFloat()
    if (!scale.isFinite() || scale <= 0f) throw LocalPdfImportException(LocalPdfFailure.INVALID_PAGE)
    val width = floor(widthPoints.toFloat() * scale).toInt().coerceAtLeast(1)
    val height = floor(heightPoints.toFloat() * scale).toInt().coerceAtLeast(1)
    if (width > 8192 || height > 8192 || width.toLong() * height > 8_388_608L) {
        // Float rounding must never move the allocation above its actual pixel budget.
        return localPdfRasterAtScale(widthPoints, heightPoints, scale * .999999f)
    }
    return LocalPdfRaster(width, height, scale)
}

private fun localPdfRasterAtScale(width: Double, height: Double, scale: Float): LocalPdfRaster {
    val pixelsWide = floor(width.toFloat() * scale).toInt().coerceAtLeast(1)
    val pixelsHigh = floor(height.toFloat() * scale).toInt().coerceAtLeast(1)
    if (pixelsWide > 8192 || pixelsHigh > 8192 || pixelsWide.toLong() * pixelsHigh > 8_388_608L) {
        throw LocalPdfImportException(LocalPdfFailure.INVALID_PAGE)
    }
    return LocalPdfRaster(pixelsWide, pixelsHigh, scale)
}

internal fun localPdfPageFile(staging: StorageFile, index: Int): StorageFile =
    StorageFile(staging, "${index.toString().padStart(5, '0')}.img")

internal fun validateLocalPdfPage(file: StorageFile, width: Int, height: Int, byteLimit: Long): Long {
    val size = file.length()
    if (size <= 0L || size > byteLimit || storageImageBounds(file) != (width to height)) {
        throw LocalPdfImportException(LocalPdfFailure.STORAGE)
    }
    syncStorageFile(file)
    return size
}

/** Writes only staged image pages. DownloadStore remains the sole chapter publication owner. */
internal expect suspend fun renderLocalPdf(
    source: StorageFile, staging: StorageFile, limits: LocalPdfLimits,
): List<LocalPdfPage>
