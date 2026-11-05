package com.dc.andagent.widget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.dc.andagent.widget.data.SavedWidget
import com.dc.andagent.widget.data.WidgetDataScheduler
import com.dc.andagent.widget.data.WidgetStore
import com.dc.andagent.widget.model.*
import com.dc.andagent.widget.render.WidgetRenderer

class ChatWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, it) }
        ids.toList().mapNotNull { recordFor(context, it) }.distinctBy { it.id }.forEach {
            WidgetDataScheduler.sync(context, it)
            WidgetDataScheduler.requestNow(context, it.id, it.spec)
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { update(context, id) }

    override fun onDeleted(context: Context, ids: IntArray) {
        val store = WidgetStore(context)
        val state = runCatching { store.read() }.getOrNull()
        val removed = ids.toList().mapNotNull { state?.bindings?.get(it.toString()) }.distinct()
        store.unbind(ids)
        val remaining = runCatching { store.read().bindings.values.toSet() }.getOrDefault(emptySet())
        removed.filter { it !in remaining }.forEach { WidgetDataScheduler.cancel(context, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            updateAll(context)
            WidgetDataScheduler.syncAll(context)
        }
        if (intent.action == REFRESH) {
            val id = intent.getIntExtra("widgetId", -1)
            if (owns(context, id)) {
                update(context, id)
                recordFor(context, id)?.let { WidgetDataScheduler.requestNow(context, it.id, it.spec) }
            }
        }
    }

    companion object {
        const val REFRESH = "com.dc.andagent.widget.REFRESH"

        fun owns(context: Context, id: Int): Boolean =
            AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider == ComponentName(context, ChatWidgetProvider::class.java)

        fun update(context: Context, id: Int) {
            val store = WidgetStore(context)
            val record = runCatching { val state = store.read(); state.records.find { it.id == state.bindings[id.toString()] } }.getOrNull()
                        val spec = record?.spec ?: WidgetSpec(
                name = "AndAgent",
                root = WidgetNode(
                    NodeType.COLUMN,
                    children = listOf(WidgetNode(NodeType.TEXT, text = "在 AndAgent 设置中保存组件后添加到桌面", textSize = 16))
                )
            )
            val view = WidgetRenderer.forHost(context, spec, record?.id, id)
            AppWidgetManager.getInstance(context).updateAppWidget(id, view)
        }

        /** Refreshes only the desktop instances bound to one saved record. */
        fun updateRecord(context: Context, recordId: String) {
            val state = runCatching { WidgetStore(context).read() }.getOrNull() ?: return
            state.bindings.filterValues { it == recordId }.keys.forEach { key ->
                key.toIntOrNull()?.let { update(context, it) }
            }
        }

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, ChatWidgetProvider::class.java)).forEach { update(context, it) }
        }

        private fun recordFor(context: Context, id: Int): SavedWidget? = runCatching {
            val state = WidgetStore(context).read()
            state.records.find { it.id == state.bindings[id.toString()] }
        }.getOrNull()
    }
}
