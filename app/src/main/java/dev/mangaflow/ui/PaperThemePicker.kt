package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.mangaflow.storage.ReaderTheme
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment

private val paperThemes = listOf(
    Triple(ReaderTheme.PAPER, "纸白", Color(0xFFF7F6F2)),
    Triple(ReaderTheme.SEPIA, "暖纸", Color(0xFFECE4D5)),
    Triple(ReaderTheme.INK, "墨黑", Color(0xFF101012)),
)

/** Ovio's shared selector, with each stationary slot previewing the actual reader paper. */
@Composable
internal fun PaperThemePicker(
    selected: ReaderTheme,
    modifier: Modifier = Modifier,
    onSelected: (ReaderTheme) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val previewHeight = (76.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)).coerceAtLeast(84.dp)
    MotionTabs(
        selected = paperThemes.indexOfFirst { it.first == selected },
        labels = paperThemes.map { it.second },
        onSelect = { index ->
            val next = paperThemes[index].first
            if (next != selected) onSelected(next)
        },
        modifier = modifier.semantics { contentDescription = "阅读底色" },
        role = Role.RadioButton,
        track = Color.Transparent,
        indicator = colors.onSurface.copy(alpha = .065f),
        fullWidthIndicator = true,
        treatment = MotionTabTreatment.Soft,
        controlHeight = previewHeight,
        fontSize = MaterialTheme.typography.bodyMedium.fontSize,
        itemContent = { index, chosen, _ ->
            val (theme, label, paper) = paperThemes[index]
            val ink = if (theme == ReaderTheme.INK) Color(0xFFF7F6F2) else Color(0xFF242426)
            Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp)).background(paper)) {
                    Column(Modifier.align(Alignment.Center).padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.fillMaxWidth(.8f).height(2.dp).background(ink.copy(alpha = .12f)))
                        Box(Modifier.fillMaxWidth().height(2.dp).background(ink.copy(alpha = .08f)))
                        Box(Modifier.fillMaxWidth(.6f).height(2.dp).background(ink.copy(alpha = .08f)))
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = colors.onSurface,
                        textAlign = TextAlign.Center,
                        fontWeight = if (chosen) FontWeight.Medium else FontWeight.Normal)
                    if (chosen) Icon(Icons.Outlined.Check, contentDescription = null,
                        tint = colors.onSurface, modifier = Modifier.padding(start = 4.dp).size(13.dp))
                }
            }
        },
    )
}
