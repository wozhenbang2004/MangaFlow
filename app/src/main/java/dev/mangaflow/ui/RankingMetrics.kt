package dev.mangaflow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.data.Manga

/** Period heat and native scores have their own baselines; the whole group opens all sources. */
@Composable
internal fun RankingMetrics(manga: Manga, heatLabel: String, onExpand: () -> Unit) {
    val scores = observedMangaMetrics(manga)
        .filter { it.score?.let { value -> value.isFinite() && value > 0 } == true }
        .sortedBy { dev.mangaflow.data.ReviewProviders.order(it.sourceId) }
    val foreground = MaterialTheme.colorScheme.onSurface
    val onDark = foreground.luminance() > .5f
    val valueStyle = mangaMetricValueStyle()
    val density = LocalDensity.current
    val markSlot = reviewSourceMarkWidth(16.dp, density.fontScale)
    val measurer = rememberTextMeasurer()
    val values = scores.map { metric ->
        detailMetricScore(requireNotNull(metric.score)) +
            if (metric.scoreScale > 0) "/${metric.scoreScale}" else ""
    }
    Column(Modifier.fillMaxWidth().heightIn(min = 44.dp)
        .clickable(role = Role.Button, onClickLabel = "查看全部来源评分", onClick = onExpand)
        .semantics(mergeDescendants = true) {
            contentDescription = listOfNotNull(manga.popularity?.let { "$heatLabel $it" },
                scores.takeIf { it.isNotEmpty() }?.joinToString("；") {
                    "${it.name} ${detailMetricScore(requireNotNull(it.score))}" +
                        if (it.scoreScale > 0) "，满分 ${it.scoreScale}" else "，原站满分未知"
                }).joinToString("；") + "；查看全部来源评分"
        }, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
        Row(Modifier.heightIn(min = with(density) { 16.sp.toDp() }),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            manga.popularity?.let { heat ->
                MangaHeatFlame(heat, Modifier.size(width = 14.dp * density.fontScale.coerceAtLeast(1f),
                    height = 16.dp * density.fontScale.coerceAtLeast(1f)), onDark = onDark)
                Text(detailMetricCount(heat), style = valueStyle,
                    color = foreground.copy(alpha = .78f), maxLines = 1)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 20.dp)) {
            val available = with(density) { (maxWidth - 18.dp).toPx().coerceAtLeast(0f) }
            val gap = with(density) { 7.dp.roundToPx().toFloat() }
            val markAndGap = with(density) { (markSlot.roundToPx() + 3.dp.roundToPx()).toFloat() }
            var used = 0f
            var count = 0
            for (value in values.take(3)) {
                val width = markAndGap + measurer.measure(value, valueStyle, maxLines = 1).size.width
                val next = used + (if (count > 0) gap else 0f) + width
                if (next > available) break
                used = next
                count++
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (count == 0) Text("各站评分", style = valueStyle, color = foreground.copy(alpha = .46f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    scores.take(count).forEachIndexed { index, metric ->
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            ReviewSourceMark(metric.sourceId, size = 16.dp, onDark = onDark)
                            Text(values[index], style = valueStyle, color = foreground.copy(alpha = .78f), maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Outlined.ChevronRight, null, modifier = Modifier.size(14.dp),
                    tint = foreground.copy(alpha = .38f))
            }
        }
    }
}
