package io.nous.andagent.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Registry of tools available to the agent. Converts to OpenAI tool definitions
 * for the LLM and dispatches calls by name.
 */
class ToolRegistry(private val tools: List<Tool>) {
    private val json = Json { encodeDefaults = false }

    private val byName = tools.associateBy { it.name }

    fun definitions(): List<io.nous.andagent.llm.ToolDefinition> = tools.map {
        io.nous.andagent.llm.ToolDefinition(
            function = io.nous.andagent.llm.FunctionDefinition(
                name = it.name,
                description = it.description,
                parameters = it.parameters,
            )
        )
    }

    fun get(name: String): Tool? = byName[name]

    suspend fun dispatch(name: String, argumentsJson: String): String {
        val tool = byName[name] ?: return "ERROR: unknown tool '$name'"
        val args: JsonObject = runCatching {
            json.decodeFromString(JsonObject.serializer(), argumentsJson.ifBlank { "{}" })
        }.getOrElse { return "ERROR: bad arguments JSON: ${it.message}" }
        return runCatching { tool.execute(args) }
            .getOrElse { "ERROR: ${it.message}" }
    }

    fun isDestructive(name: String): Boolean = byName[name]?.destructive ?: false
}
