package dev.mangaflow.anime.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.mangaflow.platform.PlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.mpv.MpvMediampPlayerFactory

actual fun createAnimePlayer(context: PlatformContext, scope: CoroutineScope): AnimePlayer =
    DesktopAnimePlayer(context, scope)

private class DesktopAnimePlayer(
    private val context: PlatformContext,
    scope: CoroutineScope,
) : RoutedNativeAnimePlayer(scope) {
    override suspend fun createBackend(): MediampPlayer {
        var pending: MediampPlayer? = null
        return try {
            withContext(Dispatchers.IO) {
                // The shipped platform runtime loads off the Swing UI thread.
                MpvMediampPlayerFactory().create(context, playerScope.coroutineContext) { options ->
                    options.option("cache", "yes")
                    options.option("cache-secs", "45")
                    options.option("demuxer-max-bytes", (32L * 1024 * 1024).toString())
                    options.option("demuxer-max-back-bytes", (8L * 1024 * 1024).toString())
                    options.option("ytdl", "no")
                    check(options.option("access-references", "no")) { "播放器无法关闭外部资源引用" }
                }.also { pending = it }
            }
        } catch (failure: Throwable) {
            pending?.close()
            throw failure
        }
    }
}

@Composable
actual fun AnimePlayerSurface(player: AnimePlayer, modifier: Modifier) = NativeAnimeSurface(player, modifier)
