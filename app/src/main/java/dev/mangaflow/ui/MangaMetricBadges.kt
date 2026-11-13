package dev.mangaflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaMetric
import dev.mangaflow.data.ReviewProviders
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.Locale

/** A single metadata stream feeds every representation of the same manga. */
internal data class MangaMetricState(
    val values: StateFlow<Map<String, List<MangaMetric>>> = MutableStateFlow(emptyMap()),
    val request: (Manga) -> Unit = {},
    val release: (String) -> Unit = {},
)

internal val LocalMangaMetrics = compositionLocalOf { MangaMetricState() }

/** Subscribe at the score's own scope; another book's response must not redraw the app. */
@Composable
internal fun observedMangaMetrics(manga: Manga, requestDelay: Long = 180L): List<MangaMetric> {
    val state = LocalMangaMetrics.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(manga, state, lifecycle, requestDelay) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            delay(requestDelay)
            state.request(manga)
            try { awaitCancellation() } finally { state.release(manga.id) }
        }
    }
    val updates = remember(state, manga.id) {
        state.values.map { it[manga.id].orEmpty() }.distinctUntilChanged()
    }
    val metrics by updates.collectAsStateWithLifecycle(initialValue = remember { state.values.value[manga.id].orEmpty() })
    return metrics
}

private data class MetricToken(
    val value: String,
    val fullValue: String,
    val description: String,
    val sourceId: String,
    val popularity: Long? = null,
)

private data class StackedMetric(val token: MetricToken, val leftPx: Int, val widthPx: Int,
    val clipStartPx: Int, val markSize: Dp)

/** One metadata line: width-fitting values followed by actual, unbacked source marks. */
@Composable
internal fun MangaMetricBadges(
    manga: Manga,
    modifier: Modifier = Modifier,
    foreground: Color = MaterialTheme.colorScheme.onSurface,
    heatLabel: String = "热",
    compact: Boolean = false,
    maxScores: Int = Int.MAX_VALUE,
    showMore: Boolean = !compact,
    plain: Boolean = false,
    valueSize: TextUnit? = null,
    onExpand: (() -> Unit)? = null,
) {
    val fan = LocalMetricSourceFan.current
    val anchorKey = remember(manga.id) { Any() }
    DisposableEffect(fan, anchorKey) {
        onDispose { if (fan != null && fan.request?.key === anchorKey) fan.clear() }
    }
    val liveMetrics = observedMangaMetrics(manga)
    val metrics = fan?.request?.takeIf { it.key === anchorKey }?.metrics ?: liveMetrics
    val heat = if (manga.rank != null) manga.popularity
        else manga.totalPopularity ?: metrics.firstOrNull { it.sourceId == "copy" }?.popularity ?: manga.popularity
    val heatToken = heat?.let { MetricToken(metricCount(it), metricCount(it), "$heatLabel $it", "copy", popularity = it) }
    val scores = metrics.filter { it.score?.let { value -> value.isFinite() && value > 0 } == true }
        .sortedBy { ReviewProviders.order(it.sourceId) }.map { metric ->
            val score = requireNotNull(metric.score)
            val nativeScore = metricScore(score) +
                (if (metric.scoreScale > 0 && (!compact || metric.scoreScale != 10)) "/${metric.scoreScale}" else "")
            MetricToken(nativeScore, metricScore(score) + (if (metric.scoreScale > 0) "/${metric.scoreScale}" else ""),
                "${metric.name}，${metric.scoreLabel}${metricScore(score)} 分" +
                    (if (metric.scoreScale > 0) "，满分 ${metric.scoreScale}" else "，原站满分未知") +
                    (metric.ratingCount?.let { "，$it 人评分" } ?: ""), metric.sourceId)
        }
    val textStyle = mangaMetricValueStyle(valueSize ?: if (compact) 10.5.sp else 12.sp)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val tokenPadding = with(density) { (if (plain) 0.dp else 2.dp).roundToPx().toFloat() }
    val innerGap = with(density) { (if (compact) 2.dp else 3.dp).roundToPx().toFloat() }
    val groupGap = if (compact) 4.dp else 7.dp
    val outerGap = with(density) { groupGap.roundToPx().toFloat() }
    val fontScale = density.fontScale.coerceAtLeast(1f)
    val markSlot = reviewSourceMarkWidth(15.dp, density.fontScale)
    val markWidth = with(density) { markSlot.roundToPx().toFloat() }
    val heatMarkSize = 14.dp * fontScale
    val heatMarkWidth = with(density) { heatMarkSize.roundToPx().toFloat() }
    val lineHeight = 22.dp * fontScale
    val markStyle = MaterialTheme.typography.labelSmall.copy(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        lineHeight = 15.sp, letterSpacing = 0.sp)
    fun tokenWidth(token: MetricToken): Float {
        val sourceWidth = if (token.sourceId == "copy") heatMarkWidth else markWidth
        return sourceWidth + innerGap + measurer.measure(token.value, textStyle, maxLines = 1).size.width + tokenPadding * 2f
    }
    if (heatToken == null && scores.isEmpty()) return

    val tokens = listOfNotNull(heatToken) + scores
    val showOverflow = compact || showMore
    val interaction = remember(manga.id) { MutableInteractionSource() }
    var stackBounds by remember(manga.id) { mutableStateOf(Rect.Zero) }
    BoxWithConstraints(modifier) {
        val availableWidth = with(density) { maxWidth.roundToPx() }
        LaunchedEffect(availableWidth, density.fontScale) {
            if (fan != null && fan.request?.key === anchorKey) fan.clear()
        }
        val narrow = compact && maxWidth < 120.dp * fontScale
        val scoreLimit = maxScores.coerceIn(0, scores.size)
        val fittedHeatMark = minOf(heatMarkWidth, availableWidth * .20f).coerceAtLeast(0f)
        val heatTextRoom = (availableWidth - fittedHeatMark - innerGap - tokenPadding * 2f - 1f).coerceAtLeast(1f)
        val heatTextWidth = heatToken?.let { measurer.measure(it.value, textStyle, maxLines = 1).size.width } ?: 0
        val heatStyle = if (heatTextWidth > heatTextRoom) textStyle.copy(
            fontSize = textStyle.fontSize * (heatTextRoom / heatTextWidth).coerceAtMost(1f)) else textStyle
        val heatWidth = heatToken?.let { fittedHeatMark + innerGap + tokenPadding * 2f +
            measurer.measure(it.value, heatStyle, maxLines = 1).size.width } ?: 0f
        val widths = scores.map(::tokenWidth)
        val baseStackSize = if (narrow) 12.dp else 15.dp
        val baseStackWidth = scores.maxOfOrNull { token ->
            val labelWidth = reviewSourceWordmark(token.sourceId)?.let {
                measurer.measure(it, markStyle.copy(fontSize =
                    (baseStackSize.value * reviewSourceLabelScale(it)).sp), maxLines = 1).size.width
            } ?: 0
            maxOf(with(density) { (baseStackSize * 1.25f * fontScale).roundToPx() },
                labelWidth + with(density) { (2.dp * fontScale).roundToPx() })
        } ?: 0
        fun stackFor(start: Int, room: Float): List<StackedMetric> {
            if (!showOverflow) return emptyList()
            val hidden = scores.drop(start).distinctBy { it.sourceId }
            if (hidden.isEmpty() || room < with(density) { (8.dp * fontScale).toPx() }) return emptyList()
            val peek = with(density) { (3.5.dp * fontScale).toPx() }
            if (room < baseStackWidth * .8f + peek * (hidden.size - 1)) return emptyList()
            // Reserve a visible edge for every rear mark before sizing the front mark.
            val rearSpace = minOf(peek * (hidden.size - 1), room * .45f)
            val width = minOf(baseStackWidth, (room - rearSpace).toInt()).coerceAtLeast(1)
            val markSize = baseStackSize * (width.toFloat() / baseStackWidth.coerceAtLeast(1))
            val step = if (hidden.size == 1) 0f else
                minOf(width * .55f, (room - width) / (hidden.size - 1)).coerceAtLeast(0f)
            val seam = with(density) { (.5.dp * fontScale).roundToPx() }
            return hidden.mapIndexed { index, token ->
                val left = (index * step).toInt()
                val previousEnd = ((index - 1) * step).toInt() + width
                StackedMetric(token, left, width,
                    if (index == 0) 0 else (previousEnd - left + seam).coerceAtMost(width), markSize)
            }
        }
        var count = scoreLimit
        var stack = emptyList<StackedMetric>()
        while (true) {
            val groups = count + if (heatToken != null) 1 else 0
            val fullWidth = heatWidth + widths.take(count).sum() + outerGap * (groups - 1).coerceAtLeast(0)
            val room = availableWidth - fullWidth - if (groups > 0) outerGap else 0f
            stack = if (count < scores.size) stackFor(count, room) else emptyList()
            if (fullWidth <= availableWidth && (count == scores.size || !showOverflow || stack.isNotEmpty())) break
            if (count == 0) break
            count--
        }
        val disclose = onExpand?.takeIf { count < scores.size }?.let { details ->
            {
                if (fan != null && stack.isNotEmpty() && stackBounds.width > 0f && stackBounds.height > 0f) {
                    fan.open(MetricSourceFanRequest(anchorKey, metrics, stackBounds,
                        stack.mapIndexed { index, item ->
                            MetricSourceFanItem(item.token.sourceId, item.token.fullValue, item.token.description,
                                item.leftPx, item.widthPx, item.clipStartPx, item.markSize, index)
                        }, lineHeight, foreground, details))
                } else details()
            }
        }
        if (heatToken == null && count == 0 && stack.isEmpty()) return@BoxWithConstraints
        Row(Modifier.fillMaxWidth().heightIn(min = lineHeight)
            .then(if (disclose != null) Modifier.clickable(interactionSource = interaction, indication = null,
                role = Role.Button, onClickLabel = "查看完整来源评分", onClick = disclose) else Modifier)
            .clearAndSetSemantics {
                contentDescription = tokens.joinToString("；") { it.description }
                if (disclose != null) {
                    role = Role.Button
                    onClick("查看完整来源评分") { disclose(); true }
                }
            },
            horizontalArrangement = Arrangement.spacedBy(groupGap), verticalAlignment = Alignment.CenterVertically) {
            heatToken?.let { MetricBadge(it, foreground, heatStyle, plain, compact,
                heatIconSize = with(density) { fittedHeatMark.toDp() }) }
            scores.take(count).forEach {
                MetricBadge(it, if (compact) foreground.copy(alpha = .76f) else foreground,
                    textStyle, plain, compact)
            }
            if (stack.isNotEmpty()) MetricSourceStack(stack, foreground, lineHeight,
                Modifier.onGloballyPositioned {
                    val position = it.localToScreen(Offset.Zero)
                    stackBounds = if (position.isSpecified) Rect(position,
                        Size(it.size.width.toFloat(), it.size.height.toFloat())) else Rect.Zero
                }
                    .graphicsLayer {
                        val request = fan?.request
                        alpha = if (request != null && request.key === anchorKey && request.popupOrigin != null) 0f else 1f
                    })
        }
    }
}

/** Front mark is complete; rear marks peek through transparent cutouts, never a painted plate. */
@Composable
private fun MetricSourceStack(items: List<StackedMetric>, foreground: Color, height: Dp,
    modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val width = with(density) { (items.last().leftPx + items.last().widthPx).toDp() }
    val onDark = foreground.luminance() > .5f
    Box(modifier.width(width).height(height)) {
        items.indices.reversed().forEach { index ->
            val item = items[index]
            ReviewSourceMark(item.token.sourceId,
                Modifier.offset { IntOffset(item.leftPx, 0) }
                    .width(with(density) { item.widthPx.toDp() }).height(height)
                    .graphicsLayer {
                        val depth = index.coerceAtMost(4)
                        translationY = -depth * .8.dp.toPx()
                        scaleX = 1f - depth * .045f
                        scaleY = scaleX
                        rotationZ = if (index == 0) 0f else -2f - depth
                        alpha = 1f - .06f * depth
                    }
                    .drawWithContent {
                        if (layoutDirection == LayoutDirection.Ltr) clipRect(left = item.clipStartPx.toFloat()) {
                            this@drawWithContent.drawContent()
                        } else clipRect(right = size.width - item.clipStartPx) { this@drawWithContent.drawContent() }
                    },
                size = item.markSize, onDark = onDark,
                alignment = if (index == 0) Alignment.Center else Alignment.CenterEnd)
        }
    }
}

@Composable
private fun MetricBadge(
    token: MetricToken,
    foreground: Color,
    textStyle: TextStyle,
    plain: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
    heatIconSize: Dp? = null,
) {
    val onDark = foreground.luminance() > .5f
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = token.description }
        .padding(horizontal = if (plain) 0.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 3.dp)) {
        if (token.sourceId == "copy") {
            val size = heatIconSize ?: 14.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
            MangaHeatFlame(token.popularity ?: 0L,
                Modifier.size(width = size, height = size * (16f / 14f)), onDark = onDark)
        } else ReviewSourceMark(token.sourceId, size = 15.dp, onDark = onDark)
        Text(token.value, color = foreground.copy(alpha = foreground.alpha * .86f), style = textStyle,
            maxLines = 1, softWrap = false)
    }
}

private fun metricScore(value: Double): String =
    if (value == value.toInt().toDouble()) value.toInt().toString() else String.format(Locale.ROOT, "%.1f", value)

private fun metricCount(value: Long): String = when {
    value >= 100_000_000 -> String.format(Locale.ROOT, "%.1f亿", value / 100_000_000.0)
    value >= 10_000 -> String.format(Locale.ROOT, "%.1f万", value / 10_000.0)
    else -> value.toString()
}

