package com.fersaiyan.cyanbridge.localmodels.benchmark

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

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
    val mtpOnFailure: String? = null,
    val mtpOnTimedOut: Boolean = false,
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
        var mtpSupported = false
        val offOutcome = runWorkerMeasurement(
            context = context,
            modelPath = file.absolutePath,
            backend = backend,
            cpuThreads = cpuThreads,
            mtpEnabled = false,
            onProgress = onProgress,
        )
        val off = when (offOutcome) {
            is WorkerOutcome.Success -> {
                mtpSupported = offOutcome.mtpSupported
                offOutcome.measurement
            }
            is WorkerOutcome.Failure -> throw IllegalStateException(offOutcome.message)
        }
        onProgress(LocalModelBenchmarkProgress.MeasurementReady(false, off))

        val onOutcome = if (mtpSupported) {
            runWorkerMeasurement(
                context = context,
                modelPath = file.absolutePath,
                backend = backend,
                cpuThreads = cpuThreads,
                mtpEnabled = true,
                onProgress = onProgress,
            )
        } else {
            null
        }
        val on = (onOutcome as? WorkerOutcome.Success)?.measurement
        if (on != null) onProgress(LocalModelBenchmarkProgress.MeasurementReady(true, on))
        val onFailure = (onOutcome as? WorkerOutcome.Failure)?.message
        val onTimedOut = (onOutcome as? WorkerOutcome.Failure)?.timedOut == true

        val recommend = on?.let {
            LocalMtpBenchmarkPolicy.recommend(
                mtpOffDecodeTokensPerSecond = off.decodeTokensPerSecond,
                mtpOnDecodeTokensPerSecond = it.decodeTokensPerSecond,
                mtpOffTimeToFirstTokenMs = off.timeToFirstTokenMs,
                mtpOnTimeToFirstTokenMs = it.timeToFirstTokenMs,
            )
        } ?: false

        if (mtpSupported) {
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
                    mtpOnOutputTokensPerSecond = on?.decodeTokensPerSecond,
                    mtpOffTimeToFirstTokenMs = off.timeToFirstTokenMs,
                    mtpOnTimeToFirstTokenMs = on?.timeToFirstTokenMs,
                    recommendMtp = recommend,
                    mtpOnFailure = onFailure,
                ),
            )
        }

        val result = LocalModelBenchmarkResult(
            mtpSupported = mtpSupported,
            mtpOff = off,
            mtpOn = on,
            recommendMtp = recommend,
            mtpOnFailure = onFailure,
            mtpOnTimedOut = onTimedOut,
        )
        onProgress(LocalModelBenchmarkProgress.Finished(result))
        result
    }

    private suspend fun runWorkerMeasurement(
        context: Context,
        modelPath: String,
        backend: LocalComputeBackend,
        cpuThreads: Int,
        mtpEnabled: Boolean,
        onProgress: (LocalModelBenchmarkProgress) -> Unit,
    ): WorkerOutcome {
        val serviceClass = if (mtpEnabled) {
            LocalModelBenchmarkMtpOnService::class.java
        } else {
            LocalModelBenchmarkMtpOffService::class.java
        }
        val completed = AtomicBoolean(false)
        val outcome = withTimeoutOrNull(WORKER_RESULT_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                        when (resultCode) {
                            LocalModelBenchmarkWorkerProtocol.RESULT_CAPABILITY -> {
                                val supported = resultData?.getBoolean(
                                    LocalModelBenchmarkWorkerProtocol.KEY_MTP_SUPPORTED,
                                ) == true
                                onProgress(LocalModelBenchmarkProgress.CapabilityReady(supported))
                            }
                            LocalModelBenchmarkWorkerProtocol.RESULT_WARMING_UP ->
                                onProgress(LocalModelBenchmarkProgress.WarmingUp(mtpEnabled))
                            LocalModelBenchmarkWorkerProtocol.RESULT_MEASURING ->
                                onProgress(LocalModelBenchmarkProgress.Measuring(mtpEnabled))
                            LocalModelBenchmarkWorkerProtocol.RESULT_SUCCESS -> {
                                if (completed.compareAndSet(false, true)) {
                                    continuation.resume(
                                        WorkerOutcome.Success(
                                            measurement = requireNotNull(resultData).toMeasurement(mtpEnabled),
                                            mtpSupported = resultData.getBoolean(
                                                LocalModelBenchmarkWorkerProtocol.KEY_MTP_SUPPORTED,
                                            ),
                                        ),
                                    )
                                }
                            }
                            LocalModelBenchmarkWorkerProtocol.RESULT_TIMEOUT,
                            LocalModelBenchmarkWorkerProtocol.RESULT_ERROR -> {
                                if (completed.compareAndSet(false, true)) {
                                    continuation.resume(
                                        WorkerOutcome.Failure(
                                            message = resultData
                                                ?.getString(LocalModelBenchmarkWorkerProtocol.KEY_ERROR)
                                                ?: "MTP ${mtpEnabled.onOff()} benchmark worker stopped unexpectedly.",
                                            timedOut = resultCode == LocalModelBenchmarkWorkerProtocol.RESULT_TIMEOUT,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
                val intent = Intent(context, serviceClass)
                    .putExtra(LocalModelBenchmarkWorkerProtocol.EXTRA_MODEL_PATH, modelPath)
                    .putExtra(LocalModelBenchmarkWorkerProtocol.EXTRA_BACKEND, backend.name)
                    .putExtra(LocalModelBenchmarkWorkerProtocol.EXTRA_CPU_THREADS, cpuThreads)
                    .putExtra(LocalModelBenchmarkWorkerProtocol.EXTRA_RECEIVER, receiver)
                val started = runCatching { context.startService(intent) }.getOrNull()
                if (started == null && completed.compareAndSet(false, true)) {
                    continuation.resume(WorkerOutcome.Failure("Unable to start the benchmark worker.", false))
                }
                continuation.invokeOnCancellation {
                    abortWorker(context, serviceClass)
                }
            }
        }
        if (outcome != null) return outcome
        completed.set(true)
        abortWorker(context, serviceClass)
        return WorkerOutcome.Failure(
            message = "MTP ${mtpEnabled.onOff()} benchmark did not finish within ${WORKER_TIMEOUT_MS / 1_000} seconds.",
            timedOut = true,
        )
    }

    private fun abortWorker(context: Context, serviceClass: Class<*>) {
        runCatching {
            context.startService(
                Intent(context, serviceClass).setAction(LocalModelBenchmarkWorkerProtocol.ACTION_ABORT),
            )
        }
    }

    @OptIn(ExperimentalApi::class)
    internal fun runMeasurementInWorker(
        context: Context,
        modelPath: String,
        backend: LocalComputeBackend,
        cpuThreads: Int,
        mtpEnabled: Boolean,
        onProgress: (Int) -> Unit,
    ): LocalModelBenchmarkMeasurement {
        val runtimeBackend = backendFor(context, backend, cpuThreads)
        val cacheDir = File(
            context.cacheDir,
            "local-model-benchmark/${if (mtpEnabled) "mtp-on" else "mtp-off"}",
        ).apply { mkdirs() }
        val previous = ExperimentalFlags.enableSpeculativeDecoding
        try {
            ExperimentalFlags.enableSpeculativeDecoding = mtpEnabled
            onProgress(LocalModelBenchmarkWorkerProtocol.RESULT_WARMING_UP)
            benchmark(
                modelPath = modelPath,
                backend = runtimeBackend,
                prefillTokens = 32,
                decodeTokens = 16,
                cacheDir = cacheDir.path,
                prompt = BENCHMARK_PROMPT,
            )

            onProgress(LocalModelBenchmarkWorkerProtocol.RESULT_MEASURING)
            return benchmark(
                modelPath = modelPath,
                backend = runtimeBackend,
                prefillTokens = 128,
                decodeTokens = 64,
                cacheDir = cacheDir.path,
                prompt = BENCHMARK_PROMPT,
            ).toMeasurement(mtpEnabled)
        } finally {
            ExperimentalFlags.enableSpeculativeDecoding = previous
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

    private fun Bundle.toMeasurement(mtpEnabled: Boolean) = LocalModelBenchmarkMeasurement(
        mtpEnabled = mtpEnabled,
        initTimeMs = getLong(LocalModelBenchmarkWorkerProtocol.KEY_INIT_TIME_MS),
        timeToFirstTokenMs = getLong(LocalModelBenchmarkWorkerProtocol.KEY_TTFT_MS),
        prefillTokens = getInt(LocalModelBenchmarkWorkerProtocol.KEY_PREFILL_TOKENS),
        decodeTokens = getInt(LocalModelBenchmarkWorkerProtocol.KEY_DECODE_TOKENS),
        prefillTokensPerSecond = getDouble(LocalModelBenchmarkWorkerProtocol.KEY_PREFILL_TPS),
        decodeTokensPerSecond = getDouble(LocalModelBenchmarkWorkerProtocol.KEY_DECODE_TPS),
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
    internal const val WORKER_TIMEOUT_MS = 60_000L
    private const val WORKER_RESULT_TIMEOUT_MS = WORKER_TIMEOUT_MS + 5_000L

    private sealed interface WorkerOutcome {
        data class Success(
            val measurement: LocalModelBenchmarkMeasurement,
            val mtpSupported: Boolean,
        ) : WorkerOutcome

        data class Failure(val message: String, val timedOut: Boolean) : WorkerOutcome
    }
}

private fun Boolean.onOff() = if (this) "on" else "off"
