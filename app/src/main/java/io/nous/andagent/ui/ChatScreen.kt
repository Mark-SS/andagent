package io.nous.andagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.nous.andagent.agent.AgentLoop
import io.nous.andagent.llm.ChatMessage
import io.nous.andagent.llm.LlmClient
import io.nous.andagent.tools.ToolRegistry
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * M1 chat screen: streaming conversation UI. Agent wiring is injected.
 */
data class ChatUiMessage(
    val role: String,           // user | assistant | tool
    val text: String,
    val isTool: Boolean = false,
    val blocked: Boolean = false,
)

@Composable
fun ChatScreen() {
    // Placeholder wiring until Settings/DI land in M1 finalization.
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var messages by remember { mutableStateOf(listOf<ChatUiMessage>()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pendingConfirm by remember { mutableStateOf<Pair<(Boolean) -> Unit, String>?>(null) }

    val history = remember(messages) {
        messages.filter { !it.isTool }.map { ChatMessage(role = it.role, content = it.text) }
    }

    suspend fun runTurn(userText: String) {
        val client = LlmClient(
            baseUrl = settings.baseUrl,
            apiKey = settings.apiKey,
            model = settings.model,
        )
        val shell = io.nous.andagent.shell.ShellEngine(context.filesDir)
        val registry = ToolRegistry(
            listOf(
                io.nous.andagent.tools.ShellTool(shell),
                io.nous.andagent.tools.DeviceInfoTool(context.applicationContext),
            )
        )
        val loop = AgentLoop(
            client = client,
            registry = registry,
            systemPrompt = SYSTEM_PROMPT,
            model = settings.model,
            confirmDestructive = { name, args ->
                kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                    pendingConfirm = { approved ->
                        pendingConfirm = null
                        cont.resumeWith(Result.success(approved))
                    } to "$name\n$args"
                }
            },
        )
        busy = true
        messages = messages + ChatUiMessage(role = "user", text = userText)
        var toolCount = 0
        loop.run(history, userText).collectLatest { ev ->
            when (ev) {
                is AgentLoop.AgentEvent.AssistantDelta -> {
                    // append to last assistant message or start a new one
                    val last = messages.lastOrNull()
                    messages = if (last?.role == "assistant" && !last.isTool) {
                        messages.dropLast(1) + last.copy(text = last.text + ev.text)
                    } else {
                        messages + ChatUiMessage(role = "assistant", text = ev.text)
                    }
                }
                is AgentLoop.AgentEvent.ToolStarted -> {
                    toolCount++
                    messages = messages + ChatUiMessage(role = "tool", text = "⚙ ${ev.call.function.name}", isTool = true)
                }
                is AgentLoop.AgentEvent.ToolFinished -> {
                    val idx = messages.indexOfLast { it.isTool }
                    if (idx >= 0) {
                        val m = messages[idx]
                        messages = messages.subList(0, idx) + m.copy(
                            text = m.text + if (ev.blocked) " — 已拒绝" else " — " + ev.result.lineSequence().firstOrNull().orEmpty().take(80),
                            blocked = ev.blocked,
                        ) + messages.subList(idx + 1, messages.size)
                    }
                }
                is AgentLoop.AgentEvent.Error -> {
                    messages = messages + ChatUiMessage(role = "assistant", text = "⚠ ${ev.message}")
                }
                is AgentLoop.AgentEvent.TurnDone -> { /* keep */ }
            }
            listState.animateScrollToItem(messages.size - 1)
        }
        busy = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages) { msg ->
                MessageBubble(msg)
            }
        }
        pendingConfirm?.let { (callback, label) ->
            ConfirmCard(label, onApprove = { callback(true) }, onDeny = { callback(false) })
        }
        InputBar(
            value = input,
            onValueChange = { input = it },
            enabled = !busy,
            busy = busy,
            onSend = {
                val text = input.trim()
                if (text.isNotEmpty() && !busy) {
                    input = ""
                    scope.launch { runTurn(text) }
                }
            },
        )
    }
}

@Composable
private fun MessageBubble(msg: ChatUiMessage) {
    when {
        msg.isTool -> Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                msg.text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp),
                color = if (msg.blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
            )
        }
        msg.role == "user" -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.large,
            ) { Text(msg.text, modifier = Modifier.padding(12.dp)) }
        }
        else -> Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.large,
        ) { Text(msg.text, modifier = Modifier.padding(12.dp)) }
    }
}

@Composable
private fun ConfirmCard(label: String, onApprove: () -> Unit, onDeny: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("⚠ 危险操作确认", style = MaterialTheme.typography.titleSmall)
            Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 4)
            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApprove) { Text("允许") }
                OutlinedButton(onClick = onDeny) { Text("拒绝") }
            }
        }
    }
}

@Composable
private fun InputBar(value: String, onValueChange: (String) -> Unit, enabled: Boolean, busy: Boolean, onSend: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("给 AndAgent 下指令…") },
            enabled = enabled,
            maxLines = 4,
        )
        Button(onClick = onSend, enabled = enabled && value.isNotBlank()) {
            Text(if (busy) "…" else "发送")
        }
    }
}

val SYSTEM_PROMPT = """
你是 AndAgent，一个运行在 Android 手机上的专业 AI 助手，可以执行 shell 命令和查询设备信息。
- 用中文回答用户。
- shell 工具在 toybox 环境下运行（ls/find/du/df/grep/cat 等可用），工作目录是应用私有目录。
- 执行删除、移动等破坏性操作前必须向用户说明将要做什么。
- 输出的命令输出可能被截断，需要更多信息时分段查询。
""".trimIndent()
