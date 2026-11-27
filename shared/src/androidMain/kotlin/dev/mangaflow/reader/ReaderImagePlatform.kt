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
import dev.mangaflow.experience.ExperienceRuntimePreferences
import java.io.File
import java.net.URI
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

internal actual suspend fun readNativeReaderSize(path: String, runtime: ExperienceRuntimePreferences): IntSize? {
    val file = checkedReaderFile(path, runtime)
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    val rotation = ExifInterface(file).rotationDegrees
    return if (rotation == 90 || rotation == 270) IntSize(options.outHeight, options.outWidth)
        else IntSize(options.outWidth, options.outHeight)
}

internal actual fun readerFilePath(uri: String): String? =
    if (uri.startsWith("file:")) runCatching { File(URI(uri)).absolutePath }.getOrNull() else null

internal actual fun createReaderRegionDecoder(path: String): ReaderRegionDecoder = AndroidReaderRegionDecoder(path)

internal actual suspend fun decodeNativeReaderRegion(
    path: String, bounds: IntRect, sampleSize: Int, runtime: ExperienceRuntimePreferences,
): ReaderRegion {
    val decoder = createReaderRegionDecoder(path)
    return try { decoder.decode(bounds, sampleSize, runtime) } finally { decoder.close() }
}

@Suppress("DEPRECATION")
private class AndroidReaderRegionDecoder(private val path: String) : ReaderRegionDecoder {
    private var stamp: Pair<Long, Long>? = null
    private var idle: BitmapRegionDecoder? = null
    private var closed = false

    override suspend fun decode(bounds: IntRect, sampleSize: Int, runtime: ExperienceRuntimePreferences): ReaderRegion {
        val context = currentCoroutineContext()
        context.ensureActive()
        val file = checkedReaderFile(path, runtime)
        val decoder = synchronized(this) {
            check(!closed) { "图片区域读取已结束" }
            val actual = file.length() to file.lastModified()
            if (stamp == null) {
                val orientation = ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                if (orientation != ExifInterface.ORIENTATION_NORMAL && orientation != ExifInterface.ORIENTATION_UNDEFINED)
                    throw ReaderDetailUnavailable("此图带旋转或镜像标记，当前显示预览")
                stamp = actual
            } else check(stamp == actual) { "图片文件已变化，请重新打开此页" }
            idle.also { idle = null }
        } ?: requireNotNull(BitmapRegionDecoder.newInstance(path, false)) { "图片区域暂时无法读取" }
        var reusable = false
        return try {
            context.ensureActive()
            val bitmap = requireNotNull(decoder.decodeRegion(
                android.graphics.Rect(bounds.left, bounds.top, bounds.right, bounds.bottom),
                BitmapFactory.Options().apply { inSampleSize = sampleSize.coerceAtLeast(1) },
            )) { "图片区域暂时无法读取" }
            reusable = true
            ReaderRegion(bitmap.asImageBitmap())
        } finally {
            // Keep one idle handle per displayed image. Concurrency still belongs to
            // the existing decode admission; surplus handles are released immediately.
            val kept = synchronized(this) {
                if (reusable && !closed && idle == null) { idle = decoder; true } else false
            }
            if (!kept) decoder.recycle()
        }
    }

    override fun close() {
        val decoder = synchronized(this) {
            closed = true
            idle.also { idle = null }
        }
        decoder?.recycle()
    }
}

private fun checkedReaderFile(path: String, runtime: ExperienceRuntimePreferences): File = File(path).also { file ->
    check(file.isFile && file.length() > 0L) { "原图文件暂不可用，当前显示预览" }
    check(file.length() <= runtime.maxImageBytes) { "原图压缩数据超过单图配置，当前显示预览" }
}
