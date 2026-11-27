package dev.mangaflow.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.material.icons.outlined.ViewSidebar
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.SearchTarget
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.LocalPlatformAccessibilityPreferences
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionNavigationColumn
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.motionInputGate
import kotlinx.coroutines.delay

internal data class DesktopDestination(
    val title: String, val icon: ImageVector, val groupHeading: String? = null, val utility: Boolean = false,
)

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
    if (compact) Spacer(modifier.fillMaxWidth().height(13.dp).semantics {
        heading()
        contentDescription = title
    }) else Text(title, modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 2.dp)
        .semantics { heading() }, style = MaterialTheme.typography.labelMedium,
        color = ink, letterSpacing = .45.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun Modifier.revealDesktopNavigationLabel(progress: State<Float>): Modifier {
    val start = if (LocalLayoutDirection.current == LayoutDirection.Ltr) -4.dp else 4.dp
    return graphicsLayer {
        alpha = progress.value.coerceIn(0f, 1f)
        translationX = start.toPx() * (1f - alpha)
    }
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
    val colors = MaterialTheme.colorScheme
    val selectedInk = if (LocalPlatformAccessibilityPreferences.current.increaseContrast) colors.onSurface else colors.primary
    val hint = listOfNotNull(title, shortcut).joinToString(" · ")
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .then(if (shortcut == null) Modifier else Modifier.background(lerp(colors.surface, colors.primary, .09f)))
        .clickable(role = Role.Button, interactionSource = interaction, indication = null, onClick = onClick)
        .motionPress(interaction).semantics {
            contentDescription = hint
            if (current != null) selected = current
        }, contentAlignment = Alignment.CenterStart) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(42.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(if (shortcut != null) 18.dp else 20.dp),
                    tint = if (current == true) selectedInk else colors.onSurfaceVariant)
            }
            if (expanded) {
                Text(title, Modifier.weight(1f).revealDesktopNavigationLabel(labels).clearAndSetSemantics {},
                    color = colors.onSurface, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (current == true) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                if (shortcut != null) Text(shortcut, Modifier.padding(end = 7.dp)
                    .revealDesktopNavigationLabel(labels).background(colors.surface, RoundedCornerShape(5.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp).clearAndSetSemantics {},
                    color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            val wideRail = desktopNavigationLabelWidth(LocalMangaWindow.current.fontScale)
            val labelsPresent by remember(labels) { derivedStateOf { labels.value > .03f } }
            val wideProfile by remember(labels) { derivedStateOf { labels.value >= .66f } }
            if (visible) {
                Box(Modifier.matchParentSize().mangaHazeSource(workspaceBackdrop, zIndex = -1f)
                    .background(MaterialTheme.colorScheme.background)) { wallpaper() }
            }
            Box(Modifier.fillMaxSize()) {
                // The content takes its final width once. Only this independent rail measures
                // the moving edge, so gallery columns and image requests stay still during travel.
                Box(Modifier.fillMaxSize().padding(start = if (visible) sidebarWidth else 0.dp).clipToBounds()) {
                    MangaAdaptiveLayout(content)
                }
                if (visible) LiquidGlassSurface(workspaceBackdrop, Modifier.align(Alignment.CenterStart).layout { measurable, bounds ->
                    val live = DesktopNavigationRailWidth + (wideRail - DesktopNavigationRailWidth) * labels.value.coerceIn(0f, 1f)
                    val width = live.roundToPx().coerceIn(bounds.minWidth, bounds.maxWidth)
                    val rail = measurable.measure(bounds.copy(minWidth = width, maxWidth = width))
                    layout(width, rail.height) { rail.placeRelative(0, 0) }
                }.pointerInput(Unit) {}
                    .fillMaxHeight().padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                    cornerRadius = 20.dp, role = GlassSurfaceRole.DRAWER, elevation = 0.dp) {
                    val railHover = remember { MutableInteractionSource() }
                    val keyboardPulse = remember { mutableIntStateOf(0) }
                    Column(Modifier.fillMaxSize().hoverable(railHover)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) keyboardPulse.intValue++
                            false
                        }
                        .onFocusChanged { if (it.hasFocus) keyboardPulse.intValue++ }.focusGroup()
                        .padding(horizontal = 6.dp, vertical = 8.dp)) {
                        val window = LocalMangaWindow.current
                        val pinTools = window.height >= 570.dp * window.fontScale.coerceAtLeast(1f)
                        val bodyIndices = remember(destinations, pinTools) {
                            destinations.indices.filter { !pinTools || !destinations[it].utility }
                        }
                        val toolIndices = remember(destinations, pinTools) {
                            destinations.indices.filter { pinTools && destinations[it].utility }
                        }
                        DesktopNavigationAction("搜索", Icons.Outlined.Search, labelsPresent, labels, onSearch,
                            modifier = Modifier.heightIn(min = 44.dp), shortcut = "Ctrl+K")
                        Spacer(Modifier.height(10.dp))
                        val scroll = rememberScrollState()
                        val rows = remember(destinations, bodyIndices) {
                            sidebarNavigationRows(bodyIndices.map { destinations[it].groupHeading })
                        }
                        val selectedRow = rows.indexOf(SidebarNavigationRow.Destination(bodyIndices.indexOf(selectedIndex)))
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 3.dp)) {
                                Box(Modifier.fillMaxWidth().then(if (motion) Modifier.animateContentSize(
                                    spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness)) else Modifier)) {
                                    profileControl(!wideProfile)
                                }
                                MotionNavigationColumn(selected = selectedRow, itemCount = rows.size,
                                    selectionVisible = selectedRow >= 0, routeMarker = true) { index, itemModifier ->
                                    when (val row = rows[index]) {
                                        is SidebarNavigationRow.Heading -> SidebarGroupLabel(row.title, itemModifier, compact = !wideProfile)
                                        is SidebarNavigationRow.Destination -> {
                                            val route = bodyIndices[row.index]
                                            val destination = destinations[route]
                                            DesktopNavigationAction(destination.title, destination.icon, labelsPresent, labels,
                                                onClick = { onNavigate(route) }, modifier = itemModifier.heightIn(min = 46.dp),
                                                current = selectedIndex == route)
                                        }
                                    }
                                }
                            }
                            DesktopNavigationRailThumb(scroll, railHover, keyboardPulse)
                        }
                        if (toolIndices.isNotEmpty()) {
                            val colors = MaterialTheme.colorScheme
                            Column(Modifier.fillMaxWidth().padding(top = 5.dp)
                                .background(colors.primary.copy(alpha = .055f), RoundedCornerShape(15.dp))
                                .padding(vertical = 2.dp)) {
                                toolIndices.forEach { route ->
                                    val target = destinations[route]
                                    val current = route == selectedIndex
                                    DesktopNavigationAction(target.title, target.icon, labelsPresent, labels, { onNavigate(route) },
                                        modifier = Modifier.heightIn(min = 44.dp).then(if (current)
                                            Modifier.background(colors.surface, RoundedCornerShape(13.dp)) else Modifier),
                                        current = current)
                                }
                            }
                        }
                        if (canExpand) Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                            val action = if (expanded) "收起导航" else "展开导航"
                            MotionIconButton(onClick = onToggle, modifier = Modifier.size(44.dp), tooltip = action) {
                                Icon(Icons.Outlined.ViewSidebar, action, Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (labelsPresent) Text(version, modifier = Modifier.revealDesktopNavigationLabel(labels),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Keep one real scrollbar mounted so thumb dragging is never lost during a fade. */
@Composable
private fun BoxScope.DesktopNavigationRailThumb(scroll: ScrollState, hover: MutableInteractionSource,
    keyboardPulse: State<Int>) {
    val hovered by hover.collectIsHoveredAsState()
    val active = hovered || scroll.isScrollInProgress
    val motion = LocalMotionEnabled.current
    val strongContrast = LocalPlatformAccessibilityPreferences.current.increaseContrast
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(active, keyboardPulse.value) {
        if (active || keyboardPulse.value != 0) shown = true
        if (!active && shown) {
            delay(650)
            shown = false
        }
    }
    val opacity = animateFloatAsState(if (shown || active || strongContrast) 1f else 0f,
        animationSpec = if (motion && !strongContrast) tween(MangaMotion.Quick) else snap(),
        label = "navigation-scrollbar-visibility")
    CompositionLocalProvider(LocalDesktopNavigationScrollbar provides true) {
        DesktopVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(5.dp)
            .graphicsLayer { alpha = opacity.value })
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
    val focused by inputInteraction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val height = maxOf(44.dp, 24.dp * LocalMangaWindow.current.fontScale + 14.dp)
    val focusAmount = animateFloatAsState(if (focused) 1f else 0f,
        if (LocalMotionEnabled.current) tween(MangaMotion.Quick) else snap(), label = "search-field-focus")
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
    val submit = {
        scopeOpen = false
        onOpen()
        onSubmit()
        focus.clearFocus(force = true)
    }
    Box(modifier.height(height)) {
        LiquidGlassSurface(backdrop, Modifier.fillMaxSize(), cornerRadius = height / 2,
            role = GlassSurfaceRole.SEARCH, interactionSource = inputInteraction, softCard = true,
            elevation = 6.dp, materialExpansion = { focusAmount.value }) {}
        Row(Modifier.fillMaxSize().padding(start = 2.dp, end = 3.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val searchAction = if (query.isBlank()) "输入关键词" else "搜索"
            MotionIconButton(onClick = { if (query.isBlank()) requester.requestFocus() else submit() },
                modifier = Modifier.size(44.dp), enabled = active, tooltip = searchAction) {
                Icon(Icons.Outlined.Search, searchAction, Modifier.size(19.dp), tint = if (focused) colors.primary else colors.onSurfaceVariant)
            }
            BasicTextField(value = query, onValueChange = { onOpen(); onQuery(it) },
                enabled = active, singleLine = true, interactionSource = inputInteraction,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                modifier = Modifier.weight(1f).height(height).padding(end = 8.dp)
                    .focusRequester(requester).onFocusChanged { if (it.isFocused) onOpen() }
                    .semantics { contentDescription = searchLabel },
                decorationBox = { input ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text(searchLabel, style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        input()
                    }
                })
            if (query.isNotEmpty() || copyOnly) MotionIconButton(onClick = {
                scopeOpen = false; onClear()
            }, enabled = active, modifier = Modifier.size(44.dp), tooltip = "清空搜索") {
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
                        modifier = Modifier.size(44.dp), tooltip = "搜索范围：" + targetLabel) {
                        Box(Modifier.size(32.dp).background(
                            if (scopeOpen) colors.primaryContainer else colors.primary.copy(alpha = .072f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center) {
                            Icon(targetIcon, "搜索范围：" + targetLabel, Modifier.align(Alignment.Center).size(18.dp))
                            if (copyOnly || target == SearchTarget.TRANSLATION_GROUP)
                                BodySourceMark("copy", "拷贝", Modifier.align(Alignment.BottomEnd).padding(4.dp).size(8.dp))
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
    tabLabels: List<String>,
    onLeaveSearch: () -> Unit,
    navigation: @Composable () -> Unit,
    tabs: @Composable (Modifier) -> Unit,
    input: @Composable (Modifier) -> Unit,
    recent: @Composable () -> Unit,
    ranking: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMotionEnabled.current
    Column(modifier) {
        val ink = rememberBrowseTabInk(tabLabels)
        val tabGroup: @Composable (Modifier) -> Unit = { bounds ->
            HeaderGlassSurface(backdrop, bounds, quietShadow = true, ink = ink) {
                tabs(Modifier.fillMaxWidth().padding(horizontal = 4.dp))
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 680.dp * LocalMangaWindow.current.fontScale.coerceAtLeast(1f)
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.animateContentSize(if (motion) spring(MangaMotion.NavigationDamping,
                        MangaMotion.NavigationStiffness) else snap()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (searching) MotionIconButton(onClick = onLeaveSearch, modifier = Modifier.size(36.dp),
                            tooltip = "返回发现，保留搜索") {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回发现", Modifier.size(20.dp))
                        } else navigation()
                        // The gap belongs to the navigation button, including when the rail supplies it instead.
                        Spacer(Modifier.width(0.dp))
                    }
                    if (!stacked) AnimatedVisibility(!searching,
                        enter = expandHorizontally(if (motion) spring(MangaMotion.NavigationDamping,
                            MangaMotion.NavigationStiffness) else snap(), expandFrom = Alignment.Start) +
                            fadeIn(if (motion) tween(MangaMotion.Content) else snap()),
                        exit = shrinkHorizontally(if (motion) spring(MangaMotion.NavigationDamping,
                            MangaMotion.NavigationStiffness) else snap(), shrinkTowards = Alignment.Start) +
                            fadeOut(if (motion) tween(MangaMotion.Quick) else snap())) {
                        Box(Modifier.padding(end = 10.dp).motionInputGate(!searching)) {
                            tabGroup(Modifier.widthIn(max = 244.dp))
                        }
                    }
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        input(Modifier.weight(1f))
                        recent()
                    }
                }
                if (stacked) AnimatedVisibility(!searching,
                    enter = expandVertically(if (motion) spring(MangaMotion.NavigationDamping,
                        MangaMotion.NavigationStiffness) else snap(), expandFrom = Alignment.Top) +
                        fadeIn(if (motion) tween(MangaMotion.Content) else snap()),
                    exit = shrinkVertically(if (motion) spring(MangaMotion.NavigationDamping,
                        MangaMotion.NavigationStiffness) else snap(), shrinkTowards = Alignment.Top) +
                        fadeOut(if (motion) tween(MangaMotion.Quick) else snap())) {
                    Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp).motionInputGate(!searching)) {
                        tabGroup(Modifier.widthIn(max = 300.dp).align(Alignment.CenterStart))
                    }
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
    val progress = animateFloatAsState(if (searching) 1f else 0f,
        if (LocalMotionEnabled.current) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else snap(),
        label = "desktop-search-workspace")
    panes.SaveableStateProvider(if (searching) "desktop-search-results" else "desktop-discovery") {
        Box(Modifier.fillMaxSize().graphicsLayer {
            val remaining = if (searching) 1f - progress.value else progress.value
            alpha = 1f - .13f * remaining
            translationY = (if (searching) 5.dp else (-3).dp).toPx() * remaining
        }) { if (searching) results() else browse() }
    }
}
