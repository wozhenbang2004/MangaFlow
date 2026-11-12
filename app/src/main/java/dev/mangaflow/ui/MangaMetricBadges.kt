package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MangaMetricBadges(
    manga: Manga,
    modifier: Modifier = Modifier,
    foreground: Color = MaterialTheme.colorScheme.onSurface,
    heatLabel: String = "热",
    compact: Boolean = false,
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
    FlowRow(
        modifier = modifier.heightIn(min = if (compact) 44.dp else 22.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        maxItemsInEachRow = if (compact) 2 else Int.MAX_VALUE,
    ) {
        heat?.let { value ->
            MetricBadge(if (compact) "热" else heatLabel, metricCount(value),
                "$heatLabel $value", foreground, compact, "copy")
        }
        val scores = metrics.filter { it.score?.let { value -> value.isFinite() && value > 0 } == true }
            .sortedBy { ReviewProviders.order(it.sourceId) }
        val visible = if (compact) scores.take(2) else scores
        visible.forEach { metric ->
            metric.score?.let { score ->
                val provider = ReviewProviders.find(metric.sourceId)
                val value = if (!compact && metric.scoreLabel.isNotBlank())
                    "${metricScore(score)} · ${metric.scoreLabel}" else metricScore(score)
                MetricBadge(provider?.mark ?: metric.sourceId, value,
                    "${metric.name}，${metric.scoreLabel}${metricScore(score)} 分，满分 ${metric.scoreScale}" +
                        (metric.ratingCount?.let { "，$it 人评分" } ?: ""), foreground, compact, metric.sourceId)
            }
        }
        if (compact && scores.size > visible.size) {
            MetricBadge("+${scores.size - visible.size}", "评分", "更多来源评分可在漫画详情查看",
                foreground, compact, "more")
        }
    }
}

@Composable
private fun MetricBadge(
    mark: String,
    value: String,
    description: String,
    foreground: Color,
    compact: Boolean,
    sourceId: String,
) {
    val onDark = foreground.luminance() > .5f
    val accent = when (sourceId) {
        "copy" -> if (onDark) Color(0xFFD8B79E) else Color(0xFF855B43)
        "bangumi" -> if (onDark) Color(0xFFAFC5E4) else Color(0xFF526D92)
        "anilist" -> if (onDark) Color(0xFFC8B4DC) else Color(0xFF745C91)
        "mangaupdates", "mangadex" -> if (onDark) Color(0xFFD8BE96) else Color(0xFF806132)
        "myanimelist" -> if (onDark) Color(0xFFAAC5DF) else Color(0xFF426589)
        "kitsu" -> if (onDark) Color(0xFFD9AAA3) else Color(0xFF8B5148)
        else -> foreground
    }
    Row(
        modifier = Modifier.clip(RoundedCornerShape(6.dp))
            .background(accent.copy(alpha = if (onDark) .14f else .085f))
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = if (compact) 4.dp else 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 5.dp),
    ) {
        Text(mark, color = accent, fontSize = 10.sp, lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(value, color = foreground.copy(alpha = .88f),
            fontSize = if (compact) 10.sp else 11.sp, lineHeight = 14.sp,
            fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

private fun metricScore(value: Double): String =
    if (value == value.toInt().toDouble()) value.toInt().toString() else String.format(Locale.ROOT, "%.1f", value)

private fun metricCount(value: Long): String = when {
    value >= 100_000_000 -> String.format(Locale.ROOT, "%.1f亿", value / 100_000_000.0)
    value >= 10_000 -> String.format(Locale.ROOT, "%.1f万", value / 10_000.0)
    else -> value.toString()
}

