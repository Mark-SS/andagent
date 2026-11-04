package io.nous.andagent.widget.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import android.text.format.DateFormat
import io.nous.andagent.widget.model.SystemProvider
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Host-provided device facts behind `SourceType.SYSTEM`. Every provider is synchronous and
 * permission-free: results become ordinary cache entries, so scripts still only read
 * `ctx.sources` and rendering never calls a platform API.
 */
object SystemSources {
    private const val GIB = 1024.0 * 1024.0 * 1024.0

    fun collect(
        context: Context,
        provider: SystemProvider,
        now: Instant = Instant.now()
    ): Map<String, String> = when (provider) {
        SystemProvider.BATTERY -> battery(context)
        SystemProvider.CLOCK -> clock(now, ZoneId.systemDefault(), Locale.getDefault())
        SystemProvider.NETWORK -> network(context)
        SystemProvider.LOCALE -> locale(context)
        SystemProvider.STORAGE -> storage(context)
        SystemProvider.DEVICE_INFO -> mapOf("text" to WidgetToolRuntime.tool(context, "device_info"))
        SystemProvider.BATTERY_DETAIL -> toolJson(context, "battery_detail")
        SystemProvider.STEPS_TODAY -> toolJson(context, "steps_today")
    }

    private fun toolJson(context: Context, name: String): Map<String, String> {
        val raw = WidgetToolRuntime.tool(context, name)
        val parsed = io.nous.andagent.widget.model.SpecCodec.json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject
            ?: error("$name 未返回 JSON 对象")
        return parsed.mapValues { (_, value) ->
            (value as? kotlinx.serialization.json.JsonPrimitive)?.content?.take(400)
                ?: error("$name 包含非标量字段")
        }
    }

    private fun battery(context: Context): Map<String, String> {
        val state = DeviceState.battery(context)
        val values = LinkedHashMap<String, String>()
        state.percent?.let { values["percent"] = it.toString() }
        values["charging"] = state.charging.toString()
        values["status"] = state.status
        return values
    }

    private fun clock(now: Instant, zone: ZoneId, locale: Locale): Map<String, String> {
        val time = now.atZone(zone)
        return linkedMapOf(
            "now" to DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(time),
            "date" to DateTimeFormatter.ISO_LOCAL_DATE.format(time),
            "time" to DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).format(time),
            "weekday" to DateTimeFormatter.ofPattern("EEE", locale).format(time),
            "timezone" to zone.id,
            "epochSeconds" to now.epochSecond.toString()
        )
    }

    private fun network(context: Context): Map<String, String> {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val active = manager?.activeNetwork
        val capabilities = if (manager != null && active != null) manager.getNetworkCapabilities(active) else null
        val type = when {
            capabilities == null -> "none"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        val connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val metered = connected && capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        return linkedMapOf(
            "connected" to connected.toString(),
            "type" to type,
            "metered" to metered.toString()
        )
    }

    private fun locale(context: Context): Map<String, String> {
        val locales = context.resources.configuration.locales
        val primary = if (locales.isEmpty) Locale.getDefault() else locales[0]
        return linkedMapOf(
            "language" to primary.language,
            "region" to primary.country,
            "timezone" to ZoneId.systemDefault().id,
            "hour24" to DateFormat.is24HourFormat(context).toString()
        )
    }

    private fun storage(context: Context): Map<String, String> {
        val stats = StatFs(context.filesDir.absolutePath)
        val free = stats.availableBytes
        val total = stats.totalBytes
        val used = if (total > 0) (((total - free) * 100) / total).toInt().coerceIn(0, 100) else 0
        return linkedMapOf(
            "freeGb" to String.format(Locale.US, "%.1f", free / GIB),
            "totalGb" to String.format(Locale.US, "%.1f", total / GIB),
            "usedPercent" to used.toString()
        )
    }
}
