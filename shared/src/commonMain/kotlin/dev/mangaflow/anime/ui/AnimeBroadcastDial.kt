package dev.mangaflow.anime.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.DragScope
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.lerp
import dev.chrisbanes.haze.glass.LocalGlassAccessibilitySettings
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.motion.*
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest

internal data class AnimeDialMark(val caption: String, val title: String, val description: String)

private const val DialTicksPerPage = 5

/** Existing keyed pager, with the saved Jelly Tabs' material-only grip and a fixed reading line. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AnimeBroadcastDial(label: String, previous: String, next: String, noSelection: String,
    pageCount: Int, anchor: Int, selectionVisible: Boolean, owner: Any, active: Boolean,
    pageKey: (Int) -> Any, mark: (Int) -> AnimeDialMark, onSelect: (Int) -> Unit) {
    val motion = LocalMotionEnabled.current
    val touchExploration = LocalTouchExplorationEnabled.current
    val contrast = LocalGlassAccessibilitySettings.current.increaseContrast
    val feedback = rememberFeelFeedback()
    val pager = rememberPagerState(initialPage = anchor) { pageCount }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    fun centered() = abs(pager.currentPageOffsetFraction) * pager.layoutInfo.pageSize.coerceAtLeast(1) <= 1f
    fun aligned() = !pager.isScrollInProgress && pager.currentPage == anchor && centered()
    val input = remember(owner, anchor, selectionVisible, active, motion, touchExploration, rtl) { DialInput(active && aligned()) }
    val currentInput = rememberUpdatedState(input)
    val drive = remember(pager, input) { PagerDialDrag(pager) }
    val pressInteraction = remember(input) { MutableInteractionSource() }
    val pressed by pressInteraction.collectIsPressedAsState()
    val dragging by drive.events.collectIsDraggedAsState()
    var keyboardFocus by remember { mutableStateOf(false) }

    LaunchedEffect(pager, input) {
        if (!active) return@LaunchedEffect
        // Ordinary selection is already centered. Only an external query/visit may realign;
        // it cannot submit a second query or play a detent tick.
        if (!aligned()) {
            pager.scroll(MutatePriority.PreventUserInput) { with(pager) { updateCurrentPage(anchor) } }
        }
        var lastDetent = anchor * DialTicksPerPage
        fun tick() {
            if (motion && !touchExploration && platformKind == PlatformKind.ANDROID) feedback.perform(FeelEvent.Selection)
        }
        input.ready = true
        snapshotFlow {
            Triple((pager.currentPage + pager.currentPageOffsetFraction) * DialTicksPerPage,
                input.drag != null || input.request != null || input.landing != null, input.landing)
        }.collectLatest { (position, movingByUser, landed) ->
            if (currentInput.value !== input) return@collectLatest
            // Tick when a drawn mark crosses the index, not when Pager changes its nearest
            // page halfway between values. A fast move emits one throttled tick, never a backlog.
            if (!movingByUser) lastDetent = position.roundToInt()
            val detent = when {
                position >= lastDetent + 1f -> floor(position).toInt()
                position <= lastDetent - 1f -> ceil(position).toInt()
                else -> lastDetent
            }
            if (movingByUser && detent != lastDetent) {
                lastDetent = detent
                tick()
            }
            if (landed == null) return@collectLatest
            // Let a new press/drag retire the previous release before committing a query.
            withFrameNanos { }
            if (currentInput.value === input && input.landing === landed && !pager.isScrollInProgress &&
                centered() && pager.settledPage == landed.page) {
                if (lastDetent != landed.page * DialTicksPerPage || (!selectionVisible && landed.page == anchor)) tick()
                lastDetent = landed.page * DialTicksPerPage
                input.landing = null
                onSelect(landed.page)
            }
        }
    }
    suspend fun moveTo(page: Int) {
        if (motion) pager.animateScrollToPage(page,
            animationSpec = spring(MangaMotion.GestureDamping, MangaMotion.GestureStiffness))
        else pager.scrollToPage(page)
    }
    fun offerLanding(page: Int) {
        if (currentInput.value === input && !pager.isScrollInProgress && centered() && pager.settledPage == page) {
            input.landing = DialLanding(page)
        }
    }
    val request = input.request
    LaunchedEffect(pager, input, request) {
        if (request == null || !input.ready || currentInput.value !== input) return@LaunchedEffect
        try {
            moveTo(request.page)
            currentCoroutineContext().ensureActive()
            if (input.request === request) offerLanding(request.page)
        } finally {
            if (input.request === request) input.request = null
        }
    }
    val interactive = active && input.ready
    // Like the saved Jelly material, only the lens takes pressure; text and hit bounds
    // keep their size. The pager still follows input directly, without a second position spring.
    val gripping = interactive && (pressed || dragging)
    val pressure = animateFloatAsState(if (motion && gripping) .045f else 0f,
        animationSpec = if (!motion) snap() else if (gripping) spring(1f, MangaMotion.PressAttackStiffness)
        else spring(MangaMotion.PressDamping, MangaMotion.PressStiffness), label = "dial-pressure")
    fun pick(page: Int): Boolean {
        if (!interactive || currentInput.value !== input || page !in 0 until pageCount || page == input.request?.page ||
            drive.events.started != null) return false
        if (!pager.isScrollInProgress && pager.currentPage == page && centered()) {
            if (!selectionVisible && page == anchor && input.request == null) {
                offerLanding(page) // explicitly chose the centered preview of the whole week / unfiltered season
                return true
            }
            return false
        }
        input.drag = null
        input.landing = null
        input.request = DialRequest(page)
        return true
    }
    fun step(delta: Int) = pick(pager.currentPage + delta)

    val snapFling = PagerDefaults.flingBehavior(pager, pagerSnapDistance = PagerSnapDistance.atMost(if (motion) 3 else 1),
        snapAnimationSpec = if (motion) spring(MangaMotion.GestureDamping, MangaMotion.GestureStiffness) else snap())
    val fling = remember(snapFling, motion) {
        if (motion) snapFling else object : TargetedFlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float, onRemainingDistanceUpdated: (Float) -> Unit): Float =
                with(snapFling) { performFling(0f, onRemainingDistanceUpdated) }
        }
    }
    // One stock drag recognizer for touch and mouse; Pager owns position and velocity settling.
    // The stock callback reports zero velocity for both an ordinary slow release and cancellation.
    val drag = Modifier.draggable(drive, Orientation.Horizontal, enabled = interactive && !touchExploration,
        interactionSource = drive.events, startDragImmediately = pager.isScrollInProgress, reverseDirection = !rtl,
        onDragStarted = {
            if (currentInput.value === input && interactive) {
                input.request = null
                input.landing = null
                input.drag = drive.events.started
            }
        }, onDragStopped = stop@{ velocity ->
            val end = drive.events.consumeEnd() ?: return@stop
            if (currentInput.value !== input || !interactive || input.drag !== end.start) return@stop
            try {
                if (end.released) {
                    pager.scroll { with(fling) { performFling(velocity, {}) } }
                    currentCoroutineContext().ensureActive()
                    val landed = pager.settledPage
                    if (input.drag === end.start && landed != anchor) offerLanding(landed)
                } else {
                    input.drag = null
                    moveTo(anchor) // cancel restores the selected date; this motion doesn't offer a landing
                }
            } finally {
                if (input.drag === end.start) input.drag = null
            }
        })

    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val ink = colors.onSurface
    val caption = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
    val value = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 23.sp, fontFeatureSettings = "tnum")
    val height = maxOf(58.dp, with(density) { caption.lineHeight.toDp() + value.lineHeight.toDp() + 24.dp })
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(Modifier.widthIn(max = 458.dp).fillMaxWidth().motionInputGate(active)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.isAltPressed || event.isCtrlPressed || event.isMetaPressed) false
                else when (event.key) {
                    Key.DirectionLeft -> step(if (rtl) 1 else -1)
                    Key.DirectionRight -> step(if (rtl) -1 else 1)
                    else -> false
                }
            }.focusGroup(), verticalAlignment = Alignment.CenterVertically) {
            MotionIconButton(onClick = { step(-1) }, enabled = interactive && pager.currentPage > 0,
                modifier = Modifier.size(44.dp), tooltip = previous) {
                Icon(if (rtl) Icons.Outlined.ChevronRight else Icons.Outlined.ChevronLeft, previous, Modifier.size(18.dp))
            }
            BoxWithConstraints(Modifier.weight(1f).height(height).clip(RoundedCornerShape(14.dp))
                .background(if (contrast) colors.surfaceContainerHigh else colors.surfaceContainerLow)
                .semantics {
                    contentDescription = label
                    stateDescription = if (selectionVisible) mark(anchor).description else noSelection
                    if (interactive && !selectionVisible) onClick(label = "选择居中值") { pick(pager.currentPage) }
                }
                .onFocusChanged { keyboardFocus = it.isFocused }
                .onPreviewKeyEvent { event ->
                    keyboardFocus && event.type == KeyEventType.KeyDown && !event.isAltPressed && !event.isCtrlPressed &&
                        !event.isMetaPressed && (event.key == Key.Enter || event.key == Key.Spacebar) && pick(pager.currentPage)
                }.focusable(enabled = interactive), contentAlignment = Alignment.Center) {
                val pageWidth = maxOf(maxWidth * .40f, 58.dp * density.fontScale).coerceIn(1.dp, maxWidth.coerceAtLeast(1.dp))
                val inset = ((maxWidth - pageWidth) / 2).coerceAtLeast(0.dp)
                val lens = RoundedCornerShape(11.dp)
                Box(Modifier.width(pageWidth).fillMaxHeight().padding(vertical = 3.dp, horizontal = 2.dp)
                    .graphicsLayer {
                        if (motion) {
                            val stretch = 1f + pressure.value * .35f + .12f * abs(pager.currentPageOffsetFraction)
                            scaleX = stretch
                            scaleY = (stretch.pow(-.65f) * (1f - pressure.value)).coerceIn(.88f, 1.025f)
                        }
                    }
                    .background(if (contrast) colors.primaryContainer else lerp(colors.surface, colors.primary, .085f), lens)
                    .then(if (keyboardFocus) Modifier.border(1.dp, ink.copy(alpha = if (contrast) 1f else .50f), lens) else Modifier))
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().then(drag),
                    pageSize = PageSize.Fixed(pageWidth), contentPadding = PaddingValues(horizontal = inset),
                    key = pageKey, snapPosition = SnapPosition.Center, overscrollEffect = null,
                    flingBehavior = fling, userScrollEnabled = false) { page ->
                    val info = mark(page)
                    Box(Modifier.fillMaxSize().focusProperties { canFocus = false }
                        .semantics { contentDescription = info.description }
                        .selectable(selectionVisible && page == anchor, pressInteraction, indication = null,
                            enabled = interactive, role = Role.RadioButton, onClick = { pick(page) }), contentAlignment = Alignment.Center) {
                        Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clearAndSetSemantics { }.graphicsLayer {
                            val offset = (page - pager.currentPage - pager.currentPageOffsetFraction).coerceIn(-1f, 1f)
                            val front = 1f - abs(offset)
                            if (motion) {
                                rotationY = offset * if (rtl) -18f else 18f
                                scaleY = .86f + .14f * front
                            }
                            alpha = if (contrast) 1f else .62f + .38f * front
                        }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(info.caption, style = caption, color = colors.onSurfaceVariant, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(info.title, style = value, color = ink, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 5.dp)
                            .height(8.dp).clearAndSetSemantics { }) {
                            for (i in -2..2) {
                                val x = size.width * (.5f + i.toFloat() / DialTicksPerPage)
                                val distance = abs(page - pager.currentPage - pager.currentPageOffsetFraction +
                                    (if (rtl) -i else i).toFloat() / DialTicksPerPage)
                                val contact = (1f - distance * DialTicksPerPage).coerceIn(0f, 1f)
                                val length = if (i == 0) 6f else 3f
                                val tip = if (motion) length + 2f * contact else length + 1f
                                val color = if (contrast) ink else lerp(ink, colors.primary, contact).copy(alpha = .22f + .60f * contact)
                                drawLine(color, Offset(x, 1.dp.toPx()), Offset(x, tip.dp.toPx()),
                                    (if (i == 0) 1.5.dp else 1.dp).toPx(), StrokeCap.Round)
                            }
                        }
                    }
                }
                Canvas(Modifier.align(Alignment.TopCenter).size(8.dp).clearAndSetSemantics { }) {
                    val tickOffset = pager.currentPageOffsetFraction * DialTicksPerPage
                    val contact = (1f - 2f * abs(tickOffset - tickOffset.roundToInt())).coerceIn(0f, 1f)
                    // The index stays anchored; its tip yields and returns as each mark passes.
                    val bend = if (motion) sin(2.0 * PI * tickOffset).toFloat() * 1.4.dp.toPx() * (if (rtl) -1f else 1f) else 0f
                    val length = if (motion) (4f + 1.5f * contact).dp else 5.dp
                    drawLine(colors.primary.copy(alpha = if (contrast) 1f else .67f + .33f * contact),
                        Offset(size.width / 2f, 1.dp.toPx()), Offset(size.width / 2f + bend, length.toPx()), 1.8.dp.toPx(), StrokeCap.Round)
                }
            }
            MotionIconButton(onClick = { step(1) }, enabled = interactive && pager.currentPage < pageCount - 1,
                modifier = Modifier.size(44.dp), tooltip = next) {
                Icon(if (rtl) Icons.Outlined.ChevronLeft else Icons.Outlined.ChevronRight, next, Modifier.size(18.dp))
            }
        }
    }
}

private class DialInput(aligned: Boolean) {
    var ready by mutableStateOf(aligned)
    var request by mutableStateOf<DialRequest?>(null)
    var drag by mutableStateOf<DragInteraction.Start?>(null)
    var landing by mutableStateOf<DialLanding?>(null)
}

private class DialRequest(val page: Int)
private class DialLanding(val page: Int)
private class DialDragEnd(val start: DragInteraction.Start, val released: Boolean)

/** Observe the exact Foundation interaction before its onDragStopped callback, not in an async collector. */
private class DialDragEvents(private val delegate: MutableInteractionSource = MutableInteractionSource()) :
    MutableInteractionSource by delegate {
    var started: DragInteraction.Start? = null
        private set
    private var terminal: DialDragEnd? = null

    fun consumeEnd(): DialDragEnd? = terminal.also { terminal = null }

    override suspend fun emit(interaction: Interaction) {
        record(interaction)
        delegate.emit(interaction)
    }

    override fun tryEmit(interaction: Interaction): Boolean {
        record(interaction)
        return delegate.tryEmit(interaction)
    }

    private fun record(interaction: Interaction) {
        when (interaction) {
            is DragInteraction.Start -> { started = interaction; terminal = null }
            is DragInteraction.Stop -> end(interaction.start, released = true)
            is DragInteraction.Cancel -> end(interaction.start, released = false)
        }
    }

    private fun end(start: DragInteraction.Start, released: Boolean) {
        if (started !== start) return
        started = null
        terminal = DialDragEnd(start, released)
    }
}

private class PagerDialDrag(private val pager: PagerState) : DraggableState {
    val events = DialDragEvents()
    override suspend fun drag(dragPriority: MutatePriority, block: suspend DragScope.() -> Unit) =
        pager.scroll(dragPriority) {
            val scroll = this
            block(object : DragScope {
                override fun dragBy(pixels: Float) { scroll.scrollBy(pixels) }
            })
        }

    override fun dispatchRawDelta(delta: Float) { pager.dispatchRawDelta(delta) }
}
