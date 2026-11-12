package dev.mangaflow.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.LocalTabVisibilityScope
import dev.mangaflow.ui.motion.MangaMotion

/** Child delay belongs to selection presence; scrolling and refreshed values do not replay it. */
@Composable
internal fun Modifier.rankingRowEntrance(visibleSlot: Int): Modifier {
    val scope = LocalTabVisibilityScope.current ?: return this
    if (!LocalMotionEnabled.current) return this
    val delay = visibleSlot.coerceIn(0, 5) * 22
    return with(scope) {
        this@rankingRowEntrance.animateEnterExit(
            enter = fadeIn(tween(MangaMotion.Content, delayMillis = delay)) +
                slideInVertically(tween(MangaMotion.Enter, delayMillis = delay,
                    easing = MangaMotion.EnterEasing)) { it / 10 },
            exit = fadeOut(tween(MangaMotion.Quick)),
        )
    }
}
