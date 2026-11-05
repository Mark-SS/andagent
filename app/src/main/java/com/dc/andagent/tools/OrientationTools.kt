package com.dc.andagent.tools

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.view.Surface
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

fun orientationModeValue(mode: String): Int? = when (mode) {
    "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    "reverse_portrait" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
    "reverse_landscape" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
    "auto" -> ActivityInfo.SCREEN_ORIENTATION_USER
    else -> null
}

class OrientationTool(private val activity: () -> Activity?, override val name: String) : Tool {
    override val description = if (name == "screen_orientation_get") "读取屏幕方向、旋转角、物理像素尺寸和分辨率。" else "锁定当前 AndAgent 界面的屏幕方向，或恢复跟随系统。"
    override val parameters = if (name == "screen_orientation_set") schemaOf("mode" to strParam("portrait | landscape | auto | reverse_portrait | reverse_landscape"), required = listOf("mode")) else schemaOf()

    override suspend fun execute(args: JsonObject): String {
        if (name == "screen_orientation_get") return onMain { a -> snapshot(a).toString() }
        val mode = args.strArg("mode") ?: return "ERROR: 缺少 mode"
        val value = orientationModeValue(mode) ?: return "ERROR: 不支持的方向模式 $mode"
        val before = onMain { a -> snapshot(a).toString() }
        if (before.startsWith("ERROR")) return before
        val setResult = onMain { a -> a.requestedOrientation = value; "OK" }
        if (setResult != "OK") return setResult
        delay(500)
        val after = onMain { a -> snapshot(a).toString() }
        return buildJsonObject { put("mode", mode); put("before", kotlinx.serialization.json.Json.parseToJsonElement(before)); if (after.startsWith("ERROR")) put("afterError", after) else put("after", kotlinx.serialization.json.Json.parseToJsonElement(after)) }.toString()
    }

    private suspend fun onMain(block: (Activity) -> String): String = suspendCancellableCoroutine { cont ->
        Handler(Looper.getMainLooper()).post {
            if (!cont.isActive) return@post
            val a = activity()
            val result = if (a == null || a.isFinishing || a.isDestroyed) "ERROR: Activity 不可用" else runCatching { block(a) }.getOrElse { "ERROR: ${it.message}" }
            if (cont.isActive) cont.resume(result)
        }
    }

    private fun snapshot(a: Activity): JsonObject {
        @Suppress("DEPRECATION") val display = a.windowManager.defaultDisplay
        val rotation = when (display.rotation) { Surface.ROTATION_0 -> 0; Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 255 }
        val configuration = a.resources.configuration.orientation
        val orientation = when (rotation) {
            0 -> if (configuration == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
            90 -> "landscape"
            180 -> "reverse_portrait"
            270 -> "reverse_landscape"
            else -> "unknown"
        }
        val physical = Point().also { display.getRealSize(it) }
        val metrics = a.resources.displayMetrics
        return buildJsonObject {
            put("rotation", rotation)
            put("orientation", orientation)
            put("configurationOrientation", configuration)
            put("widthPixels", metrics.widthPixels)
            put("heightPixels", metrics.heightPixels)
            put("densityDpi", metrics.densityDpi)
            put("physicalWidthPixels", physical.x)
            put("physicalHeightPixels", physical.y)
            if (rotation == 255) put("note", "系统返回未知旋转值")
        }
    }
}
