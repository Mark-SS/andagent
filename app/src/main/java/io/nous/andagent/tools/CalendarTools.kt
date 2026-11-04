package io.nous.andagent.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import io.nous.andagent.bridge.PermissionBroker
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Calendar tools backed by CalendarProvider. All queries are defensive: a
 * denied permission, a provider that is missing/disabled, or a security
 * exception turn into a readable "ERROR:" string instead of a crash.
 */
private const val CALENDAR_DENIED = "ERROR: 日历权限未授予，请在系统设置中开启"

private object CalendarTime {
    private val OUTPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private val DATE_TIME_PATTERNS = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy/MM/dd HH:mm",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm",
    )

    fun parse(raw: String?): Long? {
        val value = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        if (value.length >= 10) {
            value.toLongOrNull()?.let { n ->
                return if (n in 1..99_999_999_999L) n * 1000 else n
            }
        }
        runCatching { return Instant.parse(value).toEpochMilli() }
        runCatching { return java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }
        for (pattern in DATE_TIME_PATTERNS) {
            runCatching {
                val ldt = LocalDateTime.parse(value, DateTimeFormatter.ofPattern(pattern))
                return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        }
        runCatching {
            return LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        return null
    }

    fun format(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(OUTPUT)
}

private fun readCalendarNames(context: Context): Map<Long, String> {
    val names = mutableMapOf<Long, String>()
    val projection = arrayOf(
        CalendarContract.Calendars._ID,
        CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
    )
    context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        projection,
        null,
        null,
        null,
    )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
        val nameCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            names[id] = cursor.getString(nameCol) ?: "日历 $id"
        }
    }
    return names
}

class CalendarListTool(
    private val context: Context,
    private val permissions: PermissionBroker,
) : Tool {
    override val name = "calendar_list"
    override val description =
        "列出设备上的日历账户（日历 id、名称、所属账户）。写入事件前可先用它选择 calendarId。"
    override val parameters = schemaOf()

    override suspend fun execute(args: JsonObject): String {
        if (!permissions.ensure(listOf(Manifest.permission.READ_CALENDAR))) return CALENDAR_DENIED
        return runCatching {
            val projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            )
            val calendars = buildJsonArray {
                context.contentResolver.query(
                    CalendarContract.Calendars.CONTENT_URI,
                    projection,
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                    val nameCol =
                        cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                    val accountCol =
                        cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
                    val accessCol =
                        cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
                    while (cursor.moveToNext()) {
                        add(buildJsonObject {
                            put("id", cursor.getLong(idCol))
                            put("name", cursor.getString(nameCol) ?: "（未命名）")
                            put("account", cursor.getString(accountCol) ?: "")
                            put(
                                "writable",
                                cursor.getInt(accessCol) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                            )
                        })
                    }
                }
            }
            buildJsonObject {
                put("count", calendars.size)
                put("calendars", calendars)
            }.toString()
        }.getOrElse { "ERROR: 读取日历失败：${it.message ?: it.javaClass.simpleName}" }
    }
}

class CalendarEventsTool(
    private val context: Context,
    private val permissions: PermissionBroker,
) : Tool {
    override val name = "calendar_events"
    override val description =
        "查询日历事件：按时间窗 start/end 过滤，返回标题、开始/结束时间、地点、所属日历。最多 100 条，按开始时间排序。"
    override val parameters = schemaOf(
        "start" to strParam("时间窗开始，例如 2026-11-01 或 2026-11-01 08:00 或毫秒时间戳。默认 30 天前"),
        "end" to strParam("时间窗结束，格式同上。默认 30 天后"),
        "calendar_id" to intParam("可选：只看某个日历 id"),
    )

    override suspend fun execute(args: JsonObject): String {
        if (!permissions.ensure(listOf(Manifest.permission.READ_CALENDAR))) return CALENDAR_DENIED

        val now = System.currentTimeMillis()
        val start = CalendarTime.parse(args.strArg("start")) ?: (now - 30L * 24 * 3600_000)
        val end = CalendarTime.parse(args.strArg("end")) ?: (now + 30L * 24 * 3600_000)
        if (end <= start) return "ERROR: end 必须晚于 start"

        return runCatching {
            val names = readCalendarNames(context)
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.CALENDAR_ID,
                CalendarContract.Events.ALL_DAY,
            )
            val selection = buildString {
                append(CalendarContract.Events.DELETED).append(" = 0")
                append(" AND ").append(CalendarContract.Events.DTSTART).append(" >= ?")
                append(" AND ").append(CalendarContract.Events.DTSTART).append(" <= ?")
                args.longArg("calendar_id")?.let {
                    append(" AND ").append(CalendarContract.Events.CALENDAR_ID).append(" = ?")
                }
            }
            val selectionArgs = buildList {
                add(start.toString())
                add(end.toString())
                args.longArg("calendar_id")?.let { add(it.toString()) }
            }.toTypedArray()

            val events = buildJsonArray {
                var count = 0
                context.contentResolver.query(
                    CalendarContract.Events.CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    CalendarContract.Events.DTSTART + " ASC",
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(CalendarContract.Events._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
                    val startCol = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
                    val endCol = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
                    val locCol = cursor.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION)
                    val calCol = cursor.getColumnIndexOrThrow(CalendarContract.Events.CALENDAR_ID)
                    val allDayCol = cursor.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)
                    while (count < 100 && cursor.moveToNext()) {
                        if (cursor.isNull(startCol)) continue
                        val beginMs = cursor.getLong(startCol)
                        val endMs = if (cursor.isNull(endCol)) null else cursor.getLong(endCol)
                        val calendarId = cursor.getLong(calCol)
                        count++
                        add(buildJsonObject {
                            put("id", cursor.getLong(idCol))
                            put("title", cursor.getString(titleCol) ?: "（无标题）")
                            put("begin", CalendarTime.format(beginMs))
                            put("beginMs", beginMs)
                            if (endMs != null) {
                                put("end", CalendarTime.format(endMs))
                                put("endMs", endMs)
                            } else {
                                put("end", JsonNull)
                            }
                            put("location", cursor.getString(locCol) ?: "")
                            put("calendar", names[calendarId] ?: "日历 $calendarId")
                            put("calendarId", calendarId)
                            put("allDay", cursor.getInt(allDayCol) != 0)
                        })
                    }
                }
            }
            buildJsonObject {
                put("start", CalendarTime.format(start))
                put("end", CalendarTime.format(end))
                put("count", events.size)
                put("events", events)
            }.toString()
        }.getOrElse { "ERROR: 查询日历事件失败：${it.message ?: it.javaClass.simpleName}" }
    }
}

class CalendarAddTool(
    private val context: Context,
    private val permissions: PermissionBroker,
) : Tool {
    override val name = "calendar_add"
    override val description =
        "创建日历事件。title/begin/end 必填；location/description/remindMinutes 可选。写入成功后返回 WRITTEN 确认。"
    override val parameters = schemaOf(
        "title" to strParam("事件标题"),
        "begin" to strParam("开始时间，例如 2026-11-05 14:00 或 ISO-8601"),
        "end" to strParam("结束时间，格式同上"),
        "location" to strParam("可选：地点"),
        "description" to strParam("可选：备注"),
        "remindMinutes" to intParam("可选：提前多少分钟提醒"),
        "calendar_id" to intParam("可选：目标日历 id，默认使用第一个可写日历"),
        required = listOf("title", "begin", "end"),
    )
    override val destructive = true

    override suspend fun execute(args: JsonObject): String {
        if (!permissions.ensure(
                listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            )
        ) {
            return CALENDAR_DENIED
        }

        val title = args.strArg("title") ?: return "ERROR: 缺少 title"
        val beginText = args.strArg("begin") ?: return "ERROR: 缺少 begin"
        val endText = args.strArg("end") ?: return "ERROR: 缺少 end"
        val begin = CalendarTime.parse(beginText) ?: return "ERROR: 无法解析开始时间：$beginText"
        val end = CalendarTime.parse(endText) ?: return "ERROR: 无法解析结束时间：$endText"
        if (end <= begin) return "ERROR: 结束时间必须晚于开始时间"
        val remind = args.intArg("remindMinutes") ?: args.intArg("remind_minutes")
        if (remind != null && remind !in 0..10_080) return "ERROR: remindMinutes 需在 0..10080 之间"

        return runCatching {
            val calendar = pickWritableCalendar(args.longArg("calendar_id"))
                ?: return@runCatching "ERROR: 设备上没有可写入的日历账户，请先在系统日历中添加账户"
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendar.first)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, begin)
                put(CalendarContract.Events.DTEND, end)
                put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
                put(CalendarContract.Events.ALL_DAY, 0)
                put(CalendarContract.Events.HAS_ALARM, if (remind != null) 1 else 0)
                args.strArg("location")?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                args.strArg("description")?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return@runCatching "ERROR: 日历 Provider 拒绝写入（可能没有可写日历）"
            val eventId = ContentUris.parseId(uri)

            var reminderNote = "未设置提醒"
            if (remind != null) {
                val reminder = ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, remind)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
                val reminderUri = context.contentResolver.insert(
                    CalendarContract.Reminders.CONTENT_URI,
                    reminder,
                )
                reminderNote = if (reminderUri != null) {
                    "提前 $remind 分钟提醒"
                } else {
                    "提醒写入失败（事件已创建）"
                }
            }

            val message = buildString {
                append("WRITTEN: 已创建事件「").append(title).append("」 ")
                append(CalendarTime.format(begin)).append(" → ").append(CalendarTime.format(end))
                append("（日历：").append(calendar.second)
                append("，id=").append(eventId)
                append("，").append(reminderNote).append("）")
            }
            message
        }.getOrElse { "ERROR: 创建日历事件失败：${it.message ?: it.javaClass.simpleName}" }
    }

    private fun pickWritableCalendar(wantedId: Long?): Pair<Long, String>? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        val writable = mutableListOf<Pair<Long, String>>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null,
            null,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val nameCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val accessCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            while (cursor.moveToNext()) {
                if (cursor.getInt(accessCol) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                    writable.add(cursor.getLong(idCol) to (cursor.getString(nameCol) ?: "日历"))
                }
            }
        }
        if (wantedId == null) return writable.firstOrNull()
        return writable.firstOrNull { it.first == wantedId }
    }
}

class CalendarDeleteTool(
    private val context: Context,
    private val permissions: PermissionBroker,
) : Tool {
    override val name = "calendar_delete"
    override val description =
        "按事件 id 删除日历事件（先 calendar_events 查询 id）。删除成功后返回 DELETED 确认。"
    override val parameters = schemaOf(
        "id" to intParam("要删除的事件 id"),
        required = listOf("id"),
    )
    override val destructive = true

    override suspend fun execute(args: JsonObject): String {
        if (!permissions.ensure(listOf(Manifest.permission.WRITE_CALENDAR))) return CALENDAR_DENIED
        val id = args.longArg("id") ?: return "ERROR: 缺少事件 id"
        return runCatching {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
            val rows = context.contentResolver.delete(uri, null, null)
            if (rows <= 0) {
                "ERROR: 未找到 id=$id 的事件（可能已被删除）"
            } else {
                "DELETED: 已删除事件 id=$id"
            }
        }.getOrElse { "ERROR: 删除日历事件失败：${it.message ?: it.javaClass.simpleName}" }
    }
}
