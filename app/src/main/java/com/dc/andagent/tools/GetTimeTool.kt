package com.dc.andagent.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Local clock tool. Kept native (rather than in JS) so the model sees the
 * device's real timezone/clock, not the WebView's UTC default.
 */
class GetTimeTool : Tool {
    override val name = "get_time"
    override val description = "获取当前日期、时间、时区与星期。用户问现在几点、今天几号、今天星期几时使用。"
    override val parameters = schemaOf(
        "timezone" to strParam("IANA 时区，例如 Asia/Shanghai。省略则使用设备本地时区"),
    )

    override suspend fun execute(args: JsonObject): String {
        val requested = args["timezone"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val zone = requested?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val weekday = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINA)
        return buildString {
            append("local_time=").append(now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append('\n')
            append("weekday=").append(weekday).append('\n')
            append("timezone=").append(zone.id).append('\n')
            append("epoch_ms=").append(System.currentTimeMillis()).append('\n')
        }
    }
}
