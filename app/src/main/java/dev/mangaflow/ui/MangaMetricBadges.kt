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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaMetric
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
            MetricBadge("拷", "$heatLabel ${metricCount(value)}", "拷贝漫画，$heatLabel $value", foreground)
        }
        listOf("bangumi", "anilist").forEach { id ->
            val metric = metrics.firstOrNull { it.sourceId == id }
            metric?.score?.takeIf { it.isFinite() && it > 0 }?.let { score ->
                MetricBadge(if (id == "bangumi") "bgm" else "AL", metricScore(score),
                    "${metric.name}，${metricScore(score)} 分，满分 ${metric.scoreScale}" +
                        (metric.ratingCount?.let { "，$it 人评分" } ?: ""), foreground)
            }
        }
    }
}

@Composable
private fun MetricBadge(mark: String, value: String, description: String, foreground: Color) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(foreground.copy(alpha = .055f))
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 5.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(mark, color = foreground.copy(alpha = .56f), fontSize = 10.sp, lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(value, color = foreground.copy(alpha = .82f), fontSize = 11.sp, lineHeight = 14.sp,
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
