package com.dc.andagent.tools

import android.content.Context
import com.dc.andagent.cron.CronSchedule
import com.dc.andagent.cron.CronScheduler
import com.dc.andagent.cron.CronStore
import com.dc.andagent.cron.CronTask
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

class CronTool(private val context: Context, override val name: String) : Tool {
    override val description: String = when (name) {
        "cron_create" -> "建立持久化定时任务。schedule 为 5 段 cron 或 daily 09:00、weekly mon 09:00、every 30m；script 的 payload 是已保存脚本名，shell 是命令，notify 是通知正文。"
        "cron_list" -> "列出所有定时任务及上次/下次运行时间和结果摘要。"
        "cron_toggle" -> "暂停或恢复定时任务。"
        "cron_delete" -> "删除定时任务，须 confirm=true。"
        else -> "立即运行一次定时任务，方便测试。"
    }
    override val parameters = when (name) {
        "cron_create" -> schemaOf("name" to strParam("任务名称"), "schedule" to strParam("cron 或简写时间表"),
            "kind" to strParam("script/shell/notify"), "payload" to strParam("脚本名/命令/通知正文"),
            required = listOf("name", "schedule", "kind", "payload"))
        "cron_toggle" -> schemaOf("id" to strParam("任务 id"), "enabled" to boolParam("是否启用"), required = listOf("id", "enabled"))
        "cron_delete" -> schemaOf("id" to strParam("任务 id"), "confirm" to boolParam("确认删除"), required = listOf("id"))
        "cron_run_now" -> schemaOf("id" to strParam("任务 id"), required = listOf("id"))
        else -> schemaOf()
    }

    override suspend fun execute(args: JsonObject): String { return try {
        val store = CronStore(context)
        when (name) {
            "cron_create" -> {
                val title = args.strArg("name")?.trim() ?: return "ERROR: 缺少 name"
                val schedule = args.strArg("schedule")?.trim() ?: return "ERROR: 缺少 schedule"
                val kind = args.strArg("kind") ?: return "ERROR: 缺少 kind"
                val payload = args.strArg("payload") ?: return "ERROR: 缺少 payload"
                require(title.length <= 80 && payload.length <= 4000) { "名称或 payload 过长" }
                require(kind in setOf("script", "shell", "notify")) { "kind 只能为 script/shell/notify" }
                val next = CronSchedule.parse(schedule).next(System.currentTimeMillis())
                if (kind == "script") {
                    val root = File(requireNotNull(context.getExternalFilesDir(null)), "scripts")
                    require(ScriptFiles.resolve(root, payload).isFile) { "脚本不存在：$payload" }
                }
                val task = CronTask(name = title, schedule = schedule, kind = kind, payload = payload, nextRun = next)
                store.update { it.add(task) }
                CronScheduler.rebuild(context)
                buildJsonObject {
                    put("id", task.id); put("nextRun", next)
                    if (!CronScheduler.exactAllowed(context)) put("warning", "请在系统设置授予『闹钟和提醒』以获得精确分钟调度；当前使用非精确闹钟")
                }.toString()
            }
            "cron_list" -> JsonArray(store.list().map { it.json() }).toString()
            "cron_toggle" -> {
                val id = args.strArg("id") ?: return "ERROR: 缺少 id"
                val enabled = args.boolArg("enabled") ?: return "ERROR: 缺少 enabled"
                var found = false
                store.update { tasks ->
                    val index = tasks.indexOfFirst { it.id == id }
                    if (index >= 0) {
                        found = true
                        val current = tasks[index]
                        tasks[index] = current.copy(enabled = enabled, failures = if (enabled) 0 else current.failures,
                            nextRun = if (enabled) CronSchedule.parse(current.schedule).next(System.currentTimeMillis()) else null)
                    }
                }
                require(found) { "任务不存在：$id" }
                CronScheduler.rebuild(context)
                "OK"
            }
            "cron_delete" -> {
                val id = args.strArg("id") ?: return "ERROR: 缺少 id"
                if (args.boolArg("confirm") != true) return "ERROR: 删除任务需 confirm=true"
                var deleted = false
                store.update { deleted = it.removeAll { task -> task.id == id } }
                require(deleted) { "任务不存在：$id" }
                CronScheduler.rebuild(context)
                "OK"
            }
            "cron_run_now" -> {
                val id = args.strArg("id") ?: return "ERROR: 缺少 id"
                require(store.list().any { it.id == id }) { "任务不存在：$id" }
                CronScheduler.runNow(context, id)
                "已加入执行队列"
            }
            else -> "ERROR: 未知定时任务工具"
        }
    } catch (e: Exception) { "ERROR: ${e.message ?: e.javaClass.simpleName}" } }
}
