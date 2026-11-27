package dev.mangaflow.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.LocalPlatformAccessibilityPreferences
import dev.mangaflow.ui.motion.MangaMotion

// Even a window restored halfway through its first animation has already had its entrance.
private val FirstWindowOnly = Saver<Boolean, Boolean>(save = { false }, restore = { it })
private const val PortraitDurationMs = 300

@Composable
internal fun AppLaunchReveal(play: Boolean, icon: Painter?, content: @Composable () -> Unit) {
    if (platformKind == PlatformKind.IOS) {
        IosContentReveal(play, content)
        return
    }
    Box(Modifier.fillMaxSize()) {
        content()
        if (platformKind == PlatformKind.DESKTOP) DesktopLaunchPortrait(play, icon)
    }
}

@Composable
private fun BoxScope.DesktopLaunchPortrait(play: Boolean, icon: Painter?) {
    val accessibility = LocalPlatformAccessibilityPreferences.current
    val durationScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val eligible = play && icon != null && LocalMotionEnabled.current &&
        durationScale > 0f && !accessibility.increaseContrast && !accessibility.touchExploration
    var opening by rememberSaveable(stateSaver = FirstWindowOnly) { mutableStateOf(eligible) }
    if (!opening) return
    if (!eligible || icon == null) {
        SideEffect { opening = false }
        return
    }
    val clock = remember { Animatable(0f) }
    LaunchedEffect(clock) {
        clock.animateTo(PortraitDurationMs.toFloat(), tween(PortraitDurationMs, easing = LinearEasing))
        opening = false
    }
    // Draw directly over live content. Only the small portrait needs an alpha layer.
    val background = MaterialTheme.colorScheme.background
    Canvas(Modifier.matchParentSize()) {
        val uncover = FastOutSlowInEasing.transform((clock.value / 220f).coerceIn(0f, 1f))
        drawRect(background, alpha = .72f * (1f - uncover))
    }
    Box(Modifier.align(Alignment.Center).size(108.dp).graphicsLayer {
        val settle = MangaMotion.EnterEasing.transform(clock.value / PortraitDurationMs)
        scaleX = 1f - .035f * settle
        scaleY = scaleX
        translationY = -3.dp.toPx() * settle
        alpha = 1f - FastOutSlowInEasing.transform(((clock.value - 84f) / 216f).coerceIn(0f, 1f))
    }) {
        Image(icon, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Fit)
        Canvas(Modifier.matchParentSize()) {
            val sweep = (clock.value - 34f) / 134f
            if (sweep > 0f && sweep < 1f) {
                val x = size.width * (.57f + .17f * MangaMotion.EnterEasing.transform(sweep))
                val start = Offset(x, size.height * .027f)
                val end = start + Offset(size.width * .13f, 0f)
                val glow = 4f * sweep * (1f - sweep)
                val lower = Offset(0f, .45.dp.toPx())
                drawLine(Color(0xFF45464A), start + lower, end + lower, 1.45.dp.toPx(),
                    cap = StrokeCap.Round, alpha = .16f * glow)
                drawLine(Color.White, start, end, .8.dp.toPx(), cap = StrokeCap.Round, alpha = .70f * glow)
            }
        }
    }
}

/**
 * The existing iOS content reveal uses beUI's saved morph easing and radius reveal.
 * Source: starc007/ui-components a866cb2, morphing-search.tsx / popover-morph.tsx.
 * This launch adaptation adds no loading route, bitmap, input gate or data-ready delay.
 */
@Composable
private fun IosContentReveal(play: Boolean, content: @Composable () -> Unit) {
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
