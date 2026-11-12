package dev.mangaflow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaMetric
import dev.mangaflow.data.ReviewProviders
import dev.mangaflow.ui.motion.MotionBottomSheet
import java.util.Locale
import kotlinx.coroutines.awaitCancellation

@Composable
internal fun observedDetailMetrics(manga: Manga): List<MangaMetric> {
    val state = LocalMangaMetrics.current
    LaunchedEffect(manga.id) {
        state.request(manga)
        try { awaitCancellation() } finally { state.release(manga.id) }
    }
    return state.values[manga.id].orEmpty()
        .filter { it.score?.let { score -> score.isFinite() && score > 0 } == true }
        .sortedBy { ReviewProviders.order(it.sourceId) }
}

/** One fixed-height band; native scales and algorithms remain in the disclosure. */
@Composable
internal fun DetailRatingStrip(manga: Manga, foreground: Color, enabled: Boolean = true, onExpand: () -> Unit) {
    val metrics = observedDetailMetrics(manga)
    val primary = metrics.firstOrNull { it.sourceId == "mangadex" } ?: metrics.firstOrNull()
    val visible = (listOfNotNull(primary) + metrics.filterNot { it.sourceId == primary?.sourceId }).take(3)
    val heat = manga.totalPopularity ?: manga.popularity
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = "查看各站评分、样本与口径", onClick = onExpand),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState(), enabled = enabled),
            horizontalArrangement = Arrangement.spacedBy(15.dp), verticalAlignment = Alignment.CenterVertically) {
            heat?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "热度 $it" }) {
                    ReviewSourceMark("copy", size = 18.dp, onDark = foreground.luminance() > .5f, heat = true)
                    Text(detailMetricCount(it), color = foreground.copy(alpha = .86f), fontSize = 12.sp)
                }
            }
            visible.forEach { metric ->
                val score = metric.score ?: return@forEach
                val scale = if (metric.scoreScale > 0 && metric.scoreScale != 10) "/${metric.scoreScale}" else ""
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        contentDescription = "${metric.name} ${detailMetricScore(score)}" +
                            (if (metric.scoreScale > 0) "，满分 ${metric.scoreScale}" else "") + "，${metric.scoreLabel}"
                    }) {
                    ReviewSourceMark(metric.sourceId, size = if (metric == primary) 21.dp else 18.dp,
                        onDark = foreground.luminance() > .5f)
                    Text(detailMetricScore(score) + scale, color = foreground,
                        fontSize = if (metric == primary) 17.sp else 13.sp,
                        fontWeight = if (metric == primary) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
            if (metrics.isEmpty()) Text("各站评分", color = foreground.copy(alpha = .75f), fontSize = 12.sp)
        }
        Spacer(Modifier.width(6.dp))
        if (metrics.size > 3) Text("+${metrics.size - 3}", color = foreground.copy(alpha = .75f), fontSize = 11.sp)
        Icon(Icons.Outlined.ChevronRight, "展开评分", tint = foreground.copy(alpha = .8f), modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun DetailRatingSheet(manga: Manga, onDismiss: () -> Unit) {
    val metrics = observedDetailMetrics(manga)
    MotionBottomSheet(onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface, contentColor = DetailForeground) { _ ->
        Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("各站评分", color = DetailForeground, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("保留来源原始分制与统计口径，不合成为跨站总分。", color = DetailSecondary, fontSize = 12.sp)
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
