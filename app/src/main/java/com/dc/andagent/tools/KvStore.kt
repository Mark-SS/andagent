package com.dc.andagent.tools

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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

        private fun requireValidName(value: String, label: String) {
            require(isValidName(value)) { "$label 仅允许字母数字._-，长度 1-64" }
        }
    }
}
