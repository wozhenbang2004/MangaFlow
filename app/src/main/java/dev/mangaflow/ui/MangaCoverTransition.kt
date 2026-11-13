package dev.mangaflow.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.LocalMotionBackState

/** A book may appear in both the carousel and grid; only the tapped cover is matched. */
internal data class MangaCoverKey(val mangaId: String, val placement: String)

@OptIn(ExperimentalSharedTransitionApi::class)
internal class MangaCoverTransition(
    val shared: SharedTransitionScope,
    val visibility: AnimatedVisibilityScope,
    val selected: MangaCoverKey?,
    val select: (MangaCoverKey?) -> Unit,
)

internal val LocalMangaCoverTransition = compositionLocalOf<MangaCoverTransition?> { null }

/** Keep the route scope even inside nested tab transitions, as in AndroidX's shared-cover example. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.mangaSharedCover(mangaId: String, placement: String? = null): Modifier {
    val host = LocalMangaCoverTransition.current ?: return this
    if (!LocalMotionEnabled.current) return this
    // Gesture previews already move the whole route. Do not lift its cover back into an
    // untransformed overlay on release; ordinary navigation retains the matched-cover flight.
    val back = LocalMotionBackState.current
    if (back != null && (back.gesturing || back.completed)) return this
    val key = if (placement != null) MangaCoverKey(mangaId, placement)
        else host.selected?.takeIf { it.mangaId == mangaId } ?: return this
    return with(host.shared) {
        val content = rememberSharedContentState(key)
        // Keep the moving cover in its route's drawing order. Lifting only the image
        // into the shared overlay hides detail's scrim, title and actions until landing.
        // The carousel fits the whole artwork; the portrait detail uses a different crop.
        if (key.placement == "discovery-featured" || key.placement.startsWith("ranking-")) this@mangaSharedCover.sharedBounds(
            content, animatedVisibilityScope = host.visibility,
            renderInOverlayDuringTransition = false,
            enter = fadeIn(tween(MangaMotion.NavigationFade, easing = MangaMotion.EnterEasing)),
            exit = fadeOut(tween(MangaMotion.Quick, easing = MangaMotion.ExitEasing)),
            boundsTransform = { _, _ -> spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) },
        ) else this@mangaSharedCover.sharedElement(
            content, animatedVisibilityScope = host.visibility,
            renderInOverlayDuringTransition = false,
            boundsTransform = { _, _ -> spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) },
        )
    }
}

