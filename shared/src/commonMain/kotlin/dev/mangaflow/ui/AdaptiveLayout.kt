package dev.mangaflow.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Layout policy uses the app's measured window, never a device model or physical display. */
@Immutable
data class MangaWindowSize(val width: Dp, val height: Dp, val fontScale: Float) {
    val shortHeight: Boolean get() = height < 480.dp
    val panelHeight: Dp get() = (height - 48.dp).coerceAtLeast(1.dp)
}

val LocalMangaWindow = staticCompositionLocalOf {
    MangaWindowSize(360.dp, 640.dp, 1f)
}

/** These bounds keep artwork and text readable without stretching phone-sized controls. */
object MangaLayout {
    val BrowseWidth = 1120.dp
    val DetailWidth = 960.dp
    val ListWidth = 880.dp
    val SettingsWidth = 760.dp
    val AccountWidth = 840.dp
    val PanelWidth = 640.dp
    val ReaderPageWidth = 864.dp

    fun sidePadding(width: Dp): Dp = if (width < 360.dp) 12.dp else 20.dp

    fun columns(width: Dp, maximum: Int, minimum: Dp, gap: Dp, inset: Dp = 0.dp): Int {
        val available = (width - inset * 2).coerceAtLeast(0.dp)
        return ((available + gap) / (minimum + gap)).toInt().coerceIn(1, maximum.coerceAtLeast(1))
    }
}

/** All targets supply their actual Compose window to the same layout policy. */
@Composable
fun MangaAdaptiveLayout(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val window = MangaWindowSize(maxWidth, maxHeight, LocalDensity.current.fontScale.coerceAtLeast(1f))
        CompositionLocalProvider(LocalMangaWindow provides window, content = content)
    }
}
