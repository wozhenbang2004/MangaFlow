@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.mangaflow.anime.player

import android.net.Uri
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
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
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
import dev.mangaflow.net.applyDownloadRoute
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.sha256
import java.io.IOException
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

    override suspend fun openNative(request: AnimePlayerRequest, generation: Long) {
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

        fun upstream(): DataSource.Factory = OkHttpDataSource.Factory(client)
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
            }).createMediaSource(item)
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
            val extractors = ExtractorsFactory { arrayOf(SubtitleExtractor(subtitleParser.create(format), null)) }
            sources += ProgressiveMediaSource.Factory(upstream(), extractors)
                .enableLazyLoadingWithSingleTrack(
                    SubtitleExtractor.TRACK_ID,
                    format.buildUpon().setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                        .setCodecs(format.sampleMimeType)
                        .setCueReplacementBehavior(subtitleParser.getCueReplacementBehavior(format)).build(),
                )
                .setLoadOnlySelectedTracks(true)
                .createMediaSource(MediaItem.fromUri(subtitle.url))
        }
        val exo = ExoPlayer.Builder(context)
            .setLoadControl(DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 45_000, 1_500, 3_000)
                .setPrioritizeTimeOverSizeThresholds(false).build())
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        engine.value = exo
        val observer = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { synchronize(generation, exo) }
            override fun onPlayerError(error: PlaybackException) { fail(generation, playbackFailure(error)) }
        }
        listener = observer
        exo.addListener(observer)
        session = MediaSession.Builder(context, exo).setId("mangaflow-anime").build().also {
            AnimePlaybackSessions.attach(context, this, it)
        }
        if (unsupportedSubtitle) publish(generation) {
            it.copy(notice = "部分外挂字幕格式无法由当前设备读取；可换用内嵌字幕或其他线路")
        }
        exo.setMediaSource(if (sources.size == 1) media else MergingMediaSource(*sources.toTypedArray()))
        exo.seekTo(state.value.positionMs)
        exo.playbackParameters = PlaybackParameters(state.value.speed)
        exo.playWhenReady = state.value.playWhenReady
        exo.prepare()
        polling = playerScope.launch(Dispatchers.Main.immediate) {
            while (isActive && isCurrent(generation)) {
                synchronize(generation, exo)
                delay(250L)
            }
        }
    }

    private fun synchronize(generation: Long, exo: ExoPlayer) {
        publish(generation) { previous ->
            if (exo.playerError != null) return@publish previous.copy(
                phase = AnimePlayerPhase.ERROR, isBuffering = false,
                error = readablePlayerError(playbackFailure(exo.playerError!!)),
            )
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
                error = null,
            )
        }
    }

    override fun setPlayingNative(playing: Boolean) {
        engine.value?.playWhenReady = playing
    }
    override fun seekNative(positionMs: Long) { engine.value?.seekTo(positionMs) }
    override fun setSpeedNative(speed: Float) { engine.value?.setPlaybackSpeed(speed) }
    override fun releaseNative() {
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
    val url = value.toHttpUrl()
    require(url.isHttps && url.username.isEmpty() && url.password.isEmpty()) {
        "播放线路必须使用不含内嵌账号密码的 HTTPS 地址"
    }
    return url
}

private fun headersFor(asset: AnimePlaybackAsset, target: HttpUrl, origin: String = target.toString()): Map<String, String> {
    val subtitle = asset.subtitles.firstOrNull { it.url == origin }
    val root = checkedMediaUrl(subtitle?.url ?: asset.url)
    val primary = checkedMediaUrl(asset.url)
    val mayInheritCredentials = primary.host == root.host && primary.port == root.port && primary.scheme == root.scheme
    val inherited = asset.headers.filter { (name, _) ->
        mayInheritCredentials || name.lowercase() !in setOf("authorization", "cookie")
    }
    // Subtitle credentials belong to its own origin. The primary video's
    // credentials must never be rebound to a different subtitle host.
    val supplied = if (subtitle != null) inherited + subtitle.headers else asset.headers
    val sameOrigin = root.host == target.host && root.port == target.port && root.scheme == target.scheme
    return supplied.filter { (name, _) ->
        name.lowercase() !in setOf("host", "connection", "content-length", "transfer-encoding",
            "proxy-authorization", "proxy-authenticate", "upgrade", "range", "accept-encoding") &&
            (sameOrigin || name.lowercase() !in setOf("authorization", "cookie"))
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
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "当前设备无法解码这条线路，可选择其他格式或清晰度"
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
    var cues by remember(exo) { mutableStateOf(exo?.currentCues ?: CueGroup.EMPTY_TIME_ZERO) }
    DisposableEffect(exo) {
        val observer = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) { cues = cueGroup }
        }
        exo?.addListener(observer)
        onDispose { exo?.removeListener(observer) }
    }
    Box(modifier.background(Color.Black)) {
        ContentFrame(player = exo, modifier = Modifier.fillMaxSize())
        AndroidView(
            factory = { SubtitleView(it) },
            modifier = Modifier.fillMaxSize(),
            update = { it.setCues(cues.cues) },
        )
    }
}
