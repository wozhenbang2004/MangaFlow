@file:OptIn(me.saket.telephoto.ExperimentalTelephotoApi::class)

package dev.mangaflow.enhancement

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Scale
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.experience.readerPriority
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.platform.storage.StorageLock
import dev.mangaflow.reader.ReaderDetailUnavailable
import dev.mangaflow.reader.ReaderSpreadViewport
import dev.mangaflow.reader.VerticalPageWindow
import dev.mangaflow.reader.decodeNativeReaderRegion
import kotlinx.coroutines.*
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.Viewport
import me.saket.telephoto.zoomable.ZoomableContent
import me.saket.telephoto.zoomable.spatial.CoordinateSpace
import me.saket.telephoto.zoomable.spatial.SpatialOffset
import kotlin.math.floor
import kotlin.math.roundToInt

internal data class ReaderEnhancementPresentation(
    val controller: ReaderEnhancementController,
    val state: ReaderEnhancementState,
    /** Null displays the complete enhanced image; otherwise the left side remains original. */
    val comparisonFraction: Float? = null,
)
internal val LocalReaderEnhancementPresentation = staticCompositionLocalOf<ReaderEnhancementPresentation?> { null }

private data class EnhancedTile(val bounds: IntRect, val sample: Int)
private data class EnhancedTileBitmap(val tile: EnhancedTile, val bitmap: ImageBitmap)

/**
 * Draws into the ORIGINAL page's viewport/camera. It never changes its request,
 * intrinsic dimensions, zoom state, lazy-list key or scroll anchor. The original
 * remains underneath during loading, cancellation and any tile/decoder failure.
 */
@Composable
internal fun ReaderEnhancedLayer(
    asset: ReaderEnhancedPage?,
    modifier: Modifier,
    alignment: Alignment = Alignment.Center,
    zoom: ZoomableState? = null,
    spread: ReaderSpreadViewport? = null,
    visibleWindow: (() -> VerticalPageWindow?)? = null,
    comparisonFraction: Float? = null,
) {
    if (asset == null) return
    val context = LocalMangaContext.current
    val cache = remember(context) { EnhancedAssetCache.get(context) }
    val policy = remember(context) { ExperiencePolicyStore.get(context) }
    var lease by remember(asset.identity) { mutableStateOf<EnhancedAssetLease?>(null) }
    LaunchedEffect(cache, asset.identity) {
        var held: EnhancedAssetLease? = null
        try {
            held = cache.acquire(asset.identity)
            lease = held
            if (held != null) awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { lease = null }
        finally { held?.close(); lease = null }
    }
    val liveLease = lease ?: return
    val request = remember(liveLease) {
        val reads = mutableListOf<AutoCloseable>()
        val readsLock = StorageLock()
        fun finishRead() = synchronized(readsLock) { if (reads.isNotEmpty()) reads.removeAt(0).close() }
        ImageRequest.Builder(imagePlatformContext(context)).data(liveLease.file.toURI())
            .readerPriority(asset.identity.sourceId).memoryCacheKey("enhanced:${asset.identity.key}")
            .diskCachePolicy(CachePolicy.DISABLED).networkCachePolicy(CachePolicy.DISABLED)
            .size(1600, 1600).scale(Scale.FIT)
            .listener(onStart = { synchronized(readsLock) { reads += liveLease.retain() } },
                onCancel = { finishRead() }, onSuccess = { _, _ -> finishRead() }, onError = { _, _ -> finishRead() })
            .build()
    }
    val preview = rememberAsyncImagePainter(request)
    val previewState by preview.state.collectAsState()
    val ready = previewState is AsyncImagePainter.State.Success
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var tiles by remember(asset.identity) { mutableStateOf<List<EnhancedTileBitmap>>(emptyList()) }
    val fraction by rememberUpdatedState(comparisonFraction)
    val liveWindow by rememberUpdatedState(visibleWindow)
    val image = IntSize(asset.width, asset.height)

    fun drawingBounds(): Rect {
        if (viewport.width <= 0 || viewport.height <= 0) return Rect.Zero
        if (zoom != null && spread == null) return runCatching {
            with(zoom.coordinateSystem) { unscaledContentBounds.rectIn(CoordinateSpace.Viewport) }
        }.getOrDefault(Rect.Zero)
        val fit = minOf(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
        val drawn = IntSize((image.width * fit).roundToInt(), (image.height * fit).roundToInt())
        val offset = alignment.align(drawn, viewport, LayoutDirection.Ltr)
        return Rect(offset.x.toFloat(), offset.y.toFloat(), (offset.x + drawn.width).toFloat(), (offset.y + drawn.height).toFloat())
    }

    LaunchedEffect(liveLease, zoom, spread, ready, viewport, alignment) {
        if (!ready || viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
        val retained = mutableMapOf<EnhancedTile, ImageBitmap>()
        snapshotFlow {
            val drawn = drawingBounds()
            if (drawn.width <= 0 || drawn.height <= 0) emptyList()
            else {
                val visible = if (spread != null && spread.viewport.width > 0 && spread.viewport.height > 0) runCatching {
                    // The spread has one outer zoom transform; map its viewport back into this page's slot.
                    with(spread.zoom.coordinateSystem) {
                        val bounds = unscaledContentBounds.rectIn(CoordinateSpace.ZoomableContent)
                        val start = SpatialOffset(Offset.Zero, CoordinateSpace.Viewport).offsetIn(CoordinateSpace.ZoomableContent)
                        val end = SpatialOffset(Offset(spread.viewport.width.toFloat(), spread.viewport.height.toFloat()),
                            CoordinateSpace.Viewport).offsetIn(CoordinateSpace.ZoomableContent)
                        Rect((start.x - bounds.left) / bounds.width * spread.viewport.width - spread.slot.left,
                            (start.y - bounds.top) / bounds.height * spread.viewport.height - spread.slot.top,
                            (end.x - bounds.left) / bounds.width * spread.viewport.width - spread.slot.left,
                            (end.y - bounds.top) / bounds.height * spread.viewport.height - spread.slot.top)
                    }
                }.getOrDefault(Rect.Zero) else liveWindow?.invoke()?.let {
                    Rect(0f, it.top.toFloat(), viewport.width.toFloat(), it.bottom.toFloat())
                } ?: Rect(0f, 0f, viewport.width.toFloat(), viewport.height.toFloat())
                val overlap = visible.intersect(drawn)
                if (overlap.width <= 0 || overlap.height <= 0) emptyList() else {
                    val source = Rect(((overlap.left - drawn.left) / drawn.width * image.width).coerceIn(0f, image.width.toFloat()),
                        ((overlap.top - drawn.top) / drawn.height * image.height).coerceIn(0f, image.height.toFloat()),
                        ((overlap.right - drawn.left) / drawn.width * image.width).coerceIn(0f, image.width.toFloat()),
                        ((overlap.bottom - drawn.top) / drawn.height * image.height).coerceIn(0f, image.height.toFloat()))
                    val ratio = if (spread == null) image.width / drawn.width else
                        source.width / spread.viewport.width.coerceAtLeast(1)
                    var sample = 1
                    while (sample < 128 && sample * 2 <= ratio) sample *= 2
                    enhancedTiles(image, source, sample)
                }
            }
        }.distinctUntilChanged().collectLatest { requested ->
            retained.keys.retainAll(requested.toSet())
            tiles = retained.map { EnhancedTileBitmap(it.key, it.value) }
            for (tile in requested) {
                if (tile in retained) continue
                try {
                    val decoderLease = liveLease.retain()
                    try {
                    val region = policy.withTileDecodeAdmission {
                        withContext(Dispatchers.IO) {
                            decodeNativeReaderRegion(liveLease.file.path, tile.bounds, tile.sample, policy.state.value.runtime)
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    retained[tile] = region.bitmap
                    tiles = retained.map { EnhancedTileBitmap(it.key, it.value) }
                    } finally { decoderLease.close() }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: ReaderDetailUnavailable) { break }
                catch (_: Exception) { break }
            }
        }
    }
    Canvas(modifier.onSizeChanged { viewport = it }) {
        if (!ready) return@Canvas
        val bounds = drawingBounds()
        if (bounds.width <= 0 || bounds.height <= 0) return@Canvas
        val divider = fraction?.coerceIn(0f, 1f)?.times(size.width) ?: 0f
        clipRect(left = divider, top = 0f, right = size.width, bottom = size.height) {
            translate(bounds.left, bounds.top) {
                with(preview) { draw(Size(bounds.width, bounds.height)) }
                tiles.forEach { decoded ->
                    val rect = decoded.tile.bounds
                    val left = rect.left.toFloat() / image.width * bounds.width
                    val top = rect.top.toFloat() / image.height * bounds.height
                    val right = rect.right.toFloat() / image.width * bounds.width
                    val bottom = rect.bottom.toFloat() / image.height * bounds.height
                    drawImage(decoded.bitmap, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                        dstSize = IntSize((right - left).roundToInt().coerceAtLeast(1),
                            (bottom - top).roundToInt().coerceAtLeast(1)))
                }
            }
        }
    }
}

private fun enhancedTiles(size: IntSize, bounds: Rect, sample: Int): List<EnhancedTile> {
    if (bounds.width <= 0 || bounds.height <= 0) return emptyList()
    val step = 512 * sample
    val firstX = floor(bounds.left / step).toInt().coerceAtLeast(0)
    val firstY = floor(bounds.top / step).toInt().coerceAtLeast(0)
    val lastX = floor((bounds.right - 1).coerceAtLeast(bounds.left) / step).toInt().coerceAtMost((size.width - 1) / step)
    val lastY = floor((bounds.bottom - 1).coerceAtLeast(bounds.top) / step).toInt().coerceAtMost((size.height - 1) / step)
    return buildList {
        for (y in firstY..lastY) for (x in firstX..lastX) {
            if (this.size >= 32) return@buildList
            add(EnhancedTile(IntRect(x * step, y * step, minOf((x + 1) * step, size.width),
                minOf((y + 1) * step, size.height)), sample))
        }
    }
}
