package dev.mangaflow.reader

import dev.mangaflow.ui.mangaNumberText

import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.LocalGlassAccessibilitySettings
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.GlassSurfaceRole
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.glass.softSurfaceShadow
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.MotionSwitchThumb
import kotlin.math.roundToInt

/** Reader controls follow the app theme, independently from the artwork background preference. */
@Composable
internal fun ReaderSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.softSurfaceShadow(shape, colors.surface.luminance() > .5f),
        shape = shape,
        color = lerp(colors.background, colors.surface, .8f),
        contentColor = colors.onSurface,
        tonalElevation = 0.dp,
    ) { content() }
}

@Composable
internal fun ReaderDialogSurface(
    modifier: Modifier = Modifier,
    backdrop: HazeState,
    dialogWindow: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (dialogWindow) {
        ConfigureReaderDialogWindow(.12f)
        if (platformKind == PlatformKind.DESKTOP) ReaderSurface(modifier, content = content)
        else LiquidGlassSurface(backdrop, modifier, role = GlassSurfaceRole.PANEL, softCard = true) { content() }
    } else {
        // The workspace supplies one continuous matte surface behind all three inspector views.
        Box(modifier) { content() }
    }
}

/** Icon actions can return to the page without stealing focus from a settings editor. */
internal val LocalReaderActionFinished = staticCompositionLocalOf<() -> Unit> { {} }

/** Watch the artwork viewport itself, not an invisible overlay that could intercept a drag or wheel. */
internal fun Modifier.readerMouseEdgeReveal(topWidth: Dp, dockWidth: Dp, onReveal: () -> Unit): Modifier =
    pointerInput(topWidth, dockWidth) {
        val edge = 16.dp.toPx()
        val sideClearance = 12.dp.toPx()
        val topHalf = topWidth.toPx() / 2f
        val dockHalf = dockWidth.toPx() / 2f
        awaitPointerEventScope {
            var atEdge = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val mouse = event.changes.firstOrNull { it.type == PointerType.Mouse } ?: continue
                if (event.type == PointerEventType.Exit) { atEdge = false; continue }
                if (event.type != PointerEventType.Enter && event.type != PointerEventType.Move) continue
                val x = mouse.position.x
                val y = mouse.position.y
                val band = edge.coerceAtMost(size.height / 4f)
                val withinWidth = x >= sideClearance && x < size.width - sideClearance
                val fromCenter = kotlin.math.abs(x - size.width / 2f)
                val inside = withinWidth && y >= 0f && y < size.height &&
                    (y < band && fromCenter <= topHalf || y >= size.height - band && fromCenter <= dockHalf)
                // Only an edge entry can change UI state, not every Windows mouse move.
                if (inside && !atEdge && !mouse.pressed && !mouse.previousPressed && !mouse.isConsumed) onReveal()
                atEdge = inside
            }
        }
    }

/** Keep the actual page tree in one slot while an optional desktop inspector opens beside it. */
@Composable
internal fun ReaderWorkspace(
    canDock: Boolean,
    panelVisible: Boolean,
    panelWidth: Dp,
    panel: @Composable () -> Unit,
    content: @Composable (inspectorMounted: Boolean) -> Unit,
) {
    val motion = LocalMotionEnabled.current
    val transition = updateTransition(canDock && panelVisible, label = "reader-inspector")
    val reveal = transition.animateFloat(transitionSpec = {
        if (!motion || !canDock) snap() else if (targetState) tween(MangaMotion.Content, easing = MangaMotion.EnterEasing)
        else tween(MangaMotion.Quick, easing = MangaMotion.ExitEasing)
    }, label = "reader-inspector-surface") { if (it) 1f else 0f }
    val mounted = canDock && (panelVisible || motion && (transition.currentState || transition.isRunning))
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight()) { content(mounted) }
        if (mounted) {
            val side = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1f else -1f
            // A changing width causes every visible strip page to remeasure and rebase its
            // scroll offset. Reserve the final width once and move only the inspector's ink.
            Box(Modifier.width(panelWidth).fillMaxHeight().graphicsLayer {
                val amount = if (motion) reveal.value else 1f
                translationX = side * 18.dp.toPx() * (1f - amount)
                alpha = amount
            }.padding(start = 6.dp, top = 8.dp, end = 8.dp, bottom = 8.dp).motionInputGate(panelVisible)) {
                ReaderSurface(Modifier.fillMaxSize(), RoundedCornerShape(18.dp), content = panel)
            }
        }
    }
}

/** Both edges of the reader use the same visibility transition without moving the actual pages. */
@Composable
internal fun ReaderToolPresence(
    presence: Transition<Boolean>,
    top: Boolean,
    modifier: Modifier,
    content: @Composable (interactive: Boolean) -> Unit,
) {
    val motion = LocalMotionEnabled.current
    val travel = presence.animateFloat(transitionSpec = {
        when {
            !motion -> snap()
            targetState -> spring(dampingRatio = .91f, stiffness = if (top) 780f else 610f)
            else -> spring(dampingRatio = 1f, stiffness = 1_050f)
        }
    }, label = if (top) "reader-title-travel" else "reader-dock-travel") { if (it) 1f else 0f }
    if (!presence.currentState && !presence.targetState && !presence.isRunning) return
    Box(modifier.graphicsLayer {
        val amount = travel.value.coerceIn(0f, 1f)
        val remaining = 1f - amount
        // Keep the insets and the content height fixed even when the exit reverses halfway.
        transformOrigin = TransformOrigin(.5f, if (top) 0f else 1f)
        translationY = (if (top) -11.dp.toPx() else 18.dp.toPx()) * remaining
        scaleX = 1f - (if (top) .012f else .024f) * remaining
        scaleY = 1f - (if (top) .045f else .09f) * remaining
        alpha = 1f - remaining * remaining
    }) { content(presence.targetState) }
}

/** Leave artwork visible between the desktop chapter identity and the workspace tools. */
@Composable
internal fun ReaderHeadingBar(
    desktop: Boolean, backdrop: HazeState, interactive: Boolean,
    identityInteraction: MutableInteractionSource, toolsInteraction: MutableInteractionSource,
    modifier: Modifier, identity: @Composable RowScope.() -> Unit, tools: @Composable RowScope.() -> Unit,
) {
    if (desktop) {
        Row(modifier, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            ReaderHeadingCapsule(backdrop, desktop, interactive, identityInteraction,
                Modifier.weight(1f, fill = false).widthIn(max = 468.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)), identity)
            Spacer(Modifier.width(10.dp))
            ReaderHeadingCapsule(backdrop, desktop, interactive, toolsInteraction, Modifier, tools)
        }
    } else Box(modifier) {
        ReaderHeadingCapsule(backdrop, desktop, interactive, identityInteraction, Modifier.fillMaxWidth()) {
            identity()
            tools()
        }
    }
}

@Composable
private fun ReaderHeadingCapsule(
    backdrop: HazeState, desktop: Boolean, interactive: Boolean, interaction: MutableInteractionSource,
    modifier: Modifier, content: @Composable RowScope.() -> Unit,
) {
    LiquidGlassSurface(backdrop, modifier, role = if (desktop) GlassSurfaceRole.READING_DOCK else GlassSurfaceRole.NAVIGATION,
        cornerRadius = if (desktop) 22.dp else 28.dp, preferSource = true, trackPointer = true,
        interactionSource = interaction, softCard = true) {
        // Keep the hit target and its exit gate inside the rounded glass, including during a fade.
        Row(Modifier.motionInputGate(interactive).hoverable(interaction, enabled = desktop && interactive)
            .heightIn(min = 52.dp).pointerInput(Unit) {}.padding(horizontal = if (desktop) 6.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

internal val ReaderDockActionSize = if (platformKind == PlatformKind.ANDROID) 48.dp else 44.dp

/** Keep the ruler and action menu mounted when the dock moves between one and two rows. */
@Composable
internal fun ReaderDockGroups(desktop: Boolean, modifier: Modifier, progress: @Composable () -> Unit, actions: @Composable () -> Unit) {
    Layout(modifier = modifier.fillMaxWidth().pointerInput(Unit) {}.padding(horizontal = 10.dp, vertical = 4.dp), content = {
        Box { progress() }
        Box { actions() }
    }) { children, bounds ->
        val width = bounds.maxWidth
        val actionSize = ReaderDockActionSize.roundToPx().coerceAtLeast(1)
        val gap = 10.dp.roundToPx()
        val minRuler = (370.dp * fontScale.coerceAtLeast(1f)).roundToPx()
        // Two chapter buttons, overflow and download must fit together. Extra space can show
        // the picture and discussion actions before giving the rest back to the ruler.
        val slots = ((width - minRuler - gap) / actionSize).coerceIn(0, 6)
        val inline = desktop && slots >= 4
        val actionsWidth = if (inline) slots * actionSize else width
        val rulerWidth = if (inline) width - gap - actionsWidth else width
        val ruler = children[0].measure(bounds.copy(minWidth = rulerWidth, maxWidth = rulerWidth, minHeight = 0))
        val actionsHeight = if (inline) bounds.maxHeight else (bounds.maxHeight - ruler.height).coerceAtLeast(0)
        val actionRow = children[1].measure(bounds.copy(minWidth = actionsWidth, maxWidth = actionsWidth,
            minHeight = 0, maxHeight = actionsHeight))
        val height = (if (inline) maxOf(ruler.height, actionRow.height) else ruler.height + actionRow.height)
            .coerceIn(bounds.minHeight, bounds.maxHeight)
        layout(width, height) {
            ruler.placeRelative(0, if (inline) (height - ruler.height) / 2 else 0)
            actionRow.placeRelative(if (inline) rulerWidth + gap else 0,
                if (inline) (height - actionRow.height) / 2 else ruler.height)
        }
    }
}

/** Keeps Material's seek, keyboard and accessibility behavior, with a quiet track and stable thumb slot. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderSeekBar(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    label: String,
    interactions: MutableInteractionSource = remember { MutableInteractionSource() },
    valueLabel: ((Float) -> String)? = null,
    pageCount: Int? = null,
) {
    val colors = MaterialTheme.colorScheme
    val pressed by interactions.collectIsPressedAsState()
    val dragged by interactions.collectIsDraggedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val layoutDirection = LocalLayoutDirection.current
    val lastHoverIndex = (pageCount ?: 0) - 1
    val desktopRuler = platformKind == PlatformKind.DESKTOP && pageCount != null
    val floatingRulerLabel = desktopRuler || (platformKind == PlatformKind.ANDROID && pageCount != null)
    val canInspect = enabled && desktopRuler && lastHoverIndex > 0
    var hoverIndex by remember(canInspect, lastHoverIndex, layoutDirection) { mutableIntStateOf(-1) }
    var hovering by remember(canInspect, lastHoverIndex, layoutDirection) { mutableStateOf(false) }
    val contrast = LocalGlassAccessibilitySettings.current.increaseContrast
    val engaged = enabled && (pressed || dragged)
    val motion = LocalMotionEnabled.current
    val engagement = animateFloatAsState(
        if (engaged) 1f else 0f,
        if (motion) spring(dampingRatio = if (engaged) .9f else 1f,
            stiffness = if (engaged) MangaMotion.PressAttackStiffness else 760f) else snap(),
        label = "reader-slider-engagement",
    )
    val fraction = ((value - valueRange.start) /
        (valueRange.endInclusive - valueRange.start).coerceAtLeast(.001f)).coerceIn(0f, 1f)
    val inspect = canInspect && !engaged && hoverIndex >= 0
    val bubbleFraction = if (inspect) hoverIndex.toFloat() / lastHoverIndex else fraction
    val bubbleShown = engaged || hovering
    val bubbleReveal = animateFloatAsState(if (bubbleShown) 1f else 0f,
        if (motion) tween(MangaMotion.Quick, easing = MangaMotion.EnterEasing) else snap(), label = "reader-ruler-preview")
    val foreground = if (enabled) colors.primary else colors.onSurface.copy(alpha = .30f)
    val previewText = valueLabel?.invoke(value.coerceIn(valueRange)) ?: pageCount?.let { count ->
        val index = if (inspect) hoverIndex else value.roundToInt().coerceIn(0, (count - 1).coerceAtLeast(0))
        "${index + 1} / $count"
    } ?: "${(fraction * 100f).roundToInt()}%"
    // Continuous reading/scrubbing is silent; the preview and release commit are the feedback.
    val density = LocalDensity.current
    val fontScale = density.fontScale.coerceAtLeast(1f)
    val readPointer = if (!canInspect) Modifier else Modifier.pointerInput(lastHoverIndex, layoutDirection) {
        val inset = 12.dp.toPx()
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val mouse = event.changes.firstOrNull { it.type == PointerType.Mouse }
                if (event.type == PointerEventType.Exit || mouse == null || mouse.pressed) {
                    hovering = false
                    if (event.changes.any { it.pressed }) hoverIndex = -1
                    continue
                }
                if (event.type != PointerEventType.Enter && event.type != PointerEventType.Move) continue
                val at = ((mouse.position.x - inset) / (size.width - 2f * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
                hoverIndex = ((if (layoutDirection == LayoutDirection.Rtl) 1f - at else at) * lastHoverIndex).roundToInt()
                hovering = true
            }
        }
    }
    BoxWithConstraints(modifier.height(48.dp).then(readPointer)) {
        val bubbleWidth = minOf(76.dp * fontScale, maxWidth)
        val travel = (maxWidth - 24.dp).coerceAtLeast(0.dp)
        val bubbleLimit = (maxWidth - bubbleWidth).coerceAtLeast(0.dp)
        val thumbX = 12.dp + travel * bubbleFraction
        val bubbleX = (thumbX - bubbleWidth / 2f).coerceIn(0.dp, bubbleLimit)
        val origin = ((thumbX - bubbleX) / bubbleWidth.coerceAtLeast(1.dp)).coerceIn(0f, 1f)
        val bubble: @Composable (Modifier, Float) -> Unit = { placement, pivot ->
            Surface(
                modifier = placement.heightIn(min = 24.dp).graphicsLayer {
                    val amount = bubbleReveal.value.coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(pivot, 1f)
                    alpha = amount
                    scaleX = .87f + amount * .13f
                    scaleY = .72f + amount * .28f
                    if (!desktopRuler) translationY = (1f - amount) * 10.dp.toPx()
                }.clearAndSetSemantics { },
                shape = RoundedCornerShape(50), color = colors.surface, contentColor = colors.onSurface,
                shadowElevation = if (bubbleShown) 2.dp else 0.dp, tonalElevation = 0.dp,
            ) {
                Box(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
                    Text(mangaNumberText(previewText), color = colors.onSurface.copy(alpha = .86f),
                        fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
                }
            }
        }
        if (floatingRulerLabel) {
            val revealing by remember(bubbleReveal) { derivedStateOf { bubbleReveal.value > 0f } }
            if (enabled && (bubbleShown || revealing)) {
                val position = remember(density) { ReaderRulerLabelPosition(with(density) { 4.dp.roundToPx() }) }
                // Keep the anchor on the ruler and measure the label outside the dock's clipped surface.
                Box(Modifier.align(Alignment.TopStart).offset(x = thumbX - .5.dp).width(1.dp).fillMaxHeight()) {
                    Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false)) {
                        bubble(Modifier.padding(3.dp), .5f)
                    }
                }
            }
        } else {
            bubble(Modifier.align(Alignment.TopStart).offset(x = bubbleX, y = (-16).dp).width(bubbleWidth),
                if (layoutDirection == LayoutDirection.Rtl) 1f - origin else origin)
        }
        Slider(
            value = value.coerceIn(valueRange),
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            enabled = enabled,
            valueRange = valueRange,
            interactionSource = interactions,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).align(Alignment.BottomCenter)
                .semantics {
                    contentDescription = label
                    valueLabel?.let { stateDescription = it(value.coerceIn(valueRange)) }
                },
            thumb = {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(24.dp)) {
                        val width = (11f + 7f * engagement.value).dp.toPx()
                        val height = (11f - engagement.value).dp.toPx()
                        if (focused) drawCircle(foreground.copy(alpha = .25f), radius = 11.dp.toPx())
                        val inset = 1.5.dp.toPx()
                        drawRoundRect(colors.surface, Offset(center.x - width / 2f - inset, center.y - height / 2f - inset),
                            Size(width + 2 * inset, height + 2 * inset), CornerRadius(height / 2f + inset))
                        drawRoundRect(foreground, Offset(center.x - width / 2f, center.y - height / 2f),
                            Size(width, height), CornerRadius(height / 2f))
                    }
                }
            },
            track = {
                Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                    val middle = size.height / 2f
                    val start = if (layoutDirection == LayoutDirection.Rtl) size.width else 0f
                    val end = if (layoutDirection == LayoutDirection.Rtl) 0f else size.width
                    val width = (2.5f + engagement.value).dp.toPx()
                    drawLine(colors.onSurface.copy(alpha = if (contrast) .48f else .16f), Offset(start, middle),
                        Offset(end, middle), width, StrokeCap.Round)
                    // Use real page boundaries. Keep even a long chapter's rail quiet.
                    if (pageCount != null && pageCount >= 12 && size.width >= 104.dp.toPx()) {
                        val interval = when {
                            pageCount <= 40 -> 5
                            pageCount <= 80 -> 10
                            else -> ((pageCount + 79) / 80) * 10
                        }
                        for (page in interval until pageCount step interval) {
                            val at = (page - 1f) / (pageCount - 1)
                            if (at <= fraction) continue
                            drawCircle(colors.onSurface.copy(alpha = if (contrast) .68f else .34f),
                                radius = 1.35.dp.toPx(), center = Offset(start + (end - start) * at, middle))
                        }
                    }
                    if (fraction > 0f) drawLine(foreground, Offset(start, middle),
                        Offset(start + (end - start) * fraction, middle), width, StrokeCap.Round)
                    if (inspect && hovering) drawCircle(colors.onSurface.copy(alpha = if (contrast) .80f else .52f),
                        radius = 2.5.dp.toPx(), center = Offset(start + (end - start) * bubbleFraction, middle))
                }
            },
        )
    }
}

private class ReaderRulerLabelPosition(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.center.x - popupContentSize.width / 2
        val above = anchorBounds.top - gap - popupContentSize.height
        val below = anchorBounds.bottom + gap
        val y = if (above >= 0 || below + popupContentSize.height > windowSize.height) above else below
        return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
    }
}

@Composable
internal fun ReaderToggle(
    label: String,
    value: Boolean,
    description: String? = null,
    update: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
            .toggleable(value = value, interactionSource = interaction, indication = null,
                role = Role.Switch, onValueChange = update)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(label, color = colors.onSurface, style = MaterialTheme.typography.bodyMedium)
            if (description != null) Text(description, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 3.dp))
        }
        Box(Modifier.width(44.dp).height(32.dp), contentAlignment = Alignment.Center) {
            MotionSwitchThumb(value, interaction)
        }
    }
}
