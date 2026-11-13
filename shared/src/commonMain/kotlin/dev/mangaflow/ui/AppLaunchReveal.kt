package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

import dev.mangaflow.platform.*

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion

/**
 * A single short opening of the real content, using beUI's saved morph easing and radius reveal.
 * Source: starc007/ui-components a866cb2, morphing-search.tsx / popover-morph.tsx.
 * This launch adaptation adds no loading route, bitmap, input gate or data-ready delay.
 */
@Composable
internal fun AppLaunchReveal(play: Boolean, content: @Composable () -> Unit) {
    val motion = LocalMotionEnabled.current
    var completed by rememberSaveable { mutableStateOf(!play) }
    val reveal = remember { Animatable(if (play && !completed) 0f else 1f) }
    LaunchedEffect(play, motion) {
        if (!play || !motion || completed) {
            reveal.snapTo(1f)
        } else {
            reveal.animateTo(1f, tween(MangaMotion.SearchClip, easing = MangaMotion.SearchEasing))
        }
        completed = true
    }
    val opening = play && motion && !completed
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            val progress = if (opening) reveal.value else 1f
            translationY = (1f - progress) * 8.dp.toPx()
            clip = progress < 1f
            if (clip) shape = RoundedCornerShape(
                topStart = (20f * (1f - progress)).dp,
                topEnd = (20f * (1f - progress)).dp,
            )
        }) { content() }
    }
}
