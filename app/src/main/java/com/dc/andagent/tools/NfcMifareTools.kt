package com.dc.andagent.tools

import android.content.Context
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.os.Environment
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/**
 * R38: MIFARE Classic / Ultralight deep read-write on top of Android's own NFC
 * TagTechnology APIs.
 *
 * Library selection (recorded in the R38 run report): libnfc is a C library for
 * external USB/PN532 readers backed by libusb/PCSC — it is neither usable nor
 * needed inside an Android phone, whose NFC stack already exposes the equivalent
 * MifareClassic / MifareUltralight primitives. No new native dependency is added;
 * the MCT (GPL) and Proxmark3 key-file *formats* are borrowed as parsing logic
 * only (see [MifareCodec.MctKeys]).
 *
 * Compliance: MIFARE Classic Crypto-1 is broken. These tools are for cards you
 * own or authorized security testing only — never for third-party cards.
 */

private const val COMPLIANCE =
    "仅用于自有卡或获授权的安全测试：MIFARE Classic 的 Crypto-1 已被破解，禁止用于未授权卡片。"

/** User key library backed by SharedPreferences; merged into dump auth attempts. */
class MifareKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun custom(): List<String> =
        (prefs.getString(KEY_CUSTOM, "") ?: "")
            .lineSequence().map { it.trim() }
            .filter { it.length == 12 }.toList()

    /** Default well-known keys first, then user keys (deduplicated). */
    fun all(): List<String> = (MF_DEFAULT_KEYS + custom()).distinct()

    fun add(hex: String): Boolean {
        val norm = normalizeKeyHex(hex) ?: return false
        save(custom().toMutableSet().apply { add(norm) })
        return true
    }

    fun remove(hex: String): Boolean {
        val norm = normalizeKeyHex(hex) ?: return false
        val set = custom().toMutableSet()
        if (!set.remove(norm)) return false
        save(set)
        return true
    }

    fun importKeys(keys: List<String>): Int {
        val set = custom().toMutableSet()
        var added = 0
        keys.mapNotNull { normalizeKeyHex(it) }.forEach { if (set.add(it)) added++ }
        save(set)
        return added
    }

    private fun save(set: Set<String>) {
        prefs.edit().putString(KEY_CUSTOM, set.sorted().joinToString("\n")).apply()
    }

    companion object {
        private const val PREF = "nfc_mifare_keys"
        private const val KEY_CUSTOM = "custom"
    }
}

class NfcMifareTool(
    private val session: NfcSession,
    private val context: Context,
    override val name: String,
) : Tool {
    private val keyStore by lazy { MifareKeyStore(context) }

    override val description: String = when (name) {
        "nfc_mf_dump" ->
            "等待贴 MIFARE Classic 卡（最多 60 秒），按 内置默认 key 集 + 自定义 key 库 + keys 参数 " +
                "逐扇区尝试 KeyA/KeyB 认证，dump 全卡为 JSON + 十六进制/ASCII 双视图文本，并把原始镜像存到 " +
                "Download/andagent/dumps/<uid>.bin。认证失败的扇区逐扇区报告，不整体失败。keys 支持逗号/空格/换行分隔的 12 位 hex key，或 MCT/Proxmark3 字典文本。$COMPLIANCE"
        "nfc_mf_write_block" ->
            "等待贴 MIFARE Classic 卡，向指定扇区块写入 16 字节数据。block 为扇区内块索引（0 起），" +
                "key_hex 为 12 位 hex，key_type=a|b，data_hex 必须为 32 位 hex（16 字节）。破坏性：必须 confirm=true；" +
                "写前读原值、写后 read-back 校验。块 0（厂商块/UID）默认拒绝，仅可变 UID 卡在 allow_block0=true " +
                "且 confirm_block0=true 双重确认后允许（写入可能使卡永久变砖，须如实告知用户）。扇区尾块含 key/访问位，写错可锁死扇区。$COMPLIANCE"
        "nfc_ul_read" ->
            "等待贴 MIFARE Ultralight / NTAG 卡，按页读取（每页 4 字节），返回每页 hex + ASCII 及卡型。" +
                "pages_start 默认 0，count 默认 16（上限 64）。"
        "nfc_ul_write_page" ->
            "等待贴 MIFARE Ultralight / NTAG 卡，向指定页写 4 字节（data_hex 为 8 位 hex）。破坏性：必须 confirm=true，" +
                "写前读原值、写后 read-back 校验。页 0-3 为 UID/锁定位/OTP/CC 区（OTP 不可逆、UID 通常硬件只读），默认拒绝；" +
                "确需操作可变 UID 卡时加 allow_special=true 并已知情。$COMPLIANCE"
        "nfc_keys_manage" ->
            "管理 MIFARE Classic 自定义 key 库（SharedPreferences 持久化，dump 时自动并入尝试集）。" +
                "action=list（默认）列内置默认集与自定义集；add/remove 配 key_hex（12 位 hex）；" +
                "import 配 keys_text 导入 MIFARE Classic Tool(.keys/.dump) 或 Proxmark3 字典文本。内置默认 key 不可移除。$COMPLIANCE"
        else -> "MIFARE NFC 工具"
    }

    override val parameters = when (name) {
        "nfc_mf_dump" -> schemaOf(
            "keys" to strParam("可选：额外尝试的 key（12 位 hex，逗号/空格/换行分隔，或 MCT/Proxmark3 字典文本）"),
        )
        "nfc_mf_write_block" -> schemaOf(
            "sector" to intParam("扇区索引（0 起）"),
            "block" to intParam("扇区内块索引（0 起，通常为 0..3；4K 大扇区 0..15）"),
            "key_hex" to strParam("认证 key，12 位 hex（6 字节）"),
            "key_type" to strParam("a|b，默认 a"),
            "data_hex" to strParam("写入数据，32 位 hex（16 字节）"),
            "confirm" to boolParam("破坏性操作确认，必须 true"),
            "allow_block0" to boolParam("允许写厂商块 0（仅可变 UID 卡），必须 true"),
            "confirm_block0" to boolParam("块 0 二次确认，必须 true"),
            required = listOf("sector", "block", "key_hex", "data_hex", "confirm"),
        )
        "nfc_ul_read" -> schemaOf(
            "pages_start" to intParam("起始页，默认 0"),
            "count" to intParam("读取页数，默认 16，上限 64"),
        )
        "nfc_ul_write_page" -> schemaOf(
            "page" to intParam("页索引（0 起）"),
            "data_hex" to strParam("写入数据，8 位 hex（4 字节）"),
            "confirm" to boolParam("破坏性操作确认，必须 true"),
            "allow_special" to boolParam("允许写页 0-3（UID/OTP/CC 区），必须 true"),
            required = listOf("page", "data_hex", "confirm"),
        )
        "nfc_keys_manage" -> schemaOf(
            "action" to strParam("add|list|remove|import，默认 list"),
            "key_hex" to strParam("add/remove 用：12 位 hex key"),
            "keys_text" to strParam("import 用：MCT(.keys/.dump) 或 Proxmark3 字典文本"),
        )
        else -> schemaOf()
    }

    override val destructive get() = name in setOf("nfc_mf_write_block", "nfc_ul_write_page")

    override suspend fun execute(args: JsonObject): String {
        // Pure local key-library operation: no tag, no NFC adapter needed.
        if (name == "nfc_keys_manage") return runCatching { manageKeys(args) }
            .getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
        val state = session.status()
        if (state.startsWith("ERROR:")) return state
        val tag = session.awaitTag() ?: return "ERROR: 等待 NFC 贴卡超时或已有读写任务（60 秒）"
        return runCatching {
            when (name) {
                "nfc_mf_dump" -> mfDump(tag, args)
                "nfc_mf_write_block" -> mfWriteBlock(tag, args)
                "nfc_ul_read" -> ulRead(tag, args)
                "nfc_ul_write_page" -> ulWritePage(tag, args)
                else -> "ERROR: unknown tool '$name'"
            }
        }.getOrElse { "ERROR: NFC 操作失败：${it.message ?: it.javaClass.simpleName}" }
    }

    /* ----------------------------- MIFARE Classic ----------------------------- */

    private fun classicTypeLabel(mc: MifareClassic): String = when (mc.type) {
        MifareClassic.TYPE_CLASSIC -> when {
            mc.size >= 4096 -> "MIFARE Classic 4K"
            mc.size >= 2048 -> "MIFARE Classic 2K"
            else -> "MIFARE Classic 1K"
        }
        MifareClassic.TYPE_PLUS -> "MIFARE Plus (SL1, Classic-compatible)"
        MifareClassic.TYPE_PRO -> "MIFARE Pro (Classic-compatible)"
        else -> "MIFARE Classic (type=${mc.type})"
    }

    private fun mfDump(tag: Tag, args: JsonObject): String {
        val mc = MifareClassic.get(tag)
            ?: return "ERROR: 此卡不是 MIFARE Classic（DESFire/FeliCa/仅 NDEF 等），扇区 dump 不适用；请用 nfc_read 查看 NDEF/技术列表。"
        val extra = args.strArg("keys")?.let { MctKeys.parse(it) } ?: emptyList()
        val keySet = (keyStore.all() + extra.mapNotNull { normalizeKeyHex(it) }).distinct()
        val sectorCount = mc.sectorCount
        val size = mc.size
        val image = ByteArray(size) // unread sectors stay 0x00 so the .bin keeps a fixed layout
        val sectorReports = mutableListOf<JsonObject>()
        val text = StringBuilder()
        var okCount = 0
        val failed = mutableListOf<Int>()

        try {
            mc.connect()
            for (s in 0 until sectorCount) {
                val firstBlock = mc.sectorToBlock(s)
                val blockCount = mc.getBlockCountInSector(s)
                var authKey: String? = null
                var authType: String? = null
                keyLoop@ for (kh in keySet) {
                    val kb = hexToBytes(kh) ?: continue
                    for (t in listOf("A", "B")) {
                        val ok = runCatching {
                            if (t == "A") mc.authenticateSectorWithKeyA(s, kb)
                            else mc.authenticateSectorWithKeyB(s, kb)
                        }.getOrDefault(false)
                        if (ok) { authKey = kh; authType = t; break@keyLoop }
                    }
                }
                val authed = authKey != null
                val blocks = mutableListOf<ByteArray>()
                if (authed) {
                    for (b in 0 until blockCount) {
                        val abs = firstBlock + b
                        val data = runCatching { mc.readBlock(abs) }.getOrNull()
                        val clean = if (data != null && data.size == 16) data else ByteArray(16)
                        if (abs * 16 + 16 <= size) clean.copyInto(image, abs * 16)
                        blocks.add(clean)
                    }
                }
                if (authed) okCount++ else failed.add(s)
                text.append("Sector $s  ${if (authed) "OK key=$authKey/$authType" else "认证失败 (未读出)"}\n")
                blocks.forEachIndexed { b, data ->
                    text.append("  block %02d: %s\n".format(firstBlock + b, formatDumpLine(data)))
                }
                sectorReports.add(buildJsonObject {
                    put("sector", s)
                    put("firstBlock", firstBlock)
                    put("blockCount", blockCount)
                    put("authenticated", authed)
                    put("key", authKey ?: "")
                    put("keyType", authType ?: "")
                    putJsonArray("blocks") {
                        blocks.forEachIndexed { b, data -> add(formatDumpLine(data)) }
                    }
                })
            }
        } finally { runCatching { mc.close() } }

        val file = saveDump(tag.id, image)
        return buildJsonObject {
            put("uid", bytesToHex(tag.id))
            put("type", classicTypeLabel(mc))
            put("size", size)
            put("sectorCount", sectorCount)
            put("keysTried", keySet.size)
            put("authenticatedSectors", okCount)
            put("failedSectors", sectorCount - okCount)
            putJsonArray("authFailedList") { failed.forEach { add(it) } }
            putJsonArray("techList") { tag.techList.forEach { add(it) } }
            put("dumpFile", file)
            put("dumpFileNote", "原始镜像（未读出扇区为 0x00），可用 file_share / web_read 取用")
            put("hexDump", text.toString())
            putJsonArray("sectors") { sectorReports.forEach { add(it) } }
            put("compliance", COMPLIANCE)
        }.toString()
    }

    private fun mfWriteBlock(tag: Tag, args: JsonObject): String {
        if (args.boolArg("confirm") != true)
            return "ERROR: 写块是破坏性操作，必须带 confirm=true，并在说明风险后经用户确认。"
        val sector = args.intArg("sector") ?: return "ERROR: 缺少 sector"
        val block = args.intArg("block") ?: return "ERROR: 缺少 block（扇区内块索引，0 起）"
        val keyHex = normalizeKeyHex(args.strArg("key_hex") ?: "")
            ?: return "ERROR: key_hex 必须是 12 位十六进制（6 字节）"
        val keyType = (args.strArg("key_type") ?: "a").lowercase()
        if (keyType !in setOf("a", "b")) return "ERROR: key_type 必须是 a 或 b"
        val data = args.strArg("data_hex")?.let { hexToBytes(it) }
            ?: return "ERROR: 缺少 data_hex 或不是合法十六进制"
        if (data.size != 16) return "ERROR: 写块数据必须正好 16 字节（32 位 hex），当前 ${data.size} 字节"
        if (isManufacturerBlock(sector, block) &&
            (args.boolArg("allow_block0") != true || args.boolArg("confirm_block0") != true)
        ) return "ERROR: 块 0 是厂商块（含 UID/BCC），写入可能导致卡片永久变砖。仅『可变 UID 卡』在明确知情时允许，必须同时带 allow_block0=true 与 confirm_block0=true 双重确认，并已向用户如实告知变砖风险。"
        val mc = MifareClassic.get(tag)
            ?: return "ERROR: 此卡不是 MIFARE Classic，块写工具不适用；请用 nfc_read 查看 NDEF。"
        if (sector !in 0 until mc.sectorCount) return "ERROR: sector 越界（0..${mc.sectorCount - 1}）"
        if (block !in 0 until mc.getBlockCountInSector(sector))
            return "ERROR: block 越界（扇区 $sector 内 0..${mc.getBlockCountInSector(sector) - 1}）"
        val absBlock = mc.sectorToBlock(sector) + block
        val keyBytes = hexToBytes(keyHex)!!
        val before: ByteArray
        val after: ByteArray
        try {
            mc.connect()
            val authed = runCatching {
                if (keyType == "a") mc.authenticateSectorWithKeyA(sector, keyBytes)
                else mc.authenticateSectorWithKeyB(sector, keyBytes)
            }.getOrDefault(false)
            if (!authed) return "ERROR: 扇区 $sector 用 key $keyHex/$keyType 认证失败（换 key 或确认卡型）"
            before = mc.readBlock(absBlock)
            mc.writeBlock(absBlock, data)
            after = mc.readBlock(absBlock)
        } finally { runCatching { mc.close() } }
        val verified = after.contentEquals(data)
        return if (verified)
            "WRITTEN: 扇区 $sector 块 $absBlock 写入成功（UID=${bytesToHex(tag.id)}）\n原值: ${formatDumpLine(before)}\n新值: ${formatDumpLine(after)}\nread-back 校验: 通过"
        else
            "WRITTEN(校验失败): 扇区 $sector 块 $absBlock 已发出写入但回读不一致（写保护/访问位限制？）\n原值: ${formatDumpLine(before)}\n期望: ${formatDumpLine(data)}\n回读: ${formatDumpLine(after)}"
    }

    /* ----------------------------- MIFARE Ultralight ----------------------------- */

    private fun ultralightTypeLabel(ul: MifareUltralight): String = when (ul.type) {
        MifareUltralight.TYPE_ULTRALIGHT -> "MIFARE Ultralight / NTAG"
        MifareUltralight.TYPE_ULTRALIGHT_C -> "MIFARE Ultralight C / NTAG (3DES)"
        else -> "MIFARE Ultralight family (type=${ul.type})"
    }

    private fun ulRead(tag: Tag, args: JsonObject): String {
        val ul = MifareUltralight.get(tag)
            ?: return "ERROR: 此卡不是 MIFARE Ultralight/NTAG，页读工具不适用；请用 nfc_read 查看 NDEF。"
        val start = args.intArg("pages_start") ?: 0
        val count = (args.intArg("count") ?: 16).coerceIn(1, 64)
        if (start < 0) return "ERROR: pages_start 不能为负"
        val pages = mutableListOf<ByteArray>()
        val text = StringBuilder()
        var error: String? = null
        try {
            ul.connect()
            var off = start
            while (pages.size < count) {
                val chunk = runCatching { ul.readPages(off) }.getOrNull()
                if (chunk == null || chunk.size < 16) {
                    error = "在页 $off 读取失败（超出卡容量或该卡不支持 READ 连读）"
                    break
                }
                for (p in 0 until 4) {
                    if (pages.size >= count) break
                    pages.add(chunk.copyOfRange(p * 4, p * 4 + 4))
                }
                off += 4
            }
        } finally { runCatching { ul.close() } }
        pages.forEachIndexed { i, data ->
            val page = start + i
            val area = if (page < 4) " [UID/OTP/CC 区]" else ""
            text.append("page %02d:%s %s\n".format(page, area, formatDumpLine(data)))
        }
        return buildJsonObject {
            put("uid", bytesToHex(tag.id))
            put("type", ultralightTypeLabel(ul))
            put("pagesStart", start)
            put("pagesRead", pages.size)
            put("bytesPerPage", 4)
            putJsonArray("techList") { tag.techList.forEach { add(it) } }
            putJsonArray("pages") { pages.forEachIndexed { i, d -> add(formatDumpLine(d)) } }
            put("hexDump", text.toString())
            if (error != null) put("note", error!!)
            put("note2", "页 0-3 为 UID/锁定位/OTP/CC 区；OTP 位不可逆。")
        }.toString()
    }

    private fun ulWritePage(tag: Tag, args: JsonObject): String {
        if (args.boolArg("confirm") != true)
            return "ERROR: 页写入是破坏性操作，必须带 confirm=true 并经用户确认。"
        val page = args.intArg("page") ?: return "ERROR: 缺少 page"
        val data = args.strArg("data_hex")?.let { hexToBytes(it) }
            ?: return "ERROR: 缺少 data_hex 或不是合法十六进制"
        if (data.size != 4) return "ERROR: 页数据必须正好 4 字节（8 位 hex），当前 ${data.size} 字节"
        if (page < 0) return "ERROR: page 不能为负"
        if (page < 4 && args.boolArg("allow_special") != true)
            return "ERROR: 页 0-3 是 UID/锁定位/OTP/CC 区，写 OTP 不可逆、写 UID 通常被硬件拒绝。确要在可变 UID 卡上操作需带 allow_special=true 并已知情（OTP 位只能 0→1，写入后无法恢复）。"
        val ul = MifareUltralight.get(tag)
            ?: return "ERROR: 此卡不是 MIFARE Ultralight/NTAG，页写工具不适用；请用 nfc_read 查看 NDEF。"
        val before: ByteArray?
        val after: ByteArray?
        try {
            ul.connect()
            before = runCatching { ul.readPages(page).copyOfRange(0, 4) }.getOrNull()
            ul.writePage(page, data)
            after = runCatching { ul.readPages(page).copyOfRange(0, 4) }.getOrNull()
        } finally { runCatching { ul.close() } }
        val verified = after != null && after.contentEquals(data)
        return if (verified)
            "WRITTEN: 页 $page 写入成功（UID=${bytesToHex(tag.id)}）\n原值: ${before?.let { formatDumpLine(it) } ?: "读取失败"}\n新值: ${formatDumpLine(after!!)}\nread-back 校验: 通过"
        else
            "WRITTEN(校验失败): 页 $page 已发出写入但回读不一致\n原值: ${before?.let { formatDumpLine(it) } ?: "读取失败"}\n期望: ${formatDumpLine(data)}\n回读: ${after?.let { formatDumpLine(it) } ?: "读取失败"}"
    }

    /* ----------------------------- key library + dump file ----------------------------- */

    private fun manageKeys(args: JsonObject): String {
        val action = (args.strArg("action") ?: "list").lowercase()
        return when (action) {
            "list" -> buildJsonObject {
                put("defaultCount", MF_DEFAULT_KEYS.size)
                putJsonArray("defaultKeys") { MF_DEFAULT_KEYS.forEach { add(it) } }
                put("customCount", keyStore.custom().size)
                putJsonArray("customKeys") { keyStore.custom().forEach { add(it) } }
                put("total", keyStore.all().size)
                put("note", "dump 时自动按 内置默认集 + 自定义集 + keys 参数 尝试；action=import 可导入 MCT(.keys/.dump) 或 Proxmark3 字典。")
                put("compliance", COMPLIANCE)
            }.toString()
            "add" -> {
                val hex = args.strArg("key_hex") ?: return "ERROR: add 需要 key_hex（12 位十六进制）"
                if (!keyStore.add(hex)) return "ERROR: key_hex 必须是 12 位十六进制（6 字节）"
                "OK: 已加入自定义 key 库 ${normalizeKeyHex(hex)}，当前自定义 ${keyStore.custom().size} 个"
            }
            "remove" -> {
                val hex = args.strArg("key_hex") ?: return "ERROR: remove 需要 key_hex"
                if (normalizeKeyHex(hex) == null) return "ERROR: key_hex 必须是 12 位十六进制"
                if (!keyStore.remove(hex)) return "ERROR: 该 key 不在自定义库中（内置默认 key 不可移除）"
                "OK: 已移除 ${normalizeKeyHex(hex)}，当前自定义 ${keyStore.custom().size} 个"
            }
            "import" -> {
                val raw = args.strArg("keys_text") ?: return "ERROR: import 需要 keys_text（MCT/Proxmark3 字典文本）"
                val parsed = MctKeys.parse(raw)
                if (parsed.isEmpty()) return "ERROR: 未从文本中解析出任何 6 字节 key"
                val added = keyStore.importKeys(parsed)
                "OK: 解析出 ${parsed.size} 个 key，新增 $added 个，当前自定义 ${keyStore.custom().size} 个"
            }
            else -> "ERROR: action 必须是 add|list|remove|import"
        }
    }

    private fun saveDump(uid: ByteArray, image: ByteArray): String {
        val base = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "andagent/dumps",
        )
        if (!base.isDirectory && !base.mkdirs()) return "ERROR: 无法创建 dump 目录 ${base.absolutePath}"
        val name = (bytesToHex(uid).ifEmpty { "unknown" }) + ".bin"
        val file = File(base, name)
        file.writeBytes(image)
        return file.absolutePath
    }
}
