package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MifareCodecTest {
    @Test fun hexRoundTrip() {
        val bytes = byteArrayOf(0x01, 0x2f.toByte(), 0xA0.toByte(), 0xFF.toByte())
        assertEquals("012FA0FF", bytesToHex(bytes))
        assertEquals("012fa0ff", hexToBytes("01 2f A0 ff")?.let { bytesToHex(it).lowercase() })
    }

    @Test fun hexToBytesRejectsBadInput() {
        assertNull(hexToBytes(""))
        assertNull(hexToBytes("abc"))        // odd length
        assertNull(hexToBytes("zz11"))       // non-hex
        assertEquals(3, hexToBytes("aabbcc")!!.size)
    }

    @Test fun keyNormalization() {
        assertEquals("FFFFFFFFFFFF", normalizeKeyHex("ffffffffffff"))
        assertEquals("A0A1A2A3A4A5", normalizeKeyHex(" 0x a0a1 a2a3 a4a5 "))
        assertNull(normalizeKeyHex("FFFFFFFFFFFFF")) // 13 digits
        assertNull(normalizeKeyHex("short"))
        assertNull(normalizeKeyHex("nothexnothex"))
    }

    @Test fun asciiView() {
        assertEquals("..A.1..", asciiOf(byteArrayOf(0, 1, 65, 2, 49, 3, 0x7f.toByte())))
    }

    @Test fun dumpLineFormat() {
        // 0x00 → '.'（非打印占位），0x66 → 'f'；2 字节 → 2 列 ASCII。
        assertEquals("0066  |.f|", formatDumpLine(byteArrayOf(0x00, 0x66)))
    }

    @Test fun manufacturerBlockOnlySector0Block0() {
        assertTrue(isManufacturerBlock(0, 0))
        assertFalse(isManufacturerBlock(0, 1))
        assertFalse(isManufacturerBlock(1, 0))
    }

    @Test fun defaultKeysAreWellFormed() {
        assertTrue(MF_DEFAULT_KEYS.contains("FFFFFFFFFFFF"))
        assertTrue(MF_DEFAULT_KEYS.all { it.length == 12 && it.matches(Regex("[0-9A-F]{12}")) })
        assertEquals(MF_DEFAULT_KEYS.size, MF_DEFAULT_KEYS.distinct().size)
    }

    @Test fun mctKeysParsePlainDictionary() {
        val text = """
            # comment line
            FFFFFFFFFFFF
            a0a1a2a3a4a5

            // another comment
            0x000000000000
        """.trimIndent()
        assertEquals(listOf("FFFFFFFFFFFF", "A0A1A2A3A4A5", "000000000000"), MctKeys.parse(text))
    }

    @Test fun mctKeysParseDumpHarvestsBothKeys() {
        // MCT .dump block line: 16 bytes -> keyA = bytes 0..5, keyB = bytes 10..15.
        val block = "AABBCCDDEEFF00000000001122334455"
        assertEquals(
            listOf("AABBCCDDEEFF", "001122334455"),
            MctKeys.parse(block),
        )
    }

    @Test fun mctKeysParseIgnoresShortAndLongTokens() {
        // 短 token（1234/abcd）与非 hex 词跳过；行尾 12F 是合法 key，正常收集。
        assertEquals(listOf("FFFFFFFFFFFF"), MctKeys.parse("1234 abcd notakeyhere FFFFFFFFFFFF"))
    }

    @Test fun mctKeysDeduplicatesPreservingOrder() {
        val text = "FFFFFFFFFFFF\nA0A1A2A3A4A5\nffffffffffff"
        assertEquals(listOf("FFFFFFFFFFFF", "A0A1A2A3A4A5"), MctKeys.parse(text))
    }
}
