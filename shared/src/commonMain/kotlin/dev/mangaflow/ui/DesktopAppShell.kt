package dev.mangaflow.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.SearchTarget
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionNavigationColumn
import dev.mangaflow.ui.motion.motionPress

internal data class DesktopDestination(val title: String, val icon: ImageVector)

/** Recompose the current glass drawer as persistent navigation; content owns the remaining window. */
@Composable
internal fun DesktopNavigationWorkspace(
    visible: Boolean,
    expanded: Boolean,
    destinations: List<DesktopDestination>,
    selectedIndex: Int,
    version: String,
    backdrop: HazeState,
    onToggle: () -> Unit,
    onNavigate: (Int) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    profileControl: @Composable (compact: Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        if (platformKind != PlatformKind.DESKTOP) {
            content()
        } else {
            val motion = LocalMotionEnabled.current
            val width by animateDpAsState(
                if (expanded) (204.dp + 24.dp * (LocalMangaWindow.current.fontScale - 1f)).coerceAtMost(240.dp) else 64.dp,
                if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else snap(),
                label = "desktop-navigation-width",
            )
            Row(Modifier.fillMaxSize()) {
                if (visible) LiquidGlassSurface(backdrop, Modifier.width(width).fillMaxHeight().padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                    cornerRadius = 20.dp, role = GlassSurfaceRole.DRAWER, elevation = 0.dp) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                            MotionIconButton(onClick = onToggle, modifier = Modifier.size(36.dp),
                                tooltip = if (expanded) "收起导航" else "展开导航") {
                                Icon(Icons.Outlined.Menu, "切换导航宽度", Modifier.size(20.dp))
                            }
                            if (expanded) Text("漫流", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Clip)
                        }
                        val searchInteraction = remember { MutableInteractionSource() }
                        if (expanded) Row(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button, interactionSource = searchInteraction, indication = null, onClick = onSearch)
                            .motionPress(searchInteraction).padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Search, null, Modifier.size(18.dp))
                            Text("搜索", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text("Ctrl K", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                        } else MotionIconButton(onClick = onSearch, modifier = Modifier.size(40.dp), tooltip = "搜索 · Ctrl+K") {
                            Icon(Icons.Outlined.Search, "搜索", Modifier.size(19.dp))
                        }
                        profileControl(!expanded)
                        Spacer(Modifier.height(12.dp))
                        val scroll = rememberScrollState()
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 3.dp)) {
                                MotionNavigationColumn(selected = selectedIndex, itemCount = destinations.size) { index, itemModifier ->
                                    val destination = destinations[index]
                                    val interaction = remember(index) { MutableInteractionSource() }
                                    val current = selectedIndex == index
                                    if (expanded) Row(itemModifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(14.dp))
                                        .clickable(role = Role.Button, interactionSource = interaction, indication = null,
                                            onClick = { onNavigate(index) })
                                        .motionPress(interaction).semantics { selected = current }
                                        .padding(horizontal = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(destination.icon, null, Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (current) 1f else .76f))
                                        Text(destination.title, style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    } else MotionIconButton(onClick = { onNavigate(index) },
                                        modifier = itemModifier.fillMaxWidth().height(46.dp).semantics { selected = current },
                                        tooltip = destination.title) {
                                        Icon(destination.icon, destination.title, Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (current) 1f else .76f))
                                    }
                                }
                            }
                            DesktopVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(5.dp))
                        }
                        if (expanded) Text(version, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    backdrop: HazeState,
    onOpen: () -> Unit,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onTarget: (SearchTarget) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    showScopeSelector: Boolean = true,
) {
    val requester = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    var appliedRequest by remember { mutableIntStateOf(0) }
    var scopeOpen by remember { mutableStateOf(false) }
    val inputInteraction = remember { MutableInteractionSource() }
    val searchLabel = if (showScopeSelector) "搜索漫画、作者或标签" else "搜索漫画标题"
    LaunchedEffect(focusRequest, active) {
        if (active && focusRequest > appliedRequest) {
            withFrameNanos { }
            requester.requestFocus()
            appliedRequest = focusRequest
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
                }
                val targetLabel = when (target) {
                    SearchTarget.TITLE -> "作品"
                    SearchTarget.AUTHOR -> "作者"
                    SearchTarget.TAG -> "标签"
                }
                Box {
                    MotionIconButton(onClick = { scopeOpen = !scopeOpen }, enabled = active,
                        modifier = Modifier.size(36.dp), tooltip = "搜索范围：" + targetLabel) {
                        Box(Modifier.size(22.dp)) {
                            Icon(targetIcon, "搜索范围：" + targetLabel, Modifier.align(Alignment.Center).size(18.dp))
                            if (copyOnly || target != SearchTarget.TITLE)
                                BodySourceMark("copy", "拷贝", Modifier.align(Alignment.BottomEnd).size(8.dp))
                        }
                    }
                    CompactGlassPopover(scopeOpen, backdrop, onDismiss = { scopeOpen = false }, maximumWidth = 200.dp) {
                        listOf(SearchTarget.TITLE to "作品", SearchTarget.AUTHOR to "作者", SearchTarget.TAG to "标签").forEach { (value, label) ->
                            CompactMenuAction(if (value == target) Icons.Outlined.Check else when (value) {
                                SearchTarget.TITLE -> Icons.AutoMirrored.Outlined.MenuBook
                                SearchTarget.AUTHOR -> Icons.Outlined.PersonOutline
                                SearchTarget.TAG -> Icons.AutoMirrored.Outlined.Label
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
                    if (!stacked) tabs(Modifier.widthIn(max = 244.dp))
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
