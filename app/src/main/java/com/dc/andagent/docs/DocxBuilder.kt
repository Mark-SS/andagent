package com.dc.andagent.docs

import java.io.File
import java.io.OutputStream
import java.util.Base64

class DocxBuilder {
    private val body = StringBuilder()
    private val images = mutableListOf<ByteArray>()
    private fun run(text: String, bold: Boolean = false, italic: Boolean = false, size: Int? = null, color: String? = null): String {
        val props = buildString {
            if (bold) append("<w:b/>")
            if (italic) append("<w:i/>")
            if (size != null) append("<w:sz w:val=\"${size.coerceIn(1, 144) * 2}\"/>")
            if (color != null && color.matches(Regex("#[0-9A-Fa-f]{6}"))) append("<w:color w:val=\"${color.drop(1)}\"/>")
        }
        return "<w:r><w:rPr>$props</w:rPr><w:t xml:space=\"preserve\">${esc(text)}</w:t></w:r>"
    }
    fun paragraph(text: String, bold: Boolean = false, italic: Boolean = false, size: Int? = null, color: String? = null, align: String = "left") = apply {
        val jc = when (align.lowercase()) { "center" -> "center"; "right" -> "right"; "justify" -> "both"; else -> "left" }
        body.append("<w:p><w:pPr><w:jc w:val=\"$jc\"/></w:pPr>${run(text, bold, italic, size, color)}</w:p>")
    }
    fun heading(text: String, level: Int = 1) = apply {
        body.append("<w:p><w:pPr><w:pStyle w:val=\"Heading${level.coerceIn(1, 3)}\"/></w:pPr>${run(text)}</w:p>")
    }
    fun bullet(text: String) = listItem(text, 1)
    fun numbered(text: String) = listItem(text, 2)
    private fun listItem(text: String, id: Int) = apply {
        body.append("<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"$id\"/></w:numPr></w:pPr>${run(text)}</w:p>")
    }
    fun pageBreak() = apply { body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>") }
    fun table(headers: List<String>, rows: List<List<String>>) = apply {
        val cols = maxOf(headers.size, rows.maxOfOrNull { it.size } ?: 0, 1)
        body.append("<w:tbl><w:tblPr><w:tblBorders>")
        for (edge in listOf("top", "left", "bottom", "right", "insideH", "insideV")) body.append("<w:$edge w:val=\"single\" w:sz=\"4\"/>")
        body.append("</w:tblBorders></w:tblPr><w:tblGrid>")
        repeat(cols) { body.append("<w:gridCol w:w=\"${9000 / cols}\"/>") }
        body.append("</w:tblGrid>")
        fun row(cells: List<String>, header: Boolean) {
            body.append("<w:tr>")
            for (i in 0 until cols) {
                body.append("<w:tc><w:tcPr><w:tcW w:w=\"${9000 / cols}\" w:type=\"dxa\"/>")
                if (header) body.append("<w:shd w:fill=\"DCE6F1\"/>")
                body.append("</w:tcPr><w:p>${run(cells.getOrElse(i) { "" }, bold = header)}</w:p></w:tc>")
            }
            body.append("</w:tr>")
        }
        if (headers.isNotEmpty()) row(headers, true)
        rows.forEach { row(it, false) }
        body.append("</w:tbl>")
    }
    fun image(base64: String, widthPx: Int = 400, heightPx: Int = 300) = apply {
        val bytes = Base64.getDecoder().decode(base64.substringAfter("base64,", base64))
        require(bytes.size <= 10 * 1024 * 1024 && bytes.size > 8) { "图片大小无效" }
        images += bytes
        val id = images.size
        val cx = widthPx.coerceIn(1, 4000) * 9525L
        val cy = heightPx.coerceIn(1, 4000) * 9525L
        body.append("<w:p><w:r><w:drawing><wp:inline xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"><wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$id\" name=\"Image $id\"/><a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:nvPicPr><pic:cNvPr id=\"$id\" name=\"image$id.png\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"rId${id + 3}\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>")
    }
    private fun pack(): OoxmlPackage {
        val p = OoxmlPackage()
        p.xml("[Content_Types].xml", contentTypes(mapOf("rels" to "application/vnd.openxmlformats-package.relationships+xml", "xml" to "application/xml", "png" to "image/png"), mapOf(
            "word/document.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
            "word/styles.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml",
            "word/numbering.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml",
            "docProps/core.xml" to "application/vnd.openxmlformats-package.core-properties+xml")))
        p.xml("_rels/.rels", relationships(Triple("rId1", "officeDocument", "word/document.xml"), Triple("rId2", "metadata/core-properties", "docProps/core.xml")))
        p.xml("word/_rels/document.xml.rels", relationships(*listOf(Triple("rId1", "styles", "styles.xml"), Triple("rId2", "numbering", "numbering.xml"), Triple("rId3", "theme", "theme/theme1.xml")).filterNot { it.second == "theme" }.plus(images.indices.map { Triple("rId${it + 4}", "image", "media/image${it + 1}.png") }).toTypedArray()))
        p.xml("word/document.xml", XML_DECL + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:r=\"$OFFICE_REL\"><w:body>$body<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr></w:body></w:document>")
        p.xml("word/styles.xml", XML_DECL + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>" + (1..3).joinToString("") { "<w:style w:type=\"paragraph\" w:styleId=\"Heading$it\"><w:name w:val=\"heading $it\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:keepNext/></w:pPr><w:rPr><w:b/><w:sz w:val=\"${40 - it * 4}\"/></w:rPr></w:style>" } + "</w:styles>")
        p.xml("word/numbering.xml", XML_DECL + "<w:numbering xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" + listOf("bullet" to "•", "decimal" to "%1.").mapIndexed { i, (fmt, text) -> "<w:abstractNum w:abstractNumId=\"$i\"><w:multiLevelType w:val=\"singleLevel\"/><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"$fmt\"/><w:lvlText w:val=\"$text\"/></w:lvl></w:abstractNum><w:num w:numId=\"${i + 1}\"><w:abstractNumId w:val=\"$i\"/></w:num>" }.joinToString("") + "</w:numbering>")
        p.xml("docProps/core.xml", XML_DECL + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:creator>andagent</dc:creator></cp:coreProperties>")
        images.forEachIndexed { i, b -> p.binary("word/media/image${i + 1}.png", b) }
        return p
    }
    fun write(output: OutputStream) = pack().write(output)
    fun saveTo(file: File) = pack().saveTo(file)
}
