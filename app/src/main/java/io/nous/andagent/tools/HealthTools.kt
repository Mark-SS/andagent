package io.nous.andagent.tools

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Step data through Health Connect.
 *
 * [HealthConnectClient] is a suspend API; permission checks happen before every
 * read so a missing grant returns a Chinese instruction instead of an exception.
 */

private const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"
private const val HEALTH_MARKET_LINK = "market://details?id=$HEALTH_CONNECT_PACKAGE"

private sealed class HealthReady {
    data class Ready(val client: HealthConnectClient) : HealthReady()
    data class NotReady(val message: String) : HealthReady()
}

private suspend fun healthReady(context: Context): HealthReady {
    val status = runCatching { HealthConnectClient.getSdkStatus(context) }.getOrElse { -1 }
    when (status) {
        HealthConnectClient.SDK_UNAVAILABLE -> return HealthReady.NotReady(
            "ERROR: 未安装 Health Connect。Android 13 及以下请先安装 Health Connect 应用（$HEALTH_MARKET_LINK）",
        )
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> return HealthReady.NotReady(
            "ERROR: Health Connect 需要更新，请在应用商店更新后再试（$HEALTH_MARKET_LINK）",
        )
    }
    val client = runCatching { HealthConnectClient.getOrCreate(context) }.getOrElse {
        return HealthReady.NotReady(
            "ERROR: 无法连接 Health Connect：${it.message ?: it.javaClass.simpleName}",
        )
    }
    val granted = runCatching {
        client.permissionController.getGrantedPermissions()
    }.getOrElse {
        return HealthReady.NotReady(
            "ERROR: 无法读取 Health Connect 权限状态：${it.message ?: it.javaClass.simpleName}",
        )
    }
    if ("android.permission.health.READ_STEPS" !in granted) {
        return HealthReady.NotReady(
            "ERROR: 未授予步数读取权限。请打开设置页点击「授权健康数据」，或在 Health Connect 中允许 AndAgent 读取步数",
        )
    }
    return HealthReady.Ready(client)
}

private suspend fun readSteps(client: HealthConnectClient, start: Instant, end: Instant): Long {
    if (!end.isAfter(start)) return 0L
    val response = client.readRecords(
        ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(start, end)),
    )
    return response.records.sumOf { it.count }
}

class StepsTodayTool(private val context: Context) : Tool {
    override val name = "steps_today"
    override val description = "读取今天的步数（Health Connect，需用户授权步数读取权限）。"
    override val parameters = schemaOf()

    override suspend fun execute(args: JsonObject): String {
        val ready = healthReady(context)
        if (ready is HealthReady.NotReady) return ready.message
        val client = (ready as HealthReady.Ready).client
        return runCatching {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val start = today.atStartOfDay(zone).toInstant()
            val end = Instant.now()
            val steps = readSteps(client, start, end)
            buildJsonObject {
                put("date", today.toString())
                put("steps", steps)
                put("timezone", zone.id)
                put("note", "数据来自 Health Connect；若手机/手表未同步，数字可能偏低")
            }.toString()
        }.getOrElse { "ERROR: 读取今日步数失败：${it.message ?: it.javaClass.simpleName}" }
    }
}

class StepsHistoryTool(private val context: Context) : Tool {
    override val name = "steps_history"
    override val description = "读取最近 N 天每日步数（Health Connect），默认 7 天，最多 30 天。"
    override val parameters = schemaOf(
        "days" to intParam("天数，默认 7，范围 1–30"),
    )

    override suspend fun execute(args: JsonObject): String {
        val ready = healthReady(context)
        if (ready is HealthReady.NotReady) return ready.message
        val client = (ready as HealthReady.Ready).client
        val days = (args.intArg("days") ?: 7).coerceIn(1, 30)
        return runCatching {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            var total = 0L
            val daily = buildJsonArray {
                for (offset in (days - 1) downTo 0) {
                    val date = today.minusDays(offset.toLong())
                    val start = date.atStartOfDay(zone).toInstant()
                    val end = date.plusDays(1).atStartOfDay(zone).toInstant()
                    val steps = readSteps(client, start, end)
                    total += steps
                    add(buildJsonObject {
                        put("date", date.toString())
                        put("steps", steps)
                    })
                }
            }
            buildJsonObject {
                put("days", days)
                put("timezone", zone.id)
                put("total", total)
                put("average", if (days > 0) "%.0f".format(total.toDouble() / days) else "0")
                put("daily", daily)
            }.toString()
        }.getOrElse { "ERROR: 读取历史步数失败：${it.message ?: it.javaClass.simpleName}" }
    }
}
