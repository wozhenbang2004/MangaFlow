package dev.mangaflow.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import coil3.disk.DiskCache
import coil3.imageLoader
import dev.mangaflow.data.Page
import java.io.File
import java.io.IOException
import java.net.URI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import me.saket.telephoto.subsamplingimage.SubSamplingImage
import me.saket.telephoto.subsamplingimage.SubSamplingImageErrorReporter
import me.saket.telephoto.subsamplingimage.SubSamplingImageSource
import me.saket.telephoto.subsamplingimage.internal.ImageRegionDecoder
import me.saket.telephoto.subsamplingimage.rememberSubSamplingImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import okio.Path
import okio.Path.Companion.toPath

internal data class VerticalPageWindow(val top: Int, val bottom: Int)

/**
 * Adapt Telephoto 0.16.0 (2a5e5f5)'s public custom-source/region-decoder API to an outer LazyColumn.
 * Full-height SubSamplingImage would consider the entire strip visible. Fixed source bands let the
 * existing engine decode only the actual viewport and one neighboring band, without adding gestures.
 * The original image remains one manga page, with its ordinary saved pixel offset.
 */
@Composable
internal fun VerticalReaderTiles(
    page: Page,
    diskCacheKey: String?,
    imageSize: IntSize,
    displayWidth: Int,
    displayHeight: Int,
    visibleWindow: () -> VerticalPageWindow?,
) {
    val loader = LocalContext.current.imageLoader
    var path by remember(page.url, diskCacheKey) { mutableStateOf<Path?>(null) }
    LaunchedEffect(loader, page.url, diskCacheKey) {
        var snapshot: DiskCache.Snapshot? = null
        try {
            path = withContext(Dispatchers.IO) {
                if (page.url.startsWith("file:")) {
                    File(URI(page.url)).takeIf { it.isFile && it.length() > 0 }?.absolutePath?.toPath()
                } else {
                    snapshot = loader.diskCache?.openSnapshot(diskCacheKey ?: page.url)
                    snapshot?.data
                }
            }
            // Pin cached bytes until all bands leave. No extra request or temporary copy is needed.
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // The existing viewport-sized preview remains available if its cache file disappeared.
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { snapshot?.close() }
        }
    }
    val file = path ?: return
    var failed by remember(file) { mutableStateOf(false) }
    if (failed || imageSize.width <= 0 || imageSize.height <= 0 || displayHeight <= 0) return
    val window by rememberUpdatedState(visibleWindow)
    val density = LocalDensity.current
    val drawWidth = minOf(displayWidth, (displayHeight.toDouble() * imageSize.width / imageSize.height).roundToInt())
        .coerceAtLeast(1)
    val left = (displayWidth - drawWidth) / 2
    val sourceBandHeight = maxOf(1, (1024.0 * imageSize.height / displayHeight).roundToInt())
    val bandCount = ceil(imageSize.height.toDouble() / sourceBandHeight).toInt()
    val displayBandHeight = sourceBandHeight.toDouble() * displayHeight / imageSize.height
    val visibleBands by remember(file, sourceBandHeight, bandCount, displayBandHeight) {
        derivedStateOf {
            val visible = window()
            if (visible == null || visible.bottom <= visible.top) IntRange.EMPTY
            else {
                val first = (floor(visible.top / displayBandHeight).toInt() - 1).coerceIn(0, bandCount - 1)
                val last = (floor((visible.bottom - 1) / displayBandHeight).toInt() + 1).coerceIn(first, bandCount - 1)
                first..last
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        for (band in visibleBands) key(file, band, sourceBandHeight) {
            val top = band * sourceBandHeight
            val bottom = minOf(top + sourceBandHeight, imageSize.height)
            val y = (top.toDouble() * displayHeight / imageSize.height).roundToInt()
            val end = (bottom.toDouble() * displayHeight / imageSize.height).roundToInt()
            val source = remember(file, top, bottom, imageSize.width) {
                ReaderBandSource(file, IntRect(0, top, imageSize.width, bottom)) { failed = true }
            }
            val zoom = rememberZoomableState().apply {
                contentScale = ContentScale.FillBounds
                contentAlignment = Alignment.TopStart
            }
            val reporter = remember(source) {
                object : SubSamplingImageErrorReporter {
                    override fun onImageLoadingFailed(e: IOException, imageSource: SubSamplingImageSource) {
                        failed = true
                    }
                }
            }
            val state = rememberSubSamplingImageState(source, zoom, errorReporter = reporter)
            SubSamplingImage(state, contentDescription = null,
                modifier = Modifier.width(with(density) { drawWidth.toDp() }).offset { IntOffset(left, y) }
                    .height(with(density) { (end - y).coerceAtLeast(1).toDp() })
                    .zoomable(zoom, enabled = false, onDoubleClick = null))
        }
    }
}

/** The upstream decoder owns format, sampling, EXIF orientation, pooling and cancellation. */
private class ReaderBandSource(
    private val file: Path,
    private val bounds: IntRect,
    private val onFailure: () -> Unit,
) : SubSamplingImageSource {
    override val preview: ImageBitmap? = null

    override suspend fun decoder(): ImageRegionDecoder.Factory = ImageRegionDecoder.Factory { params ->
        val delegate = try { SubSamplingImageSource.file(file).decoder().create(params) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { throw IOException("长图区域暂时无法读取", failure) }
        if (bounds.right > delegate.imageSize.width || bounds.bottom > delegate.imageSize.height) {
            delegate.close()
            throw IOException("长图尺寸已变化")
        }
        object : ImageRegionDecoder {
            override val imageSize = bounds.size
            override suspend fun decodeRegion(region: IntRect, sampleSize: Int): ImageRegionDecoder.DecodeResult =
                try {
                    delegate.decodeRegion(IntRect(region.left + bounds.left, region.top + bounds.top,
                        region.right + bounds.left, region.bottom + bounds.top), sampleSize)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    onFailure()
                    ImageRegionDecoder.DecodeResult(ColorPainter(Color.Transparent), hasUltraHdrContent = false)
                }
            override fun close() = delegate.close()
        }
    }
}
