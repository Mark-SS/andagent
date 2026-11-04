package io.nous.andagent.tools

import io.nous.andagent.shell.ShellEngine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * M2 toolset: shell execution + device introspection.
 * Album/storage/sensor tools come in M3; destructive ops gated by [destructive].
 */

class ShellTool(private val engine: ShellEngine) : Tool {
    override val name = "shell"
    override val description = "Run a shell command on the device (toybox: ls, find, du, df, grep, cat, cp, mv, rm...). " +
        "Working dir is the app's private storage. Long output is truncated. Timeout 15s."
    override val parameters = schemaOf(
        "command" to strParam("The shell command line to execute"),
        required = listOf("command"),
    )
    override val destructive = true // shell can do anything; confirmation policy handled by SafetyGate

    override suspend fun execute(args: JsonObject): String {
        val cmd = args["command"]?.jsonPrimitive?.contentOrNullCompat()
            ?: throw IllegalArgumentException("missing 'command'")
        return engine.exec(cmd).asToolResult()
    }

    private fun JsonPrimitive.contentOrNullCompat(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content
}

class DeviceInfoTool(private val context: android.content.Context) : Tool {
    override val name = "device_info"
    override val description = "Get device info: model, Android version, storage usage (internal free/total), battery, memory."
    override val parameters = jsonSchema()

    override suspend fun execute(args: JsonObject): String {
        val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        val bytesToGb = { b: Long -> "%.1f GB".format(b / 1e9) }
        val total = stat.totalBytes
        val free = stat.availableBytes
        val actMgr = context.getSystemService(android.app.ActivityManager::class.java)
        val mem = android.app.ActivityManager.MemoryInfo()
        actMgr.getMemoryInfo(mem)
        return buildString {
            append("model=${android.os.Build.MODEL}\n")
            append("android=${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})\n")
            append("storage: total=${bytesToGb(total)} free=${bytesToGb(free)}\n")
            append("mem: total=${bytesToGb(mem.totalMem)} available=${bytesToGb(mem.availMem)} lowMemory=${mem.lowMemory}\n")
            append("battery=${batteryLevel(context)}%\n")
        }
    }

    private fun batteryLevel(ctx: android.content.Context): Int {
        val intent = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra("level", -1) ?: -1
        val scale = intent?.getIntExtra("scale", 100) ?: 100
        return if (level >= 0) level * 100 / scale else -1
    }
}
