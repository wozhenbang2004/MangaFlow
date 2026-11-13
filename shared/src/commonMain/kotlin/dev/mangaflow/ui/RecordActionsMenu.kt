package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

import dev.mangaflow.platform.*

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A long press exposes actions without turning every book into an editing toolbar. */
@Composable
internal fun RecordActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onDetails: (() -> Unit)? = null,
) {
    RecordActionsMenuContent(expanded, onDismiss, onDetails)
}

@Composable
internal fun CloudFavoriteActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onDetails: () -> Unit,
    onRemove: () -> Unit,
    removeEnabled: Boolean,
    removeLabel: String,
) {
    RecordActionsMenuContent(expanded, onDismiss, onDetails, onRemove, removeEnabled, removeLabel)
}

@Composable
private fun RecordActionsMenuContent(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onDetails: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    removeEnabled: Boolean = true,
    removeLabel: String = "移除",
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
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(19.dp)) },
            onClick = { onDismiss(); onDetails() },
        )
        if (onRemove != null) DropdownMenuItem(
            text = { Text(removeLabel, color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.error) },
            enabled = removeEnabled,
            onClick = { onDismiss(); onRemove() },
        )
    }
}
