package dev.mangaflow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.ReviewSource

/** A small in-scene disclosure keeps the backdrop and selected source in one visual context. */
@Composable
internal fun DetailCommentFilter(
    backdrop: HazeState,
    sources: List<ReviewSource>,
    selected: String?,
    longOnly: Boolean,
    hidePromotions: Boolean,
    onSelect: (String?) -> Unit,
    onLongOnly: (Boolean) -> Unit,
    onHidePromotions: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    Popup(alignment = Alignment.TopEnd, offset = with(density) { IntOffset(-16.dp.roundToPx(), 44.dp.roundToPx()) },
        onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        LiquidGlassSurface(backdrop,
            Modifier.width(216.dp),
            cornerRadius = 22.dp, role = GlassSurfaceRole.PANEL) {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FilterChoice("全部来源", selected == null) { onSelect(null); onDismiss() }
                sources.filter { it.hasWorkReviews }.distinctBy { it.id }.forEach { source ->
                    FilterChoice(source.name, selected == source.id) { onSelect(source.id); onDismiss() }
                }
                FilterChoice("仅看长评", longOnly) { onLongOnly(!longOnly) }
                FilterChoice("隐藏推广内容", hidePromotions) { onHidePromotions(!hidePromotions) }
            }
        }
    }
}

@Composable
private fun FilterChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.heightIn(min = 44.dp).clickable(role = Role.Checkbox, onClick = onClick)
        .semantics { stateDescription = if (selected) "已选择" else "未选择" }
        .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.Check, null, tint = if (selected) DetailForeground else Color.Transparent,
            modifier = Modifier.size(16.dp).clearAndSetSemantics { })
        Text(label, color = DetailForeground, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}
