package dev.mangaflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaMetric
import dev.mangaflow.data.ReviewProviders
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import java.util.Locale

/** A single metadata stream feeds every representation of the same manga. */
internal data class MangaMetricState(
    val values: Map<String, List<MangaMetric>> = emptyMap(),
    val request: (Manga) -> Unit = {},
    val release: (String) -> Unit = {},
)

internal val LocalMangaMetrics = compositionLocalOf { MangaMetricState() }

private data class MetricToken(
    val mark: String,
    val value: String,
    val description: String,
    val sourceId: String,
)

/** A grid has at most two information lines; a list has one width-bounded information band. */
@Composable
internal fun MangaMetricBadges(
    manga: Manga,
    modifier: Modifier = Modifier,
    foreground: Color = MaterialTheme.colorScheme.onSurface,
    heatLabel: String = "热",
    compact: Boolean = false,
    maxScores: Int = if (compact) 1 else 2,
    showMore: Boolean = true,
    plain: Boolean = false,
) {
    val state = LocalMangaMetrics.current
    // Lazy containers may briefly compose a passing item; don't start work for every fling.
    LaunchedEffect(manga.id) {
        delay(180)
        state.request(manga)
        try { awaitCancellation() } finally { state.release(manga.id) }
    }
    val metrics = state.values[manga.id].orEmpty()
    val heat = if (manga.rank != null) manga.popularity
        else manga.totalPopularity ?: metrics.firstOrNull { it.sourceId == "copy" }?.popularity ?: manga.popularity
    val heatToken = heat?.let { MetricToken(if (compact) "热" else heatLabel,
        metricCount(it), "$heatLabel $it", "copy") }
    val scores = metrics.filter { it.score?.let { value -> value.isFinite() && value > 0 } == true }
        .sortedBy { ReviewProviders.order(it.sourceId) }.map { metric ->
            val score = requireNotNull(metric.score)
            val provider = ReviewProviders.find(metric.sourceId)
            val nativeScore = metricScore(score) +
                (if (metric.scoreScale > 0) "/${metric.scoreScale}" else "")
            MetricToken(provider?.mark ?: metric.sourceId, nativeScore,
                "${metric.name}，${metric.scoreLabel}${metricScore(score)} 分" +
                    (if (metric.scoreScale > 0) "，满分 ${metric.scoreScale}" else "，原站满分未知") +
                    (metric.ratingCount?.let { "，$it 人评分" } ?: ""), metric.sourceId)
        }
    val textStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    val markStyle = textStyle.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val tokenPadding = with(density) { (if (plain) 0.dp else 2.dp).toPx() }
    val innerGap = with(density) { 3.dp.toPx() }
    val outerGap = with(density) { 7.dp.toPx() }
    fun tokenWidth(token: MetricToken): Float =
        measurer.measure(token.mark, markStyle, maxLines = 1).size.width + innerGap +
            measurer.measure(token.value, textStyle, maxLines = 1).size.width + tokenPadding * 2f
    fun moreWidth(count: Int): Float =
        measurer.measure("+$count", textStyle, maxLines = 1).size.width.toFloat()

    // A source response fills this one text line without moving history/list rows.
    BoxWithConstraints(modifier.heightIn(min = with(density) { 16.sp.toDp() })) {
        val availableWidth = with(density) { maxWidth.toPx() }
        val scoreLimit = maxScores.coerceIn(0, if (compact) 1 else 2).coerceAtMost(scores.size)
        val scoreWidths = scores.take(scoreLimit).map(::tokenWidth)
        fun widthFor(count: Int): Float {
            val widths = buildList {
                if (!compact && heatToken != null) add(tokenWidth(heatToken))
                addAll(scoreWidths.take(count))
                if (showMore && scores.size > count) add(moreWidth(scores.size - count))
            }
            return widths.sum() + outerGap * (widths.size - 1).coerceAtLeast(0)
        }
        var count = scoreLimit
        while (count > 0 && widthFor(count) > availableWidth) count--
        val visible = scores.take(count)
        val hidden = scores.drop(count)
        val more = if (showMore && hidden.isNotEmpty()) hidden.size else 0
        val moreDescription = if (more > 0) "另有 $more 个已返回来源评分：" +
            hidden.joinToString("；") { it.description } else ""

        if (compact) {
            // Reserve only these two text lines, without drawing fake source placeholders.
            Column(Modifier.heightIn(min = with(density) { 16.sp.toDp() * 2f } + 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                heatToken?.let { MetricBadge(it, foreground, textStyle, markStyle, plain) }
                if (visible.isNotEmpty() || more > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        visible.forEach { MetricBadge(it, foreground, textStyle, markStyle, plain) }
                        if (more > 0) MoreMetricCount(more, moreDescription, foreground, textStyle)
                    }
                }
            }
        } else if (heatToken != null || visible.isNotEmpty() || more > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                // A very narrow band gives the accurate more-count room before clipping heat text.
                heatToken?.let { MetricBadge(it, foreground, textStyle, markStyle, plain,
                    Modifier.weight(1f, fill = false)) }
                visible.forEach { MetricBadge(it, foreground, textStyle, markStyle, plain) }
                if (more > 0) MoreMetricCount(more, moreDescription, foreground, textStyle)
            }
        }
    }
}

@Composable
private fun MetricBadge(
    token: MetricToken,
    foreground: Color,
    textStyle: TextStyle,
    markStyle: TextStyle,
    plain: Boolean,
    modifier: Modifier = Modifier,
) {
    val onDark = foreground.luminance() > .5f
    val accent = when (token.sourceId) {
        "copy" -> if (onDark) Color(0xFFD8B79E) else Color(0xFF855B43)
        "bangumi" -> if (onDark) Color(0xFFAFC5E4) else Color(0xFF526D92)
        "anilist" -> if (onDark) Color(0xFFC8B4DC) else Color(0xFF745C91)
        "mangaupdates", "mangadex" -> if (onDark) Color(0xFFD8BE96) else Color(0xFF806132)
        "myanimelist" -> if (onDark) Color(0xFFAAC5DF) else Color(0xFF426589)
        "kitsu" -> if (onDark) Color(0xFFD9AAA3) else Color(0xFF8B5148)
        else -> foreground
    }
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = token.description }
        .padding(horizontal = if (plain) 0.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(token.mark, color = accent, style = markStyle, maxLines = 1, overflow = TextOverflow.Clip)
        Text(token.value, color = foreground.copy(alpha = .86f), style = textStyle,
            modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MoreMetricCount(count: Int, description: String, foreground: Color, textStyle: TextStyle) {
    Text("+$count", color = foreground.copy(alpha = .55f), style = textStyle, maxLines = 1,
        overflow = TextOverflow.Clip, modifier = Modifier.semantics { contentDescription = description })
}

private fun metricScore(value: Double): String =
    if (value == value.toInt().toDouble()) value.toInt().toString() else String.format(Locale.ROOT, "%.1f", value)

private fun metricCount(value: Long): String = when {
    value >= 100_000_000 -> String.format(Locale.ROOT, "%.1f亿", value / 100_000_000.0)
    value >= 10_000 -> String.format(Locale.ROOT, "%.1f万", value / 10_000.0)
    else -> value.toString()
}
