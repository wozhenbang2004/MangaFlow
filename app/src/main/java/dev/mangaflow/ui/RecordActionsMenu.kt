package dev.mangaflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mangaflow.storage.ShelfState
import dev.mangaflow.ui.motion.MotionDialog

/** A long press exposes actions without turning every book into an editing toolbar. */
@Composable
internal fun RecordActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    removeLabel: String? = null,
    onRemove: (() -> Unit)? = null,
    onDetails: (() -> Unit)? = null,
    shelfState: ShelfState? = null,
    onShelfState: ((ShelfState) -> Unit)? = null,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 164.dp, max = 240.dp),
        shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        shadowElevation = 4.dp,
    ) {
        if (onDetails != null) DropdownMenuItem(
            text = { Text("详情与目录") },
            leadingIcon = { Icon(Icons.Outlined.MenuBook, null, Modifier.size(19.dp)) },
            onClick = { onDismiss(); onDetails() },
        )
        if (shelfState != null && onShelfState != null) {
            ShelfState.entries.forEach { state ->
                val label = when (state) {
                    ShelfState.NORMAL -> "普通收藏"
                    ShelfState.FOLLOWING -> "追更"
                    ShelfState.HOARDING -> "养肥"
                    ShelfState.PAUSED -> "搁置"
                }
                DropdownMenuItem(
                    text = { Text(if (state == shelfState) "• $label" else label) },
                    onClick = { onDismiss(); onShelfState(state) },
                )
            }
        }
        if (removeLabel != null && onRemove != null) DropdownMenuItem(
            text = { Text(removeLabel, color = MaterialTheme.colorScheme.error) },
            leadingIcon = {
                Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(19.dp),
                    tint = MaterialTheme.colorScheme.error)
            },
            onClick = { onDismiss(); onRemove() },
        )
    }
}

internal data class RecordRemovalPrompt(val title: String, val record: String, val note: String)

@Composable
internal fun RecordRemovalDialog(
    prompt: RecordRemovalPrompt?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var retained by remember { mutableStateOf(prompt) }
    SideEffect { if (prompt != null) retained = prompt }
    MotionDialog(visible = prompt != null, onDismissRequest = onDismiss) {
        val shown = prompt ?: retained
        if (shown != null) Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(start = 22.dp, end = 16.dp, top = 22.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(shown.title, style = MaterialTheme.typography.titleMedium)
                Text(shown.record, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(shown.note, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = prompt != null) { Text("取消") }
                    TextButton(onClick = onConfirm, enabled = prompt != null) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
