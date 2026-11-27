@file:OptIn(org.openani.mediamp.ExperimentalMediampApi::class)

package dev.mangaflow.anime.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.hazeSource
import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.platform.wallTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.PlaybackException
import org.openani.mediamp.PlaybackErrorCode
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.features.Buffering
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.AspectRatioMode
import org.openani.mediamp.features.MediaMetadata
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.VideoAspectRatio
import org.openani.mediamp.metadata.AudioTrack
import org.openani.mediamp.metadata.SubtitleTrack
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.Subtitle
import org.openani.mediamp.source.UriMediaData

internal abstract class RoutedNativeAnimePlayer(scope: CoroutineScope) : SessionAnimePlayer(scope) {
    val engine = MutableStateFlow<MediampPlayer?>(null)
    val startupSurface = MutableStateFlow<MediampPlayer?>(null)
    private var bridge: AnimeStreamBridge? = null
    private var cachedStartup: Pair<AnimePlaybackAsset, AnimeBufferedStartup>? = null
    private var localBridge: AnimeLocalStreamBridge? = null
    private var localLease: AnimeLocalMediaLease? = null
    private var observers: CoroutineScope? = null
    private var bufferSeekCompletion: Job? = null
    private var loadingNative: Any? = null
    private var startupWait: Job? = null
    private var recovery: Job? = null
    private var recoveryNotice: String? = null
    private var audioTracks = emptyList<AudioTrack>()
    private var subtitleTracks = emptyList<SubtitleTrack>()
    protected open val externalSubtitles: Boolean = true
    protected open val supportsBufferControl: Boolean = false
    protected open val supportsVideoEnhancement: Boolean = false
    protected open val supportsModelEnhancement: Boolean = false
    protected open val supportsDecoderStartup: Boolean = false
    protected open val onlineStartupWaitMillis: Long? = null
    protected open val onlineRebufferWaitMillis: Long? = null
    protected open val streamCache: AnimeStreamCache? = null
    protected abstract suspend fun createBackend(): MediampPlayer
    protected open suspend fun createDecoderStartup(byteLimit: Long): MediampPlayer? = null
    protected open fun hasDecodedStartupFrame(backend: MediampPlayer, positionMs: Long): Boolean = false
    protected open fun activateDecoderStartup(backend: MediampPlayer): Boolean = false
    protected open fun validateAsset(asset: AnimePlaybackAsset) {}
    protected open fun validateLocalMedia(lease: AnimeLocalMediaLease) {}

    private class DecoderStartup(
        val asset: AnimePlaybackAsset,
        val positionMs: Long,
        val options: AnimePlayerOptions,
        val session: Long,
        val timeline: Long,
        val validUntil: Long,
    ) {
        var backend: MediampPlayer? = null
        var transport: AnimeStreamBridge? = null
        var ready = false
        var expiry: Job? = null
    }

    private var startup: DecoderStartup? = null
    private var handoff: DecoderStartup? = null
    private class RawStartup(val asset: AnimePlaybackAsset, val session: Long, val timeline: Long,
        val validUntil: Long) {
        var release: (() -> Unit)? = null
        var expiry: Job? = null
        var ready = false
    }
    private var rawStartup: RawStartup? = null

    override suspend fun prepareStartupCache(request: AnimeStartupCacheRequest): AnimeStartupCacheResult = withContext(Dispatchers.Main.immediate) {
        val cache = streamCache
        if (cache == null) return@withContext if (request.positionMs == 0L)
            AnimeStartupCacheResult(cacheStartup(request.asset, request.maximumBytes, request.validUntil))
        else AnimeStartupCacheResult(0L, detail = "当前平台仅支持起始片段预读")
        rawStartup?.let(::retireRawStartup)
        val snapshot = state.value
        val pending = RawStartup(request.asset, snapshot.sessionGeneration, snapshot.timelineGeneration, request.validUntil)
        rawStartup = pending
        fun current() = rawStartup === pending && foreground && state.value.sessionGeneration == pending.session &&
            state.value.timelineGeneration == pending.timeline && monotonicTimeMillis() < pending.validUntil &&
            pending.asset.expiresAt?.let { it > wallTimeMillis() } != false
        try {
            withContext(Dispatchers.IO) {
                val release = cache.retain(request.asset)
                try {
                    val retained = withContext(Dispatchers.Main.immediate) {
                        if (current()) { pending.release = release; true } else false
                    }
                    if (!retained) release()
                } catch (failure: Throwable) { release(); throw failure }
            }
            if (!current()) return@withContext AnimeStartupCacheResult(0L)
            val result = withContext(Dispatchers.IO) { AnimeStreamBridge.warmStartup(request, playerScope, cache) }
            if (!current() || result.bytes <= 0L) return@withContext if (current()) result else AnimeStartupCacheResult(0L)
            pending.ready = true
            pending.expiry = playerScope.launch {
                delay((pending.validUntil - monotonicTimeMillis()).coerceAtLeast(0L))
                if (rawStartup === pending) retireRawStartup(pending)
            }
            result
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { AnimeStartupCacheResult(0L, detail = readablePlayerError(failure)) }
        finally { if (!pending.ready || !currentCoroutineContext().isActive) retireRawStartup(pending) }
    }

    private fun retireRawStartup(pending: RawStartup) {
        if (rawStartup === pending) rawStartup = null
        pending.expiry?.cancel()
        pending.expiry = null
        val release = pending.release
        pending.release = null
        if (release != null) playerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable + Dispatchers.IO) { release() }
        }
    }

    override suspend fun cacheStartup(asset: AnimePlaybackAsset, byteLimit: Long, validUntil: Long): Long {
        val media = AnimeStreamBridge.warmStartup(asset, playerScope, byteLimit, validUntil) ?: return 0L
        currentCoroutineContext().ensureActive()
        if (monotonicTimeMillis() >= validUntil) return 0L
        val receipt = asset to media
        cachedStartup = receipt
        playerScope.launch {
            delay((validUntil - monotonicTimeMillis()).coerceAtLeast(0L))
            if (cachedStartup === receipt) cachedStartup = null
        }
        return media.bytes.size.toLong()
    }

    override suspend fun prepareStartup(asset: AnimePlaybackAsset, positionMs: Long, byteLimit: Long,
        validUntil: Long): Boolean = withContext(Dispatchers.Main.immediate) {
        val current = state.value
        if (!supportsDecoderStartup || !foreground || !current.isPlaying || byteLimit <= 0L || positionMs < 0L ||
            monotonicTimeMillis() >= validUntil) return@withContext false
        startup?.let(::retireDecoderStartup)
        val pending = DecoderStartup(asset, positionMs, options, current.sessionGeneration, current.timelineGeneration, validUntil)
        startup = pending
        fun preparing() = startup === pending && foreground && state.value.isPlaying && startupCurrent(pending)
        try {
            validateAsset(asset)
            val buffered = cachedStartup?.takeIf { (cachedAsset, media) ->
                cachedAsset.sameStartupMedia(asset) && monotonicTimeMillis() < media.validUntil
            }?.second
            val transport = AnimeStreamBridge.create(asset, playerScope, buffered, startupByteLimit = byteLimit, cache = streamCache)
            pending.transport = transport
            if (!preparing()) return@withContext false
            val backend = createDecoderStartup(byteLimit) ?: return@withContext false
            pending.backend = backend
            if (!preparing()) return@withContext false
            startupSurface.value = backend
            backend.setMediaData(UriMediaData(transport.mediaUrl, extraFiles = MediaExtraFiles(subtitleFiles(asset, transport))),
                playWhenReady = false, startPositionMillis = positionMs)
            while (preparing()) {
                val snapshot = backend.state.value
                if (snapshot.mediaStatus is MediaStatus.Error || snapshot.mediaStatus == MediaStatus.Ended ||
                    snapshot.mediaStatus == MediaStatus.Released || transport.lastFailure != null) return@withContext false
                if (snapshot.mediaStatus == MediaStatus.Ready && !snapshot.playWhenReady &&
                    hasDecodedStartupFrame(backend, positionMs)) {
                    transport.parkStartupReads()
                    pending.ready = true
                    pending.expiry = playerScope.launch {
                        delay((validUntil - monotonicTimeMillis()).coerceAtLeast(0L))
                        if (startup === pending || handoff === pending) retireDecoderStartup(pending)
                    }
                    return@withContext true
                }
                delay(80L)
            }
            false
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { false }
        finally { if (!pending.ready || !currentCoroutineContext().isActive) retireDecoderStartup(pending) }
    }

    override fun discardStartup(asset: AnimePlaybackAsset?) = command(fatal = false) {
        rawStartup?.takeIf { asset == null || it.asset.sameStartupMedia(asset) }?.let(::retireRawStartup)
        startup?.takeIf { asset == null || it.asset.sameStartupMedia(asset) }?.let(::retireDecoderStartup)
        handoff?.takeIf { asset == null || it.asset.sameStartupMedia(asset) }?.let(::retireDecoderStartup)
        if (asset == null || cachedStartup?.first?.sameStartupMedia(asset) == true) cachedStartup = null
    }

    override fun onPlaybackEnding(next: AnimePlayerRequest?) {
        rawStartup?.takeUnless { pending -> next != null && next.localMedia == null && !next.bypassMediaCache &&
            pending.ready && pending.asset.sameStartupMedia(next.asset) && monotonicTimeMillis() < pending.validUntil &&
            pending.asset.expiresAt?.let { it > wallTimeMillis() } != false }?.let(::retireRawStartup)
        handoff?.let(::retireDecoderStartup)
        val pending = startup
        if (pending != null && next != null && startupCurrent(pending) && admits(pending, next)) {
            startup = null
            handoff = pending
        } else pending?.let(::retireDecoderStartup)
        if (next == null) cachedStartup = null
    }

    private fun startupCurrent(pending: DecoderStartup) = state.value.sessionGeneration == pending.session &&
        state.value.timelineGeneration == pending.timeline && options.sameDecoderStartupOptions(pending.options) &&
        monotonicTimeMillis() < pending.validUntil && pending.asset.expiresAt?.let { it > wallTimeMillis() } != false

    private fun admits(pending: DecoderStartup, next: AnimePlayerRequest) = pending.ready && foreground &&
        next.localMedia == null && !next.bypassMediaCache && next.positionMs == pending.positionMs &&
        pending.asset.sameStartupMedia(next.asset) && pending.transport?.lastFailure == null &&
        pending.backend?.state?.value?.let { it.mediaStatus == MediaStatus.Ready && !it.playWhenReady } == true

    private fun retireDecoderStartup(pending: DecoderStartup) {
        if (startup === pending) startup = null
        if (handoff === pending) handoff = null
        pending.expiry?.cancel()
        pending.expiry = null
        if (startupSurface.value === pending.backend) startupSurface.value = null
        runCatching { pending.transport?.close() }
        pending.transport = null
        runCatching { pending.backend?.close() }
        pending.backend = null
    }

    private fun AnimePlaybackAsset.sameStartupMedia(other: AnimePlaybackAsset) = playbackIdentity() == other.playbackIdentity() &&
        episodeId == other.episodeId && sourceEpisodeId == other.sourceEpisodeId && mimeType == other.mimeType &&
        url == other.url && headers == other.headers && subtitles == other.subtitles && expiresAt == other.expiresAt &&
        preferredAdaptiveHeight == other.preferredAdaptiveHeight

    override suspend fun openNative(request: AnimePlayerRequest, generation: Long) {
        val warm = handoff?.let { pending ->
            val usable = admits(pending, request) && monotonicTimeMillis() < pending.validUntil &&
                pending.asset.expiresAt?.let { it > wallTimeMillis() } != false &&
                runCatching { activateDecoderStartup(checkNotNull(pending.backend)) }.getOrDefault(false)
            if (!usable) { retireDecoderStartup(pending); null }
            else pending.also { handoff = null; it.expiry?.cancel() }
        }
        val promotePlaying = state.value.playWhenReady
        val prepared = cachedStartup?.takeIf { (asset, media) ->
            !request.bypassMediaCache && request.localMedia == null && monotonicTimeMillis() < media.validUntil &&
                asset.sameStartupMedia(request.asset)
        }?.second
        cachedStartup = null
        val transport = if (warm != null) {
            checkNotNull(warm.transport).also { bridge = it }
        } else if (request.localMedia == null) {
            validateAsset(request.asset)
            // Storage failure must not block the routed online stream. A recovery
            // request that bypasses disk must not initialize the cache at all.
            val onlineCache = if (request.bypassMediaCache) null else withContext(Dispatchers.IO) {
                try { streamCache }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
            }
            AnimeStreamBridge.create(request.asset, playerScope, prepared,
                cache = onlineCache).also {
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
                val server = AnimeLocalStreamBridge.create(lease, playerScope,
                    inspectImagePrefixedTransport = usesImagePrefixedAnimeHls(request.asset.sourceId) && !request.localMedia.enhancedVersion)
                if (!isCurrent(generation)) { server.close(); return }
                localBridge = server
            } finally { pending?.close() }
            null
        }
        // The opened stream now retains this asset itself, so a prepared raw-cache lease can hand off.
        rawStartup?.let(::retireRawStartup)
        val native = warm?.backend ?: createBackend()
        try {
            currentCoroutineContext().ensureActive()
        } catch (failure: Throwable) {
            if (startupSurface.value === native) startupSurface.value = null
            native.close()
            throw failure
        }
        if (!isCurrent(generation)) {
            if (startupSurface.value === native) startupSurface.value = null
            native.close()
            return
        }
        engine.value = native
        if (warm != null) startupSurface.value = null
        val child = CoroutineScope(playerScope.coroutineContext + SupervisorJob(playerScope.coroutineContext[Job]))
        observers = child
        val metadata = native.features[MediaMetadata]
        publish(generation) { it.copy(capabilities = buildSet {
            add(AnimePlayerCapability.BACKGROUND_AUDIO)
            if (supportsBufferControl) add(AnimePlayerCapability.BUFFER_CONTROL)
            if (supportsVideoEnhancement) add(AnimePlayerCapability.VIDEO_ENHANCEMENT)
            if (supportsModelEnhancement) add(AnimePlayerCapability.MODEL_ENHANCEMENT)
            if (native.features[VideoAspectRatio] != null) add(AnimePlayerCapability.VIDEO_SCALE)
            if (native.features[AudioLevelController] != null) add(AnimePlayerCapability.VOLUME)
            if (metadata?.audioTracks != null) add(AnimePlayerCapability.AUDIO_TRACKS)
            if (metadata?.subtitleTracks != null) add(AnimePlayerCapability.SUBTITLE_TRACKS)
        }) }

        child.launch(Dispatchers.Main.immediate) {
            native.state.collect { fact ->
                if (!isCurrent(generation) || engine.value !== native || state.value.phase == AnimePlayerPhase.ERROR) return@collect
                if (fact.mediaStatus != MediaStatus.Ready && fact.mediaStatus != MediaStatus.Ended) {
                    bufferSeekCompletion?.cancel()
                    bufferSeekCompletion = null
                }
                if (fact.mediaStatus == MediaStatus.Idle || fact.mediaStatus == MediaStatus.Released) return@collect
                val nativeError = fact.mediaStatus as? MediaStatus.Error
                if (nativeError != null) {
                    startup?.let(::retireDecoderStartup)
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
                        positionMs = loadedPosition(native) ?: previous.positionMs,
                        playWhenReady = fact.playWhenReady,
                        isBuffering = phase == AnimePlayerPhase.OPENING || fact.isBuffering,
                        error = null,
                        notice = if (phase == AnimePlayerPhase.READY && !fact.isBuffering &&
                            previous.notice == recoveryNotice) null else previous.notice,
                    )
                }
                if (isCurrent(generation) && engine.value === native) {
                    if (fact.mediaStatus == MediaStatus.Ready && !fact.isBuffering) recoveryNotice = null
                    if (!state.value.isPlaying && !state.value.ended) startup?.let(::retireDecoderStartup)
                    onPlaybackStateNative()
                }
            }
        }
        child.launch(Dispatchers.Main.immediate) {
            native.currentPositionMillis.collect {
                val position = loadedPosition(native) ?: return@collect
                publish(generation) { it.copy(positionMs = position) }
            }
        }
        if (transport != null) watchOnlineRebuffer(child, native, transport, request, generation)
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
                    if (!isCurrent(generation) || engine.value !== native || bufferSeekCompletion?.isActive == true)
                        return@collect
                    publish(generation) { it.copy(bufferedMs = position.takeIf { value -> value >= 0 } ?: -1L) }
                }
            }
        }
        if (transport != null) child.launch(Dispatchers.Main.immediate) {
            // The native player reads a loopback stream that can be served entirely from disk.
            var previousBytes = transport.receivedNetworkBytes
            var previousAt = monotonicTimeMillis()
            while (isActive && isCurrent(generation) && engine.value === native) {
                delay(1_000L)
                val now = monotonicTimeMillis()
                val received = transport.receivedNetworkBytes
                val rate = (received - previousBytes) * 1_000L / (now - previousAt).coerceAtLeast(1L)
                publish(generation) { it.copy(downloadBytesPerSecond = rate, receivedNetworkBytes = received) }
                previousBytes = received
                previousAt = now
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
        if (warm == null) loadMedia(native, transport, request, generation)
        else {
            onMediaOpeningNative()
            onMediaReadyNative()
            transport?.promoteStartupReads()
            if (promotePlaying) native.play()
        }
    }

    private fun subtitleFiles(asset: AnimePlaybackAsset, transport: AnimeStreamBridge?): List<Subtitle> =
        if (externalSubtitles && transport != null) asset.subtitles.map {
            Subtitle(transport.subtitleUrl(it), it.mimeType, it.language, it.label)
        } else emptyList()

    private fun loadedPosition(native: MediampPlayer): Long? {
        // MediaMP clears mediaData and resets the clock before publishing Error. Ready alone can still be stale here.
        if (engine.value !== native || native.mediaData.value == null || state.value.phase == AnimePlayerPhase.ERROR ||
            bufferSeekCompletion?.isActive == true) return null
        return when (native.state.value.mediaStatus) {
            MediaStatus.Ready, MediaStatus.Ended -> native.currentPositionMillis.value.takeIf { it >= 0L }
            else -> null
        }
    }

    private fun watchOnlineRebuffer(child: CoroutineScope, native: MediampPlayer, transport: AnimeStreamBridge,
        request: AnimePlayerRequest, generation: Long) {
        val timeout = onlineRebufferWaitMillis ?: return
        val hasAlternative = request.hasStartupAlternative ?: return
        child.launch(Dispatchers.Main.immediate) {
            var playedTimeline: Long? = null
            state.map { snapshot ->
                if (snapshot.isPlaying && !snapshot.playbackSuppressed) playedTimeline = snapshot.timelineGeneration
                snapshot.timelineGeneration.takeIf { timeline ->
                    timeline == playedTimeline && snapshot.phase == AnimePlayerPhase.READY && snapshot.isBuffering &&
                        snapshot.playWhenReady && !snapshot.playbackSuppressed && (foreground || snapshot.inPictureInPicture)
                }
            }.distinctUntilChanged().collectLatest { timeline ->
                if (timeline == null) return@collectLatest
                while (isCurrent(generation) && engine.value === native) {
                    val before = state.value
                    val deliveries = transport.mediaDeliveryRevision
                    delay(timeout)
                    val current = state.value
                    val fact = native.state.value
                    if (!isCurrent(generation) || engine.value !== native || current.timelineGeneration != timeline ||
                        current.phase != AnimePlayerPhase.READY || !current.isBuffering || !current.playWhenReady ||
                        current.playbackSuppressed || !(foreground || current.inPictureInPicture) ||
                        fact.mediaStatus != MediaStatus.Ready || !fact.isBuffering || !fact.playWhenReady) return@collectLatest
                    if (current.positionMs != before.positionMs || current.bufferedMs != before.bufferedMs ||
                        transport.mediaDeliveryRevision != deliveries || !hasAlternative()) continue
                    fail(generation, IllegalStateException("当前线路的播放和缓冲已停滞，可切换本集备用线路"))
                    if (isCurrent(generation) && engine.value === native) {
                        runCatching { native.stopPlayback() }
                        onPlaybackStateNative()
                    }
                    return@collectLatest
                }
            }
        }
    }

    private fun watchOnlineStartup(native: MediampPlayer, transport: AnimeStreamBridge,
        request: AnimePlayerRequest, generation: Long) {
        val timeout = onlineStartupWaitMillis ?: return
        val onExpired = request.onStartupWaitExpired ?: return
        val opening = state.value
        fun stillOpening(snapshot: AnimePlayerState) = isCurrent(generation) && engine.value === native &&
            snapshot.sessionGeneration == opening.sessionGeneration && snapshot.timelineGeneration == opening.timelineGeneration &&
            snapshot.assetKey == request.asset.id && snapshot.episodeId == request.asset.episodeId &&
            foreground && snapshot.playWhenReady && !snapshot.inPictureInPicture && !snapshot.playbackSuppressed &&
            snapshot.phase == AnimePlayerPhase.OPENING && snapshot.recoveryAttempt == 0 &&
            snapshot.firstFrameRendered != true && snapshot.hasVideoTracks != false
        if (!stillOpening(opening)) return
        cancelStartupWait()
        transport.observeStartupOrigin(until = monotonicTimeMillis() + timeout)
        startupWait = observers?.launch(Dispatchers.Main.immediate) {
            // The native backend does not expose a presented-frame receipt. Bound only
            // its initial OPENING phase, never a READY playback rebuffer.
            val interrupted = withTimeoutOrNull(timeout) { state.first { !stillOpening(it) } }
            if (interrupted == null && stillOpening(state.value) &&
                native.state.value.mediaStatus == MediaStatus.Opening) {
                onExpired(opening.timelineGeneration, transport.observedRecentStartupOrigin())
            }
        }
    }

    override fun cancelStartupWait() {
        startupWait?.cancel()
        startupWait = null
    }

    private suspend fun loadMedia(
        native: MediampPlayer, transport: AnimeStreamBridge?, request: AnimePlayerRequest,
        generation: Long, initialFailure: PlaybackException? = null,
    ) {
        if (initialFailure != null) cancelStartupWait()
        val extras = subtitleFiles(request.asset, transport)
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
                    val useAlternative = mayRetry && (foreground || state.value.inPictureInPicture) &&
                        state.value.playWhenReady && !state.value.playbackSuppressed && request.hasStartupAlternative?.invoke() == true
                    if (!mayRetry || useAlternative || state.value.recoveryAttempt >= options.automaticRecoveryAttempts) {
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
                    if (transport != null && failure == null) watchOnlineStartup(native, transport, request, generation)
                    native.setMediaData(UriMediaData(mediaUrl, extraFiles = MediaExtraFiles(extras)),
                        playWhenReady = state.value.playWhenReady, startPositionMillis = state.value.positionMs)
                    if (!isCurrent(generation) || engine.value !== native) return
                    val immediateError = native.state.value.mediaStatus as? MediaStatus.Error
                    if (immediateError == null) {
                        onMediaReadyNative()
                        return
                    }
                    // The observer defers to this load while setMediaData is returning.
                    cancelStartupWait()
                    failure = immediateError.error
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: PlaybackException) {
                    if (!isCurrent(generation) || engine.value !== native) return
                    cancelStartupWait()
                    failure = error
                }
            }
        } finally { if (loadingNative === operation) loadingNative = null }
    }

    override fun setPlayingNative(playing: Boolean) {
        if (!playing) cancelStartupWait()
        engine.value?.let { if (playing) it.play() else it.pause() }
    }
    override fun seekNative(positionMs: Long) {
        cancelStartupWait()
        val native = engine.value ?: return
        bufferSeekCompletion?.cancel()
        val generation = nativeGeneration
        val timeline = state.value.timelineGeneration
        // Opening only updates the requested start position and need not emit SeekCompleted.
        // Subscribe before an actual seek; a replacement subscription cannot consume an older
        // queued completion. Keep the buffer collector alive, without replaying its last value.
        val pending = when (native.state.value.mediaStatus) {
            MediaStatus.Ready, MediaStatus.Ended -> playerScope.launch(
                Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED,
            ) {
                val landing = native.events.first { it is PlaybackEvent.SeekCompleted } as PlaybackEvent.SeekCompleted
                if (landing.positionMillis >= 0L && isCurrent(generation) && engine.value === native &&
                    native.mediaData.value != null && state.value.timelineGeneration == timeline &&
                    state.value.phase != AnimePlayerPhase.ERROR)
                    publish(generation) { it.copy(positionMs = landing.positionMillis) }
            }
            else -> null
        }
        bufferSeekCompletion = pending
        try { native.seekTo(positionMs) }
        catch (failure: Throwable) {
            pending?.cancel()
            if (bufferSeekCompletion === pending) bufferSeekCompletion = null
            throw failure
        }
    }
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
        cancelStartupWait()
        recovery?.cancel()
        recovery = null
        recoveryNotice = null
        loadingNative = null
        bufferSeekCompletion?.cancel()
        bufferSeekCompletion = null
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

/** Live byte/time budgets are applied at adoption; they do not invalidate an already decoded first frame. */
internal fun AnimePlayerOptions.sameDecoderStartupOptions(other: AnimePlayerOptions): Boolean = copy(
    bufferMinMs = other.bufferMinMs, bufferMaxMs = other.bufferMaxMs,
    bufferStartMs = other.bufferStartMs, bufferRebufferMs = other.bufferRebufferMs,
    bufferForwardBytes = other.bufferForwardBytes, bufferBackBytes = other.bufferBackBytes,
    bufferRefillNearTarget = other.bufferRefillNearTarget,
) == other

@Composable
internal fun NativeAnimeSurface(player: AnimePlayer, modifier: Modifier) {
    val native = player as? RoutedNativeAnimePlayer ?: return
    val engine by native.engine.collectAsState()
    val preparing by native.startupSurface.collectAsState()
    val current = engine
    val standby = preparing
    val glassSource = LocalAnimeVideoGlassSource.current?.takeIf {
        current != null && native.engine.value === current && it.matches(player, player.state.value)
    }?.haze
    Box(modifier.background(Color.Black)) {
        listOfNotNull(standby, current).distinct().forEach { backend ->
            key(backend) {
                MediampPlayerSurface(backend, if (backend === current) {
                    // The visible mpv frame is already drawn into Compose by Mediamp.
                    Modifier.matchParentSize().zIndex(1f)
                        .then(if (glassSource != null) Modifier.hazeSource(glassSource) else Modifier)
                } else Modifier.size(2.dp).alpha(0f))
            }
        }
    }
}
