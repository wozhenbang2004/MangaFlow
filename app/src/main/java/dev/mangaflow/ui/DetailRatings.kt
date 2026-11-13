package dev.mangaflow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.data.Manga
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.ReviewProviders
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionSwap
import java.util.Locale

/** One measured band shares heat priority and source stacking with discovery cards. */
@Composable
internal fun DetailRatingStrip(manga: Manga, foreground: Color, enabled: Boolean = true, compact: Boolean = false, onExpand: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = if (compact) 28.dp else 40.dp)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = "查看各站评分、样本与口径", onClick = onExpand),
        verticalAlignment = Alignment.CenterVertically) {
        MangaMetricBadges(manga, Modifier.weight(1f), foreground = foreground, compact = true,
            plain = true, valueSize = if (compact) 12.sp else 14.sp,
            onExpand = onExpand.takeIf { enabled })
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Outlined.ChevronRight, "展开评分", tint = foreground.copy(alpha = .8f), modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun DetailRatingSheet(manga: Manga, backdrop: HazeState? = null, onDismiss: () -> Unit) {
    val allMetrics = observedMangaMetrics(manga, requestDelay = 0L)
    val metrics = allMetrics.filter { it.score?.let { score -> score.isFinite() && score > 0 } == true }
        .sortedBy { ReviewProviders.order(it.sourceId) }
    val heat = if (manga.rank != null) manga.popularity else manga.totalPopularity ?:
        allMetrics.firstOrNull { it.sourceId == "copy" }?.popularity ?: manga.popularity
    val density = LocalDensity.current
    val fontScale = density.fontScale.coerceAtLeast(1f)
    val markWidth = reviewSourceMarkWidth(20.dp, fontScale)
    val denominatorStyle = mangaMetricValueStyle(11.sp, 16.sp)
    val measurer = rememberTextMeasurer()
    val denominatorWidth = with(density) {
        (metrics.maxOfOrNull { metric ->
            measurer.measure(if (metric.scoreScale > 0) "/ " + metric.scoreScale else "",
                style = denominatorStyle).size.width
        } ?: 0).toDp()
    }
    var showNotes by remember(manga.id) { mutableStateOf(false) }
    MotionBottomSheet(onDismissRequest = onDismiss,
        backdrop = backdrop,
        containerColor = MaterialTheme.colorScheme.surface, contentColor = DetailForeground) { _ ->
        Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("各站评分", Modifier.weight(1f), color = DetailForeground,
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                MotionIconButton(onClick = { showNotes = !showNotes }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Info, if (showNotes) "收起评分说明" else "评分说明",
                        tint = DetailSecondary, modifier = Modifier.size(18.dp))
                }
            }
            MotionSwap(showNotes, Modifier.fillMaxWidth()) { visible ->
                if (visible) Text("各站保留原始分制，不合并总分。平均分、贝叶斯分与热度口径不同；人数仅在来源提供时显示。",
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    color = DetailSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                else Spacer(Modifier.height(0.dp))
            }
            heat?.let { value ->
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${if (manga.rank != null) "榜期热度" else "拷贝热度"} $value"
                    }, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.width(markWidth).heightIn(min = 24.dp * fontScale),
                        contentAlignment = Alignment.Center) {
                        MangaHeatFlame(value, Modifier.size(width = 17.5.dp * fontScale, height = 20.dp * fontScale))
                    }
                    Text(if (manga.rank != null) "榜期热度" else "拷贝热度", Modifier.weight(1f),
                        color = DetailSecondary, fontSize = 13.sp)
                    Text(detailMetricCount(value), color = DetailForeground,
                        style = mangaMetricValueStyle(20.sp, 26.sp))
                }
                Spacer(Modifier.fillMaxWidth().height(1.dp).background(DetailForeground.copy(alpha = .07f)))
                Spacer(Modifier.height(4.dp))
            }
            if (metrics.isEmpty()) Text("暂未取得可显示的评分",
                Modifier.padding(vertical = 16.dp), color = DetailSecondary, fontSize = 14.sp)
            metrics.forEach { metric ->
                val details = listOfNotNull(metric.ratingCount?.let { "$it 人" },
                    metric.scoreLabel.takeIf(String::isNotBlank)).joinToString(" · ")
                Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(vertical = 8.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = listOf(metric.name,
                            detailMetricScore(metric.score ?: 0.0) +
                                if (metric.scoreScale > 0) " / ${metric.scoreScale}" else "",
                            metric.ratingCount?.let { "$it 人评分" }.orEmpty(),
                            metric.scoreLabel).filter(String::isNotBlank).joinToString("，")
                    }, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ReviewSourceMark(metric.sourceId, Modifier.width(markWidth), size = 20.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(metric.name, color = DetailForeground, fontSize = 14.sp, lineHeight = 20.sp,
                            fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (details.isNotBlank()) Text(details, color = DetailSecondary, fontSize = 11.sp,
                            lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(detailMetricScore(metric.score ?: 0.0), Modifier.alignByBaseline(),
                            color = DetailForeground, style = mangaMetricValueStyle(20.sp, 26.sp),
                            maxLines = 1, softWrap = false)
                        Text(if (metric.scoreScale > 0) "/ ${metric.scoreScale}" else "",
                            Modifier.width(denominatorWidth).alignByBaseline(), color = DetailSecondary,
                            style = denominatorStyle, textAlign = TextAlign.Start, maxLines = 1, softWrap = false)
                    }
                }
            }
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
