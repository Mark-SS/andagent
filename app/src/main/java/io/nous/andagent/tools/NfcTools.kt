package io.nous.andagent.tools

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import java.nio.charset.Charset
import kotlin.coroutines.resume

/** One foreground NFC operation at a time, owned by MainActivity's lifecycle. */
class NfcSession(private val activity: Activity, private val prompt: (String) -> Unit) {
    private val adapter by lazy { NfcAdapter.getDefaultAdapter(activity) }
    private val handler = Handler(Looper.getMainLooper())
    private var pending: ((Tag?) -> Unit)? = null
    private var timeout: Runnable? = null
    private var resumed = false

    fun status(): String = when {
        adapter == null -> "ERROR: 设备不支持 NFC"
        adapter?.isEnabled != true -> "ERROR: NFC 未开启，请在系统设置中开启"
        else -> "NFC 已开启"
    }

    suspend fun awaitTag(): Tag? = suspendCancellableCoroutine { cont ->
        handler.post {
            if (!cont.isActive) return@post
            if (pending != null) { cont.resume(null); return@post }
            pending = { tag -> if (cont.isActive) cont.resume(tag) }
            timeout = Runnable { finish(null) }.also { handler.postDelayed(it, 60_000) }
            prompt("请在 60 秒内将 NFC 卡片贴近手机")
            if (resumed) enable()
            cont.invokeOnCancellation { handler.post { finish(null) } }
        }
    }

    fun onResume() { resumed = true; if (pending != null) enable() }
    fun onPause() { resumed = false; disable() }
    fun onNewIntent(intent: Intent?) {
        if (pending == null || intent == null) return
        if (intent.action !in setOf(NfcAdapter.ACTION_TAG_DISCOVERED, NfcAdapter.ACTION_TECH_DISCOVERED, NfcAdapter.ACTION_NDEF_DISCOVERED)) return
        val tag = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
        if (tag != null) finish(tag)
    }
    fun close() { finish(null); disable() }
    private fun finish(tag: Tag?) {
        val callback = pending ?: return
        pending = null
        timeout?.let(handler::removeCallbacks)
        timeout = null
        disable()
        callback(tag)
    }
    private fun enable() = runCatching {
        val intent = Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val dispatch = PendingIntent.getActivity(activity, 0, intent, flags)
        val techs = arrayOf(
            arrayOf("android.nfc.tech.Ndef"), arrayOf("android.nfc.tech.NdefFormatable"),
            arrayOf("android.nfc.tech.NfcA"), arrayOf("android.nfc.tech.NfcB"),
            arrayOf("android.nfc.tech.NfcF"), arrayOf("android.nfc.tech.NfcV"),
            arrayOf("android.nfc.tech.IsoDep"), arrayOf("android.nfc.tech.MifareClassic"),
            arrayOf("android.nfc.tech.MifareUltralight"),
        )
        adapter?.enableForegroundDispatch(activity, dispatch, null, techs)
    }
    private fun disable() = runCatching { adapter?.disableForegroundDispatch(activity) }
}

private fun ByteArray.hex() = joinToString("") { "%02X".format(it) }

class NfcTool(private val session: NfcSession, override val name: String) : Tool {
    override val description = when (name) {
        "nfc_status" -> "查询设备 NFC 是否支持及是否开启。"
        "nfc_read" -> "等待贴卡最多 60 秒，读取 NFC UID、技术和 NDEF Text/URI/Smart Poster 记录。贴卡前先告诉用户。"
        "nfc_write_text" -> "等待贴卡并写入 NDEF 文本。会覆盖卡片原有 NDEF 内容，先告知用户。"
        else -> "等待贴卡并写入 NDEF URI。会覆盖卡片原有 NDEF 内容，先告知用户。"
    }
    override val parameters = when (name) {
        "nfc_write_text" -> schemaOf("text" to strParam("要写入的文本"), required = listOf("text"))
        "nfc_write_uri" -> schemaOf("uri" to strParam("要写入的 URI"), required = listOf("uri"))
        else -> schemaOf()
    }
    override val destructive get() = name.startsWith("nfc_write")
    override suspend fun execute(args: JsonObject): String {
        val state = session.status()
        if (name == "nfc_status" || state.startsWith("ERROR:")) return state
        val value = if (name == "nfc_write_text") args.strArg("text") else if (name == "nfc_write_uri") args.strArg("uri") else null
        if (name.startsWith("nfc_write") && value == null) return "ERROR: 缺少要写入的内容"
        if (name == "nfc_write_uri" && runCatching { android.net.Uri.parse(value).scheme }.getOrNull().isNullOrBlank()) return "ERROR: URI 缺少 scheme"
        val tag = session.awaitTag() ?: return "ERROR: 等待 NFC 贴卡超时或已有读写任务（60 秒）"
        return runCatching {
            if (name == "nfc_read") read(tag)
            else write(tag, if (name == "nfc_write_text") NdefRecord.createTextRecord("zh", value) else NdefRecord.createUri(value))
        }.getOrElse { "ERROR: NFC ${if (name == "nfc_read") "读取" else "写入"}失败：${it.message ?: it.javaClass.simpleName}" }
    }
    private fun read(tag: Tag): String {
        val ndef = Ndef.get(tag)
        val message = if (ndef != null) {
            try { ndef.connect(); ndef.ndefMessage } finally { runCatching { ndef.close() } }
        } else null
        return buildJsonObject {
            put("uid", tag.id.hex())
            put("idSize", tag.id.size)
            putJsonArray("techList") { tag.techList.forEach { add(it) } }
            put("isNdef", ndef != null)
            if (ndef != null) { put("maxSize", ndef.maxSize); put("writable", ndef.isWritable) }
            if (message != null) {
                put("size", message.toByteArray().size)
                putJsonArray("records") { message.records.forEach { add(recordJson(it)) } }
            }
        }.toString()
    }
    private fun recordJson(record: NdefRecord): JsonObject = buildJsonObject {
        put("tnf", record.tnf.toInt()); put("typeHex", record.type.hex()); put("idHex", record.id.hex())
        put("payloadHex", record.payload.hex()); put("size", record.payload.size)
        if (record.tnf == NdefRecord.TNF_WELL_KNOWN) when {
            record.type.contentEquals(NdefRecord.RTD_TEXT) && record.payload.isNotEmpty() -> {
                val n = record.payload[0].toInt() and 0x3f
                if (record.payload.size >= 1 + n) {
                    val charset = if (record.payload[0].toInt() and 0x80 != 0) Charsets.UTF_16 else Charsets.UTF_8
                    put("kind", "text"); put("language", String(record.payload, 1, n, Charsets.US_ASCII))
                    put("text", String(record.payload, 1 + n, record.payload.size - 1 - n, charset))
                }
            }
            record.type.contentEquals(NdefRecord.RTD_URI) -> { put("kind", "uri"); put("uri", record.toUri()?.toString() ?: "") }
            record.type.contentEquals(NdefRecord.RTD_SMART_POSTER) -> {
                put("kind", "smart_poster")
                runCatching { NdefMessage(record.payload).records }.getOrNull()?.let { nested ->
                    putJsonArray("records") { nested.forEach { add(recordJson(it)) } }
                }
            }
        }
    }
    private fun write(tag: Tag, record: NdefRecord): String {
        val message = NdefMessage(arrayOf(record))
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                if (!ndef.isWritable) return "ERROR: NFC 卡片只读，无法写入"
                if (ndef.maxSize < message.toByteArray().size) return "ERROR: NFC 卡片容量不足（需要 ${message.toByteArray().size} 字节，可用 ${ndef.maxSize} 字节）"
                ndef.writeNdefMessage(message)
            } finally { runCatching { ndef.close() } }
        } else {
            val formatable = NdefFormatable.get(tag) ?: return "ERROR: 此 NFC 卡片不支持 NDEF 写入"
            try { formatable.connect(); formatable.format(message) } finally { runCatching { formatable.close() } }
        }
        return "WRITTEN: NFC 已写入（UID=${tag.id.hex()}，${message.toByteArray().size} 字节）"
    }
}
