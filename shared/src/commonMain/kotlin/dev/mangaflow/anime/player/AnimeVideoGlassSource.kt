package dev.mangaflow.anime.player

import androidx.compose.runtime.staticCompositionLocalOf
import dev.chrisbanes.haze.HazeState

/** A same-window consumer can opt in to capture only the visible player's Compose video Canvas. */
internal val LocalAnimeVideoGlassSource = staticCompositionLocalOf<AnimeVideoGlassSource?> { null }

/** The UI and the native surface collect separately; the offered layer must still own the live frame. */
internal class AnimeVideoGlassSource(
    val haze: HazeState,
    private val player: AnimePlayer,
    private val sessionGeneration: Long,
    private val timelineGeneration: Long,
    private val assetKey: String,
    private val episodeId: String,
) {
    fun matches(player: AnimePlayer, state: AnimePlayerState): Boolean =
        this.player === player && state.sessionGeneration == sessionGeneration &&
            state.timelineGeneration == timelineGeneration && state.assetKey == assetKey &&
            state.episodeId == episodeId && state.canSourceVideoGlass
}

// Desktop's decoded dimensions are a surface candidate, not a rendered-frame acknowledgement.
internal val AnimePlayerState.canSourceVideoGlass: Boolean
    get() = !inPictureInPicture && !playbackSuppressed && hasVideoTracks != false &&
        (phase == AnimePlayerPhase.READY || ended) &&
        nativeVideoStatistics?.let { it.videoOutput == "libmpv" && it.decoderInputSize != null } == true
