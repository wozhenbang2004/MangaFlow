package dev.mangaflow.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.platform.LocalWindowInfo
import dev.mangaflow.ui.motion.MotionBackHandler
import dev.mangaflow.ui.motion.MotionBackState
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.CompositingStrategy
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
    onForegroundState: (State<Float>) -> Unit,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索作品、作者或标签",
    results: @Composable (maxHeight: Dp, active: Boolean) -> Unit,
) {
    val motion = LocalMotionEnabled.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    val requester = remember { FocusRequester() }
    val back = remember { MotionBackState() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val foregroundClip = remember { Path() }
    val searchInteraction = remember { MutableInteractionSource() }
    var retainedQuery by remember { mutableStateOf(query) }
    var inputFocused by remember { mutableStateOf(false) }
    var inputPlaced by remember { mutableStateOf(false) }
    var foregroundHeight by remember { mutableStateOf(44.dp) }
    val displayedQuery = if (expanded) query else retainedQuery
    val shell = updateTransition(expanded, label = "search-shell")
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
    val visibleForeground = remember(foreground, back) {
        derivedStateOf { foreground.value * (1f - back.progress.value.coerceIn(0f, 1f)) }
    }
    SideEffect {
        if (expanded) retainedQuery = query
        onPresenceChange(present)
        // Restore the underlying controls with the foreground's finite exit, not the spring's long tail.
        // An opening hides them immediately, including the trigger's lead-in, to avoid icon flashes.
        onForegroundState(visibleForeground)
    }

    LaunchedEffect(expanded, active) {
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
        keyboard?.hide()
        focus.clearFocus(force = true)
        onExpandedChange(false)
    }
    // The IME owns its first back. The next back previews this shell, not the route beneath it.
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    MotionBackHandler(active && expanded && !imeVisible, state = back, onBack = dismiss)

    // Only the overlay takes IME insets. The toolbar and browsing content keep their layout.
    BoxWithConstraints(modifier.imePadding()) {
        val panelWidth = (maxWidth - 64.dp).coerceIn(44.dp, 448.dp)
        val resultsRoom = (maxHeight - 64.dp).coerceIn(0.dp, 360.dp)
        val panelHeight = foregroundHeight.coerceIn(44.dp, 44.dp + resultsRoom)
        val width = shell.animateDp(
            transitionSpec = {
                if (motion) spring(MangaMotion.SearchDamping, MangaMotion.SearchStiffness) else snap()
            }, label = "search-shell-width",
        ) { if (it) panelWidth else 44.dp }
        val height = shell.animateDp(
            transitionSpec = {
                if (motion) spring(MangaMotion.SearchDamping, MangaMotion.SearchStiffness) else snap()
            }, label = "search-shell-height",
        ) { if (it) panelHeight else 44.dp }
        val backRemaining = 1f - back.progress.value.coerceIn(0f, 1f)
        val visibleWidth = 44.dp + (width.value - 44.dp) * backRemaining
        val visibleHeight = 44.dp + (height.value - 44.dp) * backRemaining
        val progress = (reveal.value * backRemaining).coerceIn(0f, 1f)
        val requiredTextWidth = minOf(96.dp, (panelWidth - 60.dp).coerceAtLeast(0.dp))
        LaunchedEffect(expanded, active, inputPlaced, windowFocused, panelWidth) {
            if (expanded && active && inputPlaced && windowFocused) {
                snapshotFlow {
                    val remaining = 1f - back.progress.value.coerceIn(0f, 1f)
                    val liveWidth = 44.dp + (width.value - 44.dp) * remaining
                    val clipLeft = max((panelWidth - 44.dp).value * (1f - visibleForeground.value),
                        (panelWidth - liveWidth).value).coerceAtLeast(16f)
                    !back.gesturing && panelWidth.value - 44f - clipLeft >= requiredTextWidth.value
                }.first { it }
                requester.requestFocus()
                keyboard?.show()
            }
        }
        val endInset = 8.dp
        val topInset = 0.dp

        if (expanded && active) Box(Modifier.fillMaxSize().padding(top = 44.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClickLabel = "关闭搜索", onClick = dismiss))

        // This shell never belongs to the toolbar's measurement or to its own Haze source.
        LiquidGlassSurface(
            state = backdrop,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = topInset, end = endInset)
                .width(visibleWidth.coerceAtLeast(44.dp)).height(visibleHeight.coerceAtLeast(44.dp))
                .graphicsLayer {
                    alpha = progress
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
            cornerRadius = 22.dp + 2.dp * progress,
            role = GlassSurfaceRole.NAVIGATION,
            interactionSource = searchInteraction,
            softCard = true,
            materialExpansion = { progress },
        ) {}

        // Keep the result-position owner composed while closed, but place no hidden hit region.
        Column(Modifier.align(Alignment.TopEnd).padding(end = 8.dp)
            // Actual pointer bounds shrink with the optical shell; a transparent final-width
            // foreground must not keep swallowing taps on the restored tabs/history.
            .clip(RoundedCornerShape(22.dp + 2.dp * progress))
            .motionInputGate(expanded && active)
            .layout { measurable, constraints ->
                val fullWidth = panelWidth.roundToPx()
                val placeable = measurable.measure(constraints.copy(minWidth = fullWidth, maxWidth = fullWidth,
                    minHeight = 0, maxHeight = (44.dp + resultsRoom).roundToPx()))
                val foregroundGone = !expanded && visibleForeground.value <= .02f
                // Use the same threshold as the restored toolbar's input gate. The optical
                // shell may settle longer, but the invisible foreground owns only its trigger.
                val liveWidth = (if (foregroundGone) 44.dp else visibleWidth.coerceAtLeast(44.dp)).roundToPx()
                val liveHeight = (if (foregroundGone) 44.dp else visibleHeight.coerceAtLeast(44.dp)).roundToPx()
                layout(liveWidth, liveHeight) {
                    if (present && !foregroundGone) placeable.placeRelative(liveWidth - placeable.width, 0)
                }
            }
            .width(panelWidth).heightIn(max = 44.dp + resultsRoom)
            .onSizeChanged { foregroundHeight = with(density) { it.height.toDp() } }
            .semantics { paneTitle = "搜索漫画" }
            .pointerInput(Unit) {}
            .graphicsLayer { alpha = visibleForeground.value.coerceIn(0f, 1f) }
            .drawWithContent {
                val p = visibleForeground.value.coerceIn(0f, 1f)
                val shellWidth = visibleWidth.coerceAtLeast(44.dp).toPx()
                val shellHeight = visibleHeight.coerceAtLeast(44.dp).toPx()
                val left = max((size.width - 44.dp.toPx()).coerceAtLeast(0f) * (1f - p),
                    size.width - shellWidth).coerceAtLeast(0f)
                val top = topInset.toPx()
                val radius = (22.dp + 2.dp * progress).toPx()
                foregroundClip.reset()
                foregroundClip.addRoundRect(RoundRect(size.width - shellWidth, top,
                    size.width, top + shellHeight, CornerRadius(radius)))
                // A moving bottom edge can cross any result row; it must keep the shell's corners.
                clipPath(foregroundClip) {
                    clipRect(left = left, top = top, right = size.width,
                        bottom = min(size.height, top + shellHeight)) { this@drawWithContent.drawContent() }
                }
            }) {
            Row(Modifier.width(panelWidth).height(44.dp).padding(start = 16.dp, end = 44.dp),
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
                        .semantics { contentDescription = placeholder },
                    decorationBox = { input ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                            if (displayedQuery.isEmpty()) Text(placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            input()
                        }
                    },
                )
            }
            results(if (present) resultsRoom else 0.dp, expanded && active)
        }
        MotionIconButton(
            onClick = { if (expanded) dismiss() else onExpandedChange(true) },
            enabled = active,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp).size(44.dp)
                .semantics {
                    contentDescription = if (expanded) "关闭搜索" else "搜索漫画、作者"
                    stateDescription = if (expanded) "已展开" else "已收起"
                },
        ) {
            Box(Modifier.size(22.dp).graphicsLayer {
                val response = sin((icon.value * backRemaining).coerceIn(0f, 1f) * Math.PI).toFloat()
                scaleX = 1f - .16f * response
                scaleY = scaleX
            }) {
                Icon(Icons.Outlined.Search, null, Modifier.fillMaxSize().graphicsLayer {
                    alpha = 1f - icon.value * backRemaining
                    rotationZ = -45f * icon.value * backRemaining
                })
                Icon(Icons.Outlined.Close, null, Modifier.fillMaxSize().graphicsLayer {
                    alpha = icon.value * backRemaining
                    rotationZ = 45f * (1f - icon.value * backRemaining)
                })
            }
        }
    }
}

