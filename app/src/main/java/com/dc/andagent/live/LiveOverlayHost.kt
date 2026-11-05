package com.dc.andagent.live

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Small floating canvas for live results; permission flow matches the camera overlay. */
class LiveOverlayHost(private val activity: Activity) {
    private val main = Handler(Looper.getMainLooper())
    private val windows = mutableMapOf<String, View>()

    fun prepare(): Boolean {
        if (Settings.canDrawOverlays(activity)) return true
        main.post {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${activity.packageName}")))
        }
        return false
    }

    fun update(id: String, value: JsonElement) {
        main.post {
            if (!Settings.canDrawOverlays(activity) || activity.isFinishing) return@post
            val manager = activity.getSystemService(WindowManager::class.java)
            val view = windows[id] as? ResultCanvas ?: ResultCanvas(activity).also {
                val density = activity.resources.displayMetrics.density
                val params = WindowManager.LayoutParams(
                    (320 * density).toInt(), (180 * density).toInt(),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.TOP or Gravity.START; x = 30; y = 100 + windows.size * 30 }
                manager.addView(it, params)
                windows[id] = it
            }
            view.value = value
            view.invalidate()
        }
    }

    fun remove(id: String) { main.post { windows.remove(id)?.let { activity.getSystemService(WindowManager::class.java).removeView(it) } } }
    fun close() { main.post { windows.keys.toList().forEach(::remove) } }

    private class ResultCanvas(activity: Activity) : View(activity) {
        var value: JsonElement = JsonPrimitive("")
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f }
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(0xEE101820.toInt())
            paint.color = 0xFF7DD3FC.toInt(); paint.textSize = 34f
            canvas.drawText("Live", 20f, 42f, paint)
            paint.color = Color.WHITE; paint.textSize = 25f
            val lines = value.toString().chunked(42).take(5)
            lines.forEachIndexed { index, line -> canvas.drawText(line, 20f, 78f + index * 27f, paint) }
        }
    }
}
