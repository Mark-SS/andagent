package com.dc.andagent.widget.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

@Serializable
data class WidgetSpec(
    val schemaVersion: Int = 2,
    val name: String,
    val background: String = "#17212C",
    val padding: Int = 16,
    val root: WidgetNode,
    val data: DataConfig? = null
)

@Serializable
data class DataConfig(val sources: Map<String, SourceSpec> = emptyMap())

@Serializable
enum class SourceType { STATIC, HTTP, SCRIPT, SYSTEM }

/** Host-provided, permission-free device facts. Values are produced by Kotlin, never by scripts. */
@Serializable
enum class SystemProvider { BATTERY, CLOCK, NETWORK, LOCALE, STORAGE, DEVICE_INFO, BATTERY_DETAIL, STEPS_TODAY }

@Serializable
data class SourceSpec(
    val type: SourceType,
    val value: Map<String, String> = emptyMap(),
    val url: String? = null,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val pick: String? = null,
    val ttlSeconds: Int = 900,
    val require: Set<String> = emptySet(),
    val dependsOn: List<String> = emptyList(),
    val timeoutMs: Int = 2000,
    val script: String? = null,
    val system: SystemProvider? = null,
    val sample: Map<String, String> = emptyMap(),
    val fallback: Map<String, String> = emptyMap()
)

@Serializable
enum class NodeType { COLUMN, ROW, TEXT, SPACER, PROGRESS }
@Serializable
enum class Binding { LITERAL, DATE, BATTERY, COUNTDOWN, DATA }

@Serializable
data class WidgetNode(
    val type: NodeType,
    val text: String = "",
    val textSize: Int = 18,
    val maxLines: Int = 3,
    val color: String = "#FFFFFF",
    val padding: Int = 0,
    val size: Int = 8,
    val value: Int = 50,
    val binding: Binding = Binding.LITERAL,
    val targetDate: String? = null,
    val source: String? = null,
    val field: String? = null,
    val fallback: String? = null,
    /** Scheme URI opened when this node is tapped, e.g. "widgetchat://edit" or "https://example.com". */
    val click: String? = null,
    val children: List<WidgetNode> = emptyList()
)

object SpecCodec {
    const val MAX_JSON_BYTES = 65536
    const val MAX_SCRIPT_CHARS = 16384
    private const val MAX_SOURCES = 8
    private const val MAX_FIELDS = 32
    private const val MAX_VALUE_CHARS = 400

    val json = Json { prettyPrint = true; encodeDefaults = true }
    private val color = Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")
    private val sourceKey = Regex("[a-z][a-z0-9_]{0,31}")
    private val fieldKey = Regex("[A-Za-z][A-Za-z0-9_]{0,31}")
    private val pickPath = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+){0,7}")
    private val headerName = Regex("[A-Za-z0-9-]{1,64}")
    private val clickUri = Regex("[A-Za-z][A-Za-z0-9+.\\-]{0,31}:\\S+")
    private val blockedSchemes = setOf("file", "content", "javascript", "data", "blob", "intent", "android.resource", "jar")
    private const val MAX_CLICK_CHARS = 2048
    private val requirements = setOf("network", "charging", "batteryNotLow")

    fun decode(raw: String): WidgetSpec {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) { "配置不能超过 64 KB" }
        // Reject pathological nesting before invoking the recursive JSON parser.
        var depth = 0; var quoted = false; var escaped = false
        for (c in raw) {
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 32) { "JSON 嵌套过深" } }
                '}', ']' -> depth--
            }
        }
        return migrate(json.decodeFromString<WidgetSpec>(raw)).also(::validate)
    }

    /** Reads configuration written before v2. The returned spec is always current. */
    fun migrate(spec: WidgetSpec): WidgetSpec =
        if (spec.schemaVersion == 1) spec.copy(schemaVersion = 2) else spec

    fun encode(spec: WidgetSpec): String { validate(spec); return json.encodeToString(spec) }

    fun validate(spec: WidgetSpec) {
        require(spec.schemaVersion == 2) { "暂不支持此配置版本" }
        require(spec.name.isNotBlank() && spec.name.length <= 60) { "名称须为 1–60 个字符" }
        require(color.matches(spec.background)) { "背景须为 #RRGGBB 或 #AARRGGBB" }
        require(spec.padding in 0..32) { "组件边距须为 0–32" }
        require(spec.root.type in setOf(NodeType.COLUMN, NodeType.ROW)) { "根节点必须是 COLUMN 或 ROW" }
        validateData(spec)
        var count = 0
        fun visit(node: WidgetNode, depth: Int) {
            require(++count <= 40) { "最多支持 40 个节点" }
            require(depth <= 6) { "布局最多嵌套 6 层" }
            require(node.padding in 0..24) { "节点边距须为 0–24" }
            require(node.text.length <= 400) { "单段文字不能超过 400 字" }
            require(node.maxLines in 1..6) { "文字行数须为 1–6" }
            require(node.textSize in 10..48) { "字号须为 10–48" }
            require(color.matches(node.color)) { "文字颜色格式错误" }
            require(node.size in 0..64) { "间距须为 0–64" }
            require(node.value in 0..100) { "进度须为 0–100" }
            node.click?.let { click ->
                require(click.length <= MAX_CLICK_CHARS) { "点击链接不能超过 2048 个字符" }
                require(clickUri.matches(click)) { "点击链接须为 scheme://… 形式" }
                val scheme = click.substringBefore(':').lowercase()
                require(scheme !in blockedSchemes) { "不支持 $scheme: 链接" }
            }
            val container = node.type == NodeType.ROW || node.type == NodeType.COLUMN
            require(container || node.children.isEmpty()) { "只有布局节点能包含子节点" }
            require(!container || node.binding == Binding.LITERAL) { "布局节点不能绑定数据" }
            require(node.type != NodeType.SPACER || node.binding == Binding.LITERAL) { "间距不能绑定数据" }
            require(node.type != NodeType.PROGRESS || node.binding in setOf(Binding.LITERAL, Binding.BATTERY, Binding.DATA)) {
                "进度条仅支持数值、电量或数据源"
            }
            if (node.binding == Binding.COUNTDOWN) {
                requireNotNull(node.targetDate) { "倒计时需要 targetDate" }
                try { LocalDate.parse(node.targetDate) } catch (_: Exception) { throw IllegalArgumentException("日期格式应为 YYYY-MM-DD") }
            }
            if (node.binding == Binding.DATA) {
                require(node.type == NodeType.TEXT || node.type == NodeType.PROGRESS) { "只有文字或进度条能绑定数据源" }
                val source = node.source
                requireNotNull(source) { "数据绑定需要 source" }
                val field = node.field
                requireNotNull(field) { "数据绑定需要 field" }
                require(sourceKey.matches(source)) { "数据源名称格式错误" }
                require(fieldKey.matches(field)) { "字段名格式错误" }
                val data = spec.data
                requireNotNull(data) { "数据绑定需要 data 配置" }
                require(data.sources.containsKey(source)) { "数据源 $source 未定义" }
                require(node.fallback == null || node.fallback.length <= MAX_VALUE_CHARS) { "兜底文字不能超过 400 字" }
            } else {
                require(node.source == null && node.field == null) { "只有 DATA 绑定才能声明 source/field" }
                require(node.fallback == null) { "只有 DATA 绑定才能声明 fallback" }
            }
            node.children.forEach { visit(it, depth + 1) }
        }
        visit(spec.root, 1)
    }

    private fun validateData(spec: WidgetSpec) {
        val data = spec.data ?: return
        require(data.sources.isNotEmpty()) { "data.sources 不能为空" }
        require(data.sources.size <= MAX_SOURCES) { "最多支持 8 个数据源" }

        fun validateFields(name: String, fields: Map<String, String>) {
            require(fields.size <= MAX_FIELDS) { "$name 最多 32 个字段" }
            fields.forEach { (key, value) ->
                require(fieldKey.matches(key)) { "$name 字段名 $key 不合法" }
                require(value.length <= MAX_VALUE_CHARS) { "$name 字段 $key 不能超过 400 字" }
            }
        }

        data.sources.forEach { (key, source) ->
            require(sourceKey.matches(key)) { "数据源名称须为小写字母开头的 1–32 位标识" }
            require(source.ttlSeconds in 0..86400) { "ttlSeconds 须为 0–86400" }
            require(source.timeoutMs in 100..5000) { "timeoutMs 须为 100–5000" }
            require(source.require.all { it in requirements }) { "require 仅支持 network、charging、batteryNotLow" }
            require(source.dependsOn.size <= MAX_SOURCES && source.dependsOn.distinct().size == source.dependsOn.size) { "dependsOn 配置错误" }
            validateFields("value", source.value)
            validateFields("sample", source.sample)
            validateFields("fallback", source.fallback)

            when (source.type) {
                SourceType.STATIC -> {
                    require(source.value.isNotEmpty()) { "静态数据源需要 value" }
                    require(source.url == null && source.script == null && source.pick == null) { "静态数据源不支持 url/pick/script" }
                    require(source.system == null) { "静态数据源不支持 system" }
                    require(source.dependsOn.isEmpty()) { "静态数据源不能声明依赖" }
                }
                SourceType.HTTP -> {
                    val url = source.url
                    requireNotNull(url) { "HTTP 数据源需要 url" }
                    require(url.startsWith("https://") && url.length <= 2048 && url.none(Char::isWhitespace)) { "HTTP 数据源仅支持 https 地址" }
                    require(source.method in setOf("GET", "POST")) { "仅支持 GET 或 POST" }
                    require(source.pick == null || pickPath.matches(source.pick)) { "pick 路径格式错误" }
                    require(source.script == null && source.value.isEmpty() && source.system == null) { "HTTP 数据源不支持 value/script/system" }
                    require(source.headers.size <= 8) { "请求头最多 8 个" }
                    source.headers.forEach { (name, value) ->
                        require(headerName.matches(name)) { "请求头名称 $name 不合法" }
                        require(value.length <= 512 && value.none { it == '\n' || it == '\r' }) { "请求头 $name 的值不合法" }
                    }
                }
                SourceType.SCRIPT -> {
                    val script = source.script
                    requireNotNull(script) { "脚本数据源需要 script" }
                    require(script.isNotBlank() && script.length <= MAX_SCRIPT_CHARS) { "脚本须为 1–16384 个字符" }
                    require(source.url == null && source.value.isEmpty() && source.pick == null) { "脚本数据源不支持 url/pick/value" }
                    require(source.system == null) { "脚本数据源不支持 system" }
                }
                SourceType.SYSTEM -> {
                    requireNotNull(source.system) { "系统数据源需要 system" }
                    require(source.value.isEmpty() && source.url == null && source.script == null && source.pick == null) {
                        "系统数据源不支持 value/url/pick/script"
                    }
                    require(source.dependsOn.isEmpty()) { "系统数据源不能声明依赖" }
                }
            }

            source.dependsOn.forEach { dependency ->
                require(dependency != key) { "数据源不能依赖自己" }
                require(data.sources.containsKey(dependency)) { "依赖的数据源 $dependency 未定义" }
            }
        }

        val visiting = mutableSetOf<String>()
        val done = mutableSetOf<String>()
        fun visitSource(key: String) {
            require(key !in visiting) { "数据源依赖存在循环" }
            if (key in done) return
            visiting += key
            data.sources[key]?.dependsOn?.forEach(::visitSource)
            visiting -= key
            done += key
        }
        data.sources.keys.forEach(::visitSource)
    }
}
