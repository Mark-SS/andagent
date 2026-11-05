package com.dc.andagent.tools

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.coroutines.resume

fun isMountedOtg(state: String, emulated: Boolean, removable: Boolean): Boolean =
    state == Environment.MEDIA_MOUNTED && !emulated && removable

/** Parses a relative SAF path. Rejects path traversal and absolute paths. */
fun otgTreeMatchesVolume(treeId: String, uuid: String?): Boolean = uuid != null && treeId == "$uuid:"

fun otgPathParts(path: String): List<String>? {
    if (path.startsWith('/') || path.contains('\\')) return null
    val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
    return if (parts.any { it == ".." || it.contains('\u0000') }) null else parts
}

class OtgTool(private val context: Context, override val name: String) : Tool {
    override val description = when (name) {
        "otg_status" -> "检测已挂载 OTG U 盘及 USB 设备和授权状态。"
        "otg_request" -> "申请 USB 设备权限或打开 SAF U 盘根目录授权选择器；两种授权彼此独立。"
        else -> "通过 SAF 对已授权 OTG U 盘执行 list/read/write/delete/mkdir。root 为 U 盘根目录树 URI，path 为相对路径。"
    }
    override val parameters = when (name) {
        "otg_status" -> schemaOf()
        "otg_request" -> schemaOf("permission" to strParam("usb 或 saf"), required = listOf("permission"))
        else -> schemaOf(
            "root" to strParam("已授权 U 盘根目录 content:// tree URI"),
            "op" to strParam("list | read | write | delete | mkdir"),
            "path" to strParam("U 盘根目录内相对路径；list 根目录传空字符串"),
            "content" to strParam("write 时写入的 UTF-8 文本"),
            required = listOf("root", "op", "path"),
        )
    }
    override val destructive get() = name == "otg_file_ops"
    private val storage get() = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
    private val usb get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    override suspend fun execute(args: JsonObject): String = when (name) {
        "otg_status" -> status()
        "otg_request" -> request(args)
        else -> withContext(Dispatchers.IO) { fileOps(args) }
    }

    private fun status(): String {
        val volumes = storage.storageVolumes
        return buildJsonObject {
            put("mountedOtg", volumes.any { isMountedOtg(it.state, it.isEmulated, it.isRemovable) })
            put("volumes", buildJsonArray {
                volumes.forEach { v -> add(buildJsonObject {
                    put("uuid", v.uuid ?: "")
                    put("state", v.state)
                    put("emulated", v.isEmulated)
                    put("removable", v.isRemovable)
                    put("otg", isMountedOtg(v.state, v.isEmulated, v.isRemovable))
                    if (Build.VERSION.SDK_INT >= 30) put("directory", v.directory?.absolutePath ?: "")
                }) }
            })
            put("usbDevices", buildJsonArray { usb.deviceList.values.forEach { d -> add(buildJsonObject {
                put("deviceName", d.deviceName)
                put("vendorId", d.vendorId)
                put("productId", d.productId)
                put("permission", usb.hasPermission(d))
            }) } })
            put("safTrees", buildJsonArray { SafTreeStore.list(context).forEach { add(JsonPrimitive(it)) } })
        }.toString()
    }

    private suspend fun request(args: JsonObject): String { return when (args.strArg("permission")) {
        "saf" -> ExternalDataTool(context, dummyPermissionBroker(), "saf_tree_pick").execute(buildJsonObject {})
        "usb" -> {
            val devices = usb.deviceList.values.toList()
            if (devices.isEmpty()) return "ERROR: 未检测到 USB 设备；${status()}"
            val granted = mutableListOf<String>()
            val denied = mutableListOf<String>()
            for (device in devices) {
                if (usb.hasPermission(device) || requestUsb(device)) granted += device.deviceName else denied += device.deviceName
            }
            buildJsonObject { put("granted", buildJsonArray { granted.forEach { add(JsonPrimitive(it)) } }); put("denied", buildJsonArray { denied.forEach { add(JsonPrimitive(it)) } }); put("note", "经系统挂载的 U 盘可只用 SAF 授权；原始 USB 设备权限不能代替 SAF 树授权") }.toString()
        }
        else -> "ERROR: permission 必须是 usb 或 saf"
    } }

    // ExternalDataTool's SAF picker does not use its PermissionBroker. Keep the
    // dependency explicit by injecting the existing broker from MainActivity.
    private var broker: com.dc.andagent.bridge.PermissionBroker? = null
    fun withBroker(value: com.dc.andagent.bridge.PermissionBroker): OtgTool { broker = value; return this }
    private fun dummyPermissionBroker(): com.dc.andagent.bridge.PermissionBroker = broker ?: error("权限代理不可用")

    private suspend fun requestUsb(device: android.hardware.usb.UsbDevice): Boolean = withTimeoutOrNull(60_000) {
        suspendCancellableCoroutine { cont ->
            val action = "${context.packageName}.USB_PERMISSION.${UUID.randomUUID()}"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action != action) return
                    runCatching { context.unregisterReceiver(this) }
                    if (cont.isActive) cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && usb.hasPermission(device))
                }
            }
            try {
                val filter = IntentFilter(action)
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
                val intent = Intent(action).setPackage(context.packageName)
                val pending = PendingIntent.getBroadcast(context, device.deviceId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) }; pending.cancel() }
                usb.requestPermission(device, pending)
            } catch (_: Exception) {
                runCatching { context.unregisterReceiver(receiver) }
                if (cont.isActive) cont.resume(false)
            }
        }
    } ?: false

    private fun fileOps(args: JsonObject): String {
        val root = args.strArg("root") ?: return "ERROR: 缺少 root"
        val op = args.strArg("op") ?: return "ERROR: 缺少 op"
        val path = args.strArg("path") ?: return "ERROR: 缺少 path"
        val parts = otgPathParts(path) ?: return "ERROR: path 必须是 U 盘内相对路径，不能包含 .. 或反斜杠"
        val volumes = storage.storageVolumes.filter { isMountedOtg(it.state, it.isEmulated, it.isRemovable) }
        if (volumes.isEmpty()) return "ERROR: 未挂载 OTG U 盘；${status()}"
        if (root !in SafTreeStore.list(context) || context.contentResolver.persistedUriPermissions.none { it.uri.toString() == root && it.isReadPermission && it.isWritePermission })
            return "ERROR: SAF 树未取得持久读写授权；先调用 otg_request(permission=saf)，选择 U 盘根目录"
        val tree = runCatching { Uri.parse(root) }.getOrNull() ?: return "ERROR: root URI 无效"
        if (tree.scheme != "content") return "ERROR: root 必须是 content:// URI"
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return "ERROR: root 不是 SAF 树 URI"
        if (volumes.none { otgTreeMatchesVolume(rootId, it.uuid) }) return "ERROR: root 必须是当前已挂载 U 盘的根目录树；${status()}"
        return try {
            val doc = resolve(tree, rootId, parts, op)
            when (op) {
                "list" -> {
                    val id = doc ?: return "ERROR: 路径不存在"
                    buildJsonObject { put("root", root); put("path", path); put("files", listChildren(tree, id)) }.toString()
                }
                "read" -> {
                    val id = doc ?: return "ERROR: 文件不存在"
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() <= 100 * 1024) {
                            val n = input.read(buffer, 0, minOf(buffer.size, 100 * 1024 + 1 - output.size()))
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                        }
                        output.toByteArray()
                    } ?: return "ERROR: 无法读取文件"
                    if (bytes.size > 100 * 1024) return "ERROR: 文件超过 100KB，文本读取上限"
                    buildJsonObject { put("path", path); put("content", bytes.toString(Charsets.UTF_8)); put("size", bytes.size); put("uri", uri.toString()) }.toString()
                }
                "write" -> {
                    if (parts.isEmpty()) return "ERROR: 不可覆盖 U 盘根目录"
                    val content = args.strArg("content") ?: return "ERROR: 缺少 content"
                    val id = doc ?: return "ERROR: 无法创建文件"
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) } ?: return "ERROR: 无法写入文件"
                    "WRITTEN: $uri (${content.toByteArray(Charsets.UTF_8).size} bytes)"
                }
                "delete" -> {
                    if (parts.isEmpty()) return "ERROR: 不可删除 U 盘根目录"
                    val id = doc ?: return "ERROR: 路径不存在"
                    if (DocumentsContract.deleteDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, id))) "DELETED: $path" else "ERROR: 删除失败"
                }
                "mkdir" -> if (doc != null) "WRITTEN: 目录已存在 $path" else "ERROR: 无法创建目录"
                else -> "ERROR: op 必须是 list/read/write/delete/mkdir"
            }
        } catch (e: Exception) { "ERROR: SAF 操作失败：${e.message ?: e.javaClass.simpleName}" }
    }

    private fun resolve(tree: Uri, rootId: String, parts: List<String>, op: String): String? {
        var id = rootId
        for ((index, part) in parts.withIndex()) {
            val existing = child(tree, id, part)
            if (existing != null) { id = existing; continue }
            if (op !in setOf("write", "mkdir")) return null
            val mime = if (index < parts.lastIndex || op == "mkdir") DocumentsContract.Document.MIME_TYPE_DIR else "text/plain"
            val created = DocumentsContract.createDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, id), mime, part) ?: return null
            id = DocumentsContract.getDocumentId(created)
        }
        return id
    }

    private fun child(tree: Uri, parent: String, name: String): String? {
        context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name) return c.getString(0)
        }
        return null
    }

    private fun listChildren(tree: Uri, parent: String) = buildJsonArray {
        var count = 0
        context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
            while (c.moveToNext() && count++ < 200) add(buildJsonObject {
                put("name", c.getString(1) ?: "")
                put("mime", c.getString(2) ?: "")
                put("size", if (c.isNull(3)) 0L else c.getLong(3))
                put("uri", DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)).toString())
            })
        }
    }
}
