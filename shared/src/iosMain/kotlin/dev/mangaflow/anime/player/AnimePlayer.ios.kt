@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.mangaflow.anime.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrl
import dev.mangaflow.platform.PlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.avkit.AVKitMediampPlayerFactory
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeMoviePlayback

actual fun createAnimePlayer(context: PlatformContext, scope: CoroutineScope): AnimePlayer =
    AppleAnimePlayer(scope)

actual fun initializeAnimeStorage(context: PlatformContext) = Unit

private class AppleAnimePlayer(scope: CoroutineScope) : RoutedNativeAnimePlayer(scope) {
    override val externalSubtitles = false
    override val supportsVideoEnhancement get() = AppleAnimeEnhancementBridge.factory != null
    val enhancementRenderer = MutableStateFlow<AppleAnimeEnhancementRenderer?>(null)

    override fun validateAsset(asset: AnimePlaybackAsset) {
        val path = asset.url.toHttpUrl().encodedPath.lowercase()
        require(!path.endsWith(".mkv") && !path.endsWith(".webm") &&
            asset.mimeType !in setOf("video/x-matroska", "video/webm")) {
            "iOS 系统播放器无法读取 MKV / WebM，请选择 HLS 或 MP4 线路"
        }
    }
    override fun validateLocalMedia(lease: AnimeLocalMediaLease) {
        require(lease.entry.mimeType !in setOf("video/x-matroska", "video/webm")) {
            "iOS 系统播放器无法读取这份 MKV / WebM 离线媒体，请使用 HLS 或 MP4 线路"
        }
    }
    override fun releaseNative() {
        val old = enhancementRenderer.value
        enhancementRenderer.value = null
        old?.dispose()
        super.releaseNative()
    }

    override fun configureNative(previous: AnimePlayerOptions, next: AnimePlayerOptions) {
        super.configureNative(previous, next)
        if (previous.videoEnhancement != next.videoEnhancement) configureEnhancement()
    }

    private fun configureEnhancement() {
        val renderer = enhancementRenderer.value
        val mode = options.videoEnhancement
        if (renderer == null) {
            mutableState.value = mutableState.value.copy(videoEnhancement = AnimeVideoEnhancementState(
                mode = mode,
                phase = if (mode == AnimeVideoEnhancementMode.OFF) AnimeVideoEnhancementPhase.OFF
                    else if (engine.value == null) AnimeVideoEnhancementPhase.WAITING
                    else AnimeVideoEnhancementPhase.UNAVAILABLE,
                detail = if (mode != AnimeVideoEnhancementMode.OFF && engine.value != null)
                    "当前 iOS 宿主未提供 Anime4K 渲染器，继续使用原画" else null,
            ))
            return
        }
        renderer.configure(mode.name, Anime4KShaderLibrary.UPSTREAM_COMMIT,
            Anime4KShaderLibrary.sources(mode).map { it.source })
    }

    override fun setVideoScaleNative(scale: AnimeVideoScale) {
        super.setVideoScaleNative(scale)
        enhancementRenderer.value?.setVideoScale(scale.name)
    }

    override fun setForegroundNative(foreground: Boolean, backgroundAudio: Boolean) {
        enhancementRenderer.value?.setForeground(foreground)
    }

    override fun seekNative(positionMs: Long) {
        enhancementRenderer.value?.seekStarted()
        super.seekNative(positionMs)
    }

    override fun onMediaOpeningNative() { enhancementRenderer.value?.bind(null) }
    override fun onMediaReadyNative() {
        enhancementRenderer.value?.refresh() ?: configureEnhancement()
    }

    override suspend fun createBackend(): MediampPlayer {
        val audio = AVAudioSession.sharedInstance()
        check(audio.setCategory(AVAudioSessionCategoryPlayback, AVAudioSessionModeMoviePlayback, 0uL, null)) {
            "系统暂时无法开启媒体音频会话"
        }
        // AVKit manages native buffer/stall controls; these Swift-only setters are
        // not exposed as writable properties by Kotlin/Native's Objective-C bindings.
        val generation = nativeGeneration
        var rendererForBackend: AppleAnimeEnhancementRenderer? = null
        return AVKitMediampPlayerFactory().create(
            parentCoroutineContext = playerScope.coroutineContext,
            configurePlayer = { player ->
                val factory = AppleAnimeEnhancementBridge.factory
                if (factory != null) {
                    rendererForBackend = factory.create(player, object : AppleAnimeEnhancementListener {
                        override fun enhancementChanged(
                            mode: String, phase: String, detail: String?, inputWidth: Int, inputHeight: Int,
                            outputWidth: Int, outputHeight: Int,
                        ) {
                            // Swift additionally fences each item/configuration and actual drawable.
                            if (options.videoEnhancement.name != mode) return
                            val reportedPhase = AnimeVideoEnhancementPhase.entries.firstOrNull { it.name == phase }
                                ?: AnimeVideoEnhancementPhase.FAILED
                            publish(generation) { it.copy(videoEnhancement = AnimeVideoEnhancementState(
                                mode = options.videoEnhancement, phase = reportedPhase, detail = detail,
                                inputWidth = inputWidth.takeIf { value -> value > 0 },
                                inputHeight = inputHeight.takeIf { value -> value > 0 },
                                outputWidth = outputWidth.takeIf { value -> value > 0 },
                                outputHeight = outputHeight.takeIf { value -> value > 0 },
                            )) }
                        }
                    }).also {
                        it.setForeground(foreground)
                        it.setVideoScale(state.value.videoScale.name)
                    }
                    enhancementRenderer.value = rendererForBackend
                }
                configureEnhancement()
            },
            configurePlayerItem = { item, _ ->
                if (isCurrent(generation) && enhancementRenderer.value === rendererForBackend)
                    rendererForBackend?.bind(item)
            },
        )
    }
}

@Composable
actual fun AnimePlayerSurface(player: AnimePlayer, modifier: Modifier) {
    val apple = player as? AppleAnimePlayer
    if (apple == null) {
        NativeAnimeSurface(player, modifier)
        return
    }
    val renderer by apple.enhancementRenderer.collectAsState()
    val current = renderer
    if (current == null) NativeAnimeSurface(player, modifier)
    else key(current) {
        UIKitView(
            factory = { current.view },
            modifier = modifier,
            update = { current.setSurfaceAttached(true) },
            onRelease = { current.setSurfaceAttached(false) },
        )
    }
}
