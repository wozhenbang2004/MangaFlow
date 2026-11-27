package dev.mangaflow.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onKeyEvent
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
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
import dev.mangaflow.ui.motion.MotionSwap
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.motionInputGate
import kotlinx.coroutines.delay

internal data class DesktopDestination(
    val title: String, val icon: ImageVector, val groupHeading: String? = null, val utility: Boolean = false,
    val account: Boolean = false, val accountName: String? = null,
)

internal val DesktopNavigationRailWidth = 64.dp

/** The search field and its result list live in separate slots of this window. */
internal class DesktopSearchFocus {
    var field by mutableStateOf<FocusRequester?>(null)
    var results by mutableStateOf<(() -> Boolean)?>(null)
}

internal val LocalDesktopSearchFocus = staticCompositionLocalOf<DesktopSearchFocus?> { null }

internal fun Modifier.desktopSearchPaneKey(target: (() -> Boolean)?): Modifier = if (target == null) this else
    onPreviewKeyEvent { event ->
        if (event.key != Key.F6 || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) false
        else if (event.type == KeyEventType.KeyUp) target() else true
    }

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
    if (compact) Box(modifier.fillMaxWidth().height(13.dp).semantics {
        heading()
        contentDescription = title
    }, contentAlignment = Alignment.Center) {
        Spacer(Modifier.size(18.dp, 1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    } else Text(title, modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 2.dp)
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
    shortcut: String? = null, identity: Boolean = false, keyboardFocus: Boolean,
) {
    val showText by remember(labels) { derivedStateOf { labels.value > .04f } }
    val showShortcut by remember(labels) { derivedStateOf { labels.value > .80f } }
    var clippedLabel by remember(title, subtitle) { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    val selectedInk = if (LocalPlatformAccessibilityPreferences.current.increaseContrast) colors.onSurface else colors.primary
    val hint = listOfNotNull(title, subtitle, shortcut).joinToString(" · ")
    val shape = RoundedCornerShape(if (identity) 13.dp else 10.dp)
    Box(modifier.fillMaxWidth().clip(shape)
        .then(if (identity) Modifier.background(if (current == true) colors.primaryContainer else colors.surface) else Modifier)
        .clickable(role = Role.Button, interactionSource = interactions, indication = null, onClick = onClick)
        .motionPress(interactions, showKeyboardFocus = keyboardFocus).semantics {
            contentDescription = hint
            if (current != null) selected = current
        }, contentAlignment = Alignment.CenterStart) {
        Row(Modifier.fillMaxWidth().heightIn(min = if (identity) 51.dp else 38.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) {
                if (identity) {
                    val initial = remember(subtitle) { subtitle?.trim()?.firstOrNull()?.takeIf { it.isLetterOrDigit() }?.uppercaseChar() }
                    Box(Modifier.size(30.dp).background(colors.primaryContainer, CircleShape).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                        if (initial != null) Text(initial.toString(), fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                            color = colors.onPrimaryContainer, maxLines = 1)
                        else Icon(icon, null, Modifier.size(17.dp), tint = colors.onPrimaryContainer)
                    }
                } else Icon(icon, null, Modifier.size(19.dp), tint = if (current == true) selectedInk else colors.onSurfaceVariant)
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
                if (shortcut != null && showShortcut) DesktopShortcutHint(shortcut,
                    Modifier.padding(end = 8.dp).revealDesktopNavigationLabel(labels))
            }
        }
        DesktopIconTooltip(hint.takeIf { !showText || subtitle != null || clippedLabel }, interactions,
            enabled = true, showOnFocus = keyboardFocus)
    }
}

@Composable
internal fun DesktopShortcutHint(label: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Text(label, modifier.clearAndSetSemantics { }.background(colors.surface, RoundedCornerShape(5.dp))
        .padding(horizontal = 5.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall,
        color = colors.onSurfaceVariant, maxLines = 1, softWrap = false)
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
            val wideRail = desktopNavigationLabelWidth(LocalDensity.current.fontScale.coerceAtLeast(1f))
            if (visible) Box(Modifier.matchParentSize().mangaHazeSource(workspaceBackdrop, zIndex = -1f)
                .background(MaterialTheme.colorScheme.background)) { wallpaper() }
            // The gallery is measured at its destination width only once. The rail animates over it.
            Box(Modifier.fillMaxSize().padding(start = if (visible) sidebarWidth else 0.dp).clipToBounds()) {
                val searchFocus = remember { DesktopSearchFocus() }
                CompositionLocalProvider(LocalDesktopSearchFocus provides searchFocus) { MangaAdaptiveLayout(content) }
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
    var keyboardFocus by remember { mutableStateOf(true) }
    val scroll = rememberScrollState()
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val motion = LocalMotionEnabled.current
    val paper = colors.surfaceContainerLow
    val pages = remember(destinations) { destinations.indices.filter { !destinations[it].utility } }
    val pageFocus = remember(profile, pages) { List(pages.size) { FocusRequester() } }
    val utilities = remember(destinations) { destinations.indices.filter { destinations[it].utility && !destinations[it].account } }
    val accountIndex = destinations.indexOfFirst { it.account }
    val rows = remember(destinations, pages) { sidebarNavigationRows(pages.map { destinations[it].groupHeading }) }
    val selectedRow = rows.indexOf(SidebarNavigationRow.Destination(pages.indexOf(selectedIndex)))
    val management: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val sourceTitle = if (profile == ContentProfile.ANIME) "管理片源" else "管理来源"
            if (roomy) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    DesktopUtilityAction(sourceTitle, Icons.Outlined.Source, onManageSources, Modifier.weight(1f), labelled = true, keyboardFocus = keyboardFocus)
                    utilities.forEach { route ->
                        val target = destinations[route]
                        DesktopUtilityAction(target.title, target.icon, { onNavigate(route) }, Modifier.weight(1f),
                            labelled = true, current = route == selectedIndex, keyboardFocus = keyboardFocus)
                    }
                }
            } else {
                DesktopUtilityAction(sourceTitle, Icons.Outlined.Source, onManageSources, keyboardFocus = keyboardFocus)
                utilities.forEach { route ->
                    val target = destinations[route]
                    DesktopUtilityAction(target.title, target.icon, { onNavigate(route) }, current = route == selectedIndex, keyboardFocus = keyboardFocus)
                }
            }
            if (accountIndex >= 0) {
                val account = destinations[accountIndex]
                Spacer(Modifier.height(8.dp))
                DesktopNavigationAction(account.title, account.icon, labels, { onNavigate(accountIndex) },
                    subtitle = account.accountName, current = selectedIndex == accountIndex, identity = true, keyboardFocus = keyboardFocus)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(paper).hoverable(railHover)
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press && event.changes.any { it.type == PointerType.Mouse })
                        keyboardFocus = false
                }
            }
        }
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown) {
                keyboardFocus = true
                keyboardPulse.intValue++
            }
            false
        }
        .onFocusChanged { if (it.hasFocus) keyboardPulse.intValue++ else keyboardFocus = true }.focusGroup()
        .semantics { paneTitle = "${profile.title}导航" }.motionInputGate(enabled)) {
        val pinTools = maxHeight >= 570.dp * fontScale.coerceAtMost(1.6f)
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp)) {
            val scopeHeader: @Composable () -> Unit = {
                // Change row structure only once the rail can show both labels and controls.
                Column(Modifier.fillMaxWidth().then(if (motion) Modifier.animateContentSize(
                    spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness)) else Modifier)) {
                    val toggle: @Composable () -> Unit = {
                        if (toggleLabel != null) MotionIconButton(onClick = onToggle, modifier = Modifier.size(36.dp), tooltip = toggleLabel) {
                            Icon(if (floating) Icons.Outlined.Close else Icons.Outlined.ViewSidebar, toggleLabel, Modifier.size(17.dp))
                        }
                    }
                    val historyButtons: @Composable () -> Unit = {
                        MotionIconButton(onClick = { onBack?.invoke() }, enabled = onBack != null, modifier = Modifier.size(36.dp),
                            tooltip = "后退 · Alt+←") { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "后退", Modifier.size(17.dp)) }
                        MotionIconButton(onClick = { onForward?.invoke() }, enabled = onForward != null, modifier = Modifier.size(36.dp),
                            tooltip = "前进 · Alt+→") { Icon(Icons.AutoMirrored.Outlined.ArrowForward, "前进", Modifier.size(17.dp)) }
                    }
                    if (roomy && (showHistory || toggleLabel != null)) Row(Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (showHistory) historyButtons()
                        Spacer(Modifier.weight(1f))
                        toggle()
                    } else if (!roomy && toggleLabel != null) Box(Modifier.align(Alignment.CenterHorizontally)) { toggle() }
                    ContentProfileSwitch(profile, onSelectProfile, onManage = null, compact = !roomy, enabled = enabled,
                        modifier = Modifier.padding(top = 3.dp, bottom = if (roomy) 10.dp else 6.dp))
                    if (!roomy && showHistory && pinTools) Column(Modifier.align(Alignment.CenterHorizontally)) {
                        historyButtons()
                    } else if (!roomy && showHistory) Box(Modifier.align(Alignment.CenterHorizontally)) {
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
                    if (onSearch != null) DesktopNavigationAction("搜索", Icons.Outlined.Search, labels, onSearch,
                        shortcut = "Ctrl+K", keyboardFocus = keyboardFocus)
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
                                    modifier = bounds.heightIn(min = 41.dp).focusRequester(pageFocus[row.index])
                                        .onPreviewKeyEvent { event ->
                                            if (!enabled || event.isAltPressed || event.isCtrlPressed || event.isMetaPressed || event.isShiftPressed)
                                                return@onPreviewKeyEvent false
                                            val next = when (event.key) {
                                                Key.DirectionUp -> row.index - 1
                                                Key.DirectionDown -> row.index + 1
                                                Key.MoveHome -> 0
                                                Key.MoveEnd -> pages.lastIndex
                                                else -> return@onPreviewKeyEvent false
                                            }
                                            if (next !in pages.indices) return@onPreviewKeyEvent false
                                            if (event.type == KeyEventType.KeyDown && next != row.index) pageFocus[next].requestFocus()
                                            true
                                        }, current = selectedIndex == route, keyboardFocus = keyboardFocus)
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
private fun DesktopUtilityAction(title: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier,
    labelled: Boolean = false, current: Boolean = false, keyboardFocus: Boolean) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val ink = if (current) colors.onPrimaryContainer else colors.onSurfaceVariant
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.then(if (labelled) Modifier.heightIn(min = 44.dp) else Modifier.size(38.dp))
        .clip(shape).then(if (current) Modifier.background(colors.primaryContainer) else Modifier)
        .clickable(role = Role.Button, interactionSource = interaction, indication = null, onClick = onClick)
        .motionPress(interaction, showKeyboardFocus = keyboardFocus).semantics { contentDescription = title; selected = current }, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(horizontal = 2.dp, vertical = 4.dp).clearAndSetSemantics { },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(icon, null, Modifier.size(17.dp), tint = ink)
            if (labelled) Text(title, style = MaterialTheme.typography.labelSmall, color = ink,
                maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center, overflow = TextOverflow.Ellipsis)
        }
        DesktopIconTooltip(title, interaction, enabled = true, showOnFocus = keyboardFocus)
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

@Composable
private fun DesktopMenuChevron(open: Boolean) {
    val turn = animateFloatAsState(if (open) 180f else 0f,
        if (LocalMotionEnabled.current) tween(MangaMotion.Content, easing = MangaMotion.EnterEasing) else snap(),
        label = "desktop-menu-chevron")
    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(15.dp).graphicsLayer { rotationZ = turn.value },
        tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun Modifier.desktopFieldFocusLine(focused: Boolean, accent: Color): Modifier {
    val amount = animateFloatAsState(if (focused) 1f else 0f,
        if (LocalMotionEnabled.current) tween(MangaMotion.Quick) else snap(), label = "field-focus")
    val ink = if (LocalPlatformAccessibilityPreferences.current.increaseContrast) MaterialTheme.colorScheme.onSurface else accent
    return drawBehind {
        val progress = amount.value.coerceIn(0f, 1f)
        if (progress > 0f) {
            val width = (size.width - 18.dp.toPx()).coerceAtLeast(0f) * progress
            val stroke = 2.dp.toPx()
            drawRoundRect(ink.copy(alpha = progress), Offset((size.width - width) / 2f, size.height - stroke),
                Size(width, stroke), CornerRadius(stroke / 2f))
        }
    }
}

/** The affordance changes inside its own slot, preserving caret and pointer positions. */
@Composable
internal fun DesktopFieldClear(visible: Boolean, enabled: Boolean, label: String, extent: Dp, onClick: () -> Unit) {
    val motion = LocalMotionEnabled.current
    Box(Modifier.size(extent), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible,
            enter = fadeIn(if (motion) tween(MangaMotion.Quick) else snap()) +
                scaleIn(if (motion) spring(MangaMotion.PressDamping, MangaMotion.PressStiffness) else snap(), initialScale = .82f),
            exit = fadeOut(if (motion) tween(MangaMotion.Quick) else snap()) +
                scaleOut(if (motion) tween(MangaMotion.Quick) else snap(), targetScale = .88f)) {
            MotionIconButton(onClick = onClick, enabled = enabled && visible, tooltip = label,
                modifier = Modifier.size(extent).motionInputGate(visible)) {
                Icon(Icons.Outlined.Close, label, Modifier.size(17.dp))
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
    val searchFocus = LocalDesktopSearchFocus.current
    DisposableEffect(searchFocus, requester, active) {
        if (searchFocus != null && active) searchFocus.field = requester
        onDispose { if (searchFocus != null && searchFocus.field === requester) searchFocus.field = null }
    }
    var scopeOpen by remember { mutableStateOf(false) }
    val inputInteraction = remember { MutableInteractionSource() }
    val focused by inputInteraction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val height = maxOf(40.dp, 22.dp * fontScale + 13.dp)
    val searchLabel = if (!showScopeSelector) "搜索漫画标题" else when (target) {
        SearchTarget.TITLE -> "搜索漫画标题"
        SearchTarget.AUTHOR -> "搜索作者"
        SearchTarget.TAG -> "搜索标签"
        SearchTarget.TRANSLATION_GROUP -> "搜索汉化组名称"
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
        requester.requestFocus()
    }
    val toggleScope = {
        if (!scopeOpen) onOpen()
        scopeOpen = !scopeOpen
    }
    BoxWithConstraints(modifier.height(height)) {
        val showScopeName = showScopeSelector && maxWidth >= 332.dp * fontScale
        val hintRoom = maxWidth >= 416.dp * fontScale
        val keyHint = when {
            focused && searchFocus?.results != null -> "F6 结果"
            !focused && query.isBlank() -> "Ctrl+K"
            else -> null
        }
        val fieldPaper by animateColorAsState(if (focused) lerp(colors.surface, colors.primary, .085f)
            else colors.surfaceContainerLow, if (LocalMotionEnabled.current) tween(MangaMotion.Quick) else snap(),
            label = "desktop-search-paper")
        Box(Modifier.matchParentSize().clip(RoundedCornerShape(12.dp)).background(fieldPaper)
            .desktopFieldFocusLine(focused && active, colors.primary))
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
                    .desktopSearchPaneKey(searchFocus?.results)
                    .focusRequester(requester).onFocusChanged { if (it.isFocused) onOpen() }
                    .semantics { contentDescription = searchLabel },
                decorationBox = { input ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text(searchLabel, style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        input()
                    }
                })
            if (hintRoom) Box(Modifier.width(54.dp * fontScale).clearAndSetSemantics {},
                contentAlignment = Alignment.Center) {
                MotionSwap(keyHint, scaleContent = false) { hint ->
                    if (hint != null) DesktopShortcutHint(hint)
                }
            }
            DesktopFieldClear(query.isNotEmpty() || copyOnly, active, "清空搜索", 40.dp) {
                scopeOpen = false
                onClear()
                requester.requestFocus()
            }
            if (showScopeSelector) {
                Box {
                    if (showScopeName) {
                        val interaction = remember { MutableInteractionSource() }
                        Row(Modifier.clip(RoundedCornerShape(9.dp))
                            .background(if (scopeOpen) colors.primaryContainer else colors.surface)
                            .clickable(enabled = active, role = Role.Button, interactionSource = interaction,
                                indication = null, onClick = toggleScope).motionPress(interaction, enabled = active)
                            .semantics { contentDescription = "搜索范围，当前" + target.scopeTitle }
                            .heightIn(min = 32.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            if (copyOnly || target == SearchTarget.TRANSLATION_GROUP) BodySourceMark("copy", "拷贝", Modifier.size(10.dp))
                            Text(target.scopeTitle, style = MaterialTheme.typography.labelMedium, color = colors.onSurface, maxLines = 1)
                            DesktopMenuChevron(scopeOpen)
                        }
                    } else MotionIconButton(onClick = toggleScope, enabled = active,
                        modifier = Modifier.size(44.dp).semantics { contentDescription = "搜索范围：" + target.scopeTitle },
                        tooltip = "搜索范围：" + target.scopeTitle) {
                        Box(Modifier.size(32.dp).background(
                            if (scopeOpen) colors.primaryContainer else colors.surface.copy(alpha = 0f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center) {
                            MotionSwap(target, Modifier.align(Alignment.Center)) { scope ->
                                Icon(scope.scopeIcon, null, Modifier.size(18.dp))
                            }
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
    val window = LocalMangaWindow.current
    val compact = window.shortHeight || window.width < 560.dp
    Text(title, modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
        fontSize = if (compact) 23.sp else 28.sp, lineHeight = if (compact) 29.sp else 36.sp, letterSpacing = (-.35f).sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun desktopTabContentWidth(labels: List<String>): Dp {
    val style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val measure = rememberTextMeasurer()
    val density = LocalDensity.current
    return with(density) {
        val longest = labels.maxOfOrNull { measure.measure(it, style, maxLines = 1).size.width } ?: 0
        maxOf(48.dp, longest.toDp() + 22.dp) * labels.size
    }
}

@Composable
internal fun DesktopSectionTabs(selected: Int, labels: List<String>, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier, minimumWidth: Dp? = null) {
    if (labels.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val contrast = LocalPlatformAccessibilityPreferences.current.increaseContrast
    val minimum = minimumWidth ?: desktopTabContentWidth(labels)
    val scroll = rememberScrollState()
    val motion = LocalMotionEnabled.current
    BoxWithConstraints(modifier) {
        val viewport = if (constraints.hasBoundedWidth) maxWidth else minimum
        val rail = maxOf(minimum, viewport)
        val overflow = rail > viewport
        val density = LocalDensity.current
        if (overflow) LaunchedEffect(selected, viewport, rail, density, labels.size) {
            val slot = rail / labels.size
            val leading = with(density) { (slot * selected.coerceIn(labels.indices)).roundToPx() }
            val trailing = with(density) { (slot * (selected.coerceIn(labels.indices) + 1) - viewport).roundToPx() }
            val target = scroll.value.coerceIn(trailing.coerceAtMost(leading), leading).coerceAtLeast(0)
            if (target != scroll.value) {
                if (motion) scroll.animateScrollTo(target) else scroll.scrollTo(target)
            }
        }
        Box(if (overflow) Modifier.fillMaxWidth().horizontalScroll(scroll) else Modifier) {
            MotionTabs(selected, labels, onSelect, Modifier.width(rail), treatment = MotionTabTreatment.Soft,
                fullWidthIndicator = true, controlHeight = 36.dp, fontSize = 14.sp,
                track = colors.surfaceContainerLow, foreground = if (contrast) colors.onSurface else colors.primary,
                indicator = if (contrast) colors.primaryContainer else colors.surfaceContainerHighest,
                selectedFontWeight = FontWeight.SemiBold, unselectedFontWeight = FontWeight.Normal)
        }
    }
}

@Composable
internal fun DesktopDiscoveryHeader(
    title: String, searching: Boolean, editingSearch: Boolean, tabLabels: List<String>, selectedTab: Int, onSelectTab: (Int) -> Unit,
    onEnterSearch: () -> Unit, onLeaveSearch: () -> Unit, navigation: @Composable () -> Unit, input: @Composable (Modifier) -> Unit,
    recent: @Composable () -> Unit, ranking: @Composable () -> Unit, modifier: Modifier = Modifier,
) {
    val motion = LocalMotionEnabled.current
    val inputContent = rememberUpdatedState(input)
    // Move the existing text field (selection, composition and focus) across breakpoint rows.
    val field = remember { movableContentOf<Modifier> { bounds -> inputContent.value(bounds) } }
    val displayed = if (searching) "搜索结果" else title
    val tabContentWidth = desktopTabContentWidth(tabLabels)
    BoxWithConstraints(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).pointerInput(Unit) {}) {
        val window = LocalMangaWindow.current
        val scale = window.fontScale.coerceAtLeast(1f)
        val inset = MangaLayout.sidePadding(maxWidth)
        val available = (maxWidth - inset * 2).coerceAtLeast(1.dp)
        val tabWidth = (tabContentWidth + 8.dp).coerceAtMost(available)
        val titleBudget = 132.dp * scale + 78.dp
        val compact = available < titleBudget + 240.dp * scale
        val compactEditor = compact && editingSearch
        val hideBrowseTools = searching || compactEditor
        val inlineTabs = available >= titleBudget + tabWidth + if (compact) 44.dp else 290.dp * scale
        var returnToSearchButton by remember(compact) { mutableStateOf(false) }
        val leaveSearch = {
            returnToSearchButton = compact
            onLeaveSearch()
        }
        var escapePressed by remember(editingSearch) { mutableStateOf(false) }
        val tabs: @Composable () -> Unit = {
            DesktopSectionTabs(selectedTab, tabLabels, { index ->
                if (index != selectedTab || editingSearch) onSelectTab(index)
            }, Modifier.width(tabWidth).motionInputGate(!hideBrowseTools), minimumWidth = tabContentWidth)
        }
        Column(Modifier.fillMaxWidth().semantics { paneTitle = if (compactEditor && !searching) "搜索漫画" else displayed }
            .onKeyEvent { event ->
                // An IME or scope popup gets the first chance to handle Escape.
                if (!editingSearch || event.key != Key.Escape || event.isAltPressed || event.isCtrlPressed ||
                    event.isShiftPressed || event.isMetaPressed) return@onKeyEvent false
                if (event.type == KeyEventType.KeyDown) escapePressed = true
                else if (event.type == KeyEventType.KeyUp && escapePressed) {
                    escapePressed = false
                    leaveSearch()
                } else return@onKeyEvent false
                true
            }.padding(horizontal = inset, vertical = if (window.shortHeight) 5.dp else 9.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = if (window.shortHeight) 40.dp else 46.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (hideBrowseTools) MotionIconButton(onClick = leaveSearch, modifier = Modifier.size(36.dp),
                    tooltip = "返回发现，保留搜索") {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回发现", Modifier.size(19.dp))
                } else navigation()
                if (compactEditor) field(Modifier.weight(1f)) else {
                    DesktopPageHeading(displayed, Modifier.weight(1f))
                    if (inlineTabs && !hideBrowseTools) tabs()
                    if (compact) {
                        val trigger = remember { FocusRequester() }
                        MotionIconButton(onClick = onEnterSearch, modifier = Modifier.size(36.dp).focusRequester(trigger),
                            tooltip = "搜索 · Ctrl+K") { Icon(Icons.Outlined.Search, "搜索漫画", Modifier.size(19.dp)) }
                        LaunchedEffect(returnToSearchButton) {
                            if (returnToSearchButton) { trigger.requestFocus(); returnToSearchButton = false }
                        }
                    } else {
                        val fieldBudget = (available - titleBudget - if (inlineTabs && !searching) tabWidth else 0.dp).coerceAtLeast(1.dp)
                        val fieldWidth = animateDpAsState((if (searching) (available * .60f).coerceAtMost(620.dp)
                            else (available * .43f).coerceIn(220.dp, 470.dp)).coerceAtMost(fieldBudget),
                            animationSpec = if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else snap(),
                            label = "workspace-search-width")
                        field(Modifier.layout { measurable, bounds ->
                            val width = fieldWidth.value.coerceAtMost(fieldBudget).roundToPx().coerceIn(bounds.minWidth, bounds.maxWidth)
                            val body = measurable.measure(bounds.copy(minWidth = width, maxWidth = width))
                            layout(body.width, body.height) { body.placeRelative(0, 0) }
                        })
                    }
                    recent()
                }
            }
            if (!inlineTabs) AnimatedVisibility(!hideBrowseTools,
                enter = expandVertically(if (motion) tween(MangaMotion.Content) else snap()) + fadeIn(if (motion) tween(MangaMotion.Quick) else snap()),
                exit = shrinkVertically(if (motion) tween(MangaMotion.Quick) else snap()) + fadeOut(if (motion) tween(MangaMotion.Quick) else snap())) {
                Column(Modifier.fillMaxWidth().padding(top = if (window.shortHeight) 3.dp else 7.dp)) {
                    if (available >= tabWidth + 470.dp * scale) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            tabs()
                            Box(Modifier.weight(1f).motionInputGate(!hideBrowseTools)) { ranking() }
                        }
                    } else {
                        tabs()
                        if (!hideBrowseTools) ranking()
                    }
                }
            } else if (!hideBrowseTools) ranking()
        }
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
