package com.dc.andagent.docs

import java.io.File
import java.io.OutputStream

class PptxBuilder {
    private val slides = mutableListOf<Slide>()
    fun slide(): Slide = Slide().also(slides::add)
    inner class Slide {
        private val shapes = mutableListOf<Shape>()
        fun title(value: String) = apply { shapes += Shape(.6f, .35f, 12.1f, 1.1f, value, 32, true, false) }
        fun bullet(value: String) = apply { shapes += Shape(.85f, 1.6f + shapes.count { it.bullet } * .7f, 11.5f, .65f, value, 22, false, true) }
        fun text(x: Float, y: Float, width: Float, height: Float, value: String, size: Int = 18, bold: Boolean = false) = apply {
            shapes += Shape(x, y, width, height, value, size, bold, false)
        }
        internal fun xml(): String = XML_DECL + "<p:sld xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"$OFFICE_REL\"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>" + shapes.mapIndexed { i, s ->
            val x = (s.x.coerceAtLeast(0f) * 914400).toLong(); val y = (s.y.coerceAtLeast(0f) * 914400).toLong()
            val w = (s.w.coerceAtLeast(.01f) * 914400).toLong(); val h = (s.h.coerceAtLeast(.01f) * 914400).toLong()
            "<p:sp><p:nvSpPr><p:cNvPr id=\"${i + 2}\" name=\"Text ${i + 1}\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$w\" cy=\"$h\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:pPr${if (s.bullet) " marL=\"342900\" indent=\"-285750\"" else ""}>${if (s.bullet) "<a:buChar char=\"•\"/>" else "<a:buNone/>"}</a:pPr><a:r><a:rPr lang=\"zh-CN\" sz=\"${s.size.coerceIn(1, 96) * 100}\"${if (s.bold) " b=\"1\"" else ""}/><a:t>${esc(s.value)}</a:t></a:r><a:endParaRPr lang=\"zh-CN\"/></a:p></p:txBody></p:sp>"
        }.joinToString("") + "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
    }
    private data class Shape(val x: Float, val y: Float, val w: Float, val h: Float, val value: String, val size: Int, val bold: Boolean, val bullet: Boolean)
    private fun pack(): OoxmlPackage {
        val p = OoxmlPackage()
        val all = if (slides.isEmpty()) listOf(slide()) else slides
        val overrides = linkedMapOf("ppt/presentation.xml" to "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml", "ppt/slideMasters/slideMaster1.xml" to "application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml", "ppt/slideLayouts/slideLayout1.xml" to "application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml", "ppt/theme/theme1.xml" to "application/vnd.openxmlformats-officedocument.theme+xml")
        all.indices.forEach { overrides["ppt/slides/slide${it + 1}.xml"] = "application/vnd.openxmlformats-officedocument.presentationml.slide+xml" }
        p.xml("[Content_Types].xml", contentTypes(mapOf("rels" to "application/vnd.openxmlformats-package.relationships+xml", "xml" to "application/xml"), overrides))
        p.xml("_rels/.rels", relationships(Triple("rId1", "officeDocument", "ppt/presentation.xml")))
        p.xml("ppt/presentation.xml", XML_DECL + "<p:presentation xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"$OFFICE_REL\"><p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst><p:sldIdLst>" + all.indices.joinToString("") { "<p:sldId id=\"${it + 256}\" r:id=\"rId${it + 2}\"/>" } + "</p:sldIdLst><p:sldSz cx=\"12192000\" cy=\"6858000\" type=\"screen16x9\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>")
        p.xml("ppt/_rels/presentation.xml.rels", relationships(*listOf(Triple("rId1", "slideMaster", "slideMasters/slideMaster1.xml")).plus(all.indices.map { Triple("rId${it + 2}", "slide", "slides/slide${it + 1}.xml") }).toTypedArray()))
        all.forEachIndexed { i, slide ->
            p.xml("ppt/slides/slide${i + 1}.xml", slide.xml())
            p.xml("ppt/slides/_rels/slide${i + 1}.xml.rels", relationships(Triple("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml")))
        }
        p.xml("ppt/slideMasters/slideMaster1.xml", XML_DECL + "<p:sldMaster xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"$OFFICE_REL\"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld><p:clrMap accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" bg1=\"lt1\" bg2=\"lt2\" folHlink=\"folHlink\" hlink=\"hlink\" tx1=\"dk1\" tx2=\"dk2\"/><p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst></p:sldMaster>")
        p.xml("ppt/slideMasters/_rels/slideMaster1.xml.rels", relationships(Triple("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml"), Triple("rId2", "theme", "../theme/theme1.xml")))
        p.xml("ppt/slideLayouts/slideLayout1.xml", XML_DECL + "<p:sldLayout xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"$OFFICE_REL\" type=\"blank\" preserve=\"1\"><p:cSld name=\"Blank\"><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>")
        p.xml("ppt/slideLayouts/_rels/slideLayout1.xml.rels", relationships(Triple("rId1", "slideMaster", "../slideMasters/slideMaster1.xml")))
        p.xml("ppt/theme/theme1.xml", XML_DECL + "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"andagent\"><a:themeElements><a:clrScheme name=\"Office\"><a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1><a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>" + listOf("dk2" to "44546A", "lt2" to "E7E6E6", "accent1" to "4472C4", "accent2" to "ED7D31", "accent3" to "A5A5A5", "accent4" to "FFC000", "accent5" to "5B9BD5", "accent6" to "70AD47", "hlink" to "0563C1", "folHlink" to "954F72").joinToString("") { "<a:${it.first}><a:srgbClr val=\"${it.second}\"/></a:${it.first}>" } + "</a:clrScheme><a:fontScheme name=\"Office\"><a:majorFont><a:latin typeface=\"Arial\"/></a:majorFont><a:minorFont><a:latin typeface=\"Arial\"/></a:minorFont></a:fontScheme><a:fmtScheme name=\"Office\"><a:fillStyleLst><a:solidFill><a:schemeClr val=\"accent1\"/></a:solidFill></a:fillStyleLst><a:lnStyleLst><a:ln w=\"9525\"><a:solidFill><a:schemeClr val=\"accent1\"/></a:solidFill></a:ln></a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr val=\"lt1\"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>")
        return p
    }
    fun write(output: OutputStream) = pack().write(output)
    fun saveTo(file: File) = pack().saveTo(file)
}
