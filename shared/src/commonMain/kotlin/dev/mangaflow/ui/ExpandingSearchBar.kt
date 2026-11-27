package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

import dev.mangaflow.platform.*

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.platform.LocalWindowInfo
import dev.mangaflow.ui.motion.MotionBackHandler
import dev.mangaflow.ui.motion.MotionBackState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.SearchTarget
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.motionInputGate
import kotlinx.coroutines.flow.first

private val SearchShellShape = RoundedCornerShape(24.dp)

private fun searchRevealWindow(progress: Float, from: Float, to: Float): Float {
    val t = ((progress - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * beUI Morphing Search: one root-level shell, with a separately revealed, full-size foreground.
 * The fixed right anchor and inset reveal also follow its Morph Popover. See THIRD_PARTY_NOTICES.
 */
@Composable
internal fun ExpandingSearchBar(
    expanded: Boolean,
    query: String,
    active: Boolean,
    backdrop: HazeState,
    onExpandedChange: (Boolean) -> Unit,
    onPresenceChange: (Boolean) -> Unit,
    onForegroundState: (State<Float>) -> Unit,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索",
    searchTarget: SearchTarget? = null,
    scopeCopyOnly: Boolean = false,
    onSearchTarget: (SearchTarget) -> Unit = {},
    translationGroupAvailable: Boolean = true,
    showEmptyResults: Boolean = false,
    focusInputOnExpand: Boolean = true,
    titleAndTagsOnly: Boolean = false,
    collapsedLabel: String? = null,
    results: @Composable (maxHeight: Dp, active: Boolean) -> Unit,
) {
    val motion = LocalMotionEnabled.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    val requester = remember { FocusRequester() }
    val back = remember { MotionBackState() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val searchInteraction = remember { MutableInteractionSource() }
    var retainedQuery by remember { mutableStateOf(query) }
    var inputFocused by remember { mutableStateOf(false) }
    var inputPlaced by remember { mutableStateOf(false) }
    var foregroundHeight by remember { mutableStateOf(44.dp) }
    var scopeExpanded by remember { mutableStateOf(false) }
    // A window resize or a return from another app must not reopen a dismissed keyboard.
    var focusPending by remember(expanded, focusInputOnExpand) {
        mutableStateOf(expanded && focusInputOnExpand && searchTarget != SearchTarget.TAG)
    }
    val hasScope = searchTarget != null
    val inputEndInset = if (hasScope) 84.dp else 44.dp
    val displayedQuery = if (expanded) query else retainedQuery
    val shell = updateTransition(expanded, label = "search-shell")
    val reveal = shell.animateFloat(
        transitionSpec = {
            if (motion) spring(MangaMotion.SearchDamping, MangaMotion.SearchStiffness) else snap()
        }, label = "search-morph",
    ) { if (it) 1f else 0f }
    val present = expanded || shell.currentState || shell.targetState || shell.isRunning
    val backRemaining = remember(back) { derivedStateOf { 1f - back.progress.value.coerceIn(0f, 1f) } }
    val progress = remember(reveal, backRemaining) {
        derivedStateOf { (reveal.value * backRemaining.value).coerceIn(0f, 1f) }
    }
    val visibleForeground = remember(progress) {
        derivedStateOf { searchRevealWindow(progress.value, .10f, .82f) }
    }
    val obscuredToolbar = remember(progress) {
        derivedStateOf { searchRevealWindow(progress.value, 0f, .50f) }
    }
    SideEffect {
        if (expanded) retainedQuery = query
        onPresenceChange(present)
        // The stationary toolbar can return as soon as the last visible ink clears the trigger.
        onForegroundState(obscuredToolbar)
    }

    LaunchedEffect(expanded, active) {
        if (!expanded || !active) scopeExpanded = false
        if ((!expanded || !active) && inputFocused) {
            keyboard?.hide()
            focus.clearFocus(force = true)
        }
    }
    LaunchedEffect(present) {
        if (!present) {
            back.settling?.cancel()
            back.completed = false
            back.progress.snapTo(0f)
        }
    }
    val dismiss = {
        scopeExpanded = false
        keyboard?.hide()
        focus.clearFocus(force = true)
        onExpandedChange(false)
    }
    // The IME owns its first back. The next back previews this shell, not the route beneath it.
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    MotionBackHandler(active && expanded && !imeVisible && !scopeExpanded, state = back, onBack = dismiss)
    MotionBackHandler(active && expanded && scopeExpanded, state = null, onBack = { scopeExpanded = false })

    // Only the overlay takes IME insets. The toolbar and browsing content keep their layout.
    BoxWithConstraints(modifier.imePadding()) {
        val endInset = 8.dp
        val availableWidth = (maxWidth - endInset).coerceAtLeast(44.dp)
        val minPanelWidth = minOf(availableWidth, inputEndInset + 16.dp + 64.dp)
        val panelWidth = (maxWidth - 64.dp).coerceIn(minPanelWidth, minOf(availableWidth, 448.dp))
        val resultsRoom = (maxHeight - 64.dp).coerceIn(0.dp, 360.dp)
        // An empty title/author query is exactly the toolbar height, without an empty result gutter.
        val inputOnly = expanded && query.isBlank() && searchTarget != SearchTarget.TAG && !scopeCopyOnly && !showEmptyResults
        val panelHeight = if (inputOnly) 44.dp else foregroundHeight.coerceIn(44.dp, 44.dp + resultsRoom)
        // Only incoming results change the fully open height. A single reversible extension
        // owns the trigger, horizontal opening, lower edge and real pointer clipping.
        val contentHeight = animateDpAsState(panelHeight,
            if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else snap(),
            label = "search-results-height")
        val exposedWidth = remember(progress, panelWidth) {
            derivedStateOf { 44.dp + (panelWidth - 44.dp) * progress.value }
        }
        // Measure the glass and its clipped foreground from the same live dimensions.
        val shellBounds = Modifier.layout { measurable, constraints ->
            val w = exposedWidth.value.roundToPx().coerceIn(constraints.minWidth, constraints.maxWidth)
            val lowerEdge = searchRevealWindow(progress.value, .24f, 1f)
            val h = (44.dp + (contentHeight.value - 44.dp) * lowerEdge).roundToPx()
                .coerceIn(constraints.minHeight, constraints.maxHeight)
            val child = measurable.measure(Constraints.fixed(w, h))
            layout(w, h) { child.place(0, 0) }
        }
        val requiredTextWidth = minOf(96.dp, (panelWidth - inputEndInset - 16.dp).coerceAtLeast(0.dp))
        LaunchedEffect(focusPending, active, inputPlaced, windowFocused, scopeExpanded, searchTarget, exposedWidth,
            inputEndInset, requiredTextWidth) {
            if (focusPending && active && inputPlaced && windowFocused && !scopeExpanded &&
                searchTarget != SearchTarget.TAG && requiredTextWidth > 0.dp) {
                snapshotFlow {
                    !back.gesturing && !back.completed && exposedWidth.value - inputEndInset >= requiredTextWidth
                }.first { it }
                if (requester.requestFocus()) {
                    focusPending = false
                    keyboard?.show()
                }
            }
        }
        val closeScope = { scopeExpanded = false }
        if (expanded && active) Box(Modifier.fillMaxSize().padding(top = if (scopeExpanded) 0.dp else 44.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClickLabel = if (scopeExpanded) "收起搜索范围" else "关闭搜索",
                onClick = if (scopeExpanded) closeScope else dismiss))

        // This shell never belongs to the toolbar's measurement or to its own Haze source.
        LiquidGlassSurface(
            state = backdrop,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = endInset).then(shellBounds)
                .graphicsLayer {
                    alpha = progress.value
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
            surfaceShape = SearchShellShape,
            role = GlassSurfaceRole.SEARCH,
            interactionSource = if (expanded && active) searchInteraction else null,
            softCard = true,
            materialExpansion = { progress.value },
        ) {}

        val scopeInView by remember(exposedWidth, panelWidth) {
            derivedStateOf { exposedWidth.value >= minOf(88.dp, panelWidth) && panelWidth > 44.dp }
        }
        Box(Modifier.align(Alignment.TopEnd).padding(end = endInset).then(shellBounds).clip(SearchShellShape)
            .then(if (expanded && active && !scopeExpanded) Modifier.pointerInput(Unit) {} else Modifier)) {
            // Keep the full results measured at their final width. A real end-aligned viewport
            // reveals the text, rows and scope together and clips their pointer bounds in RTL too.
            Box(Modifier.align(Alignment.TopEnd).clipToBounds()
                .layout { measurable, constraints ->
                    val fullWidth = panelWidth.roundToPx()
                    val content = measurable.measure(Constraints(
                        minWidth = fullWidth, maxWidth = fullWidth, maxHeight = (44.dp + resultsRoom).roundToPx()))
                    val liveWidth = exposedWidth.value.roundToPx().coerceAtMost(constraints.maxWidth)
                    layout(liveWidth, constraints.maxHeight) {
                        // The optical shell may keep settling after the toolbar accepts taps again.
                        if (present && (expanded || visibleForeground.value > .02f))
                            content.placeRelative(liveWidth - content.width, 0)
                    }
                }
                .motionInputGate(expanded && active)
                .graphicsLayer { alpha = visibleForeground.value }) {
                Column(Modifier.width(panelWidth).heightIn(max = 44.dp + resultsRoom)
                    .onSizeChanged { foregroundHeight = with(density) { it.height.toDp() } }
                    .semantics { paneTitle = placeholder }.pointerInput(Unit) {}) {
                    val colors = MaterialTheme.colorScheme
                    val hasResults = !inputOnly && foregroundHeight > 44.dp
                    Row(Modifier.width(panelWidth).height(44.dp).drawWithCache {
                        val edge = Brush.verticalGradient(listOf(colors.onSurface.copy(alpha = 0f),
                            colors.onSurface.copy(alpha = .055f)), startY = size.height - 3.dp.toPx(), endY = size.height)
                        onDrawBehind {
                            if (hasResults) {
                                val gutter = minOf(16.dp.toPx(), size.width / 2f)
                                drawRect(edge, Offset(gutter, size.height - 3.dp.toPx()),
                                    Size(size.width - 2f * gutter, 3.dp.toPx()))
                            }
                        }
                    }.padding(start = 16.dp, end = inputEndInset),
                        verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(
                            value = displayedQuery,
                            onValueChange = { if (expanded && active) onQuery(it) },
                            enabled = expanded && active && !scopeExpanded,
                            interactionSource = searchInteraction,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleMedium.copy(color = colors.onSurface, fontWeight = FontWeight.Medium),
                            cursorBrush = SolidColor(colors.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                keyboard?.hide()
                                focus.clearFocus(force = true)
                                if (expanded && active && query.isNotBlank()) onSearch()
                            }),
                            modifier = Modifier.weight(1f).fillMaxHeight().focusRequester(requester)
                                .onFocusChanged {
                                    inputFocused = it.isFocused
                                    if (it.isFocused) focusPending = false
                                }
                                .onPlaced { inputPlaced = true }
                                .semantics { contentDescription = placeholder },
                            decorationBox = { input ->
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                    if (displayedQuery.isEmpty()) Text(placeholder,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = colors.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    input()
                                }
                            },
                        )
                    }
                    results(if (present) resultsRoom else 0.dp, expanded && active && !scopeExpanded)
                }
                if (scopeExpanded && expanded && active) Box(Modifier.matchParentSize()
                    .pointerInput(Unit) { detectTapGestures { closeScope() } })
                if (searchTarget != null && present) SearchScopeControl(
                    target = searchTarget, expanded = scopeExpanded,
                    enabled = expanded && active && scopeInView, copyOnly = scopeCopyOnly,
                    translationGroupAvailable = translationGroupAvailable,
                    titleAndTagsOnly = titleAndTagsOnly,
                    backdrop = backdrop, maxWidth = (panelWidth - 52.dp).coerceAtLeast(36.dp),
                    onExpandedChange = {
                        if (it) {
                            if (inputFocused && imeVisible && focusInputOnExpand) focusPending = true
                            keyboard?.hide()
                            focus.clearFocus(force = true)
                        }
                        scopeExpanded = it
                    },
                    onTarget = { target ->
                        if (target != searchTarget) focusPending = focusInputOnExpand && target != SearchTarget.TAG
                        onSearchTarget(target)
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 44.dp)
                        .motionInputGate(expanded && active && scopeInView),
                )
            }
        }
        MotionIconButton(
            onClick = { if (expanded) dismiss() else onExpandedChange(true) },
            enabled = active,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp).size(44.dp)
                .semantics {
                    contentDescription = if (expanded) "关闭搜索" else collapsedLabel
                        ?: if (searchTarget == null) placeholder else "搜索漫画、作者"
                    stateDescription = if (expanded) "已展开" else "已收起"
                },
        ) {
            SearchTriggerGlyph(progress)
        }
    }
}

/** The magnifier's ring folds into one arm of the close mark, including during a back swipe. */
@Composable
private fun SearchTriggerGlyph(progress: State<Float>) {
    val ink = LocalContentColor.current
    Canvas(Modifier.size(22.dp)) {
        val t = searchRevealWindow(progress.value, 0f, .83f)
        val c = 9.5f + 2.5f * t
        val radius = 6.45f + 2.15f * t
        val thickness = 6.45f * (1f - t)
        val stroke = 2f
        scale(size.width / 24f, size.height / 24f, Offset.Zero) {
            rotate(45f, Offset(c, c)) {
                if (thickness > .12f) drawOval(ink, Offset(c - radius, c - thickness),
                    Size(2f * radius, 2f * thickness), style = Stroke(stroke))
                else drawLine(ink, Offset(c - radius, c), Offset(c + radius, c), stroke, StrokeCap.Round)
                val tip = radius + 6.4f * (1f - t)
                if (tip > radius) drawLine(ink, Offset(c + radius, c), Offset(c + tip, c), stroke, StrokeCap.Round)
                val cross = 8.6f * searchRevealWindow(t, .32f, 1f)
                if (cross > 0f) drawLine(ink, Offset(c, c - cross), Offset(c, c + cross), stroke, StrokeCap.Round)
            }
        }
    }
}

