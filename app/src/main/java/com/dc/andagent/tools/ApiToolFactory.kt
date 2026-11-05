package com.dc.andagent.tools

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.serialization.json.*

data class ApiParam(val name: String, val type: String, val description: String)
data class ApiSpec(val name: String, val domain: String, val description: String, val clazz: String, val method: String,
                   val service: String?, val params: List<ApiParam>, val permission: String?, val destructive: Boolean,
                   val static: Boolean)

object ApiToolFactory {
    private val types = setOf("string", "int", "long", "float", "double", "boolean")
    fun validate(value: JsonElement): ApiSpec {
        val obj = value as? JsonObject ?: throw IllegalArgumentException("工具条目必须为对象")
        fun required(key: String) = obj[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("缺少 $key")
        val name = required("name")
        require(name.matches(Regex("api_[a-z0-9_]+"))) { "工具名须以 api_ 开头" }
        val clazz = required("class")
        val method = required("method")
        ReflectPolicy.check(clazz, method)
        val params = (obj["params"] as? JsonArray ?: throw IllegalArgumentException("params 必须为数组")).map {
            val p = it as? JsonObject ?: throw IllegalArgumentException("参数必须为对象")
            val pn = p["name"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("参数缺少 name")
            val pt = p["type"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("参数缺少 type")
            require(pn.matches(Regex("[a-z][a-z0-9_]*")) && pt in types) { "非法参数：$pn/$pt" }
            ApiParam(pn, pt, p["description"]?.jsonPrimitive?.content ?: "")
        }
        require(params.map { it.name }.distinct().size == params.size) { "参数名重复" }
        val service = obj["service"]?.let { if (it == JsonNull) null else it.jsonPrimitive.content }
        val permission = obj["permission"]?.let { if (it == JsonNull) null else it.jsonPrimitive.content }
        val destructiveValue = obj["destructive"] as? JsonPrimitive
        val destructive = destructiveValue?.takeUnless { it.isString }?.booleanOrNull
            ?: throw IllegalArgumentException("destructive 必须为 boolean")
        val static = obj["static"]?.jsonPrimitive?.booleanOrNull ?: false
        return ApiSpec(name, required("domain"), required("description"), clazz, method, service, params, permission, destructive, static)
    }

    fun register(context: Context, tools: MutableMap<String, Tool>): List<String> {
        val skipped = mutableListOf<String>()
        val executor = RestrictedReflectExecutor(context)
        val entries = try { Json.parseToJsonElement(context.assets.open("api_tools.json").bufferedReader().use { it.readText() }) as JsonArray }
            catch (e: Exception) { Log.e("ApiToolFactory", "无法读取 api_tools.json", e); return skipped }
        for ((index, entry) in entries.withIndex()) {
            try {
                val spec = validate(entry)
                if (tools.containsKey(spec.name)) { skipped += spec.name; Log.w("ApiToolFactory", "重名跳过：${spec.name}"); continue }
                tools[spec.name] = GenericApiTool(spec, executor, context)
            } catch (e: Exception) { Log.w("ApiToolFactory", "跳过非法条目 #$index: ${e.message}") }
        }
        return skipped
    }
}

class GenericApiTool(private val spec: ApiSpec, private val executor: RestrictedReflectExecutor, private val context: Context) : Tool {
    override val name = spec.name
    override val description = spec.description
    override val destructive = spec.destructive
    override val parameters: JsonObject = jsonSchema(spec.params.associate { p ->
        p.name to buildJsonObject {
            put("type", when (p.type) { "int", "long" -> "integer"; "float", "double" -> "number"; else -> p.type })
            put("description", p.description)
        }
    }, spec.params.map { it.name })
    override suspend fun execute(args: JsonObject): String {
        if (spec.permission != null && context.checkSelfPermission(spec.permission) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("缺少权限：${spec.permission}")
        }
        val values = JsonArray(spec.params.map { p -> args[p.name] ?: throw IllegalArgumentException("缺少参数：${p.name}") })
        return executor.invoke(spec.clazz, spec.method, values, spec.static)
    }
}
