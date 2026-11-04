package io.nous.andagent.widget.data

import android.content.Context
import io.nous.andagent.widget.model.SourceSpec
import io.nous.andagent.widget.model.SourceType
import io.nous.andagent.widget.model.SpecCodec
import io.nous.andagent.widget.model.WidgetSpec
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Fetches every source of one saved record and stores the result in [DataCache].
 * Rendering never calls this; WorkManager or the foreground refresh button do.
 */
object WidgetDataRefresher {
    data class Outcome(val failures: Int, val total: Int)

    private val locks = ConcurrentHashMap<String, Mutex>()
    private val fieldKey = Regex("[A-Za-z][A-Za-z0-9_]{0,31}")
    private const val MAX_FIELDS = 32
    private const val MAX_RESPONSE_BYTES = 256 * 1024
    private const val MAX_VALUE_CHARS = 400

    suspend fun refresh(context: Context, recordId: String, spec: WidgetSpec, force: Boolean = false): Outcome =
        withContext(Dispatchers.IO) {
            val sources = spec.data?.sources.orEmpty()
            if (sources.isEmpty()) return@withContext Outcome(0, 0)

            locks.getOrPut(recordId) { Mutex() }.withLock {
                val cache = DataCache(context)
                val cached = cache.snapshot(recordId)
                val collected = HashMap<String, Map<String, String>>()
                val battery = DeviceState.batteryPercent(context)
                val now = Instant.now()
                var failures = 0

                for (key in order(sources)) {
                    val source = sources.getValue(key)
                    if (!force && cache.isFresh(recordId, key, source.ttlSeconds)) {
                        collected[key] = collected[key] ?: cached[key].orEmpty()
                        continue
                    }
                    try {
                        val values = when (source.type) {
                            SourceType.STATIC -> source.value
                            SourceType.HTTP -> fetchHttp(source)
                            SourceType.SCRIPT -> runScript(context, source, battery, now, collected, cached)
                            SourceType.SYSTEM -> SystemSources.collect(
                                context,
                                source.system ?: error("系统数据源缺少 system"),
                                now
                            )
                        }
                        require(values.size <= MAX_FIELDS) { "数据源 $key 返回了超过 32 个字段" }
                        cache.put(recordId, key, values)
                        collected[key] = values
                    } catch (t: Throwable) {
                        failures++
                        cache.putError(recordId, key, t.message ?: t.javaClass.simpleName, source.fallback)
                        collected[key] = cached[key].orEmpty().ifEmpty { source.fallback }
                    }
                }
                Outcome(failures, sources.size)
            }
        }

    private fun order(sources: Map<String, SourceSpec>): List<String> {
        val done = LinkedHashSet<String>()
        val visiting = HashSet<String>()
        fun visit(key: String) {
            if (key in done || !visiting.add(key)) return
            sources[key]?.dependsOn?.forEach(::visit)
            visiting.remove(key)
            done += key
        }
        sources.keys.forEach(::visit)
        return done.toList()
    }

    private fun fetchHttp(source: SourceSpec): Map<String, String> {
        val url = source.url ?: error("HTTP 数据源缺少 url")
        require(url.startsWith("https://")) { "HTTP 数据源仅支持 https" }
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = source.timeoutMs
            connection.readTimeout = source.timeoutMs
            connection.requestMethod = source.method
            connection.setRequestProperty("Accept", "application/json")
            source.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (source.method == "POST") {
                connection.doOutput = true
                connection.outputStream.use { it.write(ByteArray(0)) }
            }
            val code = connection.responseCode
            require(code in 200..299) { "HTTP $code" }
            val body = connection.inputStream.use { readLimited(it, MAX_RESPONSE_BYTES) }
            val element = runCatching { SpecCodec.json.parseToJsonElement(body.toString(Charsets.UTF_8)) }
                .getOrElse { error("响应不是合法 JSON") }
            val objectValue = pick(element, source.pick)
            flatten(objectValue)
        } finally {
            connection.disconnect()
        }
    }

    private fun runScript(
        context: Context,
        source: SourceSpec,
        battery: Int?,
        now: Instant,
        sources: Map<String, Map<String, String>>,
        cached: Map<String, Map<String, String>>
    ): Map<String, String> {
        val script = source.script ?: error("脚本数据源缺少 script")
        val contextJson = buildContext(now, battery, sources, cached)
        val program = """
            $script
            ;(function () {
              var ctx = JSON.parse(${JsonPrimitive(contextJson)});
              var out = (typeof refresh === 'function') ? refresh(ctx) : null;
              return JSON.stringify(out === null || out === undefined ? {} : out);
            })()
        """.trimIndent()
        val output = WidgetToolRuntime.eval(context, program, source.timeoutMs)
        require(!output.startsWith("ERROR:")) { output }
        val decoded = runCatching { SpecCodec.json.parseToJsonElement(output) }.getOrElse { error("脚本返回的不是合法 JSON") }
        val inner = (decoded as? JsonPrimitive)?.content ?: error("脚本必须返回 JSON 对象")
        val element = runCatching { SpecCodec.json.parseToJsonElement(inner) }
            .getOrElse { error("脚本返回的不是合法 JSON") }
        return flatten(element as? JsonObject ?: error("脚本必须返回 JSON 对象"))
    }

    private fun buildContext(
        now: Instant,
        battery: Int?,
        sources: Map<String, Map<String, String>>,
        cached: Map<String, Map<String, String>>
    ): String {
        fun nested(values: Map<String, Map<String, String>>) = buildJsonObject {
            values.forEach { (source, fields) ->
                put(source, buildJsonObject { fields.forEach { (key, value) -> put(key, value) } })
            }
        }
        return buildJsonObject {
            put("now", now.toString())
            put("battery", battery?.let { JsonPrimitive(it) } ?: JsonNull)
            put("sources", nested(sources))
            put("cache", nested(cached))
        }.toString()
    }

    private fun pick(root: JsonElement, path: String?): JsonObject {
        var current = root
        path?.split('.')?.filter { it.isNotBlank() }?.forEach { segment ->
            current = (current as? JsonObject)?.get(segment) ?: error("响应中缺少字段 $segment")
        }
        return current as? JsonObject ?: error("数据源必须返回 JSON 对象")
    }

    private fun flatten(value: JsonObject): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        value.forEach { (key, field) ->
            require(fieldKey.matches(key)) { "字段名 $key 不合法" }
            require(field !is JsonNull) { "字段 $key 不能为 null" }
            require(field is JsonPrimitive) { "字段 $key 必须是字符串、数字或布尔值" }
            require(result.size < MAX_FIELDS) { "数据源字段不能超过 32 个" }
            result[key] = field.content.take(MAX_VALUE_CHARS)
        }
        return result
    }

    private fun readLimited(stream: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            require(out.size() <= max) { "响应超过 ${max / 1024} KB" }
        }
        return out.toByteArray()
    }
}
