package com.dc.andagent.tools

import org.junit.Assert.*
import org.junit.Test

/** Pure-logic tests for the R38 MIFARE helpers (no Android framework calls). */
class NfcMifareCodecTest {

    @Test
    fun defaultKeyTableIsValidSixByteKeys() {
        assertTrue(MF_DEFAULT_KEYS.size >= 10)
        assertEquals(MF_DEFAULT_KEYS.size, MF_DEFAULT_KEYS.distinct().size)
        for (k in MF_DEFAULT_KEYS) {
            assertEquals(12, k.length)
            assertEquals(k.uppercase(), k)
            assertNotNull(hexToBytes(k))
            assertEquals(6, hexToBytes(k)!!.size)
            assertEquals(k, normalizeKeyHex(k))
        }
        // The four keys called out by the brief must be present.
        assertTrue(MF_DEFAULT_KEYS.contains("FFFFFFFFFFFF"))
        assertTrue(MF_DEFAULT_KEYS.contains("A0A1A2A3A4A5"))
        assertTrue(MF_DEFAULT_KEYS.contains("D3F7D3F7D3F7"))
        assertTrue(MF_DEFAULT_KEYS.contains("000000000000"))
    }

    @Test
    fun hexToBytesRoundTripsAndRejectsBadInput() {
        assertArrayEquals(byteArrayOf(0x00, 0x01, 0x7f, 0x80.toByte(), 0xff.toByte()),
            hexToBytes("00017F80FF")!!)
        assertArrayEquals(byteArrayOf(0xAB.toByte(), 0xCD.toByte()), hexToBytes("ab cd")!!)
        assertArrayEquals(ByteArray(0), byteArrayOf())
        assertNull(hexToBytes("ABC"))          // odd length
        assertNull(hexToBytes("GG0011223344")) // non-hex
        assertNull(hexToBytes(""))             // empty
        assertNull(hexToBytes("0x001122334455")) // 0x prefix not a plain hex body
    }

    @Test
    fun normalizeKeyHexAcceptsOnlySixByteKeys() {
        assertEquals("AABBCCDDEEFF", normalizeKeyHex("aabbccddeeff"))
        assertEquals("AABBCCDDEEFF", normalizeKeyHex("0xAABBCCDDEEFF"))
        assertEquals("112233445566", normalizeKeyHex(" 11 22 33 44 55 66 "))
        assertNull(normalizeKeyHex("AABBCCDDEE"))   // 5 bytes
        assertNull(normalizeKeyHex("AABBCCDDEEFFFF")) // 7 bytes
        assertNull(normalizeKeyHex("ZZZZZZZZZZZZ"))  // non-hex
    }

    @Test
    fun bytesToHexAsciiAndDumpLine() {
        assertEquals("00FF1041", bytesToHex(byteArrayOf(0x00, 0xff.toByte(), 0x10, 0x41)))
        assertEquals("A. .", asciiOf(byteArrayOf(0x41, 0x00, 0x20, 0x7f)))
        val block = "AndAgent R38!!!".toByteArray().copyOf(16)
        val line = formatDumpLine(block)
        assertTrue(line.startsWith(bytesToHex(block)))
        assertTrue(line.contains("|AndAgent R38"))
        // 16 bytes -> 32 hex chars, 16 ASCII chars between the pipes.
        val ascii = line.substringAfter("|").substringBefore("|")
        assertEquals(16, ascii.length)
    }

    @Test
    fun manufacturerBlockIsOnlySector0Block0() {
        assertTrue(isManufacturerBlock(0, 0))
        assertFalse(isManufacturerBlock(0, 1))
        assertFalse(isManufacturerBlock(1, 0))
        assertFalse(isManufacturerBlock(15, 3))
    }

    @Test
    fun mctKeysParsesDotKeysAndProxmarkDict() {
        // MCT .keys / Proxmark3 dict: one key per line, # comments, 0x prefix, comma/space separated.
        val text = """
            # common keys
            FFFFFFFFFFFF
            0xA0A1A2A3A4A5  // transport // comment
            D3F7D3F7D3F7, 000000000000
            FFFFFFFFFFFF   # duplicate
            nothexZZZZZZZZ
        """.trimIndent()
        val keys = MctKeys.parse(text)
        assertEquals(listOf("FFFFFFFFFFFF", "A0A1A2A3A4A5", "D3F7D3F7D3F7", "000000000000"), keys)
    }

    @Test
    fun mctDumpTrailerHarvestsKeyAAndKeyB() {
        // 32 hex digits = 16-byte block: keyA(6) + access(4) + keyB(6).
        val dump = """
            +Sector: 0
            112233445566778899AABBCCDDEEFF00
            FFFFFFFFFFFFFF078069A0A1A2A3A4A5
            +Sector: 1
            00000000000000000000000000000000
        """.trimIndent()
        val keys = MctKeys.parse(dump)
        // First data block's leading/trailing 12 hex are data, not keys, but the harness
        // treats every 32-hex block line as a trailer candidate; header lines are ignored.
        assertTrue(keys.contains("112233445566"))
        assertTrue(keys.contains("CCDDEEFF00".let { ("000000000000") })) // blank-key from trailer
        assertTrue(keys.contains("FFFFFFFFFFFF"))
        assertTrue(keys.contains("A0A1A2A3A4A5"))
        // Header lines must never become keys.
        assertFalse(keys.any { it.startsWith("+") })
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun mctKeysIgnoresJunkAndDeduplicates() {
        assertEquals(emptyList<String>(), MctKeys.parse(""))
        assertEquals(emptyList<String>(), MctKeys.parse("# only a comment\n// and another\n"))
        assertEquals(listOf("ABCDEF012345"), MctKeys.parse("ABCDEF012345\nABCDEF012345\n0xabcdef012345"))
        // A 13-hex-digit token is not a 6-byte key and must not be partially matched.
        assertNull(MctKeys.parse("ABCDEF0123456").firstOrNull())
    }
}
