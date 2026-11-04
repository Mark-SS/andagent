package io.nous.andagent.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** R17 file-management completion (7 tools). */
val FILE_OPS_TOOLS = listOf(
    "file_copy", "file_move", "file_delete", "file_rename",
    "file_mkdir", "file_hash", "file_info",
)

/** R16 ZIP suite (5 tools), implemented with the platform ZIP APIs. */
val ZIP_TOOLS = listOf("zip_create", "zip_list", "zip_extract", "zip_add", "zip_test")

private val BLOCKED_PREFIXES = listOf("/system", "/vendor", "/proc", "/dev", "/sys", "/apex", "/data/data")

private fun resolveFile(raw: String?): File {
    val path = raw?.trim() ?: error("缺少 path")
    require(path.startsWith("/")) { "仅支持绝对文件路径（content:// 请用 SAF 工具）" }
    val file = File(path).canonicalFile
    val appPrefix = "/data/user/0/io.nous.andagent"
    if (BLOCKED_PREFIXES.any { file.path.startsWith(it) } && !file.path.startsWith(appPrefix))
        error("路径位于系统保护目录，拒绝操作：${file.path}")
    return file
}

private fun requireSource(path: String): File {
    val file = resolveFile(path)
    require(file.exists()) { "文件不存在：${file.path}" }
    return file
}

private fun jsonPaths(raw: String?): List<String> = runCatching {
    (kotlinx.serialization.json.Json.parseToJsonElement(raw ?: "[]") as? JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
}.getOrElse { error("sources_json 必须是 JSON 字符串数组") }

class FileOpsTool(private val context: Context, override val name: String) : Tool {
    override val description: String = when (name) {
        "file_copy" -> "复制文件或目录到目标路径（覆盖需 confirm=true）。"
        "file_move" -> "移动/重命名路径到目标（覆盖需 confirm=true）。"
        "file_delete" -> "删除文件或空目录；递归删除目录需 recursive=true 且 confirm=true。"
        "file_rename" -> "重命名文件或目录（同级），需要 confirm=true。"
        "file_mkdir" -> "创建目录（可自动创建多级父目录）。"
        "file_hash" -> "计算文件哈希：md5/sha1/sha256。"
        "file_info" -> "查看文件详细信息：大小、时间、权限、MIME、目录子项数。"
        else -> "文件管理工具"
    }

    override val parameters: JsonObject = when (name) {
        "file_copy", "file_move" -> schemaOf(
            "source" to strParam("源绝对路径"),
            "dest" to strParam("目标绝对路径"),
            "confirm" to boolParam("目标已存在时须为 true"),
            required = listOf("source", "dest"),
        )
        "file_delete" -> schemaOf(
            "path" to strParam("要删除的绝对路径"),
            "recursive" to boolParam("递归删除非空目录，默认 false"),
            "confirm" to boolParam("必须设为 true 才执行删除"),
            required = listOf("path", "confirm"),
        )
        "file_rename" -> schemaOf(
            "path" to strParam("原路径"),
            "name" to strParam("新名称（不能含路径分隔符）"),
            "confirm" to boolParam("必须设为 true"),
            required = listOf("path", "name", "confirm"),
        )
        "file_mkdir" -> schemaOf("path" to strParam("要创建的目录绝对路径"), required = listOf("path"))
        "file_hash" -> schemaOf(
            "path" to strParam("文件绝对路径"),
            "algorithm" to strParam("md5|sha1|sha256，默认 sha256"),
            required = listOf("path"),
        )
        "file_info" -> schemaOf("path" to strParam("路径"), required = listOf("path"))
        else -> schemaOf()
    }

    override val destructive: Boolean = name in setOf("file_copy", "file_move", "file_delete", "file_rename", "file_mkdir")

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching {
            when (name) {
                "file_copy" -> copyOrMove(args, move = false)
                "file_move" -> copyOrMove(args, move = true)
                "file_delete" -> delete(args)
                "file_rename" -> rename(args)
                "file_mkdir" -> mkdir(args)
                "file_hash" -> hash(args)
                "file_info" -> info(args)
                else -> "ERROR: 未知文件工具 $name"
            }
        }.getOrElse { "ERROR: $name 失败：${it.message ?: it.javaClass.simpleName}" }
    }

    private fun copyOrMove(args: JsonObject, move: Boolean): String {
        val source = requireSource(args.strArg("source") ?: error("缺少 source"))
        val dest = resolveFile(args.strArg("dest") ?: error("缺少 dest"))
        if (source == dest) return "ERROR: 源与目标相同"
        if (dest.exists() && args.boolArg("confirm") != true)
            return "ERROR: 目标已存在，确认覆盖请加 confirm=true"
        if (source.isDirectory && dest.isFile) return "ERROR: 不能用文件覆盖目录"
        dest.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("无法创建目标目录：${it.path}") }
        if (move) {
            if (source.renameTo(dest)) return "WRITTEN: 已移动到 ${dest.path}"
            if (dest.exists()) dest.delete()
        }
        copyRecursive(source, dest)
        if (move) deleteRecursive(source)
        return "WRITTEN: 已${if (move) "移动" else "复制"} ${source.name} → ${dest.path}"
    }

    private fun copyRecursive(source: File, dest: File) {
        if (source.isDirectory) {
            if (!dest.exists() && !dest.mkdirs()) error("无法创建目录：${dest.path}")
            source.listFiles()?.forEach { child -> copyRecursive(child, File(dest, child.name)) }
        } else {
            if (dest.exists() && !dest.delete()) error("无法覆盖：${dest.path}")
            source.inputStream().use { input -> FileOutputStream(dest).use { output -> input.copyTo(output) } }
        }
    }

    private fun delete(args: JsonObject): String {
        if (args.boolArg("confirm") != true) return "ERROR: 删除操作需要 confirm=true"
        val file = requireSource(args.strArg("path") ?: error("缺少 path"))
        val recursive = args.boolArg("recursive") == true
        if (file.isDirectory && file.listFiles()?.isNotEmpty() == true && !recursive)
            return "ERROR: 目录非空；递归删除需 recursive=true"
        if (file.path in listOf(context.filesDir.canonicalPath, context.getExternalFilesDir(null)?.canonicalPath))
            return "ERROR: 拒绝删除应用根目录"
        val ok = if (recursive) deleteRecursive(file) else file.delete()
        return if (ok) "DELETED: ${file.path}" else "ERROR: 删除失败（权限或占用）"
    }

    private fun deleteRecursive(file: File): Boolean {
        if (file.isDirectory) file.listFiles()?.forEach { if (!deleteRecursive(it)) return false }
        return file.delete()
    }

    private fun rename(args: JsonObject): String {
        if (args.boolArg("confirm") != true) return "ERROR: 重命名需要 confirm=true"
        val file = requireSource(args.strArg("path") ?: error("缺少 path"))
        val newName = args.strArg("name") ?: error("缺少 name")
        if (newName.contains('/') || newName.contains('\\') || newName == "." || newName == "..")
            return "ERROR: name 不能包含路径分隔符"
        val target = File(file.parentFile, newName)
        if (target.exists()) return "ERROR: 目标名称已存在：$newName"
        return if (file.renameTo(target)) "WRITTEN: 已重命名为 ${target.path}" else "ERROR: 重命名失败"
    }

    private fun mkdir(args: JsonObject): String {
        val file = resolveFile(args.strArg("path") ?: error("缺少 path"))
        if (file.exists()) return if (file.isDirectory) "目录已存在：${file.path}" else "ERROR: 同名文件已存在"
        return if (file.mkdirs()) "WRITTEN: 已创建目录 ${file.path}" else "ERROR: 创建目录失败"
    }

    private fun hash(args: JsonObject): String {
        val file = requireSource(args.strArg("path") ?: error("缺少 path"))
        if (!file.isFile) return "ERROR: 哈希仅支持文件"
        if (file.length() > 200 * 1024 * 1024) return "ERROR: 文件超过 200MB，拒绝计算"
        val algorithm = when (args.strArg("algorithm")?.lowercase()) {
            "md5" -> "MD5"; "sha1" -> "SHA-1"; else -> "SHA-256"
        }
        val digest = MessageDigest.getInstance(algorithm)
        FileInputStream(file).use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return buildJsonObject {
            put("path", file.path)
            put("algorithm", algorithm)
            put("size", file.length())
            put("hash", hex)
        }.toString()
    }

    private fun info(args: JsonObject): String {
        val file = requireSource(args.strArg("path") ?: error("缺少 path"))
        return buildJsonObject {
            put("path", file.path)
            put("name", file.name)
            put("isDirectory", file.isDirectory)
            put("size", file.length())
            put("lastModified", file.lastModified())
            put("readable", file.canRead())
            put("writable", file.canWrite())
            put("hidden", file.isHidden)
            put("extension", file.extension)
            put("children", if (file.isDirectory) file.listFiles()?.size ?: 0 else 0)
        }.toString()
    }
}

class ZipTool(override val name: String) : Tool {
    override val description: String = when (name) {
        "zip_create" -> "把多个文件/目录打包为标准 ZIP（覆盖已有文件需 confirm=true）。"
        "zip_list" -> "列出 ZIP 内条目名称、原始大小、压缩大小、时间。"
        "zip_extract" -> "解压 ZIP 到目录，带路径穿越防护；覆盖需 confirm=true。"
        "zip_add" -> "向已有 ZIP 追加文件（同名条目会替换）。"
        "zip_test" -> "校验 ZIP 每个条目的 CRC，并检测是否含传统加密条目。"
        else -> "ZIP 工具"
    }

    override val parameters: JsonObject = when (name) {
        "zip_create" -> schemaOf(
            "path" to strParam("目标 .zip 绝对路径"),
            "sources_json" to strParam("源文件/目录路径的 JSON 字符串数组"),
            "confirm" to boolParam("目标已存在时须为 true"),
            required = listOf("path", "sources_json"),
        )
        "zip_list" -> schemaOf("path" to strParam("ZIP 路径"), required = listOf("path"))
        "zip_extract" -> schemaOf(
            "path" to strParam("ZIP 路径"),
            "dest" to strParam("解压目标目录"),
            "entries_json" to strParam("可选：只解压指定条目名的 JSON 数组"),
            "confirm" to boolParam("目标目录已有同名文件时须为 true"),
            required = listOf("path", "dest"),
        )
        "zip_add" -> schemaOf(
            "path" to strParam("已有 ZIP 路径"),
            "sources_json" to strParam("要追加的文件/目录 JSON 数组"),
            required = listOf("path", "sources_json"),
        )
        "zip_test" -> schemaOf("path" to strParam("ZIP 路径"), required = listOf("path"))
        else -> schemaOf()
    }

    override val destructive: Boolean = name in setOf("zip_create", "zip_extract", "zip_add")

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching {
            when (name) {
                "zip_create" -> create(args)
                "zip_list" -> list(args)
                "zip_extract" -> extract(args)
                "zip_add" -> add(args)
                "zip_test" -> test(args)
                else -> "ERROR: 未知 ZIP 工具 $name"
            }
        }.getOrElse { "ERROR: $name 失败：${it.message ?: it.javaClass.simpleName}" }
    }

    private fun encrypted(file: File): Boolean {
        // Read local file headers: bit 0 of the general-purpose flag marks
        // traditional PKZIP encryption, which java.util.zip cannot decode.
        val bytes = file.readBytes()
        var offset = 0
        while (offset + 8 <= bytes.size) {
            if (bytes[offset] == 0x50.toByte() && bytes[offset + 1] == 0x4B.toByte() &&
                bytes[offset + 2] == 0x03.toByte() && bytes[offset + 3] == 0x04.toByte()
            ) {
                val flags = (bytes[offset + 6].toInt() and 0xff) or ((bytes[offset + 7].toInt() and 0xff) shl 8)
                if (flags and 1 == 1) return true
                val nameLen = (bytes[offset + 26].toInt() and 0xff) or ((bytes[offset + 27].toInt() and 0xff) shl 8)
                val extraLen = (bytes[offset + 28].toInt() and 0xff) or ((bytes[offset + 29].toInt() and 0xff) shl 8)
                val compressed = (bytes[offset + 18].toInt() and 0xff) or
                    ((bytes[offset + 19].toInt() and 0xff) shl 8) or
                    ((bytes[offset + 20].toInt() and 0xff) shl 16) or
                    ((bytes[offset + 21].toInt() and 0xff) shl 24)
                if (compressed < 0) return false
                offset += 30 + nameLen + extraLen + compressed
            } else offset++
        }
        return false
    }

    private fun writeEntries(out: ZipOutputStream, sources: List<String>, prefix: String = "") {
        sources.forEach { raw ->
            val file = requireSource(raw)
            if (file.isDirectory) {
                file.walkTopDown().filter { it.isFile }.forEach { child ->
                    val relative = prefix + child.relativeTo(file.parentFile).path.replace('\\', '/')
                    putFile(out, child, relative)
                }
            } else putFile(out, file, prefix + file.name)
        }
    }

    private fun putFile(out: ZipOutputStream, file: File, entryName: String) {
        val safe = entryName.replace('\\', '/').removePrefix("/")
        if (safe.contains("..")) error("非法条目名：$entryName")
        out.putNextEntry(ZipEntry(safe).apply { time = file.lastModified() })
        FileInputStream(file).use { it.copyTo(out) }
        out.closeEntry()
    }

    private fun create(args: JsonObject): String {
        val target = resolveFile(args.strArg("path") ?: error("缺少 path"))
        if (target.exists() && args.boolArg("confirm") != true) return "ERROR: ZIP 已存在，覆盖请加 confirm=true"
        val sources = jsonPaths(args.strArg("sources_json"))
        if (sources.isEmpty()) return "ERROR: sources_json 为空"
        target.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("无法创建目标目录") }
        ZipOutputStream(FileOutputStream(target)).use { writeEntries(it, sources) }
        return "WRITTEN: 已创建 ${target.path}（${target.length()} 字节，${sources.size} 个来源）"
    }

    private fun list(args: JsonObject): String {
        val file = requireSource(args.strArg("path") ?: error("缺少 path"))
        if (encrypted(file)) return "ERROR: 该 ZIP 含传统加密条目，平台 ZIP API 无法读取（需要 zip4j 支持）"
        val rows = buildJsonArray {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                var count = 0
                while (entries.hasMoreElements() && count++ < 500) {
                    val e = entries.nextElement()
                    add(buildJsonObject {
                        put("name", e.name)
                        put("size", e.size)
                        put("compressedSize", e.compressedSize)
                        put("directory", e.isDirectory)
                        put("time", e.time)
                        put("crc", e.crc)
                    })
                }
            }
        }
        return buildJsonObject { put("path", file.path); put("count", rows.size); put("entries", rows) }.toString()
    }

    private fun extract(args: JsonObject): String {
        val file = requireSource(args.strArg("path") ?: error("缺少 path"))
        if (encrypted(file)) return "ERROR: 该 ZIP 含加密条目，无法解压（需要 zip4j 支持）"
        val dest = resolveFile(args.strArg("dest") ?: error("缺少 dest"))
        if (!dest.exists() && !dest.mkdirs()) return "ERROR: 无法创建目标目录"
        require(dest.isDirectory) { "dest 必须是目录" }
        val only = jsonPaths(args.strArg("entries_json")).toSet()
        var extracted = 0
        var bytes = 0L
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (only.isNotEmpty() && entry.name !in only) continue
                val target = File(dest, entry.name.replace('\\', '/')).canonicalFile
                require(target.path == dest.canonicalPath || target.path.startsWith(dest.canonicalPath + File.separator)) {
                    "ZIP 路径穿越被拦截：${entry.name}"
                }
                if (entry.isDirectory) { target.mkdirs(); continue }
                if (target.exists() && args.boolArg("confirm") != true)
                    return "ERROR: 目标文件已存在（${target.name}），覆盖请加 confirm=true"
                target.parentFile?.mkdirs()
                zip.getInputStream(entry)?.use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            bytes += n
                            if (bytes > 500 * 1024 * 1024) error("解压数据超过 500MB，已中止")
                            output.write(buffer, 0, n)
                        }
                    }
                }
                extracted++
            }
        }
        return "WRITTEN: 已解压 $extracted 个文件到 ${dest.path}（$bytes 字节）"
    }

    private fun add(args: JsonObject): String {
        val file = requireSource(args.strArg("path") ?: error("缺少 path"))
        if (encrypted(file)) return "ERROR: 加密 ZIP 不支持追加（需要 zip4j 支持）"
        val sources = jsonPaths(args.strArg("sources_json"))
        if (sources.isEmpty()) return "ERROR: sources_json 为空"
        val tmp = File(file.parentFile, "${file.nameWithoutExtension}.tmp.zip")
        val newNames = sources.map { File(it).name }.toSet()
        ZipOutputStream(FileOutputStream(tmp)).use { out ->
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory && entry.name.substringBefore('/') in newNames) continue
                    out.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                    if (!entry.isDirectory) zip.getInputStream(entry)?.use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
            writeEntries(out, sources)
        }
        if (!file.delete() || !tmp.renameTo(file)) return "ERROR: 追加完成但替换原文件失败，临时文件：${tmp.path}"
        return "WRITTEN: 已向 ${file.path} 追加 ${sources.size} 个来源"
    }

    private fun test(args: JsonObject): String {
        val file = requireSource(args.strArg("path") ?: error("缺少 path"))
        if (encrypted(file)) return "ERROR: ZIP 含传统加密条目；本实现仅支持未加密 ZIP（加密支持需 zip4j）"
        var count = 0
        var bad = 0
        ZipInputStream(FileInputStream(file)).use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val entry = stream.nextEntry ?: break
                count++
                if (!entry.isDirectory) {
                    val crc = CRC32()
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        crc.update(buffer, 0, n)
                    }
                    if (entry.crc >= 0 && entry.crc != crc.value) bad++
                }
                stream.closeEntry()
            }
        }
        return buildJsonObject {
            put("path", file.path)
            put("entries", count)
            put("crcFailures", bad)
            put("valid", bad == 0)
        }.toString()
    }
}
