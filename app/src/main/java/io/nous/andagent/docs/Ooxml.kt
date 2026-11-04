package io.nous.andagent.docs

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal const val OFFICE_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
internal const val PACKAGE_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
internal const val CONTENT_TYPES = "http://schemas.openxmlformats.org/package/2006/content-types"
internal const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

internal fun esc(value: String): String = buildString {
    value.codePoints().forEach { cp ->
        when (cp) {
            38 -> append("&amp;")
            60 -> append("&lt;")
            62 -> append("&gt;")
            34 -> append("&quot;")
            39 -> append("&apos;")
            else -> if (cp == 9 || cp == 10 || cp == 13 || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF) appendCodePoint(cp)
        }
    }
}

internal class OoxmlPackage {
    private val parts = linkedMapOf<String, ByteArray>()
    fun xml(path: String, content: String) { parts[path] = content.toByteArray(Charsets.UTF_8) }
    fun binary(path: String, bytes: ByteArray) { parts[path] = bytes }
    fun write(output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            for ((path, bytes) in parts) {
                val entry = ZipEntry(path).apply { time = 315532800000L }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
    fun saveTo(file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().use(::write)
    }
}

internal fun contentTypes(defaults: Map<String, String>, overrides: Map<String, String>): String =
    XML_DECL + "<Types xmlns=\"$CONTENT_TYPES\">" +
        defaults.entries.joinToString("") { "<Default Extension=\"${esc(it.key)}\" ContentType=\"${esc(it.value)}\"/>" } +
        overrides.entries.joinToString("") { "<Override PartName=\"/${esc(it.key)}\" ContentType=\"${esc(it.value)}\"/>" } +
        "</Types>"

internal fun relationships(vararg entries: Triple<String, String, String>): String = XML_DECL +
    "<Relationships xmlns=\"$PACKAGE_REL\">" + entries.joinToString("") { (id, type, target) ->
        "<Relationship Id=\"${esc(id)}\" Type=\"${if (type.startsWith("metadata/")) PACKAGE_REL else OFFICE_REL}/${esc(type)}\" Target=\"${esc(target)}\"/>"
    } + "</Relationships>"
