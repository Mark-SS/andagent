package com.dc.andagent.widget

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import com.dc.andagent.widget.data.WidgetDataScheduler
import com.dc.andagent.widget.data.WidgetStore
import com.dc.andagent.widget.widget.ChatWidgetProvider

class ConfigureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        setResult(RESULT_CANCELED)
        if (!ChatWidgetProvider.owns(this, id)) { finish(); return }
        val records = runCatching { WidgetStore(this).read().records }.getOrDefault(emptyList())
        if (records.isEmpty()) {
            AlertDialog.Builder(this).setMessage("请先在 AndAgent 设置中保存小组件")
                .setPositiveButton("知道了") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
            return
        }
        AlertDialog.Builder(this).setTitle("选择 AndAgent 小组件")
            .setItems(records.map { it.spec.name }.toTypedArray()) { _, which ->
                val record = records[which]
                WidgetStore(this).bind(id, record.id)
                WidgetDataScheduler.sync(this, record)
                WidgetDataScheduler.requestNow(this, record.id, record.spec)
                ChatWidgetProvider.update(this, id)
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                finish()
            }.setOnCancelListener { finish() }.show()
    }
}
