package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** R39: clipboard_write_rich 的类型映射纯逻辑（无 Android 依赖部分）。 */
class ClipboardRichToolsTest {
    @Test
    fun kindNormalization() {
        assertEquals("text", ClipboardWriteRichTool.normalizeKind("TEXT"))
        assertEquals("image", ClipboardWriteRichTool.normalizeKind("Image"))
        assertEquals("html", ClipboardWriteRichTool.normalizeKind("html"))
        assertEquals("uri", ClipboardWriteRichTool.normalizeKind("uri"))
        assertNull(ClipboardWriteRichTool.normalizeKind("file"))
        assertNull(ClipboardWriteRichTool.normalizeKind(null))
    }

    @Test
    fun imageMimeSniffing() {
        // JPEG magic: FF D8 FF
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertEquals("image/jpeg", ClipboardWriteRichTool.detectImageMime(jpeg))
        // PNG magic: 89 50 4E 47
        val png = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
        assertEquals("image/png", ClipboardWriteRichTool.detectImageMime(png))
        assertEquals("image/png", ClipboardWriteRichTool.detectImageMime(ByteArray(0)))
    }
}
