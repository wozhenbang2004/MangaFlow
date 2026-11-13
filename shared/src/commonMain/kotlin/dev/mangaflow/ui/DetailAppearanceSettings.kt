package dev.mangaflow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.storage.DetailAppearanceStore
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionSlider
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.platform.*
import kotlin.math.roundToInt

@Composable
internal fun DetailAppearanceSettings() {
    val context = LocalMangaContext.current
    val store = remember(context) { DetailAppearanceStore.get(context) }
    val state by store.state.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    DisposableEffect(store) { onDispose { store.save() } }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                onClickLabel = if (expanded) "收起封面氛围调节" else "调节详情封面氛围",
                onClick = { expanded = !expanded }).motionPress(interaction),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.BlurOn, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
            Text("封面氛围", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Box(Modifier.width(44.dp).height(32.dp), contentAlignment = Alignment.Center) {
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null,
                    Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            }
        }
        MotionTabContent(expanded, Modifier.fillMaxWidth(), contentKey = { it },
            order = { if (it) 1 else 0 }, fillContent = false, soft = true) { open ->
            if (open) Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("仅影响详情页的封面背景", Modifier.weight(1f), fontSize = 11.sp, color = colors.onSurfaceVariant)
                    MotionIconButton(onClick = store::reset, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.RestartAlt, "还原封面氛围", Modifier.size(17.dp))
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("模糊程度", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(if (modernPlatformBlur) state.blur.roundToInt().toString() else "不可用",
                        color = colors.onSurfaceVariant, fontSize = 11.sp)
                }
                MotionSlider(state.blur, store::setBlur, enabled = modernPlatformBlur, valueRange = 0f..24f,
                    onValueChangeFinished = store::save, formatValue = { it.roundToInt().toString() },
                    modifier = Modifier.semantics { contentDescription = "详情背景模糊强度" })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("封面背景", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("${(state.strength * 100).roundToInt()}%", color = colors.onSurfaceVariant, fontSize = 11.sp)
                }
                Text("越低越淡，越高越明显", color = colors.onSurfaceVariant, fontSize = 11.sp)
                MotionSlider(state.strength, store::setStrength, onValueChangeFinished = store::save,
                    formatValue = { "${(it * 100).roundToInt()}%" },
                    modifier = Modifier.semantics { contentDescription = "封面背景显眼程度；越低越淡，越高越明显" })
            }
        }
    }
}
