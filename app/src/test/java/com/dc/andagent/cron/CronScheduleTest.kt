package com.dc.andagent.cron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CronScheduleTest {
    private val utc = ZoneId.of("UTC")
    private fun time(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, utc).toInstant().toEpochMilli()
    private fun next(schedule: String, after: Long) = CronSchedule.parse(schedule).next(after, utc)

    @Test fun quarterHours() = assertEquals(time(2026, 1, 1, 10, 15), next("*/15 * * * *", time(2026, 1, 1, 10, 1)))
    @Test fun exactMinuteMovesToNextHour() = assertEquals(time(2026, 1, 1, 11, 0), next("0 * * * *", time(2026, 1, 1, 10, 0)))
    @Test fun listAndRange() = assertEquals(time(2026, 1, 1, 9, 10), next("5,10,20-30/5 9 * * *", time(2026, 1, 1, 9, 5)))
    @Test fun weeklyMonday() = assertEquals(time(2026, 1, 5, 9, 0), next("weekly mon 09:00", time(2026, 1, 2, 10, 0)))
    @Test fun sundaySeven() = assertEquals(time(2026, 1, 4, 9, 0), next("0 9 * * 7", time(2026, 1, 2, 10, 0)))
    @Test fun monthEnd() = assertEquals(time(2026, 3, 31, 23, 59), next("59 23 31 * *", time(2026, 2, 28, 23, 59)))
    @Test fun leapDay() = assertEquals(time(2028, 2, 29, 8, 0), next("0 8 29 2 *", time(2026, 3, 1, 0, 0)))
    @Test fun dailyCrossesMidnight() = assertEquals(time(2026, 1, 2, 9, 0), next("daily 09:00", time(2026, 1, 1, 9, 0)))
    @Test fun interval() = assertEquals(time(2026, 1, 1, 10, 30), next("every 30m", time(2026, 1, 1, 10, 7)))
    @Test fun badHourNamesSegment() {
        val error = runCatching { CronSchedule.parse("0 25 * * *") }.exceptionOrNull()?.message.orEmpty()
        assertTrue(error.contains("小时"))
    }
    @Test fun badStepNamesSegment() {
        val error = runCatching { CronSchedule.parse("*/0 * * * *") }.exceptionOrNull()?.message.orEmpty()
        assertTrue(error.contains("分钟"))
    }
}
