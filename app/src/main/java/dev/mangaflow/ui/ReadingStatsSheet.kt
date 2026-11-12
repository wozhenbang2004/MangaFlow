package dev.mangaflow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.storage.ReadingActivity
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.motionPress
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Lifetime totals and the last fourteen local calendar days use the recorded time only. */
@Composable
internal fun ReadingStatsSheet(
    activity: List<ReadingActivity>,
    repository: MangaRepository,
    onDismiss: () -> Unit,
    onOpenManga: (Manga) -> Unit,
) {
    var period by remember { mutableIntStateOf(0) }
    val today = LocalDate.now()
    val dates = remember(today) { (13L downTo 0L).map(today::minusDays) }
    val since = dates.first().toString()
    val until = today.toString()
    fun recent(item: ReadingActivity): Long = item.days.entries
        .filter { it.key >= since && it.key <= until }.sumOf { it.value }
    val total = activity.sumOf { it.totalMillis }
    val fortnight = activity.sumOf(::recent)
    val daily = dates.map { day -> activity.sumOf { it.days[day.toString()] ?: 0L } }
    val ordered = activity.filter { if (period == 0) recent(it) > 0L else it.totalMillis > 0L }
        .sortedWith(compareByDescending<ReadingActivity> { if (period == 0) recent(it) else it.totalMillis }
            .thenByDescending { it.lastReadAt })
    val colors = MaterialTheme.colorScheme

    MotionBottomSheet(onDismissRequest = onDismiss, containerColor = colors.background) { dismiss ->
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("阅读统计", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Close, "关闭阅读统计", Modifier.size(20.dp), tint = colors.onSurfaceVariant)
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp).navigationBarsPadding(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        ) {
            item(key = "totals") {
                Row(Modifier.fillMaxWidth().padding(bottom = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    ReadingTimeValue("累计阅读", total, Modifier.weight(1f))
                    ReadingTimeValue("近两周", fortnight, Modifier.weight(1f))
                }
            }
            item(key = "daily-time") {
                DailyReadingChart(dates, daily)
                Text("仅累计前台阅读时间", color = colors.onSurfaceVariant.copy(alpha = .78f),
                    fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
            }
            item(key = "period") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("按漫画", color = colors.onSurface, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    MotionTabs(period, listOf("近两周", "累计"), { period = it },
                        modifier = Modifier.width(172.dp), fontSize = 12.sp,
                        muted = colors.onSurfaceVariant, selectedFontWeight = FontWeight.SemiBold,
                        unselectedFontWeight = FontWeight.Normal)
                }
            }
            if (ordered.isEmpty()) item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 30.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(when {
                        activity.isEmpty() -> "从下一次阅读开始记录"
                        period == 0 -> "近两周暂无阅读记录"
                        else -> "暂无阅读时长"
                    },
                        style = MaterialTheme.typography.bodyMedium)
                    Text(if (period == 0 && activity.isNotEmpty()) "切换到累计，可查看此前的阅读时长。"
                        else "读过多久，会随漫画一起留下。",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            items(ordered, key = { it.manga.id }) { entry ->
                val recentMillis = recent(entry)
                ReadingTimeRow(entry, recentMillis, period == 0, today, repository) {
                    onOpenManga(entry.manga)
                    dismiss()
                }
            }
        }
    }
}

@Composable
private fun ReadingTimeValue(label: String, millis: Long, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Text(readingDuration(millis), color = MaterialTheme.colorScheme.onSurface,
            fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DailyReadingChart(dates: List<LocalDate>, values: List<Long>) {
    val colors = MaterialTheme.colorScheme
    val ink = colors.onSurface
    val peak = values.maxOrNull()?.coerceAtLeast(0L) ?: 0L
    val accessibility = dates.zip(values).joinToString("，") { (day, time) ->
        "${day.monthValue}月${day.dayOfMonth}日 ${readingDuration(time)}"
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("每日阅读", fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(if (peak == 0L) "暂无记录" else "单日最多 ${readingDuration(peak)}",
            fontSize = 10.sp, color = colors.onSurfaceVariant)
    }
    Canvas(Modifier.fillMaxWidth().height(66.dp).semantics { contentDescription = accessibility }) {
        val count = values.size.coerceAtLeast(1)
        val slot = size.width / count
        val width = (slot * .62f).coerceAtMost(14.dp.toPx())
        val floor = size.height - 1.dp.toPx()
        drawLine(ink.copy(alpha = .08f), Offset(0f, floor), Offset(size.width, floor), .7.dp.toPx())
        if (peak > 0L) values.forEachIndexed { index, value ->
            if (value > 0L) {
                val height = (floor * (value.toDouble() / peak).toFloat()).coerceAtLeast(.7.dp.toPx())
                drawRoundRect(ink.copy(alpha = if (index == values.lastIndex) .8f else .35f),
                    topLeft = Offset(slot * index + (slot - width) / 2f, floor - height),
                    size = Size(width, height), cornerRadius = CornerRadius(3.dp.toPx()))
            }
        }
    }
    val shortDate = DateTimeFormatter.ofPattern("M/d")
    Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(dates.first().format(shortDate), fontSize = 10.sp, color = colors.onSurfaceVariant)
        Text("今天", fontSize = 10.sp, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun ReadingTimeRow(
    entry: ReadingActivity,
    recentMillis: Long,
    showRecent: Boolean,
    today: LocalDate,
    repository: MangaRepository,
    onOpen: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interactions = remember(entry.manga.id) { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth()
        .clickable(interactionSource = interactions, indication = null,
            onClickLabel = "继续阅读 ${entry.manga.title}", onClick = onOpen)
        .motionPress(interactions, pressedScale = .99f).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        MangaCover(entry.manga, repository, Modifier.width(42.dp).height(58.dp).mangaCoverCard(6.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(entry.manga.title, color = colors.onSurface, fontSize = 13.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                Text(readingDuration(if (showRecent) recentMillis else entry.totalMillis),
                    color = colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1)
            }
            Text(
                (if (showRecent) "累计 ${readingDuration(entry.totalMillis)}" else "近两周 ${readingDuration(recentMillis)}") +
                    " · 最近 ${lastReadLabel(entry.lastReadAt, today)}",
                color = colors.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun readingDuration(millis: Long): String {
    if (millis <= 0L) return "0 分钟"
    val minutes = millis / 60_000L
    if (minutes == 0L) return "不足 1 分钟"
    if (minutes < 60L) return "$minutes 分钟"
    val hours = minutes / 60L
    val rest = minutes % 60L
    return if (rest == 0L) "$hours 小时" else "$hours 小时 $rest 分"
}

private fun lastReadLabel(timestamp: Long, today: LocalDate): String {
    if (timestamp <= 0L) return "暂无"
    val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
    return when (val days = ChronoUnit.DAYS.between(date, today)) {
        0L -> "今天"
        1L -> "昨天"
        in 2L..13L -> "$days 天前"
        else -> date.format(DateTimeFormatter.ofPattern("yyyy/M/d"))
    }
}
