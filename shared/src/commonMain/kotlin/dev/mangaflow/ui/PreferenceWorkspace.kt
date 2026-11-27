package dev.mangaflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind

/** Reflow the same live editors when a desktop window resizes; retain edits and focus targets. */
@Composable
internal fun PreferenceWorkspace(
    modifier: Modifier = Modifier,
    splitWidth: Dp = 584.dp,
    preview: @Composable () -> Unit,
    controls: @Composable () -> Unit,
) {
    val currentPreview by rememberUpdatedState(preview)
    val currentControls by rememberUpdatedState(controls)
    val showPreview = remember { movableContentOf { currentPreview() } }
    val showControls = remember { movableContentOf { currentControls() } }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val split = platformKind == PlatformKind.DESKTOP && maxWidth >= splitWidth * fontScale
        if (split) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.weight(.92f)) { showPreview() }
                Box(Modifier.weight(1.08f)) { showControls() }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                showPreview()
                showControls()
            }
        }
    }
}
