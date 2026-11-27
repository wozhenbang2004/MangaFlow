package dev.mangaflow.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Source
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.indexOfFirstPressed
import androidx.compose.ui.input.pointer.indexOfLastPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
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
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.motionInputGate
import kotlinx.coroutines.delay

internal data class DesktopDestination(
    val title: String, val icon: ImageVector, val groupHeading: String? = null, val utility: Boolean = false,
    val account: Boolean = false, val accountName: String? = null,
)

internal val DesktopNavigationRailWidth = 64.dp

internal fun desktopNavigationLabelWidth(fontScale: Float): Dp =
    (218.dp + 66.dp * (fontScale - 1f)).coerceIn(218.dp, 276.dp)

/** Keep enough of the actual workspace for a dock, and protect the existing desktop
 * detail/management layout before disclosing labels. A user's compact choice still wins. */
internal fun MangaWindowSize.canDockDesktopNavigation(): Boolean = width - DesktopNavigationRailWidth >= maxOf(398.dp, 350.dp * fontScale.coerceIn(1f, 1.5f))

internal fun MangaWindowSize.canExpandDesktopNavigation(): Boolean =
    width - desktopNavigationLabelWidth(fontScale) >= maxOf(720.dp, 650.dp * fontScale.coerceIn(1f, 1.65f))

/** A Windows mouse's dedicated side buttons follow the same route owner as Alt+Left/Right. */
@Composable
internal fun Modifier.desktopHistoryButtons(onBack: (() -> Unit)?, onForward: (() -> Unit)?): Modifier {
    if (platformKind != PlatformKind.DESKTOP) return this
    val back by rememberUpdatedState(onBack)
    val forward by rememberUpdatedState(onForward)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            var goingBack: Boolean? = null
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.none { it.type == PointerType.Mouse }) continue
                val buttons = event.buttons
                if (buttons.indexOfFirstPressed() != buttons.indexOfLastPressed() || event.type == PointerEventType.Exit) {
                    goingBack = null
                    continue
                }
                if (event.type == PointerEventType.Press && goingBack == null) {
                    goingBack = when {
                        buttons.isBackPressed && back != null -> true
                        buttons.isForwardPressed && forward != null -> false
                        else -> null
                    }
                    if (goingBack != null) event.changes.forEach { it.consume() }
                } else if (event.type == PointerEventType.Release && !buttons.areAnyPressed && goingBack != null) {
                    val action = if (goingBack == true) back else forward
                    goingBack = null
                    event.changes.forEach { it.consume() }
                    action?.invoke()
                }
            }
        }
    }
}

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

/** Groups span the full navigation canvas. Only interactive controls receive a shape. */
@Composable
private fun DesktopNavigationAction(
    title: String, icon: ImageVector, labels: State<Float>, onClick: () -> Unit,
    modifier: Modifier = Modifier, current: Boolean? = null, subtitle: String? = null,
    shortcut: String? = null, paintCurrent: Boolean = false,
) {
    val showText by remember(labels) { derivedStateOf { labels.value > .04f } }
    val showShortcut by remember(labels) { derivedStateOf { labels.value > .80f } }
    var clippedLabel by remember(title, subtitle) { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    val selectedInk = if (LocalPlatformAccessibilityPreferences.current.increaseContrast) colors.onSurface else colors.primary
    val hint = listOfNotNull(title, subtitle, shortcut).joinToString(" · ")
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.fillMaxWidth().clip(shape)
        .then(if (paintCurrent && current == true) Modifier.background(selectedInk.copy(alpha = .11f)) else Modifier)
        .clickable(role = Role.Button, interactionSource = interactions, indication = null, onClick = onClick)
        .motionPress(interactions).semantics {
            contentDescription = hint
            if (current != null) selected = current
        }, contentAlignment = Alignment.CenterStart) {
        Row(Modifier.fillMaxWidth().heightIn(min = 38.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(19.dp), tint = if (current == true) selectedInk else colors.onSurfaceVariant)
            }
            if (showText) {
                Column(Modifier.weight(1f).padding(end = 4.dp, top = 5.dp, bottom = 5.dp)
                    .revealDesktopNavigationLabel(labels).clearAndSetSemantics {}) {
                    Text(subtitle ?: title, color = if (current == true) selectedInk else colors.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (current == true || subtitle != null) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                        onTextLayout = { clippedLabel = it.hasVisualOverflow })
                    if (subtitle != null) Text(title, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (shortcut != null && showShortcut) Text(shortcut, Modifier.padding(end = 8.dp).revealDesktopNavigationLabel(labels)
                    .clearAndSetSemantics {}, style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant, maxLines = 1, softWrap = false)
            }
        }
        DesktopIconTooltip(hint.takeIf { !showText || subtitle != null || clippedLabel }, interactions, enabled = true)
    }
}

@Composable
internal fun DesktopNavigationWorkspace(
    visible: Boolean, expanded: Boolean, sidebarWidth: Dp,
    backdrop: HazeState, workspaceBackdrop: HazeState,
    wallpaper: @Composable () -> Unit, modifier: Modifier = Modifier,
    navigation: @Composable (State<Float>) -> Unit, content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize().then(if (visible) Modifier.mangaHazeSource(backdrop) else Modifier)) {
        if (platformKind != PlatformKind.DESKTOP) {
            content()
        } else {
            val motion = LocalMotionEnabled.current
            val labels = animateFloatAsState(if (expanded) 1f else 0f,
                if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else snap(),
                label = "desktop-navigation-labels")
            val wideRail = desktopNavigationLabelWidth(LocalMangaWindow.current.fontScale)
            if (visible) Box(Modifier.matchParentSize().mangaHazeSource(workspaceBackdrop, zIndex = -1f)
                .background(MaterialTheme.colorScheme.background)) { wallpaper() }
            // The gallery is measured at its destination width only once. The rail animates over it.
            Box(Modifier.fillMaxSize().padding(start = if (visible) sidebarWidth else 0.dp).clipToBounds()) {
                MangaAdaptiveLayout(content)
            }
            if (visible) Box(Modifier.align(Alignment.CenterStart).layout { measurable, bounds ->
                val live = DesktopNavigationRailWidth + (wideRail - DesktopNavigationRailWidth) * labels.value.coerceIn(0f, 1f)
                val width = live.roundToPx().coerceIn(bounds.minWidth, bounds.maxWidth)
                val rail = measurable.measure(bounds.copy(minWidth = width, maxWidth = width))
                layout(width, rail.height) { rail.placeRelative(0, 0) }
            }.fillMaxHeight().clipToBounds().pointerInput(Unit) {}) { navigation(labels) }
        }
    }
}

@Composable
internal fun DesktopNavigationPane(
    labels: State<Float>, profile: ContentProfile, destinations: List<DesktopDestination>, selectedIndex: Int,
    onSelectProfile: (ContentProfile) -> Unit, onNavigate: (Int) -> Unit,
    onManageSources: () -> Unit, onSearch: (() -> Unit)?, onBack: (() -> Unit)?, onForward: (() -> Unit)?,
    showHistory: Boolean, toggleLabel: String?, onToggle: () -> Unit, floating: Boolean, enabled: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val roomy by remember(labels) { derivedStateOf { labels.value > .74f } }
    val railHover = remember { MutableInteractionSource() }
    val keyboardPulse = remember { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    val fontScale = LocalMangaWindow.current.fontScale
    val motion = LocalMotionEnabled.current
    val paper = lerp(colors.surface, colors.onSurfaceVariant, .055f)
    val pages = remember(destinations) { destinations.indices.filter { !destinations[it].utility } }
    val utilities = remember(destinations) { destinations.indices.filter { destinations[it].utility && !destinations[it].account } }
    val accountIndex = destinations.indexOfFirst { it.account }
    val rows = remember(destinations, pages) { sidebarNavigationRows(pages.map { destinations[it].groupHeading }) }
    val selectedRow = rows.indexOf(SidebarNavigationRow.Destination(pages.indexOf(selectedIndex)))
    val management: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            DesktopNavigationAction(if (profile == ContentProfile.ANIME) "管理片源" else "管理来源", Icons.Outlined.Source,
                labels, onManageSources)
            utilities.forEach { route ->
                val target = destinations[route]
                DesktopNavigationAction(target.title, target.icon, labels, { onNavigate(route) },
                    current = route == selectedIndex, paintCurrent = true)
            }
            if (accountIndex >= 0) {
                val account = destinations[accountIndex]
                Spacer(Modifier.height(6.dp))
                DesktopNavigationAction(account.title, account.icon, labels, { onNavigate(accountIndex) },
                    subtitle = account.accountName, current = selectedIndex == accountIndex, paintCurrent = true)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(paper).hoverable(railHover)
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown) keyboardPulse.intValue++
            false
        }
        .onFocusChanged { if (it.hasFocus) keyboardPulse.intValue++ }.focusGroup()
        .semantics { paneTitle = "${profile.title}导航" }.motionInputGate(enabled)) {
        val pinTools = maxHeight >= 570.dp * fontScale.coerceAtMost(1.6f)
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp)) {
            val scopeHeader: @Composable () -> Unit = {
                // Change row structure only once the rail can show both labels and controls.
                Column(Modifier.fillMaxWidth().then(if (motion) Modifier.animateContentSize(
                    spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness)) else Modifier)) {
                    if (roomy) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { DesktopScopePicker(profile, onSelectProfile) }
                        if (toggleLabel != null) MotionIconButton(onClick = onToggle, modifier = Modifier.size(34.dp), tooltip = toggleLabel) {
                            Icon(if (floating) Icons.Outlined.Close else Icons.Outlined.ViewSidebar, toggleLabel, Modifier.size(17.dp))
                        }
                    } else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (toggleLabel != null) MotionIconButton(onClick = onToggle, modifier = Modifier.size(36.dp), tooltip = toggleLabel) {
                            Icon(Icons.Outlined.ViewSidebar, toggleLabel, Modifier.size(17.dp))
                        }
                        ContentProfile.entries.forEach { entry ->
                            val current = profile == entry
                            MotionIconButton(onClick = { if (!current) onSelectProfile(entry) }, tooltip = entry.title,
                                modifier = Modifier.size(38.dp).semantics { selected = current }
                                    .then(if (current) Modifier.background(colors.primary.copy(alpha = .13f),
                                        RoundedCornerShape(10.dp)) else Modifier)) {
                                Icon(entry.icon, entry.title, Modifier.size(18.dp),
                                    tint = if (current) colors.primary else colors.onSurfaceVariant)
                            }
                        }
                    }
                    Spacer(Modifier.height(if (roomy) 8.dp else 4.dp))
                    if (roomy && showHistory) Row(verticalAlignment = Alignment.CenterVertically) {
                        MotionIconButton(onClick = { onBack?.invoke() }, enabled = onBack != null, modifier = Modifier.size(34.dp),
                            tooltip = "后退 · Alt+←") {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "后退", Modifier.size(17.dp))
                        }
                        MotionIconButton(onClick = { onForward?.invoke() }, enabled = onForward != null, modifier = Modifier.size(34.dp),
                            tooltip = "前进 · Alt+→") {
                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, "前进", Modifier.size(17.dp))
                        }
                    } else if (showHistory) Box(Modifier.align(Alignment.CenterHorizontally)) {
                        var historyOpen by remember { mutableStateOf(false) }
                        MotionIconButton(onClick = { historyOpen = true }, enabled = onBack != null || onForward != null,
                            modifier = Modifier.size(36.dp), tooltip = "导航历史") {
                            Icon(Icons.Outlined.History, "导航历史", Modifier.size(17.dp))
                        }
                        DesktopActionPopup(historyOpen, listOf(
                            DesktopMenuAction("后退 · Alt+←", enabled = onBack != null, icon = Icons.AutoMirrored.Outlined.ArrowBack) {
                                onBack?.invoke()
                            },
                            DesktopMenuAction("前进 · Alt+→", enabled = onForward != null, icon = Icons.AutoMirrored.Outlined.ArrowForward) {
                                onForward?.invoke()
                            },
                        ), onDismiss = { historyOpen = false }, alignToStart = true)
                    }
                    if (onSearch != null) DesktopNavigationAction("搜索", Icons.Outlined.Search, labels, onSearch, shortcut = "Ctrl+K")
                }
            }
            if (pinTools) scopeHeader()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 3.dp)) {
                    if (!pinTools) scopeHeader()
                    MotionNavigationColumn(selectedRow, rows.size, selectionVisible = selectedRow >= 0, routeMarker = true) { index, bounds ->
                        when (val row = rows[index]) {
                            is SidebarNavigationRow.Heading -> SidebarGroupLabel(row.title, bounds, compact = !roomy)
                            is SidebarNavigationRow.Destination -> {
                                val route = pages[row.index]
                                val page = destinations[route]
                                DesktopNavigationAction(page.title, page.icon, labels, { onNavigate(route) },
                                    modifier = bounds.heightIn(min = 41.dp), current = selectedIndex == route)
                            }
                        }
                    }
                    if (!pinTools) management()
                }
                DesktopNavigationRailThumb(scroll, railHover, keyboardPulse)
            }
            if (pinTools) management()
        }
    }
}

@Composable
private fun DesktopScopePicker(profile: ContentProfile, onSelect: (ContentProfile) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var open by remember(profile) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Box {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button,
            interactionSource = interaction, indication = null) { open = true }.motionPress(interaction)
            .semantics { contentDescription = "切换内容分区，当前${profile.title}" }
            .heightIn(min = 48.dp).padding(horizontal = 5.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(32.dp).background(colors.primary.copy(alpha = .12f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center) { Icon(profile.icon, null, Modifier.size(18.dp), tint = colors.primary) }
            Text(profile.title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(15.dp), tint = colors.onSurfaceVariant)
        }
        DesktopActionPopup(open, ContentProfile.entries.map { entry ->
            DesktopMenuAction(entry.title, icon = entry.icon, selected = profile == entry) {
                if (profile != entry) onSelect(entry)
            }
        }, onDismiss = { open = false }, alignToStart = true)
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
        DesktopVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(10.dp)
            .graphicsLayer { alpha = opacity.value })
    }
}

private val SearchTarget.scopeTitle get() = when (this) {
    SearchTarget.TITLE -> "作品"
    SearchTarget.AUTHOR -> "作者"
    SearchTarget.TAG -> "标签"
    SearchTarget.TRANSLATION_GROUP -> "汉化组"
}

private val SearchTarget.scopeIcon get() = when (this) {
    SearchTarget.TITLE -> Icons.AutoMirrored.Outlined.MenuBook
    SearchTarget.AUTHOR -> Icons.Outlined.PersonOutline
    SearchTarget.TAG -> Icons.AutoMirrored.Outlined.Label
    SearchTarget.TRANSLATION_GROUP -> Icons.Outlined.Translate
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
    val height = maxOf(40.dp, 22.dp * LocalMangaWindow.current.fontScale + 13.dp)
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
    BoxWithConstraints(modifier.height(height)) {
        val showKeyHint = !focused && query.isBlank() && maxWidth >= 328.dp * LocalMangaWindow.current.fontScale
        val fieldPaper = lerp(colors.surface, colors.onSurfaceVariant, .062f)
        val focusInk = if (LocalPlatformAccessibilityPreferences.current.increaseContrast) colors.onSurface else colors.primary
        Box(Modifier.matchParentSize().clip(RoundedCornerShape(10.dp)).background(fieldPaper).drawBehind {
            val progress = focusAmount.value.coerceIn(0f, 1f)
            if (progress > 0f) {
                val gutter = 9.dp.toPx()
                val width = (size.width - gutter * 2f).coerceAtLeast(0f) * progress
                val thickness = 2.dp.toPx()
                drawRoundRect(focusInk.copy(alpha = progress), Offset((size.width - width) / 2f, size.height - thickness),
                    Size(width, thickness), CornerRadius(thickness / 2f))
            }
        })
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
            if (showKeyHint) Text("Ctrl+K", Modifier.padding(end = 6.dp).clearAndSetSemantics {},
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
            if (query.isNotEmpty() || copyOnly) MotionIconButton(onClick = {
                scopeOpen = false; onClear()
            }, enabled = active, modifier = Modifier.size(44.dp), tooltip = "清空搜索") {
                Icon(Icons.Outlined.Close, "清空搜索", Modifier.size(17.dp))
            }
            if (showScopeSelector) {
                Box {
                    MotionIconButton(onClick = { scopeOpen = !scopeOpen }, enabled = active,
                        modifier = Modifier.size(44.dp), tooltip = "搜索范围：" + target.scopeTitle) {
                        Box(Modifier.size(32.dp).background(
                            if (scopeOpen) colors.primaryContainer else colors.surface.copy(alpha = 0f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center) {
                            Icon(target.scopeIcon, "搜索范围：" + target.scopeTitle, Modifier.align(Alignment.Center).size(18.dp))
                            if (copyOnly || target == SearchTarget.TRANSLATION_GROUP)
                                BodySourceMark("copy", "拷贝", Modifier.align(Alignment.BottomEnd).padding(4.dp).size(8.dp))
                        }
                    }
                    val scopes = SearchTarget.entries.filter { translationGroupAvailable || it != SearchTarget.TRANSLATION_GROUP }
                    DesktopActionPopup(scopeOpen, scopes.map { scope ->
                        DesktopMenuAction(scope.scopeTitle, icon = scope.scopeIcon, selected = scope == target) {
                            onOpen()
                            onTarget(scope)
                        }
                    }, onDismiss = { scopeOpen = false })
                }
            }
        }
    }
}

@Composable
internal fun DesktopPageHeading(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
        fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-.5f).sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun DesktopSectionTabs(selected: Int, labels: List<String>, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    MotionTabs(selected, labels, onSelect, modifier, treatment = MotionTabTreatment.Underline,
        controlHeight = 38.dp, fontSize = 14.sp, indicator = if (LocalPlatformAccessibilityPreferences.current.increaseContrast)
            colors.onSurface else colors.primary, selectedFontWeight = FontWeight.SemiBold,
        unselectedFontWeight = FontWeight.Normal)
}

@Composable
internal fun DesktopDiscoveryHeader(
    title: String, searching: Boolean, tabLabels: List<String>, selectedTab: Int, onSelectTab: (Int) -> Unit,
    onLeaveSearch: () -> Unit, navigation: @Composable () -> Unit, input: @Composable (Modifier) -> Unit,
    recent: @Composable () -> Unit, ranking: @Composable () -> Unit, modifier: Modifier = Modifier,
) {
    val motion = LocalMotionEnabled.current
    val inputContent = rememberUpdatedState(input)
    // Move the existing text field (selection, composition and focus) across breakpoint rows.
    val field = remember { movableContentOf<Modifier> { bounds -> inputContent.value(bounds) } }
    val displayed = if (searching) "搜索结果" else title
    BoxWithConstraints(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
        .pointerInput(Unit) {}.semantics { paneTitle = displayed }) {
        val scale = LocalMangaWindow.current.fontScale.coerceAtLeast(1f)
        val inset = if (maxWidth >= 540.dp) 20.dp else 12.dp
        val available = (maxWidth - inset * 2).coerceAtLeast(1.dp)
        val tabWidth = (tabLabels.size * (36f * scale + 19f)).dp
        val wide = available >= 760.dp * scale
        val inlineTabs = available >= (tabWidth + 148.dp * scale + 76.dp)
        val fieldWidth = animateDpAsState(if (searching) (available * .60f).coerceAtMost(620.dp)
            else (available * .38f).coerceIn(200.dp, 424.dp),
            animationSpec = if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else snap(),
            label = "workspace-search-width")
        val switcher: @Composable (Modifier) -> Unit = { bounds ->
            DesktopSectionTabs(selectedTab, tabLabels, onSelectTab, bounds.motionInputGate(!searching))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = inset, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (searching) MotionIconButton(onClick = onLeaveSearch, modifier = Modifier.size(34.dp),
                    tooltip = "返回发现，保留搜索") {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回发现", Modifier.size(19.dp))
                } else navigation()
                DesktopPageHeading(displayed, if (inlineTabs || wide) Modifier.widthIn(max = 180.dp * scale)
                    else Modifier.weight(1f))
                if (wide || inlineTabs) AnimatedVisibility(!searching,
                    enter = expandHorizontally(if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else snap(),
                        expandFrom = Alignment.Start) + fadeIn(if (motion) tween(MangaMotion.Content) else snap()),
                    exit = shrinkHorizontally(if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else snap(),
                        shrinkTowards = Alignment.Start) + fadeOut(if (motion) tween(MangaMotion.Quick) else snap())) {
                    switcher(Modifier.width(tabWidth))
                } else if (!searching) {
                    DesktopBrowseMenu(selectedTab, tabLabels, onSelectTab)
                }
                if (wide || inlineTabs) Spacer(Modifier.weight(1f))
                if (wide) field(Modifier.width(fieldWidth.value))
                recent()
            }
            if (!wide) field(Modifier.fillMaxWidth())
            if (!searching) ranking()
        }
    }
}

/** In the narrowest windows the selector has its own anchor next to the title. The input still has a full row. */
@Composable
private fun DesktopBrowseMenu(selected: Int, labels: List<String>, onSelect: (Int) -> Unit) {
    val current = labels.getOrNull(selected) ?: return
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    Box {
        Row(Modifier.clip(RoundedCornerShape(9.dp)).clickable(role = Role.Button, interactionSource = interaction,
            indication = null) { open = true }.motionPress(interaction)
            .semantics { contentDescription = "切换浏览方式，当前$current" }
            .heightIn(min = 36.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(current, style = MaterialTheme.typography.labelLarge, color = colors.primary, maxLines = 1)
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(15.dp), tint = colors.onSurfaceVariant)
        }
        DesktopActionPopup(open, labels.mapIndexed { index, label ->
            DesktopMenuAction(label, selected = index == selected) { onSelect(index) }
        }, onDismiss = { open = false })
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
