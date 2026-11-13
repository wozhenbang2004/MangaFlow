@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.mangaflow.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.storage.ScreenFitMode
import dev.mangaflow.storage.ScreenFitState
import dev.mangaflow.storage.ScreenFitStore

internal val LocalScreenFit = staticCompositionLocalOf { ScreenFitState() }

/** Backgrounds remain outside this policy; only page foregrounds opt into its viewport. */
@Composable
internal fun ScreenFitProvider(content: @Composable () -> Unit) {
    val context = LocalMangaContext.current
    val store = remember(context) { ScreenFitStore.get(context) }
    val state by store.appliedState.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalScreenFit provides state, content = content)
}

@Composable
internal fun screenFitViewportInsets(): WindowInsets = when (LocalScreenFit.current.mode) {
    ScreenFitMode.AUTO -> WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    ScreenFitMode.FILL -> WindowInsets(0, 0, 0, 0)
    ScreenFitMode.AVOID_CUTOUT -> WindowInsets.displayCutout
    ScreenFitMode.SAFE -> WindowInsets.safeDrawing
}

/** Residual protection belongs to headers, list content padding and floating controls. */
@Composable
internal fun screenFitContentInsets(): WindowInsets =
    WindowInsets.safeDrawing.exclude(screenFitViewportInsets())

@Composable
internal fun Modifier.screenFitViewport(): Modifier {
    val state = LocalScreenFit.current
    return windowInsetsPadding(screenFitViewportInsets())
        .padding(state.marginDp.dp)
        .then(if (state.cornerDp > 0f) Modifier.clip(RoundedCornerShape(state.cornerDp.dp)) else Modifier)
}
