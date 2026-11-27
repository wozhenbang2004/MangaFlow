package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

import dev.mangaflow.platform.*

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.data.MangaTag
import dev.mangaflow.data.MangaTagKind
import dev.mangaflow.data.SearchTarget
import dev.mangaflow.data.isCopyConventionTag
import dev.mangaflow.data.sourceDisplayName
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTextButton
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
    showScope: Boolean = true,
    translationGroupAvailable: Boolean = true,
    choicesExpanded: Boolean? = null,
    onChoicesExpandedChange: ((Boolean) -> Unit)? = null,
    choicesFooter: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val targets = listOf(SearchTarget.TITLE, SearchTarget.AUTHOR, SearchTarget.TAG) +
        if (translationGroupAvailable) listOf(SearchTarget.TRANSLATION_GROUP) else emptyList()
    val labels = listOf("作品", "作者", "标签") +
        if (translationGroupAvailable) listOf("汉化组") else emptyList()
    val icons = listOf(Icons.AutoMirrored.Outlined.MenuBook, Icons.Outlined.PersonOutline,
        Icons.AutoMirrored.Outlined.Label) +
        if (translationGroupAvailable) listOf(Icons.Outlined.Translate) else emptyList()
    val selectedPaths = remember(selectedTags) { selectedTags.mapTo(mutableSetOf()) { it.path } }
    val tagChoices = remember(availableTags) {
        availableTags.distinctBy { it.path }.map { tag ->
            tag to (listOf(tag.name) + tag.aliases).map(::simplifiedTagQuery)
        }
    }
    var tagsExpanded by rememberSaveable(target, tagsOnly) { mutableStateOf(false) }
    var eventTab by rememberSaveable(target, tagsOnly) { mutableStateOf(false) }
    var showTagSources by rememberSaveable(target, tagsOnly) { mutableStateOf(false) }
    var choseTabForQuery by remember(target, tagsOnly, query) { mutableStateOf(false) }
    val themeScroll = rememberScrollState()
    val eventScroll = rememberScrollState()
    val showChoices = target == SearchTarget.TAG && (choicesExpanded ?: (tagsExpanded || selectedTags.isEmpty()))
    fun expandChoices(expanded: Boolean) {
        tagsExpanded = expanded
        onChoicesExpandedChange?.invoke(expanded)
    }
    val releaseInput = { keyboard?.hide(); focus.clearFocus(force = true) }
    val chooseTag: (MangaTag) -> Unit = { tag ->
        expandChoices(false)
        releaseInput()
        onTag(tag)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!tagsOnly && showScope) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MotionTabs(targets.indexOf(target).coerceAtLeast(0), labels, {
            if (targets[it] == SearchTarget.TAG) releaseInput()
            onTarget(targets[it])
        },
            modifier = Modifier.width(44.dp * targets.size),
            controlHeight = 44.dp, indicatorHeight = 34.dp, indicatorCornerRadius = 10.dp,
            fullWidthIndicator = true, treatment = MotionTabTreatment.Soft,
            fontSize = 11.sp, itemContent = { index, _, color ->
                Icon(icons[index], if (targets[index] == SearchTarget.TRANSLATION_GROUP)
                    "汉化组，输入汉化组名称搜索拷贝作品" else labels[index], Modifier.size(17.dp), tint = color)
            })

        }
        if (target != SearchTarget.TITLE && showScope) {
            Text(if (target == SearchTarget.TRANSLATION_GROUP) "汉化组搜索使用拷贝来源。"
                else "部分来源的作者／标签检索结果可能不完整。",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2)
        }
        if (selectedTags.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier.semantics(mergeDescendants = true) {
                            contentDescription = "已选 ${selectedPaths.size} 个标签"
                        }) {
                        Icon(Icons.Outlined.Check, null, Modifier.size(12.dp), tint = colors.onSurfaceVariant)
                        Text(selectedPaths.size.toString(), style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant)
                    }
                    selectedTags.distinctBy { it.path }.forEach { tag ->
                        SearchTagChoice(tag, selected = true, remove = true) { chooseTag(tag) }
                    }
                }
                if (target == SearchTarget.TAG) {
                    val action = remember { MutableInteractionSource() }
                    Row(Modifier.size(44.dp)
                        .clickable(interactionSource = action, indication = null, role = Role.Button,
                            onClickLabel = if (showChoices) "收起标签选择" else "添加筛选标签",
                            onClick = {
                                releaseInput()
                                expandChoices(!showChoices)
                            })
                        .motionPress(action, pressedScale = .97f)
                        .semantics { contentDescription = if (showChoices) "收起标签选择" else "添加筛选标签" },
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (showChoices) Icons.Outlined.ExpandLess else Icons.Outlined.Add, null,
                            Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                    }
                }
            }
        }
        MotionTabContent(showChoices, Modifier.fillMaxWidth(), contentKey = { it },
            order = { if (it) 1 else 0 }, fillContent = false, soft = true) { visible ->
            if (visible) Column(Modifier.fillMaxWidth().heightIn(max = maxChoicesHeight.coerceAtLeast(0.dp)),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (tagsOnly && onTagQuery != null) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
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
                val (themes, events) = remember(tagChoices, query) {
                    val term = simplifiedTagQuery(query.trim())
                    val resolvedPath = dev.mangaflow.data.copyTagForName(query, availableTags)?.path
                    val matches = tagChoices.filter { (tag, names) ->
                        term.isEmpty() || names.any { it.contains(term, ignoreCase = true) } ||
                            tag.path.contains(term, ignoreCase = true) || tag.path == resolvedPath
                    }.map { it.first }
                    val (conventions, genres) = matches.partition(::searchConventionTag)
                    genres to conventions.sortedWith(compareByDescending<MangaTag> { copyEventNumber(it) }
                        .thenBy { it.path })
                }
                val hasEvents = remember(tagChoices) { tagChoices.any { searchConventionTag(it.first) } }
                val autoEvent = when {
                    query.isBlank() || choseTabForQuery -> eventTab
                    themes.isEmpty() && events.isNotEmpty() -> true
                    events.isEmpty() && themes.isNotEmpty() -> false
                    else -> eventTab
                }
                val showEvents = hasEvents && autoEvent
                val choices = if (showEvents) events else themes
                val origins = remember(choices, translationGroupAvailable) {
                    if (translationGroupAvailable) choices.flatMap { it.providerPaths.keys }.distinct() else emptyList()
                }
                if (hasEvents || origins.isNotEmpty()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (hasEvents) MotionTabs(if (showEvents) 1 else 0, listOf("题材", "展会"),
                        onSelect = {
                            choseTabForQuery = true
                            eventTab = it == 1
                        }, modifier = Modifier.width(154.dp), controlHeight = 44.dp, indicatorHeight = 34.dp,
                        treatment = MotionTabTreatment.Soft, fullWidthIndicator = true, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    if (origins.isNotEmpty()) MotionTextButton(onClick = { showTagSources = !showTagSources }) {
                        Text(if (showTagSources) "收起来源" else "标签来源", fontSize = 11.sp)
                    }
                }
                val lookupScroll = remember(query, showEvents) { ScrollState(0) }
                val scroll = if (query.isNotBlank()) lookupScroll else if (showEvents) eventScroll else themeScroll
                Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (showTagSources && origins.isNotEmpty()) {
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            origins.forEach { source ->
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    ReviewSourceMark(source, size = 12.dp)
                                    Text(sourceDisplayName(source), style = MaterialTheme.typography.labelSmall,
                                        color = colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    when {
                        tagsLoading && choices.isEmpty() -> Row(Modifier.heightIn(min = 44.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            MotionBusyIndicator(Modifier.size(16.dp).semantics { contentDescription = "正在读取标签" },
                                color = colors.onSurfaceVariant, strokeWidth = 1.5.dp)
                        }
                        choices.isEmpty() -> if (tagsError == null) Text(
                            if (query.isBlank()) "暂无可选标签" else if (showEvents) "没有匹配的展会" else "没有匹配的题材",
                            color = colors.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp))
                        else -> FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            choices.forEach { tag ->
                                SearchTagChoice(tag, tag.path in selectedPaths,
                                    includeProvenance = translationGroupAvailable, showOrigins = showTagSources) { chooseTag(tag) }
                            }
                        }
                    }
                    if (tagsError != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(tagsError, color = colors.onSurfaceVariant, fontSize = 11.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        MotionIconButton(onClick = onRetryTags, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Outlined.Refresh, "重试加载标签", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                        }
                    }
                    choicesFooter?.invoke()
                }
            } else Spacer(Modifier.height(0.dp))
        }
    }
}

private val CopyEventName = Regex("C\\s*(\\d{1,4})", RegexOption.IGNORE_CASE)
private fun copyEventNumber(tag: MangaTag): Int? =
    CopyEventName.matchEntire(tag.name.trim())?.groupValues?.get(1)?.toIntOrNull()

private fun searchConventionTag(tag: MangaTag): Boolean =
    tag.kind == MangaTagKind.CONVENTION ||
        tag.kind == MangaTagKind.TAG && tag.providerPaths.isEmpty() && isCopyConventionTag(tag.path)

// Search aliases for the traditional characters in the source's archived theme names.
// Only display-name matching changes; selected values always retain the original path.
private fun simplifiedTagQuery(value: String): String = value.map { character ->
    when (character) {
        '愛' -> '爱'; '歡' -> '欢'; '樂' -> '乐'; '險' -> '险'; '東' -> '东'
        '鬥' -> '斗'; '長' -> '长'; '條' -> '条'; '後' -> '后'; '宮' -> '宫'; '無' -> '无'
        else -> character
    }
}.joinToString("")

/** These are past queries, not a translator catalogue or a multi-select theme filter. */
@Composable
internal fun RecentCopyGroupSearches(terms: List<String>, query: String, onChoose: ((String) -> Unit)?, onClear: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    if (onChoose == null || terms.isEmpty()) {
        Text(if (onChoose == null) "启用拷贝来源后可搜索汉化组。"
            else "输入组名，提交搜索或打开匹配作品后，即可再次点选。",
            Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        return
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("最近搜过", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant)
            if (onClear != null) MotionIconButton(onClick = onClear, modifier = Modifier.size(44.dp), tooltip = "清除最近搜过的汉化组") {
                Icon(Icons.Outlined.DeleteOutline, "清除最近搜过的汉化组", Modifier.size(16.dp), tint = colors.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            terms.forEach { term ->
                val current = term == query.trim()
                MotionTextButton(onClick = { keyboard?.hide(); focus.clearFocus(force = true); onChoose(term) },
                    modifier = Modifier.widthIn(max = 200.dp).semantics { selected = current }, shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = if (current) colors.primary.copy(alpha = .10f) else colors.onSurface.copy(alpha = .025f),
                        contentColor = if (current) colors.primary else colors.onSurface), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)) {
                    Text(term, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun SearchTagChoice(tag: MangaTag, selected: Boolean, remove: Boolean = false,
    includeProvenance: Boolean = false, showOrigins: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember(tag.path) { MutableInteractionSource() }
    val origins = if (includeProvenance && !remove) tag.providerPaths.keys else emptySet()
    val copyCount = tag.count?.takeIf { it >= 0 && "copy" in origins }
    Box(Modifier.heightIn(min = 44.dp)
        .toggleable(selected, interactionSource = interaction, indication = null, role = Role.Checkbox,
            onValueChange = { onClick() })
        .motionPress(interaction, pressedScale = .97f)
        .semantics(mergeDescendants = true) {
            contentDescription = listOfNotNull(if (remove) "移除${tag.name}标签" else tag.name,
                origins.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = "支持来源 ") { sourceDisplayName(it) },
                copyCount?.let { "拷贝统计 $it 部" }).joinToString("，")
        },
        contentAlignment = Alignment.Center) {
        Row(Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.primary.copy(alpha = .10f) else colors.onSurface.copy(alpha = .025f))
            .padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (remove || selected) Icon(if (remove) Icons.Outlined.Close else Icons.Outlined.Check, null,
                Modifier.size(12.dp), tint = colors.primary)
            Text(tag.name, fontSize = 13.sp, lineHeight = 18.sp,
                color = if (selected) colors.primary else colors.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (showOrigins) origins.filterNot { it == "copy" && copyCount != null }.forEach { origin ->
                ReviewSourceMark(origin, size = 12.dp)
            }
            copyCount?.let { count ->
                ReviewSourceMark("copy", size = 12.dp)
                Text(count.toString(), style = mangaMetricValueStyle(12.sp, 17.sp),
                    color = colors.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}
