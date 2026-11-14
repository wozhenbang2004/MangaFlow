@file:OptIn(me.saket.telephoto.ExperimentalTelephotoApi::class)

package dev.mangaflow.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.disk.DiskCache
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.platform.imagePlatformContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.ZoomableContent
import me.saket.telephoto.zoomable.Viewport
import me.saket.telephoto.zoomable.spatial.CoordinateSpace
import me.saket.telephoto.zoomable.spatial.SpatialOffset
import me.saket.telephoto.zoomable.zoomable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private data class ReaderTile(val source: IntRect, val sample: Int)
private data class DecodedReaderTile(val spec: ReaderTile, val bitmap: ImageBitmap, val notice: String? = null)
private data class VerticalTileWindow(val requested: List<ReaderTile> = emptyList(), val visibleCount: Int = 0)

// Share native decode capacity across images, including zoom and long-page bands.
// iOS constructs a bounded whole-image thumbnail for each region, so keep one in flight.
private val portableDecodeParallelism = if (platformKind == PlatformKind.DESKTOP) 2 else 1
private val portableDecodeSlots = Semaphore(portableDecodeParallelism)

private suspend fun decodeReaderTiles(
    source: String, requested: List<ReaderTile>, retained: MutableMap<ReaderTile, ReaderRegion>,
    onDecoded: () -> Unit, onFailure: (Exception) -> Unit,
) = coroutineScope {
    val pending = requested.filterNot { it in retained }
    val iterator = pending.iterator()
    // Workers inherit the reader effect's UI context; only native decoding leaves it.
    repeat(minOf(portableDecodeParallelism, pending.size)) {
        launch {
            while (iterator.hasNext()) {
                val tile = iterator.next()
                try {
                    val region = portableDecodeSlots.withPermit {
                        withContext(Dispatchers.IO) { decodeNativeReaderRegion(source, tile.source, tile.sample) }
                    }
                    currentCoroutineContext().ensureActive()
                    retained[tile] = region
                    onDecoded()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { onFailure(failure) }
            }
        }
    }
}

/**
 * The Painter/zoom wiring follows Telephoto 0.16.0 ZoomableImage.kt (Apache-2.0, 2a5e5f5).
 * Changes: retain its common gestures and source-coordinate tile grid on other platforms.
 * Native decoders report additional memory-budget sampling as a visible notice; they never
 * rewrite original bytes. A viewport preview remains underneath at most 32 decoded tiles.
 */
@Composable
internal fun PortableReaderZoomableImage(
    model: Any?, contentDescription: String?, modifier: Modifier, state: ReaderImageState,
    alignment: Alignment, contentScale: ContentScale, gesturesEnabled: Boolean,
    onClick: ((Offset) -> Unit)?,
) {
    val loader = SingletonImageLoader.get(imagePlatformContext(LocalMangaContext.current))
    val preview = rememberAsyncImagePainter(model, imageLoader = loader)
    val result by preview.state.collectAsState()
    val success = (result as? AsyncImagePainter.State.Success)?.result
    var path by remember(model) { mutableStateOf<String?>(null) }
    var original by remember(model) { mutableStateOf<IntSize?>(null) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val tileState = remember(model) { mutableStateOf<List<DecodedReaderTile>>(emptyList()) }
    val zoom = state.zoomableState
    LaunchedEffect(model) { state.detailError = null; state.detailNotice = null }
    LaunchedEffect(result) { state.isImageDisplayed = success != null }
    LaunchedEffect(model, success?.diskCacheKey) {
        var snapshot: DiskCache.Snapshot? = null
        try {
            val data = (model as? ImageRequest)?.data ?: model
            val local = (data as? String)?.let(::readerFilePath)
            val file = local ?: withContext(Dispatchers.IO) {
                snapshot = loader.diskCache?.openSnapshot(success?.diskCacheKey ?: return@withContext null)
                snapshot?.data?.toString()
            }
            path = file
            if (file != null) original = withContext(Dispatchers.IO) { readNativeReaderSize(file) }
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { state.detailError = "原图细节暂时无法读取" }
        finally { withContext(NonCancellable + Dispatchers.IO) { snapshot?.close() } }
    }
    val size = original ?: success?.image?.let { IntSize(it.width, it.height) }
    // Tile arrivals invalidate drawing only; the zoom content keeps one stable painter.
    val painter = remember(preview, size, tileState) { OriginalTilePainter(preview, size, tileState) }
    zoom.contentScale = contentScale
    zoom.contentAlignment = alignment
    zoom.setContentLocation(ZoomableContentLocation.scaledInsideAndCenterAligned(painter.intrinsicSize))
    LaunchedEffect(path, original, zoom) {
        val file = path ?: return@LaunchedEffect
        val imageSize = original ?: return@LaunchedEffect
        val retained = mutableMapOf<ReaderTile, ReaderRegion>()
        snapshotFlow {
            if (viewport.width <= 0 || viewport.height <= 0 || !state.isImageDisplayed) emptyList()
            else runCatching {
                with(zoom.coordinateSystem) {
                    val bounds = unscaledContentBounds.rectIn(CoordinateSpace.ZoomableContent)
                    if (bounds.width <= 0f || bounds.height <= 0f) emptyList()
                    else {
                        val a = SpatialOffset(Offset.Zero, CoordinateSpace.Viewport).offsetIn(CoordinateSpace.ZoomableContent)
                        val b = SpatialOffset(Offset(viewport.width.toFloat(), viewport.height.toFloat()),
                            CoordinateSpace.Viewport).offsetIn(CoordinateSpace.ZoomableContent)
                        val left = ((a.x - bounds.left) / bounds.width * imageSize.width).coerceIn(0f, imageSize.width.toFloat())
                        val top = ((a.y - bounds.top) / bounds.height * imageSize.height).coerceIn(0f, imageSize.height.toFloat())
                        val right = ((b.x - bounds.left) / bounds.width * imageSize.width).coerceIn(left, imageSize.width.toFloat())
                        val bottom = ((b.y - bounds.top) / bounds.height * imageSize.height).coerceIn(top, imageSize.height.toFloat())
                        val ratio = maxOf((right - left) / viewport.width, (bottom - top) / viewport.height)
                        var sample = 1
                        while (sample < 128 && sample * 2 <= ratio) sample *= 2
                        originalTiles(imageSize, left, top, right, bottom, sample)
                    }
                }
            }.getOrDefault(emptyList())
        }.distinctUntilChanged().collectLatest { requested ->
            retained.keys.retainAll(requested.toSet())
            tileState.value = retained.map { DecodedReaderTile(it.key, it.value.bitmap, it.value.notice) }
            decodeReaderTiles(file, requested, retained, onDecoded = {
                tileState.value = retained.map { DecodedReaderTile(it.key, it.value.bitmap, it.value.notice) }
                state.detailNotice = retained.values.firstNotNullOfOrNull { it.notice }
            }, onFailure = { failure ->
                state.detailError = failure.message?.takeIf { "当前显示预览" in it }
                    ?: "原图细节暂时无法读取，当前显示预览"
            })
            if (requested.isNotEmpty() && requested.all { it in retained }) {
                state.detailError = null
                state.detailNotice = retained.values.firstNotNullOfOrNull { it.notice }
            }
        }
    }
    Image(painter, contentDescription,
        modifier.onSizeChanged { viewport = it }.zoomable(zoom, enabled = gesturesEnabled, onClick = onClick),
        alignment = Alignment.Center, contentScale = ContentScale.Inside)
}

private fun originalTiles(size: IntSize, left: Float, top: Float, right: Float, bottom: Float, sample: Int): List<ReaderTile> {
    if (right <= left || bottom <= top) return emptyList()
    val step = 512 * sample
    val firstX = (floor(left / step).toInt() - 1).coerceAtLeast(0)
    val firstY = (floor(top / step).toInt() - 1).coerceAtLeast(0)
    val lastX = (floor((right - 1).coerceAtLeast(left) / step).toInt() + 1).coerceAtMost((size.width - 1) / step)
    val lastY = (floor((bottom - 1).coerceAtLeast(top) / step).toInt() + 1).coerceAtMost((size.height - 1) / step)
    val visibleFirstX = floor(left / step).toInt().coerceIn(firstX, lastX)
    val visibleFirstY = floor(top / step).toInt().coerceIn(firstY, lastY)
    val visibleLastX = floor((right - 1).coerceAtLeast(left) / step).toInt().coerceIn(visibleFirstX, lastX)
    val visibleLastY = floor((bottom - 1).coerceAtLeast(top) / step).toInt().coerceIn(visibleFirstY, lastY)
    return buildList {
        fun append(x: Int, y: Int) {
            if (this.size < 32) add(ReaderTile(IntRect(x * step, y * step,
                minOf((x + 1) * step, size.width), minOf((y + 1) * step, size.height)), sample))
        }
        // Keep order stable inside each tile window, so subpixel panning does not restart decodes.
        for (y in visibleFirstY..visibleLastY) for (x in visibleFirstX..visibleLastX) {
            if (this.size >= 32) return@buildList
            append(x, y)
        }
        for (y in firstY..lastY) for (x in firstX..lastX) {
            if (this.size >= 32) return@buildList
            if (x !in visibleFirstX..visibleLastX || y !in visibleFirstY..visibleLastY) append(x, y)
        }
    }
}

private class OriginalTilePainter(
    private val preview: Painter, private val original: IntSize?, private val tiles: State<List<DecodedReaderTile>>,
) : Painter() {
    override val intrinsicSize: Size get() = original?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: preview.intrinsicSize
    override fun DrawScope.onDraw() {
        with(preview) { draw(size = size) }
        val full = original ?: return
        if (full.width <= 0 || full.height <= 0) return
        for (tile in tiles.value) {
            val rect = tile.spec.source
            val left = (rect.left.toFloat() / full.width * size.width).roundToInt()
            val top = (rect.top.toFloat() / full.height * size.height).roundToInt()
            val right = (rect.right.toFloat() / full.width * size.width).roundToInt()
            val bottom = (rect.bottom.toFloat() / full.height * size.height).roundToInt()
            drawImage(tile.bitmap, dstOffset = IntOffset(left, top), dstSize = IntSize((right - left).coerceAtLeast(1),
                (bottom - top).coerceAtLeast(1)))
        }
    }
}

/** Same source-page bands as Android; only the native region decoder differs. */
@Composable
internal fun PortableVerticalReaderTiles(
    page: dev.mangaflow.data.Page, diskCacheKey: String?, imageSize: IntSize,
    displayWidth: Int, displayHeight: Int, visibleWindow: () -> VerticalPageWindow?,
    onDetailStatus: (ReaderDetailStatus?) -> Unit,
) {
    val loader = SingletonImageLoader.get(imagePlatformContext(LocalMangaContext.current))
    var file by remember(page.url, diskCacheKey) { mutableStateOf<String?>(null) }
    var decoded by remember(page.url) { mutableStateOf<List<DecodedReaderTile>>(emptyList()) }
    val liveWindow by rememberUpdatedState(visibleWindow)
    val reportStatus by rememberUpdatedState(onDetailStatus)
    LaunchedEffect(page.url, diskCacheKey, loader) {
        var snapshot: DiskCache.Snapshot? = null
        try {
            file = readerFilePath(page.url) ?: withContext(Dispatchers.IO) {
                snapshot = loader.diskCache?.openSnapshot(diskCacheKey ?: page.url)
                snapshot?.data?.toString()
            }
            if (file == null) reportStatus(ReaderDetailStatus("原图缓存暂不可用，当前显示预览", true))
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { reportStatus(ReaderDetailStatus(failure.message ?: "原图细节暂时无法读取，当前显示预览", true)) }
        finally { withContext(NonCancellable + Dispatchers.IO) { snapshot?.close() } }
    }
    LaunchedEffect(file, imageSize, displayWidth, displayHeight) {
        val source = file ?: return@LaunchedEffect
        if (imageSize.width <= 0 || imageSize.height <= 0 || displayHeight <= 0) return@LaunchedEffect
        val scale = imageSize.height.toDouble() / displayHeight
        // libwebp's native crop origin is even-aligned; keep all platform band seams exact.
        val sourceBand = maxOf(2, (1024 * scale).roundToInt() / 2 * 2)
        val count = ceil(imageSize.height.toDouble() / sourceBand).toInt()
        val retained = mutableMapOf<ReaderTile, ReaderRegion>()
        snapshotFlow {
            val window = liveWindow()
            if (window == null || window.bottom <= window.top) VerticalTileWindow()
            else {
                val first = floor(window.top * scale / sourceBand).toInt().coerceIn(0, count - 1)
                val last = floor((window.bottom - 1) * scale / sourceBand).toInt().coerceIn(first, count - 1)
                var sample = 1
                while (sample < 128 && sample * 2 <= imageSize.width.toFloat() / displayWidth.coerceAtLeast(1)) sample *= 2
                // Decode the area being read before warming either neighboring band.
                val bands = buildList {
                    addAll(first..last)
                    if (last < count - 1) add(last + 1)
                    if (first > 0) add(first - 1)
                }
                VerticalTileWindow(bands.map { band -> ReaderTile(IntRect(0, band * sourceBand, imageSize.width,
                    minOf((band + 1) * sourceBand, imageSize.height)), sample) }, last - first + 1)
            }
        }.distinctUntilChanged().collectLatest { (requested, visibleCount) ->
            retained.keys.retainAll(requested.toSet())
            val publishTiles = { decoded = retained.map { DecodedReaderTile(it.key, it.value.bitmap, it.value.notice) } }
            val reportFailure = { failure: Exception ->
                reportStatus(ReaderDetailStatus(failure.message ?: "原图细节暂时无法读取，当前显示预览", true))
            }
            publishTiles()
            // Complete the visible batch before spending capacity on neighboring bands.
            decodeReaderTiles(source, requested.take(visibleCount), retained, publishTiles, reportFailure)
            decodeReaderTiles(source, requested.drop(visibleCount), retained, publishTiles, reportFailure)
            if (requested.isNotEmpty() && requested.all { it in retained }) {
                reportStatus(retained.values.firstNotNullOfOrNull { it.notice }?.let { ReaderDetailStatus(it, false) })
            }
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val width = minOf(displayWidth.toFloat(), displayHeight.toFloat() * imageSize.width / imageSize.height)
        val left = ((displayWidth - width) / 2).roundToInt()
        for (tile in decoded) {
            val top = (tile.spec.source.top.toDouble() / imageSize.height * displayHeight).roundToInt()
            val bottom = (tile.spec.source.bottom.toDouble() / imageSize.height * displayHeight).roundToInt()
            drawImage(tile.bitmap, dstOffset = IntOffset(left, top), dstSize = IntSize(width.roundToInt().coerceAtLeast(1),
                (bottom - top).coerceAtLeast(1)))
        }
    }
}
