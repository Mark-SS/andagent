package io.nous.andagent.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.nous.andagent.widget.data.DataCache
import io.nous.andagent.widget.data.WidgetDataScheduler
import io.nous.andagent.widget.data.WidgetStore
import io.nous.andagent.widget.model.SpecCodec
import io.nous.andagent.widget.widget.ChatWidgetProvider
import io.nous.andagent.widget.widget.PinReceiver
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object WidgetApi {
    fun save(context: Context, raw: String, id: String? = null): String {
        val spec = SpecCodec.decode(raw)
        val record = WidgetStore(context).save(id, spec)
        DataCache(context).clear(record.id)
        WidgetDataScheduler.sync(context, record)
        WidgetDataScheduler.requestNow(context, record.id, record.spec)
        ChatWidgetProvider.updateRecord(context, record.id)
        return record.id
    }

    fun pin(context: Context, id: String): String {
        val record = WidgetStore(context).read().records.find { it.id == id } ?: error("组件不存在")
        val manager = AppWidgetManager.getInstance(context)
        if (!manager.isRequestPinAppWidgetSupported) return "请在桌面长按空白处，添加 AndAgent 小组件"
        val callback = Intent(context, PinReceiver::class.java).apply {
            action = "io.nous.andagent.widget.PIN"
            data = Uri.parse("andagent://pin/$id/${System.currentTimeMillis()}")
            putExtra("recordId", record.id)
        }
        val pending = PendingIntent.getBroadcast(context, id.hashCode(), callback,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT or
                (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
        return if (manager.requestPinAppWidget(ComponentName(context, ChatWidgetProvider::class.java), null, pending))
            "已请求添加「${record.spec.name}」；请在启动器确认"
        else "请在桌面长按空白处，添加 AndAgent 小组件"
    }

    fun list(context: Context): String = WidgetStore(context).read().records.map { record ->
        buildJsonObject {
            put("id", record.id)
            put("name", record.spec.name)
            put("specJson", SpecCodec.encode(record.spec))
        }
    }.let { kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.json.JsonObject.serializer()), it) }

    fun delete(context: Context, id: String) {
        WidgetStore(context).delete(id)
        DataCache(context).clear(id)
        WidgetDataScheduler.cancel(context, id)
        ChatWidgetProvider.updateAll(context)
    }
}
