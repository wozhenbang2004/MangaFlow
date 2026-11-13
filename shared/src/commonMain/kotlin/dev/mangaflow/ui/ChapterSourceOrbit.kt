package dev.mangaflow.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.motionPress
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private object SourceOrbitWindowOrigin : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}

/**
 * Bounded adaptation of the existing beUI screen-anchor morph.
 * Only the actual source discs own material: there is no enclosing panel.
 */
@Composable
internal fun ChapterSourceOrbit(
    identity: String,
    anchor: Rect?,
    backdrop: HazeState,
    count: Int,
    onDismiss: () -> Unit,
    closeRequested: Boolean = false,
    onCancel: () -> Unit = {},
    onPresenceChange: (Boolean) -> Unit = {},
    hasStatus: Boolean = false,
    status: @Composable () -> Unit = {},
    item: @Composable (index: Int, modifier: Modifier, dismiss: () -> Unit) -> Unit,
) {
    val sourceAnchor = remember(identity) { anchor }
    val motion = LocalMotionEnabled.current
    val density = LocalDensity.current
    var closing by remember(identity) { mutableStateOf(false) }
    var page by remember(identity) { mutableIntStateOf(0) }
    var origin by remember(identity) { mutableStateOf<Offset?>(null) }
    val onClosed by rememberUpdatedState(onDismiss)
    val cancel by rememberUpdatedState(onCancel)
    val reportPresence by rememberUpdatedState(onPresenceChange)
    SideEffect { reportPresence(true) }
    DisposableEffect(identity) { onDispose { reportPresence(false) } }
    LaunchedEffect(identity, closeRequested) { if (closeRequested) closing = true }
    val close = { if (!closing) { cancel(); closing = true } }
    val progress = remember(identity, page) { Animatable(0f) }
    LaunchedEffect(identity, page, closing, motion, origin != null) {
        if (origin == null) return@LaunchedEffect
        progress.animateTo(if (closing) 0f else 1f,
            if (!motion) snap()
            else if (closing) tween(MangaMotion.Exit, easing = MangaMotion.ExitEasing)
            else spring(dampingRatio = .86f, stiffness = MangaMotion.NavigationStiffness))
        if (closing) onClosed()
    }
    Popup(popupPositionProvider = SourceOrbitWindowOrigin, onDismissRequest = close,
        properties = PopupProperties(focusable = true, clippingEnabled = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()
            .onGloballyPositioned {
                val position = it.localToScreen(Offset.Zero)
                if (position.isSpecified) origin = position
            }.semantics { dismiss { close(); true } }) {
            val width = with(density) { maxWidth.toPx() }
            val height = with(density) { maxHeight.toPx() }
            val edge = with(density) { 8.dp.toPx() }
            val tileWidth = with(density) { 64.dp.toPx() }
            val tileHeightDp = (47f + 26f * density.fontScale.coerceAtLeast(1f)).dp
            val tileHeight = with(density) { tileHeightDp.toPx() }
            val discRadius = with(density) { 22.dp.toPx() }
            val capacity = if (maxWidth < 280.dp) 3 else 6
            val paged = count > capacity
            val pageSize = if (paged) capacity - 1 else capacity
            val pages = ((count + pageSize - 1) / pageSize).coerceAtLeast(1)
            val currentPage = page.coerceIn(0, pages - 1)
            val first = currentPage * pageSize
            val visibleCount = (count - first).coerceIn(0, pageSize)
            val slots = visibleCount + if (paged) 1 else 0
            val desiredRadius = with(density) {
                (when (slots) { 0, 1 -> 64; 2 -> 72; 3 -> 82; 4 -> 94; else -> 110 }).dp.toPx()
            }
            val radiusX = minOf(desiredRadius, ((width - tileWidth - edge * 2f) / 2f).coerceAtLeast(0f))
            val radiusY = minOf(radiusX * .86f,
                ((height - tileHeight - edge * 2f) / 2f).coerceAtLeast(0f))
            val localAnchor = sourceAnchor?.translate(-(origin ?: Offset.Zero))
            val start = localAnchor?.center ?: Offset(width / 2f, height / 2f)
            val upwards = start.y >= height / 2f
            val centerX = start.x.coerceIn(radiusX + tileWidth / 2f + edge,
                (width - radiusX - tileWidth / 2f - edge).coerceAtLeast(radiusX + tileWidth / 2f + edge))
            // Keep the end geometry stable when a request later reports loading/error details.
            val statusRoom = with(density) { 80.dp.toPx() }
            val minCenterY = edge + discRadius + if (upwards) radiusY else 0f
            val maxCenterY = (height - edge - (tileHeight - discRadius) -
                (if (upwards) statusRoom else radiusY + statusRoom)).coerceAtLeast(minCenterY)
            val centerY = start.y.coerceIn(minCenterY, maxCenterY)
            Box(Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .025f * progress.value.coerceIn(0f, 1f)))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClickLabel = "收起来源", onClick = close))
            repeat(slots) { slot ->
                val angle = when {
                    slots <= 1 -> PI / 2.0
                    slots == 2 -> PI / 3.0 + slot * PI / 3.0
                    slots == 3 -> PI / 6.0 + slot * PI / 3.0
                    else -> slot * PI / (slots - 1)
                }
                val targetX = centerX + radiusX * cos(angle).toFloat()
                val targetY = centerY + radiusY * sin(angle).toFloat() * if (upwards) -1f else 1f
                val modifier = Modifier.absoluteOffset {
                    IntOffset((targetX - tileWidth / 2f).roundToInt(), (targetY - discRadius).roundToInt())
                }.size(width = 64.dp, height = tileHeightDp)
                    .motionInputGate(!closing && progress.value >= .65f)
                    .graphicsLayer {
                        val delay = slot * .025f
                        val p = ((progress.value - delay) / (1f - delay)).coerceIn(0f, 1.04f)
                        translationX = (start.x - targetX) * (1f - p)
                        translationY = (start.y - targetY) * (1f - p)
                        scaleX = .68f + .32f * p; scaleY = scaleX
                        rotationZ = (slot - (slots - 1) / 2f) * 7f * (1f - p)
                        alpha = if (origin == null) 0f else p.coerceIn(0f, 1f)
                    }
                if (slot < visibleCount) item(first + slot, modifier, close)
                else {
                    val interaction = remember { MutableInteractionSource() }
                    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
                        LiquidGlassSurface(backdrop, Modifier.size(44.dp)
                            .clickable(enabled = !closing, interactionSource = interaction, indication = null,
                                role = Role.Button, onClickLabel = "下一组来源",
                                onClick = { page = (currentPage + 1) % pages })
                            .motionPress(interaction, enabled = !closing, pressedScale = .94f),
                            cornerRadius = 22.dp, role = GlassSurfaceRole.CONTROL,
                            interactionSource = interaction, trackPointer = true) {
                            Icon(Icons.Outlined.MoreHoriz, "下一组来源", Modifier.align(Alignment.Center).size(23.dp))
                        }
                        Text("${currentPage + 1}/$pages", Modifier.padding(top = 3.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                }
            }
            if (hasStatus) {
                val statusWidth = minOf(240.dp, (maxWidth - 16.dp).coerceAtLeast(1.dp))
                val statusY = centerY + tileHeight - discRadius + (if (upwards) 0f else radiusY) + edge
                Column(Modifier.absoluteOffset {
                    IntOffset((centerX - with(density) { statusWidth.toPx() } / 2f).roundToInt(), statusY.roundToInt())
                }.width(statusWidth).heightIn(max = 72.dp).verticalScroll(rememberScrollState())
                    .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) },
                    horizontalAlignment = Alignment.CenterHorizontally) { status() }
            }
        }
    }
}

/** A source is one readable, named circular action with compact truthful state. */
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
    actionLabel: String,
    onInspect: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember(variant.storageId) { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    Column(modifier.semantics(mergeDescendants = true) {
        contentDescription = variant.sourceName
        stateDescription = listOfNotNull("当前来源".takeIf { selected },
            details.takeIf(String::isNotBlank), "正在准备".takeIf { busy },
            "暂不可选".takeIf { !enabled && !busy }).joinToString("，")
    }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp)) {
            LiquidGlassSurface(backdrop, Modifier.fillMaxSize()
                .combinedClickable(enabled = enabled || onInspect != null,
                    interactionSource = interaction, indication = null, role = Role.Button,
                    onClickLabel = actionLabel, onClick = { if (enabled) onClick() },
                    onLongClickLabel = "查看来源详情", onLongClick = onInspect)
                .motionPress(interaction, enabled = enabled, pressedScale = .92f),
                cornerRadius = 22.dp, role = GlassSurfaceRole.CONTROL,
                interactionSource = interaction, trackPointer = true) {
                ChapterSourceLogo(variant.sourceId, variant.sourceName,
                    Modifier.align(Alignment.Center).size(24.dp).graphicsLayer { alpha = if (enabled || busy) 1f else .5f })
            }
            if (busy) MotionBusyIndicator(Modifier.align(Alignment.TopEnd).size(14.dp),
                color = colors.onSurface, strokeWidth = 1.4.dp)
            else if (selected) Box(Modifier.align(Alignment.TopEnd).size(14.dp)
                .background(colors.surface, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Check, null, Modifier.size(11.dp), tint = colors.onSurface)
            }
            if (entry != null) Box(Modifier.align(Alignment.BottomEnd).size(14.dp)
                .background(colors.surface, CircleShape), contentAlignment = Alignment.Center) {
                ChapterDownloadMark(entry, showDownload = false, size = 11.dp)
            }
        }
        Text(variant.sourceName, Modifier.padding(top = 3.dp), color = colors.onSurface,
            fontSize = 11.sp, lineHeight = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (secondary.isNotBlank()) Text(secondary, color = colors.onSurfaceVariant,
            fontSize = 9.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
