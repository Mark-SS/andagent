package io.nous.andagent.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A tool the agent can invoke. Parameters are described as a JSON Schema
 * (passed through to the LLM as-is).
 */
interface Tool {
    val name: String
    val description: String
    val parameters: JsonElement
    /** true → agent must get explicit user confirmation before execution */
    val destructive: Boolean
        get() = false

    /** Execute and return a result string for the LLM. Throws on failure. */
    suspend fun execute(args: JsonObject): String
}

fun jsonSchema(props: Map<String, JsonElement> = emptyMap(), required: List<String> = emptyList()): JsonObject =
    buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props))
        if (required.isNotEmpty()) {
            put("required", JsonArray(required.map { JsonPrimitive(it) }))
        }
    }

fun schemaOf(vararg props: Pair<String, JsonElement>, required: List<String> = emptyList()): JsonObject =
    jsonSchema(props.toMap(), required)

fun strParam(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

fun intParam(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

fun boolParam(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}
