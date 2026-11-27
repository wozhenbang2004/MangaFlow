package dev.mangaflow.anime.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.mangaflow.enhancement.ui.mediaSettingsPage
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.DesktopVerticalScrollbar
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.motionPress

internal val animePanelDialogProperties = DialogProperties(usePlatformDefaultWidth = platformKind != PlatformKind.DESKTOP)

internal fun animePanelWidth(desktop: Dp, compact: Dp): Dp =
    if (platformKind == PlatformKind.DESKTOP) desktop else compact

/** Read the actual pane: the settings drawer can be narrow even in a maximized desktop window. */
@Composable
internal fun AnimePaneLayout(
    modifier: Modifier = Modifier,
    minimumWidth: Dp = 660.dp,
    railWidth: Dp = 168.dp,
    showSidebar: Boolean = true,
    selectionKey: Any? = null,
    compactHeader: @Composable () -> Unit = {},
    sidebar: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.(wide: Boolean) -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = showSidebar && platformKind == PlatformKind.DESKTOP && constraints.hasBoundedHeight &&
            maxWidth >= minimumWidth * fontScale && maxHeight >= 224.dp * fontScale
        if (wide) {
            val side = (railWidth * fontScale).coerceAtMost(maxWidth * .34f)
            val height = minOf(maxHeight, 558.dp * fontScale)
            Row(Modifier.fillMaxWidth().height(height).padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.width(side).fillMaxHeight().clip(RoundedCornerShape(17.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .035f))
                    .selectableGroup().padding(6.dp), content = sidebar)
                Column(Modifier.weight(1f).fillMaxHeight().mediaSettingsPage(selectionKey ?: Unit, true)) { content(true) }
            }
        } else Column(Modifier.fillMaxWidth()) {
            compactHeader()
            content(false)
        }
    }
}

@Composable
internal fun AnimePaneChoice(label: String, selected: Boolean, onSelect: () -> Unit, icon: ImageVector? = null,
    count: String? = null, detail: String? = null, enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val input = remember { MutableInteractionSource() }
    val fill = animateColorAsState(if (selected) colors.primary.copy(alpha = .095f) else Color.Transparent,
        if (LocalMotionEnabled.current) tween(MangaMotion.Quick) else snap(), label = "anime-pane-choice")
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(11.dp))
        .drawBehind { drawRect(fill.value) }
        .selectable(selected, interactionSource = input, indication = null, enabled = enabled,
            role = Role.Tab, onClick = onSelect)
        .motionPress(input, enabled, pressedScale = .985f).padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(17.dp), tint = if (selected) colors.primary else colors.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = if (selected) colors.onSurface else colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (count != null) Text(count, color = if (selected) colors.primary else colors.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** Match the thumb to the measured list without making short/empty mobile panels grow. */
@Composable
internal fun AnimeScrollableList(modifier: Modifier = Modifier, state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp), verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    userScrollEnabled: Boolean = true, content: LazyListScope.() -> Unit) {
    Box(modifier) {
        LazyColumn(Modifier.fillMaxWidth(), state = state, contentPadding = contentPadding,
            verticalArrangement = verticalArrangement, userScrollEnabled = userScrollEnabled, content = content)
        if (platformKind == PlatformKind.DESKTOP && userScrollEnabled) Box(Modifier.matchParentSize()) {
            DesktopVerticalScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}
