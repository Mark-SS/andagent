package io.nous.andagent.bridge

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import android.webkit.JavascriptInterface
import kotlinx.serialization.json.*

/** Scoped to one app-private database and one dialog WebView. */
class DbGuiBridge(private val context: Context, private val name: String, private val closeDialog: () -> Unit) {
    private val path = context.getDatabasePath("andagent_${name}.db")
    private fun existing() = path.isFile && name.matches(Regex("[A-Za-z0-9_]{1,64}"))
    private fun error(e: Throwable) = buildJsonObject { put("error", e.message ?: "数据库读取失败") }.toString()

    @JavascriptInterface fun info(): String = runCatching {
        require(existing()) { "数据库不存在" }
        val wal = java.io.File("${path.path}-wal")
        buildJsonObject {
            put("name", name)
            put("sizeBytes", path.length())
            put("mode", if (path.length() <= 20L * 1024 * 1024 && (!wal.exists() || wal.length() == 0L)) "wasm" else "paged")
        }.toString()
    }.getOrElse(::error)

    @JavascriptInterface fun readDbBase64(requestedName: String): String = runCatching {
        require(requestedName == name && existing()) { "数据库不存在" }
        require(path.length() <= 20L * 1024 * 1024) { "数据库超过 20MB" }
        val wal = java.io.File("${path.path}-wal")
        require(!wal.exists() || wal.length() == 0L) { "数据库有 WAL 日志，请用分页模式" }
        Base64.encodeToString(path.readBytes(), Base64.NO_WRAP)
    }.getOrElse { "ERROR: ${it.message}" }

    /** The connection and SQLite query_only flag enforce read-only access even for CTEs. */
    @JavascriptInterface fun sqlExec(requestedName: String, sql: String, readOnly: Boolean): String = runCatching {
        require(requestedName == name && existing()) { "数据库不存在" }
        require(readOnly) { "浏览器仅支持只读查询" }
        val statement = sql.trim().removeSuffix(";").trim()
        require(statement.length in 1..10000 && !statement.contains(';')) { "只允许一条查询语句" }
        require(Regex("^(SELECT|WITH|EXPLAIN)\\b", RegexOption.IGNORE_CASE).containsMatchIn(statement)) { "仅支持 SELECT、WITH 或 EXPLAIN" }
        require(!Regex("\\b(ATTACH|DETACH|PRAGMA|VACUUM|REINDEX|ANALYZE|LOAD_EXTENSION)\\b", RegexOption.IGNORE_CASE).containsMatchIn(statement)) { "查询含不允许的指令" }
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.execSQL("PRAGMA query_only=ON")
            db.rawQuery(statement, null).use { cursor ->
                val cols = cursor.columnNames
                val rows = buildJsonArray {
                    var count = 0
                    while (count < 100 && cursor.moveToNext()) {
                        count++
                        add(buildJsonArray { cols.indices.forEach { i -> add(cell(cursor, i)) } })
                    }
                }
                buildJsonObject {
                    put("columns", JsonArray(cols.map(::JsonPrimitive)))
                    put("rows", rows)
                    put("truncated", cursor.moveToNext())
                }.toString()
            }
        }
    }.getOrElse(::error)

    private fun cell(c: Cursor, i: Int): JsonElement = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> JsonNull
        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(c.getLong(i))
        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(c.getDouble(i))
        Cursor.FIELD_TYPE_BLOB -> JsonPrimitive("<BLOB ${c.getBlob(i).size} bytes>")
        else -> JsonPrimitive(c.getString(i))
    }

    @JavascriptInterface fun close() = closeDialog()
}
