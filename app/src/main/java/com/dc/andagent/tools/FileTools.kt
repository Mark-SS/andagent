package com.dc.andagent.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.dc.andagent.bridge.PermissionBroker
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import android.util.Base64
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Files: download / search / write / read / list.
 *
 * Android 10+ uses MediaStore (scoped storage); Android 8/9 falls back to the
 * legacy public directories with WRITE_EXTERNAL_STORAGE. Everything returns a
 * Chinese error string on failure instead of throwing.
 */

private object FileHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}

private const val BROWSER_UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

private const val READ_CAP = 100 * 1024
private const val NES_CAP = 2 * 1024 * 1024

/** SAF-backed, bounded ROM reader. The display name and header are both checked. */
class FileReadB64Tool(private val context: Context) : Tool {
    override val name = "file_read_b64"
    override val description = "读取不超过 2MB 的 .nes ROM，返回 base64；仅接受有效 NES 文件头。"
    override val parameters = schemaOf("uri" to strParam("file_pick 返回的 content:// URI"), required = listOf("uri"))

    override suspend fun execute(args: JsonObject): String {
        val raw = args.strArg("uri") ?: return "ERROR: 缺少 uri"
        if (!raw.startsWith("content://")) return "ERROR: 只接受文件选择器的 content:// URI"
        return runCatching {
            val uri = Uri.parse(raw)
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
                ?: error("无法读取文件名")
            require(name.endsWith(".nes", ignoreCase = true)) { "只支持 .nes 文件" }
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(out.size() + n <= NES_CAP) { "ROM 超过 2MB" }
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            } ?: error("无法打开 ROM")
            require(validNesHeader(bytes)) { "无效 NES 文件头" }
            buildJsonObject {
                put("name", name)
                put("bytes", bytes.size)
                put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            }.toString()
        }.getOrElse { "ERROR: 读取 ROM 失败：${it.message ?: it.javaClass.simpleName}" }
    }
}

internal fun validNesHeader(bytes: ByteArray): Boolean = bytes.size >= 16 &&
    bytes[0] == 'N'.code.toByte() && bytes[1] == 'E'.code.toByte() &&
    bytes[2] == 'S'.code.toByte() && bytes[3] == 0x1a.toByte()

private fun dirRoot(token: String?): String? = when (token?.lowercase()) {
    null, "", "downloads", "download" -> Environment.DIRECTORY_DOWNLOADS
    "documents", "document" -> Environment.DIRECTORY_DOCUMENTS
    "movies", "movie" -> Environment.DIRECTORY_MOVIES
    "music" -> Environment.DIRECTORY_MUSIC
    "pictures", "photos" -> Environment.DIRECTORY_PICTURES
    "dcim" -> Environment.DIRECTORY_DCIM
    else -> null
}

private fun sanitizeName(raw: String?): String? {
    val base = (raw ?: "").substringAfterLast('/').substringAfterLast('\\').trim()
    if (base.isBlank() || base == "." || base == "..") return null
    return base.take(160)
}

private fun nameFromUrl(url: String): String {
    val path = runCatching { URI(url).path }.getOrNull().orEmpty()
    val last = path.substringAfterLast('/')
    return sanitizeName(last)?.takeIf { it.contains('.') }
        ?: "download_${System.currentTimeMillis()}.bin"
}

private fun mimeFor(name: String, header: String?): String {
    val fromHeader = header?.substringBefore(';')?.trim()
    if (!fromHeader.isNullOrBlank()) return fromHeader
    return when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "md", "log", "csv", "json", "xml", "yaml", "yml", "kt", "java", "js", "ts", "html", "css" -> "text/plain"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }
}

private fun displayPath(root: String, name: String): String = "/sdcard/$root/$name"

private suspend fun ensureLegacyWrite(permissions: PermissionBroker): Boolean {
    if (Build.VERSION.SDK_INT >= 29) return true
    return runCatching {
        permissions.ensure(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }.getOrDefault(false)
}

private suspend fun ensureRead(permissions: PermissionBroker): Boolean {
    val names = if (Build.VERSION.SDK_INT >= 33) {
        listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    return runCatching { permissions.ensure(names) }.getOrDefault(false)
}

private fun uniqueFile(dir: File, name: String): File {
    var candidate = File(dir, name)
    if (!candidate.exists()) return candidate
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var i = 1
    while (candidate.exists()) {
        candidate = File(dir, "$stem ($i)$ext")
        i++
    }
    return candidate
}

/* ------------------------------------------------------------------ */
/* file_download                                                       */
/* ------------------------------------------------------------------ */

class FileDownloadTool(
    private val context: Context,
    private val permissions: PermissionBroker,
) : Tool {
    override val name = "file_download"
    override val description =
        "用 HTTP GET 下载文件到公共下载目录 Downloads/andagent/，支持大文件和重定向。" +
            "参数 url 必填，filename 可选；返回保存路径与大小。"
    override val parameters = schemaOf(
        "url" to strParam("下载地址（http/https）"),
        "filename" to strParam("可选：保存的文件名，默认从 URL 推断"),
        required = listOf("url"),
    )

    override suspend fun execute(args: JsonObject): String {
        val url = args.strArg("url") ?: return "ERROR: 缺少 url"
        val name = sanitizeName(args.strArg("filename")) ?: nameFromUrl(url)
        val request = runCatching {
            Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_UA)
                .header("Accept", "*/*")
                .get()
                .build()
        }.getOrElse { return "ERROR: 下载地址无效：${it.message ?: "无法解析 URL"}" }

        val response = runCatching {
            FileHttp.client.newCall(request).execute()
        }.getOrElse { return "ERROR: 下载失败：${it.message ?: it.javaClass.simpleName}" }

        response.use { resp ->
            if (!resp.isSuccessful) {
                return "ERROR: 下载失败：HTTP ${resp.code} ${resp.message}"
            }
            val body = resp.body ?: return "ERROR: 下载失败：响应没有内容"
            val mime = mimeFor(name, resp.header("Content-Type"))

            return if (Build.VERSION.SDK_INT >= 29) {
                saveWithMediaStore(resp, name, mime)
            } else {
                if (!ensureLegacyWrite(permissions)) {
                    return "ERROR: 需要存储写入权限才能保存到 Downloads，请在系统设置中授予"
                }
                saveLegacy(resp, name, mime)
            }
        }
    }

    private fun saveWithMediaStore(
        response: okhttp3.Response,
        name: String,
        mime: String,
    ): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/andagent")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull()
            ?: return "ERROR: 无法在 Downloads 创建文件（MediaStore 拒绝）"
        return runCatching {
            val bytes = response.body!!.byteStream().use { input ->
                resolver.openOutputStream(uri)?.use { output -> input.copyTo(output) }
                    ?: error("无法打开输出流")
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            val actual = queryDisplayName(uri) ?: name
            buildJsonObject {
                put("saved", true)
                put("name", actual)
                put("path", "/sdcard/${Environment.DIRECTORY_DOWNLOADS}/andagent/$actual")
                put("uri", uri.toString())
                put("bytes", bytes)
                put("sizeMB", "%.2f".format(bytes / 1e6))
            }.toString()
        }.getOrElse {
            runCatching { resolver.delete(uri, null, null) }
            "ERROR: 写入 Downloads 失败：${it.message ?: it.javaClass.simpleName}"
        }
    }

    private fun saveLegacy(response: okhttp3.Response, name: String, mime: String): String {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "andagent",
        )
        if (!dir.exists() && !dir.mkdirs()) return "ERROR: 无法创建目录 ${dir.path}"
        val file = uniqueFile(dir, name)
        return runCatching {
            val bytes = response.body!!.byteStream().use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf(mime), null)
            buildJsonObject {
                put("saved", true)
                put("name", file.name)
                put("path", file.path)
                put("bytes", bytes)
                put("sizeMB", "%.2f".format(bytes / 1e6))
            }.toString()
        }.getOrElse {
            runCatching { file.delete() }
            "ERROR: 写入 Downloads 失败：${it.message ?: it.javaClass.simpleName}"
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
}

/* ------------------------------------------------------------------ */
/* file_write                                                          */
/* ------------------------------------------------------------------ */

class FileWriteTool(
    private val context: Context,
    private val permissions: PermissionBroker,
) : Tool {
    override val name = "file_write"
    override val description =
        "写入文本文件。dir: downloads（写入 Download/andagent/）、documents（写入 Documents/）或 app_private（应用私有目录）。" +
            "返回完整路径与字节数。"
    override val parameters = schemaOf(
        "dir" to strParam("保存位置：downloads | documents | app_private，默认 downloads"),
        "name" to strParam("文件名，例如 notes.txt"),
        "content" to strParam("文件内容（UTF-8）"),
        required = listOf("name"),
    )

    override suspend fun execute(args: JsonObject): String {
        val target = (args.strArg("dir") ?: args.strArg("path") ?: "downloads").lowercase()
        val name = sanitizeName(args.strArg("name")) ?: return "ERROR: 缺少有效的 name"
        val content = args["content"]?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        } ?: ""
        val bytes = content.toByteArray(Charsets.UTF_8)

        return when (target) {
            "app_private", "private", "app" -> {
                val dir = File(context.filesDir, "andagent")
                if (!dir.exists() && !dir.mkdirs()) return "ERROR: 无法创建应用私有目录"
                val file = File(dir, name)
                runCatching {
                    file.writeBytes(bytes)
                    buildJsonObject {
                        put("saved", true)
                        put("name", file.name)
                        put("path", file.absolutePath)
                        put("bytes", bytes.size)
                    }.toString()
                }.getOrElse { "ERROR: 写入私有文件失败：${it.message ?: it.javaClass.simpleName}" }
            }

            "documents", "document" -> {
                if (Build.VERSION.SDK_INT >= 29) {
                    writeMediaStore(
                        name = name,
                        bytes = bytes,
                        relative = Environment.DIRECTORY_DOCUMENTS,
                    )
                } else {
                    if (!ensureLegacyWrite(permissions)) return "ERROR: 需要存储写入权限"
                    writeLegacy(
                        name = name,
                        bytes = bytes,
                        root = Environment.DIRECTORY_DOCUMENTS,
                    )
                }
            }

            else -> {
                if (Build.VERSION.SDK_INT >= 29) {
                    writeMediaStore(
                        name = name,
                        bytes = bytes,
                        relative = "${Environment.DIRECTORY_DOWNLOADS}/andagent",
                    )
                } else {
                    if (!ensureLegacyWrite(permissions)) return "ERROR: 需要存储写入权限"
                    writeLegacy(
                        name = name,
                        bytes = bytes,
                        root = "${Environment.DIRECTORY_DOWNLOADS}/andagent",
                    )
                }
            }
        }
    }

    private fun writeMediaStore(name: String, bytes: ByteArray, relative: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(name, null))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (relative.startsWith(Environment.DIRECTORY_DOWNLOADS)) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull()
            ?: return "ERROR: 无法在 $relative 创建文件（MediaStore 拒绝）"
        return runCatching {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法打开输出流")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            val actual = runCatching {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: name
            buildJsonObject {
                put("saved", true)
                put("name", actual)
                put("path", "/sdcard/$relative/$actual")
                put("uri", uri.toString())
                put("bytes", bytes.size)
            }.toString()
        }.getOrElse {
            runCatching { resolver.delete(uri, null, null) }
            "ERROR: 写入 $relative 失败：${it.message ?: it.javaClass.simpleName}"
        }
    }

    private fun writeLegacy(name: String, bytes: ByteArray, root: String): String {
        val dir = File(Environment.getExternalStoragePublicDirectory(root), "")
        if (!dir.exists() && !dir.mkdirs()) return "ERROR: 无法创建目录 ${dir.path}"
        val file = uniqueFile(dir, name)
        return runCatching {
            file.writeBytes(bytes)
            MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf(mimeFor(name, null)), null)
            buildJsonObject {
                put("saved", true)
                put("name", file.name)
                put("path", file.path)
                put("bytes", bytes.size)
            }.toString()
        }.getOrElse { "ERROR: 写入文件失败：${it.message ?: it.javaClass.simpleName}" }
    }
}

/* ------------------------------------------------------------------ */
/* file_read                                                           */
/* ------------------------------------------------------------------ */

class FileReadTool(private val context: Context) : Tool {
    override val name = "file_read"
    override val description =
        "读取文本文件。可用 path（绝对路径或 content://），也可用 dir（downloads|documents|app_private）+ name。" +
            "上限 100KB；二进制文件会返回明确错误。"
    override val parameters = schemaOf(
        "path" to strParam("文件绝对路径或 content:// URI"),
        "dir" to strParam("与 name 搭配：downloads | documents | app_private"),
        "name" to strParam("文件名（与 dir 搭配）"),
    )

    override suspend fun execute(args: JsonObject): String {
        val path = args.strArg("path")
        val dir = args.strArg("dir")?.lowercase()
        val name = sanitizeName(args.strArg("name"))

        val resolved = resolve(path, dir, name)
            ?: return "ERROR: 找不到文件（请检查 path 或 dir+name，公共目录需要存储读取权限）"

        return runCatching {
            resolved.openStream().use { raw ->
                val input = BufferedInputStream(raw, 8192)
                val head = ByteArray(8)
                input.mark(16)
                val headLen = input.read(head)
                input.reset()
                if (looksBinary(head, headLen)) {
                    return@use "ERROR: 这不是文本文件（检测到二进制内容），已拒绝读取"
                }
                val cap = if (dir == "app_private" && name?.matches(Regex("nes-[0-9a-f]{8}\\.json")) == true) NES_CAP else READ_CAP
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                var truncated = false
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (total + n > cap) {
                        out.write(buf, 0, cap - total)
                        total = cap
                        truncated = true
                        break
                    }
                    out.write(buf, 0, n)
                    total += n
                }
                buildJsonObject {
                    put("path", resolved.displayPath)
                    put("bytes", total)
                    put("truncated", truncated)
                    put("content", out.toString(Charsets.UTF_8.name()))
                }.toString()
            }
        }.getOrElse { "ERROR: 读取文件失败：${it.message ?: it.javaClass.simpleName}" }
    }

    private data class Resolved(val displayPath: String, val openStream: () -> InputStream)

    private fun resolve(path: String?, dir: String?, name: String?): Resolved? {
        if (!path.isNullOrBlank()) {
            if (path.startsWith("content://")) {
                val uri = runCatching { Uri.parse(path) }.getOrNull() ?: return null
                val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
                    ?: return null
                return Resolved(path, { stream })
            }
            val file = File(path)
            if (file.isFile && file.canRead()) {
                return Resolved(file.absolutePath, { file.inputStream() })
            }
            // Scoped storage: /sdcard/... may not be directly readable but the
            // same file is still resolvable by name through MediaStore.
            if (name == null && !file.name.isNullOrBlank()) {
                resolveByMediaStore(file.name, dir)?.let { return it }
            }
        }

        if (name != null) {
            if (dir == "app_private" || dir == "private" || dir == "app") {
                val file = File(File(context.filesDir, "andagent"), name)
                if (file.isFile && file.canRead()) {
                    return Resolved(file.absolutePath, { file.inputStream() })
                }
            } else {
                resolveByMediaStore(name, dir)?.let { return it }
            }
        }
        return null
    }

    private fun resolveByMediaStore(name: String, dir: String?): Resolved? {
        if (Build.VERSION.SDK_INT < 29) return null
        val root = dirRoot(dir ?: "downloads") ?: return null
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(name, "$root/%"),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            if (!c.moveToFirst()) return null
            val id = c.getLong(0)
            val actualName = c.getString(1) ?: name
            val uri = ContentUris.withAppendedId(collection, id)
            val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
                ?: return null
            return Resolved("/sdcard/$root/$actualName", { stream })
        }
        return null
    }

    private fun looksBinary(head: ByteArray, len: Int): Boolean {
        if (len <= 0) return false
        var control = 0
        for (i in 0 until len) {
            val b = head[i].toInt() and 0xFF
            if (b == 0) return true
            if (b < 0x09 || (b in 0x0E..0x1F)) control++
        }
        return control * 10 > len * 3
    }
}

/* ------------------------------------------------------------------ */
/* file_search                                                         */
/* ------------------------------------------------------------------ */

class FileSearchTool(
    private val context: Context,
    private val permissions: PermissionBroker,
) : Tool {
    override val name = "file_search"
    override val description =
        "按文件名搜索用户文件（关键词或 *.pdf 这类通配符），返回路径、大小、类型、修改时间，最多 50 条。" +
            "可选 dir 限定 downloads|documents|movies|music|pictures。"
    override val parameters = schemaOf(
        "query" to strParam("文件名关键词或通配符，例如 报告、*.pdf、IMG_2026*"),
        "dir" to strParam("可选：限定目录 downloads|documents|movies|music|pictures"),
        required = listOf("query"),
    )

    private data class Hit(
        val name: String,
        val path: String,
        val size: Long,
        val mime: String,
        val modified: Long,
        val uri: String?,
    )

    override suspend fun execute(args: JsonObject): String {
        val query = args.strArg("query") ?: return "ERROR: 缺少 query"
        val dirToken = args.strArg("dir")
        val root = dirRoot(dirToken)
        if (dirToken != null && root == null) {
            return "ERROR: dir 只能是 downloads|documents|movies|music|pictures"
        }
        ensureRead(permissions)

        val pattern = query.trim()
        val glob = pattern.contains('*') || pattern.contains('?')
        val hits = LinkedHashMap<String, Hit>()

        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { queryMediaStore(pattern, glob, root, hits) }
        }
        runCatching { queryFileSystem(pattern, glob, root, hits) }

        val items = hits.values.sortedByDescending { it.modified }.take(50)
        if (items.isEmpty()) return "没有找到匹配「$pattern」的文件"
        return buildJsonObject {
            put("count", items.size)
            put("items", buildJsonArray {
                for (item in items) {
                    add(buildJsonObject {
                        put("name", item.name)
                        put("path", item.path)
                        put("sizeBytes", item.size)
                        put("sizeMB", "%.2f".format(item.size / 1e6))
                        put("mime", item.mime)
                        put("modified", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(item.modified)))
                        item.uri?.let { put("uri", it) }
                    })
                }
            })
        }.toString()
    }

    private fun queryMediaStore(
        pattern: String,
        glob: Boolean,
        root: String?,
        hits: MutableMap<String, Hit>,
    ) {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        val like = if (glob) globToLike(pattern) else "%${escapeLike(pattern)}%"
        var selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'"
        val selectionArgs = mutableListOf(like)
        if (root != null) {
            selection += " AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            selectionArgs += "$root/%"
        }
        context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs.toTypedArray(),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val iRel = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            while (c.moveToNext() && hits.size < 200) {
                val name = c.getString(iName) ?: continue
                val rel = c.getString(iRel) ?: ""
                val path = "/sdcard/$rel$name"
                val uri = ContentUris.withAppendedId(collection, c.getLong(iId)).toString()
                hits[path] = Hit(
                    name = name,
                    path = path,
                    size = c.getLong(iSize),
                    mime = c.getString(iMime) ?: "application/octet-stream",
                    modified = c.getLong(iDate) * 1000,
                    uri = uri,
                )
            }
        }
    }

    private fun queryFileSystem(
        pattern: String,
        glob: Boolean,
        root: String?,
        hits: MutableMap<String, Hit>,
    ) {
        val roots = if (root != null) {
            listOfNotNull(
                runCatching {
                    File(Environment.getExternalStoragePublicDirectory(root).absolutePath)
                }.getOrNull(),
            )
        } else {
            listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            )
        }
        val matcher = if (glob) globToRegex(pattern) else null
        var scanned = 0
        for (base in roots) {
            if (!base.exists() || !base.canRead()) continue
            runCatching {
                base.walkTopDown()
                    .onEnter { it == base || it.parentFile?.canRead() == true }
                    .take(4_000)
                    .forEach { file ->
                        scanned++
                        if (scanned > 8_000) return@runCatching
                        if (!file.isFile) return@forEach
                        val name = file.name
                        val matches = if (matcher != null) {
                            matcher.matches(name)
                        } else {
                            name.contains(pattern, ignoreCase = true)
                        }
                        if (!matches) return@forEach
                        hits.putIfAbsent(
                            file.absolutePath,
                            Hit(
                                name = name,
                                path = file.absolutePath,
                                size = file.length(),
                                mime = mimeFor(name, null),
                                modified = file.lastModified(),
                                uri = null,
                            ),
                        )
                    }
            }
        }
    }

    private fun escapeLike(text: String): String = text
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    private fun globToLike(glob: String): String = buildString {
        append("%")
        glob.forEach { ch ->
            when (ch) {
                '*' -> append('%')
                '?' -> append('_')
                '%', '_', '\\' -> append('\\').append(ch)
                else -> append(ch)
            }
        }
        append("%")
    }

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        glob.forEach { ch ->
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(ch.toString()))
            }
        }
        sb.append('$')
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }
}

/* ------------------------------------------------------------------ */
/* file_list                                                           */
/* ------------------------------------------------------------------ */

class FileListTool(
    private val context: Context,
    private val permissions: PermissionBroker,
) : Tool {
    override val name = "file_list"
    override val description =
        "列出 Download/andagent/（默认）或指定公共目录下的文件，返回名称、大小、修改时间，最多 100 条。" +
            "dir 可选 downloads|documents|movies|music|pictures，subdir 可指定子目录。"
    override val parameters = schemaOf(
        "dir" to strParam("目录：downloads（默认）|documents|movies|music|pictures"),
        "subdir" to strParam("可选：子目录名；downloads 默认 andagent，其他默认根目录"),
    )

    override suspend fun execute(args: JsonObject): String {
        val token = (args.strArg("dir") ?: "downloads").lowercase()
        val root = dirRoot(token) ?: return "ERROR: 不支持的 dir：$token"
        val subdir = args.strArg("subdir") ?: if (token == "downloads" || token == "download") "andagent" else ""
        ensureRead(permissions)

        val prefix = listOf(root, subdir)
            .filter { it.isNotBlank() }
            .joinToString("/")
        val hits = LinkedHashMap<String, Row>()

        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { queryMediaStore(prefix, hits) }
        }
        runCatching { queryFileSystem(root, subdir, hits) }

        val items = hits.values.sortedByDescending { it.modified }.take(100)
        if (items.isEmpty()) return "目录 $prefix/ 为空或无读取权限"
        return buildJsonObject {
            put("dir", "/sdcard/$prefix/")
            put("count", items.size)
            put("items", buildJsonArray {
                for (item in items) {
                    add(buildJsonObject {
                        put("name", item.name)
                        put("path", item.path)
                        put("sizeBytes", item.size)
                        put("sizeMB", "%.2f".format(item.size / 1e6))
                        put("modified", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(item.modified)))
                        put("directory", item.directory)
                    })
                }
            })
        }.toString()
    }

    private data class Row(
        val name: String,
        val path: String,
        val size: Long,
        val modified: Long,
        val directory: Boolean,
    )

    private fun queryMediaStore(prefix: String, hits: MutableMap<String, Row>) {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$prefix/%"),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val iRel = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            while (c.moveToNext() && hits.size < 300) {
                val name = c.getString(iName) ?: continue
                val rel = c.getString(iRel) ?: "$prefix/"
                val path = "/sdcard/$rel$name"
                hits[path] = Row(
                    name = name,
                    path = path,
                    size = c.getLong(iSize),
                    modified = c.getLong(iDate) * 1000,
                    directory = false,
                )
            }
        }
    }

    private fun queryFileSystem(root: String, subdir: String, hits: MutableMap<String, Row>) {
        val base = File(
            Environment.getExternalStoragePublicDirectory(root),
            subdir,
        )
        if (!base.exists() || !base.canRead()) return
        runCatching {
            base.listFiles()?.take(500)?.forEach { file ->
                if (file.isDirectory) {
                    hits.putIfAbsent(
                        file.absolutePath,
                        Row(file.name + "/", file.absolutePath, 0, file.lastModified(), true),
                    )
                } else {
                    hits.putIfAbsent(
                        file.absolutePath,
                        Row(file.name, file.absolutePath, file.length(), file.lastModified(), false),
                    )
                }
            }
        }
    }
}
