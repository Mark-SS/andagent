package io.nous.andagent.bridge

import android.os.Handler
import android.os.Looper
import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebView
import io.nous.andagent.widget.WidgetApi
import io.nous.andagent.widget.data.WidgetDataScheduler
import io.nous.andagent.tools.Tool
import io.nous.andagent.ui.SettingsStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.File
import android.net.Uri
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * JS <-> Kotlin bridge.
 *
 * All JS calls arrive on WebView's JavaBridge thread; all JS dispatches are
 * posted to the main thread (evaluateJavascript is not thread-safe). Network
 * I/O runs on OkHttp's dispatcher, tool execution on a small executor.
 *
 * Events are pushed as a single JSON string into
 * `window.__andagent.receive(json)`.
 */
class AgentBridge(
    private val webViewProvider: () -> WebView?,
    private val settings: SettingsStore,
    private val tools: Map<String, Tool>,
    private val permissions: PermissionBroker,
    private val appContext: Context? = null,
    private val appVersion: String = "",
    private val shizukuStatus: () -> String = { "{\"status\":\"unavailable\"}" },
    private val shizukuRequest: () -> String = { "{\"requested\":false}" },
    private val healthPermissionRequest: () -> String = { "{\"requested\":false}" },
    private val termuxStatus: () -> String = { "none" },
    private val termuxGrant: suspend () -> String = { "ERROR: Shizuku 不可用" },
    private val embeddedWebviewOpen: (String) -> String = { "ERROR: 浏览器不可用" },
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val toolExecutor = Executors.newFixedThreadPool(2)
    private val calls = ConcurrentHashMap<String, Call>()

    @Volatile
    private var released = false

    /** {"version":"0.6.0"} — shown on the home screen. */
    @JavascriptInterface
    fun getAppVersion(): String =
        buildJsonObject { put("version", appVersion) }.toString()

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // SSE: keep the stream open
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun emitNotice(message: String) { emit(buildJsonObject { put("type", "notice"); put("message", message) }) }

    @JavascriptInterface
    fun openEmbeddedWebview(uri: String): String = runCatching { embeddedWebviewOpen(uri) }
        .getOrElse { "ERROR: 打开内嵌浏览器失败：${it.message ?: it.javaClass.simpleName}" }

    /* ----------------------------- settings ----------------------------- */

    @JavascriptInterface
    fun getSettings(): String = buildJsonObject {
        put("baseUrl", settings.baseUrl)
        put("apiKey", settings.apiKey)
        put("model", settings.model)
        put("systemPrompt", settings.systemPrompt)
    }.toString()

    @JavascriptInterface
    fun saveSettings(raw: String) {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        obj["baseUrl"]?.jsonPrimitive?.contentOrNull?.let { settings.baseUrl = it }
        obj["apiKey"]?.jsonPrimitive?.contentOrNull?.let { settings.apiKey = it }
        obj["model"]?.jsonPrimitive?.contentOrNull?.let { settings.model = it }
        obj["systemPrompt"]?.jsonPrimitive?.contentOrNull?.let { settings.systemPrompt = it }
    }

    /* --------------------------- permissions ---------------------------- */

    /**
     * Ask for runtime permissions on demand from JS (JSON array of manifest
     * permission names, or a comma/space separated list). The Activity Result
     * API is only touched on the main thread inside [PermissionBroker]; the
     * outcome is pushed back as a `permission.result` event.
     */
    @JavascriptInterface
    fun requestPermissions(names: String) {
        val requested = runCatching {
            val element = json.parseToJsonElement(names)
            if (element is JsonArray) {
                element.mapNotNull { it.jsonPrimitive.contentOrNull }
            } else {
                null
            }
        }.getOrNull() ?: names.split(',', ' ', '\n').filter { it.isNotBlank() }

        if (requested.isEmpty()) {
            emitPermissionResult(emptyMap())
            return
        }
        permissions.request(requested) { granted -> emitPermissionResult(granted) }
    }

    private fun emitPermissionResult(granted: Map<String, Boolean>) {
        emit(buildJsonObject {
            put("type", "permission.result")
            put("granted", buildJsonObject {
                granted.forEach { (name, ok) -> put(name, ok) }
            })
        })
    }

    /* ------------------------- privilege / health ----------------------- */

    /** {"status":"granted"|"not_granted"|"unavailable", ...} for Settings.tsx. */
    @JavascriptInterface
    fun shizukuStatus(): String = runCatching { shizukuStatus() }.getOrElse {
        buildJsonObject {
            put("status", "unavailable")
            put("note", it.message ?: "Shizuku 状态读取失败")
        }.toString()
    }

    /** Main-thread posted by MainActivity; returns an immediate ack JSON. */
    @JavascriptInterface
    fun shizukuRequest(): String = runCatching { shizukuRequest() }.getOrElse {
        buildJsonObject {
            put("requested", false)
            put("message", it.message ?: "Shizuku 授权请求失败")
        }.toString()
    }

    /** Activity Result API is only touched on the main thread in MainActivity. */
    @JavascriptInterface
    fun healthPermissionRequest(): String = runCatching { healthPermissionRequest() }.getOrElse {
        buildJsonObject {
            put("requested", false)
            put("message", it.message ?: "Health Connect 授权请求失败")
        }.toString()
    }

    /**
     * Per-permission state without prompting: "granted" | "denied" |
     * "permanently_denied" (user checked "don't ask again" — the system
     * dialog would not show; user must flip it in app settings).
     */
    @JavascriptInterface
    fun permissionStatus(names: String): String {
        val requested = runCatching {
            val element = json.parseToJsonElement(names)
            if (element is JsonArray) {
                element.mapNotNull { it.jsonPrimitive.contentOrNull }
            } else {
                null
            }
        }.getOrNull() ?: names.split(',', ' ', '\n').filter { it.isNotBlank() }
        return buildJsonObject {
            requested.forEach { name ->
                val state = runCatching { permissions.statusOf(name) }.getOrElse { "unknown" }
                put(name, state)
            }
        }.toString()
    }

    /** Deep link to the system app-settings page (for permanently denied perms). */
    @JavascriptInterface
    fun openAppSettings() {
        permissions.openAppSettings()
    }

    @JavascriptInterface
    fun termuxStatus(): String = termuxStatus()

    @JavascriptInterface
    fun termuxGrantViaShizuku(): String = runCatching { runBlocking { termuxGrant() } }
        .getOrElse { "ERROR: Termux 授权失败：${it.message ?: it.javaClass.simpleName}" }

    @JavascriptInterface
    fun exportConversation(title: String, markdown: String): String {
        val context = appContext ?: return "ERROR: Android 环境不可用"
        if (markdown.isBlank()) return "ERROR: 会话内容为空"
        if (markdown.length > 5_000_000) return "ERROR: 导出内容超过 5MB"
        val filename = (title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(64)
            .ifBlank { "对话" }) + "-${System.currentTimeMillis()}.md"
        return runCatching {
            if (Build.VERSION.SDK_INT < 29) {
                val dir = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "andagent")
                if (!dir.exists() && !dir.mkdirs()) error("无法创建导出目录")
                val file = java.io.File(dir, filename)
                file.writeText(markdown)
                file.absolutePath
            } else {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/markdown")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/andagent/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                    ?: error("MediaStore 无法创建文件")
                try {
                    resolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(markdown) }
                        ?: error("无法打开导出文件")
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    "/sdcard/${Environment.DIRECTORY_DOWNLOADS}/andagent/$filename"
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
            }
        }.onSuccess { path -> emit(buildJsonObject { put("type", "conversation.exported"); put("path", path) }) }
            .getOrElse { "ERROR: 导出失败：${it.message ?: it.javaClass.simpleName}" }
    }

    @JavascriptInterface
    fun widgetList(): String = runCatching { WidgetApi.list(requireNotNull(appContext)) }.getOrDefault("[]")

    @JavascriptInterface
    fun widgetSave(specJson: String, id: String): String = runCatching {
        WidgetApi.save(requireNotNull(appContext), specJson, id.ifBlank { null })
    }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }

    @JavascriptInterface
    fun widgetPin(id: String): String = runCatching {
        WidgetApi.pin(requireNotNull(appContext), id)
    }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }

    @JavascriptInterface
    fun widgetRefresh(id: String): String = runCatching {
        val context = requireNotNull(appContext)
        val record = io.nous.andagent.widget.data.WidgetStore(context).read().records.first { it.id == id }
        WidgetDataScheduler.requestNow(context, id, record.spec)
        "已安排刷新"
    }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }

    @JavascriptInterface
    fun widgetDelete(id: String): String = runCatching {
        WidgetApi.delete(requireNotNull(appContext), id)
        "已删除"
    }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }

    /* ------------------------------ tools ------------------------------- */

    /**
     * Server-side speech-to-text: POST the recorded file to
     * <baseUrl>/audio/transcriptions (OpenAI-compatible), return the text.
     * Runs on OkHttp's dispatcher; result comes back via the tool channel
     * semantics (single JSON payload event, not SSE).
     */
    @JavascriptInterface
    fun transcribeAudio(reqId: String, filePath: String, language: String) {
        toolExecutor.execute {
            val file = java.io.File(filePath)
            if (!file.exists() || file.length() == 0L) {
                emitToolResult(reqId, false, "ERROR: 音频文件不存在或为空: $filePath")
                return@execute
            }
            val key = settings.apiKey
            if (key.isBlank()) {
                emitToolResult(reqId, false, "ERROR: 未配置 API Key，无法调用转写服务")
                return@execute
            }
            val url = settings.baseUrl.trimEnd('/') + "/audio/transcriptions"
            val body = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("file", file.name,
                    file.asRequestBody("audio/mp4".toMediaType()))
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart("language", language.ifBlank { "zh" })
                .addFormDataPart("response_format", "json")
                .build()
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $key")
                .post(body)
                .build()
            val result = runCatching {
                http.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty().take(8000)
                    if (resp.isSuccessful) {
                        val parsed = runCatching {
                            json.parseToJsonElement(text).jsonObject["text"]?.jsonPrimitive?.contentOrNull
                        }.getOrNull()
                        parsed ?: text
                    } else {
                        "ERROR: 转写失败 HTTP ${resp.code}: $text"
                    }
                }
            }.getOrElse { "ERROR: 转写请求异常（${it.message ?: it.javaClass.simpleName}）" }
            emitToolResult(reqId, !result.startsWith("ERROR:"), result)
        }
    }

    /**
     * Save a base64-encoded audio blob (from the WebView recorder) to an app
     * file so it can be POSTed to the transcription endpoint. Returns the
     * absolute path as JSON.
     */
    @JavascriptInterface
    fun saveAudioBase64(reqId: String, base64: String): String {
        val ctx = appContext ?: run {
            return buildJsonObject { put("ok", false); put("message", "ERROR: bridge 无 context") }.toString()
        }
        val result = runCatching {
            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            val out = java.io.File(
                ctx.getExternalFilesDir(null) ?: ctx.filesDir,
                "voice_${System.currentTimeMillis()}.wav",
            )
            out.writeBytes(bytes)
            out.absolutePath
        }.getOrElse { "ERROR: 保存音频失败（${it.message ?: it.javaClass.simpleName}）" }
        return if (result.startsWith("ERROR:")) {
            buildJsonObject { put("ok", false); put("message", result) }.toString()
        } else {
            buildJsonObject { put("ok", true); put("path", result) }.toString()
        }
    }

    @JavascriptInterface
    fun toolExec(reqId: String, name: String, argsJson: String) {
        toolExecutor.execute {
            if (name == "widget_save_and_pin") {
                val result = runCatching {
                    val args = json.parseToJsonElement(argsJson).jsonObject
                    val raw = args["spec_json"]?.jsonPrimitive?.content ?: error("缺少 spec_json")
                    val context = requireNotNull(appContext)
                    val id = WidgetApi.save(context, raw)
                    "已保存组件 $id。${WidgetApi.pin(context, id)}"
                }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
                emitToolResult(reqId, !result.startsWith("ERROR:"), result)
                return@execute
            }
            val tool = tools[name]
            if (tool == null) {
                emitToolResult(reqId, false, "ERROR: unknown tool '$name'")
                return@execute
            }
            val args = runCatching {
                json.parseToJsonElement(argsJson.ifBlank { "{}" }).jsonObject
            }.getOrElse {
                emitToolResult(reqId, false, "ERROR: bad arguments JSON: ${it.message}")
                return@execute
            }
            val result = runCatching { runBlocking { tool.execute(args) } }
                .getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
            emitToolResult(reqId, !result.startsWith("ERROR:"), mediaPreviewResult(name, result))
        }
    }

    private fun mediaPreviewResult(toolName: String, raw: String): String {
        if (toolName !in setOf("file_download", "screen_record_h264", "screen_record_stop") || raw.startsWith("ERROR:")) return raw
        return runCatching {
            val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            val path = obj?.get("path")?.jsonPrimitive?.contentOrNull
                ?: Regex("/(?:sdcard|storage/emulated/0)/Movies/andagent_[^\\s,，]+\\.mp4").find(raw)?.value
                ?: return@runCatching raw
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ext !in setOf("mp3", "m4a", "mp4", "webm", "ogg", "wav", "flac")) return@runCatching raw
            val ctx = appContext ?: return@runCatching raw
            val root = ctx.getExternalFilesDir(null) ?: return@runCatching raw
            val source = File(path).canonicalFile
            val allowed = listOf("/sdcard/Download/andagent/", "/storage/emulated/0/Download/andagent/", "/sdcard/Movies/andagent_", "/storage/emulated/0/Movies/andagent_")
            if (allowed.none { path.startsWith(it) }) return@runCatching raw
            val target = File(root, "media-previews/${System.currentTimeMillis()}_${source.name}")
            target.parentFile?.mkdirs()
            val input = obj?.get("uri")?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("content://") }?.let { ctx.contentResolver.openInputStream(Uri.parse(it)) }
                ?: source.inputStream()
            input.use { stream -> target.outputStream().use { output -> stream.copyTo(output) } }
            if (obj == null) "$raw\n预览文件：${target.absolutePath}"
            else buildJsonObject { obj.forEach { (key, value) -> put(key, value) }; put("previewPath", target.absolutePath) }.toString()
        }.getOrDefault(raw)
    }

    private fun emitToolResult(reqId: String, ok: Boolean, result: String) {
        emit(buildJsonObject {
            put("type", "tool.result")
            put("id", reqId)
            put("ok", ok)
            put("result", result)
        })
    }

    /* --------------------------- LLM transport -------------------------- */

    @JavascriptInterface
    fun llmFetchStart(reqId: String, url: String, headersJson: String, body: String) {
        val headers = runCatching { json.parseToJsonElement(headersJson).jsonObject }.getOrNull()
        val builder = Request.Builder().url(url)
        headers?.forEach { (key, value) ->
            if (key.lowercase() !in HOP_BY_HOP) {
                value.jsonPrimitive.contentOrNull?.let { builder.header(key, it) }
            }
        }
        builder.post(body.toRequestBody(JSON_MEDIA))

        val call = http.newCall(builder.build())
        calls[reqId] = call

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                calls.remove(reqId)
                if (call.isCanceled()) {
                    emit(buildJsonObject { put("type", "llm.done"); put("id", reqId) })
                } else {
                    emitError(reqId, e.message ?: "network error")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val pump = ChunkPump(mainHandler) { lines ->
                    emit(buildJsonObject {
                        put("type", "llm.chunks")
                        put("id", reqId)
                        put("lines", JsonArray(lines.map { JsonPrimitive(it) }))
                    })
                }
                try {
                    response.use { resp ->
                        emit(buildJsonObject {
                            put("type", "llm.headers")
                            put("id", reqId)
                            put("status", resp.code)
                            put("statusText", resp.message)
                            put("headers", buildJsonObject {
                                resp.headers.names().forEach { name ->
                                    put(name, resp.headers.values(name).joinToString(","))
                                }
                            })
                        })

                        val source = resp.body?.source()
                        if (source != null) {
                            while (true) {
                                val line = source.readUtf8Line() ?: break
                                if (!call.isCanceled()) pump.add(line)
                            }
                        }
                    }
                    pump.finish {
                        emit(buildJsonObject { put("type", "llm.done"); put("id", reqId) })
                    }
                } catch (e: IOException) {
                    pump.finish {
                        if (call.isCanceled()) {
                            emit(buildJsonObject { put("type", "llm.done"); put("id", reqId) })
                        } else {
                            emitError(reqId, e.message ?: "stream error")
                        }
                    }
                } finally {
                    calls.remove(reqId)
                }
            }
        })
    }

    @JavascriptInterface
    fun llmCancel(reqId: String) {
        calls.remove(reqId)?.cancel()
    }

    private fun emitError(reqId: String, message: String) {
        emit(buildJsonObject {
            put("type", "llm.error")
            put("id", reqId)
            put("message", message)
        })
    }

    /* ---------------------------- plumbing ----------------------------- */

    private fun emit(event: JsonObject) {
        if (released) return
        val payload = event.toString()
        // JsonPrimitive(payload).toString() is a correctly escaped JS string literal.
        val script = "window.__andagent&&window.__andagent.receive(${JsonPrimitive(payload)})"
        mainHandler.post {
            if (!released) webViewProvider()?.evaluateJavascript(script, null)
        }
    }

    fun shutdown() {
        released = true
        calls.values.forEach { it.cancel() }
        calls.clear()
        toolExecutor.shutdownNow()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    /**
     * Coalesces SSE lines for ~16ms so a fast token stream does not flood the
     * main thread with one evaluateJavascript call per line.
     */
    private class ChunkPump(
        private val handler: Handler,
        private val sink: (List<String>) -> Unit,
    ) {
        private val lock = Any()
        private val buffer = ArrayList<String>(64)
        private var scheduled = false

        fun add(line: String) {
            val needSchedule: Boolean
            synchronized(lock) {
                buffer.add(line)
                needSchedule = !scheduled
                scheduled = true
            }
            if (needSchedule) handler.postDelayed({ flush() }, 16L)
        }

        fun flush() {
            val batch: List<String>
            synchronized(lock) {
                if (buffer.isEmpty()) {
                    scheduled = false
                    return
                }
                batch = buffer.toList()
                buffer.clear()
                scheduled = false
            }
            sink(batch)
        }

        /** Flush the tail, then run [after] on the main thread — keeps chunks ahead of done. */
        fun finish(after: () -> Unit) {
            handler.post {
                flush()
                after()
            }
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val HOP_BY_HOP = setOf(
            "host", "content-length", "connection", "transfer-encoding",
            "accept-encoding", "keep-alive", "upgrade", "proxy-connection",
        )
    }
}
