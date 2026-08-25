package com.fersaiyan.cyanbridge.localmodels.benchmark

import android.content.Context
import com.fersaiyan.cyanbridge.localmodels.engine.LiteRtLocalInferenceEngine
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import com.fersaiyan.cyanbridge.localmodels.settings.LocalMtpBenchmarkRecord
import com.fersaiyan.cyanbridge.localmodels.settings.LocalMtpSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.InstalledLocalModel
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.benchmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed interface LocalModelBenchmarkProgress {
    data object InspectingModel : LocalModelBenchmarkProgress
    data class CapabilityReady(val mtpSupported: Boolean) : LocalModelBenchmarkProgress
    data class WarmingUp(val mtpEnabled: Boolean) : LocalModelBenchmarkProgress
    data class Measuring(val mtpEnabled: Boolean) : LocalModelBenchmarkProgress
    data class MeasurementReady(
        val mtpEnabled: Boolean,
        val measurement: LocalModelBenchmarkMeasurement,
    ) : LocalModelBenchmarkProgress
    data class Finished(val result: LocalModelBenchmarkResult) : LocalModelBenchmarkProgress
}

data class LocalModelBenchmarkMeasurement(
    val mtpEnabled: Boolean,
    val initTimeMs: Long,
    val timeToFirstTokenMs: Long,
    val prefillTokens: Int,
    val decodeTokens: Int,
    val prefillTokensPerSecond: Double,
    val decodeTokensPerSecond: Double,
)

data class LocalModelBenchmarkResult(
    val mtpSupported: Boolean,
    val mtpOff: LocalModelBenchmarkMeasurement,
    val mtpOn: LocalModelBenchmarkMeasurement?,
    val recommendMtp: Boolean,
) {
    val decodeSpeedChangePercent: Double?
        get() = mtpOn?.let { on ->
            if (mtpOff.decodeTokensPerSecond <= 0.0) null
            else ((on.decodeTokensPerSecond / mtpOff.decodeTokensPerSecond) - 1.0) * 100.0
        }
}

object LocalMtpBenchmarkPolicy {
    fun recommend(
        mtpOffDecodeTokensPerSecond: Double,
        mtpOnDecodeTokensPerSecond: Double,
        mtpOffTimeToFirstTokenMs: Long,
        mtpOnTimeToFirstTokenMs: Long,
    ): Boolean {
        if (mtpOffDecodeTokensPerSecond <= 0.0 || mtpOnDecodeTokensPerSecond <= 0.0) return false
        val decodeImprovement = mtpOnDecodeTokensPerSecond / mtpOffDecodeTokensPerSecond
        val ttftLimit = maxOf(
            (mtpOffTimeToFirstTokenMs * 1.35).toLong(),
            mtpOffTimeToFirstTokenMs + 250L,
        )
        return decodeImprovement >= 1.05 && mtpOnTimeToFirstTokenMs <= ttftLimit
    }
}

object LocalModelBenchmarkRunner {
    private val benchmarkLock = Any()

    suspend fun runLiteRtComparison(
        context: Context,
        model: InstalledLocalModel,
        backend: LocalComputeBackend,
        cpuThreads: Int,
        onProgress: (LocalModelBenchmarkProgress) -> Unit,
    ): LocalModelBenchmarkResult = withContext(Dispatchers.IO) {
        val file = File(model.absolutePath)
        require(file.exists()) { "Selected model file is missing" }

        onProgress(LocalModelBenchmarkProgress.InspectingModel)
        val mtpSupported = LiteRtLocalInferenceEngine.supportsSpeculativeDecoding(file.absolutePath)
        onProgress(LocalModelBenchmarkProgress.CapabilityReady(mtpSupported))

        val runtimeBackend = backendFor(context, backend, cpuThreads)
        val off = runMeasurement(
            context = context,
            modelPath = file.absolutePath,
            backend = runtimeBackend,
            mtpEnabled = false,
            onProgress = onProgress,
        )
        onProgress(LocalModelBenchmarkProgress.MeasurementReady(false, off))

        val on = if (mtpSupported) {
            val result = runMeasurement(
                context = context,
                modelPath = file.absolutePath,
                backend = runtimeBackend,
                mtpEnabled = true,
                onProgress = onProgress,
            )
            onProgress(LocalModelBenchmarkProgress.MeasurementReady(true, result))
            result
        } else {
            null
        }

        val recommend = on?.let {
            LocalMtpBenchmarkPolicy.recommend(
                mtpOffDecodeTokensPerSecond = off.decodeTokensPerSecond,
                mtpOnDecodeTokensPerSecond = it.decodeTokensPerSecond,
                mtpOffTimeToFirstTokenMs = off.timeToFirstTokenMs,
                mtpOnTimeToFirstTokenMs = it.timeToFirstTokenMs,
            )
        } ?: false

        if (on != null) {
            LocalMtpSettingsRepository.saveBenchmark(
                context = context,
                modelId = model.id,
                backend = backend,
                modelSignature = LocalMtpSettingsRepository.modelSignature(
                    path = file.absolutePath,
                    sizeBytes = file.length(),
                    lastModifiedMs = file.lastModified(),
                ),
                record = LocalMtpBenchmarkRecord(
                    mtpOffOutputTokensPerSecond = off.decodeTokensPerSecond,
                    mtpOnOutputTokensPerSecond = on.decodeTokensPerSecond,
                    mtpOffTimeToFirstTokenMs = off.timeToFirstTokenMs,
                    mtpOnTimeToFirstTokenMs = on.timeToFirstTokenMs,
                    recommendMtp = recommend,
                ),
            )
        }

        val result = LocalModelBenchmarkResult(
            mtpSupported = mtpSupported,
            mtpOff = off,
            mtpOn = on,
            recommendMtp = recommend,
        )
        onProgress(LocalModelBenchmarkProgress.Finished(result))
        result
    }

    @OptIn(ExperimentalApi::class)
    private fun runMeasurement(
        context: Context,
        modelPath: String,
        backend: Backend,
        mtpEnabled: Boolean,
        onProgress: (LocalModelBenchmarkProgress) -> Unit,
    ): LocalModelBenchmarkMeasurement {
        return synchronized(benchmarkLock) {
            val previous = ExperimentalFlags.enableSpeculativeDecoding
            try {
                ExperimentalFlags.enableSpeculativeDecoding = mtpEnabled
                onProgress(LocalModelBenchmarkProgress.WarmingUp(mtpEnabled))
                // Keep a short warm-up separate so shader/kernel compilation does not dominate the
                // measured pass.
                benchmark(
                    modelPath = modelPath,
                    backend = backend,
                    prefillTokens = 32,
                    decodeTokens = 16,
                    cacheDir = context.cacheDir.path,
                    prompt = BENCHMARK_PROMPT,
                )

                onProgress(LocalModelBenchmarkProgress.Measuring(mtpEnabled))
                benchmark(
                    modelPath = modelPath,
                    backend = backend,
                    prefillTokens = 128,
                    decodeTokens = 64,
                    cacheDir = context.cacheDir.path,
                    prompt = BENCHMARK_PROMPT,
                ).toMeasurement(mtpEnabled)
            } finally {
                ExperimentalFlags.enableSpeculativeDecoding = previous
            }
        }
    }

    private fun BenchmarkInfo.toMeasurement(mtpEnabled: Boolean) = LocalModelBenchmarkMeasurement(
        mtpEnabled = mtpEnabled,
        initTimeMs = (initTimeInSecond * 1000.0).toLong().coerceAtLeast(0L),
        timeToFirstTokenMs = (timeToFirstTokenInSecond * 1000.0).toLong().coerceAtLeast(0L),
        prefillTokens = lastPrefillTokenCount,
        decodeTokens = lastDecodeTokenCount,
        prefillTokensPerSecond = lastPrefillTokensPerSecond,
        decodeTokensPerSecond = lastDecodeTokensPerSecond,
    )

    private fun backendFor(
        context: Context,
        backend: LocalComputeBackend,
        cpuThreads: Int,
    ): Backend = when (backend) {
        LocalComputeBackend.GPU -> Backend.GPU()
        LocalComputeBackend.CPU -> Backend.CPU(cpuThreads.coerceIn(1, 16))
        LocalComputeBackend.NPU_EXPERIMENTAL -> Backend.NPU(
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
        )
    }

    private const val BENCHMARK_PROMPT =
        "CyanBridge is testing local model responsiveness. Give a concise description of why low latency matters for a wearable voice assistant."
}
