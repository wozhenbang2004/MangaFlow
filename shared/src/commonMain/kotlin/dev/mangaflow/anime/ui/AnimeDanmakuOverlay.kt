package dev.mangaflow.anime.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.anime.AnimeController
import dev.mangaflow.anime.danmaku.AnimeDanmakuMode
import dev.mangaflow.anime.player.AnimePlayerState
import kotlinx.coroutines.isActive
import kotlin.math.ceil

/** The native snapshot is the clock anchor; frame interpolation never advances a paused video. */
@Composable
internal fun AnimeDanmakuOverlay(controller: AnimeController, playback: AnimePlayerState, visible: Boolean,
    modifier: Modifier = Modifier) {
    val control = controller.danmaku
    val state by control.state.collectAsStateWithLifecycle()
    val owner = state.ownerKey
    val enabled = state.settings.enabled && state.totalCount > 0
    val advancing = visible && enabled && playback.isPlaying && !playback.isBuffering && !playback.inPictureInPicture
    LaunchedEffect(owner, playback.sessionGeneration, playback.positionMs, playback.speed, playback.isPlaying,
        playback.isBuffering, playback.inPictureInPicture, visible, enabled) {
        val key = owner ?: return@LaunchedEffect
        if (!visible || playback.inPictureInPicture) return@LaunchedEffect
        control.updatePosition(key, playback.positionMs, advancing)
        if (!advancing) return@LaunchedEffect
        val origin = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { now ->
                val elapsed = ((now - origin).coerceAtLeast(0L) / 1_000_000.0 * playback.speed).toLong()
                val anchor = playback.positionMs.coerceAtLeast(0L)
                val position = if (elapsed > Long.MAX_VALUE - anchor) Long.MAX_VALUE else anchor + elapsed
                control.updatePosition(key, if (playback.durationMs > 0L) position.coerceAtMost(playback.durationMs) else position, true)
            }
        }
    }
    var area by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val baseStyle = MaterialTheme.typography.bodyLarge
    val baseFont = baseStyle.fontSize
    val fontPixels = with(density) { baseFont.toPx() }.toDouble() * state.settings.fontScale
    val linePixels = ceil((fontPixels * 1.35 + with(density) { 2.sp.toPx() }).coerceAtLeast(1.0))
    val availableRows = (area.height / linePixels).toInt().coerceAtLeast(1)
    LaunchedEffect(control, owner, availableRows) { control.updateViewport(availableRows) }
    // Values beyond this viewport remain saved. They cannot produce a complete visible row here.
    val fits = area.height > 0 && fontPixels.isFinite() && fontPixels > 0 && linePixels <= area.height
    val textStyle = baseStyle.copy(fontSize = if (fits) (baseFont.value * state.settings.fontScale).sp else baseFont,
        lineHeight = if (fits) with(density) { linePixels.toFloat().toSp() } else baseStyle.lineHeight,
        color = Color.White, shadow = Shadow(Color.Black.copy(alpha = .9f), Offset(0f, 1f), 2f))
    val measurer = rememberTextMeasurer(cacheSize = 256)
    val layoutKeys = if (fits && enabled) state.visible.map { it.id to it.text } else emptyList()
    val layouts = remember(layoutKeys, textStyle, measurer) {
        layoutKeys.associate { (id, text) -> id to measurer.measure(text, textStyle, softWrap = false, maxLines = 1) }
    }
    Canvas(modifier.fillMaxSize().clipToBounds().onSizeChanged { area = it }) {
        if (!visible || !enabled || !fits || playback.inPictureInPicture) return@Canvas
        state.visible.forEach { frame ->
            val layout = layouts[frame.id] ?: return@forEach
            // Timeline lanes are physical rows; BOTTOM already receives a free row near the bottom.
            val y = (frame.lane * linePixels).toFloat()
            if (y < 0f || y + layout.size.height > size.height) return@forEach
            val x = if (frame.mode == AnimeDanmakuMode.SCROLL)
                size.width - (size.width + layout.size.width) * frame.progress
            else (size.width - layout.size.width) / 2f
            drawText(layout, color = Color(frame.color or 0xFF000000.toInt()).copy(alpha = state.settings.opacity),
                topLeft = Offset(x, y))
        }
    }
}
