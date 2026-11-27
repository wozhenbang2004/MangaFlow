package dev.mangaflow.enhancement.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.Rect
import java.io.Closeable
import java.io.File
import java.io.IOException

/** Owns at most one decoded rectangle. Give the model and output buffers their budget first. */
internal class AndroidMangaPixels(private val file: File, private val checkActive: () -> Unit) : Closeable {
    private val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    init {
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "页面格式无法读取" }
    }
    val width = bounds.outWidth
    val height = bounds.outHeight
    private val canDecodeStill = bounds.outMimeType in setOf(
        "image/jpeg", "image/png", "image/webp", "image/bmp", "image/heif", "image/heic", "image/avif"
    )
    private val options = BitmapFactory.Options().apply {
        inScaled = false
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
    }
    private var budget = 0L
    private var requireOpaque = false
    private var decoder: BitmapRegionDecoder? = null
    private var cached: Bitmap? = null
    private val cachedBounds = Rect()
    private var triedFull = false
    private var allowBands = true

    fun reserve(bytes: Long, requireOpaque: Boolean = false) {
        require(bytes > 0) { "原图解码所需的工作空间不足" }
        budget = bytes
        this.requireOpaque = requireOpaque
    }

    fun read(left: Int, top: Int, span: Int, rows: Int, out: IntArray) {
        require(span.toLong() * rows <= budget / Int.SIZE_BYTES) { "原图分块超过当前解码预算" }
        val right = left + span
        val bottom = top + rows
        var bitmap = cached
        if (bitmap == null || !cachedBounds.contains(left, top, right, bottom)) {
            cached?.recycle()
            cached = null
            cachedBounds.setEmpty()
            bitmap = decode(left, top, right, bottom)
            cached = bitmap
            if (requireOpaque && bitmap.hasAlpha()) confirmOpaque(bitmap, out)
        }
        bitmap.getPixels(out, 0, span, left - cachedBounds.left, top - cachedBounds.top, span, rows)
    }

    // Inspect the decoded region once. For a resident page this runs before the first model tile.
    // Reuse the caller's tile storage so even extremely wide source rows stay bounded.
    private fun confirmOpaque(bitmap: Bitmap, scratch: IntArray) {
        val width = bitmap.width
        val blockWidth = minOf(width, scratch.size)
        val blockRows = scratch.size / blockWidth
        var top = 0
        while (top < bitmap.height) {
            checkActive()
            val rows = minOf(blockRows, bitmap.height - top)
            var left = 0
            while (left < width) {
                val span = minOf(blockWidth, width - left)
                bitmap.getPixels(scratch, 0, span, left, top, span, rows)
                for (i in 0 until span * rows) {
                    require(scratch[i] ushr 24 == 255) { "当前模型仅支持不透明像素，此页已保留带透明度的原图" }
                }
                left += span
            }
            top += rows
        }
    }

    private fun decode(left: Int, top: Int, right: Int, bottom: Int): Bitmap {
        // Ordinary JPG/PNG pages can be decoded once. Cap speculative storage on long strips.
        val cacheBytes = minOf(budget, 24L * 1024 * 1024)
        val scanlineBytes = width.toLong() * 4
        if (!triedFull && canDecodeStill && height <= cacheBytes / scanlineBytes) {
            decodeWhole()?.let { return it }
        }
        @Suppress("DEPRECATION")
        val regions = decoder ?: try {
            BitmapRegionDecoder.newInstance(file.path, false)?.also { decoder = it }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        if (regions != null) {
            // A band covers adjacent tiles and their padding with one codec request.
            val bandRows = minOf((height - top).toLong(), cacheBytes / scanlineBytes).toInt()
            if (allowBands && bandRows >= bottom - top && (left > 0 || right < width || bandRows > bottom - top)) {
                val window = Rect(0, top, width, top + bandRows)
                val band = try { region(regions, window) } catch (_: OutOfMemoryError) { null }
                if (band != null) return band
                allowBands = false
            }
            region(regions, Rect(left, top, right, bottom))?.let { return it }
        }
        // Some single-image formats have a BitmapFactory decoder but no region decoder.
        if (!triedFull && canDecodeStill && height <= budget / scanlineBytes) decodeWhole()?.let { return it }
        error(if (canDecodeStill && height > budget / scanlineBytes)
            "此格式的分块解码不可用，完整原图超出当前解码预算" else "无法解码增强所需的原图像素")
    }

    private fun decodeWhole(): Bitmap? {
        triedFull = true
        val image = try { BitmapFactory.decodeFile(file.path, options) } catch (_: OutOfMemoryError) { null }
        return keep(image, Rect(0, 0, width, height))
    }

    private fun region(decoder: BitmapRegionDecoder, window: Rect): Bitmap? {
        val image = try { decoder.decodeRegion(window, options) } catch (_: IllegalArgumentException) { null }
        return keep(image, window)
    }

    private fun keep(image: Bitmap?, window: Rect): Bitmap? {
        if (image == null) return null
        if (image.width != window.width() || image.height != window.height() || image.allocationByteCount.toLong() > budget) {
            image.recycle()
            return null
        }
        cachedBounds.set(window)
        return image
    }

    override fun close() {
        cached?.recycle()
        cached = null
        decoder?.recycle()
        decoder = null
    }
}
