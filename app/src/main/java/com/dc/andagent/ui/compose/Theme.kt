package com.dc.andagent.ui.compose

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun AndAgentTheme(mode: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val scheme = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme(primary = Color(0xFFB1C5FF), secondaryContainer = Color(0xFF344665))
    else lightColorScheme(primary = Color(0xFF345BC1), secondaryContainer = Color(0xFFDCE5F9))
    MaterialTheme(colorScheme = scheme, content = content)
}
