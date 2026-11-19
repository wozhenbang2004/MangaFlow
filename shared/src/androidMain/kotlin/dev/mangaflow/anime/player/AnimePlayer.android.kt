@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.mangaflow.anime.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.hls.HlsDataSourceFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import androidx.media3.session.MediaSession
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.compose.ContentFrame
import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.anime.sources.animePublicUrl
import dev.mangaflow.net.applyDownloadRoute
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.sha256
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

actual fun createAnimePlayer(context: PlatformContext, scope: CoroutineScope): AnimePlayer =
    AndroidAnimePlayer(context.applicationContext, scope)

internal class AndroidAnimePlayer(
    private val context: PlatformContext,
    scope: CoroutineScope,
) : SessionAnimePlayer(scope) {
    val engine = MutableStateFlow<ExoPlayer?>(null)
    private var session: MediaSession? = null
    private var listener: Player.Listener? = null
    private var polling: Job? = null
    private var http: OkHttpClient? = null
    private var releaseCache: (() -> Unit)? = null
    private var recovery: Job? = null
    private var recoveryNotice: String? = null
    private var lastPlaybackError: PlaybackException? = null
    private var preparedMedia: MediaSource? = null
    private var enhancementRevision = 0L
    private var enhancementRecoveryUsed = false
    private var nativeVideoRecoveryPending = false
    private var nativeVideoRecoveryFailed = false
    private var nativeVideoOnly = false
    private var nativeVideoRestored = false
    private var nativeVideoFallbackPhase = AnimeVideoEnhancementPhase.FAILED
    private var nativeVideoFallbackDetail: String? = null
    private var receivedBytes = AtomicLong(0L)
    private var lastSampleBytes = 0L
    private var lastSampleAt = 0L
    private var bufferingSince: Long? = null
    private var bufferingProgressAt = 0L
    private var bufferingBytes = 0L
    private var bufferingPosition = 0L
    private var bufferingEnd = 0L
    private var bufferingTimedOut = false
    private var nativeFrameWaitSince: Long? = null
    private val videoHosts = mutableSetOf<Any>()
    private val trackBindings = mutableMapOf<String, Pair<TrackGroup, Int>>()
    private var trackGroups = emptyList<TrackGroup>()
    private var trackRevision = 0L
    private var activity: Activity? = null
    private var localLease: AnimeLocalMediaLease? = null
    override val reportsFirstFrame = true

    init {
        mutableState.value = mutableState.value.copy(capabilities = setOf(
            AnimePlayerCapability.AUDIO_TRACKS, AnimePlayerCapability.SUBTITLE_TRACKS,
            AnimePlayerCapability.VIDEO_TRACKS, AnimePlayerCapability.AUTOMATIC_AUDIO_TRACK,
            AnimePlayerCapability.VIDEO_SCALE, AnimePlayerCapability.VOLUME,
            AnimePlayerCapability.BACKGROUND_AUDIO, AnimePlayerCapability.BUFFER_CONTROL,
            AnimePlayerCapability.VIDEO_ENHANCEMENT,
        ))
    }

    override suspend fun openNative(request: AnimePlayerRequest, generation: Long) {
        if (request.localMedia != null) {
            openLocal(request, generation)
            return
        }
        val asset = request.asset
        checkedMediaUrl(asset.url)
        // SimpleCache opens its database/files once, off the UI thread. The process owns it.
        var pendingLease: (() -> Unit)? = null
        val cacheOwner = try {
            val owner = withContext(Dispatchers.IO) {
                AndroidAnimeCache.get(context).also { pendingLease = it.acquire() }
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrent(generation)) return
            releaseCache = pendingLease
            pendingLease = null
            owner
        } finally {
            // withContext may be cancelled after IO acquired the lease but before
            // delivering its result to Main.
            pendingLease?.invoke()
        }
        val cache = cacheOwner.cache

        val client = OkHttpClient.Builder()
            .applyDownloadRoute(asset.route)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                var original = chain.request()
                val headerOrigin = original.url.toString()
                var redirects = 0
                while (true) {
                    val requestedUrl = checkedMediaUrl(original.url.toString())
                    val supplied = headersFor(asset, requestedUrl, headerOrigin)
                    val builder = original.newBuilder()
                    supplied.forEach { (name, value) -> builder.header(name, value) }
                    builder.header("Accept-Encoding", "identity")
                    val response = chain.proceed(builder.build())
                    if (response.code !in setOf(301, 302, 303, 307, 308)) return@addInterceptor response
                    val location = response.header("Location")
                    val next = location?.let(requestedUrl::resolve)
                    response.close()
                    if (next == null || ++redirects > 8) throw IOException("来源重定向无效或次数过多")
                    checkedMediaUrl(next.toString())
                    // Rebuild per hop: scoped credentials never survive a cross-origin redirect.
                    original = original.newBuilder().url(next).build()
                }
                @Suppress("UNREACHABLE_CODE")
                error("Unreachable redirect state")
            }.build()
        http = client

        val mediaBytes = AtomicLong(0L)
        receivedBytes = mediaBytes
        val transferObserver = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                if (isNetwork && bytesTransferred > 0) mediaBytes.addAndGet(bytesTransferred.toLong())
            }
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        }
        lastSampleBytes = 0L
        lastSampleAt = SystemClock.elapsedRealtime()
        fun upstream(): DataSource.Factory = OkHttpDataSource.Factory(client).setTransferListener(transferObserver)
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setCacheKeyFactory { spec ->
                val identity = asset.playbackIdentity() + "\n" + spec.uri + "\n" +
                    headersFor(asset, checkedMediaUrl(spec.uri.toString())).entries
                        .sortedBy { it.key.lowercase() }.joinToString("\n") { "${it.key.lowercase()}:${it.value}" }
                sha256(identity.encodeToByteArray()).joinToString("") {
                    (it.toInt() and 255).toString(16).padStart(2, '0')
                }
            }
        val cacheFactory = DataSource.Factory {
            if (cacheOwner.targetBytes > 0L) cacheDataSourceFactory.createDataSource() else upstream().createDataSource()
        }
        val item = MediaItem.Builder()
            .setMediaId(asset.playbackIdentity())
            .setUri(asset.url)
            .setMimeType(asset.mimeType)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(asset.title.ifBlank { "动漫播放" }).build())
            .build()
        val contentType = Util.inferContentTypeForUriAndMimeType(Uri.parse(asset.url), asset.mimeType)
        val media: MediaSource = when (contentType) {
            C.CONTENT_TYPE_HLS -> HlsMediaSource.Factory(HlsDataSourceFactory { dataType ->
                // Mutable playlists and key material never enter the disposable media cache.
                if (dataType == C.DATA_TYPE_MEDIA || dataType == C.DATA_TYPE_MEDIA_INITIALIZATION)
                    cacheFactory.createDataSource() else upstream().createDataSource()
            }).setExtractorFactory(AnimeHlsExtractorFactory()).createMediaSource(item)
            C.CONTENT_TYPE_DASH -> DashMediaSource.Factory(
                DefaultDashChunkSource.Factory(cacheFactory), upstream(),
            ).createMediaSource(item)
            C.CONTENT_TYPE_OTHER -> ProgressiveMediaSource.Factory(cacheFactory).createMediaSource(item)
            else -> throw IOException("这条线路的媒体协议暂不支持，请选择 HLS、DASH 或视频文件线路")
        }
        val subtitleParser = DefaultSubtitleParserFactory()
        val sources = mutableListOf(media)
        var unsupportedSubtitle = false
        asset.subtitles.forEach { subtitle ->
            checkedMediaUrl(subtitle.url)
            val mime = subtitle.mimeType ?: subtitleMime(subtitle.url)
            val format = Format.Builder().setId(subtitle.id).setLabel(subtitle.label)
                .setLanguage(subtitle.language).setSampleMimeType(mime)
                .setSelectionFlags(if (sources.size == 1) C.SELECTION_FLAG_DEFAULT else 0).build()
            if (mime == null || !subtitleParser.supportsFormat(format)) {
                unsupportedSubtitle = true
                return@forEach
            }
            val extractors = ExtractorsFactory { arrayOf(SubtitleExtractor(subtitleParser.create(format), format)) }
            // Media3's enableLazyLoadingWithSingleTrack is package-private; subtitle
            // extraction is supported through its public progressive factory.
            sources += ProgressiveMediaSource.Factory(upstream(), extractors)
                .setLoadOnlySelectedTracks(true)
                .createMediaSource(MediaItem.fromUri(subtitle.url))
        }
        prepareMedia(generation, if (sources.size == 1) media else MergingMediaSource(*sources.toTypedArray()), unsupportedSubtitle)
    }

    private suspend fun openLocal(request: AnimePlayerRequest, generation: Long) {
        var pending: AnimeLocalMediaLease? = null
        try {
            val lease = withContext(Dispatchers.IO) {
                requireNotNull(request.localMedia).acquire(request.asset).also { pending = it }
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrent(generation)) return
            localLease = lease
            pending = null
            val paths = withContext(Dispatchers.IO) { lease.resources.values.map { it.file.canonicalPath }.toSet() }
            currentCoroutineContext().ensureActive()
            if (!isCurrent(generation)) return
            val factory = DataSource.Factory {
                object : DataSource {
                    private val file = FileDataSource()
                    override fun addTransferListener(transferListener: TransferListener) = file.addTransferListener(transferListener)
                    override fun open(dataSpec: DataSpec): Long {
                        require(dataSpec.uri.scheme == "file" && dataSpec.uri.authority.isNullOrEmpty() &&
                            java.io.File(requireNotNull(dataSpec.uri.path)).canonicalPath in paths) { "离线清单引用了未下载的资源" }
                        return file.open(dataSpec)
                    }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = file.read(buffer, offset, length)
                    override fun getUri(): Uri? = file.uri
                    override fun close() = file.close()
                }
            }
            val entry = lease.entry
            val item = MediaItem.Builder().setMediaId(request.asset.playbackIdentity()).setUri(entry.file.toURI())
                .setMimeType(entry.mimeType)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(request.asset.title.ifBlank { "离线动漫" }).build()).build()
            val media = if (entry.mimeType.contains("mpegurl", true)) HlsMediaSource.Factory(factory)
                .setExtractorFactory(AnimeHlsExtractorFactory()).createMediaSource(item)
                else ProgressiveMediaSource.Factory(factory).createMediaSource(item)
            receivedBytes = AtomicLong(0L)
            lastSampleBytes = 0L
            lastSampleAt = SystemClock.elapsedRealtime()
            prepareMedia(generation, media)
        } finally { pending?.close() }
    }

    private fun prepareMedia(generation: Long, media: MediaSource, unsupportedSubtitle: Boolean = false) {
        val exo = createEngine()
        try { connectEngine(generation, exo) } catch (failure: Throwable) {
            runCatching { exo.release() }
            throw failure
        }
        preparedMedia = media
        if (unsupportedSubtitle) publish(generation) {
            it.copy(notice = "部分外挂字幕格式无法由当前设备读取；可换用内嵌字幕或其他线路")
        }
        prepareOnEngine(generation, exo, media)
    }

    private fun createEngine(): ExoPlayer =
        ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setLoadControl(DefaultLoadControl.Builder()
                .setBufferDurationsMs(options.bufferMinMs, options.bufferMaxMs, options.bufferStartMs, options.bufferRebufferMs)
                .setTargetBufferBytes(64 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false).build())
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

    private fun connectEngine(generation: Long, exo: ExoPlayer) {
        val observer = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { synchronize(generation, exo) }
            override fun onPlayerError(error: PlaybackException) { recoverOrFail(generation, exo, error) }
            override fun onRenderedFirstFrame() {
                if (!isCurrent(generation) || engine.value !== exo || nativeVideoRecoveryFailed || bufferingTimedOut) return
                if (nativeVideoOnly) nativeVideoRestored = true
                publish(generation) { it.copy(firstFrameRendered = true,
                    videoEnhancement = if (nativeVideoOnly && options.videoEnhancement != AnimeVideoEnhancementMode.OFF)
                        it.videoEnhancement.copy(phase = nativeVideoFallbackPhase, detail = nativeVideoFallbackDetail)
                    else it.videoEnhancement,
                ) }
            }
            override fun onTracksChanged(tracks: Tracks) {
                if (!isCurrent(generation) || engine.value !== exo || nativeVideoRecoveryFailed || bufferingTimedOut) return
                publishTracks(generation, tracks)
                if (!nativeVideoOnly && tracks.groups.any { group ->
                    group.type == C.TRACK_TYPE_VIDEO && (0 until group.length).any { index ->
                        group.isTrackSelected(index) && group.getTrackFormat(index).drmInitData != null
                    }
                }) {
                    // The Media3 effect path does not support protected content. Returning to
                    // its native renderer does not grant a license or add DRM playback support.
                    recoverNativeVideo(generation, exo, AnimeVideoEnhancementPhase.UNAVAILABLE,
                        "受保护视频保持原生播放；Anime4K 不处理 DRM 内容")
                }
            }
        }
        exo.addListener(observer)
        val existingSession = session
        try {
            if (existingSession == null) {
                session = MediaSession.Builder(context, exo).setId("mangaflow-anime").build().also {
                    AnimePlaybackSessions.attach(context, this, it)
                }
            } else {
                // A failed GL backend can change without replacing the app's session/token, route,
                // asset identity, offline lease, playback-history owner or media-service attachment.
                existingSession.setPlayer(exo)
            }
        } catch (failure: Throwable) {
            exo.removeListener(observer)
            throw failure
        }
        listener = observer
        engine.value = exo
    }

    private fun prepareOnEngine(generation: Long, exo: ExoPlayer, media: MediaSource) {
        applyVideoEnhancement(generation, exo)
        exo.setMediaSource(media)
        exo.seekTo(state.value.positionMs)
        exo.playbackParameters = PlaybackParameters(state.value.speed)
        exo.volume = state.value.volume
        exo.playWhenReady = state.value.playWhenReady
        setForegroundNative(foreground || state.value.inPictureInPicture, options.backgroundAudio)
        exo.prepare()
        polling?.cancel()
        polling = playerScope.launch(Dispatchers.Main.immediate) {
            while (isActive && isCurrent(generation)) {
                synchronize(generation, exo)
                checkBufferingProgress(generation, exo)
                checkNativeFrameRecovery(generation, exo)
                delay(250L)
            }
        }
    }

    private fun applyVideoEnhancement(generation: Long, exo: ExoPlayer) {
        val revision = ++enhancementRevision
        val mode = options.videoEnhancement
        val phase = when {
            mode == AnimeVideoEnhancementMode.OFF -> AnimeVideoEnhancementPhase.OFF
            nativeVideoOnly || nativeVideoRecoveryPending || nativeVideoRecoveryFailed -> nativeVideoFallbackPhase
            else -> AnimeVideoEnhancementPhase.WAITING
        }
        publish(generation) { it.copy(videoEnhancement = AnimeVideoEnhancementState(
            mode = mode, phase = phase,
            detail = if (mode != AnimeVideoEnhancementMode.OFF &&
                (nativeVideoOnly || nativeVideoRecoveryPending || nativeVideoRecoveryFailed)) {
                if (nativeVideoRestored || nativeVideoRecoveryFailed) nativeVideoFallbackDetail
                else "正在恢复原生画面，等待首帧"
            } else null,
        )) }
        // Once a VideoGraph itself fails, an empty effect list still retains that GL graph.
        // This replacement backend deliberately never enables it again during this attempt.
        if (nativeVideoOnly || nativeVideoRecoveryPending || nativeVideoRecoveryFailed) return
        try {
            val effects = if (mode == AnimeVideoEnhancementMode.OFF) emptyList() else listOf(
                AndroidAnime4KEffect(mode) { observation, programIsCurrent ->
                    // The callback originates on Media3's GL thread. Both backend and mode
                    // revision must still own this observation when Main finally consumes it.
                    playerScope.launch(Dispatchers.Main.immediate) {
                        if (!isCurrent(generation) || engine.value !== exo || enhancementRevision != revision ||
                            options.videoEnhancement != mode || nativeVideoOnly || !programIsCurrent()) return@launch
                        publish(generation) { it.copy(videoEnhancement = observation) }
                    }
                },
            )
            // Required once BEFORE prepare even for OFF, so ordinary setting changes can
            // replace the effect list in place. ContentFrame uses setVideoSurfaceView and
            // supplies output dimensions automatically in Media3 1.11.1.
            exo.setVideoEffects(effects)
        } catch (failure: Exception) {
            recoverNativeVideo(generation, exo, AnimeVideoEnhancementPhase.FAILED,
                "Anime4K 未能启用，本次播放已退回原画；重新打开媒体可重试")
        }
    }

    private fun recoverNativeVideo(
        generation: Long,
        exo: ExoPlayer,
        phase: AnimeVideoEnhancementPhase = AnimeVideoEnhancementPhase.FAILED,
        detail: String = "图形处理已停止，本次播放已退回原画；重新打开媒体可重试",
    ): Boolean {
        if (!isCurrent(generation) || engine.value !== exo || nativeVideoOnly || enhancementRecoveryUsed || bufferingTimedOut) return false
        enhancementRecoveryUsed = true
        nativeVideoRecoveryPending = true
        nativeVideoRestored = false
        enhancementRevision++
        nativeVideoFallbackPhase = phase
        nativeVideoFallbackDetail = detail
        recovery?.cancel()
        recoveryNotice = "正在恢复原生画面"
        publish(generation) { it.copy(
            phase = AnimePlayerPhase.OPENING, isBuffering = true, error = null, notice = recoveryNotice,
            videoEnhancement = AnimeVideoEnhancementState(options.videoEnhancement,
                if (options.videoEnhancement == AnimeVideoEnhancementMode.OFF) AnimeVideoEnhancementPhase.OFF else phase,
                "正在恢复原生画面，等待首帧".takeIf { options.videoEnhancement != AnimeVideoEnhancementMode.OFF }),
        ) }
        // Dispatch (not immediate) also lets a synchronous pre-prepare failure finish installing
        // the current media first. This recovery never consumes a provider/network retry.
        recovery = playerScope.launch(Dispatchers.Main) {
            if (!isCurrent(generation) || engine.value !== exo) return@launch
            var replacement: ExoPlayer? = null
            try {
                val media = requireNotNull(preparedMedia)
                val position = exo.currentPosition.takeIf { it >= 0L } ?: state.value.positionMs
                val playing = state.value.playWhenReady
                val parameters = exo.trackSelectionParameters
                val speed = state.value.speed
                val volume = state.value.volume
                val fresh = createEngine()
                replacement = fresh
                polling?.cancel()
                polling = null
                listener?.let { exo.removeListener(it) }
                nativeVideoOnly = true
                connectEngine(generation, fresh)
                runCatching { exo.release() }
                lastPlaybackError = null
                fresh.trackSelectionParameters = parameters
                publish(generation) { it.copy(positionMs = position, playWhenReady = playing,
                    speed = speed, volume = volume, firstFrameRendered = false) }
                nativeVideoRecoveryPending = false
                prepareOnEngine(generation, fresh, media)
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                if (engine.value !== replacement) runCatching { replacement?.release() }
                failNativeVideoRecovery(generation, failure)
            }
        }
        return true
    }

    private fun failNativeVideoRecovery(generation: Long, failure: Throwable,
        message: String = "原生画面未能恢复，请重试当前媒体") {
        if (!isCurrent(generation)) return
        nativeVideoRecoveryPending = false
        nativeVideoRecoveryFailed = true
        nativeVideoFallbackPhase = AnimeVideoEnhancementPhase.FAILED
        nativeVideoFallbackDetail = message
        runCatching { engine.value?.pause() }
        publish(generation) { it.copy(videoEnhancement = it.videoEnhancement.copy(
            phase = if (options.videoEnhancement == AnimeVideoEnhancementMode.OFF)
                AnimeVideoEnhancementPhase.OFF else AnimeVideoEnhancementPhase.FAILED,
            detail = nativeVideoFallbackDetail.takeIf { options.videoEnhancement != AnimeVideoEnhancementMode.OFF },
        )) }
        fail(generation, IOException(message, failure), sourceRecoveryAllowed = false)
    }

    internal fun updateVideoHost(token: Any, hasSize: Boolean) {
        if (hasSize) videoHosts.add(token) else videoHosts.remove(token)
        if (videoHosts.isEmpty()) nativeFrameWaitSince = null
    }

    /** A visible, selected video track can reach READY before a frame reaches the surface. */
    private fun checkNativeFrameRecovery(generation: Long, exo: ExoPlayer) {
        if (!isCurrent(generation) || engine.value !== exo) return
        val audioOnly = state.value.hasVideoTracks == false && exo.currentTracks.groups.any { group ->
            group.type == C.TRACK_TYPE_AUDIO && (0 until group.length).any(group::isTrackSelected)
        }
        if (nativeVideoOnly && !nativeVideoRestored && !nativeVideoRecoveryFailed &&
            exo.playbackState == Player.STATE_READY && exo.isPlaying && audioOnly) {
            // Audio-only media cannot produce a video first frame. Do not synthesize one.
            nativeVideoRestored = true
            nativeFrameWaitSince = null
            return
        }
        val selectedVideo = exo.currentTracks.groups.any { group ->
            group.type == C.TRACK_TYPE_VIDEO && (0 until group.length).any(group::isTrackSelected)
        }
        if (state.value.firstFrameRendered != false || nativeVideoRestored || nativeVideoRecoveryPending || nativeVideoRecoveryFailed ||
            bufferingTimedOut || recovery?.isActive == true || videoHosts.isEmpty() || !selectedVideo ||
            !(foreground || state.value.inPictureInPicture) || !exo.playWhenReady ||
            exo.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE ||
            exo.playbackState != Player.STATE_READY || exo.playerError != null) {
            nativeFrameWaitSince = null
            return
        }
        val now = SystemClock.elapsedRealtime()
        val since = nativeFrameWaitSince ?: now.also { nativeFrameWaitSince = it }
        val timeoutMs = if (nativeVideoOnly) 30_000L else 12_000L
        if (now - since < timeoutMs) return
        if (nativeVideoOnly) {
            failNativeVideoRecovery(generation, IOException("原生画面恢复未收到首帧"))
        } else if (options.videoEnhancement == AnimeVideoEnhancementMode.OFF || !recoverNativeVideo(generation, exo)) {
            val message = "视频轨道已就绪，画面未收到首帧。请重试当前播放。"
            failNativeVideoRecovery(generation, IOException(message), message)
        }
    }

    private fun resetBufferingObservation() {
        bufferingSince = null
        bufferingProgressAt = 0L
    }

    /** Observes actual transport/buffer progress, not a guessed download percentage. */
    private fun checkBufferingProgress(generation: Long, exo: ExoPlayer) {
        if (!isCurrent(generation) || engine.value !== exo || bufferingTimedOut) return
        if (localLease != null || nativeVideoRecoveryPending || nativeVideoRecoveryFailed ||
            recovery?.isActive == true ||
            !(foreground || state.value.inPictureInPicture) || !exo.playWhenReady ||
            exo.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE ||
            exo.playerError != null || exo.playbackState != Player.STATE_BUFFERING) {
            resetBufferingObservation()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val bytes = receivedBytes.get()
        val position = exo.currentPosition
        val end = exo.bufferedPosition
        val since = bufferingSince
        if (since == null) {
            bufferingSince = now
            bufferingProgressAt = now
        } else if (bytes > bufferingBytes || end > bufferingEnd || position != bufferingPosition) {
            bufferingProgressAt = now
        }
        bufferingBytes = bytes
        bufferingPosition = position
        bufferingEnd = end
        val startup = state.value.firstFrameRendered == false && state.value.hasVideoTracks != false && !nativeVideoOnly
        val noProgressLimit = if (startup) 12_000L else 30_000L
        if (since == null || (now - bufferingProgressAt < noProgressLimit && now - since < 120_000L)) return
        bufferingTimedOut = true // Set before stop's synchronous callbacks can republish READY.
        var cancelled = false
        try { exo.stop() }
        catch (cancel: CancellationException) { cancelled = true; throw cancel }
        catch (_: Exception) { /* A native stop failure must not hide the observed timeout. */ }
        finally {
            if (!cancelled && isCurrent(generation) && engine.value === exo) {
                val timeout = IOException(if (now - bufferingProgressAt >= noProgressLimit)
                    "这条线路已 ${(noProgressLimit / 1_000L)} 秒没有收到新数据或缓冲进展，请重试或换个来源"
                    else "这条线路持续缓冲超过 2 分钟，暂未达到起播条件，请重试或换个来源")
                if (nativeVideoOnly && !nativeVideoRestored) failNativeVideoRecovery(generation, timeout)
                else fail(generation, timeout)
            }
        }
    }

    private fun synchronize(generation: Long, exo: ExoPlayer) {
        if (!isCurrent(generation) || engine.value !== exo) return
        if (nativeVideoRecoveryPending || nativeVideoRecoveryFailed || bufferingTimedOut) return
        if (exo.playerError != null) return // The typed error callback alone owns recovery/error publication.
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastSampleAt
        val speed = if (elapsed >= 1_000L) {
            val bytes = receivedBytes.get()
            ((bytes - lastSampleBytes).coerceAtLeast(0L) * 1_000L / elapsed).also {
                lastSampleBytes = bytes
                lastSampleAt = now
            }
        } else state.value.downloadBytesPerSecond
        publish(generation) { previous ->
            val ended = exo.playbackState == Player.STATE_ENDED
            val phase = when (exo.playbackState) {
                Player.STATE_READY -> AnimePlayerPhase.READY
                Player.STATE_ENDED -> AnimePlayerPhase.ENDED
                Player.STATE_BUFFERING -> if (previous.phase == AnimePlayerPhase.READY) AnimePlayerPhase.READY
                    else AnimePlayerPhase.OPENING
                else -> previous.phase
            }
            previous.copy(
                phase = phase,
                playWhenReady = if (ended) false else exo.playWhenReady,
                positionMs = exo.currentPosition.coerceAtLeast(0L),
                durationMs = exo.duration.takeIf { it != C.TIME_UNSET && it >= 0 } ?: -1L,
                bufferedMs = exo.bufferedPosition.takeIf { it != C.TIME_UNSET && it >= 0 } ?: -1L,
                speed = exo.playbackParameters.speed,
                isBuffering = exo.playbackState == Player.STATE_BUFFERING,
                playbackSuppressed = exo.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE,
                error = null,
                notice = if (phase == AnimePlayerPhase.READY && exo.playbackState != Player.STATE_BUFFERING &&
                    previous.notice == recoveryNotice) null else previous.notice,
                downloadBytesPerSecond = if (localLease == null) speed else -1L,
                // videoSize can describe the effect output. Source quality must come from
                // the decoder format, never the 2x render target or a guessed fallback.
                videoWidth = exo.videoFormat?.width?.takeIf { it > 0 },
                videoHeight = exo.videoFormat?.height?.takeIf { it > 0 },
                videoEnhancement = previous.videoEnhancement.let { enhancement ->
                    if (enhancement.phase in setOf(AnimeVideoEnhancementPhase.OFF,
                            AnimeVideoEnhancementPhase.UNAVAILABLE, AnimeVideoEnhancementPhase.FAILED)) {
                        val width = exo.videoFormat?.width?.takeIf { it > 0 }
                        val height = exo.videoFormat?.height?.takeIf { it > 0 }
                        enhancement.copy(inputWidth = width, inputHeight = height,
                            outputWidth = width, outputHeight = height)
                    } else enhancement
                },
            )
        }
        if (exo.playbackState == Player.STATE_READY) recoveryNotice = null
    }

    private fun recoverOrFail(generation: Long, exo: ExoPlayer, error: PlaybackException) {
        if (!isCurrent(generation) || engine.value !== exo || lastPlaybackError === error) return
        if (nativeVideoRecoveryPending || nativeVideoRecoveryFailed || bufferingTimedOut) return
        lastPlaybackError = error
        val observation = state.value
        val sourceId = request?.asset?.sourceId
        val observedAt = System.currentTimeMillis()
        playerScope.launch(Dispatchers.IO) {
            AnimePlaybackDiagnostics.record(context, observedAt, sourceId,
                observation.sessionGeneration, generation, observation.phase.name, error)
        }
        // Until its first actual frame, this engine is still the one bounded graphics-recovery
        // attempt. An asynchronous failure here must not mark the source bad or spend its retries.
        if (nativeVideoOnly && !nativeVideoRestored) {
            failNativeVideoRecovery(generation, error)
            return
        }
        if (error.errorCode in setOf(PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
                PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED) &&
            recoverNativeVideo(generation, exo)) return
        val responseCode = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        val retryableNetwork = error.errorCode in setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        ) || (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && responseCode != null && responseCode >= 500)
        // A parsed HLS segment failure cannot change the entry playlist into a
        // progressive video file. Only retry transport failures here; the controller
        // owns recovery across confirmed alternatives for the same episode.
        if (!retryableNetwork || state.value.recoveryAttempt >= options.automaticRecoveryAttempts) {
            publish(generation) { it.copy(notice = it.notice.takeUnless { value -> value == recoveryNotice }) }
            recoveryNotice = null
            fail(generation, playbackFailure(error))
            return
        }
        val attempt = state.value.recoveryAttempt + 1
        recoveryNotice = "连接中断，正在恢复播放（$attempt）"
        publish(generation) { it.copy(
            phase = AnimePlayerPhase.OPENING, isBuffering = true, error = null, recoveryAttempt = attempt,
            notice = recoveryNotice,
        ) }
        recovery?.cancel()
        recovery = playerScope.launch(Dispatchers.Main.immediate) {
            // A paused user intent is retained during the wait and read again before prepare.
            delay(750L * attempt.coerceAtMost(4))
            if (!isCurrent(generation) || engine.value !== exo) return@launch
            try {
                val position = state.value.positionMs
                val playing = state.value.playWhenReady
                exo.seekTo(position)
                exo.playWhenReady = playing
                exo.prepare()
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                fail(generation, failure)
            }
        }
    }

    private fun publishTracks(generation: Long, tracks: Tracks) {
        if (!isCurrent(generation)) return
        val nextGroups = tracks.groups.map { it.mediaTrackGroup }
        if (trackGroups != nextGroups) {
            trackGroups = nextGroups
            trackRevision++
        }
        trackBindings.clear()
        val choices = tracks.groups.flatMapIndexed { groupIndex, group ->
            val kind = when (group.type) {
                C.TRACK_TYPE_AUDIO -> AnimeTrackKind.AUDIO
                C.TRACK_TYPE_TEXT -> AnimeTrackKind.SUBTITLE
                C.TRACK_TYPE_VIDEO -> AnimeTrackKind.VIDEO
                else -> return@flatMapIndexed emptyList()
            }
            (0 until group.length).mapNotNull { index ->
                if (!group.isTrackSupported(index)) return@mapNotNull null
                val format = group.getTrackFormat(index)
                val id = "$generation:$trackRevision:${kind.name}:$groupIndex:$index"
                trackBindings[id] = group.mediaTrackGroup to index
                val detail = listOfNotNull(
                    format.label?.takeIf(String::isNotBlank),
                    format.language?.takeIf { it.isNotBlank() && it != "und" },
                    if (kind == AnimeTrackKind.VIDEO && format.height > 0) "${format.height}p" else null,
                    if (kind == AnimeTrackKind.AUDIO && format.channelCount > 0) "${format.channelCount} 声道" else null,
                ).distinct().joinToString(" · ")
                AnimePlayerTrack(id, kind, detail.ifBlank { when (kind) {
                    AnimeTrackKind.AUDIO -> "音轨 ${index + 1}"
                    AnimeTrackKind.SUBTITLE -> "字幕 ${index + 1}"
                    AnimeTrackKind.VIDEO -> "画质 ${index + 1}"
                } }, format.language, group.isTrackSelected(index))
            }
        }
        val hasVideo = when {
            tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO } -> true
            tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO } -> false
            else -> null
        }
        publish(generation) { it.copy(tracks = choices, hasVideoTracks = hasVideo) }
    }

    override fun selectTrackNative(kind: AnimeTrackKind, id: String?) {
        val exo = engine.value ?: return
        val type = when (kind) {
            AnimeTrackKind.AUDIO -> C.TRACK_TYPE_AUDIO
            AnimeTrackKind.SUBTITLE -> C.TRACK_TYPE_TEXT
            AnimeTrackKind.VIDEO -> C.TRACK_TYPE_VIDEO
        }
        val builder = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
            .setTrackTypeDisabled(type, id == null && kind == AnimeTrackKind.SUBTITLE)
        if (id != null) {
            val (group, index) = requireNotNull(trackBindings[id]) { "轨道列表已更新，请重新选择" }
            check(group.type == type) { "轨道类型不匹配" }
            builder.setOverrideForType(TrackSelectionOverride(group, index))
        }
        exo.trackSelectionParameters = builder.build()
    }

    override fun setVolumeNative(volume: Float) { engine.value?.volume = volume }

    override fun configureNative(previous: AnimePlayerOptions, next: AnimePlayerOptions) {
        if (previous.videoEnhancement != next.videoEnhancement) {
            val exo = engine.value
            if (exo != null) applyVideoEnhancement(nativeGeneration, exo)
            else mutableState.value = mutableState.value.copy(videoEnhancement = AnimeVideoEnhancementState(
                next.videoEnhancement, if (next.videoEnhancement == AnimeVideoEnhancementMode.OFF)
                    AnimeVideoEnhancementPhase.OFF else AnimeVideoEnhancementPhase.WAITING,
            ))
        }
        if (engine.value != null && previous.copy(backgroundAudio = next.backgroundAudio,
                automaticRecoveryAttempts = next.automaticRecoveryAttempts,
                videoEnhancement = next.videoEnhancement) != next) {
            mutableState.value = mutableState.value.copy(notice = "新的缓冲参数将在下次打开媒体时生效")
        }
    }

    override fun setForegroundNative(foreground: Boolean, backgroundAudio: Boolean) {
        resetBufferingObservation()
        nativeFrameWaitSince = null
        val exo = engine.value ?: return
        val videoDisabled = !foreground && backgroundAudio
        if ((C.TRACK_TYPE_VIDEO in exo.trackSelectionParameters.disabledTrackTypes) != videoDisabled) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, videoDisabled).build()
        }
    }

    internal fun attachActivity(value: Activity?) {
        activity = value
        val available = Build.VERSION.SDK_INT >= 26 && value != null &&
            value.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        mutableState.value = mutableState.value.copy(capabilities =
            if (available) state.value.capabilities + AnimePlayerCapability.PICTURE_IN_PICTURE
            else state.value.capabilities - AnimePlayerCapability.PICTURE_IN_PICTURE)
    }

    internal fun detachActivity(value: Activity) {
        if (activity === value) {
            attachActivity(null)
            if (value.isDestroyed || Build.VERSION.SDK_INT < 26 || !value.isInPictureInPictureMode)
                pictureInPictureChanged(false)
        }
    }

    internal fun onPictureInPictureChanged(value: Boolean) = pictureInPictureChanged(value)

    override fun enterPictureInPictureNative() {
        val host = activity ?: error("请先打开播放器再使用画中画")
        check(Build.VERSION.SDK_INT >= 26 && !host.isFinishing && !host.isDestroyed) { "当前窗口无法开启画中画" }
        check(request != null && state.value.phase in setOf(AnimePlayerPhase.OPENING, AnimePlayerPhase.READY)) {
            "请先打开可播放的剧集"
        }
        val width = state.value.videoWidth ?: 16
        val height = state.value.videoHeight ?: 9
        val ratio = width.toDouble() / height
        val aspect = if (ratio in (1.0 / 2.39)..2.39) Rational(width, height) else Rational(16, 9)
        val entered = host.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(aspect).build())
        check(entered) { "系统未允许开启画中画，可检查应用的画中画权限" }
        pictureInPictureChanged(true)
    }

    override fun setPlayingNative(playing: Boolean) {
        engine.value?.playWhenReady = playing
    }
    override fun seekNative(positionMs: Long) {
        resetBufferingObservation(); nativeFrameWaitSince = null
        engine.value?.seekTo(positionMs)
    }
    override fun setSpeedNative(speed: Float): Float {
        val exo = engine.value ?: return speed
        exo.setPlaybackSpeed(speed)
        return exo.playbackParameters.speed
    }
    override fun releaseNative() {
        resetBufferingObservation()
        bufferingTimedOut = false
        nativeFrameWaitSince = null
        recovery?.cancel()
        recovery = null
        recoveryNotice = null
        lastPlaybackError = null
        preparedMedia = null
        enhancementRevision++
        enhancementRecoveryUsed = false
        nativeVideoRecoveryPending = false
        nativeVideoRecoveryFailed = false
        nativeVideoOnly = false
        nativeVideoRestored = false
        nativeVideoFallbackPhase = AnimeVideoEnhancementPhase.FAILED
        nativeVideoFallbackDetail = null
        trackBindings.clear()
        trackGroups = emptyList()
        trackRevision = 0L
        polling?.cancel()
        polling = null
        session?.let { AnimePlaybackSessions.detach(it); it.release() }
        session = null
        val old = engine.value
        engine.value = null
        listener?.let { old?.removeListener(it) }
        listener = null
        http?.dispatcher?.cancelAll()
        old?.release()
        localLease?.close()
        localLease = null
        http?.connectionPool?.evictAll()
        http = null
        releaseCache?.invoke()
        releaseCache = null
    }

    internal fun backgroundServiceUnavailable() {
        mutableState.value = mutableState.value.copy(
            notice = "系统暂未允许后台播放服务，离开应用后可能暂停；可回到前台重试",
        )
    }
}

private fun checkedMediaUrl(value: String): HttpUrl {
    return animePublicUrl(value).toString().toHttpUrl()
}

private fun headersFor(asset: AnimePlaybackAsset, target: HttpUrl, origin: String = target.toString()): Map<String, String> {
    val subtitle = asset.subtitles.firstOrNull { it.url == origin }
    val root = checkedMediaUrl(subtitle?.url ?: asset.url)
    val primary = checkedMediaUrl(asset.url)
    val mayInheritCredentials = primary.host == root.host && primary.port == root.port && primary.scheme == root.scheme
    val publicHeaders = setOf("user-agent", "referer", "origin", "accept", "accept-language")
    val inherited = asset.headers.filter { (name, _) ->
        mayInheritCredentials || name.lowercase() in publicHeaders
    }
    // Subtitle credentials belong to its own origin. The primary video's
    // credentials must never be rebound to a different subtitle host.
    val supplied = if (subtitle != null) inherited + subtitle.headers else asset.headers
    val sameOrigin = root.host == target.host && root.port == target.port && root.scheme == target.scheme
    return supplied.filter { (name, _) ->
        name.lowercase() !in setOf("host", "connection", "content-length", "transfer-encoding",
            "proxy-authorization", "proxy-authenticate", "upgrade", "range", "accept-encoding") &&
            (sameOrigin || name.lowercase() in publicHeaders)
    }
}

private fun playbackFailure(error: PlaybackException): Throwable = IOException(when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
        val code = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (code == 401 || code == 403) "播放地址已失效或来源拒绝访问，请刷新线路或更换来源"
        else "来源返回 HTTP ${code ?: "错误"}，请重试或更换线路"
    }
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "当前播放路由未能连接来源，请重试或检查该来源的网络设置"
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "这条线路返回的内容无法作为视频文件读取，请重试或换个来源"
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "这条线路的播放清单无法读取，请重试或换个来源"
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "当前设备不支持这条线路的媒体格式，请换个来源"
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "这条线路的解码器未能启动，请重试或换个来源"
    PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED -> "这条线路需要当前播放器不支持的受保护媒体授权"
    else -> readablePlayerError(error)
}, error)

private fun subtitleMime(url: String): String? = when (Uri.parse(url).lastPathSegment?.substringAfterLast('.')?.lowercase()) {
    "vtt" -> MimeTypes.TEXT_VTT
    "ass", "ssa" -> MimeTypes.TEXT_SSA
    "srt" -> MimeTypes.APPLICATION_SUBRIP
    "ttml", "xml" -> MimeTypes.APPLICATION_TTML
    else -> null
}

@Composable
actual fun AnimePlayerSurface(player: AnimePlayer, modifier: Modifier) {
    val android = player as? AndroidAnimePlayer ?: return
    val exo by android.engine.collectAsState()
    val state by android.state.collectAsState()
    val localContext = LocalContext.current
    val activity = remember(localContext) { localContext.findActivity() }
    DisposableEffect(android, activity) {
        android.attachActivity(activity)
        val component = activity as? ComponentActivity
        val pipObserver = Consumer<PictureInPictureModeChangedInfo> { android.onPictureInPictureChanged(it.isInPictureInPictureMode) }
        component?.addOnPictureInPictureModeChangedListener(pipObserver)
        if (Build.VERSION.SDK_INT >= 26 && activity != null)
            android.onPictureInPictureChanged(activity.isInPictureInPictureMode)
        onDispose {
            component?.removeOnPictureInPictureModeChangedListener(pipObserver)
            if (activity != null) android.detachActivity(activity)
        }
    }
    val hostToken = remember(android) { Any() }
    var hostHasSize by remember(android) { mutableStateOf(false) }
    DisposableEffect(android, hostToken, hostHasSize) {
        android.updateVideoHost(hostToken, hostHasSize)
        onDispose { android.updateVideoHost(hostToken, false) }
    }
    var cues by remember(exo) { mutableStateOf(exo?.currentCues ?: CueGroup.EMPTY_TIME_ZERO) }
    DisposableEffect(exo) {
        val observer = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) { cues = cueGroup }
        }
        exo?.addListener(observer)
        onDispose { exo?.removeListener(observer) }
    }
    Box(modifier.background(Color.Black).clipToBounds().onSizeChanged {
        hostHasSize = it.width > 0 && it.height > 0
    }) {
        ContentFrame(player = exo, modifier = Modifier.fillMaxSize(), contentScale = when (state.videoScale) {
            AnimeVideoScale.FIT -> ContentScale.Fit
            AnimeVideoScale.FILL -> ContentScale.FillBounds
            AnimeVideoScale.ZOOM -> ContentScale.Crop
        })
        AndroidView(
            factory = { SubtitleView(it) },
            modifier = Modifier.fillMaxSize(),
            update = { it.setCues(cues.cues) },
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.findActivity()
    else -> null
}
