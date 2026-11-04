package io.nous.andagent.docs

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

class DocumentBuildersTest {
    private val output = File("/tmp/andagent-r8-samples").apply { mkdirs() }
    private fun verify(file: File, expected: List<String>) {
        assertTrue(file.length() > 500)
        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue(names.containsAll(expected + "[Content_Types].xml" + "_rels/.rels"))
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            names.filter { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { name ->
                zip.getInputStream(zip.getEntry(name)).use { factory.newDocumentBuilder().parse(it) }
            }
            val types = zip.getInputStream(zip.getEntry("[Content_Types].xml")).bufferedReader().readText()
            expected.filter { it.endsWith(".xml") }.forEach { assertTrue("Missing content type: $it", types.contains("/$it")) }
        }
    }
    @Test fun docx() {
        val file = File(output, "sample.docx")
        DocxBuilder().apply { heading("季度 & 报告"); paragraph("正文 <测试>", bold = true); bullet("要点"); numbered("第一"); table(listOf("项目", "数值"), listOf(listOf("A", "1"))); pageBreak() }.saveTo(file)
        verify(file, listOf("word/document.xml", "word/styles.xml", "word/numbering.xml", "docProps/core.xml"))
        val duplicate = File(output, "sample-copy.docx")
        DocxBuilder().apply { heading("季度 & 报告"); paragraph("正文 <测试>", bold = true); bullet("要点"); numbered("第一"); table(listOf("项目", "数值"), listOf(listOf("A", "1"))); pageBreak() }.saveTo(duplicate)
        assertArrayEquals(file.readBytes(), duplicate.readBytes())
    }
    @Test fun pptx() {
        val file = File(output, "sample.pptx")
        PptxBuilder().apply { slide().title("标题 & 测试").bullet("要点一").bullet("要点二"); slide().title("第二页").text(1f, 2f, 4f, 1f, "说明") }.saveTo(file)
        verify(file, listOf("ppt/presentation.xml", "ppt/slideMasters/slideMaster1.xml", "ppt/slideLayouts/slideLayout1.xml", "ppt/theme/theme1.xml", "ppt/slides/slide1.xml", "ppt/slides/slide2.xml"))
    }
    @Test fun xlsx() {
        val file = File(output, "sample.xlsx")
        XlsxCraftBuilder().sheet("统计") { row("项目", "数值"); row("A", 1); row("B", "=B2*2"); columnWidth(1, 20.0) }.sheet("更多") { row("X", 2) }.saveTo(file)
        verify(file, listOf("xl/workbook.xml", "xl/styles.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml"))
    }
}
