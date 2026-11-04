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

/**
 * Tool registry shared by foreground scripts and background widget refreshes.
 *
 * #11: the whole registry is now available to SCRIPT data sources. Tools that
 * need an Activity/UI (foreground dispatch, dialogs, SAF pickers, external
 * intents) are excluded in the background: they cannot work from a WorkManager
 * worker anyway. Tools whose runtime permission is not already granted return
 * their usual "ERROR: 权限未授予" string instead of prompting the user.
 *
 * After the app process is killed, only the three permission-free device facts
 * can be rebuilt from an application Context ([backgroundMap]); opening the app
 * once restores the full registry.
 */
object WidgetToolRuntime {
    @Volatile private var foreground: Map<String, Tool>? = null
    @Volatile private var foregroundEngine: QuickJsEngine? = null
    @Volatile private var cachedTools: Map<String, Tool>? = null
    private val backgroundTools = Executors.newFixedThreadPool(2)
    @Volatile private var backgroundEngine: QuickJsEngine? = null

    private val UI_ONLY = setOf(
        "web_embed", "db_gui", "file_pick", "saf_tree_pick", "file_share", "email_draft",
        "open_uri", "open_settings", "open_folder", "album_open", "tts", "screenshot_quick",
        "screen_capture", "screen_record_h264", "widget_generate", "widget_save_and_pin",
        "js_eval", "script_run", "script_save", "script_check", "script_delete",
        "nfc_hce_status", "email_verify", "app_install", "hotspot",
    )

    private val UI_PREFIXES = listOf("nfc_", "screen_", "input_")

    private fun isBackgroundSafe(name: String): Boolean =
        name !in UI_ONLY && UI_PREFIXES.none { name.startsWith(it) }

    fun attach(tools: Map<String, Tool>, engine: QuickJsEngine) {
        foreground = tools
        foregroundEngine = engine
        cachedTools = tools.filterKeys(::isBackgroundSafe)
        synchronized(this) {
            // Rebuild the background engine so it sees the freshly registered
            // tools on its next eval instead of a stale snapshot.
            backgroundEngine?.shutdown()
            backgroundEngine = null
        }
    }

    fun detach(engine: QuickJsEngine) {
        if (foregroundEngine === engine) {
            foregroundEngine = null
            foreground = null
            // cachedTools intentionally survives: widget workers still run.
        }
    }

    fun eval(context: Context, script: String, timeoutMs: Int): String {
        val engine = foregroundEngine ?: synchronized(this) {
            backgroundEngine ?: QuickJsEngine(
                cachedTools ?: backgroundMap(context.applicationContext),
                backgroundTools,
                context.applicationContext,
            ).also { backgroundEngine = it }
        }
        return engine.eval(script, ((timeoutMs + 999) / 1000).coerceIn(1, 5))
    }

    fun tool(context: Context, name: String): String {
        require(isBackgroundSafe(name)) { "后台不可用的工具：$name；请打开 AndAgent 后刷新" }
        val map = foreground?.filterKeys(::isBackgroundSafe)
            ?: cachedTools
            ?: backgroundMap(context.applicationContext)
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
