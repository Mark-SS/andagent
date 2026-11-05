package com.dc.andagent.widget.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.dc.andagent.widget.model.SourceType
import com.dc.andagent.widget.model.WidgetSpec
import java.util.concurrent.TimeUnit

/**
 * Background refresh runs on WorkManager. The shortest supported period is 15 minutes and
 * execution time is decided by the system; it is not a real-time timer.
 */
object WidgetDataScheduler {
    private const val PREFIX = "widget-data-"
    private const val MIN_MINUTES = 15L
    private const val MAX_MINUTES = 1440L

    fun sync(context: Context, record: SavedWidget) {
        val sources = record.spec.data?.sources.orEmpty()
        // Only records that a launcher instance currently points at need periodic work.
        val bound = runCatching { WidgetStore(context).read().bindings.values.any { it == record.id } }.getOrDefault(false)
        if (sources.isEmpty() || !bound) {
            cancel(context, record.id)
            return
        }
        val minutes = sources.values.map { it.ttlSeconds / 60 }.minOrNull()
            ?.let { it.toLong().coerceIn(MIN_MINUTES, MAX_MINUTES) } ?: MIN_MINUTES
        val request = PeriodicWorkRequestBuilder<WidgetDataWorker>(minutes, TimeUnit.MINUTES, 5L, TimeUnit.MINUTES)
            .setConstraints(constraints(record.spec))
            .setInputData(workDataOf(WidgetDataWorker.KEY_RECORD to record.id))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PREFIX + record.id, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun syncAll(context: Context) {
        runCatching { WidgetStore(context).read().records }.getOrDefault(emptyList()).forEach { sync(context, it) }
    }

    fun requestNow(context: Context, recordId: String, spec: WidgetSpec) {
        if (spec.data?.sources.isNullOrEmpty()) return
        val request = OneTimeWorkRequestBuilder<WidgetDataWorker>()
            .setConstraints(constraints(spec))
            .setInputData(workDataOf(WidgetDataWorker.KEY_RECORD to recordId, WidgetDataWorker.KEY_FORCE to true))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(PREFIX + "now-" + recordId, ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context, recordId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(PREFIX + recordId)
        WorkManager.getInstance(context).cancelUniqueWork(PREFIX + "now-" + recordId)
    }

    fun cancelAll(context: Context) {
        runCatching { WidgetStore(context).read().records }.getOrDefault(emptyList()).forEach { cancel(context, it.id) }
    }

    /** Manual refresh for every record currently shown on a launcher, regardless of TTL. */
    fun refreshBoundNow(context: Context) {
        val state = runCatching { WidgetStore(context).read() }.getOrNull() ?: return
        state.records.filter { record -> state.bindings.values.any { it == record.id } }.forEach {
            requestNow(context, it.id, it.spec)
        }
    }

    private fun constraints(spec: WidgetSpec): Constraints {
        val sources = spec.data?.sources.orEmpty().values
        val network = sources.any { it.type == SourceType.HTTP || "network" in it.require }
        val charging = sources.any { "charging" in it.require }
        val battery = sources.any { "batteryNotLow" in it.require }
        return Constraints.Builder()
            .setRequiredNetworkType(if (network) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
            .setRequiresCharging(charging)
            .setRequiresBatteryNotLow(battery)
            .build()
    }
}
