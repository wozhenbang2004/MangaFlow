package dev.mangaflow.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.rememberZoomableState

/** Shared ownership of image readiness and the original Telephoto gesture/camera state. */
@Stable
class ReaderImageState(val zoomableState: ZoomableState) {
    var isImageDisplayed: Boolean by mutableStateOf(false)
        internal set
    var detailError: String? by mutableStateOf(null)
        internal set
    var detailNotice: String? by mutableStateOf(null)
        internal set
}

internal data class ReaderRegion(val bitmap: ImageBitmap, val notice: String? = null)
internal data class ReaderDetailStatus(val message: String, val retryable: Boolean)

@Composable
fun rememberReaderImageState(zoomableState: ZoomableState = rememberZoomableState()): ReaderImageState =
    remember(zoomableState) { ReaderImageState(zoomableState) }

@Composable
internal expect fun ReaderZoomableImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    state: ReaderImageState = rememberReaderImageState(),
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    gesturesEnabled: Boolean = true,
    onClick: ((Offset) -> Unit)? = null,
)

@Composable
internal expect fun ReaderWindow(keepScreenOn: Boolean, controlsVisible: Boolean, lightChrome: Boolean)

internal data class VerticalPageWindow(val top: Int, val bottom: Int)

@Composable
internal expect fun VerticalReaderTiles(
    page: dev.mangaflow.data.Page,
    diskCacheKey: String?,
    imageSize: IntSize,
    displayWidth: Int,
    displayHeight: Int,
    visibleWindow: () -> VerticalPageWindow?,
    onDetailStatus: (ReaderDetailStatus?) -> Unit = {},
)

/** Native decoding reports additional budget sampling explicitly; original files are never rewritten. */
internal expect suspend fun readNativeReaderSize(path: String): IntSize?
internal expect suspend fun decodeNativeReaderRegion(path: String, bounds: IntRect, sampleSize: Int): ReaderRegion
internal expect fun readerFilePath(uri: String): String?

@Composable
internal expect fun ConfigureReaderDialogWindow(dimAmount: Float)

@Composable
internal expect fun readerNavigationInsets(): androidx.compose.foundation.layout.WindowInsets
@Composable
internal expect fun readerStatusInsets(): androidx.compose.foundation.layout.WindowInsets
