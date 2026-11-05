package com.dc.andagent.tools

/**
 * Pure, JVM-unit-testable helpers for MIFARE Classic / Ultralight data.
 *
 * No Android framework dependency lives here on purpose: the key table, hex
 * validation, MCT/Proxmark3 dictionary parsing and the hex+ASCII rendering are
 * plain Kotlin so they can be exercised by local unit tests without a device.
 *
 * Reference formats (parsing logic only, no runtime dependency):
 *  - MIFARE Classic Tool (GPL) ".keys" / ".dump" files,
 *  - Proxmark3 "mf" key dictionaries.
 */

/**
 * Well-known / factory-default MIFARE Classic keys (6 bytes, 12 hex digits).
 * Mirrors the set shipped by MIFARE Classic Tool and Android's own constants:
 *  FFFFFFFFFFFF = transport/factory default (MifareClassic.KEY_DEFAULT)
 *  A0A1A2A3A4A5 = MIFARE Application Directory
 *  D3F7D3F7D3F7 = NFC Forum / NDEF sector
 *  000000000000 = blank key
 * The rest are common deployment / vendor defaults. Dictionary-only: these are
 * tried in order, there is no brute force.
 */
val MF_DEFAULT_KEYS: List<String> = listOf(
    "FFFFFFFFFFFF",
    "A0A1A2A3A4A5",
    "D3F7D3F7D3F7",
    "000000000000",
    "B0B1B2B3B4B5",
    "4D3A99C351DD",
    "1A982C7E459A",
    "AABBCCDDEEFF",
    "714C5C886E97",
    "587EE5F9350F",
    "A0478CC39091",
    "533CB6C723F6",
    "8FD0A4F256E9",
)

private val HEX_ONLY = Regex("^[0-9a-fA-F]+$")

/** Parse hex (whitespace between bytes allowed) into bytes; null on odd length or non-hex. */
fun hexToBytes(text: String): ByteArray? {
    val s = text.trim().filter { !it.isWhitespace() }
    if (s.isEmpty() || s.length % 2 != 0 || !HEX_ONLY.matches(s)) return null
    return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

/** Normalize a MIFARE key (6 bytes / 12 hex digits). Uppercase hex, or null if invalid. */
fun normalizeKeyHex(text: String): String? {
    var s = text.trim().filter { !it.isWhitespace() }
    if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2)
    return s.uppercase().takeIf { it.length == 12 && HEX_ONLY.matches(it) }
}

/** Uppercase, zero-padded hex rendering. */
fun bytesToHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
    bytes.forEach { append("%02X".format(it.toInt() and 0xff)) }
}

/** Printable ASCII view: 0x20..0x7E map to the char, everything else to '.'. */
fun asciiOf(bytes: ByteArray): String = buildString(bytes.size) {
    bytes.forEach { b ->
        val v = b.toInt() and 0xff
        append(if (v in 0x20..0x7e) v.toChar() else '.')
    }
}

/** One MCT-style dual-view line: 32 hex chars + ASCII column. */
fun formatDumpLine(bytes: ByteArray): String = bytesToHex(bytes) + "  |" + asciiOf(bytes) + "|"

/** Sector 0 / block 0 is the manufacturer block (UID + BCC), normally read-only. */
fun isManufacturerBlock(sector: Int, blockInSector: Int): Boolean = sector == 0 && blockInSector == 0

private val KEY_TOKEN = Regex("(?<![0-9a-fA-F])(?:0x)?([0-9a-fA-F]{12})(?![0-9a-fA-F])")
private val BLOCK_LINE = Regex("^[0-9a-fA-F]{32}$")

/**
 * Parser for MIFARE Classic Tool ".keys"/".dump" and Proxmark3 mf dictionaries.
 *
 *  - ".keys" / dictionary: one 12-hex-digit key per line; '#','//' comments and
 *    blank lines ignored; optional 0x prefix; comma/space separated lines OK.
 *  - ".dump": block lines are 32 hex digits (the sector trailer holds keyA in
 *    the first 12 and keyB in the last 12 hex digits); both are harvested.
 *
 * Returns deduplicated uppercase keys in first-seen order.
 */
object MctKeys {
    fun parse(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in text.lineSequence()) {
            var line = raw.substringBefore('#').substringBefore("//").trim()
            if (line.isEmpty() || line.startsWith("+Sector") || line.startsWith("+")) continue
            val compact = line.replace(" ", "")
            if (BLOCK_LINE.matches(compact)) {
                // MCT .dump block line: harvest keyA (bytes 0..5) and keyB (bytes 10..15).
                out.add(compact.substring(0, 12).uppercase())
                out.add(compact.substring(20, 32).uppercase())
                continue
            }
            for (m in KEY_TOKEN.findAll(line)) out.add(m.groupValues[1].uppercase())
        }
        return out.toList()
    }
}
