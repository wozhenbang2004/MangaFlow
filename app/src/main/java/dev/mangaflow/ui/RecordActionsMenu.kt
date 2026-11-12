package dev.mangaflow.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mangaflow.storage.ShelfState

/** A long press exposes actions without turning every book into an editing toolbar. */
@Composable
internal fun RecordActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
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
    }
}
