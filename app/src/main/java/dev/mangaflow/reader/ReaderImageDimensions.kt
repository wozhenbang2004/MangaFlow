package dev.mangaflow.reader

import android.graphics.BitmapFactory
import androidx.compose.ui.unit.IntSize
import androidx.exifinterface.media.ExifInterface
import coil3.ImageLoader
import dev.mangaflow.data.Page
import java.io.File
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read headers from bytes we already have; never fetch an image just to measure it. */
internal suspend fun readerImageDimensions(
    imageLoader: ImageLoader,
    page: Page,
    diskCacheKey: String? = page.url,
): IntSize? = withContext(Dispatchers.IO) {
    try {
        if (page.url.startsWith("file:")) {
            imageDimensions(File(URI(page.url)))
        } else {
            diskCacheKey?.let { key ->
                imageLoader.diskCache?.openSnapshot(key)?.use { snapshot ->
                    imageDimensions(snapshot.data.toFile())
                }
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun imageDimensions(file: File): IntSize? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val rotation = ExifInterface(file).rotationDegrees
    return if (rotation == 90 || rotation == 270) IntSize(bounds.outHeight, bounds.outWidth)
        else IntSize(bounds.outWidth, bounds.outHeight)
}
