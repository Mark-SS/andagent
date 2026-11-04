package io.nous.andagent.widget.render

import android.appwidget.AppWidgetManager
import android.app.PendingIntent
import android.view.View
import android.os.Build
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.util.TypedValue
import android.widget.RemoteViews
import io.nous.andagent.R
import io.nous.andagent.widget.data.DataCache
import io.nous.andagent.widget.data.DeviceState
import io.nous.andagent.widget.model.*
import io.nous.andagent.MainActivity
import io.nous.andagent.widget.widget.ChatWidgetProvider
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object WidgetRenderer {
    fun snapshot(
        context: Context,
        spec: WidgetSpec? = null,
        recordId: String? = null,
        includeSamples: Boolean = false
    ): DataSnapshot {
        val cached = if (spec != null && recordId != null) DataCache(context).snapshot(recordId) else emptyMap()
        val sources = if (includeSamples && spec != null) {
            val merged = cached.toMutableMap()
            spec.data?.sources?.forEach { (key, source) ->
                if (merged[key].isNullOrEmpty() && source.sample.isNotEmpty()) merged[key] = source.sample
            }
            merged
        } else cached
        return DataSnapshot(LocalDate.now(), DeviceState.batteryPercent(context), sources)
    }

    fun forHost(context: Context, spec: WidgetSpec, recordId: String?, widgetId: Int): RemoteViews {
        val sizes = WidgetSizing.sizes(AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId))
        val views = sizes.associateWith { render(context, spec, recordId, widgetId, it.width, it.height) }
        return if (views.size == 1 || Build.VERSION.SDK_INT < 31) views.values.first() else RemoteViews(views)
    }

    fun render(
        context: Context,
        requested: WidgetSpec,
        recordId: String?,
        widgetId: Int = 0,
        width: Float = 320f,
        height: Float = 200f,
        preview: Boolean = false
    ): RemoteViews {
        SpecCodec.validate(requested)
        val data = snapshot(context, requested, recordId, includeSamples = preview)
        val fitted = WidgetSizing.fit(context, requested, width, height, data)
        val spec = fitted.spec
        val strings = context
        return RemoteViews(context.packageName, R.layout.widget_root).apply {
            if (Build.VERSION.SDK_INT >= 31) {
                setColorStateList(R.id.widget_background, "setBackgroundTintList", ColorStateList.valueOf(Color.parseColor(spec.background)))
            } else {
                setInt(R.id.widget_background, "setBackgroundColor", Color.parseColor(spec.background))
            }
            val p = dp(context, spec.padding)
            setViewPadding(R.id.widget_background, p, p, p, p)
            removeAllViews(R.id.widget_content)
            addView(R.id.widget_content, node(context, spec.root, data, (width - spec.padding * 2).coerceAtLeast(1f), if (preview) 0 else widgetId, preview, strings))
            setViewVisibility(R.id.widget_footer, if (fitted.footer) View.VISIBLE else View.GONE)
            setTextViewText(R.id.widget_status, "更新 " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")))
            // Node-level clicks take precedence; the whole card only opens the editor when nothing else is clickable.
            if (recordId != null && !hasClick(spec.root)) {
                val edit = Intent(context, MainActivity::class.java).apply {
                    this.data = Uri.parse("andagent://edit/$recordId/$widgetId")
                    putExtra("recordId", recordId)
                }
                setOnClickPendingIntent(R.id.widget_content, PendingIntent.getActivity(context, widgetId, edit, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            if (widgetId > 0) {
                val refresh = Intent(context, ChatWidgetProvider::class.java).apply {
                    action = ChatWidgetProvider.REFRESH
                    this.data = Uri.parse("andagent://refresh/$widgetId")
                    putExtra("widgetId", widgetId)
                }
                setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(context, widgetId, refresh, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
        }
    }

    /** True when any node declares its own click target. */
    private fun hasClick(n: WidgetNode): Boolean = n.click != null || n.children.any(::hasClick)

    private fun clickPending(context: Context, click: String, widgetId: Int): PendingIntent {
        val uri = Uri.parse(click)
        val intent = if (uri.scheme.equals("andagent", ignoreCase = true)) {
            Intent(context, MainActivity::class.java).setData(uri)
        } else {
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Same widget + same URI share one PendingIntent so layout variants never overwrite each other.
        val request = (widgetId * 31 + click.hashCode()) and 0x7FFFFFFF
        return PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun node(context: Context, n: WidgetNode, data: DataSnapshot, width: Float, widgetId: Int, preview: Boolean, strings: Context): RemoteViews {
        val layout = when(n.type) {
            NodeType.COLUMN -> R.layout.node_column
            NodeType.ROW -> R.layout.node_row
            NodeType.TEXT -> R.layout.node_text
            NodeType.SPACER -> R.layout.node_spacer
            NodeType.PROGRESS -> R.layout.node_progress
        }
        return RemoteViews(context.packageName, layout).apply {
            val p = dp(context, n.padding)
            setViewPadding(R.id.node, p, p, p, p)
            if (!preview) n.click?.let { setOnClickPendingIntent(R.id.node, clickPending(context, it, widgetId)) }
            when(n.type) {
                NodeType.COLUMN, NodeType.ROW -> {
                    removeAllViews(R.id.node)
                    val inner = (width - n.padding * 2).coerceAtLeast(1f)
                    val fixed = n.children.filter { it.type == NodeType.SPACER }.sumOf { it.size }
                    val share = ((inner - fixed) / maxOf(1, n.children.count { it.type != NodeType.SPACER })).coerceAtLeast(1f)
                    n.children.forEach {
                        val childWidth = if (n.type == NodeType.ROW) { if (it.type == NodeType.SPACER) it.size.toFloat() else share } else inner
                        val child = node(context, it, data, childWidth, widgetId, preview, strings)
                        if (Build.VERSION.SDK_INT >= 31 && n.type == NodeType.ROW && it.type != NodeType.SPACER) child.setViewLayoutWidth(R.id.node, childWidth, TypedValue.COMPLEX_UNIT_DIP)
                        addView(R.id.node, child)
                    }
                }
                NodeType.TEXT -> {
                    val value = Bindings.text(n, data)
                    setInt(R.id.node, "setMaxLines", n.maxLines)
                    setTextViewText(R.id.node, value)
                    setContentDescription(R.id.node, value)
                    setTextViewTextSize(R.id.node, TypedValue.COMPLEX_UNIT_SP, n.textSize.toFloat())
                    setTextColor(R.id.node, Color.parseColor(n.color))
                }
                NodeType.SPACER -> {
                    if (Build.VERSION.SDK_INT >= 31) {
                        setViewLayoutHeight(R.id.node, n.size.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
                        setViewLayoutWidth(R.id.node, n.size.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
                    }
                }
                NodeType.PROGRESS -> {
                    setProgressBar(R.id.node, 100, Bindings.progress(n, data), false)
                    setContentDescription(
                        R.id.node,
                        if (n.binding == Binding.BATTERY && data.battery == null) "电量未知"
                        else "${Bindings.progress(n, data)}%"
                    )
                }
            }
        }
    }
    private fun dp(context: Context, v: Int) = (context.resources.displayMetrics.density * v).toInt()
}
