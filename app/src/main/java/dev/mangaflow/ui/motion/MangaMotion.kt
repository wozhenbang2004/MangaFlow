package dev.mangaflow.ui.motion

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** beUI motion roles adapted to Compose. Fixed upstreams and licenses are in THIRD_PARTY_NOTICES. */
internal object MangaMotion {
    const val Quick = 120
    const val Content = 180
    const val Enter = 260
    const val Exit = 180
    val EnterEasing = CubicBezierEasing(.22f, 1f, .36f, 1f)
    val ExitEasing = CubicBezierEasing(.4f, 0f, 1f, 1f)
    // beUI PRESS: stiffness 500, damping 30, mass .6, converted to Compose's unit mass.
    const val PressStiffness = 833f
    const val PressDamping = .866f
    const val PanelStiffness = 840f
    const val PanelDamping = 1.38f
    // Approximate Compose counterpart of beUI Morphing Search's 580ms / .22 bounce role.
    const val SearchStiffness = 230f
    const val SearchDamping = .78f
    const val SearchClip = 320
    const val NavigationStiffness = 520f
    const val NavigationDamping = 1f
    const val NavigationFade = 160
    const val GestureStiffness = 420f
    const val GestureDamping = .86f
    const val SwitchStiffness = 300f
    const val SwitchDamping = .72f
    const val Chrome = 180
    val SearchEasing = CubicBezierEasing(.16f, 1f, .3f, 1f)
    const val ListEnter = 200
    const val ListExit = 140
    val ListFadeEasing = CubicBezierEasing(.2f, 0f, .15f, 1f)
}

internal val LocalMotionEnabled = staticCompositionLocalOf { true }
internal val LocalTabVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

@Composable
internal fun MangaMotionProvider(content: @Composable () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    fun enabled() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    var motionEnabled by remember(resolver) { mutableStateOf(enabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { motionEnabled = enabled() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        motionEnabled = enabled()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    CompositionLocalProvider(LocalMotionEnabled provides motionEnabled, content = content)
}

/** Delay presentation only: the operation and its result never wait for this clock. */
@Composable
internal fun rememberDelayedBusy(busy: Boolean, delayMillis: Long = 160L): Boolean {
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
    pressedScale: Float = .985f,
): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val motion = LocalMotionEnabled.current
    val scale = animateFloatAsState(
        if (enabled && pressed && motion) pressedScale else 1f,
        if (motion) spring(dampingRatio = MangaMotion.PressDamping, stiffness = MangaMotion.PressStiffness) else snap(),
        label = "surface-press",
    )
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value }
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
    IconButton(onClick = onClick, modifier = modifier, enabled = enabled, interactionSource = interaction) {
        Box(Modifier.motionPress(interaction, enabled, pressedScale = .94f), contentAlignment = Alignment.Center) { content() }
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
    content: @Composable (Int) -> Unit,
) = MotionTabContent(selected, modifier, contentKey = { it }, order = { it }, content = content)

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
    val transition = updateTransition(state, label = "panel-presence")
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
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            val focus = LocalFocusManager.current
            SideEffect {
                window?.setWindowAnimations(0)
                val exitingFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                if (visible) window?.clearFlags(exitingFlags)
                else {
                    focus.clearFocus(force = true)
                    window?.addFlags(exitingFlags)
                }
            }
            // Native Dialog retains focus trapping and outside/back dismissal; no full-screen blur.
            Box(Modifier.motionInputGate(visible).graphicsLayer {
                this.alpha = alpha.value
                val offset = if (motion) position.value else 0f
                translationY = 16.dp.toPx() * offset
                scaleX = 1f - .025f * offset
                scaleY = scaleX
            }) { content() }
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
    ModalBottomSheet(onDismissRequest = { onDismiss() }, sheetState = sheet, modifier = modifier,
        containerColor = containerColor, contentColor = contentColor, tonalElevation = 0.dp,
        dragHandle = {
            Box(Modifier.fillMaxWidth().height(26.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 32.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(contentColor.copy(alpha = .42f)))
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().motionInputGate(!closing)) { content(dismiss) }
    }
}

