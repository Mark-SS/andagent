package com.dc.andagent.tools

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Six KV tools backed by [KvStore]. */
val KV_TOOLS = listOf("kv_get", "kv_set", "kv_delete", "kv_keys", "kv_namespaces", "kv_clear")

class KvTool(private val context: Context, override val name: String) : Tool {
    private val store = KvStore(context)

    override val description: String = when (name) {
        "kv_get" -> "读取 KV 键值（namespace 默认 default），返回值与过期时间；不存在时返回 found=false。"
        "kv_set" -> "写入 KV 键值。value_json 为任意 JSON 值字符串；ttl_ms 为可选过期毫秒数；items_json 可传对象批量写入。"
        "kv_delete" -> "删除 KV 键；键不存在时返回错误。"
        "kv_keys" -> "列出命名空间下的键名及剩余 TTL 秒数，可选 prefix 前缀过滤。"
        "kv_namespaces" -> "列出所有 KV 命名空间及其键数量。"
        "kv_clear" -> "清空整个命名空间的所有键，必须 confirm=true。"
        else -> "KV 存储工具"
    }

    override val parameters: JsonObject = when (name) {
        "kv_get" -> schemaOf(
            "namespace" to strParam("命名空间，默认 default"),
            "key" to strParam("键名，仅字母数字._-，最长 64"),
            required = listOf("key"),
        )
        "kv_set" -> schemaOf(
            "namespace" to strParam("命名空间，默认 default"),
            "key" to strParam("键名，仅字母数字._-，最长 64"),
            "value_json" to strParam("任意 JSON 值字符串，如 \"text\"、42、true、{\"a\":1}、[1,2]"),
            "ttl_ms" to intParam("可选：过期毫秒数，正整数；省略表示永久"),
            "items_json" to strParam("可选：{\"key\":值} JSON 对象，用于批量写入；与 key/value_json 二选一"),
            required = emptyList(),
        )
        "kv_delete" -> schemaOf(
            "namespace" to strParam("命名空间，默认 default"),
            "key" to strParam("键名"),
            required = listOf("key"),
        )
        "kv_keys" -> schemaOf(
            "namespace" to strParam("命名空间，默认 default"),
            "prefix" to strParam("可选：键名前缀过滤"),
            required = emptyList(),
        )
        "kv_namespaces" -> schemaOf()
        "kv_clear" -> schemaOf(
            "namespace" to strParam("要清空的命名空间"),
            "confirm" to boolParam("必须设为 true 才会清空"),
            required = listOf("namespace"),
        )
        else -> schemaOf()
    }

    override val destructive: Boolean = name == "kv_clear"

    override suspend fun execute(args: JsonObject): String = runCatching {
        when (name) {
            "kv_get" -> get(args)
            "kv_set" -> set(args)
            "kv_delete" -> delete(args)
            "kv_keys" -> keys(args)
            "kv_namespaces" -> namespaces()
            "kv_clear" -> clear(args)
            else -> "ERROR: 未知 KV 工具 $name"
        }
    }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }

    private fun get(args: JsonObject): String {
        val namespace = KvStore.namespaceOrDefault(args.strArg("namespace"))
        val key = args.strArg("key") ?: return "ERROR: 缺少 key"
        val entry = store.read(namespace, key)
            ?: return buildJsonObject {
                put("found", false)
                put("namespace", namespace)
                put("key", key)
                put("message", "键不存在或已过期")
            }.toString()
        return buildJsonObject {
            put("found", true)
            put("namespace", namespace)
            put("key", key)
            put("value", entry.value)
            put("expiresAt", entry.expiresAt?.let { JsonPrimitive(it) } ?: JsonNull)
        }.toString()
    }

    private fun set(args: JsonObject): String {
        val namespace = KvStore.namespaceOrDefault(args.strArg("namespace"))
        val ttlRaw = args["ttl_ms"]?.let { (it as? JsonPrimitive)?.content }
        val ttlMs = ttlRaw?.let {
            it.toLongOrNull() ?: return "ERROR: ttl_ms 必须是整数毫秒"
        }
        KvStore.ttlError(ttlMs)?.let { return "ERROR: $it" }

        val itemsRaw = args.strArg("items_json")
        val key = args.strArg("key")
        val valueRaw = args.strArg("value_json")
        if (itemsRaw != null) {
            if (key != null || valueRaw != null) return "ERROR: items_json 与 key/value_json 不能同时使用"
            val items = runCatching { Json.parseToJsonElement(itemsRaw) as? JsonObject }
                .getOrNull() ?: return "ERROR: items_json 必须是 JSON 对象"
            if (items.isEmpty()) return "ERROR: items_json 不能为空对象"
            val expiry = store.setAll(namespace, items, ttlMs)
            return buildJsonObject {
                put("ok", true)
                put("namespace", namespace)
                put("count", items.size)
                put("expiresAt", expiry?.let { JsonPrimitive(it) } ?: JsonNull)
            }.toString()
        }
        if (key == null) return "ERROR: 缺少 key"
        if (valueRaw == null) return "ERROR: 缺少 value_json"
        val value = runCatching { Json.parseToJsonElement(valueRaw) }
            .getOrElse { return "ERROR: value_json 不是合法 JSON：${it.message ?: "解析失败"}" }
        val expiry = store.set(namespace, key, value, ttlMs)
        return buildJsonObject {
            put("ok", true)
            put("namespace", namespace)
            put("key", key)
            put("expiresAt", expiry?.let { JsonPrimitive(it) } ?: JsonNull)
        }.toString()
    }

    private fun delete(args: JsonObject): String {
        val namespace = KvStore.namespaceOrDefault(args.strArg("namespace"))
        val key = args.strArg("key") ?: return "ERROR: 缺少 key"
        if (!store.delete(namespace, key)) return "ERROR: 键不存在：$namespace:$key"
        return buildJsonObject {
            put("deleted", true)
            put("namespace", namespace)
            put("key", key)
        }.toString()
    }

    private fun keys(args: JsonObject): String {
        val namespace = KvStore.namespaceOrDefault(args.strArg("namespace"))
        val prefix = args.strArg("prefix")
        val items = store.keys(namespace, prefix)
        return buildJsonObject {
            put("namespace", namespace)
            put("count", items.size)
            put("keys", buildJsonArray {
                for (item in items) add(buildJsonObject {
                    put("key", item.key)
                    put("expiresAt", item.expiresAt?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("ttlRemainingSeconds", item.ttlRemainingSeconds?.let { JsonPrimitive(it) } ?: JsonNull)
                })
            })
        }.toString()
    }

    private fun namespaces(): String {
        val items = store.namespaces()
        return buildJsonObject {
            put("count", items.size)
            put("namespaces", buildJsonArray {
                for (item in items) add(buildJsonObject {
                    put("namespace", item.namespace)
                    put("keys", item.keys)
                })
            })
        }.toString()
    }

    private fun clear(args: JsonObject): String {
        KvStore.clearConfirmError(args.boolArg("confirm"))?.let { return it }
        val namespace = args.strArg("namespace") ?: return "ERROR: 缺少 namespace"
        val removed = store.clear(namespace)
        return buildJsonObject {
            put("cleared", true)
            put("namespace", namespace)
            put("removed", removed)
        }.toString()
    }
}
