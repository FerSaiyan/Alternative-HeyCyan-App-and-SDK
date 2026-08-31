package com.fersaiyan.cyanbridge.shared.ui.debug

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import com.fersaiyan.cyanbridge.shared.generated.resources.*
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun TranscriptionDebugScreen(
    endpointUrl: String,
    apiKey: String,
    useHttp: Boolean,
    transcriptStorageEnabled: Boolean,
    latestSessionInfo: String,
    isTranscribing: Boolean,
    progress: Int,
    progressText: String,
    persistedText: String,
    output: String,
    onEndpointUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onUseHttpChange: (Boolean) -> Unit,
    onStorageEnabledChange: (Boolean) -> Unit,
    onSaveEndpoint: () -> Unit,
    onLoadLatest: () -> Unit,
    onTranscribe: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
         topBar = { TopAppBar(title = { Text(stringResource(Res.string.diagnostics_title)) }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
             item { DiagnosticsHero() }
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                         Text(
                             stringResource(Res.string.diagnostics_provider),
                             style = MaterialTheme.typography.titleLarge,
                             fontWeight = FontWeight.Bold,
                         )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = !useHttp, onClick = { onUseHttpChange(false) })
                             Text(stringResource(Res.string.diagnostics_fake))
                            RadioButton(selected = useHttp, onClick = { onUseHttpChange(true) })
                             Text(stringResource(Res.string.diagnostics_http))
                        }
                        OutlinedTextField(
                            value = endpointUrl,
                            onValueChange = onEndpointUrlChange,
                             label = { Text(stringResource(Res.string.diagnostics_endpoint)) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = useHttp,
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = onApiKeyChange,
                             label = { Text(stringResource(Res.string.diagnostics_api_key)) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = useHttp,
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                             Text(stringResource(Res.string.diagnostics_persist), modifier = Modifier.weight(1f))
                            Switch(checked = transcriptStorageEnabled, onCheckedChange = onStorageEnabledChange)
                        }
                         FilledTonalButton(onClick = onSaveEndpoint, modifier = Modifier.fillMaxWidth()) {
                             Text(stringResource(Res.string.diagnostics_save_endpoint))
                         }
                    }
                }
            }
            item {
                OutlinedButton(onClick = onLoadLatest, modifier = Modifier.fillMaxWidth()) {
                     Text(stringResource(Res.string.diagnostics_load_latest))
                }
            }
            item {
                Text(latestSessionInfo, style = MaterialTheme.typography.bodySmall)
            }
            item {
                Button(
                    onClick = onTranscribe,
                    enabled = !isTranscribing,
                    modifier = Modifier.fillMaxWidth(),
                 ) {
                     Text(
                         if (isTranscribing) {
                             stringResource(Res.string.diagnostics_transcribing)
                         } else {
                             stringResource(Res.string.diagnostics_transcribe_latest)
                         },
                     )
                 }
            }
            item {
                AnimatedVisibility(
                    visible = isTranscribing || progressText.isNotBlank(),
                    enter = fadeIn(animationSpec = spring()) + expandVertically(animationSpec = spring()),
                    exit = fadeOut(animationSpec = spring()) + shrinkVertically(animationSpec = spring()),
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = spring()),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            LinearProgressIndicator(
                                progress = { progress.coerceIn(0, 100) / 100f },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)),
                            )
                        Text(progressText, style = MaterialTheme.typography.bodySmall)
                        if (persistedText.isNotBlank()) Text(persistedText, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (output.isNotBlank()) {
                item {
                     Text(stringResource(Res.string.diagnostics_transcript_output), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                        SelectionContainer(modifier = Modifier.padding(20.dp)) {
                            Text(output, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun DiagnosticsHero() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = MaterialTheme.shapes.large,
            ) {
                androidx.compose.material3.Icon(
                    Icons.Outlined.Settings,
                    contentDescription = null,
                    modifier = Modifier.padding(13.dp),
                )
            }
            Text(
                stringResource(Res.string.diagnostics_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(stringResource(Res.string.diagnostics_description), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
