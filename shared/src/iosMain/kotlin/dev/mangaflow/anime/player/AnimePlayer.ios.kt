@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.mangaflow.anime.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrl
import dev.mangaflow.platform.PlatformContext
import kotlinx.coroutines.CoroutineScope
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.avkit.AVKitMediampPlayerFactory
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeMoviePlayback

actual fun createAnimePlayer(context: PlatformContext, scope: CoroutineScope): AnimePlayer =
    AppleAnimePlayer(scope)

private class AppleAnimePlayer(scope: CoroutineScope) : RoutedNativeAnimePlayer(scope) {
    override val externalSubtitles = false
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
        super.releaseNative()
    }
    override suspend fun createBackend(): MediampPlayer {
        val audio = AVAudioSession.sharedInstance()
        check(audio.setCategory(AVAudioSessionCategoryPlayback, AVAudioSessionModeMoviePlayback, 0uL, null)) {
            "系统暂时无法开启媒体音频会话"
        }
        // AVKit manages native buffer/stall controls; these Swift-only setters are
        // not exposed as writable properties by Kotlin/Native's Objective-C bindings.
        return AVKitMediampPlayerFactory().create(
            parentCoroutineContext = playerScope.coroutineContext,
        )
    }
}

@Composable
actual fun AnimePlayerSurface(player: AnimePlayer, modifier: Modifier) = NativeAnimeSurface(player, modifier)
