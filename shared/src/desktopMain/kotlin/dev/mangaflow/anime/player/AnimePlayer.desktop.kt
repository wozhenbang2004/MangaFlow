package dev.mangaflow.anime.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.mangaflow.enhancement.engine.DesktopAnimeJaNai
import dev.mangaflow.enhancement.engine.EnhancementEngineIds
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.monotonicTimeMillis
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.mpv.MpvMediampPlayerFactory
import kotlin.math.abs

actual fun createAnimePlayer(context: PlatformContext, scope: CoroutineScope): AnimePlayer =
    DesktopAnimePlayer(context, scope)

actual fun initializeAnimeStorage(context: PlatformContext) {
    DesktopAnimeStreamCache.get(context)
    DesktopAnimeWindowFiles.get(context)
}

private class DesktopAnimePlayer(
    private val context: PlatformContext,
    scope: CoroutineScope,
) : RoutedNativeAnimePlayer(scope) {
    override val supportsBufferControl = true
    override val supportsVideoEnhancement = true
    override val supportsModelEnhancement = System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)
    override val supportsDecoderStartup = System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)
    override val streamCache by lazy { DesktopAnimeStreamCache.get(context) }
    private var warming = true
    private var videoBeforeBackground: String? = null
    private var enhancementMediaReady = false
    private var statisticsJob: Job? = null
    private var statisticsHandle: MPVHandle? = null
    private val videoRenderingActive = MutableStateFlow(false)
    private var windowOwnsVideo = false
    private val animeJaNai = DesktopAnimeJaNai(File(context.dataRoot, "enhancement/engines"))
    private val nativeEnhancement = DesktopAnimeJaNaiEnhancement(
        runtime = animeJaNai,
        scope = playerScope,
        renderingActive = videoRenderingActive,
        refreshHz = { context.playbackDisplayRefreshHz?.invoke() },
    ) { generation, value ->
        publish(generation) { it.copy(videoEnhancement = value) }
    }
    private val wantsNativeModel: Boolean
        get() = options.enhancementModel?.engineId == EnhancementEngineIds.DESKTOP_REALTIME
    private val enhancement = DesktopAnimeVideoEnhancement(
        context = context,
        scope = playerScope,
        renderingActive = videoRenderingActive,
        isCurrentSession = { handle, generation, mode ->
            isCurrent(generation) && engine.value?.impl === handle && options.videoEnhancement == mode
        },
        update = { generation, value ->
            if (!wantsNativeModel && (options.realtimeFrames == null || !animeJaNai.usesNativeRuntime())) {
                publish(generation) { it.copy(videoEnhancement = value) }
            }
        },
    )
    private val onlinePreparation by lazy {
        DesktopAnimeOnlinePreparation(context, playerScope, videoRenderingActive,
            current = { window, handle ->
                val snapshot = state.value
                engine.value?.impl === handle && snapshot.sessionGeneration == window.sessionGeneration &&
                    snapshot.timelineGeneration == window.timelineGeneration && options.preparedVideoModel == window.model &&
                    (foreground || snapshot.inPictureInPicture) && request?.localMedia == null &&
                    request?.asset?.let { asset -> asset.playbackIdentity() == window.asset.playbackIdentity() &&
                        asset.url == window.asset.url && asset.headers == window.asset.headers } == true
            },
            suspendRealtime = { suspended ->
                if (windowOwnsVideo != suspended) { windowOwnsVideo = suspended; applyVideoEnhancement() }
            },
            update = { window, issue -> mutableState.value = state.value.copy(preparedVideoWindow = window, preparedVideoIssue = issue) },
        )
    }

    override suspend fun prepareVideoWindow(request: AnimePreparedVideoRequest): Boolean {
        val handle = engine.value?.impl as? MPVHandle ?: return false
        return onlinePreparation.prepare(request, handle)
    }

    override fun discardPreparedVideo() = command(fatal = false) { onlinePreparation.clear() }

    override fun noteVideoPreparationIssue(detail: String?) = command(fatal = false) {
        mutableState.value = state.value.copy(preparedVideoIssue = detail)
    }

    override fun seekNative(positionMs: Long) {
        check(onlinePreparation.clear()) { "增强窗口尚未退出，请重新打开当前视频后定位" }
        super.seekNative(positionMs)
    }

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
                    if (configuration.preparedVideoModel != null && configuration.realtimeFrames == null &&
                        configuration.enhancementModel?.engineId != EnhancementEngineIds.DESKTOP_REALTIME)
                        check(handle.option("hwdec", "auto-copy")) { "播放器无法准备可复用的解码画面" }
                    handle.option("cache", "yes")
                    check(handle.option("rebase-start-time", "yes")) { "播放器无法统一视频时间轴" }
                    // Start with a short current-media window; expand only after actual
                    // native readiness and the initial buffering period have completed.
                    handle.option("cache-secs", if (preroll) "0.25" else (configuration.bufferStartMs / 1000.0).toString())
                    handle.option("demuxer-readahead-secs", "0")
                    handle.option("cache-pause", if (preroll) "no" else "yes")
                    handle.option("cache-pause-initial", if (preroll) "no" else "yes")
                    handle.option("cache-pause-wait", (configuration.bufferStartMs / 1000.0).toString())
                    check(handle.option("demuxer-max-bytes", (startupByteLimit ?: configuration.bufferForwardBytes).toString())) {
                        "播放器无法应用前方缓冲容量"
                    }
                    check(handle.option("demuxer-max-back-bytes", if (preroll) "0" else configuration.bufferBackBytes.toString())) {
                        "播放器无法应用回看缓冲容量"
                    }
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
        return handle.setPropertyString("demuxer-max-bytes", options.bufferForwardBytes.toString()) &&
            handle.setPropertyString("demuxer-max-back-bytes", options.bufferBackBytes.toString()) &&
            handle.setPropertyBoolean("cache-pause", true) && handle.setPropertyBoolean("cache-pause-initial", true) &&
            handle.setPropertyBoolean("mute", false)
    }

    override fun onMediaOpeningNative() {
        onlinePreparation.clear()
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
        val refreshHz = context.playbackDisplayRefreshHz?.invoke()
        if (state.value.displayRefreshHz != refreshHz) {
            mutableState.value = state.value.copy(displayRefreshHz = refreshHz)
        }
        updateStatisticsObservation(handle)
        if (warming && state.value.phase == AnimePlayerPhase.READY && !state.value.isBuffering) {
            warming = false
            applyBufferPolicy()
        }
    }

    private fun updateStatisticsObservation(handle: MPVHandle?) {
        val rendering = handle != null && state.value.isPlaying && (foreground || state.value.inPictureInPicture)
        videoRenderingActive.value = rendering
        if (handle == null || !enhancementMediaReady) {
            statisticsJob?.cancel()
            statisticsJob = null
            statisticsHandle = null
            return
        }
        if (statisticsHandle === handle && statisticsJob?.isActive == true) return
        statisticsJob?.cancel()
        statisticsHandle = handle
        val generation = nativeGeneration
        statisticsJob = playerScope.launch(Dispatchers.Main.immediate) {
            var initialSample = true
            while (isCurrent(generation) && engine.value?.impl === handle) {
                // A paused opening still needs source metadata once. Afterwards an inactive
                // video suspends on playback state, with no periodic native reads or timer.
                if (!initialSample) videoRenderingActive.first { it }
                initialSample = false
                val alternateRuntime = animeJaNai.usesNativeRuntime()
                // The pinned native registry retains each read across teardown. A closed Kotlin
                // handle is unavailable; it must not leave stale values on a replacement session.
                val sample = withContext(Dispatchers.IO) {
                    runCatching {
                        fun counter(name: String) = handle.getPropertyLongOrNull(name)?.takeIf { it >= 0L }
                        val fps = handle.getPropertyDoubleOrNull("container-fps")?.takeIf { it.isFinite() && it > 0 }
                        val statistics = AnimeNativeVideoStatistics(
                            sampledAtMillis = monotonicTimeMillis(),
                            hardwareDecoder = handle.getPropertyString("hwdec-current")?.takeIf { it.isNotBlank() },
                            videoOutput = handle.getPropertyString("current-vo")?.takeIf { it.isNotBlank() },
                            decoderDroppedFrames = counter("decoder-frame-drop-count"),
                            outputDroppedFrames = counter("frame-drop-count"),
                            estimatedFilterFps = handle.getPropertyDoubleOrNull("estimated-vf-fps")?.takeIf { it.isFinite() && it > 0 },
                            estimatedDelayedFrames = counter("vo-delayed-frame-count"),
                        )
                        if (alternateRuntime && handle.getPropertyBooleanOrNull("video-frame-info/interlaced") != null)
                            animeJaNai.confirmFirstFrame()
                        val buffers = AnimeBufferMetrics(
                            allocatedBytes = counter("demuxer-cache-state/total-bytes"),
                            forwardBytes = counter("demuxer-cache-state/fw-bytes"),
                            targetForwardBytes = counter("demuxer-max-bytes"),
                            targetBackBytes = counter("demuxer-max-back-bytes"),
                            sampledAtMillis = monotonicTimeMillis(),
                            detail = "原生压缩包缓存估计；回看实际字节未单独公开，不含已解码画面或磁盘缓存",
                        )
                        Triple(fps, statistics, buffers)
                    }.getOrNull()
                }
                if (!isCurrent(generation) || engine.value?.impl !== handle) break
                // The display provider reads AWT state and remains on Main, unlike native properties.
                val refreshHz = context.playbackDisplayRefreshHz?.invoke()
                if (sample != null) {
                    publish(generation) { it.copy(sourceFps = sample.first, droppedFrames = sample.second.outputDroppedFrames,
                        nativeVideoStatistics = sample.second, displayRefreshHz = refreshHz, bufferMetrics = sample.third) }
                } else publish(generation) { it.copy(sourceFps = null, droppedFrames = null,
                    nativeVideoStatistics = null, displayRefreshHz = refreshHz, bufferMetrics = null) }
                if (videoRenderingActive.value) delay(1_000L)
            }
        }
    }

    override fun configureNative(previous: AnimePlayerOptions, next: AnimePlayerOptions) {
        if (!previous.sameDecoderStartupOptions(next)) discardStartup()
        if (previous.preparedVideoModel != next.preparedVideoModel || previous.enhancementModel != next.enhancementModel ||
            previous.videoEnhancement != next.videoEnhancement || previous.realtimeFrames != next.realtimeFrames) onlinePreparation.clear()
        if (previous.preparedVideoModel != next.preparedVideoModel && next.preparedVideoModel != null) {
            val handle = engine.value?.impl as? MPVHandle
            if (next.realtimeFrames == null && next.enhancementModel?.engineId != EnhancementEngineIds.DESKTOP_REALTIME) {
                if (handle != null && !handle.setPropertyString("hwdec", "auto-copy"))
                    mutableState.value = state.value.copy(preparedVideoIssue = "当前内核未接受预处理解码方式，请重新打开当前视频")
            } else mutableState.value = state.value.copy(preparedVideoIssue = "DirectML 实时补帧与在线增强窗口暂不能同时启用")
        }
        applyBufferPolicy()
        applyVideoEnhancement()
    }

    private fun applyVideoEnhancement() {
        val handle = engine.value?.impl as? MPVHandle
        enhancement.configure(
            handle = handle.takeUnless { wantsNativeModel || windowOwnsVideo },
            generation = nativeGeneration,
            mode = options.videoEnhancement,
            scale = options.enhancementScale,
            model = options.enhancementModel.takeUnless { wantsNativeModel },
            mediaReady = enhancementMediaReady && !windowOwnsVideo,
        )
        nativeEnhancement.configure(
            handle.takeUnless { windowOwnsVideo }, nativeGeneration, options.videoEnhancement, enhancementMediaReady && !windowOwnsVideo,
            options.enhancementModel.takeIf { wantsNativeModel }, options.realtimeFrames,
        )
    }

    override fun setForegroundNative(foreground: Boolean, backgroundAudio: Boolean) {
        if (!foreground) { discardStartup(); onlinePreparation.clear() }
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
        updateStatisticsObservation(handle)
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
            handle.setPropertyString("demuxer-max-bytes", options.bufferForwardBytes.toString()),
            handle.setPropertyString("demuxer-max-back-bytes", options.bufferBackBytes.toString()),
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
        onlinePreparation.clear()
        videoRenderingActive.value = false
        statisticsJob?.cancel()
        statisticsJob = null
        statisticsHandle = null
        nativeEnhancement.release()
        enhancement.release()
        enhancementMediaReady = false
        mutableState.value = state.value.copy(nativeVideoStatistics = null, sourceFps = null, droppedFrames = null, bufferMetrics = null,
            videoEnhancement = AnimeVideoEnhancementState(
            mode = options.videoEnhancement,
            phase = AnimeVideoEnhancementPhase.OFF,
        ))
        videoBeforeBackground = null
        warming = true
        super.releaseNative()
        onlinePreparation.nativeClosed()
    }

}

@Composable
actual fun AnimePlayerSurface(player: AnimePlayer, modifier: Modifier) = NativeAnimeSurface(player, modifier)
