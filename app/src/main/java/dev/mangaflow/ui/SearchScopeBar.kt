package dev.mangaflow.ui

import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import dev.mangaflow.ui.motion.MotionTabContent
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.MenuBook
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
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
    tagsOnly: Boolean = false,
    onTagQuery: ((String) -> Unit)? = null,
    maxChoicesHeight: Dp = 200.dp,
) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val targets = listOf(SearchTarget.TITLE, SearchTarget.AUTHOR, SearchTarget.TAG)
    val labels = listOf("作品", "作者", "标签")
    val icons = listOf(Icons.AutoMirrored.Outlined.MenuBook, Icons.Outlined.PersonOutline, Icons.AutoMirrored.Outlined.Label)
    val selectedPaths = remember(selectedTags) { selectedTags.mapTo(mutableSetOf()) { it.path } }
    var tagsExpanded by rememberSaveable(target, tagsOnly) { mutableStateOf(false) }
    val showChoices = target == SearchTarget.TAG && (tagsExpanded || selectedTags.isEmpty())
    val releaseInput = { keyboard?.hide(); focus.clearFocus(force = true) }
    val chooseTag: (MangaTag) -> Unit = { tag ->
        tagsExpanded = false
        releaseInput()
        onTag(tag)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!tagsOnly) MotionTabs(targets.indexOf(target).coerceAtLeast(0), labels, {
            if (targets[it] == SearchTarget.TAG) releaseInput()
            onTarget(targets[it])
        },
            modifier = Modifier.widthIn(max = 228.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)),
            controlHeight = 36.dp, indicatorHeight = 28.dp, indicatorCornerRadius = 8.dp,
            fullWidthIndicator = true, treatment = MotionTabTreatment.Soft,
            fontSize = 11.sp, itemContent = { index, _, color ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(icons[index], null, Modifier.size(14.dp), tint = color)
                    Text(labels[index], color = color, fontSize = 11.sp, maxLines = 1)
                }
            })
        if (selectedTags.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("已选 ${selectedPaths.size}", fontSize = 11.sp, color = colors.onSurfaceVariant,
                        maxLines = 1)
                    selectedTags.distinctBy { it.path }.forEach { tag ->
                        SearchTagChoice(tag, selected = true, remove = true) { chooseTag(tag) }
                    }
                }
                if (target == SearchTarget.TAG) {
                    val action = remember { MutableInteractionSource() }
                    Row(Modifier.heightIn(min = 36.dp)
                        .clickable(interactionSource = action, indication = null, role = Role.Button,
                            onClickLabel = if (showChoices) "收起标签选择" else "添加筛选标签",
                            onClick = {
                                releaseInput()
                                tagsExpanded = !showChoices
                            })
                        .motionPress(action, pressedScale = .97f)
                        .padding(start = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (showChoices) Icons.Outlined.ExpandLess else Icons.Outlined.Add, null,
                            Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                        Text(if (showChoices) "收起" else "添加标签", fontSize = 11.sp, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
        MotionTabContent(showChoices, Modifier.fillMaxWidth(), contentKey = { it },
            order = { if (it) 1 else 0 }, fillContent = false, soft = true) { visible ->
            if (visible) Column(Modifier.fillMaxWidth().heightIn(max = maxChoicesHeight.coerceAtLeast(0.dp))
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (tagsOnly && onTagQuery != null) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp))
                        .background(colors.onSurface.copy(alpha = .045f)).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Search, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                        BasicTextField(value = query, onValueChange = onTagQuery, singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, color = colors.onSurface),
                            cursorBrush = SolidColor(colors.onSurface),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { releaseInput() }),
                            modifier = Modifier.weight(1f).semantics { contentDescription = "查找可选标签" },
                            decorationBox = { input -> Box {
                                if (query.isEmpty()) Text("查找标签", color = colors.onSurfaceVariant, fontSize = 12.sp)
                                input()
                            } })
                    }
                }
                val matches = remember(availableTags, query) {
                    val term = simplifiedTagQuery(query.trim())
                    availableTags.distinctBy { it.path }.filter {
                        term.isEmpty() || simplifiedTagQuery(it.name).contains(term, ignoreCase = true) ||
                            it.path.contains(term, ignoreCase = true)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("可选标签", fontSize = 11.sp, color = colors.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(matches.size.toString(), fontSize = 10.sp, color = colors.onSurfaceVariant.copy(alpha = .7f))
                }
                if (tagsError != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(tagsError, color = colors.onSurfaceVariant, fontSize = 11.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    MotionIconButton(onClick = onRetryTags, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Refresh, "重试加载标签", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                    }
                }
                when {
                    tagsLoading && matches.isEmpty() -> Row(Modifier.heightIn(min = 44.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        MotionBusyIndicator(Modifier.size(16.dp).semantics { contentDescription = "正在读取标签" },
                            color = colors.onSurfaceVariant, strokeWidth = 1.5.dp)
                    }
                    matches.isEmpty() -> if (tagsError == null) Text(if (query.isBlank()) "暂无可选标签" else "没有匹配的标签",
                        color = colors.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp))
                    else -> FlowRow(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        matches.forEach { tag -> SearchTagChoice(tag, tag.path in selectedPaths) { chooseTag(tag) } }
                    }
                }
            } else Spacer(Modifier.height(0.dp))
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
    val light = colors.surface.luminance() > .5f
    val accent = when ((tag.path.hashCode() and Int.MAX_VALUE) % 4) {
        0 -> if (light) Color(0xFF805C43) else Color(0xFFD9B49E)
        1 -> if (light) Color(0xFF486481) else Color(0xFFAFC4E2)
        2 -> if (light) Color(0xFF75588A) else Color(0xFFC7B0DC)
        else -> if (light) Color(0xFF865B6B) else Color(0xFFD5AFBB)
    }
    Box(Modifier.heightIn(min = 36.dp)
        .toggleable(selected, interactionSource = interaction, indication = null, role = Role.Checkbox,
            onValueChange = { onClick() })
        .motionPress(interaction, pressedScale = .97f)
        .semantics(mergeDescendants = true) { contentDescription = tag.name },
        contentAlignment = Alignment.Center) {
        Row(Modifier.clip(RoundedCornerShape(7.dp))
            .background(accent.copy(alpha = if (selected) .14f else .06f))
            .padding(horizontal = 7.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (remove || selected) Icon(if (remove) Icons.Outlined.Close else Icons.Outlined.Check, null,
                Modifier.size(10.dp), tint = accent)
            Text(tag.name, fontSize = if (remove) 11.sp else 10.sp, lineHeight = 15.sp,
                color = accent,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
