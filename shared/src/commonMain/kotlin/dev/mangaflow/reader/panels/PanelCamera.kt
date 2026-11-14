@file:OptIn(me.saket.telephoto.ExperimentalTelephotoApi::class)

package dev.mangaflow.reader.panels

import dev.mangaflow.experience.readerPriority

import androidx.compose.animation.core.SnapSpec
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import dev.mangaflow.platform.LocalMangaContext as LocalContext
import dev.mangaflow.platform.imagePlatformContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Scale
import dev.mangaflow.data.Page
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import me.saket.telephoto.zoomable.Viewport
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContent
import me.saket.telephoto.zoomable.ZoomableState
import dev.mangaflow.reader.ReaderZoomableImage as ZoomableAsyncImage
import dev.mangaflow.reader.rememberReaderImageState as rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.spatial.CoordinateSpace
import me.saket.telephoto.zoomable.spatial.SpatialOffset

/**
 * Camera glue over Telephoto 0.16.0's original-image/tile pipeline. Detection coordinates never
 * become an image crop. One finite camera job owns both scale and centre and can be interrupted.
 * Exact upstream APIs and the preview-to-original coordinate handoff are recorded in
 * third_party/telephoto/ADAPTATION.md.
 */
@Composable
internal fun PanelCamera(
    page: Page,
    headers: Map<String, String>,
    box: PanelBox,
    overview: Boolean,
    active: Boolean,
    onTap: (Float) -> Unit,
    onImageReady: (Boolean) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    onSwipe: (deltaX: Float) -> Unit = {},
    boxes: List<PanelBox> = emptyList(),
    selectedIndex: Int = 0,
    onSelectPanel: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val motion = LocalMotionEnabled.current
    val zoom = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 8f))
    val image = rememberZoomableImageState(zoom)
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var sourceBounds by remember(page.url) { mutableStateOf<Rect?>(null) }
    var failure by remember(page.url) { mutableStateOf<String?>(null) }
    val tap by rememberUpdatedState(onTap)
    val swipe by rememberUpdatedState(onSwipe)
    val ready by rememberUpdatedState(onImageReady)
    val reportError by rememberUpdatedState(onError)
    val select by rememberUpdatedState(onSelectPanel)
    val displayedBoxes by rememberUpdatedState(boxes)
    // A newly selected target releases a prior manual pan/zoom, while a gesture always wins
    // over an in-flight camera. A viewport/source change establishes a fresh coordinate frame.
    var manualControl by remember(page.url, box, overview, viewport, sourceBounds) { mutableStateOf(false) }
    val swipeThreshold = with(LocalDensity.current) { 56.dp.toPx() }

    @Suppress("DEPRECATION") // 0.16 exposes the actual decoder/painter coordinate size here.
    val geometry by remember(image, zoom, viewport) {
        derivedStateOf {
            val transformation = zoom.contentTransformation
            if (!image.isImageDisplayed || !transformation.isSpecified || viewport == IntSize.Zero) null
            else transformation.contentSize to transformation.scaleMetadata.initialScale
        }
    }
    LaunchedEffect(page.url, geometry, viewport) {
        if (geometry == null) {
            sourceBounds = null
            return@LaunchedEffect
        }
        // Painter previews and the original decoder use different unscaled coordinate frames.
        // At Fit the entire source is visible, so the public coordinate-system conversion gives
        // its complete bounds; taking contentBounds while zoomed would capture only a viewport.
        zoom.resetZoom(SnapSpec())
        sourceBounds = with(zoom.coordinateSystem) {
            unscaledContentBounds.rectIn(CoordinateSpace.ZoomableContent)
        }.takeIf { it.width > 0f && it.height > 0f }
    }
    LaunchedEffect(page.url, image) {
        snapshotFlow { image.isImageDisplayed }.distinctUntilChanged().collect { ready(it) }
    }
    DisposableEffect(page.url) { onDispose { ready(false) } }
    LaunchedEffect(failure) { failure?.let { reportError(it) } }
    LaunchedEffect(image.detailError) { image.detailError?.let { reportError(it) } }
    LaunchedEffect(page.url, box, overview, sourceBounds, viewport, geometry, active, motion, manualControl) {
        val bounds = sourceBounds ?: return@LaunchedEffect
        if (!active || manualControl || !image.isImageDisplayed) return@LaunchedEffect
        try {
            zoom.movePanelCamera(bounds, box, overview, viewport, motion)
        } catch (cancelled: CancellationException) {
            // A new panel, viewport, manual gesture or inactive reader owns the next pose.
            throw cancelled
        }
    }

    val request = remember(page.url, headers, viewport, context) {
        ImageRequest.Builder(imagePlatformContext(context))
            .data(page.url).readerPriority()
            .httpHeaders(NetworkHeaders.Builder().apply {
                headers.forEach { (name, value) -> set(name, value) }
            }.build())
            .size(viewport.width.coerceIn(1, 1600), viewport.height.coerceIn(1, 4096))
            .scale(Scale.FIT)
            .crossfade(false)
            .listener(onError = { _, result -> failure = result.throwable.message ?: "原图暂时无法读取" })
            .build()
    }
    Box(modifier.fillMaxSize().clipToBounds().onSizeChanged { viewport = it }
        .pointerInput(page.url, active, overview, box, viewport, sourceBounds) {
            if (!active) return@pointerInput
            awaitEachGesture {
                val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                manualControl = true
                val origin = first.position
                var last = origin
                var multiplePointers = false
                var horizontal = false
                var finished = false
                while (!finished) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed || it.previousPressed } > 1) multiplePointers = true
                    val pointer = event.changes.firstOrNull { it.id == first.id }
                    if (pointer == null) break
                    last = pointer.position
                    val delta = last - origin
                    if (!multiplePointers && !horizontal && abs(delta.x) > viewConfiguration.touchSlop &&
                        abs(delta.x) > abs(delta.y) * 1.3f) horizontal = true
                    if (horizontal && !multiplePointers) event.changes.forEach { it.consume() }
                    finished = event.changes.none { it.pressed }
                }
                val delta = last - origin
                if (!multiplePointers && horizontal && abs(delta.x) >= swipeThreshold &&
                    abs(delta.x) > abs(delta.y) * 1.3f) swipe(delta.x)
            }
        }) {
        if (viewport.width > 0 && viewport.height > 0) ZoomableAsyncImage(
            model = request,
            state = image,
            contentDescription = "第 ${page.index + 1} 页${if (overview) "，分镜概览" else "，当前分镜"}",
            modifier = Modifier.fillMaxSize(),
            alignment = Alignment.Center,
            contentScale = ContentScale.Fit,
            gesturesEnabled = active,
            onClick = { point ->
                if (active) {
                    val bounds = sourceBounds
                    val hit = if (overview && bounds != null) {
                        val source = with(zoom.coordinateSystem) {
                            SpatialOffset(point, CoordinateSpace.Viewport).offsetIn(CoordinateSpace.ZoomableContent)
                        }
                        val x = (source.x - bounds.left) / bounds.width
                        val y = (source.y - bounds.top) / bounds.height
                        // The smallest containing panel wins when the detector returned inset art.
                        displayedBoxes.indices.filter { index ->
                            val candidate = displayedBoxes[index]
                            x in candidate.left..candidate.right && y in candidate.top..candidate.bottom
                        }.minByOrNull { index ->
                            val candidate = displayedBoxes[index]
                            (candidate.right - candidate.left) * (candidate.bottom - candidate.top)
                        }
                    } else null
                    if (hit != null) select(hit)
                    else tap((point.x / viewport.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                }
            },
        )
        if (overview && sourceBounds != null && image.isImageDisplayed) Canvas(Modifier.fillMaxSize()) {
            val bounds = sourceBounds ?: return@Canvas
            displayedBoxes.forEachIndexed { index, panel ->
                val topLeft = with(zoom.coordinateSystem) {
                    SpatialOffset(Offset(bounds.left + panel.left * bounds.width,
                        bounds.top + panel.top * bounds.height), CoordinateSpace.ZoomableContent)
                        .offsetIn(CoordinateSpace.Viewport)
                }
                val bottomRight = with(zoom.coordinateSystem) {
                    SpatialOffset(Offset(bounds.left + panel.right * bounds.width,
                        bounds.top + panel.bottom * bounds.height), CoordinateSpace.ZoomableContent)
                        .offsetIn(CoordinateSpace.Viewport)
                }
                val selected = index == selectedIndex
                val panelSize = Size((bottomRight.x - topLeft.x).coerceAtLeast(0f),
                    (bottomRight.y - topLeft.y).coerceAtLeast(0f))
                drawRect(Color.Black.copy(alpha = if (selected) .65f else .30f), topLeft, panelSize,
                    style = Stroke(if (selected) 3.dp.toPx() else 2.dp.toPx()))
                drawRect(Color.White.copy(alpha = if (selected) .95f else .65f), topLeft, panelSize,
                    style = Stroke(if (selected) 1.5.dp.toPx() else .75.dp.toPx()))
            }
        }
    }
}

/** A finite two-dimensional move; every frame updates the real tiled-image viewport. */
private suspend fun ZoomableState.movePanelCamera(
    source: Rect,
    panel: PanelBox,
    overview: Boolean,
    viewport: IntSize,
    animated: Boolean,
) {
    if (viewport.width <= 0 || viewport.height <= 0) return
    val viewportCenter = Offset(viewport.width / 2f, viewport.height / 2f)
    val base = contentTransformation.scaleMetadata.initialScale
    val minimumScale = max(base.scaleX, base.scaleY)
    val panelWidth = (panel.right - panel.left).coerceIn(.01f, 1f) * source.width
    val panelHeight = (panel.bottom - panel.top).coerceIn(.01f, 1f) * source.height
    val targetScale = if (overview) minimumScale else
        max(minimumScale, min(viewport.width * .94f / panelWidth,
            viewport.height * .94f / panelHeight)).coerceAtMost(max(zoomSpec.maximum.factor, minimumScale))
    val targetCenter = if (overview) source.center else Offset(
        source.left + (panel.left + panel.right) * .5f * source.width,
        source.top + (panel.top + panel.bottom) * .5f * source.height,
    )
    val initialScale = contentTransformation.scale.scaleX
    val initialCenter = with(coordinateSystem) {
        SpatialOffset(viewportCenter, CoordinateSpace.Viewport).offsetIn(CoordinateSpace.ZoomableContent)
    }
    suspend fun applyPose(fraction: Float) {
        val scale = initialScale + (targetScale - initialScale) * fraction
        val center = initialCenter + (targetCenter - initialCenter) * fraction
        zoomTo(scale, centroid = viewportCenter, animationSpec = SnapSpec())
        val mapped = with(coordinateSystem) {
            SpatialOffset(center, CoordinateSpace.ZoomableContent).offsetIn(CoordinateSpace.Viewport)
        }
        panBy(viewportCenter - mapped, animationSpec = SnapSpec())
    }
    if (!animated) {
        applyPose(1f)
        return
    }
    val start = withFrameNanos { it }
    val durationNanos = 320_000_000f
    do {
        val fraction = withFrameNanos { ((it - start) / durationNanos).coerceIn(0f, 1f) }
        applyPose(MangaMotion.EnterEasing.transform(fraction))
    } while (fraction < 1f)
}
