package com.dc.andagent.memory

import kotlinx.serialization.json.*
import java.util.UUID

/** Local, bounded memory. The injected read/write functions make updates atomic at the caller's store. */
class MemoryStore(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Entry(
        val id: String, val content: String, val category: String, val core: Boolean,
        val createdAt: Long, val updatedAt: Long, val lastAccessAt: Long, val hits: Int,
    )

    private val entries = mutableListOf<Entry>()
    private val changes = mutableListOf<JsonObject>()
    init {
        runCatching {
            val root = Json.parseToJsonElement(read() ?: "[]")
            val array = if (root is JsonArray) root else root.jsonObject.getValue("entries").jsonArray
            if (root is JsonObject) changes += root["history"]?.jsonArray?.map { it.jsonObject }.orEmpty()
            array.forEach { item ->
                val o = item.jsonObject
                entries += Entry(
                    o.getValue("id").jsonPrimitive.content, o.getValue("content").jsonPrimitive.content,
                    o.getValue("category").jsonPrimitive.content, o.getValue("core").jsonPrimitive.boolean,
                    o.getValue("createdAt").jsonPrimitive.long, o.getValue("updatedAt").jsonPrimitive.long,
                    o.getValue("lastAccessAt").jsonPrimitive.long, o.getValue("hits").jsonPrimitive.int,
                )
            }
        }
    }

    @Synchronized fun add(content: String, category: String = "fact", core: Boolean = false): Entry {
        validate(content, category, core)
        val clean = content.trim()
        val targetCore = core || category == "instruction"
        val duplicate = entries.firstOrNull { normalized(it.content) == normalized(clean) }
        if (duplicate != null) {
            if (targetCore && !duplicate.core) {
                if (entries.count { it.core } >= CORE_LIMIT) error("core memory 已满，请先删除或更新旧条目")
                validate(duplicate.content, category, true)
                require(corePromptSize(duplicate.content, category) <= PROMPT_LIMIT) { "core 总内容超过 3000 字符注入上限" }
            }
            val updated = duplicate.copy(category = if (targetCore && !duplicate.core) category else duplicate.category,
                core = duplicate.core || targetCore, hits = duplicate.hits + 1, lastAccessAt = now())
            replace(updated)
            return updated
        }
        if (targetCore && entries.count { it.core } >= CORE_LIMIT) error("core memory 已满，请先删除或更新旧条目")
        if (targetCore) require(corePromptSize(clean, category) <= PROMPT_LIMIT) { "core 总内容超过 3000 字符注入上限" }
        val time = now()
        val entry = Entry(UUID.randomUUID().toString(), clean, category, targetCore, time, time, time, 0)
        entries += entry
        if (!targetCore && entries.count { !it.core } > ARCHIVAL_LIMIT) {
            val victim = entries.filter { !it.core }.minWith(compareBy<Entry> { it.hits }.thenBy { it.lastAccessAt }.thenBy { it.createdAt })
            entries.remove(victim)
        }
        record("add", entry)
        persist()
        return entry
    }

    @Synchronized fun update(id: String, content: String): Entry {
        val old = entries.firstOrNull { it.id == id } ?: error("记忆不存在：$id")
        validate(content, old.category, old.core)
        require(entries.none { it.id != id && normalized(it.content) == normalized(content) }) { "重复记忆" }
        if (old.core) require(corePromptSize(content.trim(), old.category, old.id) <= PROMPT_LIMIT) { "core 总内容超过 3000 字符注入上限" }
        val updated = old.copy(content = content.trim(), updatedAt = now())
        record("update", old)
        replace(updated)
        return updated
    }

    @Synchronized fun delete(id: String): Boolean {
        val old = entries.firstOrNull { it.id == id }
        val removed = entries.removeAll { it.id == id }
        if (old != null) record("delete", old)
        if (removed) persist()
        return removed
    }

    @Synchronized fun deleteAll(category: String? = null): Int {
        if (category != null) require(category in CATEGORIES) { "无效分类" }
        val count = entries.count { category == null || it.category == category }
        entries.filter { category == null || it.category == category }.forEach { record("delete", it) }
        entries.removeAll { category == null || it.category == category }
        if (category == null) changes.clear()
        if (count > 0 || category == null) persist()
        return count
    }

    @Synchronized fun getAll(coreOnly: Boolean = false): List<Entry> =
        entries.filter { !coreOnly || it.core }.sortedWith(compareByDescending<Entry> { it.core }.thenByDescending { it.updatedAt })

    @Synchronized fun search(query: String, limit: Int = 10): List<Entry> {
        require(limit in 1..200) { "limit 需在 1..200" }
        val words = tokens(query)
        require(words.isNotEmpty()) { "query 不能为空" }
        val result = entries.mapNotNull { entry ->
            val text = normalized(entry.content)
            val matched = words.count { text.contains(it) }
            if (matched == 0) null else entry to (matched * 1000 + entry.hits.coerceAtMost(100) * 10 + (entry.updatedAt / 86_400_000L).coerceAtMost(100_000))
        }.sortedWith(compareByDescending<Pair<Entry, Long>> { it.second }.thenByDescending { it.first.updatedAt }).take(limit).map { it.first }
        if (result.isNotEmpty()) {
            val time = now()
            result.forEach { hit -> entries.indexOfFirst { it.id == hit.id }.takeIf { it >= 0 }?.let { index ->
                entries[index] = entries[index].copy(hits = entries[index].hits + 1, lastAccessAt = time)
            } }
            persist()
        }
        return result
    }

    @Synchronized fun prompt(query: String): String {
        val core = entries.filter { it.core }.sortedBy { it.createdAt }
        val archival = if (query.isBlank()) emptyList() else search(query, 20).filter { !it.core }.take(10)
        val lines = (core + archival).map { "[${it.category}] ${it.content}" }
        val header = "用户记忆（core 常驻，archival 按相关性检索）：\n"
        val out = StringBuilder(header)
        for (line in lines) if (out.length + line.length + 1 <= PROMPT_LIMIT) out.append(line).append('\n')
        return if (out.length == header.length) "" else out.toString().trimEnd()
    }

    @Synchronized fun stats(): Pair<Int, Int> = entries.count { it.core } to entries.count { !it.core }

    @Synchronized fun history(id: String? = null): List<JsonObject> = changes.filter { id == null || it["id"]?.jsonPrimitive?.content == id }.asReversed()

    private fun corePromptSize(content: String, category: String, excludingId: String? = null): Int =
        "用户记忆（core 常驻，archival 按相关性检索）：\n".length + entries.filter { it.core && it.id != excludingId }.sumOf { it.content.length + it.category.length + 4 } + content.length + category.length + 4

    private fun record(action: String, entry: Entry) {
        changes += buildJsonObject { put("action", action); put("id", entry.id); put("content", entry.content); put("at", now()) }
        while (changes.size > 500) changes.removeAt(0)
    }
    private fun replace(entry: Entry) { entries[entries.indexOfFirst { it.id == entry.id }] = entry; persist() }
    private fun persist() { write(buildJsonObject { put("entries", JsonArray(entries.map { e -> buildJsonObject {
        put("id", e.id); put("content", e.content); put("category", e.category); put("core", e.core)
        put("createdAt", e.createdAt); put("updatedAt", e.updatedAt); put("lastAccessAt", e.lastAccessAt); put("hits", e.hits)
    } })); put("history", JsonArray(changes)) }.toString()) }
    private fun validate(content: String, category: String, core: Boolean) {
        require(category in CATEGORIES) { "无效分类" }
        require(content.isNotBlank()) { "内容不能为空" }
        if (core || category == "instruction") require(content.trim().length <= CORE_CHARS) { "core 单条超过 500 字符" }
        else require(content.trim().toByteArray(Charsets.UTF_8).size <= ARCHIVAL_BYTES) { "archival 单条超过 2KB" }
    }
    companion object {
        const val CORE_LIMIT = 10
        const val ARCHIVAL_LIMIT = 200
        const val CORE_CHARS = 500
        const val ARCHIVAL_BYTES = 2048
        const val PROMPT_LIMIT = 3000
        val CATEGORIES = setOf("preference", "fact", "instruction")
        fun normalized(text: String) = text.trim().lowercase().replace(Regex("\\s+"), " ")
        fun tokens(text: String) = Regex("[\\p{IsHan}]+|[\\p{L}\\p{N}]+").findAll(normalized(text)).map { it.value }.distinct().toList()
    }
}
