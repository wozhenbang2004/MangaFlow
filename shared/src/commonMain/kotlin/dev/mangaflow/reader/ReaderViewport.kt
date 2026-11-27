@file:OptIn(me.saket.telephoto.ExperimentalTelephotoApi::class)

package dev.mangaflow.reader

import androidx.compose.animation.core.snap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.Viewport
import me.saket.telephoto.zoomable.ZoomableContent
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.spatial.CoordinateSpace
import me.saket.telephoto.zoomable.spatial.SpatialOffset
import kotlin.math.roundToInt

/** Layout follows every window event. Only requests for a differently sized bitmap wait for a pause. */
@Composable
internal fun readerDecodeViewport(width: Int, height: Int): IntSize {
    val measured = IntSize(width, height)
    if (platformKind != PlatformKind.DESKTOP) return measured
    val latest by rememberUpdatedState(measured)
    var settled by remember { mutableStateOf(measured) }
    LaunchedEffect(Unit) {
        snapshotFlow { latest }.collectLatest { size ->
            if (size.width > 0 && size.height > 0 && size != settled) {
                if (settled.width > 0 && settled.height > 0) delay(150)
                settled = size
            }
        }
    }
    return settled
}

internal fun IntSize.spreadSlot(right: Boolean): IntRect {
    val spine = width / 2
    return if (right) IntRect(spine, 0, width, height) else IntRect(0, 0, spine, height)
}

/** Drawn slots and source coordinates use the same physical spine, including odd-pixel widths and RTL. */
@Composable
internal fun ReaderSpreadRow(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { pages, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val spine = width / 2
        val left = pages[0].measure(Constraints.fixed(spine, height))
        val right = pages[1].measure(Constraints.fixed(width - spine, height))
        layout(width, height) {
            left.place(0, 0)
            right.place(spine, 0)
        }
    }
}

private data class SpreadFocus(val rightPage: Boolean?, val fraction: Offset)

private data class SpreadGeometry(val viewport: IntSize, val leftImage: IntSize, val rightImage: IntSize) {
    val center get() = Offset(viewport.width * .5f, viewport.height * .5f)

    fun bounds(right: Boolean?): Rect {
        if (right == null) return Rect(0f, 0f, viewport.width.toFloat(), viewport.height.toFloat())
        val slot = viewport.spreadSlot(right)
        val image = if (right) rightImage else leftImage
        if (image.width <= 0 || image.height <= 0) {
            return Rect(slot.left.toFloat(), 0f, slot.right.toFloat(), slot.height.toFloat())
        }
        val fit = minOf(slot.width.toFloat() / image.width, slot.height.toFloat() / image.height)
        val width = (image.width * fit).roundToInt()
        val height = (image.height * fit).roundToInt()
        val left = if (right) slot.left else slot.right - width
        val top = ((slot.height - height) * .5f).roundToInt()
        return Rect(left.toFloat(), top.toFloat(), (left + width).toFloat(), (top + height).toFloat())
    }

    fun focus(zoom: ZoomableState): SpreadFocus? {
        if (viewport.width < 2 || viewport.height <= 0 || (zoom.zoomFraction ?: 0f) <= .001f) return null
        val point = with(zoom.coordinateSystem) {
            SpatialOffset(center, CoordinateSpace.Viewport).offsetIn(CoordinateSpace.ZoomableContent)
        }
        if (!point.x.isFinite() || !point.y.isFinite()) return null
        val side = point.x >= viewport.width / 2
        val page = bounds(side)
        val onPage = page.contains(point)
        val area = if (onPage) page else bounds(null)
        return SpreadFocus(side.takeIf { onPage }, Offset(
            (point.x - area.left) / area.width, (point.y - area.top) / area.height,
        ))
    }

    fun correction(zoom: ZoomableState, focus: SpreadFocus): Offset {
        val area = bounds(focus.rightPage)
        val point = Offset(area.left + area.width * focus.fraction.x, area.top + area.height * focus.fraction.y)
        return center - with(zoom.coordinateSystem) {
            SpatialOffset(point, CoordinateSpace.ZoomableContent).offsetIn(CoordinateSpace.Viewport)
        }
    }
}

/** Keep the paper point under the center of the window when its aspect ratio changes. */
@Composable
internal fun RetainDesktopSpreadFocus(zoom: ZoomableState, viewport: IntSize, left: IntSize, right: IntSize) {
    val latest by rememberUpdatedState(SpreadGeometry(viewport, left, right))
    LaunchedEffect(zoom) {
        var previous: SpreadGeometry? = null
        var anchor: SpreadFocus? = null
        snapshotFlow { latest to latest.focus(zoom) }.collect { (geometry, currentFocus) ->
            val previousAnchor = anchor
            if (previous?.viewport != geometry.viewport && previousAnchor != null && currentFocus != null &&
                !zoom.isAnimationRunning) {
                val delta = geometry.correction(zoom, previousAnchor)
                if (delta.x.isFinite() && delta.y.isFinite() && delta.getDistanceSquared() >= .25f) {
                    // A new direct gesture may take the camera back without cancelling the resize observer.
                    launch { zoom.panBy(delta, snap()) }.join()
                }
                anchor = geometry.focus(zoom)
            } else {
                anchor = currentFocus
            }
            previous = geometry
        }
    }
}
