package com.dc.andagent.ui.compose

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dc.andagent.R
import com.dc.andagent.cron.CronScheduler
import com.dc.andagent.cron.CronStore
import com.dc.andagent.cron.CronTask
import com.dc.andagent.cron.CronSchedule
import com.dc.andagent.memory.MemoryStore
import com.dc.andagent.ui.SettingsStore
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(store: SettingsStore, memory: MemoryStore, themeMode: String, languageMode: String,
    onTheme: (String) -> Unit, onLanguage: (String) -> Unit, onSaved: () -> Unit,
    onAction: (String) -> String, onAbout: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var saved by remember { mutableStateOf(false) }
    var clearMemory by remember { mutableStateOf(false) }
    var deleteSkill by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    var devtoolsEnabled by remember { mutableStateOf(store.devtoolsDebugging) }
    var newMemory by remember { mutableStateOf("") }
    var newSkill by remember { mutableStateOf("") }
    var cronName by remember { mutableStateOf("") }
    var cronSchedule by remember { mutableStateOf("") }
    var cronPayload by remember { mutableStateOf("") }
    var cronKind by remember { mutableStateOf("notify") }
    fun persist() { onSaved(); saved = true }
    val cron = remember(refresh) { CronStore(context).list() }
    val memories = remember(refresh) { memory.getAll() }
    val skills = remember(refresh) { runCatching { JSONArray(onAction("skills")) }.getOrDefault(JSONArray()) }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.ui_settings)) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.ui_back)) } },
        actions = { if (saved) Text(stringResource(R.string.ui_saved), modifier = Modifier.padding(end = 16.dp), color = MaterialTheme.colorScheme.primary) }) }) { padding ->
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize().padding(padding)) {
            item { Section(stringResource(R.string.ui_model)) }
            item { SettingText(stringResource(R.string.ui_api_url), store.baseUrl) { store.baseUrl = it; persist() } }
            item { SettingText(stringResource(R.string.ui_api_key), store.apiKey, secret = true) { store.apiKey = it; persist() } }
            item { SettingText(stringResource(R.string.ui_model_id), store.model) { store.model = it; persist() } }
            item { SettingText(stringResource(R.string.ui_system_prompt), store.systemPrompt, lines = 4) { store.systemPrompt = it; persist() } }
            item { SettingText(stringResource(R.string.ui_stt_model), store.transcribeModel) { store.transcribeModel = it; persist() } }
            item { SettingText(stringResource(R.string.ui_stt_url), store.transcribeBaseUrl) { store.transcribeBaseUrl = it; persist() } }
            item { SettingText(stringResource(R.string.ui_tts_url), store.ttsBaseUrl) { store.ttsBaseUrl = it; persist() } }
            item { SettingText(stringResource(R.string.ui_tts_model), store.ttsModel) { store.ttsModel = it; persist() } }
            item { SettingText(stringResource(R.string.ui_tts_voice), store.ttsVoice) { store.ttsVoice = it; persist() } }
            item { Section(stringResource(R.string.ui_appearance)); Choice(stringResource(R.string.ui_theme), listOf("system" to stringResource(R.string.ui_system), "light" to stringResource(R.string.ui_light), "dark" to stringResource(R.string.ui_dark)), themeMode, onTheme) }
            item { Section(stringResource(R.string.ui_language)); Choice(stringResource(R.string.ui_language), listOf("system" to stringResource(R.string.ui_system), "zh" to "中文", "en" to "English"), languageMode, onLanguage) }
            item { Section(stringResource(R.string.ui_memory)) }
            item { OutlinedTextField(newMemory, { newMemory = it }, label = { Text(stringResource(R.string.ui_new_memory)) }, modifier = Modifier.fillMaxWidth(), minLines = 2) }
            item { FilledTonalButton(onClick = { runCatching { memory.add(newMemory) }.onSuccess { newMemory = ""; refresh++; saved = true }.onFailure { message = it.message.orEmpty() } }, enabled = newMemory.isNotBlank()) { Text(stringResource(R.string.ui_add_memory)) } }
            items(memories, key = { "memory-${it.id}" }) { entry ->
                var draft by remember(entry.id) { mutableStateOf(entry.content) }
                Column { OutlinedTextField(draft, { draft = it }, label = { Text(if (entry.core) "Core · ${entry.category}" else entry.category) },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
                    Row { TextButton(onClick = { runCatching { memory.update(entry.id, draft) }.onSuccess { refresh++; saved = true }.onFailure { message = it.message.orEmpty() } }, enabled = draft != entry.content) { Text(stringResource(R.string.ui_save)) }
                        TextButton(onClick = { memory.delete(entry.id); refresh++ }) { Text(stringResource(R.string.ui_delete)) } }
                }
            }
            item { OutlinedButton(onClick = { clearMemory = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_clear_memory)) } }
            item { Section(stringResource(R.string.ui_automation)) }
            item { SettingText(stringResource(R.string.ui_task_name), cronName) { cronName = it } }
            item { SettingText(stringResource(R.string.ui_schedule), cronSchedule) { cronSchedule = it } }
            item { Choice(stringResource(R.string.ui_task_type), listOf("notify" to stringResource(R.string.ui_notification), "script" to stringResource(R.string.ui_script), "shell" to stringResource(R.string.ui_shell)), cronKind) { cronKind = it } }
            item { SettingText(stringResource(R.string.ui_payload), cronPayload) { cronPayload = it } }
            item { FilledTonalButton(onClick = {
                runCatching { CronSchedule.parse(cronSchedule); CronStore(context).update { it.add(CronTask(name = cronName, schedule = cronSchedule, kind = cronKind, payload = cronPayload)) }; CronScheduler.rebuild(context) }
                    .onSuccess { cronName = ""; cronSchedule = ""; cronPayload = ""; refresh++; saved = true }
                    .onFailure { message = it.message.orEmpty() }
            }, enabled = cronName.isNotBlank() && cronSchedule.isNotBlank() && cronPayload.isNotBlank()) { Text(stringResource(R.string.ui_create_task)) } }
            if (cron.isEmpty()) item { Text(stringResource(R.string.ui_no_tasks), style = MaterialTheme.typography.bodyMedium) }
            items(cron, key = { "cron-${it.id}" }) { task ->
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { Text(task.name, style = MaterialTheme.typography.bodyLarge); Text("${task.schedule} · ${task.kind}", style = MaterialTheme.typography.bodySmall) }
                    Switch(checked = task.enabled, onCheckedChange = { enabled ->
                        CronStore(context).update { tasks -> val i = tasks.indexOfFirst { it.id == task.id }; if (i >= 0) tasks[i] = tasks[i].copy(enabled = enabled) }
                        CronScheduler.rebuild(context); refresh++; saved = true
                    })
                }
                Row { TextButton(onClick = { CronScheduler.runNow(context, task.id); message = context.getString(R.string.ui_task_queued) }) { Text(stringResource(R.string.ui_run_now)) }
                    TextButton(onClick = { CronStore(context).update { it.removeAll { item -> item.id == task.id } }; CronScheduler.rebuild(context); refresh++ }) { Text(stringResource(R.string.ui_delete)) } }
            }
            item { OutlinedButton(onClick = { val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")); if (Build.VERSION.SDK_INT >= 31) context.startActivity(intent) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_exact_alarms)) } }
            item { Section(stringResource(R.string.ui_extensions)) }
            item { SettingText(stringResource(R.string.ui_skill_source), newSkill) { newSkill = it } }
            item { FilledTonalButton(onClick = {
                val source = newSkill.trim()
                message = "…"
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { onAction("skill_install:$source") }.getOrElse { it.message.orEmpty() } }
                    message = result; newSkill = ""; refresh++
                }
            }, enabled = newSkill.isNotBlank()) { Text(stringResource(R.string.ui_install_skill)) } }
            if (skills.length() == 0) item { Text(stringResource(R.string.ui_no_skills), style = MaterialTheme.typography.bodyMedium) }
            items(skills.length()) { index ->
                val skill = skills.getJSONObject(index)
                val name = skill.optString("name")
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) { Text(name); Text(skill.optString("description"), style = MaterialTheme.typography.bodySmall) }
                        Switch(checked = skill.optBoolean("enabled"), onCheckedChange = { enabled ->
                            message = onAction("skill_${if (enabled) "enable" else "disable"}:$name"); refresh++
                        })
                    }
                    TextButton(onClick = { deleteSkill = name }) { Text(stringResource(R.string.ui_delete)) }
                }
            }
            item { ActionRow("Shizuku", statusText(onAction("shizuku"))) { message = statusText(onAction("shizuku_request")) } }
            item {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column { Text("DevTools", style = MaterialTheme.typography.bodyLarge); Text(stringResource(R.string.ui_web_debugging), style = MaterialTheme.typography.bodySmall) }
                    Switch(checked = devtoolsEnabled, onCheckedChange = { devtoolsEnabled = it; message = onAction("devtools_toggle"); saved = true })
                }
            }
            item { Section(stringResource(R.string.ui_data)) }
            item { ActionRow("Health Connect", stringResource(R.string.ui_steps_access)) { message = statusText(onAction("health")) } }
            item { ActionRow("Screen projection", statusText(onAction("projection_status"))) { message = statusText(onAction("projection")) } }
            item { TextButton(onClick = { message = onAction("projection_stop") }) { Text(stringResource(R.string.ui_stop_projection)) } }
            item { Section(stringResource(R.string.ui_about)); ActionRow(stringResource(R.string.ui_about), stringResource(R.string.ui_version) + " · " + stringResource(R.string.ui_notices), onAbout) }
            if (message.isNotBlank()) item { Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (clearMemory) AlertDialog(onDismissRequest = { clearMemory = false }, title = { Text(stringResource(R.string.ui_clear_memory)) },
        text = { Text(stringResource(R.string.ui_clear_memory_body)) },
        confirmButton = { TextButton(onClick = { memory.deleteAll(); refresh++; clearMemory = false; saved = true }) { Text(stringResource(R.string.ui_delete)) } },
        dismissButton = { TextButton(onClick = { clearMemory = false }) { Text(stringResource(R.string.ui_cancel)) } })
    deleteSkill?.let { name ->
        AlertDialog(onDismissRequest = { deleteSkill = null }, title = { Text(stringResource(R.string.ui_delete)) }, text = { Text(name) },
            confirmButton = { TextButton(onClick = { message = onAction("skill_delete:$name"); refresh++; deleteSkill = null }) { Text(stringResource(R.string.ui_delete)) } },
            dismissButton = { TextButton(onClick = { deleteSkill = null }) { Text(stringResource(R.string.ui_cancel)) } })
    }
}

private fun statusText(raw: String): String = runCatching {
    val obj = JSONObject(raw)
    obj.optString("message").ifBlank { obj.optString("status").ifBlank { obj.optString("channel", raw) } }
}.getOrDefault(raw)

@Composable private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp))
}

@Composable private fun SettingText(label: String, initial: String, secret: Boolean = false, lines: Int = 1, save: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    OutlinedTextField(value = value, onValueChange = { value = it; save(it) }, label = { Text(label) },
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        minLines = lines, maxLines = if (lines == 1) 1 else 8, modifier = Modifier.fillMaxWidth())
}

@Composable private fun Choice(label: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    options.forEach { (value, title) ->
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected == value) { onSelect(value) },
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            RadioButton(selected = selected == value, onClick = null)
            Spacer(Modifier.width(8.dp)); Text(title)
        }
    }
}

@Composable private fun ActionRow(title: String, detail: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        Column(Modifier.padding(16.dp)) { Text(title, style = MaterialTheme.typography.bodyLarge); Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
