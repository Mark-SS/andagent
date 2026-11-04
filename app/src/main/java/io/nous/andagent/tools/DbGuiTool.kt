package io.nous.andagent.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class DbGuiTool(private val open: (String) -> String) : Tool {
    override val name = "db_gui"
    override val description = "在对话中打开指定 SQLite 数据库的只读图形浏览器。先用 db_list 查库名。"
    override val parameters: JsonElement = schemaOf("name" to strParam("数据库名"), required = listOf("name"))
    override suspend fun execute(args: JsonObject): String = open(args.strArg("name") ?: "")
}
