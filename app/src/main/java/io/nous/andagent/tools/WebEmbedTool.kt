package io.nous.andagent.tools

import android.net.Uri
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class WebEmbedTool : Tool {
    override val name = "web_embed"
    override val description = "在对话卡片中提供内嵌浏览器按钮，仅接受 HTTPS 网页。"
    override val parameters = schemaOf("uri" to strParam("HTTPS 网页地址"), required = listOf("uri"))
    override suspend fun execute(args: JsonObject): String {
        val uri = args.strArg("uri") ?: return "ERROR: 缺少 uri"
        val parsed = Uri.parse(uri)
        if (parsed.scheme != "https" || parsed.host.isNullOrBlank()) return "ERROR: 内嵌浏览器仅允许 HTTPS 网页"
        return buildJsonObject { put("type", "webview"); put("uri", uri) }.toString()
    }
}
