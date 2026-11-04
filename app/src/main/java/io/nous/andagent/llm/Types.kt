package io.nous.andagent.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ---- OpenAI-compatible wire types (DeepSeek uses the same schema) ----

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall,
)

@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String, // JSON string, not structured
)

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition,
)

@Serializable
data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: JsonElement, // JSON Schema
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition>? = null,
    val stream: Boolean = true,
    @SerialName("stream_options") val streamOptions: StreamOptions? = StreamOptions(includeUsage = true),
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
)

@Serializable
data class StreamOptions(val includeUsage: Boolean)

@Serializable
data class ChatResponse(
    val id: String? = null,
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
)

@Serializable
data class Choice(
    val index: Int = 0,
    val delta: Delta? = null,
    val message: Delta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class Delta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<DeltaToolCall>? = null,
)

@Serializable
data class DeltaToolCall(
    val index: Int = 0,
    val id: String? = null,
    val function: FunctionCall? = null,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)
