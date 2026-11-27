package dev.mangaflow.ui.motion

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import dev.mangaflow.ui.MangaWindowSize
import dev.mangaflow.ui.MangaLayout
import dev.mangaflow.ui.LocalMangaWindow
import dev.mangaflow.ui.DesktopTooltipSession
import dev.mangaflow.ui.LocalDesktopTooltipSession
import dev.mangaflow.ui.mangaOverlayScrim
import dev.mangaflow.platform.monotonicTimeMillis

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.DesktopIconTooltip
import dev.mangaflow.ui.GlassSurfaceRole
import dev.mangaflow.ui.LiquidGlassSurface
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val LocalTabVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

@Composable
fun MangaMotionProvider(content: @Composable () -> Unit) {
    val preferences = rememberPlatformAccessibilityPreferences()
    val tooltips = if (platformKind == PlatformKind.DESKTOP) remember { DesktopTooltipSession() } else null
    CompositionLocalProvider(
        LocalDesktopTooltipSession provides tooltips,
        LocalMotionEnabled provides preferences.motionEnabled,
        LocalTouchExplorationEnabled provides preferences.touchExploration,
        LocalPlatformAccessibilityPreferences provides preferences,
        content = content,
    )
}

/** Delay presentation only: the operation and its result never wait for this clock. */
@Composable
internal fun rememberDelayedBusy(busy: Boolean, delayMillis: Long = MangaMotion.BusyDelay): Boolean {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(busy, delayMillis) {
        shown = false
        if (busy) {
            delay(delayMillis)
            shown = true
        }
    }
    return busy && shown
}

/** A stable layout/hit region; only its drawing layer compresses. Press cancellation restores it. */
@Composable
internal fun Modifier.motionPress(
    interaction: MutableInteractionSource,
    enabled: Boolean = true,
    pressedScale: Float = MangaMotion.SurfacePressScale,
    showKeyboardFocus: Boolean = true,
): Modifier = motionPressFeedback(interaction, enabled, pressedScale, icon = false, desktopIcon = null,
    showKeyboardFocus = showKeyboardFocus)

/** Mouse-origin focus affects drawing only. Real focus and the click action stay with Foundation. */
private class DesktopIconFocus {
    private var focused = false
    private var mouseDown = false
    var pointerFocus by mutableStateOf(false)
        private set

    fun mousePressed(value: Boolean) {
        mouseDown = value
        if (value && focused) pointerFocus = true
    }

    fun focusChanged(value: Boolean) {
        if (focused == value) return
        focused = value
        pointerFocus = value && mouseDown
    }

    fun keyboardPressed() { pointerFocus = false }
}

/** A keyboard can deliver a genuine press and release between two draws. Hold its visual
 * briefly while Foundation retains sole ownership of activation and cancellation. */
@Composable
private fun desktopPressState(source: MutableInteractionSource, enabled: Boolean): State<Boolean> {
    val state = remember(source, enabled) { mutableStateOf(false) }
    if (enabled) LaunchedEffect(source) {
        val active = mutableSetOf<PressInteraction.Press>()
        var started = 0L
        var release: Job? = null
        source.interactions.collect { event ->
            when (event) {
                is PressInteraction.Press -> {
                    release?.cancel()
                    active += event
                    started = monotonicTimeMillis()
                    state.value = true
                }
                is PressInteraction.Release -> if (active.remove(event.press) && active.isEmpty()) {
                    val left = MangaMotion.PressMinimumDuration - (monotonicTimeMillis() - started)
                    if (left <= 0L) state.value = false
                    else release = launch { delay(left); state.value = false }
                }
                is PressInteraction.Cancel -> if (active.remove(event.press) && active.isEmpty()) {
                    release?.cancel()
                    state.value = false
                }
                else -> Unit
            }
        }
    }
    return state
}

private fun DrawScope.drawContactLight(brush: Brush, origin: TransformOrigin, maxRadius: Dp,
    amount: Float, motion: Boolean) {
    if (amount <= .001f || size.minDimension <= 0f) return
    val reach = minOf(size.minDimension * .49f, maxRadius.toPx())
    val x = (size.width * origin.pivotFractionX).coerceIn(reach, size.width - reach)
    val y = (size.height * origin.pivotFractionY).coerceIn(reach, size.height - reach)
    val radius = if (motion) reach * (.85f + .15f * amount) else reach
    withTransform({ translate(x, y); scale(radius, radius, Offset.Zero) }) {
        drawCircle(brush, radius = 1f, center = Offset.Zero, alpha = amount)
    }
}

@Composable
private fun Modifier.motionPressFeedback(
    interaction: MutableInteractionSource,
    enabled: Boolean,
    pressedScale: Float,
    icon: Boolean,
    desktopIcon: DesktopIconFocus?,
    showKeyboardFocus: Boolean = true,
): Modifier {
    val desktop = platformKind == PlatformKind.DESKTOP
    val windowActive = !desktop || LocalWindowInfo.current.isWindowFocused
    val feedbackEnabled = enabled && windowActive
    val interactionPressed by if (desktop) desktopPressState(interaction, feedbackEnabled) else interaction.collectIsPressedAsState()
    val accessibility = if (desktop) LocalPlatformAccessibilityPreferences.current else null
    val hovered = if (desktop) interaction.collectIsHoveredAsState().value else false
    val focused = if (desktop) interaction.collectIsFocusedAsState().value && windowActive &&
        (showKeyboardFocus || accessibility?.increaseContrast == true || accessibility?.touchExploration == true) else false
    val feedbackColor = MaterialTheme.colorScheme.onSurface
    var down by remember(interaction) { mutableStateOf(false) }
    var directPress by remember(interaction) { mutableStateOf(false) }
    var cancelledContact by remember(interaction) { mutableStateOf(false) }
    var contact by remember(interaction) { mutableStateOf(TransformOrigin.Center) }
    var mouseContact by remember(interaction) { mutableStateOf(false) }
    var release by remember(interaction) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val pressed = feedbackEnabled && (directPress || (!down && !cancelledContact && interactionPressed))
    val motion = LocalMotionEnabled.current
    LaunchedEffect(feedbackEnabled) {
        if (!feedbackEnabled) {
            release?.cancel()
            release = null
            directPress = false
            down = false
        }
    }
    val pressure = animateFloatAsState(
        if (pressed) 1f else 0f,
        when {
            !motion || !feedbackEnabled -> snap()
            pressed -> spring(1f, MangaMotion.PressAttackStiffness)
            cancelledContact -> spring(MangaMotion.CancelDamping, MangaMotion.CancelStiffness)
            else -> spring(MangaMotion.PressDamping, MangaMotion.PressStiffness)
        },
        label = "contact-pressure",
    )
    // clickable intentionally delays its Press inside scrolling parents. Observe down without
    // consuming it; Foundation still owns click/long-click/scroll, keyboard and TalkBack.
    val input = if (feedbackEnabled) Modifier.onPreviewKeyEvent {
        if (!down && it.type == KeyEventType.KeyDown) {
            cancelledContact = false
            contact = TransformOrigin.Center
            mouseContact = false
        }
        false
    }.pointerInput(interaction, desktopIcon) {
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val mousePress = first.type == PointerType.Mouse
            if (mousePress && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
            // Inner actions see Main first. Do not compress their enclosing card too.
            val ownDown = awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull { it.id == first.id }
            if (ownDown == null || ownDown.isConsumed) return@awaitEachGesture
            if (mousePress) desktopIcon?.mousePressed(true)
            release?.cancel()
            release = null
            mouseContact = mousePress
            cancelledContact = false
            contact = TransformOrigin((first.position.x / size.width.coerceAtLeast(1)).coerceIn(.06f, .94f),
                (first.position.y / size.height.coerceAtLeast(1)).coerceIn(.06f, .94f))
            val startedAt = monotonicTimeMillis()
            var released = false
            down = true
            directPress = true
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == first.id } ?: break
                    if (event.changes.count { it.pressed } > 1 ||
                        (change.position - first.position).getDistance() > viewConfiguration.touchSlop ||
                        (change.pressed && change.isConsumed && change.position != change.previousPosition) ||
                        change.position.x !in 0f..size.width.toFloat() ||
                        change.position.y !in 0f..size.height.toFloat()) {
                        directPress = false
                        cancelledContact = true
                    }
                    if (!change.pressed) {
                        released = change.previousPressed
                        break
                    }
                } while (true)
            } finally {
                if (mousePress) desktopIcon?.mousePressed(false)
                if (!released) cancelledContact = true
                down = false
                // A quick valid UP can precede the next draw. Keep only its visual attack
                // alive briefly; clickable still commits immediately and owns semantics.
                // Scroll, pointer cancellation and leaving the target never get this hold.
                val remaining = MangaMotion.PressMinimumDuration - (monotonicTimeMillis() - startedAt)
                if (released && directPress && remaining > 0L) {
                    release = scope.launch {
                        delay(remaining)
                        directPress = false
                        release = null
                    }
                } else directPress = false
            }
        }
    } else Modifier
    // Foundation owns hover/focus dispatch; draw those existing states only on desktop.
    val hoverAmount = if (desktop) animateFloatAsState(if (hovered && feedbackEnabled) 1f else 0f,
        if (motion) tween(MangaMotion.Quick, easing = MangaMotion.EnterEasing) else snap(), label = "hover-ink") else null
    // The resting hover lives behind the artwork. Contact and keyboard focus must still
    // be visible over an opaque cover, button or thumbnail drawn by a child Surface.
    val contactBrush = remember(feedbackColor) {
        Brush.radialGradient(0f to feedbackColor.copy(alpha = .16f),
            .48f to feedbackColor.copy(alpha = .095f), 1f to feedbackColor.copy(alpha = 0f),
            center = Offset.Zero, radius = 1f)
    }
    val desktopFeedback = if (desktop) Modifier.drawWithContent {
        if (!feedbackEnabled) {
            drawContent()
            return@drawWithContent
        }
        val hover = hoverAmount?.value ?: 0f
        val contrast = accessibility?.increaseContrast == true
        val glyph = desktopIcon != null
        val focusVisible = focused && (desktopIcon == null || contrast ||
            accessibility?.touchExploration == true || !desktopIcon.pointerFocus)
        val corner = CornerRadius(minOf(12.dp.toPx(), size.minDimension / 2f))
        if (glyph && hover > 0f) {
            drawCircle(feedbackColor.copy(alpha = hover * if (contrast) .14f else .055f),
                radius = minOf(14.dp.toPx(), size.minDimension / 2f))
        } else if (!glyph && (hover > 0f || focused)) {
            val ink = if (focused) if (contrast) .13f else .075f else hover * if (contrast) .09f else .045f
            drawRoundRect(feedbackColor.copy(alpha = ink), cornerRadius = corner)
        }
        drawContent()
        val amount = if (motion) maxOf(if (pressed) .45f else 0f, pressure.value.coerceIn(0f, 1f))
            else if (pressed) 1f else 0f
        drawContactLight(contactBrush, contact, if (glyph) 16.dp else 38.dp, amount, motion)
        if (focusVisible) {
            val stroke = (if (contrast) 2.dp else if (glyph) 1.dp else 1.25.dp).toPx()
            val width = if (glyph && !contrast) minOf(34.dp.toPx(), size.minDimension - stroke) else size.width - stroke
            val height = if (glyph && !contrast) width else size.height - stroke
            if (width > 0f && height > 0f) drawRoundRect(
                feedbackColor.copy(alpha = if (contrast) 1f else if (glyph) .80f else .52f),
                topLeft = Offset((size.width - width) / 2f, (size.height - height) / 2f),
                size = Size(width, height), cornerRadius = if (glyph) CornerRadius(minOf(8.dp.toPx(), height / 2f)) else corner,
                style = Stroke(stroke))
        }
    } else Modifier
    val iconContact = if (icon && !desktop && motion) Modifier.drawBehind {
        val amount = pressure.value.coerceIn(0f, 1f)
        val radius = minOf(15.dp.toPx(), size.minDimension * .39f)
        if (amount > 0f && radius > 0f) {
            // The contact has a fixed footprint; it fades as the glyph springs back.
            val reach = (size.minDimension / 2f - radius).coerceAtLeast(0f)
            val offset = Offset((contact.pivotFractionX - .5f) * reach, (contact.pivotFractionY - .5f) * reach)
            drawCircle(feedbackColor.copy(alpha = .075f * amount), radius, center + offset)
        }
    } else Modifier
    // A wide opaque cover hardly moves under a finger. Put its contact feedback above
    // the artwork and keep the footprint local so the title remains readable.
    val touchSurface = if (!desktop && !icon) Modifier.drawWithContent {
        drawContent()
        val amount = if (motion) maxOf(if (pressed) .38f else 0f, pressure.value.coerceIn(0f, 1f))
            else if (pressed) 1f else 0f
        drawContactLight(contactBrush, contact, 42.dp, amount, motion)
    } else Modifier
    val cursor = if (desktop && enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier
    return then(input).then(cursor).then(desktopFeedback).graphicsLayer {
        val extent = maxOf(size.width, size.height).coerceAtLeast(1f)
        // Pin the image or row to the finger. A wide desktop row travels only a few pixels.
        val compression = minOf((1f - pressedScale).coerceAtLeast(0f), 4.dp.toPx() / extent)
        val scale = if (motion) 1f - pressure.value * compression * (if (mouseContact) .65f else 1f) else 1f
        transformOrigin = contact
        scaleX = scale
        scaleY = scale
        alpha = if (icon && !desktop && !motion && pressed) .72f else 1f
    }.then(iconContact).then(touchSurface)
}

/** beUI PresenceGate: outgoing visuals do not expose controls or handle new input. */
internal fun Modifier.motionInputGate(active: Boolean): Modifier = if (active) this else
    clearAndSetSemantics { }.focusProperties { canFocus = false }.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }

@Composable
internal fun MotionIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tooltip: String? = null,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val desktopIcon = if (platformKind == PlatformKind.DESKTOP) remember { DesktopIconFocus() } else null
    val focusOrigin = if (desktopIcon == null) Modifier else Modifier
        .onFocusChanged { desktopIcon.focusChanged(it.isFocused) }
        .onPreviewKeyEvent { event ->
            // Tab/arrow can leave focus on the same control. A real key still restores its cue.
            if (event.type == KeyEventType.KeyDown) desktopIcon.keyboardPressed()
            false
        }
    Box(modifier.size(48.dp).then(focusOrigin)
        .clickable(enabled = enabled, interactionSource = interaction, indication = null,
            role = Role.Button, onClick = onClick)
        .motionPressFeedback(interaction, enabled, MangaMotion.IconPressScale, icon = true, desktopIcon = desktopIcon),
        contentAlignment = Alignment.Center) {
        Box(Modifier.graphicsLayer { alpha = if (enabled) 1f else .38f },
            contentAlignment = Alignment.Center) { content() }
        DesktopIconTooltip(tooltip, interaction, enabled, showOnFocus = desktopIcon?.pointerFocus != true)
    }
}

/** A glyph can spring into its slot. Labels keep their baseline through a change. */
@Composable
internal fun <T> MotionSwap(
    target: T,
    modifier: Modifier = Modifier,
    scaleContent: Boolean = true,
    content: @Composable (T) -> Unit,
) {
    val motion = LocalMotionEnabled.current
    if (!motion) {
        Box(modifier, contentAlignment = Alignment.Center) { key(target) { content(target) } }
        return
    }
    AnimatedContent(targetState = target, modifier = modifier, contentAlignment = Alignment.Center,
        transitionSpec = {
            val appear = fadeIn(tween(MangaMotion.StateSwapEnter, easing = MangaMotion.EnterEasing))
            val clear = fadeOut(tween(MangaMotion.StateSwapExit, easing = MangaMotion.ExitEasing))
            if (scaleContent) {
                val grow = scaleIn(spring(MangaMotion.PressDamping, MangaMotion.PressStiffness), initialScale = .9f)
                val shrink = scaleOut(tween(MangaMotion.StateSwapExit, easing = MangaMotion.ExitEasing), targetScale = .95f)
                ((appear + grow) togetherWith (clear + shrink)).using(null)
            } else (appear togetherWith clear).using(null)
        }, label = "state-slot",
    ) { value -> Box(Modifier.motionInputGate(value == target), contentAlignment = Alignment.Center) { content(value) } }
}

/** Only tab selection changes the pane. Live data and progress updates keep the current content. */
@Composable
internal fun MotionTabContent(
    selected: Int,
    modifier: Modifier = Modifier,
    soft: Boolean = false,
    content: @Composable (Int) -> Unit,
) = MotionTabContent(selected, modifier, contentKey = { it }, order = { it }, soft = soft, content = content)

/** Keep the leaving selection's data while updates to the current key stay in place. */
@Composable
internal fun <T> MotionTabContent(
    selected: T,
    modifier: Modifier = Modifier,
    contentKey: (T) -> Any,
    order: (T) -> Int,
    fillContent: Boolean = true,
    soft: Boolean = false,
    content: @Composable (T) -> Unit,
) {
    val motion = LocalMotionEnabled.current
    val distance = with(LocalDensity.current) { (if (soft) 10.dp else 18.dp).roundToPx() }
    val layoutDirection = LocalLayoutDirection.current
    AnimatedContent(targetState = selected, contentKey = contentKey, modifier = modifier.clipToBounds(),
        contentAlignment = Alignment.TopStart,
        transitionSpec = {
            val transform = if (!motion) fadeIn(snap()) togetherWith fadeOut(snap())
            else {
                val step = order(targetState).compareTo(order(initialState))
                val direction = if (layoutDirection == LayoutDirection.Rtl) -step else step
                // Full panes cover the backdrop; clear transparent list rows sooner to keep text legible.
                val incoming = fadeIn(tween(if (soft) MangaMotion.ListEnter else MangaMotion.NavigationFade,
                    easing = MangaMotion.EnterEasing)) + slideInHorizontally(
                    spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness)) { direction * distance }
                val outgoing = fadeOut(tween(if (soft) MangaMotion.StateSwapExit else MangaMotion.ListExit,
                    delayMillis = if (soft) 0 else MangaMotion.PaneExitDelay,
                    easing = if (soft) MangaMotion.ListFadeEasing else MangaMotion.ExitEasing)) + slideOutHorizontally(
                    tween(MangaMotion.Exit, easing = MangaMotion.ExitEasing)) { -direction * distance / 2 }
                incoming togetherWith outgoing
            }
            transform.using(if (motion && !fillContent) SizeTransform(clip = false) { _, _ ->
                tween(MangaMotion.Content, easing = MangaMotion.EnterEasing)
            } else null)
        }, label = "tab-content",
    ) { target ->
        val bounds = if (fillContent) Modifier.fillMaxSize() else Modifier
        CompositionLocalProvider(LocalTabVisibilityScope provides this) {
            Box(bounds.motionInputGate(contentKey(target) == contentKey(selected))) { content(target) }
        }
    }
}

/** Keep this call composed while closed. The transition owns the lifetime of its Dialog window. */
@Composable
internal fun MotionDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    maximumWidth: Dp = MangaLayout.PanelWidth,
    onClosed: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val motion = LocalMotionEnabled.current
    val state = remember { MutableTransitionState(false) }
    state.targetState = visible
    val transition = rememberTransition(state, label = "panel-presence")
    val closed = rememberUpdatedState(onClosed)
    if (onClosed != null) LaunchedEffect(state.currentState, state.targetState, state.isIdle) {
        if (state.isIdle && !state.currentState && !state.targetState) closed.value?.invoke()
    }
    val reveal = transition.surfaceReveal(motion)
    val desktop = platformKind == PlatformKind.DESKTOP
    if (state.currentState || state.targetState || !state.isIdle) {
        Dialog(onDismissRequest = { if (visible) onDismissRequest() }, properties = properties) {
            DialogBackdrop(visible, motion, reveal)
            val focus = LocalFocusManager.current
            SideEffect { if (!visible) focus.clearFocus(force = true) }
            // Read the dialog's own constraints: split-screen and the IME can be smaller
            // than the activity window. Existing panels retain their own scroll state.
            BoxWithConstraints(Modifier.safeDrawingPadding().imePadding()) {
            val panelWindow = MangaWindowSize(if (desktop) minOf(maxWidth, maximumWidth) else maxWidth,
                maxHeight, LocalDensity.current.fontScale.coerceAtLeast(1f))
            CompositionLocalProvider(LocalMangaWindow provides panelWindow) {
            Box(Modifier.widthIn(max = maximumWidth).heightIn(max = maxHeight)
                .motionInputGate(visible).graphicsLayer {
                val progress = reveal.value.coerceIn(0f, 1f)
                val rest = 1f - progress
                alpha = surfaceRevealAlpha(progress)
                transformOrigin = TransformOrigin(.5f, if (desktop) .5f else .78f)
                translationY = minOf(size.height * .11f, if (desktop) 4.dp.toPx() else 24.dp.toPx()) * rest
                scaleX = 1f - (if (desktop) .028f else .014f) * rest
                scaleY = scaleX
            }) { content() }
            }
            }
        }
    }
}

/** Share the panel's reversible reveal with the Android window dim, without recomposing its content. */
@Composable
private fun DialogBackdrop(visible: Boolean, motion: Boolean, reveal: State<Float>) {
    val opacity = if (platformKind == PlatformKind.ANDROID && motion) {
        // Native window updates cross a process boundary. Only publish visible changes.
        val level by remember(reveal) { derivedStateOf { (reveal.value.coerceIn(0f, 1f) * 24).roundToInt() } }
        level / 24f
    } else if (visible) 1f else 0f
    MotionDialogWindowPolicy(visible, backgroundDim = mangaOverlayScrim().alpha * opacity)
}

/** A single dismissal path for buttons, outside taps and the platform sheet gesture. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MotionBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(containerColor),
    backdrop: HazeState? = null,
    desktopCloseButton: Boolean = false,
    desktopWidth: Dp = MangaLayout.PanelWidth,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    if (platformKind == PlatformKind.DESKTOP) {
        DesktopSheet(onDismissRequest, modifier, containerColor, contentColor, backdrop, desktopCloseButton, desktopWidth, content)
        return
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val onDismiss by rememberUpdatedState(onDismissRequest)
    var closing by remember { mutableStateOf(false) }
    val dismiss: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch {
                try {
                    sheet.hide()
                    if (!sheet.isVisible) onDismiss()
                } finally { closing = false }
            }
        }
    }
    val window = LocalMangaWindow.current
    val sheetInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom)
    ModalBottomSheet(onDismissRequest = { onDismiss() }, sheetState = sheet,
        // The sheet's anchor must see the whole dialog; cap only its inner content.
        modifier = modifier,
        sheetMaxWidth = MangaLayout.PanelWidth,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        scrimColor = mangaOverlayScrim(),
        containerColor = if (backdrop != null) Color.Transparent else containerColor,
        contentColor = contentColor, tonalElevation = 0.dp,
        // Insets outside a transparent Surface leave an unpainted strip below the frosting.
        // Let the frosted body paint this area, then inset its foreground exactly once.
        contentWindowInsets = { if (backdrop != null) WindowInsets(0, 0, 0, 0) else sheetInsets },
        dragHandle = {
            if (backdrop == null) MotionSheetHandle(contentColor)
        },
    ) {
        if (backdrop != null) LiquidGlassSurface(backdrop,
            Modifier.fillMaxWidth().heightIn(max = (window.height - 16.dp).coerceAtLeast(1.dp)),
            cornerRadius = 0.dp, role = GlassSurfaceRole.PANEL, surfaceColor = containerColor,
            elevation = 0.dp, softCard = true) {
            Column(Modifier.fillMaxWidth().windowInsetsPadding(sheetInsets).motionInputGate(!closing)) {
                MotionSheetHandle(contentColor)
                content(dismiss)
            }
        } else Column(Modifier.fillMaxWidth()
            .heightIn(max = (window.height - 16.dp).coerceAtLeast(1.dp))
            .motionInputGate(!closing)) { content(dismiss) }
    }
}

@Composable
private fun MotionSheetHandle(color: Color) {
    val contrast = LocalPlatformAccessibilityPreferences.current.increaseContrast
    Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 30.dp, height = 3.dp).clip(RoundedCornerShape(2.dp))
            .background(color.copy(alpha = if (contrast) .68f else .30f)))
    }
}


/** The same retained PANEL content, presented as a window-sized desktop dialog. */
@Composable
private fun DesktopSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    containerColor: Color,
    contentColor: Color,
    backdrop: HazeState?,
    closeButton: Boolean,
    contentWidth: Dp,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var visible by remember { mutableStateOf(true) }
    val onDismiss by rememberUpdatedState(onDismissRequest)
    val dismiss: () -> Unit = { visible = false }
    MotionDialog(
        visible = visible,
        onDismissRequest = dismiss,
        onClosed = { if (!visible) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        maximumWidth = contentWidth + 24.dp,
    ) {
        val panelWindow = LocalMangaWindow.current
        val foreground: @Composable () -> Unit = {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val bodyWindow = MangaWindowSize(maxWidth, maxHeight, panelWindow.fontScale)
                CompositionLocalProvider(LocalContentColor provides contentColor, LocalMangaWindow provides bodyWindow) {
                    Column(Modifier.fillMaxWidth().motionInputGate(visible)) {
                        if (closeButton) Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f))
                            MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp), tooltip = "关闭") {
                                Icon(Icons.Outlined.Close, "关闭", Modifier.size(18.dp), tint = contentColor)
                            }
                        }
                        // The optional close row is part of the content height budget.
                        val clearance = if (closeButton) 44.dp else 0.dp
                        Box(Modifier.heightIn(max = (bodyWindow.height - clearance).coerceAtLeast(1.dp))) {
                            Column(Modifier.fillMaxWidth(), content = { content(dismiss) })
                        }
                    }
                }
            }
        }
        val bounds = Modifier.padding(12.dp)
            .widthIn(max = contentWidth).fillMaxWidth()
            .heightIn(max = (panelWindow.height - 24.dp).coerceAtLeast(1.dp))
        if (backdrop != null) LiquidGlassSurface(backdrop, bounds.then(modifier),
            cornerRadius = 24.dp, role = GlassSurfaceRole.PANEL, surfaceColor = containerColor,
            elevation = 0.dp, softCard = true) { foreground() }
        else Surface(modifier = bounds.then(modifier), shape = RoundedCornerShape(24.dp),
            color = containerColor, contentColor = contentColor, tonalElevation = 0.dp,
            shadowElevation = 4.dp) { foreground() }
    }
}
