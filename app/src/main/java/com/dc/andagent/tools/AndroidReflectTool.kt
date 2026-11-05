package com.dc.andagent.tools

import android.app.ActivityManager
import android.content.Context
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.MediaRouter
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import kotlinx.serialization.json.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** One-way, explicitly scoped reflection. No caller-supplied class is loaded. */
object ReflectPolicy {
    val classes: Map<String, Class<*>> = linkedMapOf(
        // ---- v1 原有域 ----
        "android.media.AudioManager" to AudioManager::class.java,
        "android.media.MediaRouter" to MediaRouter::class.java,
        "android.hardware.camera2.CameraManager" to CameraManager::class.java,
        "android.hardware.camera2.CameraCharacteristics" to CameraCharacteristics::class.java,
        "android.hardware.SensorManager" to SensorManager::class.java,
        "android.os.PowerManager" to PowerManager::class.java,
        "android.app.ActivityManager" to ActivityManager::class.java,
        "android.net.ConnectivityManager" to ConnectivityManager::class.java,
        "android.os.Build" to Build::class.java,
        "android.os.SystemClock" to SystemClock::class.java,
        "android.provider.Settings\$System" to Settings.System::class.java,
        "android.provider.Settings\$Global" to Settings.Global::class.java,
        "android.provider.Settings\$Secure" to Settings.Secure::class.java,
        "android.telephony.TelephonyManager" to TelephonyManager::class.java,
        "android.hardware.display.DisplayManager" to DisplayManager::class.java,
        "android.content.pm.PackageManager" to PackageManager::class.java,
        // ---- v2 扩充域（只读/低危优先；写操作仍走专门工具）----
        "android.os.Vibrator" to android.os.Vibrator::class.java,
        "android.os.BatteryManager" to android.os.BatteryManager::class.java,
        "android.app.AlarmManager" to android.app.AlarmManager::class.java,
        "android.app.NotificationManager" to android.app.NotificationManager::class.java,
        "android.app.usage.StorageStatsManager" to android.app.usage.StorageStatsManager::class.java,
        "android.bluetooth.BluetoothManager" to android.bluetooth.BluetoothManager::class.java,
        "android.net.wifi.WifiManager" to android.net.wifi.WifiManager::class.java,
        "android.location.LocationManager" to android.location.LocationManager::class.java,
        "android.media.MediaCodecList" to android.media.MediaCodecList::class.java,
        "android.media.AudioDeviceInfo" to android.media.AudioDeviceInfo::class.java,
        "android.telephony.SubscriptionManager" to android.telephony.SubscriptionManager::class.java,
        "android.hardware.usb.UsbManager" to android.hardware.usb.UsbManager::class.java,
        "android.content.res.Resources" to android.content.res.Resources::class.java,
        "android.view.WindowManager" to android.view.WindowManager::class.java,
    )
    private val methods = mapOf(
        "android.media.AudioManager" to setOf("getStreamVolume", "getStreamMaxVolume", "getStreamMinVolume", "getRingerMode", "isMusicActive", "getDevices", "isSpeakerphoneOn", "setStreamVolume", "getParameters", "isBluetoothScoOn", "getMode", "isWiredHeadsetOn"),
        "android.media.MediaRouter" to setOf("getRouteCount", "getRouteAt", "getSelectedRoute"),
        "android.hardware.camera2.CameraManager" to setOf("getCameraIdList", "getCameraCharacteristics", "setTorchMode", "getConcurrentCameraIds"),
        "android.hardware.camera2.CameraCharacteristics" to setOf("getKeys", "get"),
        "android.hardware.SensorManager" to setOf("getSensorList", "getDefaultSensor", "getDynamicSensorList"),
        "android.os.PowerManager" to setOf("isInteractive", "isPowerSaveMode", "isDeviceIdleMode", "isIgnoringBatteryOptimizations", "getBatteryRemainingTime", "isSustainedPerformanceModeSupported"),
        "android.app.ActivityManager" to setOf("getRunningAppProcesses", "getMemoryInfo", "getDeviceConfigurationInfo", "isLowRamDevice", "isRunningInTestHarness"),
        "android.net.ConnectivityManager" to setOf("getActiveNetwork", "getNetworkCapabilities", "getActiveNetworkInfo", "getAllNetworks", "getNetworkInfo"),
        "android.os.Build" to emptySet(),
        "android.os.SystemClock" to setOf("elapsedRealtime", "uptimeMillis", "elapsedRealtimeNanos", "currentThreadTimeMillis", "currentGnssTimeMillis"),
        "android.provider.Settings\$System" to setOf("getString", "getInt", "getLong", "getFloat"),
        "android.provider.Settings\$Global" to setOf("getString", "getInt", "getLong", "getFloat"),
        "android.provider.Settings\$Secure" to setOf("getString", "getInt", "getLong", "getFloat"),
        "android.telephony.TelephonyManager" to setOf("getSimState", "getNetworkType", "getDataNetworkType", "getVoiceNetworkType", "getNetworkOperatorName", "getSimOperatorName", "isNetworkRoaming", "getPhoneType", "getSimCount", "isDataEnabled", "getNetworkSpecifier"),
        "android.hardware.display.DisplayManager" to setOf("getDisplays", "getDisplay"),
        "android.content.pm.PackageManager" to setOf("getPackageInfo", "getInstalledPackages", "getInstalledApplications", "getApplicationInfo", "getLaunchIntentForPackage", "canPackageQuery"),
        // ---- v2 扩充域方法 ----
        "android.os.Vibrator" to setOf("hasVibrator", "hasAmplitudeControl", "getDefaultVibrationEffect"),
        "android.os.BatteryManager" to setOf("getIntProperty", "getLongProperty", "isCharging", "getChargeTimeRemaining"),
        "android.app.AlarmManager" to setOf("getNextAlarmClock", "canScheduleExactAlarms"),
        "android.app.NotificationManager" to setOf("getCurrentInterruptionFilter", "isNotificationPolicyAccessGranted", "getNotificationChannels", "areNotificationsEnabled"),
        "android.app.usage.StorageStatsManager" to setOf("queryStatsForUid", "queryStatsForPackage"),
        "android.bluetooth.BluetoothManager" to setOf("getAdapter", "getConnectedDevices"),
        "android.net.wifi.WifiManager" to setOf("isWifiEnabled", "getConnectionInfo", "isStaApConcurrencySupported", "isEasyConnectSupported"),
        "android.location.LocationManager" to setOf("isProviderEnabled", "getAllProviders", "getProvider", "isLocationEnabled", "getLastKnownLocation"),
        "android.media.MediaCodecList" to setOf("getCodecInfos", "findDecoderForFormat"),
        "android.media.AudioDeviceInfo" to setOf("getType", "getProductName", "isSource", "isSink"),
        "android.telephony.SubscriptionManager" to setOf("getActiveSubscriptionInfoCount", "getActiveSubscriptionInfoList", "isNetworkRoaming"),
        "android.hardware.usb.UsbManager" to setOf("getDeviceList", "hasPermission", "isUsbAccessorySupported"),
        "android.content.res.Resources" to setOf("getDisplayMetrics", "getConfiguration"),
        "android.view.WindowManager" to setOf("getDefaultDisplay"),
    )
    private val forbidden = Regex("(?i)(imei|meid|deviceid|subscriberid|serialnumber|^serial$|runtime|exec|^put|password|androidid|iccid|imsi|line1number|voicemailnumber)")
    fun classAllowed(name: String) = classes.keys.any { name.startsWith(it) && (name.length == it.length || name[it.length] == '$') } && classes.containsKey(name)
    fun check(clazz: String, method: String) {
        require(classAllowed(clazz)) { "类不在反射白名单：$clazz。白名单：${classes.keys.joinToString()}" }
        require(!forbidden.containsMatchIn(method)) { "反射方法被黑名单拦截：$method" }
        require(method in methods.getValue(clazz) || (clazz == "android.os.Build" && method.matches(Regex("[A-Z][A-Z0-9_]*")))) {
            "方法不在反射白名单：$clazz.$method；可用：${methods.getValue(clazz).joinToString()}"
        }
    }
}

object ReflectAdapter {
    fun convert(value: JsonElement, type: Class<*>): Any? {
        if (value == JsonNull) { require(!type.isPrimitive) { "基本类型不能为 null" }; return null }
        val p = value as? JsonPrimitive ?: throw IllegalArgumentException("只支持基本类型参数")
        val s = p.content
        if (type != String::class.java) require(!p.isString) { "数值和布尔参数不能是字符串" }
        return when (type) {
            String::class.java -> { require(p.isString) { "期望 string" }; s }
            java.lang.Integer.TYPE, java.lang.Integer::class.java -> s.toInt()
            java.lang.Long.TYPE, java.lang.Long::class.java -> s.toLong()
            java.lang.Float.TYPE, java.lang.Float::class.java -> s.toFloat()
            java.lang.Double.TYPE, java.lang.Double::class.java -> s.toDouble()
            java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> { require(s == "true" || s == "false") { "期望 boolean" }; s.toBoolean() }
            else -> throw IllegalArgumentException("不支持对象参数：${type.name}")
        }
    }
    fun result(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value.take(2048))
        else -> if (value.javaClass.isArray) {
            val count = java.lang.reflect.Array.getLength(value).coerceAtMost(100)
            JsonArray((0 until count).map { JsonPrimitive((java.lang.reflect.Array.get(value, it)?.toString() ?: "null").take(2048)) })
        } else JsonPrimitive(value.toString().take(2048))
    }
}

/** Settings keys that may be read through the reflection bridge. */
private val SETTINGS_WHITELIST = setOf(
    "screen_brightness", "screen_off_timeout", "accelerometer_rotation",
    "sound_effects_enabled", "font_scale", "time_12_24", "airplane_mode_on",
    "bluetooth_on", "ambient_light_sensor_enabled",
)

class RestrictedReflectExecutor(private val context: Context) {
    fun invoke(clazz: String, method: String, args: JsonArray = JsonArray(emptyList()), static: Boolean = false): String {
        ReflectPolicy.check(clazz, method)
        require(args.size <= 8) { "参数最多 8 个" }
        if (clazz.startsWith("android.provider.Settings\$")) {
            val key = args.filterIsInstance<JsonPrimitive>().lastOrNull { it.isString }?.content
            require(key != null && key in SETTINGS_WHITELIST) {
                "Settings 键不在白名单：${key ?: "(缺少键参数)"}；允许：${SETTINGS_WHITELIST.joinToString()}"
            }
        }
        if (clazz == "android.app.ActivityManager" && method == "getMemoryInfo" && !static && args.isEmpty()) {
            val info = ActivityManager.MemoryInfo()
            (service(clazz) as ActivityManager).getMemoryInfo(info)
            return buildJsonObject { put("availMem", info.availMem); put("totalMem", info.totalMem); put("threshold", info.threshold); put("lowMemory", info.lowMemory) }.toString()
        }
        if (clazz == "android.net.ConnectivityManager" && method == "getNetworkCapabilities" && !static && args.isEmpty()) {
            val manager = service(clazz) as ConnectivityManager
            val network = manager.activeNetwork ?: return "null"
            return ReflectAdapter.result(manager.getNetworkCapabilities(network)).toString()
        }
        if (clazz == "android.hardware.camera2.CameraCharacteristics" && method == "getKeys" && !static && args.size == 1) {
            val id = ReflectAdapter.convert(args[0], String::class.java) as String
            val manager = service("android.hardware.camera2.CameraManager") as CameraManager
            return JsonArray(manager.getCameraCharacteristics(id).keys.take(100).map { JsonPrimitive(it.name.take(2048)) }).toString()
        }
        val cls = ReflectPolicy.classes.getValue(clazz)
        if (clazz == "android.os.Build" && static && args.isEmpty()) {
            val field = cls.getField(method)
            require(Modifier.isStatic(field.modifiers) && Modifier.isPublic(field.modifiers)) { "常量不可读取" }
            return ReflectAdapter.result(field.get(null)).toString()
        }
        val signatures = cls.methods.filter { it.name == method && Modifier.isStatic(it.modifiers) == static }
        if (signatures.isEmpty()) throw NoSuchMethodException("无此方法；同类方法：${cls.methods.map { it.toGenericString() }.distinct().sorted().joinToString("; ").take(6000)}")
        val target = if (static) null else service(clazz)
        var last: Exception? = null
        for (candidate in signatures) {
            try {
                val adapted = adapt(candidate, args)
                return ReflectAdapter.result(candidate.invoke(target, *adapted)).toString()
            } catch (e: IllegalArgumentException) { last = e }
              catch (e: InvocationTargetException) { throw IllegalStateException("${e.targetException.javaClass.simpleName}: ${e.targetException.message}", e.targetException) }
        }
        throw IllegalArgumentException("参数个数/类型不匹配；期望：${signatures.joinToString("; ") { it.toGenericString() }}；原因：${last?.message}")
    }
    private fun adapt(method: Method, args: JsonArray): Array<Any?> {
        val types = method.parameterTypes
        val values = mutableListOf<Any?>()
        var index = 0
        for (type in types) {
            val arg = args.getOrNull(index)
            if (type == Context::class.java && (args.size == types.size - 1 || arg == null || arg == JsonPrimitive("\$context"))) {
                values += context; if (arg == JsonPrimitive("\$context")) index++
            } else if (type == ContentResolver::class.java && (args.size == types.size - 1 || arg == null || arg == JsonPrimitive("\$contentResolver") || arg == JsonPrimitive("\$context"))) {
                values += context.contentResolver; if (arg == JsonPrimitive("\$contentResolver") || arg == JsonPrimitive("\$context")) index++
            } else {
                require(arg != null) { "缺少 ${type.simpleName} 参数" }
                values += ReflectAdapter.convert(arg, type); index++
            }
        }
        require(index == args.size) { "参数过多" }
        return values.toTypedArray()
    }
    private fun service(clazz: String): Any = when (clazz) {
        "android.media.AudioManager" -> context.getSystemService(Context.AUDIO_SERVICE)
        "android.media.MediaRouter" -> context.getSystemService(Context.MEDIA_ROUTER_SERVICE)
        "android.hardware.camera2.CameraManager" -> context.getSystemService(Context.CAMERA_SERVICE)
        "android.hardware.SensorManager" -> context.getSystemService(Context.SENSOR_SERVICE)
        "android.os.PowerManager" -> context.getSystemService(Context.POWER_SERVICE)
        "android.app.ActivityManager" -> context.getSystemService(Context.ACTIVITY_SERVICE)
        "android.net.ConnectivityManager" -> context.getSystemService(Context.CONNECTIVITY_SERVICE)
        "android.telephony.TelephonyManager" -> context.getSystemService(Context.TELEPHONY_SERVICE)
        "android.hardware.display.DisplayManager" -> context.getSystemService(Context.DISPLAY_SERVICE)
        "android.content.pm.PackageManager" -> context.packageManager
        else -> throw IllegalArgumentException("此类仅可用于静态查询：$clazz")
    } ?: throw IllegalStateException("系统服务不可用：$clazz")
}

class AndroidReflectTool(private val executor: RestrictedReflectExecutor) : Tool {
    override val name = "android_reflect"
    override val description = "受限反射桥：查询白名单 Android 系统 API；对象结果只返回文本，不可继续反射。"
    override val parameters = schemaOf(
        "clazz" to strParam("完整白名单类名"),
        "method" to strParam("白名单方法名或 Build 静态常量名"),
        "args_json" to strParam("可选：JSON 数组；支持基本类型、null、\$context/\$contentResolver 占位符"),
        "static" to boolParam("是否为静态方法/常量，默认 false"),
        "confirm" to boolParam("调用 setStreamVolume 时必须为 true"),
        required = listOf("clazz", "method"),
    )
    override suspend fun execute(args: JsonObject): String {
        val raw = args.strArg("args_json") ?: "[]"
        if (args.strArg("method") == "setStreamVolume") require(args.boolArg("confirm") == true) { "设置音量需 confirm=true" }
        return executor.invoke(args.strArg("clazz") ?: "", args.strArg("method") ?: "", Json.parseToJsonElement(raw) as JsonArray, args.boolArg("static") == true)
    }
}
