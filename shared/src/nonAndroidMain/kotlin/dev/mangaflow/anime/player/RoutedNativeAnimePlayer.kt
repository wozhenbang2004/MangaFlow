@file:OptIn(org.openani.mediamp.ExperimentalMediampApi::class)

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.PlaybackException
import org.openani.mediamp.PlaybackErrorCode
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.features.Buffering
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.AspectRatioMode
import org.openani.mediamp.features.MediaMetadata
import org.openani.mediamp.features.NetworkStats
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.VideoAspectRatio
import org.openani.mediamp.metadata.AudioTrack
import org.openani.mediamp.metadata.SubtitleTrack
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.Subtitle
import org.openani.mediamp.source.UriMediaData

internal abstract class RoutedNativeAnimePlayer(scope: CoroutineScope) : SessionAnimePlayer(scope) {
    val engine = MutableStateFlow<MediampPlayer?>(null)
    private var bridge: AnimeStreamBridge? = null
    private var localBridge: AnimeLocalStreamBridge? = null
    private var localLease: AnimeLocalMediaLease? = null
    private var observers: CoroutineScope? = null
    private var loadingNative: Any? = null
    private var recovery: Job? = null
    private var recoveryNotice: String? = null
    private var audioTracks = emptyList<AudioTrack>()
    private var subtitleTracks = emptyList<SubtitleTrack>()
    protected open val externalSubtitles: Boolean = true
    protected open val supportsBufferControl: Boolean = false
    protected abstract suspend fun createBackend(): MediampPlayer
    protected open fun validateAsset(asset: AnimePlaybackAsset) {}
    protected open fun validateLocalMedia(lease: AnimeLocalMediaLease) {}

    override suspend fun openNative(request: AnimePlayerRequest, generation: Long) {
        val transport = if (request.localMedia == null) {
            validateAsset(request.asset)
            AnimeStreamBridge.create(request.asset, playerScope).also {
                if (!isCurrent(generation)) { it.close(); return }
                bridge = it
            }
        } else {
            var pending: AnimeLocalMediaLease? = null
            try {
                val lease = withContext(Dispatchers.IO) { request.localMedia.acquire(request.asset).also { pending = it } }
                currentCoroutineContext().ensureActive()
                if (!isCurrent(generation)) return
                validateLocalMedia(lease)
                localLease = lease
                pending = null
                val server = AnimeLocalStreamBridge.create(lease, playerScope)
                if (!isCurrent(generation)) { server.close(); return }
                localBridge = server
            } finally { pending?.close() }
            null
        }
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
        val metadata = native.features[MediaMetadata]
        publish(generation) { it.copy(capabilities = buildSet {
            add(AnimePlayerCapability.BACKGROUND_AUDIO)
            if (supportsBufferControl) add(AnimePlayerCapability.BUFFER_CONTROL)
            if (native.features[VideoAspectRatio] != null) add(AnimePlayerCapability.VIDEO_SCALE)
            if (native.features[AudioLevelController] != null) add(AnimePlayerCapability.VOLUME)
            if (metadata?.audioTracks != null) add(AnimePlayerCapability.AUDIO_TRACKS)
            if (metadata?.subtitleTracks != null) add(AnimePlayerCapability.SUBTITLE_TRACKS)
        }) }

        child.launch(Dispatchers.Main.immediate) {
            native.state.collect { fact ->
                if (fact.mediaStatus == MediaStatus.Idle || fact.mediaStatus == MediaStatus.Released) return@collect
                val nativeError = fact.mediaStatus as? MediaStatus.Error
                if (nativeError != null) {
                    if (loadingNative == null && recovery?.isActive != true && isCurrent(generation)) {
                        recovery = child.launch(Dispatchers.Main.immediate) {
                            try { loadMedia(native, transport, request, generation, nativeError.error) }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (failure: Throwable) {
                                fail(generation, failure)
                                transport?.lastFailure?.let { message -> publish(generation) { it.copy(error = message) } }
                            }
                        }
                    }
                    return@collect
                }
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
                        error = null,
                        notice = if (phase == AnimePlayerPhase.READY && !fact.isBuffering &&
                            previous.notice == recoveryNotice) null else previous.notice,
                    )
                }
                if (isCurrent(generation) && engine.value === native) {
                    if (fact.mediaStatus == MediaStatus.Ready && !fact.isBuffering) recoveryNotice = null
                    onPlaybackStateNative()
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
                if (properties != null) publish(generation) { it.copy(
                    durationMs = properties.durationMillis ?: -1L,
                    videoWidth = properties.videoWidth, videoHeight = properties.videoHeight,
                ) }
            }
        }
        native.features[Buffering]?.let { buffering ->
            child.launch(Dispatchers.Main.immediate) {
                buffering.bufferedPositionMillis.collect { position ->
                    publish(generation) { it.copy(bufferedMs = position.takeIf { value -> value >= 0 } ?: -1L) }
                }
            }
        }
        native.features[NetworkStats]?.let { network ->
            child.launch(Dispatchers.Main.immediate) {
                network.downloadSpeedBytesPerSecond.collect { speed ->
                    publish(generation) { it.copy(downloadBytesPerSecond = if (request.localMedia != null) -1L
                        else speed.takeIf { it >= 0L } ?: -1L) }
                }
            }
        }
        metadata?.audioTracks?.let { group ->
            child.launch(Dispatchers.Main.immediate) {
                combine(group.candidates, group.selected) { candidates, selected -> candidates to selected }
                    .collect { (candidates, selected) ->
                        if (!isCurrent(generation) || engine.value !== native) return@collect
                        audioTracks = candidates
                        publish(generation) { previous -> previous.copy(tracks =
                            previous.tracks.filterNot { it.kind == AnimeTrackKind.AUDIO } + candidates.mapIndexed { index, track ->
                                AnimePlayerTrack("$generation:audio:${track.id}", AnimeTrackKind.AUDIO,
                                    track.labels.map { it.value }.filter(String::isNotBlank).distinct().joinToString(" · ")
                                        .ifBlank { track.name?.takeIf(String::isNotBlank) ?: "音轨 ${index + 1}" },
                                    selected = track == selected)
                            }) }
                    }
            }
        }
        metadata?.subtitleTracks?.let { group ->
            child.launch(Dispatchers.Main.immediate) {
                combine(group.candidates, group.selected) { candidates, selected -> candidates to selected }
                    .collect { (candidates, selected) ->
                        if (!isCurrent(generation) || engine.value !== native) return@collect
                        subtitleTracks = candidates
                        publish(generation) { previous -> previous.copy(tracks =
                            previous.tracks.filterNot { it.kind == AnimeTrackKind.SUBTITLE } + candidates.mapIndexed { index, track ->
                                AnimePlayerTrack("$generation:subtitle:${track.id}", AnimeTrackKind.SUBTITLE,
                                    track.labels.map { it.value }.filter(String::isNotBlank).distinct().joinToString(" · ")
                                        .ifBlank { track.language?.takeIf(String::isNotBlank) ?: "字幕 ${index + 1}" },
                                    language = track.language, selected = track == selected)
                            }) }
                    }
            }
        }
        native.features[PlaybackSpeed]?.set(state.value.speed)
        native.features[PlaybackSpeed]?.let { playbackSpeed ->
            child.launch(Dispatchers.Main.immediate) {
                playbackSpeed.valueFlow.collect { speed ->
                    if (speed.isFinite() && speed > 0f) publish(generation) { it.copy(speed = speed) }
                }
            }
        }
        setVideoScaleNative(state.value.videoScale)
        native.features[AudioLevelController]?.setVolume(state.value.volume)
        native.features[AudioLevelController]?.let { audio ->
            child.launch(Dispatchers.Main.immediate) {
                audio.volume.collect { volume -> publish(generation) { it.copy(volume = volume.coerceIn(0f, 1f)) } }
            }
        }
        if (!externalSubtitles && request.asset.subtitles.isNotEmpty()) publish(generation) {
            it.copy(notice = "iOS 使用系统播放器；当前支持 HLS 内嵌字幕，外挂 ASS / SRT 需要切换为内嵌字幕线路")
        }
        loadMedia(native, transport, request, generation)
    }

    private suspend fun loadMedia(
        native: MediampPlayer, transport: AnimeStreamBridge?, request: AnimePlayerRequest,
        generation: Long, initialFailure: PlaybackException? = null,
    ) {
        val extras = if (externalSubtitles && transport != null) request.asset.subtitles.map {
            Subtitle(transport.subtitleUrl(it), it.mimeType, it.language, it.label)
        } else emptyList()
        var failure = initialFailure
        val operation = Any()
        loadingNative = operation
        try {
            onMediaOpeningNative()
            while (isCurrent(generation) && engine.value === native) {
                failure?.let { error ->
                    val httpStatus = transport?.lastFailureStatus
                    val mayRetry = transport != null && error.code == PlaybackErrorCode.IO &&
                        transport.lastFailureRetryable != false &&
                        (httpStatus == null || httpStatus >= 500 || httpStatus == 408)
                    if (!mayRetry || state.value.recoveryAttempt >= options.automaticRecoveryAttempts) {
                        publish(generation) { it.copy(notice = it.notice.takeUnless { value -> value == recoveryNotice }) }
                        recoveryNotice = null
                        throw IllegalStateException(transport?.lastFailure ?: when (error.code) {
                            PlaybackErrorCode.IO -> if (transport == null) "离线媒体暂时无法读取，请检查文件是否完整或重新下载"
                                else "当前播放路由未能连接来源，请重试或检查该来源的网络设置"
                            PlaybackErrorCode.DECODING, PlaybackErrorCode.UNSUPPORTED_FORMAT ->
                                "当前设备无法解码这条线路，可选择其他格式或清晰度"
                            PlaybackErrorCode.ACCESS_DENIED -> "来源拒绝访问，请刷新线路或更换来源"
                            PlaybackErrorCode.INTERNAL -> readablePlayerError(error)
                        }, error)
                    }
                    val attempt = state.value.recoveryAttempt + 1
                    recoveryNotice = "连接中断，正在恢复播放（$attempt）"
                    publish(generation) { it.copy(phase = AnimePlayerPhase.OPENING, error = null,
                        isBuffering = true, recoveryAttempt = attempt, notice = recoveryNotice) }
                    delay(750L * attempt.coerceAtMost(4))
                }
                currentCoroutineContext().ensureActive()
                if (!isCurrent(generation) || engine.value !== native) return
                try {
                    transport?.beginAttempt()
                    val mediaUrl = transport?.mediaUrl ?: checkNotNull(localBridge).mediaUrl
                    native.setMediaData(UriMediaData(mediaUrl, extraFiles = MediaExtraFiles(extras)),
                        playWhenReady = state.value.playWhenReady, startPositionMillis = state.value.positionMs)
                    if (isCurrent(generation)) onMediaReadyNative()
                    return
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: PlaybackException) { failure = error }
            }
        } finally { if (loadingNative === operation) loadingNative = null }
    }

    override fun setPlayingNative(playing: Boolean) {
        engine.value?.let { if (playing) it.play() else it.pause() }
    }
    override fun seekNative(positionMs: Long) { engine.value?.seekTo(positionMs) }
    override fun setSpeedNative(speed: Float): Float {
        val native = engine.value ?: return speed
        val playbackSpeed = checkNotNull(native.features[PlaybackSpeed]) { "当前播放器不支持调整倍速" }
        playbackSpeed.set(speed)
        return playbackSpeed.value
    }
    override fun setVideoScaleNative(scale: AnimeVideoScale) {
        engine.value?.features?.get(VideoAspectRatio)?.setMode(when (scale) {
            AnimeVideoScale.FIT -> AspectRatioMode.FIT
            AnimeVideoScale.FILL -> AspectRatioMode.STRETCH
            AnimeVideoScale.ZOOM -> AspectRatioMode.CROP
        })
    }
    override fun setVolumeNative(volume: Float) {
        checkNotNull(engine.value?.features?.get(AudioLevelController)) { "当前播放器不支持调整音量" }.setVolume(volume)
    }
    override fun selectTrackNative(kind: AnimeTrackKind, id: String?) {
        val metadata = checkNotNull(engine.value?.features?.get(MediaMetadata)) { "当前播放器未提供可选轨道" }
        val generation = nativeGeneration
        val accepted = when (kind) {
            AnimeTrackKind.AUDIO -> {
                require(id != null) { "当前播放器需要选择一条具体音轨" }
                val track = audioTracks.singleOrNull { "$generation:audio:${it.id}" == id }
                    ?: error("音轨列表已变化，请重新选择")
                metadata.audioTracks?.select(track) == true
            }
            AnimeTrackKind.SUBTITLE -> {
                val track = if (id == null) null else {
                    subtitleTracks.singleOrNull { "$generation:subtitle:${it.id}" == id }
                        ?: error("字幕列表已变化，请重新选择")
                }
                metadata.subtitleTracks?.select(track) == true
            }
            AnimeTrackKind.VIDEO -> error("这条线路未提供可选画质轨道，请从来源画质中选择")
        }
        check(accepted) { "播放器没有接受这次轨道切换，请选择其他轨道" }
    }
    protected open fun onMediaOpeningNative() {}
    protected open fun onMediaReadyNative() {}
    protected open fun onPlaybackStateNative() {}

    override fun releaseNative() {
        recovery?.cancel()
        recovery = null
        recoveryNotice = null
        loadingNative = null
        audioTracks = emptyList()
        subtitleTracks = emptyList()
        observers?.cancel()
        observers = null
        val old = engine.value
        engine.value = null
        bridge?.close()
        bridge = null
        localBridge?.close()
        localBridge = null
        old?.pause()
        old?.close()
        localLease?.close()
        localLease = null
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
