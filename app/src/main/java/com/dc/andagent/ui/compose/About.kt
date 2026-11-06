package com.dc.andagent.ui.compose

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dc.andagent.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(version: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val notices = context.assets.open("THIRD-PARTY-NOTICES.md").bufferedReader().use { it.readText() }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.ui_about)) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ui_back)) } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("AndAgent", style = MaterialTheme.typography.headlineMedium)
            Text("${stringResource(R.string.ui_version)} $version", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/congduan/andagent/blob/main/LICENSE"))) }) { Text("Apache License 2.0") }
            FilledTonalButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/congduan/andagent"))) }, modifier = Modifier.fillMaxWidth()) { Text("GitHub · Star") }
            Text(stringResource(R.string.ui_notices), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
            notices.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                when {
                    line.startsWith("# ") -> Text(line.removePrefix("# "), style = MaterialTheme.typography.titleLarge)
                    line.startsWith("## ") -> Text(line.removePrefix("## "), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    line.startsWith("### ") -> Text(line.removePrefix("### "), style = MaterialTheme.typography.titleSmall)
                    else -> Text(line.replace(Regex("\\[([^]]+)]\\(([^)]+)\\)"), "$1 ($2)"), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
