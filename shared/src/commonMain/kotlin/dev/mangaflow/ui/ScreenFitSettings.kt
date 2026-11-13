package dev.mangaflow.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SettingsOverscan
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.storage.ScreenFitMode
import dev.mangaflow.storage.ScreenFitStore
import dev.mangaflow.ui.motion.MotionSwitchThumb

/** One explicit exception to full-screen content, using the existing press-stretch settings switch. */
@Composable
internal fun ScreenFitSettings(modifier: Modifier = Modifier) {
    val context = LocalMangaContext.current
    val store = remember(context) { ScreenFitStore.get(context) }
    val state by store.state.collectAsStateWithLifecycle()
    val interaction = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    val avoidCutout = state.mode == ScreenFitMode.AVOID_CUTOUT
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .toggleable(avoidCutout, interactionSource = interaction, indication = null, role = Role.Switch,
            onValueChange = { store.setMode(if (it) ScreenFitMode.AVOID_CUTOUT else ScreenFitMode.FILL) })
        .heightIn(min = 56.dp).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.SettingsOverscan, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("避让刘海与挖孔", style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            Text(if (avoidCutout) "内容避开挖孔，背景仍铺满" else "默认铺满，按钮自动避让系统栏",
                color = colors.onSurfaceVariant, fontSize = 11.sp)
        }
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            MotionSwitchThumb(avoidCutout, interaction)
        }
    }
}
