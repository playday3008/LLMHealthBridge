package dev.playday3008.llmhealthbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

import dev.playday3008.llmhealthbridge.R
import dev.playday3008.llmhealthbridge.entry.ReplyResult

/** Error list and extracted snippet for a reply that cannot be used. */
@Composable
fun BlockedScreen(result: ReplyResult.Blocking, onBack: () -> Unit, contentPadding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(contentPadding).padding(16.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.blocked_title),
                style = MaterialTheme.typography.titleLarge
            )
            result.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            result.snippet?.let {
                Text(
                    stringResource(R.string.blocked_snippet),
                    style = MaterialTheme.typography.titleSmall
                )
                SelectionContainer {
                    Text(
                        it,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.back))
        }
    }
}
