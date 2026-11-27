package dev.mangaflow.ui

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Check
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
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
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.anchoredMenuReveal
import dev.mangaflow.ui.motion.surfaceReveal
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.motionPress
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Existing object callbacks gain a desktop entry; they never duplicate their data operations. */
internal data class DesktopMenuAction(
    val label: String,
    val enabled: Boolean = true,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val selected: Boolean? = null,
    val onClick: () -> Unit,
)

internal fun desktopShelfAction(favorite: Boolean, addLabel: String = "加入书架", removeLabel: String = "移出书架",
    onClick: () -> Unit) = DesktopMenuAction(if (favorite) removeLabel else addLabel,
    icon = if (favorite) Icons.Outlined.BookmarkRemove else Icons.Outlined.BookmarkAdd,
    destructive = favorite, onClick = onClick)

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
            if (event.type == KeyEventType.KeyDown && latestActions.isNotEmpty() &&
                (event.key == Key.Menu || event.key == Key.F10 && event.isShiftPressed)) {
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
        val colors = MaterialTheme.colorScheme
        val selected = if (visible) Modifier.background(colors.primary.copy(alpha = .10f), RoundedCornerShape(9.dp)) else Modifier
        MotionIconButton(onClick = { visible = !visible }, modifier = Modifier.size(32.dp).then(selected)
            .semantics { stateDescription = if (visible) "已展开" else "已收起" }, tooltip = label.takeUnless { visible }) {
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
internal fun DesktopActionPopup(
    visible: Boolean,
    actions: List<DesktopMenuAction>,
    onDismiss: () -> Unit,
    anchor: IntOffset? = null,
    onPresenceChange: (Boolean) -> Unit = {},
    alignToStart: Boolean = false,
) {
    // Closed menus belong to every visible and precomposed item. Only install the
    // transition once that particular menu opens, then retain it through its exit.
    var mounted by remember { mutableStateOf(false) }
    if (!visible && !mounted) return
    val motion = LocalMotionEnabled.current
    val density = LocalDensity.current
    val presence = remember { MutableTransitionState(false) }
    presence.targetState = visible
    val transition = rememberTransition(presence, label = "desktop-actions")
    val reveal = transition.surfaceReveal(motion, compact = true)
    val present = presence.currentState || presence.targetState || !presence.isIdle
    val reportPresence by rememberUpdatedState(onPresenceChange)
    SideEffect { mounted = present }
    DisposableEffect(present) {
        if (present) reportPresence(true)
        onDispose { if (present) reportPresence(false) }
    }
    if (!present) return
    val window = LocalMangaWindow.current
    val maximumWidth = (window.width - 24.dp).coerceIn(1.dp, 320.dp * window.fontScale.coerceAtMost(1.5f))
    val (removals, ordinary) = actions.partition { it.destructive }
    val entries = if (removals.isEmpty()) actions else ordinary + removals
    val hasIcons = entries.any { it.icon != null }
    val hasSelection = entries.any { it.selected != null }
    val enabledIndices = entries.indices.filter { entries[it].enabled }
    val requesters = remember(entries.size) { List(entries.size) { FocusRequester() } }
    var focusedIndex by remember { mutableIntStateOf(-1) }
    var keyboardFocus by remember(visible) { mutableStateOf(anchor == null) }
    var origin by remember(anchor) { mutableStateOf(TransformOrigin(.5f, 0f)) }
    val latestDismiss by rememberUpdatedState(onDismiss)
    val position = remember(anchor, alignToStart, density) {
        val margin = with(density) { 8.dp.roundToPx() }
        val gap = with(density) { 4.dp.roundToPx() }
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val ltr = layoutDirection == LayoutDirection.Ltr
                val growRight = if (anchor == null) alignToStart == ltr else ltr
                val pointX = anchor?.let { anchorBounds.left + it.x } ?: if (growRight) anchorBounds.left else anchorBounds.right
                val roomRight = windowSize.width - margin - pointX
                val roomLeft = pointX - margin
                val placeRight = when {
                    growRight && roomRight >= popupContentSize.width -> true
                    !growRight && roomLeft >= popupContentSize.width -> false
                    else -> roomRight >= roomLeft
                }
                val desiredX = if (placeRight) pointX else pointX - popupContentSize.width
                val pointerY = anchor?.let { anchorBounds.top + it.y }
                val below = (pointerY ?: anchorBounds.bottom) + gap
                val above = (pointerY ?: anchorBounds.top) - gap
                val placeBelow = windowSize.height - margin - below >= popupContentSize.height ||
                    windowSize.height - margin - below >= above - margin
                val desiredY = if (placeBelow) below else above - popupContentSize.height
                val pointY = pointerY ?: if (placeBelow) anchorBounds.bottom else anchorBounds.top
                val placed = IntOffset(
                    desiredX.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)),
                    desiredY.coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)),
                )
                origin = TransformOrigin(((pointX - placed.x).toFloat() / popupContentSize.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                    ((pointY - placed.y).toFloat() / popupContentSize.height.coerceAtLeast(1)).coerceIn(0f, 1f))
                return placed
            }
        }
    }
    Popup(popupPositionProvider = position,
        onDismissRequest = { if (visible) latestDismiss() },
        properties = PopupProperties(focusable = visible)) {
        LaunchedEffect(visible, enabledIndices) {
            if (!visible) focusedIndex = -1
            if (visible && enabledIndices.isNotEmpty()) {
                withFrameNanos { }
                if (focusedIndex !in enabledIndices) {
                    val initialIndex = enabledIndices.firstOrNull { entries[it].selected == true } ?: enabledIndices.first()
                    requesters[initialIndex].requestFocus()
                }
            }
        }
        Surface(
            modifier = Modifier.widthIn(min = minOf(168.dp, maximumWidth), max = maximumWidth).width(IntrinsicSize.Max)
                .motionInputGate(visible)
                .pointerInput(visible, anchor) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press || (event.type == PointerEventType.Move &&
                                event.changes.any { it.position != it.previousPosition })) keyboardFocus = false
                        }
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (visible && event.type == KeyEventType.KeyDown) keyboardFocus = true
                    if (!visible || event.type != KeyEventType.KeyDown) false
                    else when (event.key) {
                        Key.Escape, Key.Tab -> { latestDismiss(); true }
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
                }.anchoredMenuReveal(reveal, 14.dp) { origin },
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            tonalElevation = 0.dp,
            shadowElevation = 4.dp,
        ) {
            Column(Modifier.heightIn(max = (LocalMangaWindow.current.height - 48.dp).coerceAtLeast(1.dp))
                .verticalScroll(rememberScrollState()).padding(6.dp)) {
                entries.forEachIndexed { index, action ->
                    val interaction = remember(index) { MutableInteractionSource() }
                    val startRemovalGroup = index > 0 && action.destructive && !entries[index - 1].destructive
                    val colors = MaterialTheme.colorScheme
                    val opacity = if (action.enabled) 1f else .40f
                    Row(Modifier.fillMaxWidth().padding(top = if (startRemovalGroup) 6.dp else 0.dp)
                        .clip(RoundedCornerShape(8.dp)).then(if (action.selected == true)
                            Modifier.background(colors.primary.copy(alpha = .065f)) else Modifier)
                        .focusRequester(requesters[index])
                        .onFocusChanged { if (it.isFocused) focusedIndex = index else if (focusedIndex == index) focusedIndex = -1 }
                        .then(action.selected?.let { choice -> Modifier.semantics { selected = choice } } ?: Modifier)
                        .clickable(enabled = action.enabled, interactionSource = interaction, indication = null,
                            role = if (action.selected != null) Role.RadioButton else Role.Button, onClick = {
                                latestDismiss()
                                action.onClick()
                            }).motionPress(interaction, action.enabled, pressedScale = .98f, showKeyboardFocus = keyboardFocus)
                        .heightIn(min = 34.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (hasIcons) Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                            action.icon?.let { glyph -> Icon(glyph, null, Modifier.size(17.dp),
                                tint = (if (action.destructive) colors.error else colors.onSurfaceVariant).copy(alpha = opacity)) }
                        }
                        Text(action.label, Modifier.weight(1f), color = colors.onSurface.copy(alpha = opacity),
                            style = MaterialTheme.typography.bodyMedium)
                        if (hasSelection) Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                            if (action.selected == true) Icon(Icons.Outlined.Check, null, Modifier.size(15.dp),
                                tint = colors.primary.copy(alpha = opacity))
                        }
                    }
                }
            }
        }
    }
}

/** Only a host with dark media chrome opts into this variant. */
internal val LocalDesktopTooltipOverMedia = staticCompositionLocalOf { false }

/** Nearby targets share one tooltip and a brief grace period when the pointer crosses a gap. */
internal class DesktopTooltipSession {
    var visibleOwner by mutableStateOf<Any?>(null)
        private set
    private var candidate: Any? = null
    private var lastHiddenAt: Long? = null

    fun arm(owner: Any): Long {
        val now = monotonicTimeMillis()
        val warm = visibleOwner != null || lastHiddenAt?.let { now - it in 0L..450L } == true
        visibleOwner?.let { end(it) }
        candidate = owner
        return if (warm) 95L else 480L
    }

    fun show(owner: Any): Boolean {
        if (candidate !== owner) return false
        visibleOwner = owner
        return true
    }

    fun end(owner: Any) {
        if (candidate === owner) candidate = null
        if (visibleOwner === owner) {
            visibleOwner = null
            lastHiddenAt = monotonicTimeMillis()
        }
    }
}

internal val LocalDesktopTooltipSession = staticCompositionLocalOf<DesktopTooltipSession?> { null }

/** A non-focusable label lives inside its actual icon's Box, preserving the caller's layout slot. */
@Composable
internal fun DesktopIconTooltip(label: String?, interaction: MutableInteractionSource, enabled: Boolean,
    showOnFocus: Boolean = true) {
    if (platformKind != PlatformKind.DESKTOP || label.isNullOrBlank()) return
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val session = LocalDesktopTooltipSession.current ?: remember { DesktopTooltipSession() }
    val owner = remember(interaction) { Any() }
    var ready by remember(owner) { mutableStateOf(false) }
    val interested = hovered || (focused && showOnFocus)
    LaunchedEffect(session, owner, label, enabled, interested, pressed, windowFocused) {
        if (!enabled || !windowFocused || pressed || !interested) return@LaunchedEffect
        try {
            delay(session.arm(owner))
            ready = session.show(owner)
            if (ready) awaitCancellation()
        } finally {
            ready = false
            session.end(owner)
        }
    }
    if (!ready || session.visibleOwner !== owner) return
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
    val overMedia = LocalDesktopTooltipOverMedia.current
    val colors = if (overMedia) MangaDarkColorScheme else MaterialTheme.colorScheme
    Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false)) {
        Surface(shape = RoundedCornerShape(9.dp), color = if (overMedia) colors.surfaceContainerHigh else colors.surface,
            tonalElevation = 0.dp, shadowElevation = if (overMedia) 1.dp else 3.dp) {
            Text(label, Modifier.widthIn(max = 300.dp).padding(horizontal = 11.dp, vertical = 6.dp),
                color = colors.onSurface, fontSize = 12.sp, lineHeight = 17.sp,
                maxLines = 2)
        }
    }
}
