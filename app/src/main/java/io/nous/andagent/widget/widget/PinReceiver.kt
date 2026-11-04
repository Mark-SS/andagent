package io.nous.andagent.widget.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.nous.andagent.widget.data.WidgetDataScheduler
import io.nous.andagent.widget.data.WidgetStore

class PinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        val recordId = intent.getStringExtra("recordId") ?: return
        if (!ChatWidgetProvider.owns(context, id)) return
        val store = WidgetStore(context)
        val record = store.read().records.find { it.id == recordId } ?: return
        store.bind(id, recordId)
        WidgetDataScheduler.sync(context, record)
        WidgetDataScheduler.requestNow(context, record.id, record.spec)
        ChatWidgetProvider.update(context, id)
    }
}
