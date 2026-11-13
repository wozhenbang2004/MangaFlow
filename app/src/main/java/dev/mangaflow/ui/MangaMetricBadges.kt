package dev.mangaflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    val metrics by updates.collectAsStateWithLifecycle(initialValue = state.values.value[manga.id].orEmpty())
    return metrics
}

private data class MetricToken(
    val value: String,
    val description: String,
    val sourceId: String,
)

/** One metadata line: complete values that fit, then overlapping marks for the remaining sources. */
@Composable
internal fun MangaMetricBadges(
    manga: Manga,
    modifier: Modifier = Modifier,
    foreground: Color = MaterialTheme.colorScheme.onSurface,
    heatLabel: String = "热",
    compact: Boolean = false,
    maxScores: Int = if (compact) 1 else 2,
    showMore: Boolean = !compact,
    plain: Boolean = false,
) {
    val metrics = observedMangaMetrics(manga)
    val heat = if (manga.rank != null) manga.popularity
        else manga.totalPopularity ?: metrics.firstOrNull { it.sourceId == "copy" }?.popularity ?: manga.popularity
    val heatToken = heat?.let { MetricToken(metricCount(it), "$heatLabel $it", "copy") }
    val scores = metrics.filter { it.score?.let { value -> value.isFinite() && value > 0 } == true }
        .sortedBy { ReviewProviders.order(it.sourceId) }.map { metric ->
            val score = requireNotNull(metric.score)
            val nativeScore = metricScore(score) +
                (if (metric.scoreScale > 0 && (!compact || metric.scoreScale != 10)) "/${metric.scoreScale}" else "")
            MetricToken(nativeScore,
                "${metric.name}，${metric.scoreLabel}${metricScore(score)} 分" +
                    (if (metric.scoreScale > 0) "，满分 ${metric.scoreScale}" else "，原站满分未知") +
                    (metric.ratingCount?.let { "，$it 人评分" } ?: ""), metric.sourceId)
        }
    val textStyle = MaterialTheme.typography.labelSmall.copy(
        fontFamily = FontFamily.SansSerif, fontSize = 11.sp, lineHeight = 16.sp,
        letterSpacing = 0.sp, fontFeatureSettings = "tnum",
        fontWeight = if (compact) FontWeight.Normal else FontWeight.Medium)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val tokenPadding = with(density) { (if (plain) 0.dp else 2.dp).roundToPx().toFloat() }
    val innerGap = with(density) { 3.dp.roundToPx().toFloat() }
    val outerGap = with(density) { 7.dp.roundToPx().toFloat() }
    val markSlot = reviewSourceMarkWidth(15.dp, density.fontScale)
    val stackStep = 8.dp * density.fontScale.coerceAtLeast(1f)
    val markWidth = with(density) { markSlot.roundToPx().toFloat() }
    fun tokenWidth(token: MetricToken): Float {
        return markWidth + innerGap + measurer.measure(token.value, textStyle, maxLines = 1).size.width + tokenPadding * 2f
    }
    if (heatToken == null && scores.isEmpty()) return

    val tokens = listOfNotNull(heatToken) + scores
    val showOverflow = compact || showMore
    val onDark = foreground.luminance() > .5f
    val stackBacking = if (onDark) Color(0xFF29292C) else MaterialTheme.colorScheme.surface
    BoxWithConstraints(modifier) {
        val availableWidth = with(density) { maxWidth.toPx() }
        val scoreLimit = maxScores.coerceIn(0, if (compact) 1 else 2).coerceAtMost(scores.size)
        val widths = tokens.map(::tokenWidth)
        fun stackWidth(count: Int): Float = if (count == 0) 0f else with(density) {
            (markSlot + stackStep * (count - 1)).roundToPx().toFloat()
        }
        fun widthFor(count: Int, marks: Int): Float {
            val groups = count + if (marks > 0) 1 else 0
            return widths.take(count).sum() + stackWidth(marks) + outerGap * (groups - 1).coerceAtLeast(0)
        }
        var count = scoreLimit + if (heatToken != null) 1 else 0
        var marks = 0
        while (true) {
            marks = if (showOverflow) tokens.drop(count).distinctBy { it.sourceId }.size.coerceAtMost(3) else 0
            if (widthFor(count, marks) <= availableWidth) break
            if (count > 0) count-- else {
                while (marks > 0 && stackWidth(marks) > availableWidth) marks--
                break
            }
        }
        val hidden = tokens.drop(count).distinctBy { it.sourceId }.take(marks)
        Row(Modifier.fillMaxWidth().heightIn(min = with(density) { 16.sp.toDp() })
            .clearAndSetSemantics { contentDescription = tokens.joinToString("；") { it.description } },
            horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            tokens.take(count).forEach {
                MetricBadge(it, if (compact) foreground.copy(alpha = .76f) else foreground, textStyle, plain)
            }
            if (hidden.isNotEmpty()) Box(Modifier.size(
                width = markSlot + stackStep * (hidden.size - 1),
                height = 15.dp * density.fontScale.coerceAtLeast(1f))) {
                // Draw the first hidden source last so its whole mark stays legible at the front.
                hidden.indices.reversed().forEach { index ->
                    ReviewSourceMark(hidden[index].sourceId,
                        Modifier.offset(x = stackStep * index).then(if (index < hidden.lastIndex)
                            Modifier.background(stackBacking, RoundedCornerShape(3.dp)) else Modifier),
                        size = 15.dp, onDark = onDark, heat = hidden[index].sourceId == "copy")
                }
            }
        }
    }
}

@Composable
private fun MetricBadge(
    token: MetricToken,
    foreground: Color,
    textStyle: TextStyle,
    plain: Boolean,
    modifier: Modifier = Modifier,
) {
    val onDark = foreground.luminance() > .5f
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = token.description }
        .padding(horizontal = if (plain) 0.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        ReviewSourceMark(token.sourceId, size = 15.dp, onDark = onDark, heat = token.sourceId == "copy")
        Text(token.value, color = foreground.copy(alpha = foreground.alpha * .86f), style = textStyle,
            modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun metricScore(value: Double): String =
    if (value == value.toInt().toDouble()) value.toInt().toString() else String.format(Locale.ROOT, "%.1f", value)

private fun metricCount(value: Long): String = when {
    value >= 100_000_000 -> String.format(Locale.ROOT, "%.1f亿", value / 100_000_000.0)
    value >= 10_000 -> String.format(Locale.ROOT, "%.1f万", value / 10_000.0)
    else -> value.toString()
}
