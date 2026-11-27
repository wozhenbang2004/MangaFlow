package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

import dev.mangaflow.platform.*

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment

private val BrowseTabSize = 14.sp

@Composable
internal fun rememberBrowseTabInk(labels: List<String>): HeaderInkCells {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.copy(fontSize = BrowseTabSize)
    val cells by remember(labels, density, measurer, style) {
        derivedStateOf {
            val sizes = labels.map { label ->
                val normal = measurer.measure(label, style.copy(fontWeight = FontWeight.Normal), maxLines = 1).size
                val bold = measurer.measure(label, style.copy(fontWeight = FontWeight.SemiBold), maxLines = 1).size
                with(density) { DpSize(maxOf(normal.width, bold.width).toDp(), maxOf(normal.height, bold.height).toDp()) }
            }
            HeaderInkCells(4.dp, sizes, wraps = density.fontScale > 1.3f)
        }
    }
    return cells
}

/** Keep the compact toolbar geometry while sharing interruptible, draw-time tab motion. */
@Composable
internal fun BrowseTabs(selected: Int, labels: List<String>, backdrop: HazeState, modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit) {
    MotionTabs(
        selected = selected,
        labels = labels,
        onSelect = onSelect,
        modifier = modifier,
        foreground = MaterialTheme.colorScheme.onSurface,
        controlHeight = 44.dp,
        treatment = MotionTabTreatment.Soft,
        selectionBackdrop = backdrop,
        fontSize = BrowseTabSize,
        selectedFontWeight = FontWeight.SemiBold,
        unselectedFontWeight = FontWeight.Normal,
    )
}
