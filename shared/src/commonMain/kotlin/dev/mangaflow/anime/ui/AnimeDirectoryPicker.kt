package dev.mangaflow.anime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.AnimeController
import dev.mangaflow.anime.sources.AnimeBrowseSource
import dev.mangaflow.anime.sources.BUNDLED_ANIME_SOURCE_IDS
import dev.mangaflow.anime.sources.lanerc.LanercSourceListing
import dev.mangaflow.platform.normalizeNfkc
import dev.mangaflow.ui.CompactGlassPopover
import dev.mangaflow.ui.CompactMenuAction
import dev.mangaflow.ui.LocalMangaWindow
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionPress

private enum class DirectoryGroup(val title: String, val variant: String) {
    BUILT_IN("内置片源", "内置版"), REFERENCE("资料目录", "资料"), EXTENSION("扩展片源", "扩展版"),
}

private data class DirectoryChoice(val source: AnimeBrowseSource, val group: DirectoryGroup, val variant: String?,
    val searchText: String)

private fun directoryChoices(sources: List<AnimeBrowseSource>, builtIns: Set<String>,
    extensions: List<LanercSourceListing>): List<DirectoryChoice> {
    val listings = extensions.associateBy { it.id }
    val distinctSources = sources.distinctBy { it.id to it.revision }
    fun group(source: AnimeBrowseSource) = when {
        !source.videoSource -> DirectoryGroup.REFERENCE
        source.id in builtIns || source.id in BUNDLED_ANIME_SOURCE_IDS -> DirectoryGroup.BUILT_IN
        else -> DirectoryGroup.EXTENSION
    }
    fun family(source: AnimeBrowseSource) = listings[source.id]?.bundledAlternativeId ?: source.id
    val byFamily = distinctSources.groupBy(::family)
    val byName = distinctSources.groupBy { normalizeNfkc(it.name).lowercase() }
    return distinctSources.map { source ->
        val kind = group(source)
        val familyMembers = byFamily.getValue(family(source))
        val sameName = byName.getValue(normalizeNfkc(source.name).lowercase())
        val sameGroup = sameName.filter { group(it) == kind }
        val variant = when {
            sameGroup.count { it.id == source.id } > 1 -> source.id + " · " + source.revision
            sameGroup.size > 1 -> source.id
            sameName.size > 1 || familyMembers.size > 1 -> kind.variant
            else -> null
        }
        // Use known alternative names for lookup. Selection always uses the actual row's ID and revision.
        val terms = familyMembers.flatMap { listOf(it.name) + listings[it.id]?.aliases.orEmpty() } +
            listOf(source.id, kind.title, kind.variant)
        DirectoryChoice(source, kind, variant, normalizeNfkc(terms.joinToString(" ")).lowercase())
    }
}

@Composable
internal fun AnimeDirectoryPicker(controller: AnimeController, sources: List<AnimeBrowseSource>,
    source: AnimeBrowseSource?, recentIds: List<String>, backdrop: HazeState, active: Boolean,
    modifier: Modifier = Modifier, onPresenceChange: (Boolean) -> Unit) {
    var quickOpen by remember { mutableStateOf(false) }
    var quickPresent by remember { mutableStateOf(false) }
    var allOpen by rememberSaveable { mutableStateOf(false) }
    val visible = quickOpen || quickPresent || allOpen
    val reportPresence by rememberUpdatedState(onPresenceChange)
    SideEffect { reportPresence(visible) }
    DisposableEffect(Unit) { onDispose { reportPresence(false) } }
    LaunchedEffect(active) { if (!active) { quickOpen = false; allOpen = false } }

    val extensions by controller.extensions.state.collectAsStateWithLifecycle()
    val choices = remember(sources, extensions, controller) {
        directoryChoices(sources, controller.builtInVideoSources.keys, extensions)
    }
    val current = source?.let { shown ->
        choices.firstOrNull { it.source.id == shown.id && it.source.revision == shown.revision }
    }
    val pick: (DirectoryChoice) -> Unit = { entry ->
        controller.sourceBrowse.selectSource(entry.source.id, entry.source.revision)
    }
    Box(modifier) {
        MotionTextButton(onClick = { if (!visible) quickOpen = true }, enabled = active,
            modifier = Modifier.widthIn(max = 300.dp).heightIn(min = 44.dp),
            contentPadding = PaddingValues(horizontal = 10.dp)) {
            if (source != null) {
                AnimeSourceMark(source.id, source.name, size = 18.dp)
                Spacer(Modifier.width(7.dp))
            }
            Column(Modifier.weight(1f, fill = false)) {
                Text(source?.name ?: "选择目录", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val scope = current?.variant ?: current?.group?.title
                if (scope != null) Text(scope, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.ExpandMore, "切换目录", Modifier.padding(start = 4.dp).size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CompactGlassPopover(quickOpen && active, backdrop, onDismiss = { quickOpen = false },
            onPresenceChange = { quickPresent = it }, maximumWidth = 272.dp, maximumHeight = 320.dp,
            alignToStart = true) {
            if (current != null) DirectoryRow(current, selected = true,
                detail = current.variant ?: current.group.title, onClick = { quickOpen = false })
            val others = choices.filterNot { it == current }
            val recent = recentIds.mapNotNull { id -> others.firstOrNull { it.source.id == id } }.take(2)
            val shortcuts = recent.ifEmpty {
                others.sortedBy { it.group == current?.group }.distinctBy { it.group }.take(2)
            }
            if (shortcuts.isNotEmpty()) {
                DirectoryHeading(if (recent.isEmpty()) "其他目录" else "最近浏览")
                shortcuts.forEach { entry ->
                    DirectoryRow(entry, selected = false, detail = entry.variant ?: entry.group.title,
                        onClick = { pick(entry); quickOpen = false })
                }
            }
            CompactMenuAction(Icons.Outlined.Search, "全部目录（" + choices.size + "）") {
                quickOpen = false
                allOpen = true
            }
        }
    }
    if (allOpen && active && !quickPresent) {
        DirectorySheet(choices, current, backdrop, onSelect = pick, onDismiss = { allOpen = false })
    }
}

@Composable
private fun DirectorySheet(choices: List<DirectoryChoice>, current: DirectoryChoice?, backdrop: HazeState,
    onSelect: (DirectoryChoice) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val groups = remember(choices, query) {
        val terms = normalizeNfkc(query).trim().lowercase().split(' ').filter(String::isNotBlank)
        choices.filter { entry -> terms.all { it in entry.searchText } }.groupBy { it.group }
    }
    val list = rememberLazyListState()
    LaunchedEffect(query) { if (list.firstVisibleItemIndex != 0 || list.firstVisibleItemScrollOffset != 0) list.scrollToItem(0) }
    MotionBottomSheet(onDismissRequest = onDismiss, backdrop = backdrop) { dismiss ->
        val window = LocalMangaWindow.current
        val maxHeight = minOf(window.height * (if (window.shortHeight) .82f else .67f), 520.dp)
        Column(Modifier.fillMaxWidth().imePadding().heightIn(max = maxHeight)
            .semantics { paneTitle = "全部片源目录" }) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DirectorySearch(query, onChange = { query = it }, modifier = Modifier.weight(1f))
                MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp), tooltip = "关闭目录选择") {
                    Icon(Icons.Outlined.Close, "关闭目录选择", Modifier.size(18.dp))
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).selectableGroup(), state = list,
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 10.dp)) {
                if (groups.isEmpty()) item(key = "empty") {
                    AnimeQuietLabel("没有匹配的目录", Modifier.padding(12.dp))
                }
                DirectoryGroup.entries.forEach { group ->
                    val matches = groups[group].orEmpty()
                    if (matches.isNotEmpty()) {
                        item(key = "heading-" + group.name) { DirectoryHeading(group.title) }
                        items(matches, key = { it.source.id + ":" + it.source.revision }) { entry ->
                            DirectoryRow(entry, selected = current == entry, detail = entry.variant) {
                                onSelect(entry)
                                dismiss()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectoryHeading(label: String) {
    Text(label, Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp).semantics { heading() },
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun DirectoryRow(choice: DirectoryChoice, selected: Boolean, detail: String?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember(choice.source.id, choice.source.revision) { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(if (selected) colors.primaryContainer else Color.Transparent)
        .selectable(selected, interactionSource = interaction, indication = null, role = Role.RadioButton,
            onClick = onClick)
        .motionPress(interaction, pressedScale = .98f).heightIn(min = 46.dp)
        .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
            AnimeSourceMark(choice.source.id, choice.source.name, size = 18.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(choice.source.name, style = MaterialTheme.typography.titleMedium,
                color = if (selected) colors.onPrimaryContainer else colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp), tint = colors.onPrimaryContainer)
    }
}

@Composable
private fun DirectorySearch(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    Row(modifier.heightIn(min = 44.dp).background(colors.surfaceContainerLow, RoundedCornerShape(14.dp))
        .padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Search, null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
        BasicTextField(query, onChange, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.onSurface), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            modifier = Modifier.weight(1f).heightIn(min = 44.dp).padding(horizontal = 8.dp)
                .semantics { contentDescription = "搜索片源目录" },
            decorationBox = { field -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text("搜索片源目录", style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                field()
            } })
        if (query.isNotEmpty()) MotionIconButton(onClick = { onChange("") }, modifier = Modifier.size(44.dp), tooltip = "清除搜索") {
            Icon(Icons.Outlined.Close, "清除搜索", Modifier.size(15.dp))
        }
    }
}
