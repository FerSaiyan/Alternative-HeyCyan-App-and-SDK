package com.fersaiyan.cyanbridge.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.localmodels.benchmark.LocalModelBenchmarkMeasurement
import com.fersaiyan.cyanbridge.localmodels.benchmark.LocalModelBenchmarkProgress
import com.fersaiyan.cyanbridge.localmodels.benchmark.LocalModelBenchmarkRunner
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogRepository
import com.fersaiyan.cyanbridge.localmodels.session.LocalChatSessionManager
import com.fersaiyan.cyanbridge.localmodels.settings.LocalGenerationSettings
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelRuntime
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.InstalledLocalModel
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class LocalModelTestActivity : ComponentActivity() {
    private var uiState by mutableStateOf(ModelTestUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appearancePreferences = AppearancePreferences(this)
        setContent {
            val appearance by rememberAppearanceSettings(appearancePreferences)
            CyanBridgeTheme(appearance) {
                LocalModelTestScreen(
                    state = uiState,
                    onBack = { finish() },
                )
            }
        }
        startTest()
    }

    private fun startTest() {
        val requestedId = intent.getStringExtra(EXTRA_MODEL_ID) ?: LocalModelsPrefs.getSelectedModelId(this)
        val model = LocalModelStorageRepository.listInstalled(this).firstOrNull { it.id == requestedId }
        if (model == null) {
            uiState = uiState.copy(
                running = false,
                phase = "Unable to start",
                error = "Select an installed model before running the test.",
            )
            return
        }

        val settings = LocalModelSettingsRepository.getForModel(this, model.id)
        uiState = ModelTestUiState(
            modelName = model.displayName,
            runtime = settings.modelRuntime.label,
            requestedBackend = settings.computeBackend.label,
            running = true,
            phase = "Preparing test",
        )

        lifecycleScope.launch {
            runCatching {
                if (settings.modelRuntime == LocalModelRuntime.LITERT) {
                    runLiteRtTest(model, settings)
                } else {
                    runLegacyRuntimeTest(model, settings)
                }
            }.onFailure { error ->
                uiState = uiState.copy(
                    running = false,
                    phase = "Test failed",
                    error = error.message ?: error::class.java.simpleName,
                )
            }
        }
    }

    private suspend fun runLiteRtTest(
        model: InstalledLocalModel,
        settings: LocalGenerationSettings,
    ) {
        uiState = uiState.copy(phase = "Releasing active model resources")
        LocalChatSessionManager.unload()
        LocalModelBenchmarkRunner.runLiteRtComparison(
            context = this,
            model = model,
            backend = settings.computeBackend,
            cpuThreads = settings.cpuThreads,
        ) { progress ->
            runOnUiThread {
                uiState = when (progress) {
                    LocalModelBenchmarkProgress.InspectingModel ->
                        uiState.copy(phase = "Inspecting model capabilities")

                    is LocalModelBenchmarkProgress.CapabilityReady ->
                        uiState.copy(
                            mtpSupported = progress.mtpSupported,
                            phase = if (progress.mtpSupported) {
                                "MTP supported — preparing comparison"
                            } else {
                                "MTP not supported — measuring baseline"
                            },
                        )

                    is LocalModelBenchmarkProgress.WarmingUp ->
                        uiState.copy(phase = "Warming up • MTP ${progress.mtpEnabled.onOff()}")

                    is LocalModelBenchmarkProgress.Measuring ->
                        uiState.copy(phase = "Measuring • MTP ${progress.mtpEnabled.onOff()}")

                    is LocalModelBenchmarkProgress.MeasurementReady ->
                        if (progress.mtpEnabled) {
                            uiState.copy(mtpOn = progress.measurement)
                        } else {
                            uiState.copy(mtpOff = progress.measurement)
                        }

                    is LocalModelBenchmarkProgress.Finished -> {
                        val result = progress.result
                        val summary = buildString {
                            append("MTP ")
                            append(if (result.recommendMtp) "recommended" else "not recommended")
                            if (result.mtpOnTimedOut) append(" (MTP on timed out)")
                            result.decodeSpeedChangePercent?.let {
                                append(" (${signedPercent(it)} decode speed)")
                            }
                        }
                        LocalModelsPrefs.setLastBenchmark(this, summary)
                        uiState.copy(
                            running = false,
                            phase = "Test complete",
                            mtpSupported = result.mtpSupported,
                            mtpOff = result.mtpOff,
                            mtpOn = result.mtpOn,
                            recommendation = if (!result.mtpSupported) {
                                "This model package does not contain MTP/speculative decoding support. Automatic mode will keep MTP off."
                            } else if (result.mtpOnTimedOut) {
                                "The MTP-on test did not finish within 60 seconds. Automatic will keep MTP off for this model and backend on this device."
                            } else if (result.mtpOnFailure != null) {
                                "The MTP-on test could not finish. Automatic will keep MTP off for this model and backend on this device."
                            } else if (result.recommendMtp) {
                                "Automatic will enable MTP for this model and backend on this device."
                            } else {
                                "Automatic will keep MTP off for this model and backend on this device."
                            },
                            speedDeltaPercent = result.decodeSpeedChangePercent,
                            note = result.mtpOnFailure.orEmpty(),
                        )
                    }
                }
            }
        }
    }

    private suspend fun runLegacyRuntimeTest(
        model: InstalledLocalModel,
        settings: LocalGenerationSettings,
    ) {
        uiState = uiState.copy(
            mtpSupported = false,
            phase = "Loading ${settings.modelRuntime.label}",
            note = "MTP comparison is available only for compatible LiteRT-LM packages.",
        )
        val entry = LocalModelCatalogRepository.findById(model.catalogId)
        val result = withContext(Dispatchers.IO) {
            LocalChatSessionManager.ensureModelLoaded(
                context = this@LocalModelTestActivity,
                model = model,
                catalogEntry = entry,
                settings = settings,
            )
            LocalChatSessionManager.runWarmupProbe(settings) { }
        }
        val outputTps = if (result.elapsedMs > 0L) {
            result.generatedTokens * 1000.0 / result.elapsedMs
        } else {
            0.0
        }
        uiState = uiState.copy(
            running = false,
            phase = "Test complete",
            note = result.fallbackReason
                ?: "This runtime does not expose LiteRT's exact prefill benchmark counters.",
            legacyOutputTokensPerSecond = outputTps,
            legacyElapsedMs = result.elapsedMs,
        )
    }

    companion object {
        const val EXTRA_MODEL_ID = "model_id"
    }
}

private data class ModelTestUiState(
    val modelName: String = "Local model",
    val runtime: String = "",
    val requestedBackend: String = "",
    val running: Boolean = false,
    val phase: String = "Starting",
    val mtpSupported: Boolean? = null,
    val mtpOff: LocalModelBenchmarkMeasurement? = null,
    val mtpOn: LocalModelBenchmarkMeasurement? = null,
    val recommendation: String = "",
    val speedDeltaPercent: Double? = null,
    val note: String = "",
    val error: String = "",
    val legacyOutputTokensPerSecond: Double? = null,
    val legacyElapsedMs: Long? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalModelTestScreen(
    state: ModelTestUiState,
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

private fun Boolean.onOff() = if (this) "on" else "off"

private fun signedPercent(value: Double): String =
    String.format(Locale.US, "%+.1f%%", value)

private fun format2(value: Double): String =
    String.format(Locale.US, "%.2f", value)
