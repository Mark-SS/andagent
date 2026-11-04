package io.nous.andagent.tools

import android.content.Context
import io.nous.andagent.script.QuickJsEngine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant

class ScriptFsTool(private val context: Context, private val engine: QuickJsEngine, override val name: String) : Tool {
    override val description = when (name) {
        "script_save" -> "保存 UTF-8 JS 文件到脚本库；覆盖现有脚本须 confirm=true，保存前检查语法。"
        "script_list" -> "列出脚本库里的 JS 文件及描述、大小、修改时间。"
        "script_read" -> "读取脚本库里的 JS 源码，最多 100KB。修改前先读。"
        "script_run" -> "从脚本库加载并执行 JS 文件，args_json 会成为全局 args 对象。"
        "script_delete" -> "删除脚本库里的 JS 文件，须 confirm=true。"
        "script_check" -> "只编译检查 JS 语法，不执行源码。"
        else -> "尝试修复不规范 JSON，并验证结果。"
    }
    override val parameters: JsonElement = when (name) {
        "script_save" -> schemaOf("name" to strParam("脚本名"), "code" to strParam("JS 源码"), "description" to strParam("描述"), "confirm" to boolParam("覆盖已有文件"), required = listOf("name", "code"))
        "script_read", "script_delete" -> schemaOf("name" to strParam("脚本名"), "confirm" to boolParam("确认删除"), required = listOf("name"))
        "script_run" -> schemaOf("name" to strParam("脚本名"), "args_json" to strParam("JSON 对象参数"), "timeout_s" to intParam("超时秒数，1 到 30"), required = listOf("name"))
        "script_check" -> schemaOf("code" to strParam("JS 源码"), required = listOf("code"))
        "json_fix" -> schemaOf("text" to strParam("待修复 JSON"), required = listOf("text"))
        else -> schemaOf()
    }

    private fun root(): File {
        val external = requireNotNull(context.getExternalFilesDir(null)).canonicalFile
        val directory = File(external, "scripts").canonicalFile
        require(directory.parentFile == external) { "脚本目录越界" }
        return directory
    }

    private fun file(raw: String): File = ScriptFiles.resolve(root(), raw)

    override suspend fun execute(args: JsonObject): String {
        return try {
        when (name) {
            "script_check" -> engine.check(args.strArg("code") ?: return "ERROR: 缺少 code")
            "json_fix" -> {
                val result = JsonFixer.fix(args.strArg("text") ?: return "ERROR: 缺少 text")
                buildJsonObject {
                    put("fixed", result.valid)
                    put("json", result.json)
                    put("changes", kotlinx.serialization.json.JsonArray(result.changes.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                    if (result.error != null) put("error", result.error)
                }.toString()
            }
            "script_list" -> {
                val dir = root()
                val files = dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".js") && it.canonicalFile.parentFile == dir }
                kotlinx.serialization.json.JsonArray(files.sortedBy { it.name }.map { f ->
                    val description = f.useLines(Charsets.UTF_8) { lines -> lines.take(3).firstOrNull { it.startsWith("// description: ") }?.removePrefix("// description: ").orEmpty() }
                    buildJsonObject { put("name", f.name); put("size", f.length()); put("modified", Instant.ofEpochMilli(f.lastModified()).toString()); put("description", description) }
                }).toString()
            }
            else -> {
                val f = file(args.strArg("name") ?: return "ERROR: 缺少 name")
                when (name) {
                    "script_save" -> {
                        val code = args.strArg("code") ?: return "ERROR: 缺少 code"
                        if (f.exists() && args.boolArg("confirm") != true) return "ERROR: 脚本已存在，覆盖需 confirm=true"
                        val check = engine.check(code)
                        if (!check.contains("\"valid\":true")) return "ERROR: JS 语法检查失败：$check"
                        val description = args.strArg("description").orEmpty().replace(Regex("[\\r\\n]+"), " ").take(300)
                        val header = "// name: ${f.name}\n// description: $description\n// saved: ${Instant.now()}\n"
                        val bytes = (header + code).toByteArray(Charsets.UTF_8)
                        require(bytes.size <= 100_000) { "脚本超过 100KB" }
                        require(root().mkdirs() || root().isDirectory) { "无法创建脚本目录" }
                        f.writeBytes(bytes)
                        buildJsonObject { put("path", f.absolutePath); put("bytes", bytes.size) }.toString()
                    }
                    "script_read" -> {
                        if (!f.exists()) return "ERROR: 脚本不存在：${f.name}"
                        if (f.length() > 100_000) return "ERROR: 脚本超过 100KB"
                        f.readText(Charsets.UTF_8)
                    }
                    "script_run" -> {
                        if (!f.exists()) return "ERROR: 脚本不存在：${f.name}"
                        if (f.length() > 100_000) return "ERROR: 脚本超过 100KB"
                        val raw = args.strArg("args_json") ?: "{}"
                        val inject = JsonFixer.parseObject(raw)
                        engine.eval(f.readText(Charsets.UTF_8), args.intArg("timeout_s") ?: 30, inject)
                    }
                    "script_delete" -> {
                        if (!f.exists()) return "ERROR: 脚本不存在：${f.name}"
                        if (args.boolArg("confirm") != true) return "ERROR: 删除脚本需 confirm=true"
                        if (!f.delete()) return "ERROR: 删除脚本失败：${f.name}"
                        buildJsonObject { put("deleted", f.name) }.toString()
                    }
                    else -> "ERROR: 未知脚本工具"
                }
            }
        }
    } catch (e: Exception) { "ERROR: ${e.message ?: e.javaClass.simpleName}" }
    }
}
