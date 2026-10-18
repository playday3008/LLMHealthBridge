package dev.playday3008.llmhealthbridge

import android.os.Bundle

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import dev.playday3008.llmhealthbridge.ui.theme.LLMHealthBridgeTheme

/** Explains why the app asks for Health Connect write access; target of the rationale intents. */
class PermissionRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LLMHealthBridgeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Text(
                        text = getString(R.string.permission_rationale),
                        modifier = Modifier.padding(innerPadding).padding(24.dp)
                    )
                }
            }
        }
    }
}
