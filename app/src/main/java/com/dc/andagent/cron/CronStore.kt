package com.dc.andagent.cron

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

data class CronTask(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val schedule: String,
    val kind: String,
    val payload: String,
    val enabled: Boolean = true,
    val lastRun: Long? = null,
    val nextRun: Long? = null,
    val lastResult: String = "",
    val failures: Int = 0,
) {
    fun json(): JsonObject = buildJsonObject {
        put("id", id); put("name", name); put("schedule", schedule); put("kind", kind)
        put("payload", payload); put("enabled", enabled)
        lastRun?.let { put("lastRun", it) }; nextRun?.let { put("nextRun", it) }
        put("lastResult", lastResult.take(200)); put("failures", failures)
    }
}

/** Atomic JSON file shared by tools, the alarm receiver and WorkManager. */
class CronStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "cron.json"))

    fun list(): List<CronTask> = synchronized(lock) { read() }

    fun update(block: (MutableList<CronTask>) -> Unit): List<CronTask> = synchronized(lock) {
        val tasks = read().toMutableList()
        block(tasks)
        val stream = file.startWrite()
        try {
            stream.write(JsonArray(tasks.map { it.json() }).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
        tasks.toList()
    }

    private fun read(): List<CronTask> {
        if (!file.baseFile.exists()) return emptyList()
        return Json.parseToJsonElement(file.openRead().bufferedReader().use { it.readText() })
            .let { it as JsonArray }.map { item ->
                val o = item as JsonObject
                fun string(key: String) = o[key]?.jsonPrimitive?.content.orEmpty()
                fun long(key: String) = o[key]?.jsonPrimitive?.content?.toLongOrNull()
                CronTask(string("id"), string("name"), string("schedule"), string("kind"), string("payload"),
                    string("enabled") != "false", long("lastRun"), long("nextRun"), string("lastResult"),
                    string("failures").toIntOrNull() ?: 0)
            }
    }

    companion object { private val lock = Any() }
}
