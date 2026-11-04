package io.nous.andagent.docs

import java.io.File
import java.io.OutputStream

class XlsxCraftBuilder {
    private val sheets = mutableListOf<Sheet>()
    fun sheet(name: String, block: Sheet.() -> Unit): XlsxCraftBuilder = apply {
        val clean = name.filterNot { it in charArrayOf(':', '/', '?', '*', '[', ']', '\\') }.trim().trim { it == 39.toChar() }.take(31).ifBlank { "Sheet${sheets.size + 1}" }
        var unique = clean
        var suffix = 2
        while (sheets.any { it.name.equals(unique, true) }) {
            val tail = " ($suffix)"
            unique = clean.take(31 - tail.length) + tail
            suffix++
        }
        Sheet(unique).apply(block).also(sheets::add)
    }
    inner class Sheet(val name: String) {
        internal val rows = mutableListOf<List<Any?>>()
        internal val widths = linkedMapOf<Int, Double>()
        fun row(vararg cells: Any?) { rows += cells.toList() }
        fun columnWidth(column: Int, width: Double) { require(column > 0); widths[column] = width.coerceIn(1.0, 255.0) }
        internal fun xml(): String = buildString {
            append(XML_DECL).append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><cols>")
            widths.forEach { (col, width) -> append("<col min=\"$col\" max=\"$col\" width=\"$width\" customWidth=\"1\"/>") }
            append("</cols><sheetData>")
            rows.forEachIndexed { r, cells ->
                append("<row r=\"${r + 1}\">")
                cells.forEachIndexed { c, value ->
                    val ref = columnName(c + 1) + (r + 1)
                    val style = if (r == 0) " s=\"1\"" else ""
                    when (value) {
                        null -> append("<c r=\"$ref\"$style/>")
                        is Number -> if (value.toDouble().isFinite()) append("<c r=\"$ref\"$style><v>${value}</v></c>") else append("<c r=\"$ref\"$style/>")
                        is Boolean -> append("<c r=\"$ref\"$style t=\"b\"><v>${if (value) 1 else 0}</v></c>")
                        else -> {
                            val text = value.toString()
                            if (text.startsWith("=") && text.length > 1) append("<c r=\"$ref\"$style><f>${esc(text.drop(1))}</f><v>0</v></c>")
                            else append("<c r=\"$ref\"$style t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(text)}</t></is></c>")
                        }
                    }
                }
                append("</row>")
            }
            append("</sheetData></worksheet>")
        }
    }
    private fun columnName(index: Int): String { var n = index; var s = ""; while (n > 0) { n--; s = ('A' + n % 26) + s; n /= 26 }; return s }
    private fun pack(): OoxmlPackage {
        val p = OoxmlPackage()
        val all = if (sheets.isEmpty()) listOf(Sheet("Sheet1")) else sheets
        val overrides = linkedMapOf("xl/workbook.xml" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml", "xl/styles.xml" to "application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml")
        all.indices.forEach { overrides["xl/worksheets/sheet${it + 1}.xml"] = "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml" }
        p.xml("[Content_Types].xml", contentTypes(mapOf("rels" to "application/vnd.openxmlformats-package.relationships+xml", "xml" to "application/xml"), overrides))
        p.xml("_rels/.rels", relationships(Triple("rId1", "officeDocument", "xl/workbook.xml")))
        p.xml("xl/workbook.xml", XML_DECL + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"$OFFICE_REL\"><sheets>" + all.mapIndexed { i, sheet -> "<sheet name=\"${esc(sheet.name.take(31).ifBlank { "Sheet${i + 1}" })}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") + "</sheets></workbook>")
        p.xml("xl/_rels/workbook.xml.rels", relationships(*all.indices.map { Triple("rId${it + 1}", "worksheet", "worksheets/sheet${it + 1}.xml") }.plus(Triple("rId${all.size + 1}", "styles", "styles.xml")).toTypedArray()))
        all.forEachIndexed { i, s -> p.xml("xl/worksheets/sheet${i + 1}.xml", s.xml()) }
        p.xml("xl/styles.xml", XML_DECL + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills><borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>")
        return p
    }
    fun write(output: OutputStream) = pack().write(output)
    fun saveTo(file: File) = pack().saveTo(file)
}
