package dev.mangaflow.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import dev.mangaflow.ui.motion.anchoredMenuReveal
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
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
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeSourceRetention
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.FeelEvent
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.rememberFeelFeedback
import kotlin.math.abs
import kotlin.math.roundToInt

private object SourceOrbitWindowOrigin : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}

/**
 * Side disclosure shared by chapter reading and downloads.
 * The real trigger chooses the free side; only compact source marks and labels are drawn.
 */
@Composable
internal fun ChapterSourceOrbit(
    identity: String,
    title: String,
    chapterTitle: String,
    anchor: Rect?,
    backdrop: HazeState,
    count: Int,
    onDismiss: () -> Unit,
    closeRequested: Boolean = false,
    onCancel: () -> Unit = {},
    onPresenceChange: (Boolean) -> Unit = {},
    onClosingChange: (Boolean) -> Unit = {},
    hasStatus: Boolean = false,
    status: @Composable (active: Boolean) -> Unit = {},
    sourceItem: @Composable (index: Int, modifier: Modifier, dismiss: () -> Unit, reveal: () -> Float, active: Boolean) -> Unit,
) {
    val desktop = platformKind == PlatformKind.DESKTOP
    val sourceAnchor = remember(identity) { anchor }
    val motion = LocalMotionEnabled.current
    val feedback = rememberFeelFeedback()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    var closing by remember(identity) { mutableStateOf(false) }
    var page by remember(identity) { mutableIntStateOf(0) }
    var pageTurn by remember(identity) { mutableIntStateOf(0) }
    val pageArrival = remember(identity, pageTurn) { Animatable(if (pageTurn > 0 && motion) 0f else 1f) }
    LaunchedEffect(pageArrival, motion) {
        if (!motion) pageArrival.snapTo(1f)
        else if (pageArrival.value < 1f) pageArrival.animateTo(1f,
            tween(MangaMotion.Content, easing = MangaMotion.EnterEasing))
    }
    var origin by remember(identity) { mutableStateOf<Offset?>(null) }
    val onClosed by rememberUpdatedState(onDismiss)
    val cancel by rememberUpdatedState(onCancel)
    val reportPresence by rememberUpdatedState(onPresenceChange)
    val reportClosing by rememberUpdatedState(onClosingChange)
    SideEffect { reportPresence(true); reportClosing(closing) }
    DisposableEffect(identity) { onDispose { reportPresence(false); reportClosing(false) } }
    val setClosing = { value: Boolean -> closing = value; reportClosing(value) }
    LaunchedEffect(identity, closeRequested) { if (closeRequested) setClosing(true) }
    val close = { if (!closing) { setClosing(true); cancel() } }
    val progress = remember(identity) { Animatable(0f) }
    val flightVelocity = remember(identity) { floatArrayOf(0f) }
    val canInteract by remember(identity, closing) {
        // Rows are already separated at 0.6; delaying taps to 0.9 swallowed the first touch.
        derivedStateOf { !closing && origin != null && (desktop || progress.value >= .6f) }
    }
    val scrim = MaterialTheme.colorScheme.scrim
    // A Popup is another window: explicit source layers carry the actual page across it.
    // Keep the same presence through pagination so the page cannot flash sharp between groups.
    val blurInput = remember(backdrop) {
        HazeInput.Sources(backdrop, retention = HazeSourceRetention.KeepLastFrame)
    }
    val blurStyle = remember(progress, scrim) {
        HazeBlurStyle {
            val p = progress.value.coerceIn(0f, 1f)
            blurRadius(18.dp * p)
            alpha(p)
            noiseFactor(0f)
            backgroundColor(Color.Transparent)
            colorEffects(listOf(HazeColorEffect.tint(scrim.copy(alpha = .045f))))
            fallbackColorEffect(HazeColorEffect.tint(scrim.copy(alpha = .16f)))
        }
    }
    LaunchedEffect(identity, closing, motion, origin != null) {
        if (origin == null) {
            if (closing) onClosed()
            return@LaunchedEffect
        }
        progress.animateTo(if (closing) 0f else 1f,
            if (!motion) snap() else spring(
                dampingRatio = MangaMotion.NavigationDamping,
                stiffness = MangaMotion.NavigationStiffness),
            initialVelocity = if (motion) flightVelocity[0] else 0f) { flightVelocity[0] = velocity }
        flightVelocity[0] = 0f
        if (closing) onClosed()
    }
    Popup(popupPositionProvider = SourceOrbitWindowOrigin, onDismissRequest = close,
        properties = PopupProperties(focusable = true, clippingEnabled = false)) {
        Box(Modifier.fillMaxSize()) {
            // This sibling owns the full-window material; the source choices stay sharp above it.
            Box(Modifier.fillMaxSize()
                .then(if (desktop) Modifier.drawBehind { drawRect(scrim.copy(alpha = .065f * progress.value.coerceIn(0f, 1f))) }
                    else Modifier.hazeBlur(input = blurInput, style = blurStyle, expandLayerBounds = false))
                .focusProperties { canFocus = false }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClickLabel = "收起来源", onClick = close))
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()
                .onGloballyPositioned {
                    val position = it.localToScreen(Offset.Zero)
                    if (position.isSpecified) origin = position
                }.semantics { dismiss { close(); true } }) {
                if (desktop) {
                    DesktopChapterSources(identity, title, chapterTitle, sourceAnchor?.translate(-(origin ?: Offset.Zero)),
                        backdrop, count, canInteract, close, { if (origin == null) 0f else progress.value },
                        if (hasStatus) status else null, sourceItem)
                    return@BoxWithConstraints
                }
                val width = with(density) { maxWidth.toPx() }
                val height = with(density) { maxHeight.toPx() }
                val edge = with(density) { 8.dp.toPx() }
                val availableHeight = (maxHeight - 16.dp).coerceAtLeast(1.dp)
                val rowHeight = 48.dp * density.fontScale.coerceAtLeast(1f)
                val railWidth = minOf(152.dp * density.fontScale.coerceIn(1f, 1.3f),
                    (maxWidth - 16.dp).coerceAtLeast(1.dp))
                val railWidthPx = with(density) { railWidth.toPx() }
                val rowHeightPx = with(density) { rowHeight.toPx() }
                val statusRoom = with(density) { 80.dp.toPx() }
                val capacity = ((height - edge * 2f - statusRoom) / rowHeightPx).toInt().coerceIn(2, 6)
                val paged = count > capacity
                val pageSize = if (paged) capacity - 1 else capacity
                val pages = ((count + pageSize - 1) / pageSize).coerceAtLeast(1)
                val currentPage = page.coerceIn(0, pages - 1)
                // Even groups keep the next-page control close to its previous position.
                val regularCount = count / pages
                val extra = count % pages
                val first = currentPage * regularCount + minOf(currentPage, extra)
                val visibleCount = regularCount + if (currentPage < extra) 1 else 0
                val slots = visibleCount + if (paged) 1 else 0
                val localAnchor = sourceAnchor?.translate(-(origin ?: Offset.Zero))
                val start = localAnchor?.center ?: Offset(width / 2f, height / 2f)
                val anchorLeft = localAnchor?.left ?: start.x
                val anchorRight = localAnchor?.right ?: start.x
                val leftRoom = anchorLeft - edge * 2f
                val rightRoom = width - anchorRight - edge * 2f
                val preferLeft = start.x >= width / 2f
                val placeLeft = if (preferLeft) leftRoom >= railWidthPx || leftRoom >= rightRoom
                    else !(rightRoom >= railWidthPx || rightRoom >= leftRoom)
                val left = (if (placeLeft) anchorLeft - edge - railWidthPx else anchorRight + edge)
                    .coerceIn(edge, (width - railWidthPx - edge).coerceAtLeast(edge))
                // Reserve the same status room before and after asynchronous failures arrive.
                val boundsHeight = minOf(slots * rowHeightPx + statusRoom, height - edge * 2f)
                    .coerceAtLeast(1f)
                val targetTop = (start.y - slots * rowHeightPx / 2f)
                    .coerceIn(edge, (height - edge - boundsHeight).coerceAtLeast(edge))
                val top by animateFloatAsState(targetTop,
                    if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else snap(),
                    label = "source-rail-position")
                val railScroll = rememberScrollState()
                // Disc's 4dp inset and 36dp mark slot locate the glyph, not the text box.
                val markInset = with(density) { 22.dp.toPx() }
                val markCenterX = if (layoutDirection == LayoutDirection.Ltr) markInset else railWidthPx - markInset
                val railExtent = minOf(slots * rowHeightPx, height - edge * 2f).coerceAtLeast(0f)
                val landingY = { (start.y - top).coerceIn(0f, railExtent) }
                Column(Modifier.absoluteOffset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .width(railWidth).heightIn(max = availableHeight)
                    .animateContentSize(if (motion) spring(MangaMotion.NavigationDamping,
                        MangaMotion.NavigationStiffness) else snap())
                    .graphicsLayer {
                        val p = progress.value.coerceIn(0f, 1f)
                        transformOrigin = TransformOrigin(
                            (markCenterX / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f),
                            (landingY() / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f))
                        translationX = (start.x - left - markCenterX) * (1f - p)
                        translationY = (start.y - top - landingY()) * (1f - p)
                        scaleX = .62f + .38f * p
                        scaleY = scaleX
                        // Keep the glyphs visible until they reach the actual trigger.
                        alpha = if (origin == null) 0f else (p / .12f).coerceIn(0f, 1f)
                    }.verticalScroll(railScroll)) {
                    repeat(slots) { slot ->
                        val distance = { (slot + .5f) * rowHeightPx - railScroll.value - landingY() }
                        val spread = { (abs(distance()) / (rowHeightPx * (slots - 1).coerceAtLeast(1))).coerceIn(0f, 1f) }
                        val reveal = {
                            val delay = spread() * .125f
                            ((progress.value - delay) / (1f - delay)).coerceIn(0f, 1f)
                        }
                        val modifier = Modifier.fillMaxWidth().height(rowHeight)
                            .zIndex((slots - slot).toFloat())
                            .motionInputGate(canInteract)
                            .graphicsLayer {
                                val p = reveal()
                                val turn = if (slot < visibleCount && motion) pageArrival.value else 1f
                                translationY = -distance() * (1f - p)
                                translationX = (if (placeLeft) -edge else edge) *
                                    (4f * p * (1f - p) * spread() + (1f - turn) * .8f)
                                alpha = .55f + .45f * turn
                            }
                        if (slot < visibleCount) sourceItem(first + slot, modifier, close, reveal, canInteract)
                        else {
                            val interaction = remember { MutableInteractionSource() }
                            Row(modifier.clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = canInteract,
                                    interactionSource = interaction, indication = null,
                                    role = Role.Button, onClickLabel = "下一组来源",
                                    onClick = {
                                        feedback.perform(FeelEvent.Selection)
                                        page = (currentPage + 1) % pages
                                        pageTurn++
                                    })
                                .motionPress(interaction, enabled = !closing, pressedScale = .97f)
                                .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.MoreHoriz, null, Modifier.size(36.dp).padding(7.dp))
                                Text("下一组 ${currentPage + 1}/$pages", Modifier.padding(start = 6.dp)
                                    .graphicsLayer { alpha = ((reveal() - .3f) / .7f).coerceIn(0f, 1f) },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1)
                            }
                        }
                    }
                    if (hasStatus) Column(Modifier.fillMaxWidth().heightIn(max = 72.dp)
                        .motionInputGate(canInteract)
                        .graphicsLayer { alpha = ((progress.value - .5f) / .5f).coerceIn(0f, 1f) }
                        .verticalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp)) { status(canInteract) }
                }
                // A second tap at the original stack takes over the same retained flight.
                if (localAnchor != null) Box(Modifier.absoluteOffset {
                    IntOffset(localAnchor.left.roundToInt(), localAnchor.top.roundToInt())
                }.size(with(density) { localAnchor.width.toDp() }, with(density) { localAnchor.height.toDp() })
                    .clickable(enabled = !closeRequested,
                        interactionSource = remember(identity) { MutableInteractionSource() }, indication = null,
                        role = Role.Button, onClickLabel = if (closing) "展开来源" else "收起来源",
                        onClick = { if (closing) setClosing(false) else close() })
                    .semantics { contentDescription = if (closing) "展开来源" else "收起来源" })
            }
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.DesktopChapterSources(
    identity: String, title: String, chapterTitle: String, target: Rect?, backdrop: HazeState, count: Int,
    canInteract: Boolean, close: () -> Unit, reveal: () -> Float, status: (@Composable (Boolean) -> Unit)?,
    sourceItem: @Composable (Int, Modifier, () -> Unit, () -> Float, Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val availableWidth = (maxWidth - 20.dp).coerceAtLeast(1.dp)
    val panelWidth = minOf(380.dp * density.fontScale.coerceIn(1f, 1.28f), availableWidth)
    val availableHeight = (maxHeight - 20.dp).coerceAtLeast(1.dp)
    val panelMaxHeight = minOf(availableHeight, 570.dp * density.fontScale.coerceAtLeast(1f))
    val viewport = constraints
    val panelPixels = with(density) { panelWidth.roundToPx() }
    val inset = with(density) { 10.dp.roundToPx() }
    var measuredHeight by remember(identity) { mutableIntStateOf(0) }
    val left = if (target == null) (viewport.maxWidth - panelPixels) / 2 else when {
        target.right + inset + panelPixels <= viewport.maxWidth - inset -> target.right.roundToInt() + inset
        target.left - inset - panelPixels >= inset -> target.left.roundToInt() - inset - panelPixels
        else -> target.center.x.roundToInt() - panelPixels / 2
    }
    val top = target?.center?.y?.roundToInt()?.minus(measuredHeight / 2)
        ?: ((viewport.maxHeight - measuredHeight) / 2)
    val panelOffset = IntOffset(left.coerceIn(inset, (viewport.maxWidth - inset - panelPixels).coerceAtLeast(inset)),
        top.coerceIn(inset, (viewport.maxHeight - inset - measuredHeight).coerceAtLeast(inset)))
    val pivot = target?.center?.let { anchor ->
        TransformOrigin(((anchor.x - panelOffset.x) / panelPixels.coerceAtLeast(1)).coerceIn(0f, 1f),
            ((anchor.y - panelOffset.y) / measuredHeight.coerceAtLeast(1)).coerceIn(0f, 1f))
    } ?: TransformOrigin.Center
    val currentReveal by rememberUpdatedState(reveal)
    val visibleFraction = remember(identity) { derivedStateOf { if (measuredHeight == 0) 0f else currentReveal() } }
    val focus = LocalFocusManager.current
    val state = rememberLazyListState()
    val colors = MaterialTheme.colorScheme
    LiquidGlassSurface(backdrop, Modifier.absoluteOffset { panelOffset }.width(panelWidth).heightIn(max = panelMaxHeight)
        .onGloballyPositioned { measuredHeight = it.size.height }
        .anchoredMenuReveal(visibleFraction, 20.dp) { pivot }
        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
        .semantics { paneTitle = title }
        .onPreviewKeyEvent { event ->
            if (!canInteract || event.type != KeyEventType.KeyDown) false
            else when (event.key) {
                Key.DirectionUp -> focus.moveFocus(FocusDirection.Previous)
                Key.DirectionDown -> focus.moveFocus(FocusDirection.Next)
                else -> false
            }
        }, cornerRadius = 20.dp, role = GlassSurfaceRole.PANEL, softCard = true) {
        Box(Modifier.fillMaxWidth()) {
            LazyColumn(state = state, modifier = Modifier.fillMaxWidth().motionInputGate(canInteract),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 10.dp, end = 16.dp,
                    top = 8.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item("heading") {
                    Row(Modifier.fillMaxWidth().padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 5.dp)) {
                            Text(title, color = colors.onSurface, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            if (chapterTitle.isNotBlank()) Text(chapterTitle,
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        MotionIconButton(onClick = close, enabled = canInteract,
                            modifier = Modifier.size(36.dp), tooltip = "关闭来源选择") {
                            Icon(Icons.Outlined.Close, "关闭来源选择", Modifier.size(17.dp))
                        }
                    }
                }
                if (status != null) item("status") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp)) { status(canInteract) }
                }
                items(count) { index ->
                    sourceItem(index, Modifier.fillMaxWidth(), close, { 1f }, canInteract)
                }
            }
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                DesktopVerticalScrollbar(state, Modifier.fillMaxHeight())
            }
        }
    }
}

/** Prefer fields that actually distinguish this source's candidates; identical metadata stays explicit. */
internal fun chapterVariantDistinction(variant: ChapterVariant, peers: List<ChapterVariant>): String {
    if (peers.size < 2) return ""
    fun fields(value: ChapterVariant) = listOf(value.scanlator.trim(), value.edition.trim(),
        value.language.trim(), value.title.trim())
    val candidates = peers.map(::fields)
    val current = fields(variant)
    val varying = current.indices.filter { field -> candidates.map { it[field] }.distinct().size > 1 }
    val selected = mutableListOf<Int>()
    for (field in varying) {
        selected += field
        if (candidates.count { candidate -> selected.all { candidate[it] == current[it] } } == 1) break
    }
    val missing = listOf("未标注汉化组", "未标注版本", "未标注语言", "未标注标题")
    return selected.map { current[it].ifBlank { missing[it] } }.distinct().joinToString(" · ")
        .ifBlank { "版本${peers.indexOfFirst { it.storageId == variant.storageId }.coerceAtLeast(0) + 1}" }
}

/** One compact side-row; the whole source label shares the same touch and inspection target. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChapterSourceDisc(
    variant: ChapterVariant,
    backdrop: HazeState,
    modifier: Modifier,
    selected: Boolean,
    busy: Boolean = false,
    enabled: Boolean = true,
    details: String = "",
    secondary: String = "",
    entry: DownloadEntry? = null,
    resolution: ChapterResolutionState? = null,
    actionLabel: String,
    onInspect: (() -> Unit)? = null,
    resolutionActionEnabled: Boolean = true,
    revealProgress: () -> Float = { 1f },
    onClick: () -> Unit,
) {
    if (platformKind == PlatformKind.DESKTOP) {
        DesktopChapterSourceOption(variant, modifier, selected, busy, enabled, details, secondary, entry,
            resolution, resolutionActionEnabled, actionLabel, onClick)
        return
    }
    val interaction = remember(variant.storageId) { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Row(modifier.clip(RoundedCornerShape(10.dp))
        .then(if (selected) Modifier.background(colors.primary.copy(alpha = .08f)) else Modifier)
        .combinedClickable(enabled = enabled || onInspect != null,
            interactionSource = interaction, indication = null, role = Role.Button,
            onClickLabel = actionLabel, onClick = { if (enabled) onClick() },
            onLongClickLabel = "查看来源详情", onLongClick = onInspect)
        .motionPress(interaction, enabled = enabled, pressedScale = .97f)
        .semantics(mergeDescendants = true) {
            contentDescription = variant.sourceName
            stateDescription = listOfNotNull("当前来源".takeIf { selected },
                details.takeIf(String::isNotBlank), "正在准备".takeIf { busy },
                "暂不可选".takeIf { !enabled && !busy }).joinToString("，")
        }.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp)) {
            ChapterSourceLogo(variant.sourceId, variant.sourceName,
                Modifier.align(Alignment.Center).size(23.dp).graphicsLayer { alpha = if (enabled || busy) 1f else .5f })
            if (busy) MotionBusyIndicator(Modifier.align(Alignment.TopEnd).size(12.dp),
                color = colors.onSurface, strokeWidth = 1.4.dp)
            else if (selected) Box(Modifier.align(Alignment.TopEnd).size(12.dp)
                .background(colors.primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Check, null, Modifier.size(10.dp), tint = colors.onPrimary)
            }
            if (entry != null) Box(Modifier.align(Alignment.BottomEnd).size(12.dp)
                .background(colors.surface, CircleShape), contentAlignment = Alignment.Center) {
                ChapterDownloadMark(entry, showDownload = false, size = 10.dp)
            }
        }
        Column(Modifier.weight(1f).padding(start = 6.dp)
            .graphicsLayer { alpha = ((revealProgress() - .3f) / .7f).coerceIn(0f, 1f) }) {
            Text(variant.sourceName, color = colors.onSurface, fontSize = 14.sp, lineHeight = 18.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (secondary.isNotBlank()) Text(mangaNumberText(secondary), color = colors.onSurfaceVariant,
                fontSize = 12.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            resolution?.let { sample ->
                val resolutionInteraction = remember(variant.storageId) { MutableInteractionSource() }
                val configuration = LocalViewConfiguration.current
                val inlineTargets = remember(configuration) {
                    object : ViewConfiguration by configuration {
                        override val minimumTouchTargetSize = DpSize(0.dp, 0.dp)
                    }
                }
                // Restrict this independent inline action to its painted line.
                // The normal platform target must not cover the source name/logo.
                CompositionLocalProvider(LocalViewConfiguration provides inlineTargets) {
                    // Always consume its own tap, including while busy/unavailable;
                    // this never delegates to source selection.
                    Row(Modifier.fillMaxWidth().height(14.dp * fontScale)
                        .then(if (resolutionActionEnabled) Modifier.clickable(
                            interactionSource = resolutionInteraction, indication = null,
                            role = Role.Button,
                            onClickLabel = if (sample.known) "重新读取原图尺寸" else "读取原图尺寸",
                            onClick = { if (sample.canMeasure && !sample.busy) sample.measure() })
                            .motionPress(resolutionInteraction,
                                enabled = sample.canMeasure && !sample.busy, pressedScale = .98f)
                            else Modifier)
                        .semantics(mergeDescendants = true) {
                            contentDescription = variant.sourceName + "，" + sample.detail
                            stateDescription = when {
                                sample.busy -> "正在读取原图尺寸"
                                sample.error != null -> "读取失败，可重试"
                                sample.known -> "已知原图尺寸，可重新读取"
                                sample.canMeasure -> "尚未读取原图尺寸"
                                else -> "尺寸未知"
                            }
                            if (!resolutionActionEnabled || !sample.canMeasure || sample.busy) disabled()
                        }, verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        if (sample.busy) MotionBusyIndicator(Modifier.size(9.dp),
                            color = colors.onSurfaceVariant, strokeWidth = 1.dp)
                        else if (sample.error != null && sample.canMeasure)
                            Icon(Icons.Outlined.Refresh, null, Modifier.size(10.dp), tint = colors.onSurfaceVariant)
                        if (sample.known) {
                            Text(sample.compactLabel, color = colors.onSurfaceVariant,
                                style = mangaMetricValueStyle(12.sp, 14.sp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        } else Text(when {
                            sample.busy -> "读取中"
                            sample.error != null -> "读取失败"
                            else -> "尺寸未知"
                        }, color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 14.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopChapterSourceOption(
    variant: ChapterVariant, modifier: Modifier, selected: Boolean, busy: Boolean, enabled: Boolean,
    details: String, secondary: String, entry: DownloadEntry?, resolution: ChapterResolutionState?,
    resolutionActionEnabled: Boolean, actionLabel: String, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember(variant.storageId) { MutableInteractionSource() }
    var inspect by remember(variant.storageId) { mutableStateOf(false) }
    val metadata = listOfNotNull(secondary.takeIf(String::isNotBlank),
        resolution?.takeIf { it.known }?.compactLabel,
        "正在准备".takeIf { busy }).distinct().joinToString(" · ")
    Column(modifier.clip(RoundedCornerShape(12.dp))
        .background(colors.onSurface.copy(alpha = if (selected) .065f else .025f))) {
        Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null,
                    onClickLabel = actionLabel, onClick = onClick)
                .motionPress(interaction, enabled, pressedScale = .99f)
                .semantics { stateDescription = listOfNotNull("当前来源".takeIf { selected },
                    metadata.takeIf(String::isNotBlank)).joinToString("，") }
                .heightIn(min = 54.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    ChapterSourceLogo(variant.sourceId, variant.sourceName, Modifier.size(25.dp))
                    if (entry != null) Box(Modifier.align(Alignment.BottomEnd).size(13.dp)
                        .background(colors.surface, CircleShape), contentAlignment = Alignment.Center) {
                        ChapterDownloadMark(entry, showDownload = false, size = 10.dp)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(variant.sourceName, color = colors.onSurface, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (metadata.isNotBlank()) Text(mangaNumberText(metadata), color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (busy) MotionBusyIndicator(Modifier.size(15.dp), color = colors.primary, strokeWidth = 1.6.dp)
                else if (selected) Icon(Icons.Outlined.Check, "当前来源", Modifier.size(16.dp), tint = colors.primary)
            }
            if (details.isNotBlank()) MotionIconButton(onClick = { inspect = !inspect },
                modifier = Modifier.size(34.dp).semantics { stateDescription = if (inspect) "已展开" else "已收起" },
                tooltip = if (inspect) "收起来源详情" else "查看来源详情") {
                Icon(Icons.Outlined.Info, "来源详情", Modifier.size(17.dp), tint = if (inspect) colors.primary else colors.onSurfaceVariant)
            }
        }
        CopyShelfReveal(inspect) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(details, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (resolutionActionEnabled && resolution != null && (resolution.canMeasure || resolution.busy)) {
                    dev.mangaflow.ui.motion.MotionTextButton(onClick = resolution.measure, enabled = !resolution.busy) {
                        Text(when {
                            resolution.busy -> "正在读取尺寸…"
                            resolution.error != null -> "重试原图尺寸"
                            resolution.known -> "重新读取原图尺寸"
                            else -> "读取原图尺寸"
                        })
                    }
                }
            }
        }
    }
}
