package com.fersaiyan.cyanbridge.shared.ui.localmodels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.localmodels.benchmark.LocalModelBenchmarkMeasurement
import com.fersaiyan.cyanbridge.shared.format.formatFixed
import com.fersaiyan.cyanbridge.shared.localmodels.LocalModelTestUiState
import com.fersaiyan.cyanbridge.shared.localmodels.signedPercent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelTestScreen(
    state: LocalModelTestUiState,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model performance test") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(state.modelName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (state.runtime.isNotBlank()) {
                        Text(
                            "${state.runtime} • ${state.requestedBackend}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(state.phase, style = MaterialTheme.typography.bodyMedium)
                    if (state.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    state.mtpSupported?.let {
                        Text(
                            if (it) "MTP capability: supported" else "MTP capability: not supported",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            state.mtpOff?.let { MeasurementCard(title = "MTP off", measurement = it) }
            state.mtpOn?.let { MeasurementCard(title = "MTP on", measurement = it) }

            if (state.legacyOutputTokensPerSecond != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Baseline", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        MetricRow("Output speed", "${format2(state.legacyOutputTokensPerSecond)} tok/s")
                        state.legacyElapsedMs?.let { MetricRow("Elapsed", "${it} ms") }
                    }
                }
            }

            if (state.recommendation.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Automatic MTP", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(state.recommendation)
                        state.speedDeltaPercent?.let {
                            Text(
                                "Decode speed change: ${signedPercent(it)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (state.note.isNotBlank()) {
                Text(state.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.error.isNotBlank()) {
                Text(
                    state.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun MeasurementCard(title: String, measurement: LocalModelBenchmarkMeasurement) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            MetricRow("Initialization", "${measurement.initTimeMs} ms")
            MetricRow("Time to first token", "${measurement.timeToFirstTokenMs} ms")
            HorizontalDivider()
            MetricRow(
                "Prefill speed",
                "${format2(measurement.prefillTokensPerSecond)} tok/s (${measurement.prefillTokens} tokens)",
            )
            MetricRow(
                "Token generation speed",
                "${format2(measurement.decodeTokensPerSecond)} tok/s (${measurement.decodeTokens} tokens)",
            )
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun format2(value: Double): String = formatFixed(value, 2)
