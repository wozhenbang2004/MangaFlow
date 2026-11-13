package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.mangaflow.storage.WallpaperAdjustment
import dev.mangaflow.storage.WallpaperFit
import dev.mangaflow.storage.WallpaperKind
import dev.mangaflow.storage.WallpaperStore
import dev.mangaflow.ui.motion.LocalMotionEnabled

internal val LocalWallpaperActive = compositionLocalOf { false }

/** Decorative local media stays behind content; edits never own another video decoder. */
@Composable
internal fun AppWallpaper(store: WallpaperStore, modifier: Modifier = Modifier, active: Boolean = true) {
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

@Composable
private fun WallpaperPicture(
    path: String,
    adjustment: WallpaperAdjustment,
    imageAdjustments: Boolean,
    modifier: Modifier,
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
    AsyncImage(model = path, contentDescription = null,
        contentScale = if (imageAdjustments && adjustment.fit == WallpaperFit.FIT) ContentScale.Fit else ContentScale.Crop,
        alignment = if (imageAdjustments) BiasAlignment(adjustment.offsetX, adjustment.offsetY) else Alignment.Center,
        colorFilter = filter, modifier = modifier.then(transform).then(diffusion), onError = { onError() })
}
