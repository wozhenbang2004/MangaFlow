package dev.mangaflow.anime.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.mangaflow.platform.PlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.mpv.MpvMediampPlayerFactory

actual fun createAnimePlayer(context: PlatformContext, scope: CoroutineScope): AnimePlayer =
    DesktopAnimePlayer(context, scope)

private class DesktopAnimePlayer(
    private val context: PlatformContext,
    scope: CoroutineScope,
) : RoutedNativeAnimePlayer(scope) {
    override val supportsBufferControl = true
    override val supportsVideoEnhancement = true
    private var warming = true
    private var videoBeforeBackground: String? = null
    private var enhancementMediaReady = false
    private val enhancement = DesktopAnimeVideoEnhancement(
        context = context,
        scope = playerScope,
        isCurrentSession = { handle, generation, mode ->
            isCurrent(generation) && engine.value?.impl === handle && options.videoEnhancement == mode
        },
        update = { generation, value -> publish(generation) { it.copy(videoEnhancement = value) } },
    )

    override suspend fun createBackend(): MediampPlayer {
        var pending: MediampPlayer? = null
        val configuration = options
        return try {
            withContext(Dispatchers.IO) {
                // The shipped platform runtime loads off the Swing UI thread.
                MpvMediampPlayerFactory().create(context, playerScope.coroutineContext) { handle ->
                    handle.option("cache", "yes")
                    // Start with a short current-media window; expand only after actual
                    // native readiness and the initial buffering period have completed.
                    handle.option("cache-secs", (configuration.bufferStartMs / 1000.0).toString())
                    handle.option("demuxer-readahead-secs", "0")
                    handle.option("cache-pause", "yes")
                    handle.option("cache-pause-initial", "yes")
                    handle.option("cache-pause-wait", (configuration.bufferStartMs / 1000.0).toString())
                    handle.option("demuxer-max-bytes", (32L * 1024 * 1024).toString())
                    handle.option("demuxer-max-back-bytes", (8L * 1024 * 1024).toString())
                    handle.option("prefetch-playlist", "no")
                    handle.option("ytdl", "no")
                    check(handle.option("access-references", "no")) { "播放器无法关闭外部资源引用" }
                }.also { pending = it }
            }
        } catch (failure: Throwable) {
            pending?.close()
            throw failure
        }
    }

    override fun onMediaOpeningNative() {
        warming = true
        enhancementMediaReady = false
        applyVideoEnhancement()
        applyBufferPolicy()
        setForegroundNative(foreground || state.value.inPictureInPicture, options.backgroundAudio)
    }

    override fun onMediaReadyNative() {
        enhancementMediaReady = true
        applyVideoEnhancement()
        onPlaybackStateNative()
    }

    override fun onPlaybackStateNative() {
        val handle = engine.value?.impl as? MPVHandle
        if (handle != null) {
            val fps = handle.getPropertyString("container-fps")?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
            val dropped = handle.getPropertyLongOrNull("frame-drop-count")?.takeIf { it >= 0 }
            mutableState.value = state.value.copy(sourceFps = fps, droppedFrames = dropped)
        }
        if (warming && state.value.phase == AnimePlayerPhase.READY && !state.value.isBuffering) {
            warming = false
            applyBufferPolicy()
        }
    }

    override fun configureNative(previous: AnimePlayerOptions, next: AnimePlayerOptions) {
        applyBufferPolicy()
        applyVideoEnhancement()
    }

    private fun applyVideoEnhancement() {
        enhancement.configure(
            handle = engine.value?.impl as? MPVHandle,
            generation = nativeGeneration,
            mode = options.videoEnhancement,
            scale = options.enhancementScale,
            mediaReady = enhancementMediaReady,
        )
    }

    override fun setForegroundNative(foreground: Boolean, backgroundAudio: Boolean) {
        val handle = engine.value?.impl as? MPVHandle ?: return
        if (!foreground && backgroundAudio && videoBeforeBackground == null) {
            val selection = handle.getPropertyString("vid") ?: "auto"
            if (handle.setPropertyString("vid", "no")) videoBeforeBackground = selection
        } else if (foreground || !backgroundAudio) {
            videoBeforeBackground?.let { selection ->
                if (handle.setPropertyString("vid", selection)) videoBeforeBackground = null
            }
        }
        applyBufferPolicy()
    }

    private fun applyBufferPolicy() {
        val handle = engine.value?.impl as? MPVHandle ?: return
        val inactive = !foreground && !state.value.inPictureInPicture && !options.backgroundAudio
        val target = when {
            inactive -> 0
            warming -> options.bufferStartMs
            else -> options.bufferMaxMs
        }
        val minimum = if (inactive || warming) 0 else options.bufferMinMs
        val accepted = listOf(
            handle.setPropertyDouble("cache-secs", target / 1000.0),
            handle.setPropertyDouble("demuxer-readahead-secs", 0.0),
            handle.setPropertyDouble("demuxer-hysteresis-secs", minimum / 1000.0),
            handle.setPropertyDouble("cache-pause-wait", (if (warming) options.bufferStartMs else options.bufferRebufferMs) / 1000.0),
        ).all { it }
        if (!accepted) mutableState.value = state.value.copy(
            capabilities = state.value.capabilities - AnimePlayerCapability.BUFFER_CONTROL,
            notice = "当前桌面内核未接受全部缓冲参数，仍可继续播放",
        )
    }

    override fun releaseNative() {
        enhancement.release()
        enhancementMediaReady = false
        mutableState.value = state.value.copy(videoEnhancement = AnimeVideoEnhancementState(
            mode = options.videoEnhancement,
            phase = AnimeVideoEnhancementPhase.OFF,
        ))
        videoBeforeBackground = null
        warming = true
        super.releaseNative()
    }
}

@Composable
actual fun AnimePlayerSurface(player: AnimePlayer, modifier: Modifier) = NativeAnimeSurface(player, modifier)
