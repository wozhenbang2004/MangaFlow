package dev.mangaflow.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.SearchTarget
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionNavigationColumn
import dev.mangaflow.ui.motion.motionPress

internal data class DesktopDestination(val title: String, val icon: ImageVector, val groupHeading: String? = null)

internal val DesktopNavigationRailWidth = 64.dp

internal fun desktopNavigationLabelWidth(fontScale: Float): Dp =
    (204.dp + 24.dp * (fontScale - 1f)).coerceIn(204.dp, 240.dp)

/** Keep enough of the actual workspace for a dock, and protect the existing desktop
 * detail/management layout before disclosing labels. A user's compact choice still wins. */
internal fun MangaWindowSize.canDockDesktopNavigation(): Boolean = width - DesktopNavigationRailWidth >= 460.dp

internal fun MangaWindowSize.canExpandDesktopNavigation(): Boolean =
    width - desktopNavigationLabelWidth(fontScale) >= maxOf(900.dp, 840.dp * fontScale.coerceAtMost(1.5f))

/** Headers occupy space in the one motion column, but never own a route or a tap target. */
internal sealed interface SidebarNavigationRow {
    data class Heading(val title: String) : SidebarNavigationRow
    data class Destination(val index: Int) : SidebarNavigationRow
}

internal fun sidebarNavigationRows(headingsByDestination: List<String?>): List<SidebarNavigationRow> = buildList {
    headingsByDestination.forEachIndexed { index, heading ->
        if (heading != null) add(SidebarNavigationRow.Heading(heading))
        add(SidebarNavigationRow.Destination(index))
    }
}

@Composable
internal fun SidebarGroupLabel(title: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    if (compact) Box(modifier.fillMaxWidth().height(10.dp).semantics {
        heading()
        contentDescription = title
    }, contentAlignment = Alignment.Center) {
        Spacer(Modifier.width(18.dp).height(1.dp).background(ink.copy(alpha = .28f)))
    } else Text(title, modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 7.dp, bottom = 1.dp)
        .semantics { heading() }, style = MaterialTheme.typography.labelMedium,
        color = ink, fontWeight = FontWeight.Medium)
}

private fun Modifier.revealDesktopNavigationLabel(progress: State<Float>): Modifier = graphicsLayer {
    alpha = progress.value.coerceIn(0f, 1f)
    translationX = -4.dp.toPx() * (1f - alpha)
}

/** Keep the same focus/hit target and icon origin; only the label slot is disclosed. */
@Composable
private fun DesktopNavigationAction(
    title: String,
    icon: ImageVector,
    expanded: Boolean,
    labels: State<Float>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    current: Boolean? = null,
    shortcut: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val ink = MaterialTheme.colorScheme.onSurface
    val hint = listOfNotNull(title, shortcut).joinToString(" · ")
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .clickable(role = Role.Button, interactionSource = interaction, indication = null, onClick = onClick)
        .motionPress(interaction).semantics {
            contentDescription = hint
            if (current != null) selected = current
        }, contentAlignment = Alignment.CenterStart) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = ink.copy(alpha = if (current == true) 1f else .76f))
            }
            if (expanded) {
                Text(title, Modifier.weight(1f).revealDesktopNavigationLabel(labels).clearAndSetSemantics {},
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (current == true) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (shortcut != null) Text(shortcut,
                    Modifier.padding(horizontal = 8.dp).revealDesktopNavigationLabel(labels).clearAndSetSemantics {},
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1)
            }
        }
        DesktopIconTooltip(hint.takeUnless { expanded }, interaction, enabled = true)
    }
}

/** Recompose the current glass drawer as persistent navigation; content owns the remaining window. */
@Composable
internal fun DesktopNavigationWorkspace(
    visible: Boolean,
    expanded: Boolean,
    canExpand: Boolean,
    sidebarWidth: Dp,
    destinations: List<DesktopDestination>,
    selectedIndex: Int,
    version: String,
    backdrop: HazeState,
    workspaceBackdrop: HazeState,
    wallpaper: @Composable () -> Unit,
    onToggle: () -> Unit,
    onNavigate: (Int) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    profileControl: @Composable (compact: Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    // Global dialogs see the assembled window. The rail samples the painted scene
    // beneath it, never the rail itself or a snapshot of the adjacent content pane.
    Box(modifier.fillMaxSize().then(if (visible) Modifier.mangaHazeSource(backdrop) else Modifier)) {
        if (platformKind != PlatformKind.DESKTOP) {
            content()
        } else {
            val motion = LocalMotionEnabled.current
            val labels = animateFloatAsState(if (expanded) 1f else 0f,
                if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else snap(),
                label = "desktop-navigation-labels")
            if (visible) {
                Box(Modifier.matchParentSize().mangaHazeSource(workspaceBackdrop, zIndex = -1f)
                    .background(MaterialTheme.colorScheme.background)) { wallpaper() }
            }
            Row(Modifier.fillMaxSize()) {
                // Commit the workspace width once. Label motion is draw-only; a sidebar click
                // no longer remeasures the gallery and its breakpoints through a spring.
                if (visible) LiquidGlassSurface(workspaceBackdrop, Modifier.width(sidebarWidth)
                    .fillMaxHeight().padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                    cornerRadius = 20.dp, role = GlassSurfaceRole.DRAWER, elevation = 0.dp) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 8.dp)) {
                        DesktopNavigationAction("搜索", Icons.Outlined.Search, expanded, labels, onSearch,
                            modifier = Modifier.height(40.dp), shortcut = "Ctrl+K")
                        val scroll = rememberScrollState()
                        val rows = remember(destinations) { sidebarNavigationRows(destinations.map(DesktopDestination::groupHeading)) }
                        val selectedRow = rows.indexOf(SidebarNavigationRow.Destination(selectedIndex))
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 3.dp)) {
                                profileControl(!expanded)
                                Spacer(Modifier.height(12.dp))
                                MotionNavigationColumn(selected = selectedRow, itemCount = rows.size,
                                    selectionVisible = selectedRow >= 0) { index, itemModifier ->
                                    when (val row = rows[index]) {
                                        is SidebarNavigationRow.Heading -> SidebarGroupLabel(row.title, itemModifier, compact = !expanded)
                                        is SidebarNavigationRow.Destination -> {
                                            val destination = destinations[row.index]
                                            DesktopNavigationAction(destination.title, destination.icon, expanded, labels,
                                                onClick = { onNavigate(row.index) }, modifier = itemModifier.height(46.dp),
                                                current = selectedIndex == row.index)
                                        }
                                    }
                                }
                            }
                            DesktopVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(5.dp))
                        }
                        // Keep the search anchor at the top in every docked width. The
                        // display preference belongs in the footer, not in the task list.
                        if (canExpand) Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                            val action = if (expanded) "收起导航" else "展开导航"
                            MotionIconButton(onClick = onToggle, modifier = Modifier.size(40.dp), tooltip = action) {
                                Icon(Icons.Outlined.Menu, action, Modifier.size(20.dp))
                            }
                            if (expanded) Text(version, modifier = Modifier.revealDesktopNavigationLabel(labels),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                    MangaAdaptiveLayout(content)
                }
            }
        }
    }
}

/** The beUI-derived search input remains compact; its actual results belong to the workspace. */
@Composable
internal fun DesktopSearchField(
    query: String,
    target: SearchTarget,
    copyOnly: Boolean,
    active: Boolean,
    focusRequest: Int,
    takeFocusRequest: (Int) -> Boolean,
    backdrop: HazeState,
    onOpen: () -> Unit,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onTarget: (SearchTarget) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    showScopeSelector: Boolean = true,
    translationGroupAvailable: Boolean = true,
) {
    val requester = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    var scopeOpen by remember { mutableStateOf(false) }
    val inputInteraction = remember { MutableInteractionSource() }
    val searchLabel = when {
        !showScopeSelector -> "搜索漫画标题"
        target == SearchTarget.TRANSLATION_GROUP -> "搜索汉化组名称"
        else -> "搜索漫画、作者或标签"
    }
    LaunchedEffect(focusRequest, active) {
        if (active && focusRequest > 0) {
            withFrameNanos { }
            if (takeFocusRequest(focusRequest)) requester.requestFocus()
        }
    }
    LaunchedEffect(active, showScopeSelector) { if (!active || !showScopeSelector) scopeOpen = false }
    Box(modifier.height(44.dp)) {
        LiquidGlassSurface(backdrop, Modifier.fillMaxSize(), cornerRadius = 16.dp,
            role = GlassSurfaceRole.NAVIGATION, interactionSource = inputInteraction, softCard = true) {}
        Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            BasicTextField(value = query, onValueChange = { onOpen(); onQuery(it) },
                enabled = active, singleLine = true, interactionSource = inputInteraction,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    scopeOpen = false
                    onOpen()
                    onSubmit()
                    focus.clearFocus(force = true)
                }),
                modifier = Modifier.weight(1f).height(44.dp).padding(horizontal = 10.dp)
                    .focusRequester(requester).onFocusChanged { if (it.isFocused) onOpen() }
                    .semantics { contentDescription = searchLabel },
                decorationBox = { input ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text(searchLabel, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        input()
                    }
                })
            if (query.isNotEmpty() || copyOnly) MotionIconButton(onClick = {
                scopeOpen = false; onClear()
            }, enabled = active, modifier = Modifier.size(32.dp), tooltip = "清空搜索") {
                Icon(Icons.Outlined.Close, "清空搜索", Modifier.size(17.dp))
            }
            if (showScopeSelector) {
                val targetIcon = when (target) {
                    SearchTarget.TITLE -> Icons.AutoMirrored.Outlined.MenuBook
                    SearchTarget.AUTHOR -> Icons.Outlined.PersonOutline
                    SearchTarget.TAG -> Icons.AutoMirrored.Outlined.Label
                    SearchTarget.TRANSLATION_GROUP -> Icons.Outlined.Translate
                }
                val targetLabel = when (target) {
                    SearchTarget.TITLE -> "作品"
                    SearchTarget.AUTHOR -> "作者"
                    SearchTarget.TAG -> "标签"
                    SearchTarget.TRANSLATION_GROUP -> "汉化组"
                }
                Box {
                    MotionIconButton(onClick = { scopeOpen = !scopeOpen }, enabled = active,
                        modifier = Modifier.size(36.dp), tooltip = "搜索范围：" + targetLabel) {
                        Box(Modifier.size(22.dp)) {
                            Icon(targetIcon, "搜索范围：" + targetLabel, Modifier.align(Alignment.Center).size(18.dp))
                            if (copyOnly || target == SearchTarget.TRANSLATION_GROUP)
                                BodySourceMark("copy", "拷贝", Modifier.align(Alignment.BottomEnd).size(8.dp))
                        }
                    }
                    CompactGlassPopover(scopeOpen, backdrop, onDismiss = { scopeOpen = false }, maximumWidth = 200.dp) {
                        listOf(SearchTarget.TITLE to "作品", SearchTarget.AUTHOR to "作者", SearchTarget.TAG to "标签",
                            SearchTarget.TRANSLATION_GROUP to "汉化组")
                            .filter { translationGroupAvailable || it.first != SearchTarget.TRANSLATION_GROUP }
                            .forEach { (value, label) ->
                            CompactMenuAction(if (value == target) Icons.Outlined.Check else when (value) {
                                SearchTarget.TITLE -> Icons.AutoMirrored.Outlined.MenuBook
                                SearchTarget.AUTHOR -> Icons.Outlined.PersonOutline
                                SearchTarget.TAG -> Icons.AutoMirrored.Outlined.Label
                                SearchTarget.TRANSLATION_GROUP -> Icons.Outlined.Translate
                            }, label) {
                                scopeOpen = false; onOpen(); onTarget(value)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DesktopDiscoveryHeader(
    searching: Boolean,
    backdrop: HazeState,
    onLeaveSearch: () -> Unit,
    navigation: @Composable () -> Unit,
    tabs: @Composable (Modifier) -> Unit,
    input: @Composable (Modifier) -> Unit,
    recent: @Composable () -> Unit,
    ranking: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 680.dp * LocalMangaWindow.current.fontScale.coerceAtLeast(1f)
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (searching) MotionIconButton(onClick = onLeaveSearch, modifier = Modifier.size(36.dp),
                        tooltip = "返回发现，保留搜索") {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回发现", Modifier.size(20.dp))
                    } else navigation()
                    if (!stacked && !searching) tabs(Modifier.widthIn(max = 244.dp))
                    input(Modifier.weight(1f))
                    recent()
                }
                if (!searching && stacked) Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)) {
                    tabs(Modifier.widthIn(max = 300.dp).align(Alignment.CenterStart))
                }
            }
        }
        if (!searching) ranking()
    }
}

@Composable
internal fun DesktopDiscoveryWorkspace(
    searching: Boolean,
    browse: @Composable () -> Unit,
    results: @Composable () -> Unit,
) {
    val panes = rememberSaveableStateHolder()
    panes.SaveableStateProvider(if (searching) "desktop-search-results" else "desktop-discovery") {
        if (searching) results() else browse()
    }
}
