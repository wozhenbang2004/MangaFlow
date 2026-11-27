package dev.mangaflow.data.sources

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Rect
import dev.mangaflow.platform.storage.StorageFile
import io.github.jukomu.jmcomic.api.exception.JmComicException
import io.github.jukomu.jmcomic.api.model.JmImage
import io.github.jukomu.jmcomic.core.crypto.JmImageTool
import java.io.IOException
import java.util.concurrent.CancellationException

// Android uses BitmapFactory.inJustDecodeBounds and a file-backed EXIF reader.
@Suppress("UNUSED_PARAMETER")
internal fun jmHeaderWorkingBytes(encodedBytes: Long): Long = 0L

/** The reader already spooled the original to a private file. Restore directly from that
 * file and encode to the pending file; neither encoded image needs a full heap copy. */
@Suppress("DEPRECATION")
internal fun restoreJmFile(source: StorageFile, pending: StorageFile, image: JmImage, segments: Int,
    pixels: Long, maximum: Long, reserve: (Long) -> (() -> Unit)): StorageFile {
    val header = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.path, header)
    val width = header.outWidth
    val height = header.outHeight
    if (width <= 0 || height <= 0 || width.toLong() * height != pixels)
        throw JmComicException("禁漫图片还原失败：图片尺寸无效。")
    if (height < segments) return source
    val formatName = header.outMimeType?.substringAfter("image/", "")?.takeIf { it.isNotBlank() }
        ?: JmImageTool.getFormatName(image.getFilename())
    val format = JmImageTool.getCompressFormat(formatName)
    // Bound each region to at most 256 Ki pixels, except for a single wider scanline.
    val bandRows = minOf(height, (256 * 1024 / width).coerceIn(1, 256))
    val bandBytes = width.toLong() * bandRows * 4L
    val releaseBitmap = reserve(pixels * 4L)
    try {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false }
            val releaseReader = reserve(source.length() + 2L * bandBytes + 256L * 1024)
            try {
                val decoder = try { BitmapRegionDecoder.newInstance(source.path, false) }
                    catch (_: IOException) { null }
                    catch (_: IllegalArgumentException) { null }
                if (decoder != null) {
                    try {
                        JmImageTool.forEachStrip(height, segments) { from, to, count ->
                            var offset = 0
                            while (offset < count) {
                                if (Thread.currentThread().isInterrupted) throw CancellationException("JM restore cancelled")
                                val rows = minOf(bandRows, count - offset)
                                val stripe = decoder.decodeRegion(Rect(0, from + offset, width, from + offset + rows), options)
                                    ?: throw JmComicException("禁漫图片还原失败：无法读取图片条带。")
                                try {
                                    canvas.drawBitmap(stripe, null, Rect(0, to + offset, width, to + offset + rows), null)
                                } finally { stripe.recycle() }
                                offset += rows
                            }
                        }
                    } finally { decoder.recycle() }
                } else {
                    // Formats without a region decoder can use a bounded full decode.
                    val releaseFull = reserve(pixels * 4L)
                    try {
                        val original = BitmapFactory.decodeFile(source.path, options)
                            ?: throw JmComicException("禁漫图片还原失败：无法解码下载的图片。")
                        try {
                            JmImageTool.forEachStrip(height, segments) { from, to, count ->
                                canvas.drawBitmap(original, Rect(0, from, width, from + count),
                                    Rect(0, to, width, to + count), null)
                            }
                        } finally { original.recycle() }
                    } finally { releaseFull() }
                }
            } finally { releaseReader() }
            // WebP can keep a full-frame encoder work buffer. PNG/JPEG use scanline buffers.
            val encoderAllowance = 256L * 1024 + if (format == Bitmap.CompressFormat.WEBP) pixels * 4L else bandBytes * 2L
            val releaseEncoder = reserve(encoderAllowance)
            try {
                writeJmRestoredFile(pending, maximum) { stream ->
                    if (!output.compress(format, 100, stream)) throw JmComicException("禁漫图片还原失败：编码未完成。")
                }
            } finally { releaseEncoder() }
        } finally { output.recycle() }
    } finally { releaseBitmap() }
    return pending
}
