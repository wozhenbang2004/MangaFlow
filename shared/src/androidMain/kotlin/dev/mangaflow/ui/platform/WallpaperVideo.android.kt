package dev.mangaflow.ui.platform

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.max

@Composable
actual fun WallpaperVideo(path: String, playing: Boolean, modifier: Modifier, onError: (String) -> Unit) {
    val context = LocalContext.current
    val video = remember { WallpaperVideoView(context) }
    DisposableEffect(video) { onDispose { video.release() } }
    AndroidView(factory = { video }, modifier = modifier, update = { it.bind(path, playing, onError) })
}

/** TextureView supports alpha/composition; all MediaPlayer calls remain on the UI thread. */
private class WallpaperVideoView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private var source: String? = null
    private var allowed = false
    private var failed = false
    private var prepared = false
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var failure: (String) -> Unit = {}
    private var videoWidth = 0
    private var videoHeight = 0

    init {
        isOpaque = false
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        surfaceTextureListener = this
        alpha = 0f
    }

    fun bind(path: String, canPlay: Boolean, onFailure: (String) -> Unit) {
        failure = onFailure
        if (source != path) { releasePlayer(); source = path; failed = false }
        allowed = canPlay && !failed
        if (allowed && player == null && isAvailable) surfaceTexture?.let(::prepare)
        updatePlayback()
    }

    private fun prepare(texture: SurfaceTexture) {
        if (player != null || !allowed) return
        val file = source ?: return
        try {
            val target = Surface(texture)
            surface = target
            val next = MediaPlayer()
            player = next
            next.setOnPreparedListener { ready ->
                if (player === ready) {
                    prepared = true
                    ready.setVolume(0f, 0f)
                    ready.isLooping = true
                    videoWidth = ready.videoWidth
                    videoHeight = ready.videoHeight
                    updateCrop()
                    updatePlayback()
                }
            }
            next.setOnVideoSizeChangedListener { current, width, height ->
                if (player === current) { videoWidth = width; videoHeight = height; updateCrop() }
            }
            next.setOnInfoListener { current, what, _ ->
                if (player === current && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) alpha = 1f
                false
            }
            next.setOnErrorListener { current, what, extra ->
                if (player === current) {
                    fail("视频播放失败（$what / $extra），已保留静态画面。可重试或换用 MP4（H.264）。")
                }
                true
            }
            next.setDataSource(file)
            next.setSurface(target)
            next.prepareAsync()
        } catch (_: Exception) {
            fail("视频无法打开，已保留静态画面。请重试或换用 MP4（H.264）。")
        }
    }

    private fun updatePlayback() {
        val current = player ?: return
        if (!prepared) return
        try {
            if (allowed && !current.isPlaying) current.start()
            else if (!allowed && current.isPlaying) current.pause()
        } catch (_: IllegalStateException) {
            fail("视频播放中断，已保留静态画面。可点击重试。")
        }
    }

    private fun fail(message: String) {
        failed = true
        allowed = false
        releasePlayer()
        failure(message)
    }

    private fun updateCrop() {
        if (width <= 0 || height <= 0 || videoWidth <= 0 || videoHeight <= 0) return
        val scale = max(width.toFloat() / videoWidth, height.toFloat() / videoHeight)
        setTransform(Matrix().apply {
            setScale(videoWidth * scale / width, videoHeight * scale / height, width / 2f, height / 2f)
        })
    }

    private fun releasePlayer() {
        val old = player
        player = null
        prepared = false
        alpha = 0f
        if (old != null) {
            old.setOnPreparedListener(null)
            old.setOnVideoSizeChangedListener(null)
            old.setOnInfoListener(null)
            old.setOnErrorListener(null)
            old.release()
        }
        surface?.release()
        surface = null
    }

    fun release() { allowed = false; releasePlayer(); source = null }
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) { prepare(texture) }
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) { updateCrop() }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { releasePlayer(); return true }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
}
