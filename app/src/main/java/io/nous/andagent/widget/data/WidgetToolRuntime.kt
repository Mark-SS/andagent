package io.nous.andagent.widget.data

import android.content.Context
import io.nous.andagent.script.QuickJsEngine
import io.nous.andagent.tools.BatteryDetailTool
import io.nous.andagent.tools.DeviceInfoTool
import io.nous.andagent.tools.StepsTodayTool
import io.nous.andagent.tools.Tool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.Executors

/** Foreground tool registry is shared with widget scripts; background workers can always use device facts. */
object WidgetToolRuntime {
    @Volatile private var foreground: Map<String, Tool>? = null
    @Volatile private var foregroundEngine: QuickJsEngine? = null
    private val backgroundTools = Executors.newFixedThreadPool(2)
    @Volatile private var backgroundEngine: QuickJsEngine? = null

    fun attach(tools: Map<String, Tool>, engine: QuickJsEngine) {
        foreground = tools
        foregroundEngine = engine
    }

    fun detach(engine: QuickJsEngine) {
        if (foregroundEngine === engine) {
            foregroundEngine = null
            foreground = null
        }
    }

    fun eval(context: Context, script: String, timeoutMs: Int): String {
        val engine = foregroundEngine ?: synchronized(this) {
            backgroundEngine ?: QuickJsEngine(backgroundMap(context.applicationContext), backgroundTools).also { backgroundEngine = it }
        }
        return engine.eval(script, ((timeoutMs + 999) / 1000).coerceIn(1, 5))
    }

    fun tool(context: Context, name: String): String {
        val map = foreground ?: backgroundMap(context.applicationContext)
        val tool = map[name] ?: error("后台不可用的工具：$name；请打开 AndAgent 后刷新")
        return runBlocking { tool.execute(JsonObject(emptyMap())) }.also {
            require(!it.startsWith("ERROR:")) { it }
        }
    }

    private fun backgroundMap(context: Context): Map<String, Tool> = mapOf(
        "device_info" to DeviceInfoTool(context),
        "battery_detail" to BatteryDetailTool(context),
        "steps_today" to StepsTodayTool(context),
    )
}
