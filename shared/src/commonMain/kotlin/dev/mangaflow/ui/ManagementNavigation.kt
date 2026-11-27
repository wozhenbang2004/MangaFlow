package dev.mangaflow.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.motionPress

@Composable
internal fun ManagementNavigationItem(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit,
    detail: String? = null, leading: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    val paper by animateColorAsState(colors.primary.copy(alpha = if (selected) .095f else 0f),
        if (LocalMotionEnabled.current) tween(MangaMotion.Quick) else snap(), label = "management-navigation")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
        .background(paper)
        .selectable(selected = selected, enabled = enabled, interactionSource = interaction, indication = null,
            role = Role.Tab, onClick = onClick).motionPress(interaction, enabled)
        .heightIn(min = 39.dp).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        leading()
        Text(label, Modifier.weight(1f), color = if (selected) colors.primary else colors.onSurface,
            style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        detail?.let { Text(it, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
internal fun ManagementResultFilter(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    val paper by animateColorAsState(colors.primary.copy(alpha = if (selected) .10f else 0f),
        if (LocalMotionEnabled.current) tween(MangaMotion.Quick) else snap(), label = "management-result-filter")
    Box(Modifier.clip(RoundedCornerShape(8.dp))
        .background(paper)
        .selectable(selected = selected, enabled = enabled, interactionSource = interaction, indication = null,
            role = Role.RadioButton, onClick = onClick).motionPress(interaction, enabled)
        .heightIn(min = 36.dp).padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = if (selected) colors.primary else colors.onSurfaceVariant)
    }
}
