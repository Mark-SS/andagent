package io.nous.andagent.llm

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Streaming OpenAI-compatible chat client (works with DeepSeek, OpenRouter, etc.).
 * Emits [StreamEvent]s; accumulates tool calls from streamed deltas.
 */
class LlmClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS) // long generations
        .build()

    sealed interface StreamEvent {
        /** Incremental assistant text. */
        data class Text(val delta: String) : StreamEvent

        /** Complete tool call assembled from streamed fragments. */
        data class ToolRequested(val call: ToolCall) : StreamEvent

        /** Stream finished; finishReason is "stop" | "tool_calls" | "length". */
        data class Done(val finishReason: String?, val usage: Usage?) : StreamEvent
    }

    fun streamChat(request: ChatRequest): Flow<StreamEvent> = flow {
        val body = json.encodeToString(ChatRequest.serializer(), request)
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string()?.take(2000)
                throw LlmException("HTTP ${resp.code}: $err")
            }
            val src = resp.body?.source() ?: throw LlmException("empty body")

            // SSE parsing: split on \n\n, lines start with "data: "
            val textStream = src
            val sb = StringBuilder()
            // Accumulator for tool calls assembled across deltas
            val toolAcc = mutableMapOf<Int, StringBuilder>()   // args per index
            val toolIds = mutableMapOf<Int, String>()
            val toolNames = mutableMapOf<Int, String>()
            var finishReason: String? = null
            var usage: Usage? = null

            while (true) {
                val line = textStream.readUtf8Line() ?: break
                when {
                    line.isEmpty() -> {
                        // end of event: dispatch accumulated data
                        if (sb.isNotEmpty()) {
                            val payload = sb.toString()
                            sb.clear()
                            if (payload == "[DONE]") continue
                            val respChunk = json.decodeFromString(ChatResponse.serializer(), payload)
                            val choice = respChunk.choices.firstOrNull() ?: continue
                            choice.delta?.let { delta ->
                                delta.content?.let { emit(StreamEvent.Text(it)) }
                                delta.toolCalls?.forEach { dtc ->
                                    val idx = dtc.index
                                    dtc.id?.let { toolIds[idx] = it }
                                    dtc.function?.name?.let { toolNames[idx] = it }
                                    dtc.function?.arguments?.let { args ->
                                        toolAcc.getOrPut(idx) { StringBuilder() }.append(args)
                                    }
                                    // Emit ToolRequested when this delta closes the args (heuristic:
                                    // DeepSeek streams full JSON args usually in one or few chunks;
                                    // we emit at stream end via finishReason=="tool_calls" too).
                                }
                            }
                            choice.finishReason?.let { finishReason = it }
                            respChunk.usage?.let { usage = it }
                        }
                    }
                    line.startsWith("data:") -> sb.append(line.removePrefix("data:").trim())
                    line.startsWith("event:") || line.startsWith(":") -> { /* ignore */ }
                }
            }
            // Flush any tool calls accumulated (emit in index order)
            toolAcc.keys.toSortedSet().forEach { idx ->
                val name = toolNames[idx] ?: return@forEach
                val call = ToolCall(
                    id = toolIds[idx] ?: "call_$idx",
                    function = FunctionCall(name = name, arguments = toolAcc[idx]?.toString() ?: "{}"),
                )
                emit(StreamEvent.ToolRequested(call))
            }
            emit(StreamEvent.Done(finishReason, usage))
        }
    }.flowOn(dispatcher)
}

class LlmException(message: String) : Exception(message)
