package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.data.MangaTag
import dev.mangaflow.data.SearchTarget
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.motionPress

/** Existing Ovio scope tabs and compact source-choice patterns, using actual source tags only. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchScopeBar(
    target: SearchTarget,
    query: String,
    selectedTags: List<MangaTag>,
    availableTags: List<MangaTag>,
    tagsLoading: Boolean,
    tagsError: String?,
    onTarget: (SearchTarget) -> Unit,
    onTag: (MangaTag) -> Unit,
    onRetryTags: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val targets = listOf(SearchTarget.TITLE, SearchTarget.AUTHOR, SearchTarget.TAG)
    val labels = listOf("作品", "作者", "标签")
    val icons = listOf(Icons.Outlined.MenuBook, Icons.Outlined.PersonOutline, Icons.Outlined.Label)
    val selectedPaths = remember(selectedTags) { selectedTags.mapTo(mutableSetOf()) { it.path } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MotionTabs(targets.indexOf(target).coerceAtLeast(0), labels, { onTarget(targets[it]) },
            controlHeight = 44.dp, fullWidthIndicator = true, treatment = MotionTabTreatment.Soft,
            fontSize = 12.sp, itemContent = { index, _, color ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(icons[index], null, Modifier.size(16.dp), tint = color)
                    Text(labels[index], color = color, fontSize = 12.sp, maxLines = 1)
                }
            })
        if (selectedTags.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (selectedTags.size > 1) Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(Icons.Outlined.FilterAlt, null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                    Text("同时匹配", fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1)
                }
                selectedTags.distinctBy { it.path }.forEach { tag ->
                    SearchTagChoice(tag, selected = true, remove = true) { onTag(tag) }
                }
            }
        }
        if (target == SearchTarget.TAG) {
            val matches = remember(availableTags, query) {
                val term = simplifiedTagQuery(query.trim())
                availableTags.distinctBy { it.path }.filter {
                    term.isEmpty() || simplifiedTagQuery(it.name).contains(term, ignoreCase = true) || it.path.contains(term, ignoreCase = true)
                }
            }
            if (tagsError != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(tagsError, color = colors.onSurfaceVariant, fontSize = 11.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 6.dp))
                    MotionIconButton(onClick = onRetryTags, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Refresh, "重试加载标签", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                    }
            }
            when {
                tagsLoading && matches.isEmpty() -> Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    MotionBusyIndicator(Modifier.size(16.dp).semantics { contentDescription = "正在读取标签" },
                        color = colors.onSurfaceVariant, strokeWidth = 1.5.dp)
                }
                matches.isEmpty() -> if (tagsError == null) Text(if (query.isBlank()) "暂无可选标签" else "没有匹配的标签",
                    color = colors.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
                else -> FlowRow(Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    matches.forEach { tag -> SearchTagChoice(tag, tag.path in selectedPaths) { onTag(tag) } }
                }
            }
        }
    }
}

// Search aliases for the traditional characters in the source's archived theme names.
// Only display-name matching changes; selected values always retain the original path.
private fun simplifiedTagQuery(value: String): String = value.map { character ->
    when (character) {
        '愛' -> '爱'; '歡' -> '欢'; '樂' -> '乐'; '險' -> '险'; '東' -> '东'
        '鬥' -> '斗'; '長' -> '长'; '條' -> '条'; '後' -> '后'; '宮' -> '宫'; '無' -> '无'
        else -> character
    }
}.joinToString("")

@Composable
private fun SearchTagChoice(tag: MangaTag, selected: Boolean, remove: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember(tag.path) { MutableInteractionSource() }
    Row(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
        .background(colors.onSurface.copy(alpha = if (selected) .09f else .035f))
        .toggleable(selected, interactionSource = interaction, indication = null, role = Role.Checkbox,
            onValueChange = { onClick() })
        .motionPress(interaction, pressedScale = .97f)
        .semantics(mergeDescendants = true) { contentDescription = tag.name }
        .padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (remove || selected) Icon(if (remove) Icons.Outlined.Close else Icons.Outlined.Check, null,
            Modifier.size(12.dp), tint = colors.onSurfaceVariant)
        Text(tag.name, fontSize = 11.sp, color = if (selected) colors.onSurface else colors.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
