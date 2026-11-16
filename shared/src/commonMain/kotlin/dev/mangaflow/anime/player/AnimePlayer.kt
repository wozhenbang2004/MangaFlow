package dev.mangaflow.anime.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.mangaflow.anime.AnimePlaybackAsset
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
)

enum class AnimePlayerPhase { IDLE, OPENING, READY, ENDED, ERROR, RELEASED }

enum class AnimeVideoScale { FIT, FILL, ZOOM }
enum class AnimeTrackKind { AUDIO, SUBTITLE, VIDEO }
enum class AnimePlayerCapability {
    AUDIO_TRACKS, SUBTITLE_TRACKS, VIDEO_TRACKS, AUTOMATIC_AUDIO_TRACK,
    VIDEO_SCALE, VOLUME, PICTURE_IN_PICTURE, BACKGROUND_AUDIO, BUFFER_CONTROL, VIDEO_ENHANCEMENT,
}

data class AnimePlayerTrack(
    val id: String,
    val kind: AnimeTrackKind,
    val label: String,
    val language: String? = null,
    val selected: Boolean = false,
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
) {
    fun requireValid() {
        require(bufferStartMs >= 0 && bufferRebufferMs >= 0 && bufferMinMs >= 0 && bufferMaxMs > 0) {
            "缓冲时间不能为负，最大缓冲时间必须大于零"
        }
        require(bufferMinMs >= bufferStartMs && bufferMinMs >= bufferRebufferMs && bufferMaxMs >= bufferMinMs) {
            "最大缓冲须不小于最小缓冲，最小缓冲须不小于起播和恢复缓冲"
        }
        require(automaticRecoveryAttempts >= 0) { "自动恢复次数不能为负" }
    }
}

data class AnimePlayerState(
    val sessionGeneration: Long = 0L,
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
) {
    val ended: Boolean get() = phase == AnimePlayerPhase.ENDED
    val isPlaying: Boolean get() = phase == AnimePlayerPhase.READY && playWhenReady && !isBuffering
    val bufferedAheadMs: Long get() = if (bufferedMs < 0) -1L else (bufferedMs - positionMs).coerceAtLeast(0L)
}

interface AnimePlayer {
    val state: StateFlow<AnimePlayerState>
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
        releaseNative()
        request = next
        mutableState.value = AnimePlayerState(
            sessionGeneration = previous.sessionGeneration + if (sameSession) 0 else 1, assetKey = next.asset.id,
            episodeId = next.asset.episodeId, phase = AnimePlayerPhase.OPENING,
            playWhenReady = next.playWhenReady && (foreground || previous.inPictureInPicture || options.backgroundAudio),
            positionMs = next.positionMs,
            speed = previous.speed, isBuffering = true,
            firstFrameRendered = if (reportsFirstFrame) false else null,
            capabilities = previous.capabilities,
            videoScale = previous.videoScale, volume = previous.volume,
            inPictureInPicture = previous.inPictureInPicture,
            videoEnhancement = AnimeVideoEnhancementState(options.videoEnhancement,
                if (options.videoEnhancement == AnimeVideoEnhancementMode.OFF) AnimeVideoEnhancementPhase.OFF
                else AnimeVideoEnhancementPhase.WAITING),
        )
        opening = playerScope.launch(Dispatchers.Main.immediate) {
            try {
                openNative(next, generation)
                if (isCurrent(generation)) applyForegroundPolicy()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                if (isCurrent(generation)) {
                    releaseNative()
                    fail(generation, failure)
                }
            }
        }
    }

    final override fun play() = command {
        val current = mutableState.value
        if (current.phase == AnimePlayerPhase.ERROR) {
            request?.let { openNow(it.copy(positionMs = current.positionMs, playWhenReady = true), sameSession = true) }
        } else if (current.phase == AnimePlayerPhase.ENDED) {
            mutableState.value = current.copy(phase = AnimePlayerPhase.READY, positionMs = 0L, playWhenReady = true)
            seekNative(0L)
            setPlayingNative(true)
        } else if (request != null) {
            mutableState.value = current.copy(playWhenReady = true)
            setPlayingNative(true)
        }
    }

    final override fun pause() = command {
        mutableState.value = mutableState.value.copy(playWhenReady = false)
        setPlayingNative(false)
    }

    final override fun seekTo(positionMs: Long) = command {
        if (request == null) return@command
        val current = mutableState.value
        val target = if (current.durationMs > 0) positionMs.coerceIn(0L, current.durationMs)
            else positionMs.coerceAtLeast(0L)
        mutableState.value = current.copy(
            positionMs = target,
            phase = if (current.ended) AnimePlayerPhase.READY else current.phase,
        )
        seekNative(target)
    }

    final override fun setSpeed(speed: Float) = command(fatal = false) {
        require(speed.isFinite() && speed > 0f) { "播放速度必须大于零" }
        val accepted = setSpeedNative(speed)
        check(accepted.isFinite() && accepted > 0f) { "播放器没有接受这个倍速，请选择其他速度" }
        mutableState.value = mutableState.value.copy(speed = accepted)
    }

    final override fun configure(options: AnimePlayerOptions) {
        options.requireValid()
        command(fatal = false) {
            val previous = this.options
            this.options = options
            if (previous.videoEnhancement != options.videoEnhancement) {
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

    protected fun fail(generation: Long, failure: Throwable, sourceRecoveryAllowed: Boolean = true) {
        publish(generation) {
            it.copy(phase = AnimePlayerPhase.ERROR, isBuffering = false, error = readablePlayerError(failure),
                sourceRecoveryAllowed = sourceRecoveryAllowed)
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

    protected abstract suspend fun openNative(request: AnimePlayerRequest, generation: Long)
    protected abstract fun setPlayingNative(playing: Boolean)
    protected abstract fun seekNative(positionMs: Long)
    protected abstract fun setSpeedNative(speed: Float): Float
    protected abstract fun releaseNative()
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

internal fun readablePlayerError(failure: Throwable): String {
    val message = failure.message?.takeIf { it.isNotBlank() } ?: "播放器暂时无法打开这个资源，请重试或换一条线路"
    return message.replace(Regex("https?://[^\\s]+"), "来源地址")
        .replace(Regex("file://[^\\s]+"), "离线文件").take(240)
}
