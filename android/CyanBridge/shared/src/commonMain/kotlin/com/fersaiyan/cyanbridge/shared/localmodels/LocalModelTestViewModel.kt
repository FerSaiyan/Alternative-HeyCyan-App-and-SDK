package com.fersaiyan.cyanbridge.shared.localmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.localmodels.benchmark.LocalModelBenchmarkMeasurement
import com.fersaiyan.cyanbridge.localmodels.benchmark.LocalModelBenchmarkProgress
import com.fersaiyan.cyanbridge.shared.format.formatFixed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LocalModelTestUiState(
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

/** The installed model a performance test runs on, with its saved runtime and backend labels. */
data class LocalModelTestTarget(
    val modelName: String,
    val runtime: String,
    val backend: String,
    /** LiteRT-LM packages get the MTP on/off comparison; other runtimes a baseline run. */
    val liteRt: Boolean,
)

data class LocalModelBaseline(val generatedTokens: Int, val elapsedMs: Long, val fallbackReason: String?)

/** The device runtimes behind the model performance test. */
interface LocalModelTestRunner {
    /** The requested (or selected) installed model; null when it is not installed. */
    fun target(): LocalModelTestTarget?
    suspend fun releaseActiveModel()

    /** Runs the LiteRT MTP comparison; [onProgress] may be called from any thread. */
    suspend fun runComparison(onProgress: (LocalModelBenchmarkProgress) -> Unit)

    /** Loads the model on its runtime and runs one warm-up prompt. */
    suspend fun runBaseline(): LocalModelBaseline

    /** Remembers the outcome for the local models screen. */
    fun saveSummary(summary: String)
}

internal fun signedPercent(value: Double): String = formatFixed(value, 1, plusSign = true) + "%"

/** Measures the selected local model: MTP on/off for LiteRT-LM, a baseline for other runtimes. */
class LocalModelTestViewModel(private val runner: LocalModelTestRunner) : ViewModel() {
    private val _state = MutableStateFlow(LocalModelTestUiState())
    val state: StateFlow<LocalModelTestUiState> = _state.asStateFlow()
    private var started = false

    fun start() {
        if (started) return
        started = true
        val target = runner.target()
        if (target == null) {
            _state.update {
                it.copy(running = false, phase = "Unable to start", error = "Select an installed model before running the test.")
            }
            return
        }
        _state.value = LocalModelTestUiState(
            modelName = target.modelName,
            runtime = target.runtime,
            requestedBackend = target.backend,
            running = true,
            phase = "Preparing test",
        )
        viewModelScope.launch {
            runCatching {
                if (target.liteRt) runLiteRtTest() else runBaselineTest(target)
            }.onFailure { error ->
                _state.update {
                    it.copy(running = false, phase = "Test failed", error = error.message ?: error::class.simpleName.orEmpty())
                }
            }
        }
    }

    private suspend fun runLiteRtTest() {
        _state.update { it.copy(phase = "Releasing active model resources") }
        runner.releaseActiveModel()
        runner.runComparison { progress -> viewModelScope.launch { _state.value = _state.value.apply(progress) } }
    }

    private fun LocalModelTestUiState.apply(progress: LocalModelBenchmarkProgress): LocalModelTestUiState = when (progress) {
        LocalModelBenchmarkProgress.InspectingModel -> copy(phase = "Inspecting model capabilities")
        is LocalModelBenchmarkProgress.CapabilityReady -> copy(
            mtpSupported = progress.mtpSupported,
            phase = if (progress.mtpSupported) "MTP supported — preparing comparison" else "MTP not supported — measuring baseline",
        )
        is LocalModelBenchmarkProgress.WarmingUp -> copy(phase = "Warming up • MTP ${progress.mtpEnabled.onOff()}")
        is LocalModelBenchmarkProgress.Measuring -> copy(phase = "Measuring • MTP ${progress.mtpEnabled.onOff()}")
        is LocalModelBenchmarkProgress.MeasurementReady ->
            if (progress.mtpEnabled) copy(mtpOn = progress.measurement) else copy(mtpOff = progress.measurement)
        is LocalModelBenchmarkProgress.Finished -> {
            val result = progress.result
            val summary = buildString {
                append("MTP ")
                append(if (result.recommendMtp) "recommended" else "not recommended")
                if (result.mtpOnTimedOut) append(" (MTP on timed out)")
                result.decodeSpeedChangePercent?.let { append(" (${signedPercent(it)} decode speed)") }
            }
            runner.saveSummary(summary.trim())
            copy(
                running = false,
                phase = "Test complete",
                mtpSupported = result.mtpSupported,
                mtpOff = result.mtpOff,
                mtpOn = result.mtpOn,
                recommendation = when {
                    !result.mtpSupported ->
                        "This model package does not contain MTP/speculative decoding support. Automatic mode will keep MTP off."
                    result.mtpOnTimedOut ->
                        "The MTP-on test did not finish within 120 seconds. Automatic will keep MTP off for this model and backend on this device."
                    result.mtpOnFailure != null ->
                        "The MTP-on test could not finish. Automatic will keep MTP off for this model and backend on this device."
                    result.recommendMtp -> "Automatic will enable MTP for this model and backend on this device."
                    else -> "Automatic will keep MTP off for this model and backend on this device."
                },
                speedDeltaPercent = result.decodeSpeedChangePercent,
                note = result.mtpOnFailure.orEmpty(),
            )
        }
    }

    private suspend fun runBaselineTest(target: LocalModelTestTarget) {
        _state.update {
            it.copy(
                mtpSupported = false,
                phase = "Loading ${target.runtime}",
                note = "MTP comparison is available only for compatible LiteRT-LM packages.",
            )
        }
        val result = runner.runBaseline()
        val outputTps = if (result.elapsedMs > 0L) result.generatedTokens * 1000.0 / result.elapsedMs else 0.0
        _state.update {
            it.copy(
                running = false,
                phase = "Test complete",
                note = result.fallbackReason ?: "This runtime does not expose LiteRT's exact prefill benchmark counters.",
                legacyOutputTokensPerSecond = outputTps,
                legacyElapsedMs = result.elapsedMs,
            )
        }
    }

    private fun Boolean.onOff() = if (this) "on" else "off"
}
