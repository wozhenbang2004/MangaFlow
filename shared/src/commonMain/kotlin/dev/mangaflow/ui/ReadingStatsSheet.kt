package dev.mangaflow.ui

import dev.mangaflow.typography.MangaFontScene

import dev.mangaflow.platform.*

import dev.mangaflow.ui.platform.*

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.storage.ReadingActivity
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.LocalPlatformAccessibilityPreferences
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.FeelEvent
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.rememberFeelFeedback
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.motionPress
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlin.math.roundToInt

private enum class ReadingPeriod(val label: String) {
    DAY("今天"), WEEK("本周"), MONTH("本月"), YEAR("今年"), ALL("全部"),
}

/** The chosen date range owns the time summary, actual daily heatmap and work ranking. */
@Composable
internal fun ReadingStatsSheet(
    activity: List<ReadingActivity>,
    repository: MangaRepository,
    onDismiss: () -> Unit,
    onOpenManga: (Manga) -> Unit,
) {
    // This sheet is removed on dismissal, so every new opening starts with this year's data.
    var periodIndex by remember { mutableIntStateOf(ReadingPeriod.YEAR.ordinal) }
    val period = ReadingPeriod.entries[periodIndex]
    val today = localToday()
    val earliest = remember(activity, today) {
        activity.flatMap { it.days.keys }.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            .filter { !it.isAfter(today) }.minOrNull() ?: today
    }
    val rangeStart = when (period) {
        ReadingPeriod.DAY -> today
        ReadingPeriod.WEEK -> today.minusDays(today.dayOfWeek.isoDayNumber - 1L)
        ReadingPeriod.MONTH -> today.withDayOfMonth(1)
        ReadingPeriod.YEAR -> today.withDayOfYear(1)
        ReadingPeriod.ALL -> earliest
    }
    val since = rangeStart.toString()
    val until = today.toString()
    val durations = remember(activity, period, since, until) {
        activity.associate { entry -> entry.manga.id to if (period == ReadingPeriod.ALL) entry.totalMillis else
            entry.days.entries.filter { it.key >= since && it.key <= until }.sumOf { it.value } }
    }
    val visibleTotal = durations.values.sum()
    val activeDays = remember(activity, since, until) {
        activity.flatMap { entry -> entry.days.filter { (date, millis) ->
            date >= since && date <= until && millis > 0L
        }.keys }.distinct().size
    }
    val ordered = remember(activity, durations) {
        activity.filter { durations.getValue(it.manga.id) > 0L }
            .sortedWith(compareByDescending<ReadingActivity> { durations.getValue(it.manga.id) }
                .thenByDescending { it.lastReadAt })
    }
    val colors = MaterialTheme.colorScheme
    val desktop = platformKind == PlatformKind.DESKTOP
    val rankMeasurer = rememberTextMeasurer()
    val rankStyle = mangaMetricValueStyle()
    val rankDigits = ordered.size.toString().length
    val rankWidth = remember(rankMeasurer, rankStyle, rankDigits) {
        rankMeasurer.measure("8".repeat(rankDigits), style = rankStyle).size.width
    }
    val rankGutter = with(LocalDensity.current) { rankWidth.toDp() + 8.dp }

    MotionBottomSheet(onDismissRequest = onDismiss, containerColor = colors.background, desktopWidth = 980.dp) { dismiss ->
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("阅读统计",
                color = colors.onSurface, style = MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Close, "关闭阅读统计", Modifier.size(20.dp), tint = colors.onSurfaceVariant)
            }
        }
        val periods: @Composable () -> Unit = {
            MotionTabs(periodIndex, ReadingPeriod.entries.map { it.label }, { periodIndex = it },
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                treatment = MotionTabTreatment.Soft, controlHeight = 40.dp,
                fontSize = 12.sp, muted = colors.onSurfaceVariant)
        }
        val summary: @Composable () -> Unit = {
            ReadingTimeSummary(visibleTotal, activeDays, ordered.size,
                period.label,
                average = if (period != ReadingPeriod.ALL && period != ReadingPeriod.DAY)
                    visibleTotal / (daysBetween(rangeStart, today) + 1L) else null)
        }
        val calendar: @Composable () -> Unit = {
            val datePattern = if (rangeStart.year != today.year) "yyyy/M/d" else "M/d"
            val format = datePattern
            Text(if (rangeStart == today) "今天" else "${formatUiDate(rangeStart, format)} – ${formatUiDate(today, format)}",
                color = colors.onSurfaceVariant, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 8.dp))
            ReadingHeatmap(activity, rangeStart, today)
        }
        val ranks: LazyListScope.() -> Unit = {
            if (ordered.isNotEmpty()) item(key = "ranking-heading") {
                Text("${period.label}排行", color = colors.onSurface, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 22.dp, bottom = 6.dp))
            }
            if (ordered.isEmpty()) item(key = "empty") {
                Row(Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                    Text(when {
                        activity.isEmpty() && desktop -> "当前分区暂无阅读时长记录。阅读后会自动更新。"
                        activity.isEmpty() -> "从下一次阅读开始记录"
                        period == ReadingPeriod.ALL -> "暂无阅读时长"
                        else -> "${period.label}暂无阅读记录"
                    }, color = colors.onSurfaceVariant, fontSize = 13.sp)
                }
            }
            itemsIndexed(ordered, key = { _, entry -> entry.manga.id }) { index, entry ->
                val duration = durations.getValue(entry.manga.id)
                val share = if (visibleTotal > 0L) (duration.toDouble() / visibleTotal).toFloat().coerceIn(0f, 1f) else 0f
                ReadingTimeRow(entry, duration, share, index + 1, rankGutter, today, repository) {
                    onOpenManga(entry.manga)
                    dismiss()
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            val wide = desktop && maxWidth >= 710.dp * LocalDensity.current.fontScale.coerceAtLeast(1f) && maxHeight >= 310.dp
            if (wide) {
                Column(Modifier.fillMaxWidth().height(maxHeight.coerceAtMost(548.dp)).padding(horizontal = 20.dp)) {
                    periods()
                    Row(Modifier.weight(1f).fillMaxWidth().padding(bottom = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        val metricsScroll = rememberScrollState()
                        Box(Modifier.weight(1.15f).fillMaxHeight()) {
                            Column(Modifier.fillMaxSize().padding(end = 10.dp).verticalScroll(metricsScroll)) {
                                summary()
                                calendar()
                            }
                            DesktopVerticalScrollbar(metricsScroll, Modifier.align(Alignment.CenterEnd))
                        }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(colors.outlineVariant.copy(alpha = .45f)))
                        val rankingScroll = rememberLazyListState()
                        LaunchedEffect(period) { rankingScroll.scrollToItem(0) }
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            LazyColumn(Modifier.fillMaxSize().padding(end = 8.dp), state = rankingScroll, content = ranks)
                            DesktopVerticalScrollbar(rankingScroll, Modifier.align(Alignment.CenterEnd))
                        }
                    }
                }
            } else LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp).navigationBarsPadding(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
            ) {
                item(key = "period") { periods() }
                item(key = "summary") { summary() }
                item(key = "daily-time") { calendar() }
                ranks()
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ReadingTimeSummary(millis: Long, activeDays: Int, books: Int, range: String, average: Long? = null) {
    val colors = MaterialTheme.colorScheme
    val duration = mangaNumberText(readingDuration(millis))
    val display = remember(duration) {
        buildAnnotatedString {
            append(duration)
            duration.text.forEachIndexed { i, char ->
                if (char.isDigit()) addStyle(SpanStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold), i, i + 1)
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 20.dp).clearAndSetSemantics {
        contentDescription = "${range}阅读 ${duration.text}，活跃 $activeDays 天，$books 部作品。" +
            (average?.let { "日均 ${readingDuration(it)}。" } ?: "") + "仅累计前台阅读时间。"
    }) {
        Text("${range}阅读", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Text(display, color = colors.onSurface, fontSize = 17.sp, lineHeight = 42.sp)
        FlowRow(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(mangaNumberText("活跃 $activeDays 天"), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(mangaNumberText("$books 部作品"), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (average != null) Text(mangaNumberText("日均 ${readingDuration(average)}"), color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Adapt react-activity-calendar's continuous dates, weekday padding and week-column layout.
 * The foreground-time store supplies every value; fixed time levels never fabricate activity.
 */
@Composable
private fun ReadingHeatmap(activity: List<ReadingActivity>, firstDay: LocalDate, today: LocalDate) {
    val colors = MaterialTheme.colorScheme
    val density = androidx.compose.ui.platform.LocalDensity.current
    val fontScale = density.fontScale.coerceAtLeast(1f)
    val measurer = rememberTextMeasurer()
    val daily = remember(activity) {
        buildMap<String, Long> {
            activity.forEach { entry -> entry.days.forEach { (date, millis) ->
                put(date, (get(date) ?: 0L) + millis.coerceAtLeast(0L))
            } }
        }
    }
    // Monday-first, with missing dates at level zero and out-of-range padding left blank.
    val calendarStart = remember(firstDay) { firstDay.minusDays(firstDay.dayOfWeek.isoDayNumber - 1L) }
    val dayCount = daysBetween(calendarStart, today).toInt() + 1
    val weeks = (dayCount + 6) / 7
    // Day/week/month views use a familiar seven-column calendar; long ranges keep GitHub weeks.
    val compactCalendar = daysBetween(firstDay, today) < 32L
    val monthLabels = remember(calendarStart, firstDay, weeks) {
        buildList {
            var previousMonth = -1
            repeat(weeks) { week ->
                val date = maxOf(firstDay, calendarStart.plusWeeks(week.toLong()))
                if (date.monthNumber != previousMonth) {
                    add(week to ("${date.monthNumber}月"))
                    previousMonth = date.monthNumber
                }
            }
        }
    }
    var selectedDay by remember(firstDay, today) { mutableStateOf(today) }
    var previewDay by remember(firstDay, today) { mutableStateOf<LocalDate?>(null) }
    val booksByDay = remember(activity) {
        buildMap<String, Int> { activity.forEach { work -> work.days.forEach { (date, millis) ->
            if (millis > 0L) put(date, (get(date) ?: 0) + 1)
        } } }
    }
    val motion = LocalMotionEnabled.current
    val feedback = rememberFeelFeedback()
    fun selectDay(date: LocalDate) {
        previewDay = null
        if (date == selectedDay) return
        selectedDay = date
        feedback.perform(FeelEvent.Selection)
    }
    val selectedMillis = daily[selectedDay.toString()] ?: 0L
    val displayDay = previewDay ?: selectedDay
    val displayKey = displayDay.toString()
    val displayBooks = booksByDay[displayKey] ?: 0
    val scroll = rememberScrollState()
    val desktop = platformKind == PlatformKind.DESKTOP
    val chartFocus = remember { FocusRequester() }
    var chartFocused by remember { mutableStateOf(false) }
    val ink = lerp(colors.primary, mangaStatusColors().activity, .38f)
    val strong = LocalPlatformAccessibilityPreferences.current.increaseContrast
    val levels = listOf(lerp(colors.surface, colors.onSurface, if (strong) .12f else .065f),
        lerp(colors.surface, ink, .27f), lerp(colors.surface, ink, .47f), lerp(colors.surface, ink, .72f), ink)
    fun level(millis: Long): Int = when {
        millis <= 0L -> 0
        millis < 15 * 60_000L -> 1
        millis < 30 * 60_000L -> 2
        millis < 60 * 60_000L -> 3
        else -> 4
    }
    val cellLevels = remember(calendarStart, firstDay, dayCount, daily) {
        IntArray(dayCount) { index ->
            val date = calendarStart.plusDays(index.toLong())
            if (date.isBefore(firstDay)) -1 else level(daily[date.toString()] ?: 0L)
        }
    }
    val labelHeight = 20.dp * fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val weekdayWidth = if (compactCalendar) 0.dp else 22.dp * fontScale
        val viewport = (maxWidth - weekdayWidth).coerceAtLeast(1.dp)
        val gap = if (compactCalendar) 6.dp else 3.dp
        val block = if (compactCalendar) (viewport / 7f - gap).coerceIn(20.dp, 40.dp)
            else if (weeks > 16) 14.dp else
            (viewport / weeks.coerceAtLeast(1).toFloat() - 3.dp).coerceIn(12.dp, 22.dp)
        val step = block + gap
        val columns = if (compactCalendar) 7 else weeks
        val rows = if (compactCalendar) weeks else 7
        val chartWidth = step * columns - gap
        val chartHeight = labelHeight + step * rows - gap
        val stepPx = with(density) { step.toPx() }
        val blockPx = with(density) { block.toPx() }
        val labelPx = with(density) { labelHeight.toPx() }
        fun hitDay(point: Offset): LocalDate? {
            val y = point.y - labelPx
            if (point.x < 0f || y < 0f || point.x % stepPx >= blockPx || y % stepPx >= blockPx) return null
            val col = (point.x / stepPx).toInt()
            val row = (y / stepPx).toInt()
            if (col !in 0 until columns || row !in 0 until rows) return null
            val index = if (compactCalendar) row * 7L + col else col * 7L + row
            return calendarStart.plusDays(index).takeIf { it in firstDay..today }
        }
        val hover = if (!desktop) Modifier else Modifier.pointerInput(firstDay, today, stepPx, blockPx, labelPx) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Exit || event.type == PointerEventType.Scroll) previewDay = null
                    else if (event.type == PointerEventType.Move || event.type == PointerEventType.Enter) {
                        val cursor = event.changes.firstOrNull { it.type == PointerType.Mouse && !it.pressed }
                        previewDay = if (scroll.isScrollInProgress) null else cursor?.let { hitDay(it.position) }
                    }
                }
            }
        }
        val selectedIndex = daysBetween(calendarStart, selectedDay).toInt()
        val target = if (compactCalendar) Offset((selectedIndex % 7).toFloat(), (selectedIndex / 7).toFloat())
            else Offset((selectedIndex / 7).toFloat(), (selectedIndex % 7).toFloat())
        val marker = remember(firstDay, today) { Animatable(target, Offset.VectorConverter) }
        val strength = remember(firstDay, today) { Animatable(1f) }
        LaunchedEffect(target, motion) {
            if (!motion) {
                marker.snapTo(target)
                strength.snapTo(1f)
            } else if ((marker.value - target).getDistanceSquared() <= 2.05f) {
                strength.snapTo(1f)
                marker.animateTo(target, spring(MangaMotion.NavigationDamping, MangaMotion.PanelStiffness))
            } else {
                // A tap on a distant date should not trail across unrelated weeks.
                marker.snapTo(target)
                strength.snapTo(.4f)
                strength.animateTo(1f, tween(MangaMotion.Quick))
            }
        }
        LaunchedEffect(firstDay, today, scroll.maxValue, if (desktop) selectedDay else null) {
            if (!desktop) scroll.scrollTo(scroll.maxValue) else {
                val left = with(density) { (step * target.x).roundToPx() }
                val right = left + with(density) { block.roundToPx() }
                val available = with(density) { viewport.roundToPx() }
                val next = if (left < scroll.value) left else if (right > scroll.value + available) right - available else scroll.value
                if (next != scroll.value) scroll.scrollTo(next.coerceIn(0, scroll.maxValue))
            }
        }
        val keyboard = if (!desktop) Modifier else Modifier.focusRequester(chartFocus).onFocusChanged { chartFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else {
                    val date = when (event.key) {
                        Key.MoveHome -> firstDay
                        Key.MoveEnd -> today
                        Key.DirectionLeft -> selectedDay.minusDays(if (compactCalendar) 1L else 7L)
                        Key.DirectionRight -> selectedDay.plusDays(if (compactCalendar) 1L else 7L)
                        Key.DirectionUp -> selectedDay.minusDays(if (compactCalendar) 7L else 1L)
                        Key.DirectionDown -> selectedDay.plusDays(if (compactCalendar) 7L else 1L)
                        else -> null
                    }
                    if (date == null) false else { selectDay(date.coerceIn(firstDay, today)); true }
                }
            }.focusable()
        Row(Modifier.fillMaxWidth()) {
            if (!compactCalendar) Canvas(Modifier.width(weekdayWidth).height(chartHeight)) {
                listOf(0 to "一", 2 to "三", 4 to "五").forEach { (row, label) ->
                    drawText(measurer.measure(label, TextStyle(fontSize = 10.sp, color = colors.onSurfaceVariant)),
                        topLeft = Offset(0f, labelHeight.toPx() + row * step.toPx() + (block.toPx() - 10.sp.toPx()) / 2f))
                }
            }
            Box(Modifier.weight(1f).horizontalScroll(scroll)) {
                Canvas(Modifier.width(chartWidth).height(chartHeight).then(keyboard).then(hover)
                    .pointerInput(firstDay, today, stepPx, blockPx, labelPx, feedback) {
                        detectTapGestures { position ->
                            hitDay(position)?.let { date ->
                                if (desktop) chartFocus.requestFocus()
                                selectDay(date)
                            }
                        }
                    }.semantics {
                        contentDescription = "每日阅读时长热力图。颜色由少至多：0、少于15分钟、15至30分钟、30至60分钟、至少60分钟。"
                        stateDescription = "${selectedDay.year}年${selectedDay.monthNumber}月${selectedDay.dayOfMonth}日 ${readingDuration(selectedMillis)}"
                        onClick("查看今天") { selectDay(today); true }
                        customActions = listOf(
                            CustomAccessibilityAction("前一天") {
                                if (selectedDay.isAfter(firstDay)) { selectDay(selectedDay.minusDays(1)); true } else false
                            },
                            CustomAccessibilityAction("后一天") {
                                if (selectedDay.isBefore(today)) { selectDay(selectedDay.plusDays(1)); true } else false
                            })
                    }) {
                    if (compactCalendar) listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { column, label ->
                        val text = measurer.measure(label, TextStyle(fontSize = 10.sp, color = colors.onSurfaceVariant))
                        drawText(text, topLeft = Offset(column * step.toPx() + (block.toPx() - text.size.width) / 2f, 0f))
                    } else monthLabels.forEach { (column, label) ->
                        drawText(measurer.measure(label, TextStyle(fontSize = 10.sp, color = colors.onSurfaceVariant)),
                            topLeft = Offset(column * step.toPx(), 0f))
                    }
                    repeat(dayCount) { index ->
                        val intensity = cellLevels[index]
                        if (intensity >= 0) {
                            val column = if (compactCalendar) index % 7 else index / 7
                            val row = if (compactCalendar) index / 7 else index % 7
                            val point = Offset(column * step.toPx(), labelHeight.toPx() + row * step.toPx())
                            drawRoundRect(levels[intensity], point,
                                Size(block.toPx(), block.toPx()), CornerRadius(2.dp.toPx()))
                        }
                    }
                }
                Canvas(Modifier.width(chartWidth).height(chartHeight)) {
                    previewDay?.takeIf { it != selectedDay }?.let { date ->
                        val index = daysBetween(calendarStart, date).toInt()
                        val col = if (compactCalendar) index % 7 else index / 7
                        val row = if (compactCalendar) index / 7 else index % 7
                        drawRoundRect(colors.onSurface.copy(alpha = if (strong) .36f else .18f),
                            Offset(col * stepPx, labelPx + row * stepPx), Size(blockPx, blockPx), CornerRadius(2.dp.toPx()))
                    }
                    val stroke = if (strong || chartFocused) 2.dp.toPx() else 1.3.dp.toPx()
                    val position = marker.value * step.toPx() + Offset(stroke / 2f, labelHeight.toPx() + stroke / 2f)
                    val side = block.toPx() - stroke
                    drawRoundRect(colors.onSurface.copy(alpha = .9f * strength.value), position,
                        Size(side, side), CornerRadius(2.dp.toPx()), style = Stroke(stroke))
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val dayLabel = when {
            displayDay == today -> "今天"
            displayDay.year != today.year -> formatUiDate(displayDay, "yyyy/M/d")
            else -> "${displayDay.monthNumber}月${displayDay.dayOfMonth}日"
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(mangaNumberText(dayLabel), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Text(if (displayBooks > 0) "$displayBooks 部作品" else "暂无记录", fontSize = 12.sp, color = colors.onSurfaceVariant)
        }
        Text(mangaNumberText(readingDuration(daily[displayKey] ?: 0L)), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (desktop || firstDay.isBefore(today.minusDays(112))) Text(if (desktop) "悬停预览 · 点击选定" else "左右滑动查看", fontSize = 10.sp, color = colors.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text("少", fontSize = 10.sp, color = colors.onSurfaceVariant)
        Canvas(Modifier.width(62.dp).height(10.dp).clearAndSetSemantics { }) {
            levels.forEachIndexed { index, color ->
                drawRoundRect(color, Offset(index * 13.dp.toPx(), 0f),
                    Size(10.dp.toPx(), 10.dp.toPx()), CornerRadius(2.dp.toPx()))
            }
        }
        Text("多", fontSize = 10.sp, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun ReadingTimeRow(
    entry: ReadingActivity,
    duration: Long,
    share: Float,
    rank: Int,
    rankGutter: Dp,
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
        Text(rank.toString(), style = mangaMetricValueStyle(), color = colors.onSurfaceVariant,
            modifier = Modifier.width(rankGutter).clearAndSetSemantics { })
        MangaCover(entry.manga, repository, Modifier.width(38.dp).height(52.dp).mangaCoverCard(7.dp, shadowRadius = 5.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(entry.manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE), color = colors.onSurface,
                    fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                Text(mangaNumberText(readingDuration(duration)), color = colors.onSurface, fontSize = 12.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.weight(1f).height(3.dp)) {
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
    val date = localDateAt(timestamp)
    return when (val days = daysBetween(date, today)) {
        0L -> "今天"
        1L -> "昨天"
        in 2L..13L -> "$days 天前"
        else -> formatUiDate(date, "yyyy/M/d")
    }
}
