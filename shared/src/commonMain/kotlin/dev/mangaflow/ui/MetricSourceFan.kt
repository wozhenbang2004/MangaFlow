package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

import dev.mangaflow.platform.*

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
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
import dev.mangaflow.data.MangaMetric
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeSourceRetention
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class MetricSourceFanItem(
    val sourceId: String,
    val value: String,
    val description: String,
    val leftPx: Int,
    val widthPx: Int,
    val clipStartPx: Int,
    val markSize: Dp,
)

internal class MetricSourceFanRequest(
    val key: Any,
    val metrics: List<MangaMetric>,
    val anchor: Rect,
    val items: List<MetricSourceFanItem>,
    val lineHeight: Dp,
    val foreground: Color,
    val onDetails: () -> Unit,
) {
    var popupOrigin by mutableStateOf<Offset?>(null)
}

/** One discovery owner, as with the existing ratings sheet; cells retain only their anchor. */
internal class MetricSourceFanState {
    var request by mutableStateOf<MetricSourceFanRequest?>(null)
        private set
    var closing by mutableStateOf(false)
        private set
    private var afterClose: (() -> Unit)? = null

    fun open(value: MetricSourceFanRequest) {
        if (request != null) return
        closing = false
        afterClose = null
        request = value
    }

    fun close(after: (() -> Unit)? = null) {
        if (request == null || closing) return
        afterClose = after
        closing = true
    }

    fun finish(value: MetricSourceFanRequest) {
        if (request !== value) return
        val action = afterClose
        clear()
        action?.invoke()
    }

    fun clear() {
        request = null
        closing = false
        afterClose = null
    }
}

internal val LocalMetricSourceFan = compositionLocalOf<MetricSourceFanState?> { null }

private object FanWindowOrigin : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}

/** Reuse source marks, the app's motion roles and reference-keyed modal ownership.
 *  No card measures change, and reversing one progress retraces every icon's flight. */
@Composable
internal fun MetricSourceFan(state: MetricSourceFanState, backdrop: HazeState? = null) {
    val request = state.request ?: return
    val motion = LocalMotionEnabled.current
    val progress = remember(request) { Animatable(0f) }
    val closing = state.closing
    val ready by remember(request, closing) { derivedStateOf { !closing && progress.value >= .99f } }
    val origin = request.popupOrigin
    LaunchedEffect(request, closing, motion, origin != null) {
        if (origin == null) {
            if (closing) state.finish(request)
            return@LaunchedEffect
        }
        progress.animateTo(if (closing) 0f else 1f,
            if (motion) tween(240, easing = MangaMotion.EnterEasing) else snap())
        if (closing) state.finish(request)
    }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val onDark = request.foreground.luminance() > .5f
    val backing = if (onDark) Color(0xFF19181C) else MaterialTheme.colorScheme.surface
    val scrim = MaterialTheme.colorScheme.scrim
    val blurInput = remember(backdrop) {
        backdrop?.let { HazeInput.Sources(it, retention = HazeSourceRetention.KeepLastFrame) }
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
    val interaction = remember(request) { MutableInteractionSource() }
    Popup(popupPositionProvider = FanWindowOrigin, onDismissRequest = { state.close() },
        properties = PopupProperties(focusable = true, clippingEnabled = false)) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()
                .then(if (blurInput != null) Modifier.hazeBlur(input = blurInput, style = blurStyle,
                    expandLayerBounds = false) else Modifier
                    .graphicsLayer { alpha = progress.value }
                    .drawBehind { drawRect(backing.copy(alpha = .16f)) })
                .clickable(interactionSource = interaction, indication = null,
                    role = Role.Button, onClickLabel = "收起评分来源", onClick = { state.close() }))
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()
            .onGloballyPositioned {
                val position = it.localToScreen(Offset.Zero)
                if (position.isSpecified) request.popupOrigin = position
            }
            .semantics { dismiss { state.close(); true } }) {
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }
            val fontScale = density.fontScale.coerceAtLeast(1f)
            val inset = with(density) { 20.dp.toPx() }
            val cellWidth = with(density) { (64.dp * fontScale).toPx() }
            val rowHeight = with(density) { (64.dp * fontScale).toPx() }
            val arch = with(density) { (12.dp * fontScale).toPx() }
            val columns = ((widthPx - 2 * inset) / cellWidth).toInt()
                .coerceIn(1, request.items.size.coerceAtMost(5))
            val rows = ceil(request.items.size.toFloat() / columns).toInt()
            val fanWidth = columns * cellWidth
            val fanHeight = rows * rowHeight + arch
            // Large accessibility type/short windows keep the original, scrollable disclosure.
            if (fanWidth > widthPx - 2 * inset || fanHeight > heightPx - 2 * inset) {
                LaunchedEffect(request) {
                    if (state.request === request) { state.clear(); request.onDetails() }
                }
                return@BoxWithConstraints
            }
            val screenOrigin = origin ?: return@BoxWithConstraints
            val anchor = request.anchor.translate(-screenOrigin)
            val centerX = anchor.center.x.coerceIn(inset + fanWidth / 2f, widthPx - inset - fanWidth / 2f)
            val gap = with(density) { 12.dp.toPx() }
            val preferredTop = if (anchor.top >= fanHeight + gap + inset)
                anchor.top - gap - fanHeight else anchor.bottom + gap
            val top = preferredTop.coerceIn(inset, heightPx - inset - fanHeight)
            request.items.indices.reversed().forEach { index ->
                val item = request.items[index]
                val row = index / columns
                val column = index % columns
                val rowCount = minOf(columns, request.items.size - row * columns)
                val spread = column - (rowCount - 1) / 2f
                val targetX = centerX + spread * cellWidth
                val normalized = if (rowCount <= 1) 0f else spread * 2f / (rowCount - 1)
                val targetY = top + row * rowHeight + arch * normalized * normalized + rowHeight * .25f
                val sourceLeft = if (layoutDirection == LayoutDirection.Ltr)
                    anchor.left + item.leftPx else anchor.right - item.leftPx - item.widthPx
                val startX = sourceLeft + item.widthPx / 2f
                val startY = anchor.center.y
                val lineHeightPx = with(density) { request.lineHeight.toPx() }
                fun flight(): Float = ((progress.value - index * .018f) / (1f - index * .018f)).coerceIn(0f, 1f)
                ReviewSourceMark(item.sourceId,
                    Modifier.absoluteOffset {
                        val p = flight()
                        IntOffset((startX + (targetX - startX) * p - item.widthPx / 2f).roundToInt(),
                            (startY + (targetY - startY) * p - lineHeightPx / 2f -
                                sin(p * PI).toFloat() * arch).roundToInt())
                    }.width(with(density) { item.widthPx.toDp() }).height(request.lineHeight)
                        .graphicsLayer {
                            val p = flight()
                            scaleX = 1f + .8f * p
                            scaleY = scaleX
                        }.drawWithContent {
                            val cut = item.clipStartPx * (1f - flight())
                            if (layoutDirection == LayoutDirection.Ltr) clipRect(left = cut) {
                                this@drawWithContent.drawContent()
                            } else clipRect(right = size.width - cut) { this@drawWithContent.drawContent() }
                        }, size = item.markSize, onDark = onDark,
                    alignment = if (index == 0) Alignment.Center else Alignment.CenterEnd)

                Text(item.value, Modifier.absoluteOffset {
                    val p = flight()
                    IntOffset((startX + (targetX - startX) * p - cellWidth / 2f).roundToInt(),
                        (startY + (targetY - startY) * p + with(density) { (14.dp * fontScale).toPx() }).roundToInt())
                }.width(with(density) { cellWidth.toDp() })
                    .graphicsLayer { alpha = ((flight() - .35f) / .65f).coerceIn(0f, 1f) }
                    .clearAndSetSemantics { }, color = request.foreground,
                    style = mangaMetricValueStyle(12.sp, 16.sp), textAlign = TextAlign.Center,
                    maxLines = 1, softWrap = false)
                Box(Modifier.absoluteOffset {
                    IntOffset((targetX - cellWidth / 2f).roundToInt(),
                        (targetY - rowHeight * .25f).roundToInt())
                }.size(with(density) { cellWidth.toDp() }, with(density) { rowHeight.toDp() })
                    .then(if (ready) Modifier.clickable(interactionSource = remember(request, index) {
                        MutableInteractionSource()
                    }, indication = null, role = Role.Button, onClickLabel = "查看各站评分详情") {
                        state.close(request.onDetails)
                    }.semantics { contentDescription = item.description } else Modifier.clearAndSetSemantics { }))
            }
        }
        }
    }
}
