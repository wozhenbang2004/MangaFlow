package dev.mangaflow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Schedule
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import kotlin.math.roundToInt

/** The chosen range owns both the time summary and ranking; the chart is always fourteen days. */
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
    val recentPeriod = period == 0
    val visibleTotal = if (recentPeriod) fortnight else total
    val activeDays = if (recentPeriod) daily.count { it > 0L } else activity
        .flatMap { it.days.filterValues { millis -> millis > 0L }.keys }.distinct().size
    val ordered = activity.filter { if (recentPeriod) recent(it) > 0L else it.totalMillis > 0L }
        .sortedWith(compareByDescending<ReadingActivity> { if (recentPeriod) recent(it) else it.totalMillis }
            .thenByDescending { it.lastReadAt })
    val colors = MaterialTheme.colorScheme

    MotionBottomSheet(onDismissRequest = onDismiss, containerColor = colors.background) { dismiss ->
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("阅读统计", color = colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            MotionTabs(period, listOf("近两周", "累计"), { period = it },
                modifier = Modifier.width(156.dp), compact = true, controlHeight = 40.dp,
                fontSize = 12.sp, muted = colors.onSurfaceVariant,
                selectedFontWeight = FontWeight.SemiBold, unselectedFontWeight = FontWeight.Normal)
            MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Close, "关闭阅读统计", Modifier.size(20.dp), tint = colors.onSurfaceVariant)
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp).navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
        ) {
            item(key = "summary") {
                ReadingTimeSummary(visibleTotal, activeDays, ordered.size,
                    if (recentPeriod) "近两周" else "累计")
            }
            item(key = "daily-time") { DailyReadingChart(dates, daily) }
            if (ordered.isNotEmpty()) item(key = "ranking-heading") {
                Text("阅读排行", color = colors.onSurfaceVariant, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
            }
            if (ordered.isEmpty()) item(key = "empty") {
                Row(Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                    Text(when {
                        activity.isEmpty() -> "从下一次阅读开始记录"
                        recentPeriod -> "近两周暂无阅读记录"
                        else -> "暂无阅读时长"
                    }, color = colors.onSurfaceVariant, fontSize = 13.sp)
                }
            }
            itemsIndexed(ordered, key = { _, entry -> entry.manga.id }) { index, entry ->
                val duration = if (recentPeriod) recent(entry) else entry.totalMillis
                val share = if (visibleTotal > 0L) (duration.toDouble() / visibleTotal).toFloat().coerceIn(0f, 1f) else 0f
                ReadingTimeRow(entry, duration, share, index + 1, today, repository) {
                    onOpenManga(entry.manga)
                    dismiss()
                }
            }
        }
    }
}

@Composable
private fun ReadingTimeSummary(millis: Long, activeDays: Int, books: Int, range: String) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp).clearAndSetSemantics {
        contentDescription = "$range阅读 ${readingDuration(millis)}，活跃 $activeDays 天，$books 部作品。仅累计前台阅读时间。"
    }, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(readingDuration(millis), color = colors.onSurface,
            fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(Icons.Outlined.CalendarToday, null, Modifier.size(13.dp), tint = colors.onSurfaceVariant)
                Text("$activeDays 天", color = colors.onSurfaceVariant, fontSize = 11.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(Icons.Outlined.MenuBook, null, Modifier.size(13.dp), tint = colors.onSurfaceVariant)
                Text("$books 部", color = colors.onSurfaceVariant, fontSize = 11.sp)
            }
        }
    }
}

/** Adapt the original daily plot, retaining real zero days and an accessible date selection. */
@Composable
private fun DailyReadingChart(dates: List<LocalDate>, values: List<Long>) {
    val colors = MaterialTheme.colorScheme
    val ink = colors.onSurface
    val peak = values.maxOrNull()?.coerceAtLeast(0L) ?: 0L
    var selectedDay by remember(dates) {
        mutableIntStateOf(values.indexOfLast { it > 0L }.takeIf { it >= 0 } ?: dates.lastIndex)
    }
    val selected = selectedDay.coerceIn(dates.indices)
    val day = dates[selected]
    val dayLabel = if (selected == dates.lastIndex) "今天" else "${day.monthValue}/${day.dayOfMonth}"
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("近14天", fontSize = 11.sp, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text("$dayLabel · ${readingDuration(values[selected])}",
            fontSize = 11.sp, color = colors.onSurface, fontWeight = FontWeight.Medium)
    }
    Canvas(Modifier.fillMaxWidth().height(44.dp)
        .pointerInput(dates, values) {
            detectTapGestures { position ->
                selectedDay = (position.x / size.width.coerceAtLeast(1) * values.size).toInt()
                    .coerceIn(0, values.lastIndex)
            }
        }
        .semantics {
            contentDescription = "近14天每日阅读时长"
            stateDescription = "${day.monthValue}月${day.dayOfMonth}日 ${readingDuration(values[selected])}"
            onClick(label = "查看今天") { selectedDay = dates.lastIndex; true }
            customActions = listOf(
                CustomAccessibilityAction("前一天") {
                    if (selectedDay > 0) { selectedDay--; true } else false
                },
                CustomAccessibilityAction("后一天") {
                    if (selectedDay < dates.lastIndex) { selectedDay++; true } else false
                },
            )
        }) {
        val slot = size.width / values.size
        val width = (slot * .55f).coerceAtMost(12.dp.toPx())
        val floor = size.height - 3.dp.toPx()
        values.forEachIndexed { index, value ->
            val x = slot * index + (slot - width) / 2f
            if (index == selected) drawRoundRect(ink.copy(alpha = .035f),
                topLeft = Offset(slot * index + 1.dp.toPx(), 0f),
                size = Size((slot - 2.dp.toPx()).coerceAtLeast(0f), size.height),
                cornerRadius = CornerRadius(4.dp.toPx()))
            if (value > 0L && peak > 0L) {
                val height = (floor * (value.toDouble() / peak).toFloat()).coerceAtLeast(.8.dp.toPx())
                drawRoundRect(if (index == selected) colors.primary.copy(alpha = .9f) else ink.copy(alpha = .3f),
                    topLeft = Offset(x, floor - height), size = Size(width, height),
                    cornerRadius = CornerRadius(2.dp.toPx()))
            } else drawCircle(if (index == selected) colors.primary.copy(alpha = .75f) else ink.copy(alpha = .13f),
                radius = 1.dp.toPx(), center = Offset(slot * (index + .5f), floor))
        }
    }
    val shortDate = DateTimeFormatter.ofPattern("M/d")
    Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(dates.first().format(shortDate), fontSize = 10.sp, color = colors.onSurfaceVariant)
        Text("今天", fontSize = 10.sp, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun ReadingTimeRow(
    entry: ReadingActivity,
    duration: Long,
    share: Float,
    rank: Int,
    today: LocalDate,
    repository: MangaRepository,
    onOpen: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interactions = remember(entry.manga.id) { MutableInteractionSource() }
    val shareLabel = if (share > 0f && share < .01f) "不足1%" else "${(share * 100f).roundToInt()}%"
    Row(Modifier.fillMaxWidth()
        .clickable(interactionSource = interactions, indication = null,
            onClickLabel = "继续阅读 ${entry.manga.title}", onClick = onOpen)
        .semantics(mergeDescendants = true) { stateDescription = "占所选时段阅读时间 $shareLabel" }
        .motionPress(interactions, pressedScale = .99f).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(rank.toString().padStart(2, '0'), color = colors.onSurfaceVariant.copy(alpha = .65f),
            fontSize = 10.sp, modifier = Modifier.width(22.dp).clearAndSetSemantics { })
        MangaCover(entry.manga, repository, Modifier.width(32.dp).height(44.dp).mangaCoverCard(5.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(entry.manga.title, color = colors.onSurface, fontSize = 13.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                Text(readingDuration(duration), color = colors.onSurface, fontSize = 11.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.weight(1f).height(2.dp)) {
                    val radius = CornerRadius(size.height * .5f)
                    drawRoundRect(colors.onSurface.copy(alpha = .055f), cornerRadius = radius)
                    if (share > 0f) drawRoundRect(colors.primary.copy(alpha = .38f),
                        size = Size(size.width * share, size.height), cornerRadius = radius)
                }
                Text(lastReadLabel(entry.lastReadAt, today), color = colors.onSurfaceVariant,
                    fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1)
            }
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
