package com.dc.andagent.tools

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.dc.andagent.bridge.PermissionBroker
import com.dc.andagent.docs.DocxBuilder
import com.dc.andagent.docs.PptxBuilder
import com.dc.andagent.docs.XlsxCraftBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File

private fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
private fun JsonElement?.arr(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonObject.text(key: String): String = this[key].str().orEmpty()
private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
private fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

class DocumentCreateTool(
    private val context: Context,
    private val permissions: PermissionBroker,
    private val format: String,
) : Tool {
    override val name = "${format}_create"
    override val description = when (format) {
        "docx" -> "创建 Word 文档并保存到 Downloads/andagent/。content_spec_json 是 JSON 字符串：{\"title\":\"季度报告\",\"paragraphs\":[{\"type\":\"heading\",\"text\":\"概览\",\"level\":2},{\"type\":\"p\",\"text\":\"正文\",\"bold\":true,\"italic\":false,\"size\":12,\"color\":\"#336699\",\"align\":\"left\"},{\"type\":\"bullet\",\"text\":\"要点\"},{\"type\":\"numbered\",\"text\":\"第一项\"},{\"type\":\"table\",\"headers\":[\"项目\",\"值\"],\"rows\":[[\"A\",\"1\"]]},{\"type\":\"pageBreak\"},{\"type\":\"image\",\"base64\":\"PNG base64\",\"widthPx\":400,\"heightPx\":300}]}。filename 以 .docx 结尾。"
        "pptx" -> "创建 16:9 PowerPoint 并保存到 Downloads/andagent/。slides_json 是 JSON 数组字符串：[{\"title\":\"季度报告\",\"bullets\":[\"要点一\",\"要点二\"],\"notes\":\"说明\"}]。notes 作为页面底部说明文字；也可传 texts:[{x,y,width,height,text,size,bold}]，坐标单位英寸。filename 以 .pptx 结尾。"
        else -> "创建 Excel 工作簿并保存到 Downloads/andagent/。sheets_json 是 JSON 数组字符串：[{\"name\":\"统计\",\"rows\":[[\"项目\",\"数量\"],[\"A\",12],[\"B\",\"=B2*2\"]],\"columnWidths\":{\"1\":20,\"2\":12}}]。数字自动为数值单元格，= 开头字符串是公式，首行加粗。filename 以 .xlsx 结尾。"
    }
    private val specKey = when (format) { "docx" -> "content_spec_json"; "pptx" -> "slides_json"; else -> "sheets_json" }
    override val parameters = schemaOf(
        "filename" to strParam("输出文件名，例如 报告.$format"),
        specKey to strParam("按 description 中 schema 编码的 JSON 字符串"),
        required = listOf("filename", specKey),
    )
    override suspend fun execute(args: JsonObject): String = runCatching {
        val filename = safeFilename(args.strArg("filename") ?: error("缺少 filename"))
        val raw = args.strArg(specKey) ?: error("缺少 $specKey")
        require(raw.length <= 2_000_000) { "规格超过 2MB" }
        val spec = JsonFixer.parse(raw)
        val out = ByteArrayOutputStream()
        when (format) {
            "docx" -> {
                val root = spec as? JsonObject ?: error("文档规格必须是 JSON 对象")
                val doc = DocxBuilder()
                root.text("title").takeIf { it.isNotBlank() }?.let { doc.heading(it) }
                root["paragraphs"].arr().forEach { item ->
                    val p = item.obj()
                    when (p.text("type")) {
                        "heading" -> doc.heading(p.text("text"), p.number("level") ?: 1)
                        "p", "paragraph" -> doc.paragraph(p.text("text"), p.flag("bold"), p.flag("italic"), p.number("size"), p["color"].str(), p.text("align"))
                        "bullet" -> doc.bullet(p.text("text"))
                        "numbered" -> doc.numbered(p.text("text"))
                        "table" -> doc.table(p["headers"].arr().map { it.str().orEmpty() }, p["rows"].arr().map { row -> row.arr().map { it.str().orEmpty() } })
                        "pageBreak" -> doc.pageBreak()
                        "image" -> doc.image(p.text("base64"), p.number("widthPx") ?: 400, p.number("heightPx") ?: 300)
                        else -> error("未知段落类型：${p.text("type")}")
                    }
                }
                doc.write(out)
            }
            "pptx" -> {
                val ppt = PptxBuilder()
                for (item in spec.arr()) {
                    val s = item.obj(); val slide = ppt.slide().title(s.text("title"))
                    s["bullets"].arr().forEach { slide.bullet(it.str().orEmpty()) }
                    s["texts"].arr().forEach { item2 ->
                        val t = item2.obj()
                        fun f(k: String, default: Float) = (t[k] as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: default
                        slide.text(f("x", 0.6f), f("y", 1.5f), f("width", 10f), f("height", 1f), t.text("text"), t.number("size") ?: 18, t.flag("bold"))
                    }
                    s.text("notes").takeIf { it.isNotBlank() }?.let { slide.text(.7f, 6.8f, 12f, .4f, it, 12) }
                }
                ppt.write(out)
            }
            else -> {
                val book = XlsxCraftBuilder()
                for (item in spec.arr()) {
                    val s = item.obj()
                    book.sheet(s.text("name")) {
                        s["rows"].arr().forEach { rowElement ->
                            val values = rowElement.arr().map { cell ->
                                val v = cell as? JsonPrimitive
                                when {
                                    v == null || v.contentOrNull == null -> null
                                    v.isString -> v.content
                                    v.booleanOrNull != null -> v.booleanOrNull
                                    v.doubleOrNull != null -> v.doubleOrNull
                                    else -> v.content
                                }
                            }
                            row(*values.toTypedArray())
                        }
                        s["columnWidths"].obj().forEach { (index, value) ->
                            val col = index.toIntOrNull() ?: return@forEach
                            val width = (value as? JsonPrimitive)?.doubleOrNull ?: return@forEach
                            columnWidth(col, width)
                        }
                    }
                }
                book.write(out)
            }
        }
        val bytes = out.toByteArray()
        require(bytes.size <= 30 * 1024 * 1024) { "输出超过 30MB" }
        save(filename, bytes)
    }.getOrElse { "ERROR: 文档生成失败：${it.message ?: it.javaClass.simpleName}" }

    private fun safeFilename(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
            .replace(Regex("[\\u0000-\\u001F<>:\"|?*]"), "_").take(150)
        require(base.isNotBlank() && base != "." && base != "..") { "文件名无效" }
        return if (base.endsWith(".$format", true)) base else "$base.$format"
    }
    private suspend fun save(name: String, bytes: ByteArray): String {
        val mime = when (format) {
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/andagent")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: error("MediaStore 拒绝创建文件")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法打开输出流")
                values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                val actual = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: name
                return buildJsonObject { put("path", "/sdcard/Download/andagent/$actual"); put("uri", uri.toString()); put("bytes", bytes.size) }.toString()
            } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
        }
        require(permissions.ensure(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))) { "需要存储写入权限" }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "andagent")
        require(dir.isDirectory || dir.mkdirs()) { "无法创建输出目录" }
        val file = File(dir, name)
        file.writeBytes(bytes)
        return buildJsonObject { put("path", file.absolutePath); put("bytes", bytes.size) }.toString()
    }
}
