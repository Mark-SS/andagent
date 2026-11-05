package com.dc.andagent.widget.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dc.andagent.widget.widget.ChatWidgetProvider

class WidgetDataWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val recordId = inputData.getString(KEY_RECORD) ?: return Result.failure()
        val record = runCatching { WidgetStore(applicationContext).read().records.find { it.id == recordId } }
            .getOrNull() ?: return Result.success()
        if (record.spec.data?.sources.isNullOrEmpty()) return Result.success()

        // TTL decides whether a scheduled run actually hits the network or the script engine.
        WidgetDataRefresher.refresh(applicationContext, record.id, record.spec, force = inputData.getBoolean(KEY_FORCE, false))
        ChatWidgetProvider.updateRecord(applicationContext, record.id)
        return Result.success()
    }

    companion object { const val KEY_RECORD = "recordId"; const val KEY_FORCE = "force" }
}
