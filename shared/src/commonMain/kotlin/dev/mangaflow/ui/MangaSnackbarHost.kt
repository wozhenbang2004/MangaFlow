package dev.mangaflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton

/** Keep Material's queue, accessibility timeout and dismissal semantics at the existing host. */
@Composable
internal fun MangaSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    if (platformKind != PlatformKind.DESKTOP) {
        SnackbarHost(state, modifier)
        return
    }
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        SnackbarHost(state) { data ->
            val colors = MaterialTheme.colorScheme
            val hasAction = data.visuals.actionLabel != null || data.visuals.withDismissAction
            BoxWithConstraints(Modifier.padding(horizontal = 8.dp, vertical = 6.dp).widthIn(max = 560.dp),
                contentAlignment = Alignment.Center) {
                val stacked = hasAction && maxWidth < 310.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
                Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerHigh,
                    contentColor = colors.onSurface, tonalElevation = 0.dp, shadowElevation = 6.dp) {
                    if (stacked) {
                        Column(Modifier.padding(start = 14.dp, top = 10.dp, end = 6.dp, bottom = 2.dp)) {
                            Text(data.visuals.message, Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium)
                            Box(Modifier.align(Alignment.End)) { NoticeActions(data) }
                        }
                    } else Row(Modifier.padding(start = 15.dp, end = if (hasAction) 4.dp else 15.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(data.visuals.message, Modifier.weight(1f, fill = false).padding(vertical = 11.dp),
                            style = MaterialTheme.typography.bodyMedium)
                        if (hasAction) NoticeActions(data)
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeActions(data: SnackbarData) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        data.visuals.actionLabel?.let { label ->
            MotionTextButton(onClick = data::performAction,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.primary),
                contentPadding = PaddingValues(horizontal = 10.dp)) { Text(label) }
        }
        if (data.visuals.withDismissAction) MotionIconButton(onClick = data::dismiss,
            modifier = Modifier.size(40.dp), tooltip = "关闭提示") {
            Icon(Icons.Outlined.Close, "关闭提示", Modifier.size(16.dp), tint = colors.onSurfaceVariant)
        }
    }
}
