package io.nous.andagent.script

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import io.nous.andagent.tools.Tool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** A fresh 64 MB runtime per script, serialized on one HandlerThread. */
class QuickJsEngine(private val tools: Map<String, Tool>, private val toolExecutor: ExecutorService) {
    private val thread = HandlerThread("andagent-quickjs").apply { start() }
    private val handler = Handler(thread.looper)
    private val json = Json { ignoreUnknownKeys = true }

    private external fun installInterrupt(context: QuickJSContext, timeoutMs: Int): Boolean
    private external fun clearInterrupt(context: QuickJSContext)

    fun eval(script: String, timeoutSeconds: Int): String {
        if (script.length > 100_000) return "ERROR: 脚本超过 100KB"
        val seconds = timeoutSeconds.coerceIn(1, 30)
        val task = FutureTask(Callable { runScript(script, seconds) })
        if (!handler.post(task)) return "ERROR: QuickJS 线程不可用"
        return try {
            task.get(seconds + 32L, TimeUnit.SECONDS)
        } catch (e: Exception) {
            task.cancel(true)
            "ERROR: QuickJS 执行超时或失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun runScript(script: String, seconds: Int): String = try {
        QuickJSLoader.init()
        System.loadLibrary("scriptinterrupt")
        QuickJSContext.create().use { context ->
            context.setMemoryLimit(64 * 1024 * 1024)
            // wrapper's built-in console.js requires a Console backend —
            // without setConsole() any console.log throws "should be set a
            // stdout of platform". Route console output to logcat + andagent.log.
            context.setConsole(object : QuickJSContext.Console {
                override fun log(msg: String) { Log.i("QuickJsConsole", msg.take(1000)) }
                override fun info(msg: String) { Log.i("QuickJsConsole", msg.take(1000)) }
                override fun warn(msg: String) { Log.w("QuickJsConsole", msg.take(1000)) }
                override fun error(msg: String) { Log.e("QuickJsConsole", msg.take(1000)) }
            })
            if (!installInterrupt(context, seconds * 1000))
                return "ERROR: QuickJS 中断钩子加载失败，脚本未执行"
            try {
                val api = context.createNewJSObject()
                api.setProperty("tool", JSCallFunction { args ->
                    val name = args.getOrNull(0)?.toString().orEmpty()
                    val raw = args.getOrNull(1)?.toString() ?: "{}"
                    if (name == "js_eval") return@JSCallFunction "ERROR: 不允许递归执行 js_eval"
                    val tool = tools[name] ?: return@JSCallFunction "ERROR: 未知工具 $name"
                    val parsed: JsonObject = try { json.parseToJsonElement(raw).jsonObject }
                        catch (e: Exception) { return@JSCallFunction "ERROR: 工具参数不是 JSON 对象" }
                    val future = toolExecutor.submit<String> {
                        runCatching { runBlocking { tool.execute(parsed) } }
                            .getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
                    }
                    try { future.get(30, TimeUnit.SECONDS) }
                    catch (e: Exception) {
                        future.cancel(true)
                        "ERROR: 工具 $name 执行超时或失败：${e.message ?: e.javaClass.simpleName}"
                    }
                })
                for ((extension, key) in listOf("docx" to "content_spec_json", "pptx" to "slides_json", "xlsx" to "sheets_json")) {
                    api.setProperty(extension, JSCallFunction { args ->
                        val raw = args.getOrNull(0)?.toString() ?: return@JSCallFunction "ERROR: 缺少规格 JSON"
                        val filename = args.getOrNull(1)?.toString() ?: "andagent.$extension"
                        val tool = tools["${extension}_create"] ?: return@JSCallFunction "ERROR: 文档工具不可用"
                        val parsed = kotlinx.serialization.json.buildJsonObject { put("filename", filename); put(key, raw) }
                        val future = toolExecutor.submit<String> {
                            runCatching { runBlocking { tool.execute(parsed) } }.getOrElse { "ERROR: ${it.message}" }
                        }
                        try { future.get(30, TimeUnit.SECONDS) } catch (e: Exception) {
                            future.cancel(true)
                            "ERROR: 文档生成超时或失败：${e.message ?: e.javaClass.simpleName}"
                        }
                    })
                }
                api.setProperty("log", JSCallFunction { args ->
                    Log.i("QuickJsEngine", args.getOrNull(0)?.toString()?.take(1000) ?: "")
                    null
                })
                context.getGlobalObject().setProperty("andagent", api)
                context.evaluate("""
                    andagent.db = function(name) {
                      var call = function(tool, args) {
                        var result = andagent.tool(tool, JSON.stringify(args));
                        try { return JSON.parse(result); } catch (_) { return result; }
                      };
                      call('db_create', {name: name});
                      return {
                        exec: function(sql, params) { return call('db_exec', {name: name, sql: sql, params_json: JSON.stringify(params || [])}); },
                        query: function(sql, params, limit) { return call('db_query', {name: name, sql: sql, params_json: JSON.stringify(params || []), limit: limit || 100}); },
                        tables: function() { return call('db_tables', {name: name}); }
                      };
                    };
                """.trimIndent(), "andagent-db.js")
                // No Java Class object or reflection bridge is exposed to JS.
                val program = "JSON.stringify((0,eval)(${JsonPrimitive(script)}))"
                context.evaluate(program, "andagent-script.js")?.toString() ?: "null"
            } finally {
                clearInterrupt(context)
            }
        }
    } catch (e: Throwable) {
        val detail = if (e is ExecutionException) e.cause?.message else e.message
        "ERROR: QuickJS 加载或执行失败：${detail ?: e.javaClass.simpleName}"
    }

    fun shutdown() { thread.quitSafely() }
}
