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
)

enum class AnimePlayerPhase { IDLE, OPENING, READY, ENDED, ERROR, RELEASED }

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

    init {
        playerScope.launch(Dispatchers.Main.immediate) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    closed = true
                    opening?.cancel()
                    releaseNative()
                    mutableState.value = mutableState.value.copy(
                        sessionGeneration = mutableState.value.sessionGeneration + 1,
                        phase = AnimePlayerPhase.RELEASED, playWhenReady = false, isBuffering = false,
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

    private fun openNow(next: AnimePlayerRequest) {
        val generation = mutableState.value.sessionGeneration + 1
        opening?.cancel()
        releaseNative()
        request = next
        mutableState.value = AnimePlayerState(
            sessionGeneration = generation, assetKey = next.asset.id,
            episodeId = next.asset.episodeId, phase = AnimePlayerPhase.OPENING,
            playWhenReady = next.playWhenReady, positionMs = next.positionMs,
            speed = mutableState.value.speed, isBuffering = true,
        )
        opening = playerScope.launch(Dispatchers.Main.immediate) {
            try {
                openNative(next, generation)
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
            request?.let { openNow(it.copy(positionMs = current.positionMs, playWhenReady = true)) }
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

    final override fun setSpeed(speed: Float) = command {
        require(speed.isFinite() && speed > 0f) { "播放速度必须大于零" }
        setSpeedNative(speed)
        mutableState.value = mutableState.value.copy(speed = speed)
    }

    final override fun retry() = command {
        request?.let {
            openNow(it.copy(positionMs = mutableState.value.positionMs, playWhenReady = mutableState.value.playWhenReady))
        }
    }

    final override fun stop() = command {
        opening?.cancel()
        releaseNative()
        request = null
        mutableState.value = AnimePlayerState(
            sessionGeneration = mutableState.value.sessionGeneration + 1,
            speed = mutableState.value.speed,
        )
    }

    final override fun close() {
        playerScope.launch(Dispatchers.Main.immediate) {
            if (closed) return@launch
            closed = true
            opening?.cancel()
            releaseNative()
            request = null
            mutableState.value = mutableState.value.copy(
                sessionGeneration = mutableState.value.sessionGeneration + 1,
                phase = AnimePlayerPhase.RELEASED, playWhenReady = false, isBuffering = false,
            )
            playerScope.cancel()
        }
    }

    protected fun isCurrent(generation: Long): Boolean =
        !closed && generation == mutableState.value.sessionGeneration

    protected fun publish(generation: Long, update: (AnimePlayerState) -> AnimePlayerState) {
        if (isCurrent(generation)) mutableState.value = update(mutableState.value)
    }

    protected fun fail(generation: Long, failure: Throwable) {
        publish(generation) {
            it.copy(phase = AnimePlayerPhase.ERROR, isBuffering = false, error = readablePlayerError(failure))
        }
    }

    private fun command(action: () -> Unit) {
        playerScope.launch(Dispatchers.Main.immediate) {
            if (closed) return@launch
            try {
                action()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                fail(mutableState.value.sessionGeneration, failure)
            }
        }
    }

    protected abstract suspend fun openNative(request: AnimePlayerRequest, generation: Long)
    protected abstract fun setPlayingNative(playing: Boolean)
    protected abstract fun seekNative(positionMs: Long)
    protected abstract fun setSpeedNative(speed: Float)
    protected abstract fun releaseNative()
}

/** Delimit and hash every identity field; tokens and headers never appear in file names or UI. */
internal fun AnimePlaybackAsset.playbackIdentity(): String {
    val pieces = listOf(sourceId, sourceRevision, sessionEpoch.toString(), id, edition, route.toString())
    val identity = pieces.joinToString("") { "${it.length}:$it" }
    return sha256(identity.encodeToByteArray()).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}

internal fun readablePlayerError(failure: Throwable): String {
    val message = failure.message?.takeIf { it.isNotBlank() } ?: "播放器暂时无法打开这个资源，请重试或换一条线路"
    return message.replace(Regex("https?://[^\\s]+"), "来源地址").take(240)
}
