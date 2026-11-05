package com.dc.andagent.tools

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * App-private key/value store shared by the six kv_* tools and `andagent.kv`.
 *
 * Values are stored as JSON envelopes: `{"v":<json>,"exp":<epochMs|null>}`.
 * The Android-backed part is intentionally thin; all validation, TTL and
 * JSON wrapping logic lives in the companion object so it can be unit-tested
 * on a plain JVM without Robolectric.
 */
class KvStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Entry(val value: JsonElement, val expiresAt: Long?)

    data class KeyInfo(val key: String, val expiresAt: Long?, val ttlRemainingSeconds: Long?)

    data class NamespaceInfo(val namespace: String, val keys: Int)

    data class ImportResult(val namespaces: Int, val imported: Int, val skipped: Int)

    /** One exported/imported key with its absolute expiry (null = never). */
    data class BackupRecord(
        val namespace: String,
        val key: String,
        val value: JsonElement,
        val expiresAt: Long?,
    )

    /** Validated import plan; built entirely before any SharedPreferences write. */
    data class ImportPlan(
        val writes: Map<String, Map<String, BackupRecord>>,
        val clearedNamespaces: Set<String>,
        val namespaces: Int,
        val imported: Int,
        val skipped: Int,
    )

    /** Reads an entry, lazily deleting it when its TTL has elapsed. */
    @Synchronized
    fun read(namespace: String?, key: String, now: Long = System.currentTimeMillis()): Entry? {
        val ns = namespaceOrDefault(namespace)
        requireValidName(ns, "namespace")
        requireValidName(key, "key")
        val storedKey = encode(ns, key)
        val raw = prefs.getString(storedKey, null) ?: return null
        val expiresAt = expiryOf(raw)
        if (isExpired(expiresAt, now)) {
            prefs.edit().remove(storedKey).apply()
            return null
        }
        val value = unwrap(raw) ?: return null
        return Entry(value, expiresAt)
    }

    fun get(namespace: String?, key: String, now: Long = System.currentTimeMillis()): JsonElement? =
        read(namespace, key, now)?.value

    /** Writes one key and returns its absolute expiry, or null when it never expires. */
    @Synchronized
    fun set(namespace: String?, key: String, value: JsonElement, ttlMs: Long? = null, now: Long = System.currentTimeMillis()): Long? {
        return setAll(namespace, mapOf(key to value), ttlMs, now)
    }

    /**
     * Writes several keys in one batch. Returns the absolute expiry shared by
     * the batch, or null when they never expire.
     */
    @Synchronized
    fun setAll(namespace: String?, values: Map<String, JsonElement>, ttlMs: Long? = null, now: Long = System.currentTimeMillis()): Long? {
        val ns = namespaceOrDefault(namespace)
        requireValidName(ns, "namespace")
        require(values.isNotEmpty()) { "批量写入至少需要一个键" }
        ttlError(ttlMs)?.let { throw IllegalArgumentException(it) }
        for ((key, value) in values) {
            requireValidName(key, "key")
            val json = value.toString()
            if (exceedsValueLimit(json)) throw IllegalArgumentException("值超过 64KB 上限（$key）")
        }
        ensureCapacity(ns, values.keys)
        val expiry = expiryFrom(ttlMs, now)
        val editor = prefs.edit()
        for ((key, value) in values) {
            editor.putString(encode(ns, key), wrap(value, expiry))
        }
        editor.apply()
        return expiry
    }

    /** Returns true when the key existed and was removed. */
    @Synchronized
    fun delete(namespace: String?, key: String): Boolean {
        val ns = namespaceOrDefault(namespace)
        requireValidName(ns, "namespace")
        requireValidName(key, "key")
        val storedKey = encode(ns, key)
        if (!prefs.contains(storedKey)) return false
        prefs.edit().remove(storedKey).apply()
        return true
    }

    /** Lists live keys in a namespace, optionally filtered by prefix, with remaining TTL. */
    @Synchronized
    fun keys(namespace: String?, prefix: String? = null, now: Long = System.currentTimeMillis()): List<KeyInfo> {
        val ns = namespaceOrDefault(namespace)
        requireValidName(ns, "namespace")
        val needle = prefix?.takeIf { it.isNotBlank() }
        val result = mutableListOf<KeyInfo>()
        val expired = mutableListOf<String>()
        for ((stored, raw) in prefs.all) {
            val decoded = decode(stored) ?: continue
            if (decoded.first != ns) continue
            if (needle != null && !decoded.second.startsWith(needle)) continue
            val expiresAt = (raw as? String)?.let(::expiryOf)
            if (isExpired(expiresAt, now)) {
                expired += stored
                continue
            }
            result += KeyInfo(decoded.second, expiresAt, ttlRemainingSeconds(expiresAt, now))
        }
        if (expired.isNotEmpty()) {
            prefs.edit().apply { expired.forEach { remove(it) } }.apply()
        }
        return result.sortedBy { it.key }
    }

    /** Lists namespaces with live key counts, lazily pruning expired entries. */
    @Synchronized
    fun namespaces(now: Long = System.currentTimeMillis()): List<NamespaceInfo> {
        val counts = linkedMapOf<String, Int>()
        val expired = mutableListOf<String>()
        for ((stored, raw) in prefs.all) {
            val decoded = decode(stored) ?: continue
            val expiresAt = (raw as? String)?.let(::expiryOf)
            if (isExpired(expiresAt, now)) {
                expired += stored
                continue
            }
            counts[decoded.first] = (counts[decoded.first] ?: 0) + 1
        }
        if (expired.isNotEmpty()) {
            prefs.edit().apply { expired.forEach { remove(it) } }.apply()
        }
        return counts.map { NamespaceInfo(it.key, it.value) }.sortedBy { it.namespace }
    }

    /** Removes every key in a namespace; returns how many entries were stored. */
    @Synchronized
    fun clear(namespace: String?): Int {
        val ns = namespaceOrDefault(namespace)
        requireValidName(ns, "namespace")
        val targets = prefs.all.keys.filter { decode(it)?.first == ns }
        if (targets.isEmpty()) return 0
        val editor = prefs.edit()
        targets.forEach { editor.remove(it) }
        editor.apply()
        return targets.size
    }

    /**
     * R54: exports one namespace, or every namespace when [namespace] is
     * null/blank/"all", as a JSON backup string. The result is returned to the
     * tool caller, never written back into KV, so it is not bound by the 64KB
     * per-value limit; all-namespace exports are capped by [MAX_EXPORT_BYTES].
     */
    @Synchronized
    fun export(
        namespace: String? = null,
        includeExpired: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): String {
        val all = namespace.isNullOrBlank() || namespace == "all"
        val ns = namespaceOrDefault(namespace)
        if (!all) requireValidName(ns, "namespace")
        val records = mutableListOf<BackupRecord>()
        for ((stored, raw) in prefs.all) {
            val decoded = decode(stored) ?: continue
            if (!all && decoded.first != ns) continue
            val rawText = raw as? String ?: continue
            val expiresAt = expiryOf(rawText)
            if (!includeExpired && isExpired(expiresAt, now)) continue
            val value = unwrap(rawText) ?: continue
            records += BackupRecord(decoded.first, decoded.second, value, expiresAt)
        }
        val scope = if (all) ALL_SCOPE else ns
        val json = backupEnvelope(scope, records, now)
        exportSizeError(scope, jsonByteSize(json))?.let { throw IllegalArgumentException(it) }
        return json
    }

    /**
     * R54: imports a [backupEnvelope] produced by [export].
     *
     * The whole payload is validated by [planImport] before anything is
     * written, so malformed input never leaves a half-applied import. In
     * `replace` mode the target namespaces are cleared only after validation
     * succeeds. Entries already expired at import time are skipped and
     * reported in [ImportResult.skipped] (valid or over-quota payloads are
     * errors, never silently skipped).
     */
    @Synchronized
    fun importJson(
        dataJson: String,
        mode: String = IMPORT_MODE_MERGE,
        confirm: Boolean? = null,
        now: Long = System.currentTimeMillis(),
    ): ImportResult {
        importConfirmError(mode, confirm)?.let { throw IllegalArgumentException(it.removePrefix("ERROR: ")) }
        val plan = planImport(dataJson, mode, liveKeysByNamespace(now), now)
        if (plan.imported == 0) return ImportResult(0, 0, plan.skipped)
        val editor = prefs.edit()
        if (plan.clearedNamespaces.isNotEmpty()) {
            prefs.all.keys
                .filter { decode(it)?.first in plan.clearedNamespaces }
                .forEach { editor.remove(it) }
        }
        for ((ns, items) in plan.writes) {
            for ((key, record) in items) {
                editor.putString(encode(ns, key), wrap(record.value, record.expiresAt))
            }
        }
        editor.apply()
        return ImportResult(plan.namespaces, plan.imported, plan.skipped)
    }

    /** Live (non-expired) keys grouped by namespace, used for quota checks. */
    private fun liveKeysByNamespace(now: Long): Map<String, Set<String>> {
        val result = linkedMapOf<String, MutableSet<String>>()
        for ((stored, raw) in prefs.all) {
            val decoded = decode(stored) ?: continue
            val expiresAt = (raw as? String)?.let(::expiryOf)
            if (isExpired(expiresAt, now)) continue
            result.getOrPut(decoded.first) { linkedSetOf() } += decoded.second
        }
        return result
    }

    @Synchronized
    private fun ensureCapacity(ns: String, newKeys: Collection<String>) {
        val allNamespaces = namespaces()
        val existingKeys = storedKeys(ns)
        val isNewNamespace = allNamespaces.none { it.namespace == ns }
        val added = newKeys.distinct().count { it !in existingKeys }
        capacityError(
            namespaceCount = allNamespaces.size,
            keyCount = existingKeys.size,
            isNewNamespace = isNewNamespace,
            newKeys = added,
        )?.let { throw IllegalArgumentException(it) }
    }

    private fun storedKeys(ns: String): Set<String> {
        val now = System.currentTimeMillis()
        return prefs.all.entries.mapNotNull { (stored, raw) ->
            val decoded = decode(stored) ?: return@mapNotNull null
            if (decoded.first != ns) return@mapNotNull null
            val expiresAt = (raw as? String)?.let(::expiryOf)
            if (isExpired(expiresAt, now)) null else decoded.second
        }.toSet()
    }

    companion object {
        const val PREFS_NAME = "andagent_kv"
        const val DEFAULT_NAMESPACE = "default"
        const val MAX_VALUE_BYTES = 64 * 1024
        const val MAX_NAMESPACES = 64
        const val MAX_KEYS_PER_NAMESPACE = 512
        /** All-namespace export ceiling; larger payloads must be split per namespace. */
        const val MAX_EXPORT_BYTES = 512 * 1024
        const val ALL_SCOPE = "all"
        const val IMPORT_MODE_MERGE = "merge"
        const val IMPORT_MODE_REPLACE = "replace"

        private val NAME = Regex("[a-zA-Z0-9_.-]{1,64}")

        fun isValidName(value: String?): Boolean = value != null && NAME.matches(value)

        fun namespaceOrDefault(namespace: String?): String =
            namespace?.takeIf { it.isNotBlank() } ?: DEFAULT_NAMESPACE

        /** SharedPreferences key encoding; both halves are regex-validated first. */
        fun encode(namespace: String, key: String): String = "$namespace:$key"

        fun decode(stored: String): Pair<String, String>? {
            val separator = stored.indexOf(':')
            if (separator <= 0 || separator == stored.lastIndex) return null
            return stored.substring(0, separator) to stored.substring(separator + 1)
        }

        fun isExpired(expiresAt: Long?, now: Long): Boolean =
            expiresAt != null && expiresAt <= now

        fun expiryFrom(ttlMs: Long?, now: Long): Long? = ttlMs?.let { now + it }

        fun ttlError(ttlMs: Long?): String? =
            if (ttlMs != null && ttlMs <= 0) "ttl_ms 必须为正整数" else null

        /** Remaining TTL rounded up so a live key never reports 0 seconds. */
        fun ttlRemainingSeconds(expiresAt: Long?, now: Long): Long? =
            expiresAt?.let { ((it - now + 999).coerceAtLeast(0)) / 1000 }

        fun wrap(value: JsonElement, expiresAt: Long?): String = buildJsonObject {
            put("v", value)
            put("exp", expiresAt?.let { JsonPrimitive(it) } ?: JsonNull)
        }.toString()

        fun unwrap(raw: String): JsonElement? = runCatching {
            (Json.parseToJsonElement(raw) as? JsonObject)?.get("v")
        }.getOrNull()

        fun expiryOf(raw: String): Long? = runCatching {
            (Json.parseToJsonElement(raw) as? JsonObject)?.get("exp")?.jsonPrimitive?.longOrNull
        }.getOrNull()

        fun jsonByteSize(json: String): Int = json.toByteArray(Charsets.UTF_8).size

        /** Limits are applied to the serialized JSON value (not the envelope). */
        fun exceedsValueLimit(json: String): Boolean = jsonByteSize(json) > MAX_VALUE_BYTES

        /** Pure capacity check used before any write; null means the write is allowed. */
        fun capacityError(namespaceCount: Int, keyCount: Int, isNewNamespace: Boolean, newKeys: Int): String? = when {
            isNewNamespace && namespaceCount >= MAX_NAMESPACES ->
                "命名空间数量已达上限 $MAX_NAMESPACES"
            keyCount + newKeys > MAX_KEYS_PER_NAMESPACE ->
                "命名空间键数超过上限 $MAX_KEYS_PER_NAMESPACE"
            else -> null
        }

        fun clearConfirmError(confirm: Boolean?): String? =
            if (confirm == true) null else "ERROR: kv_clear 需要 confirm=true"

        /* ------------------------- R54 backup helpers ------------------------- */

        fun normalizeImportMode(mode: String?): String =
            mode?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: IMPORT_MODE_MERGE

        fun importModeError(mode: String): String? =
            if (mode == IMPORT_MODE_MERGE || mode == IMPORT_MODE_REPLACE) null
            else "ERROR: mode 仅支持 merge 或 replace"

        fun importConfirmError(mode: String, confirm: Boolean?): String? =
            if (mode == IMPORT_MODE_REPLACE && confirm != true) "ERROR: kv_import mode=replace 需要 confirm=true"
            else null

        /** All-namespace exports above [MAX_EXPORT_BYTES] must be split per namespace. */
        fun exportSizeError(scope: String, bytes: Int): String? =
            if (scope == ALL_SCOPE && bytes > MAX_EXPORT_BYTES)
                "导出体积 ${bytes}B 超过 ${MAX_EXPORT_BYTES}B 上限，请用 namespace 参数分命名空间导出"
            else null

        /**
         * Serializes records into the public backup envelope. Each item is the
         * same `{"v":<json>,"exp":<epochMs|null>}` envelope used at rest, so a
         * round trip preserves both values and absolute TTLs.
         */
        fun backupEnvelope(scope: String, records: List<BackupRecord>, exportedAt: Long): String {
            val grouped = records.groupBy { it.namespace }
            return buildJsonObject {
                put("ok", true)
                put("scope", scope)
                put("count", records.size)
                put("exportedAt", exportedAt)
                put("namespaces", buildJsonArray {
                    for ((ns, items) in grouped.toSortedMap()) {
                        add(buildJsonObject {
                            put("namespace", ns)
                            put("items", buildJsonObject {
                                for (record in items.sortedBy { it.key }) {
                                    put(record.key, Json.parseToJsonElement(wrap(record.value, record.expiresAt)))
                                }
                            })
                        })
                    }
                })
            }.toString()
        }

        /**
         * Strictly parses a backup payload. Any structural problem, illegal
         * name, malformed envelope or oversized value throws, so callers can
         * guarantee "validate everything, then write".
         */
        fun parseBackup(dataJson: String): List<BackupRecord> {
            val root = runCatching { Json.parseToJsonElement(dataJson) }.getOrNull()
                ?: throw IllegalArgumentException("data_json 不是合法 JSON")
            if (root !is JsonObject) throw IllegalArgumentException("data_json 必须是 kv_export 输出的 JSON 对象")
            val namespaces = root["namespaces"]
                ?: throw IllegalArgumentException("data_json 缺少 namespaces 数组")
            if (namespaces !is JsonArray) throw IllegalArgumentException("data_json 的 namespaces 必须是数组")
            val records = mutableListOf<BackupRecord>()
            for ((index, node) in namespaces.withIndex()) {
                val nsObject = node as? JsonObject
                    ?: throw IllegalArgumentException("namespaces[$index] 必须是对象")
                val rawNamespace = (nsObject["namespace"] as? JsonPrimitive)?.contentOrNull
                if (!isValidName(rawNamespace)) throw IllegalArgumentException("namespaces[$index] 的 namespace 非法")
                val items = nsObject["items"]
                    ?: throw IllegalArgumentException("namespaces[$index] 缺少 items 对象")
                if (items !is JsonObject) throw IllegalArgumentException("namespaces[$index].items 必须是对象")
                for ((key, envelope) in items) {
                    if (!isValidName(key)) throw IllegalArgumentException("键名非法：$key")
                    val obj = envelope as? JsonObject
                        ?: throw IllegalArgumentException("items[$key] 必须是包含 v 的 JSON 对象")
                    if (!obj.containsKey("v")) throw IllegalArgumentException("items[$key] 缺少 v 字段")
                    val value = obj.getValue("v")
                    if (exceedsValueLimit(value.toString())) {
                        throw IllegalArgumentException("值超过 64KB 上限（$rawNamespace:$key）")
                    }
                    val expNode = obj["exp"]
                    val expiresAt = if (expNode == null || expNode is JsonNull) {
                        null
                    } else {
                        (expNode as? JsonPrimitive)?.longOrNull
                            ?: throw IllegalArgumentException("items[$key] 的 exp 必须是 epoch 毫秒或 null")
                    }
                    records += BackupRecord(rawNamespace!!, key, value, expiresAt)
                }
            }
            return records
        }

        /**
         * Pure merge/replace planning. [existing] maps live namespaces to their
         * live keys. Throws on quota overflow; returns the exact writes and
         * clears the caller should apply.
         */
        fun planImport(
            dataJson: String,
            mode: String,
            existing: Map<String, Set<String>>,
            now: Long,
        ): ImportPlan {
            importModeError(mode)?.let { throw IllegalArgumentException(it.removePrefix("ERROR: ")) }
            val parsed = parseBackup(dataJson)
            var skipped = 0
            val byNamespace = linkedMapOf<String, LinkedHashMap<String, BackupRecord>>()
            for (record in parsed) {
                if (isExpired(record.expiresAt, now)) {
                    skipped++
                    continue
                }
                val bucket = byNamespace.getOrPut(record.namespace) { linkedMapOf() }
                if (bucket.put(record.key, record) != null) skipped++
            }
            if (byNamespace.isEmpty()) return ImportPlan(emptyMap(), emptySet(), 0, 0, skipped)

            val newNamespaces = byNamespace.keys.count { it !in existing }
            if (existing.size + newNamespaces > MAX_NAMESPACES) {
                throw IllegalArgumentException("命名空间数量超过上限 $MAX_NAMESPACES")
            }
            for ((ns, items) in byNamespace) {
                val base = if (mode == IMPORT_MODE_REPLACE) emptySet() else existing[ns].orEmpty()
                if ((base + items.keys).size > MAX_KEYS_PER_NAMESPACE) {
                    throw IllegalArgumentException("命名空间键数超过上限 $MAX_KEYS_PER_NAMESPACE（$ns）")
                }
            }
            return ImportPlan(
                writes = byNamespace,
                clearedNamespaces = if (mode == IMPORT_MODE_REPLACE) byNamespace.keys.toSet() else emptySet(),
                namespaces = byNamespace.size,
                imported = byNamespace.values.sumOf { it.size },
                skipped = skipped,
            )
        }

        private fun requireValidName(value: String, label: String) {
            require(isValidName(value)) { "$label 仅允许字母数字._-，长度 1-64" }
        }
    }
}
