package dev.mangaflow.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import dev.mangaflow.storage.GlassSkin
import dev.mangaflow.ui.motion.MotionTabs

private val glassSkinChoices = listOf(GlassSkin.FROSTED to "毛玻璃", GlassSkin.LIQUID to "液态玻璃")

@Composable
internal fun GlassSkinPicker(
    selected: GlassSkin,
    onSelected: (GlassSkin) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    MotionTabs(
        selected = glassSkinChoices.indexOfFirst { it.first == selected },
        labels = glassSkinChoices.map { it.second },
        onSelect = { index ->
            val next = glassSkinChoices[index].first
            if (next != selected) onSelected(next)
        },
        modifier = modifier.semantics { contentDescription = "玻璃皮肤" },
        role = Role.RadioButton,
        track = colors.surfaceContainerHigh,
        indicator = colors.surface,
        fontSize = MaterialTheme.typography.labelLarge.fontSize,
        selectedFontWeight = FontWeight.SemiBold,
        unselectedFontWeight = FontWeight.Normal,
    )
}
