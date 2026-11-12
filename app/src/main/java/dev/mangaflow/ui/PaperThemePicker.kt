package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
    val previewHeight = (56.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)).coerceAtLeast(72.dp)
    MotionTabs(
        selected = paperThemes.indexOfFirst { it.first == selected },
        labels = paperThemes.map { it.second },
        onSelect = { index ->
            val next = paperThemes[index].first
            if (next != selected) onSelected(next)
        },
        modifier = modifier.semantics { contentDescription = "阅读底色" },
        role = Role.RadioButton,
        track = colors.onSurface.copy(alpha = .04f),
        indicator = colors.onSurface.copy(alpha = .11f),
        fullWidthIndicator = true,
        controlHeight = previewHeight,
        fontSize = MaterialTheme.typography.bodyMedium.fontSize,
        itemContent = { index, chosen, _ ->
            val (theme, label, paper) = paperThemes[index]
            val ink = if (theme == ReaderTheme.INK) Color(0xFFF7F6F2) else Color(0xFF242426)
            Box(Modifier.fillMaxSize().padding(7.dp).clip(RoundedCornerShape(10.dp)).background(paper)) {
                Text(label, modifier = Modifier.align(Alignment.Center).padding(horizontal = 6.dp),
                    style = MaterialTheme.typography.bodyMedium, color = ink, textAlign = TextAlign.Center,
                    fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal)
                if (chosen) Icon(Icons.Outlined.Check, contentDescription = null,
                    tint = ink.copy(alpha = .8f), modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(14.dp))
            }
        },
    )
}
