package io.nous.andagent.agent

import io.nous.andagent.llm.ChatMessage
import io.nous.andagent.llm.ChatRequest
import io.nous.andagent.llm.LlmClient
import io.nous.andagent.llm.LlmClient.StreamEvent
import io.nous.andagent.llm.ToolCall
import io.nous.andagent.llm.ToolDefinition
import io.nous.andagent.llm.Usage
import io.nous.andagent.tools.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The agent loop: user message → LLM stream → (tool calls → execute → feed back)*
 * → final answer. Emits [AgentEvent]s for the UI.
 */
class AgentLoop(
    private val client: LlmClient,
    private val registry: ToolRegistry,
    private val systemPrompt: String,
    private val model: String,
    /** Called before a destructive tool executes; return true to proceed. */
    private val confirmDestructive: suspend (toolName: String, args: String) -> Boolean,
) {

    sealed interface AgentEvent {
        data class AssistantDelta(val text: String) : AgentEvent
        data class ToolStarted(val call: ToolCall) : AgentEvent
        data class ToolFinished(val call: ToolCall, val result: String, val blocked: Boolean = false) : AgentEvent
        data class TurnDone(val usage: Usage?) : AgentEvent
        data class Error(val message: String) : AgentEvent
    }

    fun run(history: List<ChatMessage>, userMessage: String): Flow<AgentEvent> = flow {
        val messages = (history + ChatMessage(role = "user", content = userMessage)).toMutableList()
        runTurn(messages)
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.runTurn(
        messages: MutableList<ChatMessage>
    ) {
        val request = ChatRequest(
            model = model,
            messages = listOf(ChatMessage(role = "system", content = systemPrompt)) + messages,
            tools = registry.definitions(),
        )
        val assistant = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()

        try {
            client.streamChat(request).collect { ev ->
                when (ev) {
                    is StreamEvent.Text -> {
                        assistant.append(ev.delta)
                        emit(AgentEvent.AssistantDelta(ev.delta))
                    }
                    is StreamEvent.ToolRequested -> toolCalls.add(ev.call)
                    is StreamEvent.Done -> {
                        if (ev.finishReason == "tool_calls" || toolCalls.isNotEmpty()) {
                            messages.add(
                                ChatMessage(role = "assistant", content = assistant.toString(), toolCalls = toolCalls)
                            )
                            for (call in toolCalls) {
                                val blocked = registry.isDestructive(call.function.name) &&
                                    !confirmDestructive(call.function.name, call.function.arguments)
                                emit(AgentEvent.ToolStarted(call))
                                val result = if (blocked) {
                                    "BLOCKED: user denied execution"
                                } else {
                                    registry.dispatch(call.function.name, call.function.arguments)
                                }
                                emit(AgentEvent.ToolFinished(call, result, blocked))
                                messages.add(
                                    ChatMessage(role = "tool", content = result, toolCallId = call.id)
                                )
                            }
                            // recurse: continue the loop with tool results
                            runTurn(messages)
                        } else {
                            emit(AgentEvent.TurnDone(ev.usage))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            emit(AgentEvent.Error(e.message ?: e.javaClass.simpleName))
        }
    }
}
