package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

import dev.mangaflow.platform.*

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment

/** Keep the compact toolbar geometry while sharing interruptible, draw-time tab motion. */
@Composable
internal fun BrowseTabs(selected: Int, labels: List<String>, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    MotionTabs(
        selected = selected,
        labels = labels,
        onSelect = onSelect,
        modifier = modifier,
        foreground = MaterialTheme.colorScheme.onSurface,
        controlHeight = 44.dp,
        treatment = MotionTabTreatment.Soft,
        inGlassGroup = true,
        fontSize = 14.sp,
        selectedFontWeight = FontWeight.SemiBold,
        unselectedFontWeight = FontWeight.Normal,
    )
}
