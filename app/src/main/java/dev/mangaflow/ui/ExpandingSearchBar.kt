package dev.mangaflow.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.motionInputGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    results: @Composable (maxHeight: Dp, active: Boolean) -> Unit,
) {
    val motion = LocalMotionEnabled.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    val requester = remember { FocusRequester() }
    val foregroundClip = remember { Path() }
    val opticalClip = remember { Path() }
    val searchInteraction = remember { MutableInteractionSource() }
    var retainedQuery by remember { mutableStateOf(query) }
    var inputFocused by remember { mutableStateOf(false) }
    var inputPlaced by remember { mutableStateOf(false) }
    var shellOpen by remember { mutableStateOf(expanded) }
    var foregroundHeight by remember { mutableStateOf(52.dp) }
    val displayedQuery = if (expanded) query else retainedQuery
    val shell = updateTransition(shellOpen, label = "search-shell")
    val reveal = shell.animateFloat(
        transitionSpec = {
            if (motion) spring(MangaMotion.SearchDamping, MangaMotion.SearchStiffness) else snap()
        }, label = "search-morph",
    ) { if (it) 1f else 0f }
    val foreground = shell.animateFloat(
        transitionSpec = {
            if (motion) tween(MangaMotion.SearchClip, easing = MangaMotion.SearchEasing) else snap()
        }, label = "search-foreground",
    ) { if (it) 1f else 0f }
    val iconTransition = updateTransition(expanded, label = "search-trigger")
    val icon = iconTransition.animateFloat(
        transitionSpec = {
            if (motion) tween(MangaMotion.Content, easing = MangaMotion.SearchEasing) else snap()
        }, label = "search-icon",
    ) { if (it) 1f else 0f }
    val present = expanded || shell.currentState || shell.targetState || shell.isRunning
    SideEffect {
        if (expanded) retainedQuery = query
        onPresenceChange(present)
    }

    LaunchedEffect(expanded, motion) {
        // Only a fully closed entrance gets the lead-in; an interrupted morph reverses in place.
        if (expanded && motion && !shell.currentState && !shell.isRunning && reveal.value < .001f) {
            delay(MangaMotion.SearchLead)
        }
        shellOpen = expanded
    }
    LaunchedEffect(expanded, active, inputPlaced) {
        if (expanded && active && inputPlaced) {
            snapshotFlow { reveal.value >= .90f && foreground.value >= .98f }.first { it }
            requester.requestFocus()
            keyboard?.show()
        } else if (inputFocused) {
            keyboard?.hide()
            focus.clearFocus(force = true)
        }
    }
    val dismiss = {
        keyboard?.hide()
        focus.clearFocus(force = true)
        onExpandedChange(false)
    }
    BackHandler(active && expanded, onBack = dismiss)

    // Only the overlay takes IME insets. The toolbar and browsing content keep their layout.
    BoxWithConstraints(modifier.imePadding()) {
        val panelWidth = (maxWidth - 64.dp).coerceIn(48.dp, 448.dp)
        val resultsRoom = (maxHeight - 64.dp).coerceIn(0.dp, 360.dp)
        val panelHeight = foregroundHeight.coerceIn(52.dp, 52.dp + resultsRoom)
        val width = shell.animateDp(
            transitionSpec = {
                if (motion) spring(MangaMotion.SearchDamping, MangaMotion.SearchStiffness) else snap()
            }, label = "search-shell-width",
        ) { if (it) panelWidth else 48.dp }
        val height = shell.animateDp(
            transitionSpec = {
                if (motion) spring(MangaMotion.SearchDamping, MangaMotion.SearchStiffness) else snap()
            }, label = "search-shell-height",
        ) { if (it) panelHeight else 48.dp }
        val progress = reveal.value.coerceIn(0f, 1f)
        val endInset = 8.dp
        val topInset = 2.dp * (1f - progress)

        if (present) Box(Modifier.fillMaxSize().padding(top = 52.dp)
            .motionInputGate(expanded && active)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClickLabel = "关闭搜索", onClick = dismiss))

        // This shell never belongs to the toolbar's measurement or to its own Haze source.
        LiquidGlassSurface(
            state = backdrop,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = topInset, end = endInset)
                .width(width.value.coerceAtLeast(48.dp)).height(height.value.coerceAtLeast(48.dp))
                .graphicsLayer { alpha = reveal.value.coerceIn(0f, 1f) }
                .drawWithContent {
                    // Remove this search shell's outer rim without changing the shared material.
                    // The captured backdrop and rounded refraction remain inside the light edge.
                    val inset = .85.dp.toPx()
                    val radius = ((24.dp + 2.dp * progress).toPx() - inset).coerceAtLeast(0f)
                    opticalClip.reset()
                    opticalClip.addRoundRect(RoundRect(inset, inset,
                        (size.width - inset).coerceAtLeast(inset),
                        (size.height - inset).coerceAtLeast(inset), CornerRadius(radius)))
                    clipPath(opticalClip) { this@drawWithContent.drawContent() }
                },
            cornerRadius = 24.dp + 2.dp * progress,
            role = if (displayedQuery.isBlank()) GlassSurfaceRole.CONTROL else GlassSurfaceRole.PANEL,
            interactionSource = searchInteraction,
            elevation = 0.dp,
        ) {}

        // Keep the result-position owner composed while closed, but place no hidden hit region.
        Column(Modifier.align(Alignment.TopEnd).padding(end = 8.dp).width(panelWidth)
            .heightIn(max = 52.dp + resultsRoom)
            .onSizeChanged { foregroundHeight = with(density) { it.height.toDp() } }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { if (present) placeable.place(0, 0) }
            }
            .motionInputGate(expanded && active)
            .semantics { paneTitle = "搜索漫画" }
            .pointerInput(Unit) {}
            .graphicsLayer { alpha = foreground.value.coerceIn(0f, 1f) }
            .drawWithContent {
                val p = foreground.value.coerceIn(0f, 1f)
                val shellWidth = width.value.coerceAtLeast(48.dp).toPx()
                val shellHeight = height.value.coerceAtLeast(48.dp).toPx()
                val left = max((size.width - 48.dp.toPx()).coerceAtLeast(0f) * (1f - p),
                    size.width - shellWidth).coerceAtLeast(0f)
                val top = topInset.toPx()
                val radius = (24.dp + 2.dp * progress).toPx()
                foregroundClip.reset()
                foregroundClip.addRoundRect(RoundRect(size.width - shellWidth, top,
                    size.width, top + shellHeight, CornerRadius(radius)))
                // A moving bottom edge can cross any result row; it must keep the shell's corners.
                clipPath(foregroundClip) {
                    clipRect(left = left, top = top, right = size.width,
                        bottom = min(size.height, top + shellHeight)) { this@drawWithContent.drawContent() }
                }
            }) {
            Row(Modifier.width(panelWidth).height(52.dp).padding(start = 16.dp, end = 48.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = displayedQuery,
                    onValueChange = { if (expanded && active) onQuery(it) },
                    enabled = expanded && active,
                    interactionSource = searchInteraction,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboard?.hide()
                        if (expanded && active && query.isNotBlank()) onSearch()
                    }),
                    modifier = Modifier.weight(1f).fillMaxHeight().focusRequester(requester)
                        .onFocusChanged { inputFocused = it.isFocused }
                        .onPlaced { inputPlaced = true }
                        .semantics { contentDescription = "搜索漫画、作者" },
                    decorationBox = { input ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                            if (displayedQuery.isEmpty()) Text("搜索漫画、作者",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            input()
                        }
                    },
                )
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    if (displayedQuery.isNotEmpty()) MotionIconButton(
                        onClick = { onQuery("") }, enabled = expanded && active,
                        modifier = Modifier.size(40.dp),
                    ) { Icon(Icons.Outlined.Cancel, "清空搜索", Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            results(if (present) resultsRoom else 0.dp, expanded && active)
        }
        MotionIconButton(
            onClick = { if (expanded) dismiss() else onExpandedChange(true) },
            enabled = active,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 8.dp).size(48.dp)
                .semantics {
                    contentDescription = if (expanded) "关闭搜索" else "搜索漫画、作者"
                    stateDescription = if (expanded) "已展开" else "已收起"
                },
        ) {
            Box(Modifier.size(22.dp).graphicsLayer {
                val response = sin(icon.value.coerceIn(0f, 1f) * Math.PI).toFloat()
                scaleX = 1f - .16f * response
                scaleY = scaleX
            }) {
                Icon(Icons.Outlined.Search, null, Modifier.fillMaxSize().graphicsLayer {
                    alpha = 1f - icon.value
                    rotationZ = -45f * icon.value
                })
                Icon(Icons.Outlined.Close, null, Modifier.fillMaxSize().graphicsLayer {
                    alpha = icon.value
                    rotationZ = 45f * (1f - icon.value)
                })
            }
        }
    }
}
