package io.nous.andagent.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import kotlinx.serialization.json.JsonObject

/**
 * System clock app integration. Android exposes no API to read the alarms the
 * user already set, so only "set" operations exist here.
 */
class AlarmSetTool(private val context: Context) : Tool {
    override val name = "alarm_set"
    override val description =
        "通过系统闹钟应用设置闹钟（小时/分钟/备注）。会打开系统确认界面，用户仍需在闹钟应用中确认。不需要额外权限。"
    override val parameters = schemaOf(
        "hour" to intParam("小时，0-23"),
        "minutes" to intParam("分钟，0-59"),
        "message" to strParam("可选：闹钟备注"),
        required = listOf("hour", "minutes"),
    )

    override suspend fun execute(args: JsonObject): String {
        val hour = args.intArg("hour") ?: return "ERROR: 缺少 hour"
        val minutes = args.intArg("minutes") ?: return "ERROR: 缺少 minutes"
        if (hour !in 0..23) return "ERROR: hour 需在 0..23 之间"
        if (minutes !in 0..59) return "ERROR: minutes 需在 0..59 之间"
        val message = args.strArg("message")

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minutes)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (!message.isNullOrEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "WRITTEN: 闹钟已直接写入系统闹钟应用，小时 $hour 分 $minutes" +
                (if (message.isNullOrEmpty()) "" else "（备注：$message）") +
                "。SKIP_UI=true 无需用户确认；如设备策略不允许静默写入会回落到确认界面。"
        } catch (e: Exception) {
            "ERROR: 无法打开系统闹钟应用（${e.message ?: e.javaClass.simpleName}）"
        }
    }
}

class TimerSetTool(private val context: Context) : Tool {
    override val name = "timer_set"
    override val description =
        "通过系统时钟应用设置倒计时（秒）。会打开系统计时器界面，用户仍需在系统中确认。不需要额外权限。"
    override val parameters = schemaOf(
        "seconds" to intParam("倒计时秒数"),
        "message" to strParam("可选：计时器备注"),
        required = listOf("seconds"),
    )

    override suspend fun execute(args: JsonObject): String {
        val seconds = args.intArg("seconds") ?: return "ERROR: 缺少 seconds"
        if (seconds !in 1..86_400) return "ERROR: seconds 需在 1..86400 之间"
        val message = args.strArg("message")

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (!message.isNullOrEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            val minutes = seconds / 60
            val rest = seconds % 60
            val duration = if (minutes > 0) "${minutes} 分 ${rest} 秒" else "${rest} 秒"
            "已唤起系统计时器，时长 $duration" +
                (if (message.isNullOrEmpty()) "" else "（备注：$message）") +
                "。请在系统时钟应用中确认。"
        } catch (e: Exception) {
            "ERROR: 无法打开系统计时器（${e.message ?: e.javaClass.simpleName}）"
        }
    }
}
