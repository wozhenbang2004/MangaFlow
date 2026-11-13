package dev.mangaflow.ui.platform

import dev.mangaflow.platform.formatInstantMillis
import dev.mangaflow.platform.wallTimeMillis
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

fun localToday(): LocalDate = Instant.fromEpochMilliseconds(wallTimeMillis()).toLocalDateTime(TimeZone.currentSystemDefault()).date
fun localDateAt(millis: Long): LocalDate = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault()).date
fun LocalDate.isAfter(other: LocalDate): Boolean = this > other
fun LocalDate.isBefore(other: LocalDate): Boolean = this < other
fun LocalDate.minusDays(days: Long): LocalDate = minus(days.toInt(), DateTimeUnit.DAY)
fun LocalDate.plusDays(days: Long): LocalDate = plus(days.toInt(), DateTimeUnit.DAY)
fun LocalDate.plusWeeks(weeks: Long): LocalDate = plusDays(weeks * 7)
fun LocalDate.withDayOfMonth(day: Int): LocalDate = LocalDate(year, monthNumber, day)
fun LocalDate.withDayOfYear(day: Int): LocalDate = LocalDate(year, 1, 1).plusDays(day - 1L)
fun daysBetween(first: LocalDate, last: LocalDate): Long = first.daysUntil(last).toLong()
fun formatUiDate(date: LocalDate, pattern: String): String {
    val values = mapOf("yyyy" to date.year.toString().padStart(4, '0'), "MM" to date.monthNumber.toString().padStart(2, '0'),
        "dd" to date.dayOfMonth.toString().padStart(2, '0'), "M" to date.monthNumber.toString(), "d" to date.dayOfMonth.toString())
    return Regex("yyyy|MM|dd|M|d").replace(pattern) { values.getValue(it.value) }
}
fun relativeCheckTime(time: Long): String {
    val seconds = ((wallTimeMillis() - time).coerceAtLeast(0L) / 1000)
    return when {
        seconds < 60 -> "刚刚检查"
        seconds < 3600 -> "${seconds / 60}分钟前检查"
        seconds < 86400 -> "${seconds / 3600}小时前检查"
        seconds < 604800 -> "${seconds / 86400}天前检查"
        else -> formatInstantMillis(time, "yyyy-MM-dd HH:mm") + "检查"
    }
}
