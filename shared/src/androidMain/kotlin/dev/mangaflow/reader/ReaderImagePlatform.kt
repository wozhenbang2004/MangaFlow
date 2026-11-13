package dev.mangaflow.reader

import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.net.URI
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState

@Composable
internal actual fun ReaderZoomableImage(
    model: Any?, contentDescription: String?, modifier: Modifier,
    state: ReaderImageState, alignment: Alignment, contentScale: ContentScale,
    gesturesEnabled: Boolean, onClick: ((Offset) -> Unit)?,
) {
    val native = rememberZoomableImageState(state.zoomableState)
    LaunchedEffect(native) {
        snapshotFlow { native.isImageDisplayed }.collect { state.isImageDisplayed = it }
    }
    ZoomableAsyncImage(model, contentDescription, modifier, native,
        alignment = alignment, contentScale = contentScale,
        gesturesEnabled = gesturesEnabled, onClick = onClick)
}

internal actual suspend fun readNativeReaderSize(path: String): IntSize? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    val rotation = ExifInterface(File(path)).rotationDegrees
    return if (rotation == 90 || rotation == 270) IntSize(options.outHeight, options.outWidth)
        else IntSize(options.outWidth, options.outHeight)
}

internal actual fun readerFilePath(uri: String): String? =
    if (uri.startsWith("file:")) runCatching { File(URI(uri)).absolutePath }.getOrNull() else null

@Suppress("DEPRECATION")
internal actual suspend fun decodeNativeReaderRegion(path: String, bounds: IntRect, sampleSize: Int): ReaderRegion {
    val decoder = requireNotNull(BitmapRegionDecoder.newInstance(path, false)) { "原图区域暂时无法读取" }
    return try {
        ReaderRegion(requireNotNull(decoder.decodeRegion(android.graphics.Rect(bounds.left, bounds.top, bounds.right, bounds.bottom),
            BitmapFactory.Options().apply { inSampleSize = sampleSize.coerceAtLeast(1) })).asImageBitmap())
    } finally { decoder.recycle() }
}
