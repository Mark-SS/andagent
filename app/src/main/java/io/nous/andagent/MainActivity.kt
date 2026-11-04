package io.nous.andagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import io.nous.andagent.ui.ChatScreen
import io.nous.andagent.ui.AndAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndAgentTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    ChatScreen()
                }
            }
        }
    }
}
