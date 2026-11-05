package com.dc.andagent.memory

import com.dc.andagent.tools.*
import kotlinx.serialization.json.*

val MEMORY_TOOLS = listOf("memory_add", "memory_search", "memory_update", "memory_delete", "memory_delete_all", "memory_get_all", "memory_stats", "memory_history")

class MemoryTool(private val store: MemoryStore, override val name: String) : Tool {
    override val description: String = when (name) {
        "memory_add" -> "保存长期用户记忆。core memory 是你的长期人格记忆：用户偏好/重要指令写这里，满了先删除过期条目再写新的；instruction 默认进 core。"
        "memory_search" -> "按关键词检索长期记忆，返回相关条目。"
        "memory_update" -> "按 id 改写记忆；可自编辑 core memory。"
        "memory_delete" -> "按 id 删除记忆。"
        "memory_delete_all" -> "清空全部或指定分类记忆，必须 confirm=true。"
        "memory_get_all" -> "列出全部记忆，可只看 core。"
        "memory_history" -> "查看记忆增改删历史，可按 id 过滤。"
        else -> "查看 core/archival 记忆数量与容量。"
    }
    override val parameters: JsonObject = when (name) {
        "memory_add" -> schemaOf("content" to strParam("记忆内容"), "category" to strParam("preference/fact/instruction，默认 fact"), "core" to boolParam("写入 core，默认 false；instruction 自动进入 core"), required = listOf("content"))
        "memory_search" -> schemaOf("query" to strParam("检索词"), "limit" to intParam("返回条数，默认 10"), required = listOf("query"))
        "memory_update" -> schemaOf("id" to strParam("记忆 id"), "content" to strParam("新内容"), required = listOf("id", "content"))
        "memory_delete" -> schemaOf("id" to strParam("记忆 id"), required = listOf("id"))
        "memory_delete_all" -> schemaOf("category" to strParam("可选分类"), "confirm" to boolParam("必须 true"), required = listOf("confirm"))
        "memory_get_all" -> schemaOf("core_only" to boolParam("只返回 core"))
        "memory_history" -> schemaOf("id" to strParam("可选记忆 id"))
        else -> schemaOf()
    }
    override val destructive: Boolean = false
    override suspend fun execute(args: JsonObject): String = runCatching {
        when (name) {
            "memory_add" -> entryJson(store.add(args.strArg("content") ?: error("缺少 content"), args.strArg("category") ?: "fact", args.boolArg("core") == true)).toString()
            "memory_search" -> JsonArray(store.search(args.strArg("query") ?: error("缺少 query"), args.intArg("limit") ?: 10).map(::entryJson)).toString()
            "memory_update" -> entryJson(store.update(args.strArg("id") ?: error("缺少 id"), args.strArg("content") ?: error("缺少 content"))).toString()
            "memory_delete" -> buildJsonObject { put("deleted", store.delete(args.strArg("id") ?: error("缺少 id"))) }.toString()
            "memory_delete_all" -> {
                require(args.boolArg("confirm") == true) { "必须 confirm=true" }
                buildJsonObject { put("removed", store.deleteAll(args.strArg("category"))) }.toString()
            }
            "memory_get_all" -> JsonArray(store.getAll(args.boolArg("core_only") == true).map(::entryJson)).toString()
            "memory_history" -> JsonArray(store.history(args.strArg("id"))).toString()
            "memory_stats" -> {
                val (core, archival) = store.stats()
                buildJsonObject { put("core", core); put("coreLimit", MemoryStore.CORE_LIMIT); put("archival", archival); put("archivalLimit", MemoryStore.ARCHIVAL_LIMIT) }.toString()
            }
            else -> "ERROR: 未知记忆工具"
        }
    }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
}

fun entryJson(entry: MemoryStore.Entry): JsonObject = buildJsonObject {
    put("id", entry.id); put("content", entry.content); put("category", entry.category); put("core", entry.core)
    put("createdAt", entry.createdAt); put("updatedAt", entry.updatedAt); put("lastAccessAt", entry.lastAccessAt); put("hits", entry.hits)
}
