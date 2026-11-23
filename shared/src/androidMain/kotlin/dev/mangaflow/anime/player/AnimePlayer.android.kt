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
import androidx.media3.common.Effect
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.hls.HlsDataSourceFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.effect.GlEffect
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import androidx.media3.session.MediaSession
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.compose.ContentFrame
import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.anime.sources.animeMediaUrl
import dev.mangaflow.enhancement.engine.AndroidRealtimeVideoModel
import dev.mangaflow.enhancement.engine.AndroidLiveRifeVulkan
import dev.mangaflow.enhancement.engine.EnhancementEngineIds
import dev.mangaflow.net.applyDownloadRoute
import dev.mangaflow.net.applyPublicMediaNetwork
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.sha256
import dev.mangaflow.platform.wallTimeMillis
import java.io.IOException
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

actual fun createAnimePlayer(context: PlatformContext, scope: CoroutineScope): AnimePlayer =
    AndroidAnimePlayer(context.applicationContext, scope)

actual fun initializeAnimeStorage(context: PlatformContext) {
    AndroidAnimeCache.get(context)
}

internal class AndroidAnimePlayer(
    private val context: PlatformContext,
    scope: CoroutineScope,
) : SessionAnimePlayer(scope) {
    val engine = MutableStateFlow<ExoPlayer?>(null)
    private val loadControls = WeakHashMap<ExoPlayer, AndroidAnimeLoadControl>()
    private var lastAcceptedForwardBytes = AnimePlayerOptions().bufferForwardBytes
    private var session: MediaSession? = null
    private var listener: Player.Listener? = null
    private var polling: Job? = null
    private var http: AnimeMediaHttpClient? = null
    private var releaseCache: (() -> Unit)? = null
    private var recovery: Job? = null
    private var recoveryNotice: String? = null
    private var cachePlaybackNotice: String? = null
    private var lastPlaybackError: PlaybackException? = null
    private var preparedMedia: MediaSource? = null
    private data class RemoteMediaPlan(
        val type: Int, val cacheWasEnabled: Boolean, val createSource: (Int, Boolean) -> MediaSource,
    )
    private class RemoteMediaResources(
        val plan: RemoteMediaPlan,
        val client: AnimeMediaHttpClient,
        val bytes: AtomicLong,
        val releaseCache: (() -> Unit)?,
        val unsupportedSubtitle: Boolean,
        val cacheNotice: String?,
    ) {
        fun close() {
            closeAnimeMediaHttpClient(client)
            releaseCache?.invoke()
        }
    }

    private class Startup(
        val asset: AnimePlaybackAsset,
        val positionMs: Long,
        val options: AnimePlayerOptions,
        val validUntil: Long,
        val gate: AndroidAnimeStartupGate,
        val resources: RemoteMediaResources,
        val player: ExoPlayer,
        val surface: AndroidAnimeStartupSurface,
        val media: MediaSource,
        val listener: Player.Listener,
        val result: CompletableDeferred<Boolean>,
    ) {
        var firstFrame = false

        fun close() {
            gate.retire()
            result.complete(false)
            player.removeListener(listener)
            closeAnimeMediaHttpClient(resources.client)
            try { player.release() }
            finally {
                try { surface.close() }
                finally { resources.releaseCache?.invoke() }
            }
        }
    }

    private var startupRequest: AnimePlaybackAsset? = null
    private var startupGeneration = 0L
    private var startup: Startup? = null
    private var startupExpiry: Job? = null
    private class StartupCacheLease(val asset: AnimePlaybackAsset, val release: () -> Unit)
    private var startupCacheGeneration = 0L
    private var startupCacheRequest: AnimePlaybackAsset? = null
    private var startupCacheLease: StartupCacheLease? = null
    private var startupCacheExpiry: Job? = null
    private var remoteMediaPlan: RemoteMediaPlan? = null
    private var inspectedAfterParserError = false
    @Volatile private var enhancementRevision = 0L
    private var enhancementEffect: AndroidAnime4KEffect? = null
    private var realtimeLoading: Job? = null
    private var realtimeModel: AndroidRealtimeVideoModel? = null
    private var realtimeRetirement: Job? = null
    private var motionLoading: Job? = null
    private data class FailedMotion(val session: Long, val intent: Long, val request: AnimeRealtimeFrameRequest,
        val state: AnimeRealtimeMotionState)
    /** Resource pressure can temporarily remove a request; it must not clear a real failure. */
    private var failedMotion: FailedMotion? = null
    @Volatile private var motionModel: AndroidLiveRifeVulkan? = null
    private var motionRetirement: Job? = null
    private val motionOutputs = MotionOutputReceipts()
    private var lastMotionSample: AndroidLiveRifeProgram.RifeSample? = null
    private var lastMotionSampleAt: Long? = null
    private var motionObservedEpoch: Long? = null
    private var motionGuardEpoch: Long? = null
    private var motionReject: ((String) -> Unit)? = null
    private var motionStableFlowSince: Long? = null
    private var motionLowOutputSince: Long? = null
    private var motionNoOutputSince: Long? = null
    private var motionLastReleasedFrames: Long? = null
    private var motionEffect: Effect? = null
    private var baseVideoEffects: List<Effect> = emptyList()
    @Volatile private var preparedVideo: AndroidPreparedWindow? = null
    private var reusablePreparedVideo: AndroidPreparedWindow? = null
    private val videoPreparationRevision = AtomicLong(0L)
    private var lastEnhancementSubmissionAt: Long? = null
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
    private var outputFrameReleases = AtomicLong(0L)
    private val frameWindow = AnimePlaybackFrameWindow()
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

    /** A bounded bridge from GL-generated PTS to the final video sink's release callback. */
    private class MotionOutputReceipts {
        data class Snapshot(val epoch: Long, val frames: Long, val generated: Long)
        private val lock = Any()
        private var owner: ExoPlayer? = null
        private var program: Any? = null
        private var epoch = 0L
        private var accepting = false
        private var released = 0L
        private var generated = 0L
        private val pending = linkedSetOf<Long>()

        fun activate(exo: ExoPlayer, token: Any, current: () -> Boolean) = synchronized(lock) {
            if (!current()) return@synchronized
            owner = exo
            program = token
            resetLocked(accept = true)
        }
        fun queue(token: Any, pts: Long) = synchronized(lock) {
            if (program !== token || !accepting) return@synchronized
            if (pending.add(pts)) generated++
            while (pending.size > 512) pending.remove(pending.first())
        }
        fun release(exo: ExoPlayer, pts: Long) = synchronized(lock) {
            if (owner !== exo || !accepting) return@synchronized
            if (pending.remove(pts)) released++
            // Older generated frames skipped downstream can no longer be released in this timeline.
            while (pending.firstOrNull()?.let { it < pts } == true) pending.remove(pending.first())
        }
        fun snapshot(token: Any): Snapshot? = synchronized(lock) {
            if (program === token && accepting) Snapshot(epoch, released, generated) else null
        }
        fun isCurrent(token: Any, snapshot: Snapshot): Boolean = synchronized(lock) {
            program === token && accepting && epoch == snapshot.epoch
        }
        fun currentSnapshot(): Snapshot? = synchronized(lock) {
            if (program != null && accepting) Snapshot(epoch, released, generated) else null
        }
        fun reset(token: Any) = synchronized(lock) {
            if (program === token) resetLocked(accept = true)
        }
        fun seek() = synchronized(lock) { resetLocked(accept = false) }
        fun clear() = synchronized(lock) {
            owner = null
            program = null
            resetLocked(accept = false)
        }
        private fun resetLocked(accept: Boolean) {
            epoch++
            pending.clear()
            released = 0L
            generated = 0L
            accepting = accept
        }
    }

    override suspend fun cacheStartup(asset: AnimePlaybackAsset, byteLimit: Long, validUntil: Long): Long =
        prepareStartupCache(AnimeStartupCacheRequest(asset, 0L, 30_000L, byteLimit, validUntil)).bytes

    override suspend fun prepareStartupCache(request: AnimeStartupCacheRequest): AnimeStartupCacheResult =
        withContext(Dispatchers.Main.immediate) {
            discardStartupCacheNow()
            if (request.validUntil <= SystemClock.elapsedRealtime())
                return@withContext AnimeStartupCacheResult(0L, detail = "下一集准备已过期")
            val attempt = startupCacheGeneration
            startupCacheRequest = request.asset
            var pending: (() -> Unit)? = null
            var owner: StartupCacheLease? = null
            var retained = false
            try {
                val release = withContext(Dispatchers.IO) {
                    AndroidAnimeCache.get(context).acquire(request.asset).also { pending = it }
                }
                if (startupCacheGeneration != attempt)
                    return@withContext AnimeStartupCacheResult(0L, detail = "下一集准备意图已变化")
                val lease = StartupCacheLease(request.asset, release).also {
                    owner = it
                    startupCacheLease = it
                    pending = null
                }
                startupCacheExpiry = playerScope.launch(Dispatchers.Main.immediate) {
                    delay((request.validUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                    if (startupCacheLease === lease) discardStartupCacheNow()
                }
                val result = cacheAndroidAnimeStartup(context, request)
                if (startupCacheGeneration != attempt || startupCacheLease !== lease)
                    return@withContext AnimeStartupCacheResult(0L, detail = "下一集准备已失效")
                retained = true
                result
            } finally {
                pending?.invoke()
                if (!retained && owner != null && startupCacheLease === owner) discardStartupCacheNow()
            }
        }

    override fun noteVideoPreparationIssue(detail: String?) {
        mutableState.value = mutableState.value.copy(preparedVideoIssue = detail)
    }

    override suspend fun prepareVideoWindow(request: AnimePreparedVideoRequest): Boolean = withContext(Dispatchers.Main.immediate) {
        val generation = nativeGeneration
        val revision = videoPreparationRevision.incrementAndGet()
        fun current(): Boolean {
            val snapshot = state.value
            val exo = engine.value
            return videoPreparationRevision.get() == revision && isCurrent(generation) && !nativeVideoOnly &&
                options.preparedVideoModel == request.model &&
                this@AndroidAnimePlayer.request?.localMedia == null && this@AndroidAnimePlayer.request?.asset == request.asset &&
                exo != null && exo.isCurrentMediaItemSeekable && !exo.isCurrentMediaItemLive &&
                snapshot.sessionGeneration == request.sessionGeneration && snapshot.timelineGeneration == request.timelineGeneration
        }
        if (!current()) return@withContext false
        val reusable = (preparedVideo ?: reusablePreparedVideo)?.takeIf {
            request.canReuse(it.request, it.receipt, state.value.positionMs)
        }
        // Own at most one completed window, including while the consumer is switched off.
        preparedVideo = null
        reusablePreparedVideo = null
        if (reusable != null) {
            // A new consumer identity fences an old GL draw even when the immutable frame bytes are reused.
            val restored = reusable.copy(receipt = reusable.receipt.copy(framesUsed = 0L, lastUsedAt = 0L))
            preparedVideo = restored
            publish(generation) { it.copy(preparedVideoWindow = restored.receipt,
                preparedVideoRetainedBytes = 0L, preparedVideoIssue = null) }
            return@withContext true
        }
        publish(generation) { it.copy(preparedVideoWindow = null, preparedVideoRetainedBytes = 0L) }
        val result = try { prepareAndroidAnimeWindow(context, request) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: LinkageError) {
            if (current()) publish(generation) { it.copy(preparedVideoIssue = "原生推理运行库未能加载") }
            return@withContext false
        }
        catch (_: OutOfMemoryError) {
            if (current()) publish(generation) { it.copy(preparedVideoIssue = "当前内存不足以保留预处理帧") }
            return@withContext false
        }
        catch (failure: Exception) {
            if (current()) publish(generation) { it.copy(preparedVideoIssue = readablePlayerError(failure)) }
            return@withContext false
        }
        currentCoroutineContext().ensureActive()
        if (!current() || state.value.positionMs >= result.receipt.endMs) return@withContext false
        preparedVideo = result
        publish(generation) { it.copy(preparedVideoWindow = result.receipt, preparedVideoRetainedBytes = 0L,
            preparedVideoIssue = null) }
        true
    }

    override fun discardPreparedVideo(retainReusable: Boolean) {
        videoPreparationRevision.incrementAndGet()
        // Repeated configuration notifications must not replace an already retained window with null.
        reusablePreparedVideo = if (retainReusable) preparedVideo ?: reusablePreparedVideo else null
        preparedVideo = null
        mutableState.value = state.value.copy(preparedVideoWindow = null,
            preparedVideoRetainedBytes = reusablePreparedVideo?.receipt?.bytes ?: 0L, preparedVideoIssue = null)
    }

    override suspend fun prepareStartup(asset: AnimePlaybackAsset, positionMs: Long, byteLimit: Long, validUntil: Long): Boolean =
        withContext(Dispatchers.Main.immediate) {
            if (byteLimit <= 0L || !foreground || state.value.isBuffering ||
                validUntil <= SystemClock.elapsedRealtime()) return@withContext false
            discardStartupNow()
            if (startupCacheRequest?.let { !isSameStartupAsset(it, asset) } == true) discardStartupCacheNow()
            val attempt = startupGeneration
            val preparedOptions = options
            startupRequest = asset
            val gate = AndroidAnimeStartupGate(byteLimit, validUntil)
            var resources: RemoteMediaResources? = null
            var candidate: ExoPlayer? = null
            var surface: AndroidAnimeStartupSurface? = null
            var retained = false
            try {
                val next = AnimePlayerRequest(asset, positionMs.coerceAtLeast(0L), playWhenReady = false)
                val remote = createRemoteMedia(next, gate).also { resources = it }
                if (startupGeneration != attempt || !foreground || !sameStartupConfiguration(options, preparedOptions))
                    return@withContext false
                val exo = createEngine(gate).also { candidate = it }
                val output = AndroidAnimeStartupSurface().also { surface = it }
                val media = remote.plan.createSource(remote.plan.type, false)
                val ready = CompletableDeferred<Boolean>()
                val observer = object : Player.Listener {
                    private fun acknowledge() {
                        val owner = startup?.takeIf { it.player === exo } ?: return
                        if (!owner.firstFrame || exo.playbackState != Player.STATE_READY) return
                        if (exo.isCurrentMediaItemLive || exo.isCurrentMediaItemDynamic) {
                            ready.complete(false)
                            return
                        }
                        gate.park()
                        ready.complete(true)
                    }

                    override fun onRenderedFirstFrame() {
                        val owner = startup?.takeIf { it.player === exo } ?: return
                        owner.firstFrame = true
                        acknowledge()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) ready.complete(false) else acknowledge()
                    }
                    override fun onPlayerError(error: PlaybackException) { ready.complete(false) }
                }
                val owner = Startup(asset, next.positionMs, preparedOptions, validUntil, gate, remote,
                    exo, output, media, observer, ready)
                startup = owner
                resources = null
                candidate = null
                surface = null
                exo.addListener(observer)
                // Keep the ordinary Media3 effect graph configurable when this exact engine
                // takes over. The hidden output is used to acknowledge native decoded frames.
                exo.setVideoEffects(emptyList())
                output.attach(exo)
                exo.setMediaSource(media, next.positionMs)
                exo.prepare()
                val timeLeft = (validUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                val result = withTimeoutOrNull(timeLeft) { ready.await() } == true
                if (!result || startup !== owner || exo.playerError != null ||
                    state.value.isBuffering || !foreground || !sameStartupConfiguration(options, preparedOptions))
                    return@withContext false
                retained = true
                startupExpiry = playerScope.launch(Dispatchers.Main.immediate) {
                    try { delay((validUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0L)) }
                    finally { if (startup === owner) discardStartupNow() }
                }
                true
            } finally {
                candidate?.let { runCatching { it.release() } }
                surface?.close()
                resources?.close()
                if (!retained && startupGeneration == attempt) discardStartupNow()
            }
        }

    override fun discardStartup(asset: AnimePlaybackAsset?) {
        playerScope.launch(Dispatchers.Main.immediate) {
            if (asset == null || startupRequest?.let { isSameStartupAsset(it, asset) } == true) discardStartupNow()
            if (asset == null || startupCacheRequest?.let { isSameStartupAsset(it, asset) } == true) discardStartupCacheNow()
        }
    }

    private fun discardStartupCacheNow() {
        ++startupCacheGeneration
        startupCacheRequest = null
        startupCacheExpiry?.cancel()
        startupCacheExpiry = null
        val previous = startupCacheLease
        startupCacheLease = null
        previous?.release?.invoke()
    }

    private fun discardStartupNow() {
        ++startupGeneration
        startupRequest = null
        val previous = startup
        startup = null
        startupExpiry?.cancel()
        startupExpiry = null
        previous?.let { runCatching { it.close() } }
    }

    private fun isSameStartupAsset(left: AnimePlaybackAsset, right: AnimePlaybackAsset): Boolean =
        left.copy(title = "", resolutions = emptyList()) == right.copy(title = "", resolutions = emptyList())

    private fun sameStartupConfiguration(left: AnimePlayerOptions, right: AnimePlayerOptions): Boolean =
        left.copy(bufferMinMs = right.bufferMinMs, bufferMaxMs = right.bufferMaxMs,
            bufferStartMs = right.bufferStartMs, bufferRebufferMs = right.bufferRebufferMs,
            bufferForwardBytes = right.bufferForwardBytes, bufferBackBytes = right.bufferBackBytes,
            bufferRefillNearTarget = right.bufferRefillNearTarget) == right

    private fun takeStartup(request: AnimePlayerRequest): Startup? {
        val owner = startup
        if (owner == null || !owner.firstFrame || owner.positionMs != request.positionMs ||
            request.bypassMediaCache || request.localMedia != null || !sameStartupConfiguration(options, owner.options) ||
            !isSameStartupAsset(owner.asset, request.asset) || owner.player.playerError != null ||
            owner.player.playbackState != Player.STATE_READY || owner.player.currentPosition != request.positionMs ||
            owner.validUntil <= SystemClock.elapsedRealtime() ||
            owner.asset.expiresAt?.let { it <= wallTimeMillis() } == true) {
            discardStartupNow()
            return null
        }
        ++startupGeneration
        startupRequest = null
        startup = null
        startupExpiry?.cancel()
        startupExpiry = null
        return owner
    }

    init {
        mutableState.value = mutableState.value.copy(capabilities = setOf(
            AnimePlayerCapability.AUDIO_TRACKS, AnimePlayerCapability.SUBTITLE_TRACKS,
            AnimePlayerCapability.VIDEO_TRACKS, AnimePlayerCapability.AUTOMATIC_AUDIO_TRACK,
            AnimePlayerCapability.VIDEO_SCALE, AnimePlayerCapability.VOLUME,
            AnimePlayerCapability.BACKGROUND_AUDIO, AnimePlayerCapability.BUFFER_CONTROL,
            AnimePlayerCapability.VIDEO_ENHANCEMENT,
        ) + listOfNotNull(AnimePlayerCapability.MODEL_ENHANCEMENT.takeIf { Build.VERSION.SDK_INT >= 29 }))
    }

    override suspend fun openNative(request: AnimePlayerRequest, generation: Long) {
        takeStartup(request)?.let { warm ->
            if (adoptStartup(warm, generation)) return
        }
        if (request.localMedia != null) {
            openLocal(request, generation)
            return
        }
        val remote = createRemoteMedia(request)
        if (!isCurrent(generation)) {
            remote.close()
            return
        }
        adoptRemoteResources(remote, generation)
        // The active player already owns this exact asset before the speculative
        // lease is relinquished, so a cache sweep cannot break the hand-off.
        discardStartupCacheNow()
        prepareMedia(generation, remote.plan.createSource(remote.plan.type, false), remote.unsupportedSubtitle)
    }

    private fun adoptStartup(owner: Startup, generation: Long): Boolean {
        val exo = owner.player
        try {
            exo.removeListener(owner.listener)
            owner.surface.detach(exo)
            owner.surface.close()
            loadControls[exo]?.update(options)
            owner.gate.promote()
            exo.setPriority(C.PRIORITY_PLAYBACK)
            exo.setWakeMode(C.WAKE_MODE_NETWORK)
            connectEngine(generation, exo)
            adoptRemoteResources(owner.resources, generation)
            discardStartupCacheNow()
        } catch (_: Exception) {
            runCatching { owner.close() }
            return false
        }
        preparedMedia = owner.media
        publishTracks(generation, exo.currentTracks)
        reportUnsupportedSubtitles(generation, owner.resources.unsupportedSubtitle)
        applyVideoEnhancement(generation, exo)
        configureActiveMedia(exo)
        startPlaybackPolling(generation, exo)
        return true
    }

    private fun adoptRemoteResources(remote: RemoteMediaResources, generation: Long) {
        remoteMediaPlan = remote.plan
        http = remote.client
        releaseCache = remote.releaseCache
        receivedBytes = remote.bytes
        lastSampleBytes = remote.bytes.get()
        lastSampleAt = SystemClock.elapsedRealtime()
        cachePlaybackNotice = remote.cacheNotice
        remote.cacheNotice?.let { notice -> publish(generation) { it.copy(notice = notice) } }
    }

    private suspend fun createRemoteMedia(
        request: AnimePlayerRequest,
        gate: AndroidAnimeStartupGate? = null,
    ): RemoteMediaResources {
        val asset = request.asset
        checkedMediaUrl(asset.url)
        // SimpleCache opens its database/files once, off the UI thread. The process owns it.
        var pendingLease: (() -> Unit)? = null
        var pendingClient: AnimeMediaHttpClient? = null
        var cacheNotice: String? = null
        try {
            val cacheOwner = if (request.bypassMediaCache) null else withContext(Dispatchers.IO) {
                try {
                    AndroidAnimeCache.get(context).also {
                        it.cache.checkInitialization()
                        pendingLease = it.acquire(asset)
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) {
                    cacheNotice = "本机缓存暂不可用，已跳过缓存播放原片"
                    null
                }
            }
            currentCoroutineContext().ensureActive()

            val client = animeMediaHttpClient(asset).also { pendingClient = it }

            val mediaBytes = AtomicLong(0L)
            val transferObserver = object : TransferListener {
                override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
                override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
                override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                    if (isNetwork && bytesTransferred > 0) mediaBytes.addAndGet(bytesTransferred.toLong())
                }
                override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            }
            val nativeUpstream = OkHttpDataSource.Factory(client).setTransferListener(transferObserver)
            fun gated(source: DataSource): DataSource = gate?.wrap(source) ?: source
            val upstream = DataSource.Factory { gated(nativeUpstream.createDataSource()) }
            val cacheFactory = cacheOwner?.let { owner ->
                val cached = animeCacheDataSource(asset, owner, nativeUpstream)
                DataSource.Factory {
                    gated(if (owner.targetBytes > 0L) cached.createDataSource() else nativeUpstream.createDataSource())
                }
            } ?: upstream
            val subtitleParser = DefaultSubtitleParserFactory()
            val subtitles = mutableListOf<() -> MediaSource>()
            var unsupportedSubtitle = false
            asset.subtitles.forEach { subtitle ->
                checkedMediaUrl(subtitle.url)
                val mime = subtitle.mimeType ?: subtitleMime(subtitle.url)
                val format = Format.Builder().setId(subtitle.id).setLabel(subtitle.label)
                    .setLanguage(subtitle.language).setSampleMimeType(mime)
                    .setSelectionFlags(if (subtitles.isEmpty()) C.SELECTION_FLAG_DEFAULT else 0).build()
                if (mime == null || !subtitleParser.supportsFormat(format)) {
                    unsupportedSubtitle = true
                    return@forEach
                }
                // Media3's enableLazyLoadingWithSingleTrack is package-private; subtitle
                // extraction is supported through its public progressive factory.
                subtitles += {
                    val extractors = ExtractorsFactory { arrayOf(SubtitleExtractor(subtitleParser.create(format), format)) }
                    ProgressiveMediaSource.Factory(upstream, extractors).setLoadOnlySelectedTracks(true)
                        .createMediaSource(MediaItem.fromUri(subtitle.url))
                }
            }
            val contentType = inferAnimeMediaContentType(Uri.parse(asset.url), asset.mimeType)
            val plan = RemoteMediaPlan(contentType, (cacheOwner?.targetBytes ?: 0L) > 0L) { type, bypassProgressiveCache ->
                val mime = when (type) {
                    C.CONTENT_TYPE_HLS -> MimeTypes.APPLICATION_M3U8
                    C.CONTENT_TYPE_DASH -> MimeTypes.APPLICATION_MPD
                    else -> asset.mimeType.takeIf { type == contentType }
                }
                val item = MediaItem.Builder().setMediaId(asset.playbackIdentity()).setUri(asset.url).setMimeType(mime)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(asset.title.ifBlank { "动漫播放" }).build())
                    .build()
                val media = when (type) {
                    C.CONTENT_TYPE_HLS -> HlsMediaSource.Factory(HlsDataSourceFactory { dataType ->
                        // Mutable playlists and key material never enter the disposable media cache.
                        if (dataType == C.DATA_TYPE_MEDIA || dataType == C.DATA_TYPE_MEDIA_INITIALIZATION)
                            cacheFactory.createDataSource() else upstream.createDataSource()
                    }).setExtractorFactory(AnimeHlsExtractorFactory()).createMediaSource(item)
                    C.CONTENT_TYPE_DASH -> DashMediaSource.Factory(
                        DefaultDashChunkSource.Factory(cacheFactory), upstream,
                    ).createMediaSource(item)
                    C.CONTENT_TYPE_OTHER -> ProgressiveMediaSource.Factory(
                        if (bypassProgressiveCache) upstream else cacheFactory).createMediaSource(item)
                    else -> throw IOException("这条线路的媒体协议暂不支持，请选择 HLS、DASH 或视频文件线路")
                }
                if (subtitles.isEmpty()) media else MergingMediaSource(*(listOf(media) + subtitles.map { it() }).toTypedArray())
            }
            val remote = RemoteMediaResources(plan, client, mediaBytes, pendingLease, unsupportedSubtitle, cacheNotice)
            pendingLease = null
            pendingClient = null
            return remote
        } finally {
            pendingClient?.let(::closeAnimeMediaHttpClient)
            // Cancellation can occur after IO acquired the lease, before delivering it to Main.
            pendingLease?.invoke()
        }
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
        reportUnsupportedSubtitles(generation, unsupportedSubtitle)
        prepareOnEngine(generation, exo, media)
    }

    private fun reportUnsupportedSubtitles(generation: Long, unsupported: Boolean) {
        if (unsupported) publish(generation) {
            it.copy(notice = listOfNotNull(cachePlaybackNotice,
                "部分外挂字幕格式无法由当前设备读取；可换用内嵌字幕或其他线路").joinToString("；"))
        }
    }

    private fun createEngine(gate: AndroidAnimeStartupGate? = null): ExoPlayer = try {
        val loadControl = AndroidAnimeLoadControl(options, gate, lastAcceptedForwardBytes)
        lastAcceptedForwardBytes = loadControl.effectiveForwardBytes
        ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(if (gate == null) C.WAKE_MODE_NETWORK else C.WAKE_MODE_NONE)
            .setPriority(if (gate == null) C.PRIORITY_PLAYBACK else C.PRIORITY_PLAYBACK_PRELOAD)
            .build().also { loadControls[it] = loadControl }
    } catch (cancel: CancellationException) { throw cancel }
    catch (failure: Throwable) {
        val detail = failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
        throw AnimePlayerInitializationException("本机播放器初始化失败：$detail", failure)
    }

    private fun connectEngine(generation: Long, exo: ExoPlayer) {
        val releases = AtomicLong(0L)
        val lastPresentationTimeUs = AtomicLong(C.TIME_UNSET)
        outputFrameReleases = releases
        frameWindow.reset()
        // Media3 routes this listener through the final VideoSink when effects are active,
        // so synthesized frames are counted too. A release is not a physical Present receipt.
        exo.setVideoFrameMetadataListener { presentationTimeUs, releaseTimeNs, _, _ ->
            if (releaseTimeNs != C.TIME_UNSET &&
                lastPresentationTimeUs.getAndSet(presentationTimeUs) != presentationTimeUs) {
                releases.incrementAndGet()
                motionOutputs.release(exo, presentationTimeUs)
            }
        }
        val observer = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { synchronize(generation, exo) }
            override fun onPlayerError(error: PlaybackException) { recoverOrFail(generation, exo, error) }
            override fun onRenderedFirstFrame() {
                if (!isCurrent(generation) || engine.value !== exo || nativeVideoRecoveryFailed || bufferingTimedOut) return
                if (nativeVideoOnly) nativeVideoRestored = true
                val renderedAt = SystemClock.elapsedRealtime()
                publish(generation) { it.copy(firstFrameRendered = true,
                    firstFrameAtMillis = it.firstFrameAtMillis ?: renderedAt,
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
        configureActiveMedia(exo)
        exo.prepare()
        startPlaybackPolling(generation, exo)
    }

    private fun configureActiveMedia(exo: ExoPlayer) {
        exo.playbackParameters = PlaybackParameters(state.value.speed)
        exo.volume = state.value.volume
        exo.playWhenReady = state.value.playWhenReady
        setForegroundNative(foreground || state.value.inPictureInPicture, options.backgroundAudio)
    }

    private fun startPlaybackPolling(generation: Long, exo: ExoPlayer) {
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
        frameWindow.reset()
        lastEnhancementSubmissionAt = null
        clearRealtimeVideoModel()
        clearRealtimeMotion()
        val mode = options.videoEnhancement
        val shaderProfile = options.enhancementModel?.takeIf { Anime4KOptionalModels.supports(it.engineId) }
        val phase = when {
            mode == AnimeVideoEnhancementMode.OFF -> AnimeVideoEnhancementPhase.OFF
            nativeVideoOnly || nativeVideoRecoveryPending || nativeVideoRecoveryFailed -> nativeVideoFallbackPhase
            else -> AnimeVideoEnhancementPhase.WAITING
        }
        publish(generation) { it.copy(realtimeMotion = null, videoEnhancement = AnimeVideoEnhancementState(
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
            enhancementEffect?.invalidateObservations()
            enhancementEffect = null
            if (mode == AnimeVideoEnhancementMode.MODEL && shaderProfile == null) {
                applyRealtimeVideoModel(generation, exo, revision)
                applyRealtimeMotion(generation, exo, revision)
                return
            }
            val preparedModel = options.preparedVideoModel
            val preparedFrames: (() -> AndroidPreparedWindow?)? = preparedModel?.let { model ->
                { preparedVideo?.takeIf { window ->
                    enhancementRevision == revision && isCurrent(generation) && window.request.model == model &&
                        window.request.sessionGeneration == state.value.sessionGeneration &&
                        window.request.timelineGeneration == state.value.timelineGeneration
                } }
            }
            var lastPreparedReport = 0L
            var preparedFramesUsed = 0L
            var lastPreparedWindow: AndroidPreparedWindow? = null
            val reportPrepared: (AndroidPreparedWindow, () -> Boolean) -> Unit = preparedFrame@{ window, programIsCurrent ->
                if (!programIsCurrent()) return@preparedFrame
                val time = SystemClock.elapsedRealtime()
                if (lastPreparedWindow !== window) {
                    lastPreparedWindow = window
                    preparedFramesUsed = 0L
                    lastPreparedReport = 0L
                }
                preparedFramesUsed++
                if (lastPreparedReport == 0L || time - lastPreparedReport >= 250L) {
                    lastPreparedReport = time
                    val completed = preparedFramesUsed
                    playerScope.launch(Dispatchers.Main.immediate) {
                        if (isCurrent(generation) && engine.value === exo && enhancementRevision == revision &&
                            !nativeVideoOnly && programIsCurrent() && options.preparedVideoModel == preparedModel &&
                            preparedVideo === window) {
                            publish(generation) { it.copy(preparedVideoWindow =
                                window.receipt.copy(framesUsed = completed, lastUsedAt = time)) }
                        }
                    }
                }
            }
            val effects: List<Effect> = if (mode == AnimeVideoEnhancementMode.OFF) {
                preparedModel?.nativeScale?.let { scale ->
                    listOf(GlEffect { _, hdr ->
                        if (hdr) androidx.media3.effect.PassthroughShaderProgram()
                        else AndroidPreparedFrameProgram(scale, checkNotNull(preparedFrames), reportPrepared)
                    })
                }.orEmpty()
            } else listOf(
                AndroidAnime4KEffect(mode, options.enhancementScale, shaderProfile,
                    prepared = preparedFrames, onPreparedFrame = reportPrepared) { observation, programIsCurrent ->
                    val observedAt = SystemClock.elapsedRealtime()
                    // The callback originates on Media3's GL thread. Both backend and mode
                    // revision must still own this observation when Main finally consumes it.
                    playerScope.launch(Dispatchers.Main.immediate) {
                        if (!isCurrent(generation) || engine.value !== exo || enhancementRevision != revision ||
                            options.videoEnhancement != mode || nativeVideoOnly || !programIsCurrent()) return@launch
                        val recent = observation.observation?.recentlySubmitting == true &&
                            canReportLiveEnhancement(exo) &&
                            SystemClock.elapsedRealtime() - observedAt < ANIME4K_ACTIVITY_TIMEOUT_MS
                        val newWindow = lastEnhancementSubmissionAt == null
                        lastEnhancementSubmissionAt = observedAt.takeIf { recent }
                        val current = when {
                            !recent -> observation.withoutLiveSample()
                            newWindow -> observation.copy(observation = observation.observation?.copy(
                                sampleFrames = null, sampleDurationMillis = null))
                            else -> observation
                        }
                        publish(generation) { it.copy(videoEnhancement = current) }
                    }
                }.also { enhancementEffect = it },
            )
            // Required once BEFORE prepare even for OFF, so ordinary setting changes can
            // replace the effect list in place. ContentFrame uses setVideoSurfaceView and
            // supplies output dimensions automatically in Media3 1.11.1.
            baseVideoEffects = effects
            updateVideoEffects(exo)
            applyRealtimeMotion(generation, exo, revision)
        } catch (failure: Exception) {
            recoverNativeVideo(generation, exo, AnimeVideoEnhancementPhase.FAILED,
                "Anime4K 未能启用，本次播放已退回原画；重新打开媒体可重试")
        }
    }

    private fun clearRealtimeVideoModel() {
        val loading = realtimeLoading
        loading?.cancel()
        realtimeLoading = null
        val model = realtimeModel
        realtimeModel = null
        realtimeRetirement = retireEnhancement(model, loading, realtimeRetirement)
    }

    private fun retireEnhancement(owner: AutoCloseable?, loading: Job? = null, previous: Job? = null): Job? {
        if (owner == null && loading == null) return previous
        return playerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Vulkan retirement can wait for an in-flight GL submission. Release its
            // permit after native cleanup even when this player scope is being closed.
            withContext(NonCancellable + Dispatchers.IO) {
                previous?.join()
                loading?.join()
                runCatching { owner?.close() }
            }
        }
    }

    private suspend fun closeOpeningEnhancement(owner: AutoCloseable?) {
        if (owner != null) withContext(NonCancellable + Dispatchers.IO) { runCatching { owner.close() } }
    }

    private fun updateVideoEffects(exo: ExoPlayer) {
        exo.setVideoEffects(listOfNotNull(motionEffect) + baseVideoEffects)
    }

    private fun clearRealtimeMotion() {
        val loading = motionLoading
        loading?.cancel()
        motionLoading = null
        motionEffect = null
        val model = motionModel
        motionModel = null
        motionOutputs.clear()
        motionReject = null
        lastMotionSample = null
        lastMotionSampleAt = null
        motionObservedEpoch = null
        resetMotionProgress()
        motionRetirement = retireEnhancement(model, loading, motionRetirement)
    }

    private suspend fun awaitDecodedVideo(current: () -> Boolean): AnimePlayerState? {
        fun ended(value: AnimePlayerState) = value.hasVideoTracks == false ||
            value.phase in setOf(AnimePlayerPhase.ERROR, AnimePlayerPhase.ENDED, AnimePlayerPhase.RELEASED)
        // Loading/pausing before the first frame is owned by the player, not by the GPU
        // initialization timeout. This wait holds no enhancement session or reservation.
        val ready = state.first { !current() || ended(it) || it.firstFrameRendered == true }
        if (!current() || ended(ready)) return null
        val dimensions = withTimeoutOrNull(10_000L) {
            state.first { !current() || ended(it) || it.videoWidth != null && it.videoHeight != null }
        } ?: error("尚未取得解码尺寸")
        return dimensions.takeIf { current() && !ended(it) }
    }

    private fun applyRealtimeMotion(generation: Long, exo: ExoPlayer, revision: Long) {
        val wanted = options.realtimeFrames ?: return
        if (state.value.hasVideoTracks == false ||
            state.value.phase in setOf(AnimePlayerPhase.ERROR, AnimePlayerPhase.ENDED, AnimePlayerPhase.RELEASED)) return
        val motionSession = state.value.sessionGeneration
        val intent = options.realtimeMotionIntent
        failedMotion?.takeIf { it.session == motionSession && it.intent == intent && it.request == wanted }?.let { failure ->
            publish(generation) { it.copy(realtimeMotion = failure.state) }
            return
        }
        if (motionLoading?.isActive == true || motionModel != null ||
            !foreground && !state.value.inPictureInPicture) return
        fun current() = isCurrent(generation) && engine.value === exo && revision == enhancementRevision &&
            options.realtimeFrames == wanted && options.realtimeMotionIntent == intent &&
            !nativeVideoOnly && !nativeVideoRecoveryPending &&
            (foreground || state.value.inPictureInPicture)
        fun motionState(phase: AnimeVideoEnhancementPhase, detail: String, sample: AndroidLiveRifeProgram.RifeSample?,
            outputFrames: Long?) = AnimeRealtimeMotionState(wanted.model.title, phase, detail,
                sample?.synthesized ?: 0L, sample?.graphFps, sample?.generationFps, sample?.inferenceNanos,
                inputWidth = sample?.inputWidth?.takeIf { it > 0 }, inputHeight = sample?.inputHeight?.takeIf { it > 0 },
                targetFps = sample?.targetFps?.takeIf { it.isFinite() && it > 0.0 }, outputInterpolatedFrames = outputFrames)
        fun publishMotion(phase: AnimeVideoEnhancementPhase, detail: String, sample: AndroidLiveRifeProgram.RifeSample? = null,
            outputFrames: Long? = null) {
            if (!current()) return
            publish(generation) { it.copy(realtimeMotion = motionState(phase, detail, sample, outputFrames)) }
        }
        fun rejectMotion(detail: String, sample: AndroidLiveRifeProgram.RifeSample? = null, outputFrames: Long? = null) {
            if (!current()) return
            // Releasing a GPU reservation updates resources.usage, which reapplies foreground
            // policy. Latch before releasing so that refresh cannot reopen this failed request.
            failedMotion = FailedMotion(motionSession, intent, wanted,
                motionState(AnimeVideoEnhancementPhase.UNAVAILABLE, detail, sample, outputFrames))
            motionReject = null
            motionEffect = null
            try { updateVideoEffects(exo) }
            catch (_: Exception) { recoverNativeVideo(generation, exo) }
            finally { clearRealtimeMotion() }
            publishMotion(AnimeVideoEnhancementPhase.UNAVAILABLE, detail, sample, outputFrames)
        }
        val previousPictureRetirement = realtimeRetirement
        val previousMotionRetirement = motionRetirement
        motionLoading = playerScope.launch(Dispatchers.Main.immediate) {
            val loadingJob = currentCoroutineContext()[Job]
            var opening: AndroidLiveRifeVulkan? = null
            try {
                publishMotion(AnimeVideoEnhancementPhase.WAITING, "原帧率可播，正在确认 Vulkan 和当前片源")
                val dimensions = awaitDecodedVideo(::current) ?: return@launch
                if (!current()) return@launch
                AndroidLiveRifeVulkan.memoryBudget(checkNotNull(dimensions.videoWidth), checkNotNull(dimensions.videoHeight))
                val width = checkNotNull(dimensions.videoWidth)
                val height = checkNotNull(dimensions.videoHeight)
                val sourceRate = dimensions.sourceFps?.takeIf { it.isFinite() && it > 0.0 }
                require(sourceRate == null || sourceRate <= 60.5) {
                    "实时补帧支持最高 60 fps 输入，当前保持源帧率"
                }
                require(wanted.automatic || wanted.targetFps == null || sourceRate == null || wanted.targetFps > sourceRate) {
                    "当前源帧率已达到所选目标，无需补帧"
                }
                val displayRate = if (wanted.automatic) {
                    displayRefreshHz() ?: run {
                        // The activity/display can attach after native first frame. Keep this
                        // request waiting for the observed value instead of failing it once.
                        publishMotion(AnimeVideoEnhancementPhase.WAITING, "等待显示屏信息，原帧率继续播放")
                        state.first { !current() || displayRefreshHz() != null }
                        if (!current()) return@launch
                        checkNotNull(displayRefreshHz())
                    }
                } else null
                // AUTO obtains 2x cadence from real input timestamps inside the RIFE program.
                // Format.frameRate may remain unset for HLS/TS and must not block processing.
                val target = if (wanted.automatic) minOf(checkNotNull(displayRate), 120.0) else wanted.targetFps
                // A previous close may still own its GPU reservation after a quick settings change.
                // Wait for that exact retirement, never repeatedly reopen an overloaded renderer.
                previousPictureRetirement?.join()
                previousMotionRetirement?.join()
                if (!current()) return@launch
                withContext(Dispatchers.IO) { opening = AndroidLiveRifeVulkan.open(context, wanted.model, width, height) }
                if (!current()) return@launch
                val active = checkNotNull(opening)
                motionModel = active
                opening = null
                motionReject = { detail -> rejectMotion(detail, lastMotionSample,
                    state.value.realtimeMotion?.outputInterpolatedFrames) }
                val programRevision = AtomicLong(0L)
                motionEffect = GlEffect { _, hdr ->
                    val program = programRevision.incrementAndGet()
                    val outputToken = Any()
                    motionOutputs.activate(exo, outputToken) { current() && motionModel === active }
                    if (hdr) {
                        playerScope.launch(Dispatchers.Main) {
                            if (current() && motionModel === active && programRevision.get() == program)
                                rejectMotion("HDR 原片保持源帧率")
                        }
                        androidx.media3.effect.PassthroughShaderProgram()
                    } else AndroidLiveRifeProgram(active, target, automatic = wanted.automatic,
                        onSynthesizedFrame = { pts -> motionOutputs.queue(outputToken, pts) },
                        onReset = { motionOutputs.reset(outputToken) }) report@{ sample ->
                        // Snapshot on this report's cadence so an older generated count is never
                        // paired with a newer (and potentially larger) released-output count.
                        val output = motionOutputs.snapshot(outputToken) ?: return@report
                        val observedAt = SystemClock.elapsedRealtime()
                        val timeline = state.value.timelineGeneration
                        playerScope.launch(Dispatchers.Main) {
                            if (!current() || motionModel !== active || programRevision.get() != program ||
                                state.value.timelineGeneration != timeline || !motionOutputs.isCurrent(outputToken, output)) return@launch
                            lastMotionSample = sample
                            lastMotionSampleAt = observedAt
                            motionObservedEpoch = output.epoch
                            if (sample.failure != null) {
                                rejectMotion(sample.failure, sample, output.frames)
                            } else if (sample.synthesized > 0) {
                                publishMotion(AnimeVideoEnhancementPhase.ACTIVE, "Vulkan 已生成补帧画面", sample, output.frames)
                            } else if (sample.graphFps?.let { it.isFinite() && it > 0.0 } == true) {
                                publishMotion(AnimeVideoEnhancementPhase.ACTIVE, "静止画面或切换镜头时保留原帧", sample, output.frames)
                            }
                        }
                    }
                }
                updateVideoEffects(exo)
                publishMotion(AnimeVideoEnhancementPhase.CONFIGURED, if (wanted.automatic)
                    "正在读取画面节奏，原帧率继续播放" else "正在接入补帧，原帧率继续播放")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: LinkageError) {
                rejectMotion("Vulkan 组件不可用，原帧率继续播放")
            } catch (_: OutOfMemoryError) {
                rejectMotion("当前内存不足以实时补帧，已恢复源帧率")
            } catch (failure: Exception) {
                rejectMotion(failure.message?.take(100) ?: "实时补帧未能启用，原帧率继续播放")
            } finally {
                closeOpeningEnhancement(opening)
                if (motionLoading === loadingJob) motionLoading = null
            }
        }
    }

    private fun applyRealtimeVideoModel(generation: Long, exo: ExoPlayer, revision: Long) {
        baseVideoEffects = emptyList()
        updateVideoEffects(exo)
        val model = checkNotNull(options.enhancementModel)
        val backend = if (model.engineId == EnhancementEngineIds.ANDROID_CUGAN_VIDEO) "ncnn Vulkan" else "NNAPI 硬件"
        fun current() = isCurrent(generation) && engine.value === exo && enhancementRevision == revision &&
            options.enhancementModel == model && !nativeVideoOnly && !nativeVideoRecoveryPending
        val previousPictureRetirement = realtimeRetirement
        val previousMotionRetirement = motionRetirement
        realtimeLoading = playerScope.launch(Dispatchers.Main.immediate) {
            var opening: AndroidRealtimeVideoModel? = null
            try {
                publish(generation) { it.copy(videoEnhancement = AnimeVideoEnhancementState(AnimeVideoEnhancementMode.MODEL,
                    AnimeVideoEnhancementPhase.WAITING, "原画可播，正在准备 $backend 模型")) }
                val video = awaitDecodedVideo(::current) ?: return@launch
                val width = checkNotNull(video.videoWidth)
                val height = checkNotNull(video.videoHeight)
                previousPictureRetirement?.join()
                previousMotionRetirement?.join()
                if (!current()) return@launch
                withContext(Dispatchers.Default) { opening = AndroidRealtimeVideoModel.open(context, model, width, height) }
                if (!current()) return@launch
                val active = checkNotNull(opening)
                realtimeModel = active
                opening = null
                val fps = video.sourceFps?.takeIf { it.isFinite() && it > 0 } ?: 30.0
                val motionTarget = options.realtimeFrames?.let { it.targetFps ?: fps * 2 } ?: fps
                val frameBudget = 1_000_000_000.0 / motionTarget * .85
                var submitted = 0L
                var slow = 0
                var lastReport = 0L
                baseVideoEffects = listOf(active.effect { elapsed ->
                    submitted++
                    // Treat SDK graph warm-up separately and never repeatedly stall the current media.
                    slow = if (elapsed > frameBudget) slow + 1 else 0
                    val tooSlow = (submitted == 1L && elapsed > 120_000_000L) || slow >= 3
                    val now = SystemClock.elapsedRealtime()
                    if (tooSlow || submitted == 1L || now - lastReport >= 350L) {
                        lastReport = now
                        val frames = submitted
                        playerScope.launch(Dispatchers.Main.immediate) {
                            if (!current() || realtimeModel !== active) return@launch
                            if (tooSlow) {
                                baseVideoEffects = emptyList()
                                try { updateVideoEffects(exo) }
                                catch (_: Exception) { recoverNativeVideo(generation, exo) }
                                finally { clearRealtimeVideoModel() }
                                publish(generation) { it.copy(videoEnhancement = AnimeVideoEnhancementState(
                                    AnimeVideoEnhancementMode.MODEL, AnimeVideoEnhancementPhase.UNAVAILABLE,
                                    "实际处理耗时超过帧预算，已恢复原画；可使用离线增强")) }
                            } else {
                                val observation = AnimeVideoEnhancementObservation(frames, intermediateTextureBytes =
                                    width.toLong() * height * model.nativeScale * model.nativeScale * 4,
                                    recentlySubmitting = canReportLiveEnhancement(exo))
                                val millis = elapsed / 1_000_000L
                                publish(generation) { it.copy(videoEnhancement = AnimeVideoEnhancementState(
                                    AnimeVideoEnhancementMode.MODEL, AnimeVideoEnhancementPhase.ACTIVE,
                                    "$backend · 本帧处理 ${millis}ms", width, height, width * model.nativeScale,
                                    height * model.nativeScale, observation, model.title)) }
                            }
                        }
                    }
                })
                updateVideoEffects(exo)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (current()) {
                    baseVideoEffects = emptyList()
                    try { updateVideoEffects(exo) }
                    catch (_: Exception) { recoverNativeVideo(generation, exo) }
                    finally { clearRealtimeVideoModel() }
                    if (current()) publish(generation) { it.copy(videoEnhancement = AnimeVideoEnhancementState(AnimeVideoEnhancementMode.MODEL,
                        AnimeVideoEnhancementPhase.UNAVAILABLE, "$backend 未能启用，原画继续播放。" + (failure.message?.take(140) ?: ""))) }
                }
            } catch (_: LinkageError) {
                if (current()) {
                    baseVideoEffects = emptyList()
                    try { updateVideoEffects(exo) }
                    catch (_: Exception) { recoverNativeVideo(generation, exo) }
                    finally { clearRealtimeVideoModel() }
                    if (current()) publish(generation) { it.copy(videoEnhancement = AnimeVideoEnhancementState(AnimeVideoEnhancementMode.MODEL,
                        AnimeVideoEnhancementPhase.UNAVAILABLE, "$backend 组件不可用，原画继续播放")) }
                }
            } finally { closeOpeningEnhancement(opening) }
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
        lastEnhancementSubmissionAt = null
        clearRealtimeVideoModel()
        clearRealtimeMotion()
        baseVideoEffects = emptyList()
        nativeVideoFallbackPhase = phase
        nativeVideoFallbackDetail = detail
        recovery?.cancel()
        recoveryNotice = "正在恢复原生画面"
        publish(generation) { it.copy(
            phase = AnimePlayerPhase.OPENING, isBuffering = true, error = null, notice = recoveryNotice,
            realtimeMotion = it.realtimeMotion?.copy(phase = AnimeVideoEnhancementPhase.UNAVAILABLE,
                detail = "图形处理已恢复原帧率", graphFps = null, generationFps = null, inferenceNanos = null),
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
        if (videoHosts.isEmpty()) {
            nativeFrameWaitSince = null
            clearLiveEnhancement()
        }
    }

    private fun canReportLiveEnhancement(exo: ExoPlayer): Boolean =
        videoHosts.isNotEmpty() && (foreground || state.value.inPictureInPicture) && exo.isPlaying

    private fun clearLiveEnhancement() {
        enhancementEffect?.invalidateObservations()
        lastEnhancementSubmissionAt = null
        publish(nativeGeneration) { previous ->
            previous.copy(videoEnhancement = previous.videoEnhancement.withoutLiveSample())
        }
    }

    private fun AnimeVideoEnhancementState.withoutLiveSample(): AnimeVideoEnhancementState {
        val active = observation ?: return this
        return copy(observation = active.copy(sampleFrames = null, sampleDurationMillis = null,
            submitThreadCpuNanos = null, recentlySubmitting = false, gpuSampleSequence = null,
            gpuExecutionNanos = null, gpuSubmittedAtNanos = null, gpuCompletionObservedAtNanos = null,
            presentedAtNanos = null))
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
        } else if (options.videoEnhancement == AnimeVideoEnhancementMode.OFF && options.realtimeFrames == null &&
            options.preparedVideoModel == null && hasStartupAlternative()) {
            stopStalledPlayback(generation, exo, "视频轨道已就绪但未收到首帧，正在尝试本集备用线路")
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
        if (since == null) return
        val canSwitch = startup && now - since >= 10_000L && hasStartupAlternative()
        if (!canSwitch && now - bufferingProgressAt < noProgressLimit && now - since < 120_000L) return
        stopStalledPlayback(generation, exo, when {
            canSwitch -> "这条线路起播缓冲超过 10 秒，正在尝试本集备用线路"
            now - bufferingProgressAt >= noProgressLimit ->
                "这条线路已 ${(noProgressLimit / 1_000L)} 秒没有收到新数据或缓冲进展，请重试或换个来源"
            else -> "这条线路持续缓冲超过 2 分钟，暂未达到起播条件，请重试或换个来源"
        })
    }

    private fun stopStalledPlayback(generation: Long, exo: ExoPlayer, reason: String) {
        bufferingTimedOut = true // Set before stop's synchronous callbacks can republish READY.
        clearLiveEnhancement()
        var cancelled = false
        try { exo.stop() }
        catch (cancel: CancellationException) { cancelled = true; throw cancel }
        catch (_: Exception) { /* A native stop failure must not hide the observed timeout. */ }
        finally {
            if (!cancelled && isCurrent(generation) && engine.value === exo) {
                val timeout = IOException(reason)
                if (nativeVideoOnly && !nativeVideoRestored) failNativeVideoRecovery(generation, timeout)
                else fail(generation, timeout)
            }
        }
    }

    private fun hasStartupAlternative() = localLease == null && state.value.firstFrameAtMillis == null &&
        state.value.hasVideoTracks != false && !nativeVideoOnly && (foreground || state.value.inPictureInPicture) &&
        state.value.playWhenReady && !state.value.playbackSuppressed && request?.hasStartupAlternative?.invoke() == true

    private fun synchronize(generation: Long, exo: ExoPlayer) {
        if (!isCurrent(generation) || engine.value !== exo) return
        if (nativeVideoRecoveryPending || nativeVideoRecoveryFailed || bufferingTimedOut) return
        if (exo.playerError != null) return // The typed error callback alone owns recovery/error publication.
        reusablePreparedVideo?.takeIf { exo.currentPosition >= it.receipt.endMs }?.let {
            reusablePreparedVideo = null
            mutableState.value = state.value.copy(preparedVideoRetainedBytes = 0L)
        }
        val now = SystemClock.elapsedRealtime()
        val enhancementActive = lastEnhancementSubmissionAt?.let { now - it < ANIME4K_ACTIVITY_TIMEOUT_MS } == true &&
            canReportLiveEnhancement(exo)
        if (!enhancementActive) lastEnhancementSubmissionAt = null
        val elapsed = now - lastSampleAt
        val counters = exo.videoDecoderCounters?.also { it.ensureUpdated() }
        val frameMetrics = frameWindow.sample(now,
            epoch = listOf(generation, state.value.timelineGeneration, enhancementRevision, counters,
                exo.playbackParameters.speed, outputFrameReleases),
            flowing = exo.isPlaying && exo.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
                (foreground || state.value.inPictureInPicture) && state.value.hasVideoTracks != false &&
                state.value.firstFrameRendered == true,
            outputFrames = outputFrameReleases.get(), droppedFrames = counters?.droppedBufferCount?.toLong(),
            // Interpolation mixes pre-synthesis and post-synthesis drop stages. Do not invent
            // a percentage with the decoder's input-frame count as an output denominator.
            comparableDropDenominator = options.realtimeFrames == null,
        )
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
                bufferMetrics = loadControls[exo]?.metrics(wallTimeMillis(), exo.isLoading, exo.playbackParameters.speed),
                speed = exo.playbackParameters.speed,
                isBuffering = exo.playbackState == Player.STATE_BUFFERING,
                playbackSuppressed = exo.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE,
                error = null,
                notice = (if (phase == AnimePlayerPhase.READY && exo.playbackState != Player.STATE_BUFFERING &&
                    previous.notice == recoveryNotice) null else previous.notice) ?: cachePlaybackNotice,
                downloadBytesPerSecond = if (localLease == null) speed else -1L,
                // videoSize can describe the effect output. Source quality must come from
                // the decoder format, never the 2x render target or a guessed fallback.
                videoWidth = exo.videoFormat?.width?.takeIf { it > 0 },
                videoHeight = exo.videoFormat?.height?.takeIf { it > 0 },
                sourceFps = exo.videoFormat?.frameRate?.takeIf { it.isFinite() && it > 0 }?.toDouble(),
                displayRefreshHz = displayRefreshHz(),
                decoderOutputFrames = counters?.renderedOutputBufferCount?.toLong(),
                droppedFrames = counters?.droppedBufferCount?.toLong(),
                frameMetrics = frameMetrics,
                videoEnhancement = previous.videoEnhancement.let { last ->
                    val enhancement = if (enhancementActive) last else last.withoutLiveSample()
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
        checkRealtimeMotionProgress(exo, now)
    }

    private fun resetMotionProgress() {
        motionGuardEpoch = null
        motionStableFlowSince = null
        motionLowOutputSince = null
        motionNoOutputSince = null
        motionLastReleasedFrames = null
    }

    /** Judge the combined chain by final releases, not the sum of optimistic per-model budgets. */
    private fun checkRealtimeMotionProgress(exo: ExoPlayer, now: Long) {
        val reject = motionReject
        val snapshot = state.value
        val receipts = motionOutputs.currentSnapshot()
        val sample = lastMotionSample?.takeIf { motionObservedEpoch == receipts?.epoch }
        val metrics = snapshot.frameMetrics
        val output = metrics?.outputFps
        val buffered = (exo.bufferedPosition - exo.currentPosition).coerceAtLeast(0L)
        val flowing = motionModel != null && motionEffect != null && reject != null && receipts != null &&
            receipts.generated > 0L &&
            canReportLiveEnhancement(exo) && !snapshot.isBuffering && !snapshot.playbackSuppressed &&
            snapshot.firstFrameRendered == true && buffered >= (3_000L * exo.playbackParameters.speed).toLong()
        if (!flowing || metrics == null || output == null || !output.isFinite() || metrics.windowDurationMs < 1_000L) {
            resetMotionProgress()
            return
        }
        if (motionGuardEpoch != receipts?.epoch) {
            resetMotionProgress()
            motionGuardEpoch = receipts?.epoch
        }
        val stableSince = motionStableFlowSince ?: now.also { motionStableFlowSince = it }
        val released = receipts?.frames
        val advanced = released != null && released > (motionLastReleasedFrames ?: 0L)
        motionLastReleasedFrames = released
        val freshSample = lastMotionSampleAt?.let {
            now - it in 0L..maxOf(2_000L, (1_500.0 / snapshot.speed).toLong())
        } == true
        val generating = freshSample && sample?.generationFps?.let { it.isFinite() && it > 0.0 } == true
        // The first generated frame can stall downstream before the program reaches its
        // first reporting interval. Its queued PTS is already real evidence in this epoch.
        val waitingForFirstReport = sample == null && (released == 0L || output == 0.0)
        if ((generating || waitingForFirstReport) && !advanced) {
            val waitingSince = motionNoOutputSince ?: now.also { motionNoOutputSince = it }
            if (now - waitingSince >= 12_000L) {
                reject?.invoke(if (waitingForFirstReport) "未确认补帧画面输出，已恢复源帧率"
                    else "持续生成但未有新的补帧画面输出，已恢复源帧率")
                return
            }
        } else motionNoOutputSince = null
        val sourceRate = snapshot.sourceFps?.takeIf { it.isFinite() && it > 0.0 }
            ?: sample?.inputFps?.takeIf { it.isFinite() && it > 0.0 }
        if (now - stableSince < 4_000L || metrics.windowDurationMs < 4_000L || sourceRate == null ||
            output >= sourceRate * exo.playbackParameters.speed * 0.90) {
            motionLowOutputSince = null
            return
        }
        val lowSince = motionLowOutputSince ?: now.also { motionLowOutputSince = it }
        if (now - lowSince >= 2_500L)
            reject?.invoke("当前输出跟不上源帧率，已恢复源帧率")
    }

    private fun displayRefreshHz(): Double? =
        activity?.window?.decorView?.display?.refreshRate?.toDouble()?.takeIf { it.isFinite() && it in 20.0..480.0 }

    private fun recoverOrFail(generation: Long, exo: ExoPlayer, error: PlaybackException) {
        if (!isCurrent(generation) || engine.value !== exo || lastPlaybackError === error) return
        if (nativeVideoRecoveryPending || nativeVideoRecoveryFailed || bufferingTimedOut) return
        lastPlaybackError = error
        clearLiveEnhancement()
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
        if (inspectAfterParserFailure(generation, exo, error)) return
        val responseCode = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        val retryableNetwork = error.errorCode in setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        ) || (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && responseCode != null && responseCode >= 500)
        // A parsed HLS segment failure cannot change the entry playlist into a
        // progressive video file. Only retry transport failures here; the controller
        // owns recovery across confirmed alternatives for the same episode.
        if (!retryableNetwork || hasStartupAlternative() || state.value.recoveryAttempt >= options.automaticRecoveryAttempts) {
            publish(generation) { it.copy(notice = it.notice.takeUnless { value -> value == recoveryNotice }) }
            recoveryNotice = null
            fail(generation, playbackFailure(error, remoteMediaPlan?.type),
                refreshSourceOnStartupFailure = inspectedAfterParserError && mayRefreshStartupSource(error))
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

    private fun inspectAfterParserFailure(generation: Long, exo: ExoPlayer, error: PlaybackException): Boolean {
        if (inspectedAfterParserError || state.value.firstFrameRendered == true ||
            !isStartupFormatError(error)) return false
        val plan = remoteMediaPlan ?: return false
        val client = http ?: return false
        val url = request?.asset?.url ?: return false
        inspectedAfterParserError = true
        recovery?.cancel()
        recoveryNotice = "正在确认线路实际返回的媒体格式"
        publish(generation) { it.copy(phase = AnimePlayerPhase.OPENING, isBuffering = true, error = null, notice = recoveryNotice) }
        recovery = playerScope.launch(Dispatchers.Main) {
            try {
                val result = inspectAnimeRemoteMedia(client, url)
                if (!isCurrent(generation) || engine.value !== exo) return@launch
                val detected = result?.mediaType
                val retryUncachedFile = detected == C.CONTENT_TYPE_OTHER && plan.cacheWasEnabled
                publish(generation) { state -> state.copy(notice = state.notice.takeUnless { it == recoveryNotice }) }
                recoveryNotice = null
                if (detected != null && (detected != plan.type || retryUncachedFile)) {
                    val media = plan.createSource(detected, retryUncachedFile)
                    remoteMediaPlan = plan.copy(type = detected)
                    preparedMedia = media
                    resetBufferingObservation()
                    nativeFrameWaitSince = null
                    publish(generation) { it.copy(hasVideoTracks = null, tracks = emptyList()) }
                    prepareOnEngine(generation, exo, media)
                } else {
                    val failure = result?.failure?.let { IOException(it, error) } ?: playbackFailure(error, plan.type)
                    fail(generation, failure, refreshSourceOnStartupFailure =
                        result?.mayRefreshSource != false && mayRefreshStartupSource(error))
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if (isCurrent(generation) && engine.value === exo) {
                    publish(generation) { state -> state.copy(notice = state.notice.takeUnless { it == recoveryNotice }) }
                    recoveryNotice = null
                    fail(generation, playbackFailure(error, plan.type))
                }
            }
        }
        return true
    }

    private fun isStartupFormatError(error: PlaybackException): Boolean =
        error.cause is UnrecognizedInputFormatException || error.errorCode in setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        )

    private fun mayRefreshStartupSource(error: PlaybackException): Boolean =
        remoteMediaPlan != null && state.value.firstFrameRendered != true && state.value.hasVideoTracks != false &&
            isStartupFormatError(error) && error.errorCode != PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED

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
        if (next.bufferForwardBytes in 1L until Runtime.getRuntime().maxMemory())
            lastAcceptedForwardBytes = next.bufferForwardBytes
        startup?.takeIf { sameStartupConfiguration(it.options, next) }?.player?.let { warm ->
            loadControls[warm]?.update(next)
        }
        engine.value?.let { exo ->
            loadControls[exo]?.let { control ->
                control.update(next)
                lastAcceptedForwardBytes = control.effectiveForwardBytes
                mutableState.value = mutableState.value.copy(bufferMetrics =
                    control.metrics(wallTimeMillis(), exo.isLoading, exo.playbackParameters.speed))
            }
        }
        if (previous.videoEnhancement != next.videoEnhancement || previous.enhancementScale != next.enhancementScale ||
            previous.preparedVideoModel != next.preparedVideoModel || previous.enhancementModel != next.enhancementModel ||
            previous.realtimeFrames != next.realtimeFrames || previous.realtimeMotionIntent != next.realtimeMotionIntent) {
            discardPreparedVideo(retainReusable = true)
            val exo = engine.value
            if (exo != null) applyVideoEnhancement(nativeGeneration, exo)
            else mutableState.value = mutableState.value.copy(videoEnhancement = AnimeVideoEnhancementState(
                next.videoEnhancement, if (next.videoEnhancement == AnimeVideoEnhancementMode.OFF)
                    AnimeVideoEnhancementPhase.OFF else AnimeVideoEnhancementPhase.WAITING,
            ))
        }
    }

    override fun setForegroundNative(foreground: Boolean, backgroundAudio: Boolean) {
        if (!foreground && !state.value.inPictureInPicture) frameWindow.reset()
        resetBufferingObservation()
        nativeFrameWaitSince = null
        if (!foreground) clearLiveEnhancement()
        val exo = engine.value ?: return
        if (!foreground && !state.value.inPictureInPicture && (motionLoading != null || motionModel != null)) {
            clearRealtimeMotion()
            try { updateVideoEffects(exo) }
            catch (_: Exception) { recoverNativeVideo(nativeGeneration, exo) }
            publish(nativeGeneration) { it.copy(realtimeMotion = it.realtimeMotion?.copy(
                phase = AnimeVideoEnhancementPhase.WAITING, detail = "离开画面后暂停补帧",
                graphFps = null, generationFps = null, inferenceNanos = null)) }
        } else if (foreground && options.realtimeFrames != null && motionLoading == null && motionModel == null &&
            !nativeVideoOnly && !nativeVideoRecoveryPending) {
            applyRealtimeMotion(nativeGeneration, exo, enhancementRevision)
        }
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
        if (!playing) { clearLiveEnhancement(); frameWindow.reset(); resetMotionProgress() }
        engine.value?.playWhenReady = playing
    }
    override fun seekNative(positionMs: Long) {
        frameWindow.reset()
        motionOutputs.seek()
        lastMotionSample = null
        lastMotionSampleAt = null
        motionObservedEpoch = null
        resetMotionProgress()
        fun clearSample(value: AnimeRealtimeMotionState) = value.copy(synthesizedFrames = 0L, graphFps = null,
            generationFps = null, inferenceNanos = null, inputWidth = null, inputHeight = null,
            targetFps = null, outputInterpolatedFrames = null)
        mutableState.value = state.value.copy(realtimeMotion = state.value.realtimeMotion?.let(::clearSample))
        failedMotion = failedMotion?.let { it.copy(state = clearSample(it.state)) }
        discardPreparedVideo()
        resetBufferingObservation(); nativeFrameWaitSince = null
        clearLiveEnhancement()
        engine.value?.seekTo(positionMs)
    }
    override fun setSpeedNative(speed: Float): Float {
        frameWindow.reset()
        val exo = engine.value ?: return speed
        exo.setPlaybackSpeed(speed)
        return exo.playbackParameters.speed
    }
    override fun releaseNative() {
        frameWindow.reset()
        clearRealtimeMotion()
        baseVideoEffects = emptyList()
        clearRealtimeVideoModel()
        discardPreparedVideo()
        enhancementEffect?.invalidateObservations()
        enhancementEffect = null
        resetBufferingObservation()
        bufferingTimedOut = false
        nativeFrameWaitSince = null
        recovery?.cancel()
        recovery = null
        recoveryNotice = null
        cachePlaybackNotice = null
        lastPlaybackError = null
        preparedMedia = null
        remoteMediaPlan = null
        inspectedAfterParserError = false
        enhancementRevision++
        lastEnhancementSubmissionAt = null
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
        val oldClient = http
        http = null
        oldClient?.let(::closeAnimeMediaHttpClient)
        old?.release()
        localLease?.close()
        localLease = null
        releaseCache?.invoke()
        releaseCache = null
    }

    override fun onPlaybackEnding(next: AnimePlayerRequest?) {
        // This hook also runs from the cancelled application scope's finalizer.
        // Release synchronously; launching a new child there would never execute.
        if (next == null) {
            discardStartupNow()
            discardStartupCacheNow()
        } else if (next.localMedia != null || next.bypassMediaCache ||
                startupCacheRequest?.let { !isSameStartupAsset(it, next.asset) } == true)
            discardStartupCacheNow()
    }

    internal fun backgroundServiceUnavailable() {
        mutableState.value = mutableState.value.copy(
            notice = "系统暂未允许后台播放服务，离开应用后可能暂停；可回到前台重试",
        )
    }
}

internal fun inferAnimeMediaContentType(uri: Uri, mimeType: String?): Int {
    val normalized = when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
        "application/vnd.apple.mpegurl", "application/x-mpegurl" -> MimeTypes.APPLICATION_M3U8
        "application/dash+xml" -> MimeTypes.APPLICATION_MPD
        else -> mimeType
    }
    return Util.inferContentTypeForUriAndMimeType(uri, normalized)
}

internal fun closeAnimeMediaHttpClient(client: AnimeMediaHttpClient) {
    // Closing a pooled TLS socket can write a close-notify record. The retired
    // client must be released off Main even after its playback scope has ended.
    Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable {
        client.close()
    })
}

/** One player transport owner, selecting the source's declared protocol per resource. */
internal class AnimeMediaHttpClient(private val create: (Boolean) -> OkHttpClient) : Call.Factory {
    private val lock = Any()
    private val https = create(false)
    private var cleartext: OkHttpClient? = null
    private var closed = false

    override fun newCall(request: Request): Call {
        val target = checkedMediaUrl(request.url.toString())
        val client = synchronized(lock) {
            if (closed) throw IOException("播放连接已关闭")
            if (target.isHttps) https else cleartext ?: try {
                create(true).also { cleartext = it }
            } catch (failure: IllegalStateException) { throw IOException(failure.message, failure) }
        }
        return client.newCall(request)
    }

    fun cancelAll() {
        val clients = synchronized(lock) { listOfNotNull(https, cleartext) }
        clients.forEach { it.dispatcher.cancelAll() }
    }

    fun close() {
        val clients = synchronized(lock) { closed = true; listOfNotNull(https, cleartext) }
        clients.forEach { client ->
            try { client.dispatcher.cancelAll() }
            finally { client.connectionPool.evictAll() }
        }
    }
}

internal fun animeMediaHttpClient(asset: AnimePlaybackAsset, forPrefetch: Boolean = false): AnimeMediaHttpClient =
    AnimeMediaHttpClient { publicMedia ->
    OkHttpClient.Builder()
        .apply {
            if (forPrefetch) {
                connectTimeout(3L, TimeUnit.SECONDS)
                readTimeout(3L, TimeUnit.SECONDS)
                callTimeout(6L, TimeUnit.SECONDS)
            }
        }
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
                if (response.code !in setOf(301, 302, 303, 307, 308)) {
                    val start = original.header("Range")?.substringAfter("bytes=")?.substringBefore('-')?.toLongOrNull()
                    if (forPrefetch && start != null && start > 0L &&
                        (response.code != 206 || response.header("Content-Range")?.startsWith("bytes $start-") != true)) {
                        response.close()
                        throw IOException("来源未按请求提供分段范围")
                    }
                    return@addInterceptor response
                }
                val location = response.header("Location")
                val next = location?.let(requestedUrl::resolve)
                response.close()
                if (next == null || ++redirects > 8) throw IOException("来源重定向无效或次数过多")
                checkedMediaUrl(next.toString())
                if (requestedUrl.isHttps && !next.isHttps)
                    throw IOException("播放线路不允许从 HTTPS 跳转到 HTTP，请重新解析线路")
                // Rebuild per hop: scoped credentials never survive a cross-origin redirect.
                original = original.newBuilder().url(next).build()
            }
            @Suppress("UNREACHABLE_CODE")
            error("Unreachable redirect state")
        }
        // These interceptors follow the manual redirect owner, so every hop
        // rechecks the same route and HTTP public-network policy.
        .applyDownloadRoute(asset.route, publicMediaOnly = publicMedia)
        .apply { if (publicMedia) applyPublicMediaNetwork(asset.route) }
        .build()
    }

internal fun animeCacheDataSource(asset: AnimePlaybackAsset, cacheOwner: AndroidAnimeCache,
    upstream: DataSource.Factory): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cacheOwner.cache)
        .setUpstreamDataSourceFactory(upstream)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        .setCacheKeyFactory { spec ->
            cacheOwner.register(asset, animeCacheKey(asset, spec.uri))
        }

internal fun animeCacheKey(asset: AnimePlaybackAsset, uri: Uri): String {
    val identity = asset.playbackIdentity() + "\n" + uri + "\n" +
        headersFor(asset, checkedMediaUrl(uri.toString())).entries
            .sortedBy { it.key.lowercase() }.joinToString("\n") { "${it.key.lowercase()}:${it.value}" }
    return sha256(identity.encodeToByteArray()).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}

private fun checkedMediaUrl(value: String): HttpUrl {
    return try { animeMediaUrl(value).toString().toHttpUrl() }
    catch (failure: IllegalArgumentException) { throw IOException("媒体地址不是有效的公开 HTTP(S) 地址", failure) }
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

private fun playbackFailure(error: PlaybackException, mediaType: Int? = null): Throwable = IOException(when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
        val code = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (code == 401 || code == 403) "播放地址已失效或来源拒绝访问，请刷新线路或更换来源"
        else "来源返回 HTTP ${code ?: "错误"}，请重试或更换线路"
    }
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "当前播放路由未能连接来源，请重试或检查该来源的网络设置"
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
        if (mediaType == C.CONTENT_TYPE_HLS || mediaType == C.CONTENT_TYPE_DASH)
            "已读取播放清单，但这条线路的媒体分片无法读取，请重试或换个来源"
        else "这条线路返回的内容无法作为视频文件读取，请重试或换个来源"
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "这条线路的播放清单无法读取，请重试或换个来源"
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "当前设备不支持这条线路的媒体格式，请换个来源"
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "这条线路的解码器未能启动，请重试或换个来源"
    PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED -> "这条线路需要当前播放器不支持的受保护媒体授权"
    else -> if (error.cause is UnrecognizedInputFormatException)
        "播放器未能识别这条线路返回的媒体格式，请重试或换个来源"
    else readablePlayerError(error)
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
