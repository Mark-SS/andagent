package com.dc.andagent.cron

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dc.andagent.R
import com.dc.andagent.shell.TermuxEnv
import com.dc.andagent.tools.ScriptFiles
import com.dc.andagent.widget.data.WidgetToolRuntime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

object CronScheduler {
    private const val ACTION = "com.dc.andagent.cron.TICK"
    private const val REQUEST = 1737

    fun exactAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun intent(context: Context, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, CronReceiver::class.java).setAction(ACTION),
        flags or PendingIntent.FLAG_IMMUTABLE,
    )

    fun ensure(context: Context) {
        val tasks = CronStore(context).list()
        if (tasks.none { it.enabled }) return
        if (intent(context, PendingIntent.FLAG_NO_CREATE) == null) rebuild(context)
    }

    /** Recompute one global alarm whenever the store changes or the device boots. */
    fun rebuild(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java)
        intent(context, PendingIntent.FLAG_NO_CREATE)?.let(manager::cancel)
        val now = System.currentTimeMillis()
        val tasks = CronStore(context).update { list ->
            for (i in list.indices) {
                val task = list[i]
                if (task.enabled && (task.nextRun == null || task.nextRun <= now))
                    list[i] = task.copy(nextRun = CronSchedule.parse(task.schedule).next(now))
            }
        }
        val next = tasks.filter { it.enabled }.mapNotNull { it.nextRun }.minOrNull() ?: return
        val alarm = requireNotNull(intent(context, PendingIntent.FLAG_UPDATE_CURRENT))
        try {
            if (exactAllowed(context)) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarm)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarm)
        } catch (e: SecurityException) {
            // Permission may be revoked between canScheduleExactAlarms and setExact.
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarm)
            Log.w("CronScheduler", "Exact alarm permission revoked", e)
        }
    }

    fun runNow(context: Context, taskId: String, force: Boolean = true) = enqueue(context, taskId, force)

    private fun enqueue(context: Context, taskId: String, force: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<CronWorker>()
            .setInputData(Data.Builder().putString("id", taskId).putBoolean("force", force).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("cron-$taskId-${System.currentTimeMillis()}", ExistingWorkPolicy.KEEP, request)
    }

    fun tick(context: Context) {
        val now = System.currentTimeMillis()
        val due = mutableListOf<String>()
        CronStore(context).update { tasks ->
            for (i in tasks.indices) {
                val task = tasks[i]
                if (task.enabled && task.nextRun != null && task.nextRun <= now + 1000) {
                    due += task.id
                    tasks[i] = task.copy(nextRun = CronSchedule.parse(task.schedule).next(now))
                }
            }
        }
        // Chain before executing; failure in a worker cannot stop future alarms.
        rebuild(context)
        due.forEach { enqueue(context, it) }
    }

    fun notify(context: Context, title: String, body: String, id: Int) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("通知权限未授予")
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("cron", "定时任务", NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify(id, NotificationCompat.Builder(context, "cron")
            .setSmallIcon(R.drawable.ic_stat_andagent).setContentTitle(title)
            .setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true).build())
    }
}

class CronReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)
                    CronScheduler.rebuild(context)
                else CronScheduler.tick(context)
            } catch (e: Exception) { Log.e("CronReceiver", "Alarm chain failed", e) }
            finally { pending.finish() }
        }.start()
    }
}

class CronWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        val task = CronStore(applicationContext).list().find { it.id == id } ?: return Result.success()
        if (!task.enabled && !inputData.getBoolean("force", false)) return Result.success()
        val output = runCatching {
            when (task.kind) {
                "script" -> {
                    val root = File(requireNotNull(applicationContext.getExternalFilesDir(null)), "scripts")
                    val file = ScriptFiles.resolve(root, task.payload)
                    require(file.isFile && file.length() <= 100_000) { "脚本不存在或超过 100KB" }
                    WidgetToolRuntime.evalCron(applicationContext, file.readText(), buildJsonObject {
                        put("trigger", "cron"); put("task", task.name)
                    })
                }
                "shell" -> TermuxEnv(applicationContext).exec(task.payload, 60)
                "notify" -> {
                    CronScheduler.notify(applicationContext, task.name, task.payload, task.id.hashCode())
                    "通知已发送"
                }
                else -> error("未知任务类型：${task.kind}")
            }
        }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
        val failed = output.startsWith("ERROR:")
        var disabled = false
        CronStore(applicationContext).update { tasks ->
            val index = tasks.indexOfFirst { it.id == id }
            if (index >= 0) {
                val current = tasks[index]
                val failures = if (failed) current.failures + 1 else 0
                disabled = failures >= 3
                tasks[index] = current.copy(lastRun = System.currentTimeMillis(),
                    lastResult = output.take(200), failures = failures,
                    enabled = current.enabled && !disabled, nextRun = if (disabled) null else current.nextRun)
            }
        }
        if (disabled) {
            runCatching { CronScheduler.notify(applicationContext, "定时任务已暂停", "${task.name} 连续失败 3 次：${output.take(100)}", task.id.hashCode()) }
            CronScheduler.rebuild(applicationContext)
        }
        return Result.success()
    }
}
