package com.dc.andagent.tools

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 无悬浮窗权限时的应用内伪浮窗：把同款 UI（标题栏 + 内容区）作为普通 View
 * 叠加在主 Activity 的 decorView 上。随 Activity 生命周期销毁，只在本 app
 * 内可见——权限授予后由真实 CameraOverlayService 接管。
 *
 * 单例管理：show() 先移除旧的（避免叠加），dismiss() 由 hide 工具或
 * Activity 销毁清理。
 */
object InAppOverlayHost {

    private val main = Handler(Looper.getMainLooper())
    private var current: View? = null
    private var title: TextView? = null
    private var contentHook: ((FrameLayout) -> Unit)? = null

    /** 内容挂载回调：宿主（如相机预览）把渲染 View 塞进 content 区域。 */
    fun attachContent(hook: (FrameLayout) -> Unit) { contentHook = hook }

    @SuppressLint("InflateParams")
    fun show(
        activityContext: Context,
        titleText: String,
        widthDp: Int,
        heightDp: Int,
        xDp: Int,
        yDp: Int,
        contentHook: (FrameLayout) -> Unit,
    ): Boolean {
        val activity = activityContext as? android.app.Activity ?: return false
        if (activity.isFinishing || activity.window == null) return false
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        main.post {
            dismiss()
            val frame = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
            val content = FrameLayout(activity)
            val header = TextView(activity).apply {
                text = titleText
                setTextColor(Color.WHITE); textSize = 14f
                setBackgroundColor(0xBB222222.toInt())
                setPadding(dp(10), dp(6), dp(10), dp(6))
            }
            val close = TextView(activity).apply {
                text = "✕"; setTextColor(Color.WHITE); textSize = 18f
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener { dismiss() }
            }
            val headerRow = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setBackgroundColor(0xBB222222.toInt()); gravity = Gravity.CENTER_VERTICAL
            }
            headerRow.addView(header, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            headerRow.addView(close, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            frame.addView(content, FrameLayout.LayoutParams(-1, -1))
            frame.addView(headerRow, FrameLayout.LayoutParams(-1, dp(34), Gravity.TOP))
            // 应用内伪浮窗位置：decorView 内绝对定位（近似系统浮窗 x/y）。
            frame.translationX = dp(xDp).toFloat()
            frame.translationY = dp(yDp).toFloat()
            val lp = ViewGroup.LayoutParams(dp(widthDp), dp(heightDp))
            activity.window.decorView.let { root ->
                (root as? ViewGroup)?.addView(frame, lp) ?: return@post
            }
            contentHook(content)
            current = frame
            title = header
        }
        return true
    }

    fun setTitle(text: String) { main.post { title?.text = text } }

    fun dismiss() { main.post {
        current?.let { v ->
            (v.parent as? ViewGroup)?.removeView(v)
            // 通知宿主清理（相机释放等）。
            contentHook?.invoke(FrameLayout(v.context).apply { tag = "dismiss" })
        }
        current = null
    } }

    fun isShowing(): Boolean = current != null
}
