package dev.mangaflow.anime.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.enhancement.engine.EngineModel
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AnimePlayerRequest(
    val asset: AnimePlaybackAsset,
    val positionMs: Long = 0L,
    val playWhenReady: Boolean = true,
    val localMedia: AnimeLocalMedia? = null,
    val bypassMediaCache: Boolean = false,
    /** Rechecked when an online startup stalls or encounters a retryable transport failure. */
    val hasStartupAlternative: (() -> Boolean)? = null,
    /** An opt-in native backend reports an unbroken opening wait, not a transport or decoder error. */
    val onStartupWaitExpired: (suspend (Long, Boolean) -> Unit)? = null,
)

/** Native engine construction failed before the source could be evaluated. */
internal class AnimePlayerInitializationException(message: String, cause: Throwable) :
    IllegalStateException(message, cause)

data class AnimeStartupCacheRequest(
    val asset: AnimePlaybackAsset,
    val positionMs: Long,
    val targetDurationMs: Long,
    val maximumBytes: Long,
    val validUntil: Long,
)

/** Resident original-media bytes for the requested range; a transfer count is not a readiness count. */
data class AnimeStartupCacheResult(
    val bytes: Long,
    val bufferedDurationMs: Long? = null,
    val detail: String? = null,
)

data class AnimePreparedVideoRequest(
    val asset: AnimePlaybackAsset,
    val sessionGeneration: Long,
    val timelineGeneration: Long,
    val startMs: Long,
    val endMs: Long,
    val width: Int,
    val height: Int,
    val model: EngineModel,
    val maximumSourceBytes: Long,
    val maximumResultBytes: Long,
)

data class AnimePreparedVideoWindow(
    val startMs: Long,
    val endMs: Long,
    val frames: Int,
    val bytes: Long,
    val modelName: String,
    val outputWidth: Int,
    val outputHeight: Int,
    val processingMillis: Long,
    val framesUsed: Long = 0L,
    val lastUsedAt: Long = 0L,
    val usageIsSampled: Boolean = false,
)

/** Reuse only a still-useful window from this exact media/model/timeline and preparation budget. */
internal fun AnimePreparedVideoRequest.canReuse(
    previous: AnimePreparedVideoRequest,
    window: AnimePreparedVideoWindow,
    positionMs: Long,
): Boolean = asset == previous.asset && sessionGeneration == previous.sessionGeneration &&
    timelineGeneration == previous.timelineGeneration && model == previous.model &&
    width == previous.width && height == previous.height &&
    endMs - startMs == previous.endMs - previous.startMs &&
    maximumSourceBytes == previous.maximumSourceBytes && maximumResultBytes == previous.maximumResultBytes &&
    window.bytes in 1L..maximumResultBytes && window.endMs > maxOf(positionMs, startMs) && window.startMs < endMs

enum class AnimePlayerPhase { IDLE, OPENING, READY, ENDED, ERROR, RELEASED }

enum class AnimeVideoScale { FIT, FILL, ZOOM }
enum class AnimeTrackKind { AUDIO, SUBTITLE, VIDEO }
enum class AnimePlayerCapability {
    AUDIO_TRACKS, SUBTITLE_TRACKS, VIDEO_TRACKS, AUTOMATIC_AUDIO_TRACK,
    VIDEO_SCALE, VOLUME, PICTURE_IN_PICTURE, BACKGROUND_AUDIO, BUFFER_CONTROL, VIDEO_ENHANCEMENT, MODEL_ENHANCEMENT,
}

data class AnimePlayerTrack(
    val id: String,
    val kind: AnimeTrackKind,
    val label: String,
    val language: String? = null,
    val selected: Boolean = false,
)

data class AnimeRealtimeFrameRequest(val model: EngineModel, val targetFps: Double?, val automatic: Boolean = false)

data class AnimeRealtimeMotionState(
    val modelName: String,
    val phase: AnimeVideoEnhancementPhase,
    val detail: String,
    val synthesizedFrames: Long = 0L,
    val graphFps: Double? = null,
    val generationFps: Double? = null,
    val inferenceNanos: Long? = null,
    /** Measured at the interpolation program's actual input, before subsequent upscaling. */
    val inputWidth: Int? = null,
    val inputHeight: Int? = null,
    /** Resolved from this program's source timestamps/display ceiling; never an output receipt. */
    val targetFps: Double? = null,
    /** Interpolated PTS matched by the final video-output release callback; not physical Present. */
    val outputInterpolatedFrames: Long? = null,
)

/** mpv property observations; filter throughput and delayed-frame estimates are not display presentation. */
data class AnimeNativeVideoStatistics(
    val sampledAtMillis: Long,
    val hardwareDecoder: String?,
    val videoOutput: String?,
    val decoderDroppedFrames: Long?,
    val outputDroppedFrames: Long?,
    val estimatedFilterFps: Double?,
    val estimatedDelayedFrames: Long?,
    /** Frame size after the video filters, before the video renderer's scaling and shaders. */
    val filterOutputSize: IntSize? = null,
    /** mpv's decoded input size, without the container-size fallback. */
    val decoderInputSize: IntSize? = null,
    /** DirectML spatial output corroborated by its live model receipt and native filter dimensions. */
    val confirmedSpatialOutputSize: IntSize? = null,
)

/** Native compressed-buffer observations. Missing measurements are deliberately not inferred from targets. */
data class AnimeBufferMetrics(
    val allocatedBytes: Long? = null,
    val forwardBytes: Long? = null,
    val backBytes: Long? = null,
    val targetForwardBytes: Long? = null,
    val targetBackBytes: Long? = null,
    val sampledAtMillis: Long? = null,
    val detail: String? = null,
    val targetDurationMs: Long? = null,
    val refillBelowMs: Long? = null,
    val isLoading: Boolean? = null,
)

/** Buffer times are targets, not a claim about memory use or startup latency. */
data class AnimePlayerOptions(
    val backgroundAudio: Boolean = false,
    val bufferMinMs: Int = 50_000,
    val bufferMaxMs: Int = 120_000,
    val bufferStartMs: Int = 1_500,
    val bufferRebufferMs: Int = 3_000,
    val automaticRecoveryAttempts: Int = 2,
    val videoEnhancement: AnimeVideoEnhancementMode = AnimeVideoEnhancementMode.OFF,
    val enhancementScale: Int = 2,
    val enhancementModel: EngineModel? = null,
    val preparedVideoModel: EngineModel? = null,
    val realtimeFrames: AnimeRealtimeFrameRequest? = null,
    val bufferForwardBytes: Long = 64L * 1024L * 1024L,
    val bufferBackBytes: Long = 8L * 1024L * 1024L,
    val bufferRefillNearTarget: Boolean = true,
    /** Raw user viewing intent; resource-driven projection changes do not increment it. */
    val realtimeMotionIntent: Long = 0L,
    /** A manual 2× choice may run a desktop shader even at the source's display size. */
    val enhanceAtNativeSize: Boolean = false,
) {
    /** A small refill band preserves the saved low/high watermarks and bounds request churn. */
    val effectiveBufferRefillMs: Int get() = if (!bufferRefillNearTarget) bufferMinMs else
        maxOf(bufferMinMs, bufferMaxMs - minOf(5_000, maxOf(1_000, bufferMaxMs / 10)))
            .coerceAtMost(bufferMaxMs)

    fun requireValid() {
        require(if (videoEnhancement == AnimeVideoEnhancementMode.MODEL)
            enhancementScale in setOf(1, 2, 4) && enhancementModel?.nativeScale == enhancementScale
        else enhancementScale in 1..2 && enhancementModel == null) { "实时模型与所选倍率不兼容" }
        require(preparedVideoModel == null || preparedVideoModel.nativeScale in setOf(1, 2, 4))
        require(realtimeFrames?.targetFps?.let { it.isFinite() && it in 1.0..240.0 } != false)
        require(bufferStartMs >= 0 && bufferRebufferMs >= 0 && bufferMinMs >= 0 && bufferMaxMs > 0) {
            "缓冲时间不能为负，最大缓冲时间必须大于零"
        }
        require(bufferMinMs >= bufferStartMs && bufferMinMs >= bufferRebufferMs && bufferMaxMs >= bufferMinMs) {
            "最大缓冲须不小于最小缓冲，最小缓冲须不小于起播和恢复缓冲"
        }
        require(automaticRecoveryAttempts >= 0) { "自动恢复次数不能为负" }
        require(bufferForwardBytes > 0L && bufferBackBytes >= 0L) { "前方缓冲容量必须大于零，回看容量不能为负" }
    }
}

data class AnimePlayerState(
    val sessionGeneration: Long = 0L,
    /** Seek invalidates prepared frames and measurements without changing the saved episode identity. */
    val timelineGeneration: Long = 0L,
    val assetKey: String? = null,
    val episodeId: String? = null,
    val phase: AnimePlayerPhase = AnimePlayerPhase.IDLE,
    val playWhenReady: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = -1L,
    /** Absolute end of the buffered timeline. -1 means the backend cannot report it. */
    val bufferedMs: Long = -1L,
    val speed: Float = 1f,
    val error: String? = null,
    val isBuffering: Boolean = false,
    val notice: String? = null,
    /** null means this backend does not expose an actual rendered-frame acknowledgement. */
    val firstFrameRendered: Boolean? = null,
    val firstFrameAtMillis: Long? = null,
    val tracks: List<AnimePlayerTrack> = emptyList(),
    val capabilities: Set<AnimePlayerCapability> = emptySet(),
    val videoScale: AnimeVideoScale = AnimeVideoScale.FIT,
    val volume: Float = 1f,
    val recoveryAttempt: Int = 0,
    val inPictureInPicture: Boolean = false,
    /** Actual received media bytes per second; -1 means unavailable. */
    val downloadBytesPerSecond: Long = -1L,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoEnhancement: AnimeVideoEnhancementState = AnimeVideoEnhancementState(),
    /** A local renderer failure is not evidence that another provider should be selected. */
    val sourceRecoveryAllowed: Boolean = true,
    /** The platform observed a first-start failure that can benefit from resolving the same line again. */
    val refreshSourceOnStartupFailure: Boolean = false,
    /** null until track discovery; false requires observed audio tracks and no video group. */
    val hasVideoTracks: Boolean? = null,
    /** Native playback is temporarily suppressed, for example by audio focus loss. */
    val playbackSuppressed: Boolean = false,
    val preEnhanced: Boolean = false,
    val sourceFps: Double? = null,
    val displayRefreshHz: Double? = null,
    /** Native decoder output is not a display presentation acknowledgement. */
    val decoderOutputFrames: Long? = null,
    val droppedFrames: Long? = null,
    val presentedFps: Double? = null,
    val presentedFrames: Long? = null,
    val frameMetrics: AnimePlaybackFrameMetrics? = null,
    val preparedVideoWindow: AnimePreparedVideoWindow? = null,
    /** A detached completed window still occupies memory/disk until reused or explicitly evicted. */
    val preparedVideoRetainedBytes: Long = 0L,
    val preparedVideoIssue: String? = null,
    val realtimeMotion: AnimeRealtimeMotionState? = null,
    val nativeVideoStatistics: AnimeNativeVideoStatistics? = null,
    val bufferMetrics: AnimeBufferMetrics? = null,
    /** Measured bounds of the playback surface in pixels, including any letterboxing. */
    val playbackViewportSize: IntSize? = null,
    /** Cumulative elapsed visible video playback in this session; null without native observations. */
    val visibleWatchMillis: Long? = null,
    /** Cumulative upstream bytes for this online media session. Local cache hits are excluded. */
    val receivedNetworkBytes: Long? = null,
) {
    val ended: Boolean get() = phase == AnimePlayerPhase.ENDED
    val isPlaying: Boolean get() = phase == AnimePlayerPhase.READY && playWhenReady && !isBuffering
    val bufferedAheadMs: Long get() = if (bufferedMs < 0) -1L else (bufferedMs - positionMs).coerceAtLeast(0L)
}

interface AnimePlayer {
    val state: StateFlow<AnimePlayerState>
    /** Optional original-media bytes confirmed in the backend's playback cache. */
    suspend fun cacheStartup(asset: AnimePlaybackAsset, byteLimit: Long, validUntil: Long): Long = 0L
    suspend fun prepareStartupCache(request: AnimeStartupCacheRequest): AnimeStartupCacheResult =
        AnimeStartupCacheResult(cacheStartup(request.asset, request.maximumBytes, request.validUntil))
    /** True requires a retained native backend with a decoded frame for the exact start position. */
    suspend fun prepareStartup(asset: AnimePlaybackAsset, positionMs: Long, byteLimit: Long, validUntil: Long): Boolean =
        false
    fun discardStartup(asset: AnimePlaybackAsset? = null) {}
    suspend fun prepareVideoWindow(request: AnimePreparedVideoRequest): Boolean = false
    fun noteVideoPreparationIssue(detail: String?) {}
    fun discardPreparedVideo(retainReusable: Boolean = false) {}
    fun open(request: AnimePlayerRequest)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    fun configure(options: AnimePlayerOptions)
    fun setVideoScale(scale: AnimeVideoScale)
    fun setVolume(volume: Float)
    /** null disables subtitles; automatic audio/video requires the corresponding capability. */
    fun selectTrack(kind: AnimeTrackKind, id: String?)
    fun setForeground(foreground: Boolean)
    fun enterPictureInPicture()
    fun retry()
    fun stop()
    fun close()
}

expect fun createAnimePlayer(context: PlatformContext, scope: CoroutineScope): AnimePlayer

/** Registers persisted native cache ownership without constructing a playback session. */
expect fun initializeAnimeStorage(context: PlatformContext)

/** Surface only: app-owned playback survives disposal of the screen. */
@Composable
expect fun AnimePlayerSurface(player: AnimePlayer, modifier: Modifier = Modifier)

/**
 * Commands and native observations are confined to Main. Each open owns a fresh
 * backend and generation; no late completion from a replaced source can publish.
 * The supplied application scope is the sole lifetime owner.
 */
internal abstract class SessionAnimePlayer(applicationScope: CoroutineScope) : AnimePlayer {
    protected val playerScope = CoroutineScope(
        applicationScope.coroutineContext + SupervisorJob(applicationScope.coroutineContext[Job]),
    )
    protected val mutableState = MutableStateFlow(AnimePlayerState())
    final override val state: StateFlow<AnimePlayerState> = mutableState.asStateFlow()
    protected var request: AnimePlayerRequest? = null
        private set
    private var opening: Job? = null
    private var closed = false
    /** Native attempts may be replaced without changing the controller's media-session identity. */
    protected var nativeGeneration = 0L
        private set
    protected var options = AnimePlayerOptions()
        private set
    protected var foreground = true
        private set
    protected open val reportsFirstFrame: Boolean = false

    init {
        playerScope.launch(Dispatchers.Main.immediate) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    closed = true
                    nativeGeneration++
                    opening?.cancel()
                    onPlaybackEnding(null)
                    releaseNative()
                    mutableState.value = mutableState.value.copy(
                        sessionGeneration = mutableState.value.sessionGeneration + 1,
                        phase = AnimePlayerPhase.RELEASED, playWhenReady = false, isBuffering = false,
                        videoEnhancement = AnimeVideoEnhancementState(options.videoEnhancement),
                    )
                }
            }
        }
    }

    final override fun open(request: AnimePlayerRequest) = command {
        val asset = request.asset.copy(
            headers = request.asset.headers.toMap(),
            subtitles = request.asset.subtitles.map { it.copy(headers = it.headers.toMap()) },
        )
        openNow(request.copy(asset = asset, positionMs = request.positionMs.coerceAtLeast(0L)))
    }

    private fun openNow(next: AnimePlayerRequest, sameSession: Boolean = false) {
        val previous = mutableState.value
        val generation = ++nativeGeneration
        opening?.cancel()
        onPlaybackEnding(next)
        releaseNative()
        request = next
        mutableState.value = AnimePlayerState(
            sessionGeneration = previous.sessionGeneration + if (sameSession) 0 else 1,
            timelineGeneration = previous.timelineGeneration + 1,
            assetKey = next.asset.id,
            episodeId = next.asset.episodeId, phase = AnimePlayerPhase.OPENING,
            playWhenReady = next.playWhenReady && (foreground || previous.inPictureInPicture || options.backgroundAudio),
            positionMs = next.positionMs,
            speed = previous.speed, isBuffering = true,
            firstFrameRendered = if (reportsFirstFrame) false else null,
            capabilities = previous.capabilities,
            videoScale = previous.videoScale, volume = previous.volume,
            inPictureInPicture = previous.inPictureInPicture,
            preEnhanced = next.localMedia?.enhancedVersion == true,
            videoEnhancement = AnimeVideoEnhancementState(options.videoEnhancement,
                if (options.videoEnhancement == AnimeVideoEnhancementMode.OFF) AnimeVideoEnhancementPhase.OFF
                else AnimeVideoEnhancementPhase.WAITING),
            visibleWatchMillis = previous.visibleWatchMillis.takeIf { sameSession },
        )
        opening = playerScope.launch(Dispatchers.Main.immediate) {
            try {
                openNative(next, generation)
                if (isCurrent(generation)) applyForegroundPolicy()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                if (isCurrent(generation)) {
                    onPlaybackEnding(null)
                    releaseNative()
                    fail(generation, failure,
                        sourceRecoveryAllowed = failure !is AnimePlayerInitializationException)
                }
            }
        }
    }

    final override fun play() = command {
        val current = mutableState.value
        if (current.phase == AnimePlayerPhase.ERROR) {
            request?.let { openNow(it.copy(positionMs = current.positionMs, playWhenReady = true), sameSession = true) }
        } else if (current.phase == AnimePlayerPhase.ENDED) {
            mutableState.value = current.copy(phase = AnimePlayerPhase.READY, positionMs = 0L, playWhenReady = true,
                timelineGeneration = current.timelineGeneration + 1,
                bufferedMs = -1L, bufferMetrics = null, nativeVideoStatistics = null,
                sourceFps = null, decoderOutputFrames = null, droppedFrames = null,
                presentedFps = null, presentedFrames = null, frameMetrics = null,
                videoEnhancement = current.videoEnhancement.copy(observation = null))
            seekNative(0L)
            setPlayingNative(true)
        } else if (request != null) {
            mutableState.value = current.copy(playWhenReady = true)
            setPlayingNative(true)
        }
    }

    final override fun pause() = command {
        mutableState.value = mutableState.value.copy(playWhenReady = false, frameMetrics = null)
        setPlayingNative(false)
    }

    final override fun seekTo(positionMs: Long) = command {
        if (request == null) return@command
        val current = mutableState.value
        val target = if (current.durationMs > 0) positionMs.coerceIn(0L, current.durationMs)
            else positionMs.coerceAtLeast(0L)
        mutableState.value = current.copy(
            positionMs = target,
            timelineGeneration = current.timelineGeneration + 1,
            bufferedMs = -1L, bufferMetrics = null, nativeVideoStatistics = null,
            sourceFps = null, decoderOutputFrames = null, droppedFrames = null,
            presentedFps = null, presentedFrames = null, frameMetrics = null,
            videoEnhancement = current.videoEnhancement.copy(observation = null),
            phase = if (current.ended) AnimePlayerPhase.READY else current.phase,
        )
        seekNative(target)
    }

    final override fun setSpeed(speed: Float) = command(fatal = false) {
        require(speed.isFinite() && speed > 0f) { "播放速度必须大于零" }
        val accepted = setSpeedNative(speed)
        check(accepted.isFinite() && accepted > 0f) { "播放器没有接受这个倍速，请选择其他速度" }
        mutableState.value = mutableState.value.copy(speed = accepted, frameMetrics = null)
    }

    final override fun configure(options: AnimePlayerOptions) {
        options.requireValid()
        command(fatal = false) {
            val previous = this.options
            this.options = options
            if (previous.videoEnhancement != options.videoEnhancement || previous.enhancementScale != options.enhancementScale ||
                previous.enhancementModel != options.enhancementModel || previous.realtimeFrames != options.realtimeFrames ||
                previous.preparedVideoModel != options.preparedVideoModel ||
                previous.realtimeMotionIntent != options.realtimeMotionIntent)
                mutableState.value = mutableState.value.copy(frameMetrics = null)
            if (previous.videoEnhancement != options.videoEnhancement || previous.enhancementScale != options.enhancementScale ||
                previous.enhancementModel != options.enhancementModel) {
                mutableState.value = mutableState.value.copy(videoEnhancement = AnimeVideoEnhancementState(
                    options.videoEnhancement,
                    if (options.videoEnhancement == AnimeVideoEnhancementMode.OFF) AnimeVideoEnhancementPhase.OFF
                    else AnimeVideoEnhancementPhase.WAITING,
                ))
            }
            configureNative(previous, options)
            applyForegroundPolicy()
        }
    }

    final override fun setVideoScale(scale: AnimeVideoScale) = command(fatal = false) {
        check(AnimePlayerCapability.VIDEO_SCALE in state.value.capabilities) { "当前播放器不支持调整画幅" }
        setVideoScaleNative(scale)
        mutableState.value = mutableState.value.copy(videoScale = scale)
    }

    final override fun setVolume(volume: Float) = command(fatal = false) {
        require(volume.isFinite() && volume in 0f..1f) { "音量须在 0 到 100% 之间" }
        check(AnimePlayerCapability.VOLUME in state.value.capabilities) { "当前播放器不支持调整音量" }
        setVolumeNative(volume)
        mutableState.value = mutableState.value.copy(volume = volume)
    }

    final override fun selectTrack(kind: AnimeTrackKind, id: String?) = command(fatal = false) {
        if (id != null) check(state.value.tracks.any { it.kind == kind && it.id == id }) {
            "这条轨道已变化，请重新打开轨道列表"
        }
        selectTrackNative(kind, id)
    }

    final override fun setForeground(foreground: Boolean) = command(fatal = false) {
        this.foreground = foreground
        if (!foreground) cancelStartupWait()
        applyForegroundPolicy()
    }

    private fun applyForegroundPolicy() {
        if (!foreground && !state.value.inPictureInPicture && !options.backgroundAudio) {
            // Returning to the foreground must not undo an intentional or system pause.
            mutableState.value = mutableState.value.copy(playWhenReady = false)
            setPlayingNative(false)
        }
        setForegroundNative(foreground || state.value.inPictureInPicture, options.backgroundAudio)
    }

    final override fun enterPictureInPicture() = command(fatal = false) {
        check(AnimePlayerCapability.PICTURE_IN_PICTURE in state.value.capabilities) { "当前设备无法开启画中画" }
        enterPictureInPictureNative()
    }

    protected fun pictureInPictureChanged(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(inPictureInPicture = enabled)
        applyForegroundPolicy()
    }

    final override fun retry() = command {
        request?.let {
            openNow(it.copy(positionMs = mutableState.value.positionMs, playWhenReady = mutableState.value.playWhenReady),
                sameSession = true)
        }
    }

    final override fun stop() = command {
        nativeGeneration++
        opening?.cancel()
        onPlaybackEnding(null)
        releaseNative()
        request = null
        mutableState.value = AnimePlayerState(
            sessionGeneration = mutableState.value.sessionGeneration + 1,
            speed = mutableState.value.speed,
            capabilities = mutableState.value.capabilities,
            videoScale = mutableState.value.videoScale, volume = mutableState.value.volume,
            inPictureInPicture = mutableState.value.inPictureInPicture,
            videoEnhancement = AnimeVideoEnhancementState(options.videoEnhancement,
                if (options.videoEnhancement == AnimeVideoEnhancementMode.OFF) AnimeVideoEnhancementPhase.OFF
                else AnimeVideoEnhancementPhase.WAITING),
        )
    }

    final override fun close() {
        playerScope.launch(Dispatchers.Main.immediate) {
            if (closed) return@launch
            closed = true
            nativeGeneration++
            opening?.cancel()
            onPlaybackEnding(null)
            releaseNative()
            request = null
            mutableState.value = mutableState.value.copy(
                sessionGeneration = mutableState.value.sessionGeneration + 1,
                phase = AnimePlayerPhase.RELEASED, playWhenReady = false, isBuffering = false,
                videoEnhancement = AnimeVideoEnhancementState(options.videoEnhancement),
            )
            playerScope.cancel()
        }
    }

    protected fun isCurrent(generation: Long): Boolean =
        !closed && generation == nativeGeneration

    protected fun publish(generation: Long, update: (AnimePlayerState) -> AnimePlayerState) {
        if (isCurrent(generation)) mutableState.value = update(mutableState.value)
    }

    protected fun fail(generation: Long, failure: Throwable, sourceRecoveryAllowed: Boolean = true,
        refreshSourceOnStartupFailure: Boolean = false) {
        publish(generation) {
            it.copy(phase = AnimePlayerPhase.ERROR, isBuffering = false, error = readablePlayerError(failure),
                sourceRecoveryAllowed = sourceRecoveryAllowed, refreshSourceOnStartupFailure = refreshSourceOnStartupFailure)
        }
    }

    protected fun command(fatal: Boolean = true, action: () -> Unit) {
        playerScope.launch(Dispatchers.Main.immediate) {
            if (closed) return@launch
            try {
                action()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                if (fatal) fail(nativeGeneration, failure)
                else mutableState.value = mutableState.value.copy(notice = readablePlayerError(failure))
            }
        }
    }

    protected open fun cancelStartupWait() {}
    protected abstract suspend fun openNative(request: AnimePlayerRequest, generation: Long)
    protected abstract fun setPlayingNative(playing: Boolean)
    protected abstract fun seekNative(positionMs: Long)
    protected abstract fun setSpeedNative(speed: Float): Float
    protected abstract fun releaseNative()
    protected open fun onPlaybackEnding(next: AnimePlayerRequest?) {
        if (next == null) discardStartup()
    }
    protected open fun configureNative(previous: AnimePlayerOptions, next: AnimePlayerOptions) {}
    protected open fun setVideoScaleNative(scale: AnimeVideoScale) {}
    protected open fun setVolumeNative(volume: Float) { error("当前播放器不支持调整音量") }
    protected open fun selectTrackNative(kind: AnimeTrackKind, id: String?) { error("当前播放器不支持选择轨道") }
    protected open fun setForegroundNative(foreground: Boolean, backgroundAudio: Boolean) {}
    protected open fun enterPictureInPictureNative() { error("当前设备无法开启画中画") }
}

/** Delimit and hash every identity field; tokens and headers never appear in file names or UI. */
internal fun AnimePlaybackAsset.playbackIdentity(): String {
    val pieces = listOf(sourceId, sourceRevision, sessionEpoch.toString(), id, edition, route.toString())
    val identity = pieces.joinToString("") { "${it.length}:$it" }
    return sha256(identity.encodeToByteArray()).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}

internal fun isAnimeStartupVideoPrefix(bytes: ByteArray, size: Int = bytes.size): Boolean {
    val mp4 = size >= 8 && bytes.decodeToString(4, 8) in setOf("ftyp", "styp", "moof", "sidx", "moov")
    val transport = size >= 5 * 188 && (0 until 5).all { bytes[it * 188] == 0x47.toByte() }
    val matroska = size >= 4 && bytes[0] == 0x1a.toByte() && bytes[1] == 0x45.toByte() &&
        bytes[2] == 0xdf.toByte() && bytes[3] == 0xa3.toByte()
    return mp4 || transport || matroska
}

internal const val ANIME_STARTUP_MEDIA_PREFIX_BYTES = 5 * 188

/** Optional preparation accepts the same proved container/audio headers on every platform. */
internal fun isAnimeStartupMediaPrefix(
    bytes: ByteArray,
    size: Int = bytes.size,
    audio: Boolean = false,
    mp4Only: Boolean = false,
    imagePrefixedTransport: Boolean = false,
): Boolean {
    if (mp4Only) return size >= 8 && bytes.decodeToString(4, 8) in setOf("ftyp", "styp", "moof", "sidx", "moov")
    if (isAnimeStartupVideoPrefix(bytes, size)) return true
    if (imagePrefixedTransport && animeHlsTransportOffset(bytes, size) > 0) return true
    if (!audio) return false
    var offset = 0
    if (size >= 10 && bytes.decodeToString(0, 3) == "ID3") {
        if ((6..9).any { bytes[it].toInt() and 0x80 != 0 }) return false
        val tagSize = (6..9).fold(0) { value, index -> (value shl 7) or (bytes[index].toInt() and 0x7f) }
        offset = 10 + tagSize
    }
    if (offset < 0 || size - offset < 7) return false
    val first = bytes[offset].toInt() and 0xff
    val second = bytes[offset + 1].toInt() and 0xff
    val adts = first == 0xff && second and 0xf6 == 0xf0
    val ac3 = first == 0x0b && second == 0x77
    return adts || ac3
}

internal fun readablePlayerError(failure: Throwable): String {
    val message = failure.message?.takeIf { it.isNotBlank() } ?: "播放器暂时无法打开这个资源，请重试或换一条线路"
    return message.replace(Regex("https?://[^\\s]+"), "来源地址")
        .replace(Regex("file://[^\\s]+"), "离线文件").take(240)
}
