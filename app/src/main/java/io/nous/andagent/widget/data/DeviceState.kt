package io.nous.andagent.widget.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

object DeviceState {
    /** Battery facts from the sticky broadcast, shared by node bindings and SYSTEM sources. */
    data class Battery(val percent: Int?, val status: String, val charging: Boolean)

    fun battery(context: Context): Battery {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) ((level.toLong() * 100) / scale).toInt().coerceIn(0, 100) else null
        val status = when (battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "unknown"
        }
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging = plugged == BatteryManager.BATTERY_PLUGGED_AC ||
            plugged == BatteryManager.BATTERY_PLUGGED_USB ||
            plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
        return Battery(percent, status, charging)
    }

    fun batteryPercent(context: Context): Int? = battery(context).percent
}
