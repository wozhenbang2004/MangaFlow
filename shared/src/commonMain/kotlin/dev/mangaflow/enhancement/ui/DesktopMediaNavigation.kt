package dev.mangaflow.enhancement.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mangaflow.ui.DesktopVerticalScrollbar
import dev.mangaflow.ui.LocalMangaWindow
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionNavigationColumn
import dev.mangaflow.ui.motion.motionPress

internal data class MediaSettingsDestination<T>(val page: T, val label: String, val icon: ImageVector)

@Composable
internal fun <T> MediaSettingsWorkspace(
    split: Boolean,
    destinations: List<MediaSettingsDestination<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    railWidth: Dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val window = LocalMangaWindow.current
    val editorWindow = if (split) window.copy(width = window.width - railWidth) else window
    Row(modifier.fillMaxWidth()) {
        if (split) DesktopMediaNavigation(destinations, selected, onSelect,
            Modifier.width(railWidth).fillMaxHeight().padding(start = 8.dp, top = 4.dp))
        val editor = Modifier.weight(1f).then(if (split) Modifier.fillMaxHeight() else Modifier)
        CompositionLocalProvider(LocalMangaWindow provides editorWindow) {
            Column(editor, content = content)
        }
    }
}

/** One stationary navigation rail for desktop viewing tools. Only the active editor owns drafts and jobs. */
@Composable
private fun <T> DesktopMediaNavigation(
    destinations: List<MediaSettingsDestination<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val index = destinations.indexOfFirst { it.page == selected }
    Box(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 6.dp, bottom = 8.dp)) {
            MotionNavigationColumn(index, destinations.size, selectionVisible = index >= 0, routeMarker = true) { i, cell ->
                val destination = destinations[i]
                val interaction = remember(destination.page) { MutableInteractionSource() }
                val ink = if (i == index) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                Row(cell.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .selectable(selected = i == index, role = Role.Tab, interactionSource = interaction,
                        indication = null, onClick = { onSelect(destination.page) })
                    .motionPress(interaction, pressedScale = .98f).heightIn(min = 42.dp)
                    .padding(start = 13.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Icon(destination.icon, null, Modifier.size(17.dp), tint = ink)
                    Text(destination.label, color = ink, style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (i == index) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        DesktopVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/** Changing a page reveals its existing editor without composing a second copy of live playback or model state. */
@Composable
internal fun Modifier.mediaSettingsPage(route: Any, enabled: Boolean): Modifier {
    if (!enabled) return this
    val motion = LocalMotionEnabled.current
    var previous by remember { mutableStateOf(route) }
    val reveal = remember { Animatable(1f) }
    LaunchedEffect(route, motion) {
        if (motion && previous != route) {
            previous = route
            reveal.snapTo(0f)
            reveal.animateTo(1f, tween(MangaMotion.Quick, easing = MangaMotion.EnterEasing))
        } else {
            previous = route
            reveal.snapTo(1f)
        }
    }
    return graphicsLayer {
        val progress = if (motion) reveal.value else 1f
        alpha = .72f + .28f * progress
        translationY = 5.dp.toPx() * (1f - progress)
    }
}
