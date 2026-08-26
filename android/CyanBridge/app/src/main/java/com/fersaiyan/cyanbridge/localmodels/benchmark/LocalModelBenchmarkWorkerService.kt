package com.fersaiyan.cyanbridge.localmodels.benchmark

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.ResultReceiver
import android.util.Log
import com.fersaiyan.cyanbridge.localmodels.engine.LiteRtLocalInferenceEngine
import com.fersaiyan.cyanbridge.localmodels.settings.LocalComputeBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

object LocalModelBenchmarkWorkerProtocol {
    const val ACTION_ABORT = "com.fersaiyan.cyanbridge.localmodels.benchmark.ABORT"
    const val EXTRA_MODEL_PATH = "model_path"
    const val EXTRA_BACKEND = "backend"
    const val EXTRA_CPU_THREADS = "cpu_threads"
    const val EXTRA_RECEIVER = "receiver"

    const val RESULT_CAPABILITY = 1
    const val RESULT_WARMING_UP = 2
    const val RESULT_MEASURING = 3
    const val RESULT_SUCCESS = 4
    const val RESULT_ERROR = 5
    const val RESULT_TIMEOUT = 6

    const val KEY_MTP_SUPPORTED = "mtp_supported"
    const val KEY_INIT_TIME_MS = "init_time_ms"
    const val KEY_TTFT_MS = "ttft_ms"
    const val KEY_PREFILL_TOKENS = "prefill_tokens"
    const val KEY_DECODE_TOKENS = "decode_tokens"
    const val KEY_PREFILL_TPS = "prefill_tps"
    const val KEY_DECODE_TPS = "decode_tps"
    const val KEY_ERROR = "error"
}

abstract class LocalModelBenchmarkWorkerService : Service() {
    protected abstract val mtpEnabled: Boolean

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val finished = AtomicBoolean(false)
    private var receiver: ResultReceiver? = null
    @Volatile private var phase = "capability inspection"

    private val watchdog = Runnable {
        Log.e(TAG, "Worker watchdog fired mtp=$mtpEnabled pid=${Process.myPid()}")
        finish(
            LocalModelBenchmarkWorkerProtocol.RESULT_TIMEOUT,
            Bundle().apply {
                putString(
                    LocalModelBenchmarkWorkerProtocol.KEY_ERROR,
                    "MTP ${if (mtpEnabled) "on" else "off"} $phase timed out after " +
                        "${LocalModelBenchmarkRunner.WORKER_TIMEOUT_MS / 1_000} seconds.",
                )
            },
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == LocalModelBenchmarkWorkerProtocol.ACTION_ABORT) {
            Process.killProcess(Process.myPid())
            return START_NOT_STICKY
        }
        if (receiver != null) return START_NOT_STICKY

        val modelPath = intent?.getStringExtra(LocalModelBenchmarkWorkerProtocol.EXTRA_MODEL_PATH)
        val backend = intent?.getStringExtra(LocalModelBenchmarkWorkerProtocol.EXTRA_BACKEND)
            ?.let { runCatching { LocalComputeBackend.valueOf(it) }.getOrNull() }
        val cpuThreads = intent?.getIntExtra(LocalModelBenchmarkWorkerProtocol.EXTRA_CPU_THREADS, 4) ?: 4
        receiver = intent?.resultReceiverExtra(LocalModelBenchmarkWorkerProtocol.EXTRA_RECEIVER)
        if (modelPath.isNullOrBlank() || backend == null || receiver == null) {
            finishError("Benchmark worker received invalid input.")
            return START_NOT_STICKY
        }

        handler.postDelayed(watchdog, LocalModelBenchmarkRunner.WORKER_TIMEOUT_MS)
        Log.i(TAG, "Starting worker mtp=$mtpEnabled backend=$backend pid=${Process.myPid()}")
        scope.launch {
            runCatching {
                val mtpSupported = LiteRtLocalInferenceEngine.supportsSpeculativeDecoding(modelPath)
                if (!mtpEnabled) {
                    receiver?.send(
                        LocalModelBenchmarkWorkerProtocol.RESULT_CAPABILITY,
                        Bundle().apply {
                            putBoolean(LocalModelBenchmarkWorkerProtocol.KEY_MTP_SUPPORTED, mtpSupported)
                        },
                    )
                }
                check(!mtpEnabled || mtpSupported) {
                    "This model package does not support MTP/speculative decoding."
                }
                val measurement = LocalModelBenchmarkRunner.runMeasurementInWorker(
                    context = applicationContext,
                    modelPath = modelPath,
                    backend = backend,
                    cpuThreads = cpuThreads,
                    mtpEnabled = mtpEnabled,
                ) { progress ->
                    phase = if (progress == LocalModelBenchmarkWorkerProtocol.RESULT_WARMING_UP) {
                        "warm-up/initialization"
                    } else {
                        "measurement"
                    }
                    receiver?.send(progress, Bundle.EMPTY)
                }
                Log.i(TAG, "Worker completed mtp=$mtpEnabled pid=${Process.myPid()}")
                Bundle().apply {
                    putBoolean(LocalModelBenchmarkWorkerProtocol.KEY_MTP_SUPPORTED, mtpSupported)
                    putLong(LocalModelBenchmarkWorkerProtocol.KEY_INIT_TIME_MS, measurement.initTimeMs)
                    putLong(LocalModelBenchmarkWorkerProtocol.KEY_TTFT_MS, measurement.timeToFirstTokenMs)
                    putInt(LocalModelBenchmarkWorkerProtocol.KEY_PREFILL_TOKENS, measurement.prefillTokens)
                    putInt(LocalModelBenchmarkWorkerProtocol.KEY_DECODE_TOKENS, measurement.decodeTokens)
                    putDouble(LocalModelBenchmarkWorkerProtocol.KEY_PREFILL_TPS, measurement.prefillTokensPerSecond)
                    putDouble(LocalModelBenchmarkWorkerProtocol.KEY_DECODE_TPS, measurement.decodeTokensPerSecond)
                }
            }.fold(
                onSuccess = { finish(LocalModelBenchmarkWorkerProtocol.RESULT_SUCCESS, it) },
                onFailure = {
                    Log.e(TAG, "Worker failed mtp=$mtpEnabled pid=${Process.myPid()}", it)
                    finishError(it.message ?: it::class.java.simpleName)
                },
            )
        }
        return START_NOT_STICKY
    }

    private fun finishError(message: String) {
        finish(
            LocalModelBenchmarkWorkerProtocol.RESULT_ERROR,
            Bundle().apply { putString(LocalModelBenchmarkWorkerProtocol.KEY_ERROR, message) },
        )
    }

    private fun finish(resultCode: Int, data: Bundle) {
        if (!finished.compareAndSet(false, true)) return
        handler.removeCallbacks(watchdog)
        receiver?.send(resultCode, data)
        stopSelf()
        // JNI teardown is process-scoped. Do not reuse native LiteRT state for another run.
        handler.postDelayed({ Process.killProcess(Process.myPid()) }, 250L)
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "LocalModelBenchmark"
    }
}

class LocalModelBenchmarkMtpOffService : LocalModelBenchmarkWorkerService() {
    override val mtpEnabled = false
}

class LocalModelBenchmarkMtpOnService : LocalModelBenchmarkWorkerService() {
    override val mtpEnabled = true
}

@Suppress("DEPRECATION")
private fun Intent.resultReceiverExtra(key: String): ResultReceiver? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, ResultReceiver::class.java)
    } else {
        getParcelableExtra(key)
    }
