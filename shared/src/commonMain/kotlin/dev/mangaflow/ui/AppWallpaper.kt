package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import dev.mangaflow.platform.LocalMangaContext as LocalContext
import dev.mangaflow.platform.*
import dev.mangaflow.platform.storage.applicationContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.mangaflow.storage.WallpaperKind
import dev.mangaflow.storage.WallpaperStore
import dev.mangaflow.ui.motion.LocalMotionEnabled
import kotlin.math.max

internal val LocalWallpaperActive = compositionLocalOf { false }

/** Decorative local media stays behind the content; it owns no controls or audio focus. */
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
    val desaturate = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(.70f) }) }
    Box(modifier.clearAndSetSemantics {}.graphicsLayer { alpha = selected.strength }) {
        val preview = if (selected.kind == WallpaperKind.VIDEO) selected.posterPath else path
        if (preview != null && (selected.kind == WallpaperKind.VIDEO || selected.error == null)) {
            key(preview, selected.revision) {
                AsyncImage(model = preview, contentDescription = null, contentScale = ContentScale.Crop,
                    colorFilter = desaturate, modifier = Modifier.fillMaxSize(),
                    onError = { store.reportError(path, "壁纸图片读取失败，请重试或重新选择。", revision) })
            }
        }
        if (selected.kind == WallpaperKind.VIDEO && motion && selected.error == null && visible) {
            key(path, selected.revision) {
                WallpaperVideo(path, resumed && active, Modifier.fillMaxSize()) { store.reportError(path, it, revision) }
            }
        }
    }
}
