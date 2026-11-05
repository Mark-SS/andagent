package com.dc.andagent.tools

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.serialization.json.JsonObject

/**
 * Opens a specific Android system settings panel by key, so the agent can
 * jump the user straight to the right screen ("帮我打开蓝牙设置" etc.).
 * Every panel is ACTION_*_SETTINGS — no permissions needed; we only launch
 * the screen, we never change anything.
 */
class OpenSettingsTool(private val context: Context) : Tool {
    override val name = "open_settings"
    override val description =
        "打开 Android 系统设置的具体页面（如 WiFi、蓝牙、显示、声音、应用、悬浮窗授权等）。只跳转，不修改任何设置。"

    override val parameters = schemaOf(
        "panel" to strParam(
            "设置页 key，可选值：wifi | bluetooth | display | sound | volume | battery | storage | " +
                "apps | app_details(需 package 参数) | location | security | privacy | date_time | " +
                "nfc | airplane | developer | accessibility | input | data_usage | notification | " +
                "home | cast | vpn | print | overlay | user_dict | keyboard_settings | main",
        ),
        "package" to strParam("可选：app_details 时指定应用包名，省略则打开本应用详情"),
    )

    override val destructive: Boolean = false

    override suspend fun execute(args: JsonObject): String {
        val panel = (args.strArg("panel") ?: "main").lowercase().trim()
        val pkg = args.strArg("package")

        val intent: Intent = when (panel) {
            "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "wireless" -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "brightness" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "night_light" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            "volume" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            "do_not_disturb" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            "battery" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            "battery_optimization" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            "storage" -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            "apps" -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
            "app_details" -> {
                val target = pkg ?: context.packageName
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", target, null))
            }
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .setData(android.net.Uri.fromParts("package", context.packageName, null))
            "default_apps" -> Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "security" -> Intent(Settings.ACTION_SECURITY_SETTINGS)
            "privacy" -> Intent(Settings.ACTION_PRIVACY_SETTINGS)
            "date_time" -> Intent(Settings.ACTION_DATE_SETTINGS)
            "nfc" -> Intent(Settings.ACTION_NFC_SETTINGS)
            "airplane" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            "developer" -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "input" -> Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            "data_usage" -> Intent(Settings.ACTION_DATA_USAGE_SETTINGS)
            "notification" -> Intent("android.settings.NOTIFICATION_SETTINGS")
            "notification_app" -> {
                val target = pkg ?: context.packageName
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, target)
            }
            "home" -> Intent(Settings.ACTION_HOME_SETTINGS)
            "cast" -> Intent(Settings.ACTION_CAST_SETTINGS)
            "vpn" -> Intent(Settings.ACTION_VPN_SETTINGS)
            "print" -> Intent(Settings.ACTION_PRINT_SETTINGS)
            "user_dict" -> Intent(Settings.ACTION_USER_DICTIONARY_SETTINGS)
            "keyboard" -> Intent(Settings.ACTION_HARD_KEYBOARD_SETTINGS)
            "voice_input" -> Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            "main" -> Intent(Settings.ACTION_SETTINGS)
            else -> return (
                "ERROR: 未知设置页 key '$panel'。可选：wifi/bluetooth/display/sound/volume/battery/" +
                    "storage/apps/app_details/location/security/privacy/date_time/nfc/airplane/" +
                    "developer/accessibility/input/data_usage/notification/home/cast/vpn/print/overlay/main"
                )
        }

        val activity = context as? Activity
        return try {
            if (activity != null) {
                activity.startActivity(intent)
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            "OPENED: 已打开系统设置「$panel」页面"
        } catch (e: ActivityNotFoundException) {
            "ERROR: 设备上没有「$panel」对应的设置页面（${e.message ?: "ActivityNotFound"}）"
        } catch (e: Exception) {
            "ERROR: 打开设置失败（${e.message ?: e.javaClass.simpleName}）"
        }
    }
}
