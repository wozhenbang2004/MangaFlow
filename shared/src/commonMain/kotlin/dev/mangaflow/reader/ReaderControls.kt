package dev.mangaflow.reader

import dev.mangaflow.ui.mangaNumberText

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.LocalGlassAccessibilitySettings
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
        LiquidGlassSurface(backdrop, modifier, role = GlassSurfaceRole.PANEL, softCard = true) { content() }
    } else {
        // A docked desktop inspector sits beside the page, outside its optical sample.
        ReaderSurface(modifier, content = content)
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
    panelVisible: Boolean,
    panelWidth: Dp,
    panel: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val motion = LocalMotionEnabled.current
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
        AnimatedVisibility(
            visible = panelVisible,
            enter = expandHorizontally(
                animationSpec = tween(if (motion) MangaMotion.Content else 0, easing = MangaMotion.EnterEasing),
                expandFrom = Alignment.End,
            ) + fadeIn(tween(if (motion) MangaMotion.Quick else 0)),
            exit = shrinkHorizontally(
                animationSpec = tween(if (motion) MangaMotion.Exit else 0, easing = MangaMotion.ExitEasing),
                shrinkTowards = Alignment.End,
            ) + fadeOut(tween(if (motion) MangaMotion.Quick else 0)),
        ) {
            Box(Modifier.width(panelWidth).fillMaxHeight().padding(start = 6.dp, top = 8.dp, end = 8.dp, bottom = 8.dp)
                .motionInputGate(panelVisible)) {
                panel()
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
    content: @Composable () -> Unit,
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
    }.motionInputGate(presence.targetState)) { content() }
}

/** Place the page ruler and chapter tools on the same baseline when the artwork pane has room. */
@Composable
internal fun ReaderDockGroups(desktop: Boolean, progress: @Composable () -> Unit, actions: @Composable () -> Unit) {
    val content = Modifier.fillMaxWidth().pointerInput(Unit) {}.padding(horizontal = 10.dp, vertical = 4.dp)
    if (!desktop) {
        Column(content) { progress(); actions() }
        return
    }
    BoxWithConstraints(content) {
        if (maxWidth >= 620.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { progress() }
                Spacer(Modifier.padding(horizontal = 8.dp).size(1.dp, 20.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .13f)))
                Box(Modifier.width(272.dp)) { actions() }
            }
        } else Column(Modifier.fillMaxWidth()) { progress(); actions() }
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
    val foreground = if (enabled) colors.primary else colors.onSurface.copy(alpha = .30f)
    val previewText = valueLabel?.invoke(value.coerceIn(valueRange)) ?: pageCount?.let { count ->
        "${value.roundToInt().coerceIn(0, (count - 1).coerceAtLeast(0)) + 1} / $count"
    } ?: "${(fraction * 100f).roundToInt()}%"
    // Continuous reading/scrubbing is silent; the preview and release commit are the feedback.
    val layoutDirection = LocalLayoutDirection.current
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(modifier.height(48.dp)) {
        val bubbleWidth = minOf(76.dp * fontScale, maxWidth)
        val travel = (maxWidth - 24.dp).coerceAtLeast(0.dp)
        val bubbleLimit = (maxWidth - bubbleWidth).coerceAtLeast(0.dp)
        val thumbX = 12.dp + travel * fraction
        val bubbleX = (thumbX - bubbleWidth / 2f).coerceIn(0.dp, bubbleLimit)
        val origin = ((thumbX - bubbleX) / bubbleWidth.coerceAtLeast(1.dp)).coerceIn(0f, 1f)
        Surface(
            modifier = Modifier.width(bubbleWidth).heightIn(min = 24.dp).align(Alignment.TopStart)
                .offset(x = bubbleX, y = (-16).dp)
                .graphicsLayer {
                    val amount = engagement.value.coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(if (layoutDirection == LayoutDirection.Rtl) 1f - origin else origin, 1f)
                    alpha = amount
                    scaleX = .87f + amount * .13f
                    scaleY = .72f + amount * .28f
                    translationY = (1f - amount) * 10.dp.toPx()
                }
                .clearAndSetSemantics { },
            shape = RoundedCornerShape(50),
            color = colors.surface,
            contentColor = colors.onSurface,
            shadowElevation = if (engaged) 2.dp else 0.dp,
            tonalElevation = 0.dp,
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(mangaNumberText(previewText), color = colors.onSurface.copy(alpha = .86f),
                    fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1)
            }
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
                }
            },
        )
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
