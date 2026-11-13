package dev.mangaflow.ui.motion

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import dev.mangaflow.ui.MangaWindowSize
import dev.mangaflow.ui.MangaLayout
import dev.mangaflow.ui.LocalMangaWindow

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.ui.GlassSurfaceRole
import dev.mangaflow.ui.LiquidGlassSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val LocalTabVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

@Composable
fun MangaMotionProvider(content: @Composable () -> Unit) {
    val preferences = rememberPlatformAccessibilityPreferences()
    CompositionLocalProvider(
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
): Modifier {
    val interactionPressed by interaction.collectIsPressedAsState()
    var down by remember(interaction) { mutableStateOf(false) }
    var directPress by remember(interaction) { mutableStateOf(false) }
    val pressed = enabled && if (down) directPress else interactionPressed
    val motion = LocalMotionEnabled.current
    val scale = animateFloatAsState(
        if (pressed && motion) pressedScale else 1f,
        if (!motion) snap() else if (pressed) spring(1f, MangaMotion.PressAttackStiffness)
        else spring(MangaMotion.PressDamping, MangaMotion.PressStiffness),
        label = "surface-press",
    )
    // clickable intentionally delays its Press inside scrolling parents. Observe down without
    // consuming it; Foundation still owns click/long-click/scroll, keyboard and TalkBack.
    val input = if (enabled) Modifier.pointerInput(interaction) {
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            down = true
            directPress = true
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == first.id }
                    if (change == null || !change.pressed) break
                    if (event.changes.count { it.pressed } > 1 ||
                        (change.position - first.position).getDistance() > viewConfiguration.touchSlop ||
                        (change.isConsumed && change.position != change.previousPosition) ||
                        change.position.x !in 0f..size.width.toFloat() ||
                        change.position.y !in 0f..size.height.toFloat()) directPress = false
                } while (true)
            } finally {
                directPress = false
                down = false
            }
        }
    } else Modifier
    return then(input).graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
        if (!motion) alpha = if (pressed) .72f else 1f
    }
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
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(modifier.size(48.dp)
        .clickable(enabled = enabled, interactionSource = interaction, indication = null,
            role = Role.Button, onClick = onClick)
        .motionPress(interaction, enabled, MangaMotion.IconPressScale), contentAlignment = Alignment.Center) {
        Box(Modifier.graphicsLayer { alpha = if (enabled) 1f else .38f },
            contentAlignment = Alignment.Center) { content() }
    }
}

/** The caller owns the fixed icon slot. Neither entering nor leaving content changes its bounds. */
@Composable
internal fun <T> MotionSwap(target: T, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val motion = LocalMotionEnabled.current
    AnimatedContent(targetState = target, modifier = modifier, contentAlignment = Alignment.Center,
        transitionSpec = {
            if (motion) {
                ((fadeIn(tween(MangaMotion.Quick)) + scaleIn(spring(.943f, 836f), initialScale = .86f)) togetherWith
                    (fadeOut(tween(90)) + scaleOut(tween(MangaMotion.Quick), targetScale = .92f))).using(null)
            } else (fadeIn(snap()) togetherWith fadeOut(snap())).using(null)
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
    val distance = with(LocalDensity.current) { (if (soft) 24.dp else 10.dp).roundToPx() }
    val layoutDirection = LocalLayoutDirection.current
    AnimatedContent(targetState = selected, contentKey = contentKey, modifier = modifier.clipToBounds(),
        contentAlignment = Alignment.TopStart,
        transitionSpec = {
            val transform = if (!motion) fadeIn(snap()) togetherWith fadeOut(snap())
            else {
                val step = order(targetState).compareTo(order(initialState))
                val direction = if (layoutDirection == LayoutDirection.Rtl) -step else step
                if (soft) {
                    ((fadeIn(tween(MangaMotion.ListEnter, easing = MangaMotion.ListFadeEasing)) +
                        slideInHorizontally(spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness)) { direction * distance }) togetherWith
                        (fadeOut(tween(MangaMotion.ListExit, easing = MangaMotion.ListFadeEasing)) +
                            slideOutHorizontally(tween(MangaMotion.ListEnter, easing = MangaMotion.EnterEasing)) { -direction * distance / 3 }))
                } else ((fadeIn(tween(MangaMotion.Content)) +
                    slideInHorizontally(spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness)) { direction * distance }) togetherWith
                    (fadeOut(tween(MangaMotion.Quick)) +
                        slideOutHorizontally(tween(MangaMotion.Exit, easing = MangaMotion.ExitEasing)) { -direction * distance / 2 }))
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
    content: @Composable () -> Unit,
) {
    val motion = LocalMotionEnabled.current
    val state = remember { MutableTransitionState(false) }
    state.targetState = visible
    val transition = rememberTransition(state, label = "panel-presence")
    val alpha = transition.animateFloat(
        transitionSpec = { tween(if (!motion) 0 else if (targetState) MangaMotion.Content else MangaMotion.Quick) },
        label = "panel-alpha",
    ) { if (it) 1f else 0f }
    val position = transition.animateFloat(
        transitionSpec = {
            if (!motion) snap()
            else if (targetState) spring(MangaMotion.PanelDamping, MangaMotion.PanelStiffness)
            else tween(MangaMotion.Exit, easing = MangaMotion.ExitEasing)
        }, label = "panel-position",
    ) { if (it) 0f else 1f }
    if (state.currentState || state.targetState || !state.isIdle) {
        Dialog(onDismissRequest = { if (visible) onDismissRequest() }, properties = properties) {
            MotionDialogWindowPolicy(visible)
            val focus = LocalFocusManager.current
            SideEffect { if (!visible) focus.clearFocus(force = true) }
            // Read the dialog's own constraints: split-screen and the IME can be smaller
            // than the activity window. Existing panels retain their own scroll state.
            BoxWithConstraints(Modifier.safeDrawingPadding().imePadding()) {
            val panelWindow = MangaWindowSize(maxWidth, maxHeight, LocalDensity.current.fontScale.coerceAtLeast(1f))
            CompositionLocalProvider(LocalMangaWindow provides panelWindow) {
            Box(Modifier.widthIn(max = MangaLayout.PanelWidth).heightIn(max = maxHeight)
                .motionInputGate(visible).graphicsLayer {
                this.alpha = alpha.value
                val offset = if (motion) position.value else 0f
                translationY = 16.dp.toPx() * offset
                scaleX = 1f - .025f * offset
                scaleY = scaleX
            }) { content() }
            }
            }
        }
    }
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
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
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
    ModalBottomSheet(onDismissRequest = { onDismiss() }, sheetState = sheet,
        modifier = modifier.heightIn(max = (window.height - 16.dp).coerceAtLeast(1.dp)),
        sheetMaxWidth = MangaLayout.PanelWidth,
        containerColor = if (backdrop != null) Color.Transparent else containerColor,
        contentColor = contentColor, tonalElevation = 0.dp,
        dragHandle = {
            if (backdrop == null) MotionSheetHandle(contentColor)
        },
    ) {
        if (backdrop != null) LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(),
            cornerRadius = 0.dp, role = GlassSurfaceRole.PANEL, surfaceColor = containerColor,
            elevation = 0.dp, softCard = true) {
            Column(Modifier.fillMaxWidth().motionInputGate(!closing)) {
                MotionSheetHandle(contentColor)
                content(dismiss)
            }
        } else Column(Modifier.fillMaxWidth().motionInputGate(!closing)) { content(dismiss) }
    }
}

@Composable
private fun MotionSheetHandle(color: Color) {
    Box(Modifier.fillMaxWidth().height(26.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp))
            .background(color.copy(alpha = .42f)))
    }
}
