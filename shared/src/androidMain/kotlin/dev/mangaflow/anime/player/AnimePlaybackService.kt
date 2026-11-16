@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.mangaflow.anime.player

import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * The application controller owns the engine. The service only exposes that same
 * session to Android's notification, lock screen and audio-focus lifecycle.
 */
internal object AnimePlaybackSessions {
    private var session: MediaSession? = null
    private var owner: AndroidAnimePlayer? = null
    private var service: AnimePlaybackService? = null

    fun attach(context: Context, player: AndroidAnimePlayer, next: MediaSession) {
        session?.let { service?.removeSession(it) }
        owner = player
        session = next
        service?.addSession(next)
        try {
            // Starting from the visible player avoids the five-second foreground
            // deadline while a slow source is still opening. MediaSessionService
            // promotes itself when actual playback begins.
            context.startService(Intent(context, AnimePlaybackService::class.java))
        } catch (_: IllegalStateException) {
            player.backgroundServiceUnavailable()
        } catch (_: SecurityException) {
            player.backgroundServiceUnavailable()
        }
    }

    fun detach(old: MediaSession) {
        if (session !== old) return
        service?.removeSession(old)
        session = null
        owner = null
        // MediaSessionService demotes/stops an unbound idle service itself.
        // Do not stopSelf here: a source switch can attach its next session in
        // the same application-owned playback operation.
    }

    fun connect(value: AnimePlaybackService) {
        service = value
        session?.let(value::addSession) ?: value.stopSelf()
    }

    fun disconnect(value: AnimePlaybackService) {
        if (service !== value) return
        service = null
        // A killed/removed service must not leave an invisible foreground-less player.
        owner?.pause()
    }

    fun current(): MediaSession? = session
}

class AnimePlaybackService : MediaSessionService() {
    override fun onCreate() {
        super.onCreate()
        AnimePlaybackSessions.connect(this)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        AnimePlaybackSessions.current()

    override fun onDestroy() {
        AnimePlaybackSessions.disconnect(this)
        super.onDestroy()
    }
}
