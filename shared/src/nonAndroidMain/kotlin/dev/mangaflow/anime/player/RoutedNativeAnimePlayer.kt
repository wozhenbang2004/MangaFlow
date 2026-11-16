package dev.mangaflow.anime.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.mangaflow.anime.AnimePlaybackAsset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.features.Buffering
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.Subtitle
import org.openani.mediamp.source.UriMediaData

internal abstract class RoutedNativeAnimePlayer(scope: CoroutineScope) : SessionAnimePlayer(scope) {
    val engine = MutableStateFlow<MediampPlayer?>(null)
    private var bridge: AnimeStreamBridge? = null
    private var observers: CoroutineScope? = null
    protected open val externalSubtitles: Boolean = true
    protected abstract suspend fun createBackend(): MediampPlayer
    protected open fun validateAsset(asset: AnimePlaybackAsset) {}

    override suspend fun openNative(request: AnimePlayerRequest, generation: Long) {
        validateAsset(request.asset)
        val transport = AnimeStreamBridge.create(request.asset, playerScope)
        if (!isCurrent(generation)) { transport.close(); return }
        bridge = transport
        val native = createBackend()
        try {
            currentCoroutineContext().ensureActive()
        } catch (failure: Throwable) {
            native.close()
            throw failure
        }
        if (!isCurrent(generation)) { native.close(); return }
        engine.value = native
        val child = CoroutineScope(playerScope.coroutineContext + SupervisorJob(playerScope.coroutineContext[Job]))
        observers = child

        child.launch(Dispatchers.Main.immediate) {
            native.state.collect { fact ->
                if (fact.mediaStatus == MediaStatus.Idle || fact.mediaStatus == MediaStatus.Released) return@collect
                publish(generation) { previous ->
                    val phase = when (fact.mediaStatus) {
                        MediaStatus.Opening -> AnimePlayerPhase.OPENING
                        MediaStatus.Ready -> AnimePlayerPhase.READY
                        MediaStatus.Ended -> AnimePlayerPhase.ENDED
                        is MediaStatus.Error -> AnimePlayerPhase.ERROR
                        MediaStatus.Idle -> AnimePlayerPhase.IDLE
                        MediaStatus.Released -> AnimePlayerPhase.RELEASED
                    }
                    previous.copy(
                        phase = phase,
                        playWhenReady = if (phase == AnimePlayerPhase.ERROR) previous.playWhenReady else fact.playWhenReady,
                        isBuffering = phase == AnimePlayerPhase.OPENING || fact.isBuffering,
                        error = (fact.mediaStatus as? MediaStatus.Error)?.let {
                            transport.lastFailure ?: readablePlayerError(it.error)
                        },
                    )
                }
            }
        }
        child.launch(Dispatchers.Main.immediate) {
            native.currentPositionMillis.collect { position ->
                if (native.state.value.mediaStatus in setOf(MediaStatus.Ready, MediaStatus.Ended))
                    publish(generation) { it.copy(positionMs = position.coerceAtLeast(0L)) }
            }
        }
        child.launch(Dispatchers.Main.immediate) {
            native.mediaProperties.collect { properties ->
                if (properties != null) publish(generation) { it.copy(durationMs = properties.durationMillis ?: -1L) }
            }
        }
        native.features[Buffering]?.let { buffering ->
            child.launch(Dispatchers.Main.immediate) {
                buffering.bufferedPositionMillis.collect { position ->
                    publish(generation) { it.copy(bufferedMs = position.takeIf { value -> value >= 0 } ?: -1L) }
                }
            }
        }
        native.features[PlaybackSpeed]?.set(state.value.speed)
        if (!externalSubtitles && request.asset.subtitles.isNotEmpty()) publish(generation) {
            it.copy(notice = "iOS 使用系统播放器；当前支持 HLS 内嵌字幕，外挂 ASS / SRT 需要切换为内嵌字幕线路")
        }
        val extras = if (externalSubtitles) request.asset.subtitles.map {
            Subtitle(transport.subtitleUrl(it), it.mimeType, it.language, it.label)
        } else emptyList()
        native.setMediaData(
            UriMediaData(transport.mediaUrl, extraFiles = MediaExtraFiles(extras)),
            playWhenReady = state.value.playWhenReady,
            startPositionMillis = state.value.positionMs,
        )
    }

    override fun setPlayingNative(playing: Boolean) {
        engine.value?.let { if (playing) it.play() else it.pause() }
    }
    override fun seekNative(positionMs: Long) { engine.value?.seekTo(positionMs) }
    override fun setSpeedNative(speed: Float) { engine.value?.features?.get(PlaybackSpeed)?.set(speed) }

    override fun releaseNative() {
        observers?.cancel()
        observers = null
        val old = engine.value
        engine.value = null
        bridge?.close()
        bridge = null
        old?.pause()
        old?.close()
    }
}

@Composable
internal fun NativeAnimeSurface(player: AnimePlayer, modifier: Modifier) {
    val native = player as? RoutedNativeAnimePlayer ?: return
    val engine by native.engine.collectAsState()
    val current = engine
    if (current == null) Box(modifier.background(Color.Black))
    else MediampPlayerSurface(current, modifier.background(Color.Black))
}
