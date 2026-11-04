package io.nous.andagent.widget.data

import android.content.Context
import android.util.AtomicFile
import io.nous.andagent.widget.model.SpecCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

@Serializable
data class CachedSource(
    val values: Map<String, String> = emptyMap(),
    val fetchedAt: Long = 0L,
    val error: String? = null
)

@Serializable
data class DataCacheState(val records: Map<String, Map<String, CachedSource>> = emptyMap())

/**
 * Stores the last successful data for each source. Cache corruption only falls back to
 * "no data"; it never blocks rendering, and it is kept apart from the versioned library.
 */
class DataCache(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "widget-data.json"))

    fun read(): DataCacheState = synchronized(lock) { readLocked() }

    private fun readLocked(): DataCacheState {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return DataCacheState()
        return runCatching {
            SpecCodec.json.decodeFromString<DataCacheState>(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        }.getOrDefault(DataCacheState())
    }

    fun sources(recordId: String): Map<String, CachedSource> = read().records[recordId].orEmpty()

    fun snapshot(recordId: String?): Map<String, Map<String, String>> {
        if (recordId == null) return emptyMap()
        return read().records[recordId]?.mapValues { it.value.values }.orEmpty()
    }

    fun isFresh(recordId: String, source: String, ttlSeconds: Int, now: Long = System.currentTimeMillis()): Boolean {
        if (ttlSeconds <= 0) return false
        val cached = read().records[recordId]?.get(source) ?: return false
        return cached.fetchedAt > 0 && now - cached.fetchedAt < ttlSeconds * 1000L
    }

    fun put(recordId: String, source: String, values: Map<String, String>) = update(recordId, source) {
        CachedSource(values, System.currentTimeMillis(), null)
    }

    /** Keeps the last good value when available, otherwise exposes the declared fallback. */
    fun putError(recordId: String, source: String, message: String, fallback: Map<String, String>) = update(recordId, source) { old ->
        val previous = old?.values.orEmpty()
        val values = if (previous.isNotEmpty()) previous else fallback
        val fetchedAt = if (previous.isNotEmpty()) old?.fetchedAt ?: 0L else 0L
        CachedSource(values, fetchedAt, message.take(200))
    }

    fun clear(recordId: String) = synchronized(lock) {
        val state = readLocked()
        if (state.records.containsKey(recordId)) writeLocked(state.copy(records = state.records - recordId))
    }

    fun clearAll() = synchronized(lock) { writeLocked(DataCacheState()) }

    fun sizeBytes(): Long = if (file.baseFile.exists()) file.baseFile.length() else 0L

    private fun update(recordId: String, source: String, block: (CachedSource?) -> CachedSource) = synchronized(lock) {
        val state = readLocked()
        val record = state.records[recordId].orEmpty()
        val next = state.copy(records = state.records + (recordId to (record + (source to block(record[source])))))
        writeLocked(next)
    }

    private fun writeLocked(state: DataCacheState) {
        val bytes = SpecCodec.json.encodeToString(state).toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }

    companion object { private val lock = Any() }
}
