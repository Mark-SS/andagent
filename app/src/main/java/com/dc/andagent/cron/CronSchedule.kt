package com.dc.andagent.cron

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Five-field, local-time cron. Day-of-month and weekday use traditional OR semantics. */
class CronSchedule private constructor(
    private val minute: Set<Int>, private val hour: Set<Int>, private val day: Set<Int>,
    private val month: Set<Int>, private val weekday: Set<Int>,
    private val dayAny: Boolean, private val weekdayAny: Boolean,
    private val intervalMinutes: Int? = null,
) {
    fun next(afterMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        intervalMinutes?.let { minutes ->
            val step = minutes * 60_000L
            return (Math.floorDiv(afterMillis, step) + 1) * step
        }
        var time = Instant.ofEpochMilli(afterMillis).atZone(zone).withSecond(0).withNano(0).plusMinutes(1)
        // A leap year plus one year covers February 29 and all month/day combinations.
        val limit = time.plusYears(5)
        while (time.isBefore(limit)) {
            val dow = time.dayOfWeek.value % 7
            val dayMatches = when {
                dayAny && weekdayAny -> true
                dayAny -> dow in weekday
                weekdayAny -> time.dayOfMonth in day
                else -> time.dayOfMonth in day || dow in weekday
            }
            if (time.minute in minute && time.hour in hour && time.monthValue in month && dayMatches)
                return time.toInstant().toEpochMilli()
            time = time.plusMinutes(1)
        }
        throw IllegalArgumentException("时间表在未来 5 年内没有触发时间")
    }

    companion object {
        private val WEEKDAYS = mapOf("sun" to 0, "mon" to 1, "tue" to 2, "wed" to 3,
            "thu" to 4, "fri" to 5, "sat" to 6)

        fun parse(raw: String): CronSchedule {
            val input = raw.trim().lowercase()
            val every = Regex("every\\s+(\\d+)(m|h)").matchEntire(input)
            if (every != null) {
                val count = every.groupValues[1].toIntOrNull() ?: error("间隔数值过大")
                val minutes = count * if (every.groupValues[2] == "h") 60 else 1
                require(count > 0 && minutes in 1..525600) { "间隔必须在 1 分钟到 1 年之间" }
                return CronSchedule(emptySet(), emptySet(), emptySet(), emptySet(), emptySet(), true, true, minutes)
            }
            val daily = Regex("daily\\s+(\\d{1,2}):(\\d{2})").matchEntire(input)
            val weekly = Regex("weekly\\s+(sun|mon|tue|wed|thu|fri|sat)\\s+(\\d{1,2}):(\\d{2})").matchEntire(input)
            val fields = when {
                daily != null -> listOf(daily.groupValues[2], daily.groupValues[1], "*", "*", "*")
                weekly != null -> listOf(weekly.groupValues[3], weekly.groupValues[2], "*", "*", WEEKDAYS.getValue(weekly.groupValues[1]).toString())
                else -> input.split(Regex("\\s+"))
            }
            require(fields.size == 5) { "时间表须为 5 段 cron，或 daily HH:mm / weekly mon HH:mm / every 30m" }
            return CronSchedule(
                field(fields[0], "分钟", 0, 59), field(fields[1], "小时", 0, 23),
                field(fields[2], "日期", 1, 31), field(fields[3], "月份", 1, 12),
                field(fields[4], "星期", 0, 7).map { it % 7 }.toSet(),
                fields[2] == "*", fields[4] == "*",
            )
        }

        private fun field(text: String, label: String, min: Int, max: Int): Set<Int> {
            val out = mutableSetOf<Int>()
            for (part in text.split(',')) {
                val pieces = part.split('/')
                require(pieces.size <= 2 && pieces[0].isNotEmpty()) { "$label 段无效：$text" }
                val step = if (pieces.size == 2) pieces[1].toIntOrNull() else 1
                require(step != null && step > 0) { "$label 段步长无效：$part" }
                val range = pieces[0]
                val (start, end) = when {
                    range == "*" -> min to max
                    '-' in range -> {
                        val bounds = range.split('-')
                        require(bounds.size == 2) { "$label 段范围无效：$part" }
                        (bounds[0].toIntOrNull() ?: -1) to (bounds[1].toIntOrNull() ?: -1)
                    }
                    pieces.size == 2 -> (range.toIntOrNull() ?: -1) to max
                    else -> (range.toIntOrNull() ?: -1) to (range.toIntOrNull() ?: -1)
                }
                require(start in min..max && end in min..max && start <= end) { "$label 段越界或倒序：$part（$min..$max）" }
                for (value in start..end step step) out += value
            }
            require(out.isNotEmpty()) { "$label 段为空：$text" }
            return out
        }
    }
}
