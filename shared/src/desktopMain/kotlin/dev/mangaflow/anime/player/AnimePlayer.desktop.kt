package dev.mangaflow.anime.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.mangaflow.enhancement.engine.DesktopAnimeJaNai
import dev.mangaflow.enhancement.engine.EnhancementEngineIds
import dev.mangaflow.platform.PlatformContext
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.mpv.MpvMediampPlayerFactory
import kotlin.math.abs

actual fun createAnimePlayer(context: PlatformContext, scope: CoroutineScope): AnimePlayer =
    DesktopAnimePlayer(context, scope)

private class DesktopAnimePlayer(
    private val context: PlatformContext,
    scope: CoroutineScope,
) : RoutedNativeAnimePlayer(scope) {
    override val supportsBufferControl = true
    override val supportsVideoEnhancement = true
    override val supportsModelEnhancement = System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)
    override val supportsDecoderStartup = System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)
    private var warming = true
    private var videoBeforeBackground: String? = null
    private var enhancementMediaReady = false
    private val animeJaNai = DesktopAnimeJaNai(File(context.dataRoot, "enhancement/engines"))
    private val nativeEnhancement = DesktopAnimeJaNaiEnhancement(
        animeJaNai, playerScope, { context.playbackDisplayRefreshHz?.invoke() },
    ) { generation, value ->
        publish(generation) { it.copy(videoEnhancement = value) }
    }
    private val wantsNativeModel: Boolean
        get() = options.enhancementModel?.engineId == EnhancementEngineIds.DESKTOP_REALTIME
    private val enhancement = DesktopAnimeVideoEnhancement(
        context = context,
        scope = playerScope,
        isCurrentSession = { handle, generation, mode ->
            isCurrent(generation) && engine.value?.impl === handle && options.videoEnhancement == mode
        },
        update = { generation, value ->
            if (!wantsNativeModel && (options.realtimeFrames == null || !animeJaNai.usesNativeRuntime())) {
                publish(generation) { it.copy(videoEnhancement = value) }
            }
        },
    )

    override suspend fun createBackend(): MediampPlayer = createMpv()

    override suspend fun createDecoderStartup(byteLimit: Long): MediampPlayer = createMpv(byteLimit)

    private suspend fun createMpv(startupByteLimit: Long? = null): MediampPlayer {
        var pending: MediampPlayer? = null
        val configuration = options
        val preroll = startupByteLimit != null
        return try {
            withContext(Dispatchers.IO) {
                // The shipped platform runtime loads off the Swing UI thread.
                animeJaNai.configureProcess(configuration.enhancementModel?.engineId == EnhancementEngineIds.DESKTOP_REALTIME ||
                    configuration.realtimeFrames != null)
                MpvMediampPlayerFactory().create(context, playerScope.coroutineContext) { handle ->
                    if (animeJaNai.usesNativeRuntime()) handle.option("hwdec", "d3d11va")
                    handle.option("cache", "yes")
                    // Start with a short current-media window; expand only after actual
                    // native readiness and the initial buffering period have completed.
                    handle.option("cache-secs", if (preroll) "0.25" else (configuration.bufferStartMs / 1000.0).toString())
                    handle.option("demuxer-readahead-secs", "0")
                    handle.option("cache-pause", if (preroll) "no" else "yes")
                    handle.option("cache-pause-initial", if (preroll) "no" else "yes")
                    handle.option("cache-pause-wait", (configuration.bufferStartMs / 1000.0).toString())
                    handle.option("demuxer-max-bytes", (startupByteLimit ?: ACTIVE_FORWARD_BYTES).toString())
                    handle.option("demuxer-max-back-bytes", if (preroll) "0" else ACTIVE_BACK_BYTES.toString())
                    if (preroll) check(handle.option("mute", "yes")) { "预解码播放器无法静音" }
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

    override fun hasDecodedStartupFrame(backend: MediampPlayer, positionMs: Long): Boolean {
        val handle = backend.impl as? MPVHandle ?: return false
        val time = handle.getPropertyDoubleOrNull("time-pos")?.takeIf(Double::isFinite) ?: return false
        // libmpv only exposes frame-info after obtaining a current video-output image.
        return abs(time * 1000 - positionMs) <= 500 &&
            handle.getPropertyBooleanOrNull("video-frame-info/interlaced") != null
    }

    override fun activateDecoderStartup(backend: MediampPlayer): Boolean {
        val handle = backend.impl as? MPVHandle ?: return false
        return handle.setPropertyString("demuxer-max-bytes", ACTIVE_FORWARD_BYTES.toString()) &&
            handle.setPropertyString("demuxer-max-back-bytes", ACTIVE_BACK_BYTES.toString()) &&
            handle.setPropertyBoolean("cache-pause", true) && handle.setPropertyBoolean("cache-pause-initial", true) &&
            handle.setPropertyBoolean("mute", false)
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
            if (animeJaNai.usesNativeRuntime() && handle.getPropertyBooleanOrNull("video-frame-info/interlaced") != null) {
                animeJaNai.confirmFirstFrame()
            }
            val fps = handle.getPropertyString("container-fps")?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
            val dropped = handle.getPropertyLongOrNull("frame-drop-count")?.takeIf { it >= 0 }
            mutableState.value = state.value.copy(sourceFps = fps, droppedFrames = dropped,
                displayRefreshHz = context.playbackDisplayRefreshHz?.invoke())
        }
        if (warming && state.value.phase == AnimePlayerPhase.READY && !state.value.isBuffering) {
            warming = false
            applyBufferPolicy()
        }
    }

    override fun configureNative(previous: AnimePlayerOptions, next: AnimePlayerOptions) {
        if (previous != next) discardStartup()
        applyBufferPolicy()
        applyVideoEnhancement()
    }

    private fun applyVideoEnhancement() {
        val handle = engine.value?.impl as? MPVHandle
        enhancement.configure(
            handle = handle.takeUnless { wantsNativeModel },
            generation = nativeGeneration,
            mode = options.videoEnhancement,
            scale = options.enhancementScale,
            model = options.enhancementModel.takeUnless { wantsNativeModel },
            mediaReady = enhancementMediaReady,
        )
        nativeEnhancement.configure(
            handle, nativeGeneration, options.videoEnhancement, enhancementMediaReady,
            options.enhancementModel.takeIf { wantsNativeModel }, options.realtimeFrames,
        )
    }

    override fun setForegroundNative(foreground: Boolean, backgroundAudio: Boolean) {
        if (!foreground) discardStartup()
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
        nativeEnhancement.release()
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

    private companion object {
        const val ACTIVE_FORWARD_BYTES = 32L * 1024 * 1024
        const val ACTIVE_BACK_BYTES = 8L * 1024 * 1024
    }
}

@Composable
actual fun AnimePlayerSurface(player: AnimePlayer, modifier: Modifier) = NativeAnimeSurface(player, modifier)
