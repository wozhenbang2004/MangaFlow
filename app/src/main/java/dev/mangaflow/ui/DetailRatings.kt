package dev.mangaflow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaMetric
import dev.mangaflow.data.ReviewProviders
import dev.mangaflow.ui.motion.MotionBottomSheet
import java.util.Locale

@Composable
internal fun observedDetailMetrics(manga: Manga): List<MangaMetric> {
    return observedMangaMetrics(manga, requestDelay = 0L)
        .filter { it.score?.let { score -> score.isFinite() && score > 0 } == true }
        .sortedBy { ReviewProviders.order(it.sourceId) }
}

/** One fixed-height band; native scales and algorithms remain in the disclosure. */
@Composable
internal fun DetailRatingStrip(manga: Manga, foreground: Color, enabled: Boolean = true, onExpand: () -> Unit) {
    val metrics = observedDetailMetrics(manga)
    val primary = metrics.firstOrNull { it.sourceId == "mangadex" } ?: metrics.firstOrNull()
    val ordered = listOfNotNull(primary) + metrics.filterNot { it.sourceId == primary?.sourceId }
    val heat = manga.totalPopularity ?: manga.popularity
    val valueStyle = MaterialTheme.typography.labelMedium.copy(
        fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.sp, fontFeatureSettings = "tnum")
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val values = ordered.map { metric ->
        detailMetricScore(requireNotNull(metric.score)) +
            if (metric.scoreScale > 0 && metric.scoreScale != 10) "/${metric.scoreScale}" else ""
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = "查看各站评分、样本与口径", onClick = onExpand),
        verticalAlignment = Alignment.CenterVertically) {
        BoxWithConstraints(Modifier.weight(1f)) {
            val available = with(density) { maxWidth.toPx() }
            val markAndGap = with(density) {
                (reviewSourceMarkWidth(18.dp, fontScale).roundToPx() + 4.dp.roundToPx()).toFloat()
            }
            val gap = with(density) { 10.dp.roundToPx().toFloat() }
            var used = heat?.let { markAndGap + measurer.measure(detailMetricCount(it), valueStyle,
                maxLines = 1).size.width } ?: 0f
            var count = 0
            for (value in values.take(3)) {
                val tokenWidth = markAndGap + measurer.measure(value, valueStyle, maxLines = 1).size.width
                val next = used + (if (heat != null || count > 0) gap else 0f) + tokenWidth
                if (next > available) break
                used = next
                count++
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                heat?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f, fill = false).semantics(mergeDescendants = true) {
                            contentDescription = "热度 $it"
                        }) {
                        ReviewSourceMark("copy", size = 18.dp, onDark = foreground.luminance() > .5f, heat = true)
                        Text(detailMetricCount(it), color = foreground.copy(alpha = .86f), style = valueStyle,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                }
                ordered.take(count).forEachIndexed { index, metric ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.semantics(mergeDescendants = true) {
                            contentDescription = "${metric.name} ${detailMetricScore(requireNotNull(metric.score))}" +
                                (if (metric.scoreScale > 0) "，满分 ${metric.scoreScale}" else "") + "，${metric.scoreLabel}"
                        }) {
                        ReviewSourceMark(metric.sourceId, size = 18.dp, onDark = foreground.luminance() > .5f)
                        Text(values[index], color = foreground, style = valueStyle, maxLines = 1, softWrap = false)
                    }
                }
                if (heat == null && count == 0) Icon(Icons.Outlined.StarOutline, null,
                    tint = foreground.copy(alpha = .65f), modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Outlined.ChevronRight, "展开评分", tint = foreground.copy(alpha = .8f), modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun DetailRatingSheet(manga: Manga, onDismiss: () -> Unit) {
    val allMetrics = observedMangaMetrics(manga, requestDelay = 0L)
    val metrics = allMetrics.filter { it.score?.let { score -> score.isFinite() && score > 0 } == true }
        .sortedBy { ReviewProviders.order(it.sourceId) }
    val heat = if (manga.rank != null) manga.popularity else manga.totalPopularity ?:
        allMetrics.firstOrNull { it.sourceId == "copy" }?.popularity ?: manga.popularity
    MotionBottomSheet(onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface, contentColor = DetailForeground) { _ ->
        Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("各站评分", color = DetailForeground, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("保留来源原始分制与统计口径，不合成为跨站总分。", color = DetailSecondary, fontSize = 12.sp)
            heat?.let { value ->
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                    contentDescription = "${if (manga.rank != null) "榜期热度" else "拷贝热度"} $value"
                }, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ReviewSourceMark("copy", size = 24.dp, heat = true)
                    Text(if (manga.rank != null) "榜期热度" else "拷贝热度", Modifier.weight(1f),
                        color = DetailForeground, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(detailMetricCount(value), color = DetailForeground,
                        fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (metrics.isEmpty()) Text("暂未取得可显示的评分", color = DetailSecondary, fontSize = 14.sp)
            metrics.forEach { metric ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ReviewSourceMark(metric.sourceId, size = 24.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(metric.name, color = DetailForeground, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        val details = listOfNotNull(metric.scoreLabel.takeIf(String::isNotBlank),
                            metric.ratingCount?.let { "$it 人评分" }).joinToString(" · ")
                        if (details.isNotBlank()) Text(details, color = DetailSecondary, fontSize = 12.sp)
                    }
                    Text(detailMetricScore(metric.score ?: 0.0) +
                        (if (metric.scoreScale > 0) " / ${metric.scoreScale}" else ""),
                        color = DetailForeground, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Text("贝叶斯分、平均分与热度含义不同；样本数仅在来源提供时显示。具体算法权重以来源公开规则为准。",
                color = DetailSecondary, fontSize = 12.sp, lineHeight = 19.sp)
        }
    }
}

internal fun detailMetricScore(value: Double): String =
    if (value == value.toInt().toDouble()) value.toInt().toString() else String.format(Locale.ROOT, "%.1f", value)

internal fun detailMetricCount(value: Long): String = when {
    value >= 100_000_000 -> String.format(Locale.ROOT, "%.1f亿", value / 100_000_000.0)
    value >= 10_000 -> String.format(Locale.ROOT, "%.1f万", value / 10_000.0)
    else -> value.toString()
}
