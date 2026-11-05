package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R35: pure-logic coverage for the 8 new tools — no Android framework, no
 * permissions, no device. Runs on the plain JVM (ZXing core is pure Java).
 */
class R35CodecTest {

    /* ----------------------------- AppGuard ----------------------------- */

    @Test
    fun safePackageNameAcceptsNormalPackages() {
        assertTrue(AppGuard.isSafePackageName("com.example.app"))
        assertTrue(AppGuard.isSafePackageName("com.example"))
        assertTrue(AppGuard.isSafePackageName("a.b"))
        assertTrue(AppGuard.isSafePackageName("org.a"))
        assertTrue(AppGuard.isSafePackageName("com.example.app_2"))
    }

    @Test
    fun safePackageNameRejectsInjectionAndMalformed() {
        assertFalse(AppGuard.isSafePackageName(null))
        assertFalse(AppGuard.isSafePackageName(""))
        assertFalse(AppGuard.isSafePackageName("a"))
        assertFalse(AppGuard.isSafePackageName("1com.example"))
        assertFalse(AppGuard.isSafePackageName("com..example"))
        assertFalse(AppGuard.isSafePackageName("com.1x"))
        assertFalse(AppGuard.isSafePackageName("com.exa mple"))
        assertFalse(AppGuard.isSafePackageName("com.example;rm -rf /"))
        assertFalse(AppGuard.isSafePackageName("com.example/../x"))
        assertFalse(AppGuard.isSafePackageName("com.example\n"))
    }

    @Test
    fun protectedPackages() {
        assertTrue(AppGuard.isProtected("android"))
        assertTrue(AppGuard.isProtected("com.dc.andagent"))
        assertTrue(AppGuard.isProtected("com.android.settings"))
        assertTrue(AppGuard.isProtected("android.media"))
        assertTrue(AppGuard.isProtected("com.android.providers.contacts"))
        assertFalse(AppGuard.isProtected("com.example.app"))
    }

    /* ----------------------------- SensorCsv ---------------------------- */

    @Test
    fun csvEscapesCommaQuoteAndNewline() {
        assertEquals("plain", SensorCsv.escape("plain"))
        assertEquals("\"a,b\"", SensorCsv.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", SensorCsv.escape("say \"hi\""))
        assertEquals("\"line1\nline2\"", SensorCsv.escape("line1\nline2"))
        assertEquals("\"cr\r\"", SensorCsv.escape("cr\r"))
    }

    @Test
    fun csvBuildsHeaderAndRows() {
        val csv = SensorCsv.build(
            listOf("t_ms", "x"),
            listOf(listOf("0.000", "1.5"), listOf("20.000", "-2,5")),
        )
        assertEquals("t_ms,x\n0.000,1.5\n20.000,\"-2,5\"\n", csv)
    }

    /* ------------------------------ QrCodec ----------------------------- */

    private fun renderToPixels(matrix: com.google.zxing.common.BitMatrix): IntArray {
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                pixels[y * matrix.width + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return pixels
    }

    @Test
    fun qrEncodeDecodeRoundtrip() {
        val text = "https://example.com/andagent?q=测试&n=42"
        val matrix = QrCodec.encode(text, 256)
        val decoded = QrCodec.decode(renderToPixels(matrix), matrix.width, matrix.height)
        assertEquals(text, decoded.text)
        assertEquals("QR_CODE", decoded.format)
    }

    @Test
    fun qrEncodeRejectsInvalidInput() {
        assertThrows(IllegalArgumentException::class.java) { QrCodec.encode("", 256) }
        assertThrows(IllegalArgumentException::class.java) { QrCodec.encode("x", 32) }
        assertThrows(IllegalArgumentException::class.java) { QrCodec.encode("x", 4096) }
    }

    @Test
    fun qrAsciiRenderUsesMatrixSize() {
        val matrix = QrCodec.encode("hello", 128)
        val ascii = QrCodec.renderAscii(matrix, step = 8)
        assertTrue(ascii.contains('#'))
        // step=8 over a 128px matrix → 16 rows, one newline per row
        assertEquals(16, ascii.count { it == '\n' })
    }

    /* ----------------------------- FileCrypto --------------------------- */

    @Test
    fun fileCryptoRoundtrip() {
        val plain = "机密内容 secret payload".toByteArray(Charsets.UTF_8)
        val password = "hunter2".toCharArray()
        val blob = FileCrypto.encrypt(plain, password)
        assertTrue(FileCrypto.isEncrypted(blob))
        assertFalse(FileCrypto.isEncrypted(plain))
        assertTrue(blob.size > plain.size)
        assertTrue(FileCrypto.decrypt(blob, password).contentEquals(plain))
    }

    @Test
    fun fileCryptoUsesFreshSaltAndIv() {
        val plain = "same input".toByteArray()
        val password = "pw12".toCharArray()
        val a = FileCrypto.encrypt(plain, password)
        val b = FileCrypto.encrypt(plain, password)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun fileCryptoRejectsWrongPasswordAndBadBlob() {
        val blob = FileCrypto.encrypt("data".toByteArray(), "right-pw".toCharArray())
        assertThrows(javax.crypto.AEADBadTagException::class.java) {
            FileCrypto.decrypt(blob, "wrong-pw".toCharArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            FileCrypto.decrypt("not encrypted".toByteArray(), "pw".toCharArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            FileCrypto.decrypt("ANDAGENT1".toByteArray(), "pw".toCharArray())
        }
    }

    /* ---------------------------- registration -------------------------- */

    @Test
    fun registryContainsAllEightTools() {
        assertEquals(
            setOf(
                "contacts_add", "call_log_read", "app_uninstall", "apk_extract",
                "qr_generate", "qr_scan", "sensor_record", "file_encrypt",
            ),
            R35_TOOLS.toSet(),
        )
        assertEquals(8, R35_TOOLS.size)
    }
}
