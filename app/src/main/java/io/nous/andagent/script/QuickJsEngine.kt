package io.nous.andagent.script

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import io.nous.andagent.tools.Tool
import io.nous.andagent.tools.JsonFixer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** A fresh 64 MB runtime per script, serialized on one HandlerThread. */
class QuickJsEngine(
    private val tools: Map<String, Tool>,
    private val toolExecutor: ExecutorService,
    private val appContext: Context? = null,
) {
    private val thread = HandlerThread("andagent-quickjs").apply { start() }
    private val handler = Handler(thread.looper)

    companion object {
        fun sourceWithArgs(script: String, injectArgs: JsonObject?): String =
            if (injectArgs == null) script else "var args = ${injectArgs};\n$script"
    }

    private external fun installInterrupt(context: QuickJSContext, timeoutMs: Int): Boolean
    private external fun clearInterrupt(context: QuickJSContext)

    fun eval(script: String, timeoutSeconds: Int, injectArgs: JsonObject? = null): String {
        val source = sourceWithArgs(script, injectArgs)
        if (source.length > 100_000) return "ERROR: 脚本超过 100KB"
        val seconds = timeoutSeconds.coerceIn(1, 30)
        val task = FutureTask(Callable { runScript(source, seconds) })
        if (!handler.post(task)) return "ERROR: QuickJS 线程不可用"
        return try {
            task.get(seconds + 32L, TimeUnit.SECONDS)
        } catch (e: Exception) {
            task.cancel(true)
            "ERROR: QuickJS 执行超时或失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun check(script: String): String {
        if (script.length > 100_000) return "{\"valid\":false,\"error\":\"script exceeds 100KB\"}"
        val task = FutureTask(Callable { checkScript(script) })
        if (!handler.post(task)) return "{\"valid\":false,\"error\":\"QuickJS thread unavailable\"}"
        return try { task.get(32, TimeUnit.SECONDS) }
        catch (e: Exception) { task.cancel(true); "{\"valid\":false,\"error\":${JsonPrimitive(e.message ?: "syntax check failed")}}" }
    }

    private fun checkScript(script: String): String = try {
        QuickJSLoader.init()
        System.loadLibrary("scriptinterrupt")
        QuickJSContext.create().use { context ->
            context.setMemoryLimit(64 * 1024 * 1024)
            if (!installInterrupt(context, 30000)) return "{\"valid\":false,\"error\":\"interrupt hook unavailable\"}"
            try {
                val probe = "try { new Function(${JsonPrimitive(script)}); JSON.stringify({valid:true}) } catch(e) { JSON.stringify({valid:false,error:String(e)}) }"
                context.evaluate(probe, "andagent-check.js")?.toString() ?: "{\"valid\":false,\"error\":\"empty check result\"}"
            } finally { clearInterrupt(context) }
        }
    } catch (e: Throwable) {
        "{\"valid\":false,\"error\":${JsonPrimitive(e.message ?: e.javaClass.simpleName)}}"
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
                    if (name in setOf("js_eval", "script_run", "script_save", "script_check")) return@JSCallFunction "ERROR: 不允许递归执行 $name"
                    val tool = tools[name] ?: return@JSCallFunction "ERROR: 未知工具 $name"
                    val parsed: JsonObject = try { JsonFixer.parseObject(raw) }
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
                        val repaired = try { JsonFixer.fix(raw).also { if (!it.valid) error(it.error ?: "invalid JSON") }.json }
                            catch (e: Exception) { return@JSCallFunction "ERROR: 规格 JSON 无法修复：${e.message}" }
                        val parsed = kotlinx.serialization.json.buildJsonObject { put("filename", filename); put(key, repaired) }
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
                // R10.7: sandboxed script-owned storage under scripts/ and data/.
                val fs = context.createNewJSObject()
                fs.setProperty("read", JSCallFunction { args -> fsRead(args.getOrNull(0)?.toString()) })
                fs.setProperty("write", JSCallFunction { args -> fsWrite(args.getOrNull(0)?.toString(), args.getOrNull(1)?.toString()) })
                fs.setProperty("list", JSCallFunction { args -> fsList(args.getOrNull(0)?.toString()) })
                fs.setProperty("_exists", JSCallFunction { args -> if (fsExists(args.getOrNull(0)?.toString())) "true" else "false" })
                api.setProperty("fs", fs)
                context.getGlobalObject().setProperty("andagent", api)
                context.evaluate("""
                    andagent.fs.exists = function(p) { return andagent.fs._exists(p) === 'true'; };
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

    /* ------------------------ R10.7 sandboxed fs ------------------------ */

    private fun fsResolve(raw: String?): File {
        val base = appContext?.getExternalFilesDir(null)?.canonicalFile ?: error("脚本文件系统不可用")
        val parts = (raw ?: error("缺少路径")).trim().replace('\\', '/').split('/').filter { it.isNotEmpty() }
        require(parts.isNotEmpty() && parts[0] in setOf("scripts", "data")) {
            "路径必须以 scripts/ 或 data/ 开头"
        }
        require(parts.none { it == ".." || it == "." }) { "路径越界" }
        val target = File(base, parts.joinToString("/")).canonicalFile
        require(target.path.startsWith(base.path + File.separator)) { "路径越界" }
        return target
    }

    private fun fsRead(raw: String?): String = runCatching {
        val file = fsResolve(raw)
        when {
            !file.isFile -> "ERROR: 文件不存在"
            file.length() > 100 * 1024 -> "ERROR: 文件超过 100KB"
            else -> file.readText(Charsets.UTF_8)
        }
    }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }

    private fun fsWrite(raw: String?, content: String?): String = runCatching {
        val file = fsResolve(raw)
        val text = content ?: error("缺少写入内容")
        require(text.length <= 100_000) { "内容超过 100KB" }
        require(!file.isDirectory) { "目标路径是目录" }
        file.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("无法创建目录") }
        file.writeText(text, Charsets.UTF_8)
        "OK"
    }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }

    private fun fsList(raw: String?): String = runCatching {
        val dir = fsResolve(raw ?: "data")
        if (!dir.exists()) return "[]"
        require(dir.isDirectory) { "不是目录" }
        val items = dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.map { file ->
            buildJsonObject {
                put("name", file.name)
                put("size", file.length())
                put("modified", file.lastModified())
            }
        }
        JsonArray(items).toString()
    }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }

    private fun fsExists(raw: String?): Boolean = runCatching { fsResolve(raw).exists() }.getOrDefault(false)

    fun shutdown() { thread.quitSafely() }
}
