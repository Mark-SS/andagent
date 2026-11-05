package com.dc.andagent.widget.data

import android.content.Context
import android.util.AtomicFile
import com.dc.andagent.widget.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID

@Serializable
data class SavedWidget(val id: String, val spec: WidgetSpec, val history: List<WidgetSpec> = emptyList())
@Serializable
data class LibraryState(val records: List<SavedWidget> = emptyList(), val bindings: Map<String, String> = emptyMap())

/** Small atomic local store for P0. No credentials or network data are persisted here. */
class WidgetStore(context: Context) {
    private val appContext = context.applicationContext
    private val file = AtomicFile(File(appContext.filesDir, "widget-library.json"))

    fun read(): LibraryState = synchronized(lock) { readLocked() }

    private fun readLocked(): LibraryState {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
            val initial = LibraryState()
            writeLocked(initial)
            return initial
        }
        val state = SpecCodec.json.decodeFromString<LibraryState>(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        // Records written before v2 are upgraded in memory; the next save persists v2.
        val upgraded = state.copy(records = state.records.map { record ->
            record.copy(spec = SpecCodec.migrate(record.spec), history = record.history.map(SpecCodec::migrate))
        })
        return upgraded
    }

    private fun writeLocked(state: LibraryState) {
        val bytes = SpecCodec.json.encodeToString(state).toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }

    fun save(id: String?, spec: WidgetSpec): SavedWidget = synchronized(lock) {
        SpecCodec.validate(spec)
        val state = readLocked()
        val old = state.records.find { it.id == id }
        if (old?.spec == spec) return@synchronized old
        val record = SavedWidget(old?.id ?: UUID.randomUUID().toString(), spec,
            if (old == null) emptyList() else (old.history + old.spec).takeLast(10))
        val records = if (old == null) state.records + record else state.records.map { if (it.id == old.id) record else it }
        writeLocked(state.copy(records = records))
        record
    }

    fun undo(id: String): SavedWidget = synchronized(lock) {
        val state = readLocked()
        val old = state.records.first { it.id == id }
        require(old.history.isNotEmpty()) { "暂无上一版本" }
        val record = old.copy(spec = old.history.last(), history = old.history.dropLast(1))
        writeLocked(state.copy(records = state.records.map { if (it.id == id) record else it }))
        record
    }

    fun delete(id: String) = synchronized(lock) {
        val state = readLocked()
        writeLocked(state.copy(records = state.records.filterNot { it.id == id }, bindings = state.bindings.filterValues { it != id }))
    }

    fun bind(widgetId: Int, recordId: String) = synchronized(lock) {
        require(widgetId > 0)
        val state = readLocked()
        require(state.records.any { it.id == recordId }) { "组件不存在" }
        writeLocked(state.copy(bindings = state.bindings + (widgetId.toString() to recordId)))
    }

    fun unbind(ids: IntArray) = synchronized(lock) {
        val state = readLocked()
        writeLocked(state.copy(bindings = state.bindings - ids.map(Int::toString).toSet()))
    }

    companion object { private val lock = Any() }
}
