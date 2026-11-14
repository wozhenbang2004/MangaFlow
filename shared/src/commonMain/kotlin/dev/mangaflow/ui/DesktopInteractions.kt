package dev.mangaflow.ui

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.motionPress
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Existing object callbacks gain a desktop entry; they never duplicate their data operations. */
internal data class DesktopMenuAction(
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** Touch keeps its original gesture tree. A secondary mouse press never becomes an open action. */
@Composable
internal fun DesktopContextMenuArea(
    actions: List<DesktopMenuAction>,
    content: @Composable () -> Unit,
) {
    if (platformKind != PlatformKind.DESKTOP || actions.isEmpty()) {
        content()
        return
    }
    var visible by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<IntOffset?>(null) }
    val latestActions by rememberUpdatedState(actions)
    Box(Modifier
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.F10 &&
                event.isShiftPressed && latestActions.isNotEmpty()) {
                anchor = null
                visible = true
                true
            } else false
        }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                var secondaryStroke = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed &&
                        latestActions.isNotEmpty()) {
                        val point = event.changes.firstOrNull()?.position
                        anchor = point?.let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) }
                        visible = true
                        secondaryStroke = true
                        event.changes.forEach { it.consume() }
                    } else if (secondaryStroke) {
                        // Opening a Popup can deliver the right-button UP to its window.
                        // A later primary DOWN must never be swallowed by that stale stroke.
                        if (event.buttons.isSecondaryPressed || event.type == PointerEventType.Release)
                            event.changes.forEach { it.consume() }
                        if (!event.buttons.isSecondaryPressed) secondaryStroke = false
                    }
                }
            }
        }) {
        content()
        DesktopActionPopup(visible, actions, { visible = false }, anchor)
    }
}

/** Always reachable with pointer and keyboard, without adding a permanent row of object commands. */
@Composable
internal fun DesktopMoreButton(
    actions: List<DesktopMenuAction>,
    label: String = "更多操作",
    onExpandedChange: (Boolean) -> Unit = {},
) {
    if (platformKind != PlatformKind.DESKTOP || actions.isEmpty()) return
    var visible by remember { mutableStateOf(false) }
    Box {
        MotionIconButton(onClick = { visible = !visible }, modifier = Modifier.size(32.dp), tooltip = label) {
            Icon(Icons.Outlined.MoreHoriz, label, Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DesktopActionPopup(visible, actions, { visible = false }, onPresenceChange = onExpandedChange)
    }
}

/**
 * Adapts CompactGlassPopover's retained opacity/scale lifetime and RecordActionsMenu's existing
 * surface, radius and shadow. The real anchor and window bounds own placement, not animated width.
 */
@Composable
private fun DesktopActionPopup(
    visible: Boolean,
    actions: List<DesktopMenuAction>,
    onDismiss: () -> Unit,
    anchor: IntOffset? = null,
    onPresenceChange: (Boolean) -> Unit = {},
) {
    val motion = LocalMotionEnabled.current
    val density = LocalDensity.current
    val presence = remember { MutableTransitionState(false) }
    presence.targetState = visible
    val transition = rememberTransition(presence, label = "desktop-actions")
    val opacity = transition.animateFloat(transitionSpec = {
        tween(if (motion) if (targetState) MangaMotion.Content else MangaMotion.Quick else 0)
    }, label = "desktop-actions-opacity") { if (it) 1f else 0f }
    val scale = transition.animateFloat(transitionSpec = {
        if (motion) spring(MangaMotion.PanelDamping, MangaMotion.PanelStiffness) else snap()
    }, label = "desktop-actions-scale") { if (it) 1f else .94f }
    val present = presence.currentState || presence.targetState || !presence.isIdle
    val reportPresence by rememberUpdatedState(onPresenceChange)
    SideEffect { reportPresence(present) }
    DisposableEffect(Unit) { onDispose { reportPresence(false) } }
    if (!present) return
    val enabledIndices = actions.indices.filter { actions[it].enabled }
    val requesters = remember(actions.size) { List(actions.size) { FocusRequester() } }
    var focusedIndex by remember { mutableIntStateOf(-1) }
    val latestDismiss by rememberUpdatedState(onDismiss)
    val position = remember(anchor, density) {
        val margin = with(density) { 8.dp.roundToPx() }
        val gap = with(density) { 4.dp.roundToPx() }
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val pointX = anchorBounds.left + (anchor?.x ?: anchorBounds.width)
                val pointY = anchorBounds.top + (anchor?.y ?: anchorBounds.height)
                val desiredX = if (pointX + popupContentSize.width > windowSize.width - margin)
                    pointX - popupContentSize.width else pointX
                val desiredY = if (pointY + popupContentSize.height + gap > windowSize.height - margin)
                    pointY - popupContentSize.height - gap else pointY + gap
                return IntOffset(
                    desiredX.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)),
                    desiredY.coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)),
                )
            }
        }
    }
    Popup(popupPositionProvider = position,
        onDismissRequest = { if (visible) latestDismiss() },
        properties = PopupProperties(focusable = visible)) {
        LaunchedEffect(visible, actions.map { it.label to it.enabled }) {
            if (visible && enabledIndices.isNotEmpty()) {
                withFrameNanos { }
                val first = enabledIndices.first()
                requesters[first].requestFocus()
            }
        }
        Surface(
            modifier = Modifier.width((LocalMangaWindow.current.width - 24.dp)
                .coerceAtLeast(1.dp).coerceAtMost(264.dp))
                .motionInputGate(visible)
                .onPreviewKeyEvent { event ->
                    if (!visible || event.type != KeyEventType.KeyDown) false
                    else when (event.key) {
                        Key.Escape -> { latestDismiss(); true }
                        Key.DirectionDown, Key.DirectionUp, Key.MoveHome, Key.MoveEnd -> {
                            if (enabledIndices.isNotEmpty()) {
                                val current = enabledIndices.indexOf(focusedIndex)
                                val next = when (event.key) {
                                    Key.MoveHome -> 0
                                    Key.MoveEnd -> enabledIndices.lastIndex
                                    Key.DirectionUp -> if (current <= 0) enabledIndices.lastIndex else current - 1
                                    else -> if (current < 0 || current == enabledIndices.lastIndex) 0 else current + 1
                                }
                                requesters[enabledIndices[next]].requestFocus()
                            }
                            true
                        }
                        else -> false
                    }
                }.graphicsLayer {
                    alpha = opacity.value
                    scaleX = scale.value
                    scaleY = scale.value
                    transformOrigin = TransformOrigin(.5f, 0f)
                    translationY = -4.dp.toPx() * (1f - opacity.value)
                },
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp,
            shadowElevation = 4.dp,
        ) {
            Column(Modifier.heightIn(max = (LocalMangaWindow.current.height - 48.dp).coerceAtLeast(1.dp))
                .verticalScroll(rememberScrollState()).padding(6.dp)) {
                actions.forEachIndexed { index, action ->
                    val interaction = remember(index) { MutableInteractionSource() }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .focusRequester(requesters[index])
                        .onFocusChanged { if (it.isFocused) focusedIndex = index }
                        .clickable(enabled = action.enabled, interactionSource = interaction, indication = null,
                            role = Role.Button, onClick = {
                                latestDismiss()
                                action.onClick()
                            }).motionPress(interaction, action.enabled, pressedScale = .98f)
                        .heightIn(min = 36.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(action.label, color = MaterialTheme.colorScheme.onSurface.copy(
                            alpha = if (action.enabled) .86f else .30f), fontSize = 13.sp, lineHeight = 19.sp)
                    }
                }
            }
        }
    }
}

/** A non-focusable label lives inside its actual icon's Box, preserving the caller's layout slot. */
@Composable
internal fun DesktopIconTooltip(label: String?, interaction: MutableInteractionSource, enabled: Boolean) {
    if (platformKind != PlatformKind.DESKTOP || label.isNullOrBlank()) return
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(label, enabled, hovered, focused, pressed, windowFocused) {
        visible = false
        if (enabled && windowFocused && !pressed && (hovered || focused)) {
            delay(500)
            visible = true
        }
    }
    if (!visible) return
    val density = LocalDensity.current
    val position = remember(density) {
        val gap = with(density) { 8.dp.roundToPx() }
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val x = anchorBounds.center.x - popupContentSize.width / 2
                val below = anchorBounds.bottom + gap
                val y = if (below + popupContentSize.height > windowSize.height - gap)
                    anchorBounds.top - popupContentSize.height - gap else below
                return IntOffset(
                    x.coerceIn(gap, (windowSize.width - popupContentSize.width - gap).coerceAtLeast(gap)),
                    y.coerceIn(gap, (windowSize.height - popupContentSize.height - gap).coerceAtLeast(gap)),
                )
            }
        }
    }
    Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false)) {
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp, shadowElevation = 4.dp) {
            Text(label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, lineHeight = 16.sp,
                maxLines = 2)
        }
    }
}
