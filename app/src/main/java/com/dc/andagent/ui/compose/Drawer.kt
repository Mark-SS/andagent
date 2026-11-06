package com.dc.andagent.ui.compose

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dc.andagent.R

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ConversationDrawer(sessions: List<SessionItem>, currentId: String?, onNew: () -> Unit,
    onSwitch: (String) -> Unit, onDelete: (String) -> Unit, onExport: (String) -> Unit,
    onSettings: () -> Unit, version: String) {
    var menuId by remember { mutableStateOf<String?>(null) }
    ModalDrawerSheet(modifier = Modifier.width(360.dp).fillMaxHeight()) {
        Text("AndAgent", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(20.dp))
        FilledTonalButton(onClick = onNew, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Icon(Icons.Default.Add, contentDescription = null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.ui_new_chat))
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (sessions.isEmpty()) item { Text(stringResource(R.string.ui_no_sessions), modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(sessions, key = { it.id }) { session ->
                val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                    if (value == SwipeToDismissBoxValue.EndToStart) { onDelete(session.id); true } else false
                })
                SwipeToDismissBox(state = dismiss, enableDismissFromStartToEnd = false,
                    backgroundContent = { Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.CenterEnd) { Text(stringResource(R.string.ui_delete), color = MaterialTheme.colorScheme.error) } }) {
                    Box {
                        NavigationDrawerItem(
                            selected = currentId == session.id,
                            onClick = { onSwitch(session.id) },
                            label = {
                                Column(Modifier.fillMaxWidth().combinedClickable(onClick = { onSwitch(session.id) }, onLongClick = { menuId = session.id })) {
                                    Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                    Spacer(Modifier.height(3.dp))
                                    Text("${DateUtils.getRelativeTimeSpanString(session.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)} · ${session.tokens} ${stringResource(R.string.ui_tokens)}",
                                        maxLines = 1, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp).heightIn(min = 64.dp)
                        )
                        DropdownMenu(expanded = menuId == session.id, onDismissRequest = { menuId = null }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.ui_export)) }, onClick = { menuId = null; onExport(session.id) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.ui_delete)) }, onClick = { menuId = null; onDelete(session.id) })
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        NavigationDrawerItem(label = { Text(stringResource(R.string.ui_settings)) }, selected = false,
            icon = { Icon(Icons.Default.Settings, contentDescription = null) }, onClick = onSettings,
            modifier = Modifier.padding(horizontal = 12.dp))
        Text("v$version", modifier = Modifier.padding(start = 28.dp, bottom = 16.dp, top = 4.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

data class SessionItem(val id: String, val title: String, val updatedAt: Long, val tokens: Int)
