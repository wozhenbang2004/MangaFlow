package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.storage.WallpaperAdjustment
import dev.mangaflow.storage.WallpaperFit
import dev.mangaflow.storage.WallpaperKind
import dev.mangaflow.storage.WallpaperStore
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion

internal val LocalWallpaperActive = compositionLocalOf { false }

/** Decorative local media stays behind content; edits never own another video decoder. */
@Composable
internal fun AppWallpaper(store: WallpaperStore, modifier: Modifier = Modifier, active: Boolean = true,
    underCatalogue: Boolean = false) {
    val selected by store.state.collectAsStateWithLifecycle()
    val revision = selected.revision
    val motion = LocalMotionEnabled.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val owner = remember(store) { Any() }
    val visible = active && resumed && selected.strength > 0f
    DisposableEffect(store, owner, visible) {
        store.setActive(owner, visible)
        onDispose { store.setActive(owner, false) }
    }
    val path = selected.mediaPath ?: return
    Box(modifier.clearAndSetSemantics {}.clipToBounds().graphicsLayer { alpha = selected.strength }) {
        val preview = if (selected.kind == WallpaperKind.VIDEO) selected.posterPath else path
        if (preview != null && (selected.kind == WallpaperKind.VIDEO || selected.error == null)) {
            key(preview, revision) {
                WallpaperPicture(preview, selected.adjustment, selected.kind == WallpaperKind.IMAGE,
                    Modifier.fillMaxSize()) { store.reportError(path, "壁纸图片读取失败，请重试或重新选择。", revision) }
            }
        }
        if (selected.kind == WallpaperKind.VIDEO && motion && selected.error == null && visible) {
            key(path, revision) {
                WallpaperVideo(path, resumed && active, Modifier.fillMaxSize()) { store.reportError(path, it, revision) }
            }
        }
        if (selected.adjustment.shade > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = selected.adjustment.shade)))
        if (underCatalogue) {
            val paper = MaterialTheme.colorScheme.background
            Box(Modifier.matchParentSize().drawWithCache {
                val veil = Brush.verticalGradient(
                    0f to paper.copy(alpha = .12f),
                    .28f to paper.copy(alpha = .38f),
                    .55f to paper.copy(alpha = .78f),
                    1f to paper.copy(alpha = .90f),
                )
                onDrawBehind { drawRect(veil) }
            })
        }
    }
}

/** The editor uses the exact image transform/color/mask path, without playing a second video. */
@Composable
internal fun WallpaperPreview(
    path: String?,
    kind: WallpaperKind,
    adjustment: WallpaperAdjustment,
    modifier: Modifier = Modifier,
) {
    Box(modifier.clipToBounds().graphicsLayer { alpha = adjustment.strength }) {
        if (path != null) WallpaperPicture(path, adjustment, kind == WallpaperKind.IMAGE, Modifier.fillMaxSize())
        if (adjustment.shade > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = adjustment.shade)))
    }
}

internal enum class WallpaperPreviewMode { APPLIED, COMPARE, ORIGINAL }

/** Both sides draw one bounded bitmap. A drag changes only the clipping boundary. */
@Composable
internal fun DesktopWallpaperPreview(path: String, kind: WallpaperKind, adjustment: WallpaperAdjustment,
    mode: WallpaperPreviewMode, description: String, modifier: Modifier = Modifier) {
    val context = LocalMangaContext.current
    val motion = LocalMotionEnabled.current
    val colors = MaterialTheme.colorScheme
    var split by rememberSaveable(path) { mutableFloatStateOf(.5f) }
    var dragging by remember(path, mode) { mutableStateOf(false) }
    var focused by remember(path, mode) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val reveal = animateFloatAsState(when (mode) {
        WallpaperPreviewMode.APPLIED -> 0f
        WallpaperPreviewMode.COMPARE -> split
        WallpaperPreviewMode.ORIGINAL -> 1f
    }, if (motion && !dragging) tween(MangaMotion.Content, easing = MangaMotion.EnterEasing) else snap(),
        label = "wallpaper-comparison")
    BoxWithConstraints(modifier.clipToBounds()) {
        val width = constraints.maxWidth.coerceAtLeast(1)
        val pxWidth = (width.coerceAtMost(4096) + 63) / 64 * 64
        val pxHeight = (constraints.maxHeight.coerceIn(1, 4096) + 63) / 64 * 64
        val request = remember(context, path, pxWidth, pxHeight) {
            ImageRequest.Builder(imagePlatformContext(context)).data(path).size(pxWidth, pxHeight).build()
        }
        val painter = rememberAsyncImagePainter(request, contentScale = ContentScale.Fit)
        Box(Modifier.fillMaxSize().drawWithContent {
            val boundary = size.width * reveal.value
            if (boundary < size.width) clipRect(left = boundary) { this@drawWithContent.drawContent() }
        }.graphicsLayer { alpha = adjustment.strength }) {
            WallpaperPicture(path, adjustment, kind == WallpaperKind.IMAGE, Modifier.fillMaxSize(), painter = painter)
            if (adjustment.shade > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = adjustment.shade)))
        }
        Image(painter, if (mode == WallpaperPreviewMode.COMPARE) null else description,
            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().drawWithContent {
                val boundary = size.width * reveal.value
                if (boundary > 0f) clipRect(right = boundary) { this@drawWithContent.drawContent() }
            })
        if (mode == WallpaperPreviewMode.COMPARE) {
            val drag = rememberDraggableState { delta -> split = (split + delta / width).coerceIn(0f, 1f) }
            Box(Modifier.fillMaxSize().focusRequester(focus).onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    val next = when (event.key) {
                        Key.DirectionLeft -> split - .05f
                        Key.DirectionRight -> split + .05f
                        Key.MoveHome -> 0f
                        Key.MoveEnd -> 1f
                        else -> null
                    }
                    if (next == null) false else {
                        if (event.type == KeyEventType.KeyDown) split = next.coerceIn(0f, 1f)
                        true
                    }
                }.focusable().semantics {
                    contentDescription = "壁纸对照分界，左侧原图，右侧应用效果"
                    progressBarRangeInfo = ProgressBarRangeInfo(split, 0f..1f)
                    setProgress { split = it.coerceIn(0f, 1f); true }
                }.pointerHoverIcon(PointerIcon.Hand)
                .draggable(drag, Orientation.Horizontal, onDragStarted = { point ->
                    dragging = true
                    split = (point.x / width).coerceIn(0f, 1f)
                    focus.requestFocus()
                }, onDragStopped = { dragging = false })
                .drawWithContent {
                    val x = size.width * reveal.value
                    drawLine(Color.Black.copy(alpha = .38f), Offset(x, 0f), Offset(x, size.height), 3.dp.toPx())
                    drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                    val w = minOf(22.dp.toPx(), size.width)
                    val h = minOf(28.dp.toPx(), size.height)
                    val left = (x - w / 2f).coerceIn(0f, size.width - w)
                    val middle = left + w / 2f
                    drawRoundRect(if (focused) colors.primary else colors.surface,
                        Offset(left, center.y - h / 2f), Size(w, h), CornerRadius(w / 2f))
                    val ink = if (focused) colors.onPrimary else colors.onSurface
                    for (side in -1..1 step 2) {
                        val offset = 2.dp.toPx() * side
                        drawLine(ink, Offset(middle + offset, center.y - 4.dp.toPx()),
                            Offset(middle + offset, center.y + 4.dp.toPx()), 1.4.dp.toPx())
                    }
                })
        }
    }
}

@Composable
private fun WallpaperPicture(
    path: String,
    adjustment: WallpaperAdjustment,
    imageAdjustments: Boolean,
    modifier: Modifier,
    painter: Painter? = null,
    onError: () -> Unit = {},
) {
    val filter = remember(adjustment.brightness, adjustment.saturation, adjustment.contrast, imageAdjustments) {
        if (!imageAdjustments) null else {
            val matrix = ColorMatrix().apply { setToSaturation(adjustment.saturation) }
            val multiplier = adjustment.brightness * adjustment.contrast
            for (row in 0..2) {
                for (column in 0..3) matrix.values[row * 5 + column] *= multiplier
                matrix.values[row * 5 + 4] = 128f * (1f - adjustment.contrast) * adjustment.brightness
            }
            ColorFilter.colorMatrix(matrix)
        }
    }
    val transform = if (imageAdjustments) Modifier.graphicsLayer {
        scaleX = adjustment.zoom
        scaleY = adjustment.zoom
        translationX = -adjustment.offsetX * size.width * (adjustment.zoom - 1f) / 2f
        translationY = -adjustment.offsetY * size.height * (adjustment.zoom - 1f) / 2f
    } else Modifier
    val diffusion = if (imageAdjustments && modernPlatformBlur && adjustment.blur > 0f)
        Modifier.blur(adjustment.blur.dp, BlurredEdgeTreatment.Unbounded) else Modifier
    val scale = if (imageAdjustments && adjustment.fit == WallpaperFit.FIT) ContentScale.Fit else ContentScale.Crop
    val alignment = if (imageAdjustments) BiasAlignment(adjustment.offsetX, adjustment.offsetY) else Alignment.Center
    val picture = modifier.then(transform).then(diffusion)
    if (painter != null) Image(painter, null, modifier = picture, contentScale = scale, alignment = alignment, colorFilter = filter)
    else AsyncImage(model = path, contentDescription = null, contentScale = scale, alignment = alignment,
        colorFilter = filter, modifier = picture, onError = { onError() })
}
