package dev.mangaflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material3.Icon
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

/** One metadata line: complete values, then one graphical stack cue for remaining ratings. */
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
    val innerGap = with(density) { (if (compact) 2.dp else 3.dp).roundToPx().toFloat() }
    val groupGap = if (compact) 4.dp else 7.dp
    val outerGap = with(density) { groupGap.roundToPx().toFloat() }
    val fontScale = density.fontScale.coerceAtLeast(1f)
    val markSlot = reviewSourceMarkWidth(15.dp, density.fontScale)
    val markWidth = with(density) { markSlot.roundToPx().toFloat() }
    val heatMarkSize = (if (compact) 12.dp else 15.dp) * fontScale
    val heatMarkWidth = with(density) { heatMarkSize.roundToPx().toFloat() }
    val stackSize = 14.dp * fontScale
    val stackWidth = with(density) { stackSize.roundToPx().toFloat() }
    fun tokenWidth(token: MetricToken): Float {
        val sourceWidth = if (token.sourceId == "copy") heatMarkWidth else markWidth
        return sourceWidth + innerGap + measurer.measure(token.value, textStyle, maxLines = 1).size.width + tokenPadding * 2f
    }
    if (heatToken == null && scores.isEmpty()) return

    val showOverflow = compact || showMore
    BoxWithConstraints(modifier) {
        val availableWidth = with(density) { maxWidth.toPx() }
        // Narrow covers keep useful heat rather than spending the row on cropped source letters.
        val visibleHeat = if (compact && heatToken != null && scores.isNotEmpty() && showOverflow &&
            tokenWidth(heatToken) + outerGap + stackWidth > availableWidth) {
            heatToken.copy(value = metricCount(requireNotNull(heat), concise = true))
        } else heatToken
        val tokens = listOfNotNull(visibleHeat) + scores
        val scoreLimit = maxScores.coerceIn(0, if (compact) 1 else 2).coerceAtMost(scores.size)
        val widths = tokens.map(::tokenWidth)
        fun widthFor(count: Int): Float {
            val overflow = showOverflow && count < tokens.size
            val groups = count + if (overflow) 1 else 0
            return widths.take(count).sum() + (if (overflow) stackWidth else 0f) +
                outerGap * (groups - 1).coerceAtLeast(0)
        }
        var count = scoreLimit + if (heatToken != null) 1 else 0
        while (count > 0 && widthFor(count) > availableWidth) count--
        var overflow = showOverflow && count < tokens.size && stackWidth <= availableWidth
        if (count == 0 && (heatToken != null || scoreLimit > 0) && widths.first() <= availableWidth) {
            // A large-font/narrow row must not discard its primary value just to retain this cue.
            count = 1
            overflow = false
        }
        Row(Modifier.fillMaxWidth().heightIn(min = with(density) { 16.sp.toDp() })
            .clearAndSetSemantics { contentDescription = tokens.joinToString("；") { it.description } },
            horizontalArrangement = Arrangement.spacedBy(groupGap), verticalAlignment = Alignment.CenterVertically) {
            tokens.take(count).forEach {
                MetricBadge(it, if (compact) foreground.copy(alpha = .76f) else foreground,
                    textStyle, plain, compact)
            }
            if (overflow) Icon(Icons.Outlined.Layers, null, Modifier.size(stackSize),
                tint = foreground.copy(alpha = .5f))
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
) {
    val onDark = foreground.luminance() > .5f
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = token.description }
        .padding(horizontal = if (plain) 0.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 3.dp)) {
        if (token.sourceId == "copy") {
            val size = (if (compact) 12.dp else 15.dp) * LocalDensity.current.fontScale.coerceAtLeast(1f)
            Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.LocalFireDepartment, null, Modifier.size(size * .88f),
                    tint = reviewSourceAccent("copy", onDark))
            }
        } else ReviewSourceMark(token.sourceId, size = 15.dp, onDark = onDark)
        Text(token.value, color = foreground.copy(alpha = foreground.alpha * .86f), style = textStyle,
            modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun metricScore(value: Double): String =
    if (value == value.toInt().toDouble()) value.toInt().toString() else String.format(Locale.ROOT, "%.1f", value)

private fun metricCount(value: Long, concise: Boolean = false): String = when {
    value >= 100_000_000 -> String.format(Locale.ROOT,
        if (concise && value >= 10_000_000_000L) "%.0f亿" else "%.1f亿", value / 100_000_000.0)
    value >= 10_000 -> String.format(Locale.ROOT,
        if (concise && value >= 1_000_000) "%.0f万" else "%.1f万", value / 10_000.0)
    else -> value.toString()
}
