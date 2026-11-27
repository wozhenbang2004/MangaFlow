package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

import dev.mangaflow.platform.*

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mangaflow.storage.ReaderTheme
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.typography.MangaFontScene
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.motionPress
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
    desktopPreview: Boolean = false,
    manga: Manga? = null,
    repository: MangaRepository? = null,
    onSelected: (ReaderTheme) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    if (desktopPreview) {
        val selectedPaper = paperThemes.first { it.first == selected }
        val motion = LocalMotionEnabled.current
        val paper by animateColorAsState(selectedPaper.third,
            if (motion) tween(MangaMotion.Content) else snap(), label = "reader-paper-sample")
        val ink by animateColorAsState(if (selected == ReaderTheme.INK) Color.White else Color(0xFF242426),
            if (motion) tween(MangaMotion.Content) else snap(), label = "reader-ink-sample")
        PreferenceWorkspace(modifier, splitWidth = 502.dp, preview = {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(paper)
                .heightIn(min = 132.dp).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (manga != null && repository != null) MangaCover(manga, repository,
                    modifier = Modifier.width(54.dp).height(78.dp).clip(RoundedCornerShape(4.dp)),
                    scale = ContentScale.Fit)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("阅读底色预览", style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = .72f))
                    Text(manga?.title ?: "翻开下一页", style = MaterialTheme.typography.titleLarge,
                        fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE), color = ink,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(selectedPaper.second, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = .72f))
                }
            }
        }, controls = {
            Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                paperThemes.forEach { (theme, label, swatch) ->
                    val chosen = selected == theme
                    val interaction = remember(theme) { MutableInteractionSource() }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(if (chosen) colors.secondaryContainer else Color.Transparent)
                        .selectable(chosen, role = Role.RadioButton, interactionSource = interaction, indication = null,
                            onClick = { if (!chosen) onSelected(theme) })
                        .motionPress(interaction, pressedScale = .99f)
                        .heightIn(min = 40.dp).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(18.dp).background(colors.onSurface.copy(alpha = .15f), RoundedCornerShape(5.dp))
                            .padding(1.dp).background(swatch, RoundedCornerShape(4.dp)))
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            color = if (chosen) colors.onSecondaryContainer else colors.onSurface)
                        if (chosen) Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = colors.primary)
                    }
                }
            }
        })
        return
    }
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
