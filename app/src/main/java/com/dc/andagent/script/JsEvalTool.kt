package com.dc.andagent.script

import com.dc.andagent.tools.Tool
import com.dc.andagent.tools.intParam
import com.dc.andagent.tools.schemaOf
import com.dc.andagent.tools.strArg
import com.dc.andagent.tools.intArg
import com.dc.andagent.tools.strParam
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class JsEvalTool(private val engine: QuickJsEngine) : Tool {
    override val name = "js_eval"
    override val description = "在隔离的 QuickJS 中运行 JS 脚本；andagent.tool(name, argsJson) 可同步调用设备工具；andagent.docx(specJson, filename)、andagent.pptx(specJson, filename)、andagent.xlsx(specJson, filename) 可生成文档。"
    override val parameters: JsonElement = schemaOf(
        "script" to strParam("JavaScript 脚本，最后表达式作为 JSON 结果"),
        "timeout_s" to intParam("超时秒数，1 到 30，默认 30"),
        required = listOf("script"),
    )

    override suspend fun execute(args: JsonObject): String {
        val script = args.strArg("script") ?: return "ERROR: 缺少 script"
        return engine.eval(script, args.intArg("timeout_s") ?: 30)
    }
}
