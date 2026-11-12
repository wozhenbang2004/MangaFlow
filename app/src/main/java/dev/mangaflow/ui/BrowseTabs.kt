package dev.mangaflow.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Reuses the Ovio Liquid Tabs edge-spring adaptation already used by DetailSections. */
@Composable
internal fun BrowseTabs(selected: Int, labels: List<String>, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    var movingRight by remember { mutableStateOf(true) }
    val left = animateFloatAsState(selected.toFloat(),
        spring(dampingRatio = 1f, stiffness = if (movingRight) 230f else 520f), label = "browse-tab-left")
    val right = animateFloatAsState(selected + 1f,
        spring(dampingRatio = 1f, stiffness = if (movingRight) 520f else 230f), label = "browse-tab-right")
    val indicator = MaterialTheme.colorScheme.onSurface.copy(alpha = .065f)
    Row(
        modifier.height(48.dp).drawBehind {
            val slot = size.width / labels.size
            val inset = 4.dp.toPx()
            val l = left.value * slot + inset
            val r = right.value * slot - inset
            val h = 32.dp.toPx()
            drawRoundRect(indicator, Offset(l, (size.height - h) / 2),
                Size((r - l).coerceAtLeast(h), h), CornerRadius(h / 2))
        }.selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            Box(Modifier.weight(1f).height(48.dp).selectable(
                selected = selected == index, role = Role.Tab,
                onClick = { movingRight = index.toFloat() > left.value; onSelect(index) },
            ), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 14.sp,
                    fontWeight = if (selected == index) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected == index) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
