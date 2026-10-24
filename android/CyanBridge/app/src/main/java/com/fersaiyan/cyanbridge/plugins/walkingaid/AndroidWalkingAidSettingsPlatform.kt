package com.fersaiyan.cyanbridge.plugins.walkingaid

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.widget.Toast
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.agent.ProSubscriptionRelayClient
import com.fersaiyan.cyanbridge.devices.DeviceCapabilityHelper
import com.fersaiyan.cyanbridge.plugins.walkingaid.vision.LiteRtVisionBackend
import com.fersaiyan.cyanbridge.plugins.walkingaid.vision.VisionFrame
import com.fersaiyan.cyanbridge.shared.plugins.walkingaid.WalkingAidModelOption
import com.fersaiyan.cyanbridge.shared.plugins.walkingaid.WalkingAidSettingsMessage
import com.fersaiyan.cyanbridge.shared.plugins.walkingaid.WalkingAidSettingsPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Android camera, service, LiteRT models and relay behind the Walking Aid settings screen. */
internal class AndroidWalkingAidSettingsPlatform(private val context: Context) : WalkingAidSettingsPlatform {
    override val hasCamera = DeviceCapabilityHelper.hasCamera(context)
    override val cameraUnavailableReason = DeviceCapabilityHelper.unavailableCameraReason(context)

    override fun isServiceRunning() = WalkingAidService.isRunning()

    override fun checkReadiness() = WalkingAidReadinessChecker.checkReadiness(context)

    override suspend fun installModel(entryId: String, onProgress: (percent: Int) -> Unit) = withContext(Dispatchers.IO) {
        val entry = checkNotNull(WalkingAidModelCatalog.findById(entryId)) { "Unknown model $entryId" }
        WalkingAidModelInstaller.install(context, entry) { progress -> onProgress(progress.percent) }
    }

    override suspend fun runLatencyBenchmark(
        depthEnabled: Boolean,
        depthSource: String,
        focusDescription: String,
    ): String = withContext(Dispatchers.IO) {
        val captureStartedAt = SystemClock.elapsedRealtime()
        val capturedThumbnail = WalkingAidImageCapture(context)
            .captureFreshThumbnail("WALKING_AID_BENCHMARK")
        val captureMs = SystemClock.elapsedRealtime() - captureStartedAt
        val report = try {
            val testBitmap = checkNotNull(BitmapFactory.decodeFile(capturedThumbnail.file.absolutePath)) {
                "Fresh glasses thumbnail could not be decoded"
            }
            val inputDescription =
                "Fresh glasses thumbnail, quality ${WalkingAidPreferences.getThumbnailQualityLevel(context)} (${testBitmap.width}x${testBitmap.height})"
            val frame = VisionFrame(
                bitmap = testBitmap,
                timestampMs = capturedThumbnail.captureCommandAtMs,
                captureCommandAtMs = capturedThumbnail.captureCommandAtMs,
                estimatedExposureAtMs = capturedThumbnail.estimatedExposureAtMs,
                receivedAtMs = capturedThumbnail.receivedAtMs,
            )
            val backend = LiteRtVisionBackend(context)
            try {
                val t0 = SystemClock.elapsedRealtime()
                val detResult = backend.detect(frame)
                val t1 = SystemClock.elapsedRealtime()
                val depthResult = if (depthEnabled && depthSource == "local") {
                    backend.estimateDepth(frame)
                } else {
                    null
                }
                val t2 = SystemClock.elapsedRealtime()

                WalkingAidWarningEngine.reset()
                WalkingAidWarningEngine.evaluate(detResult, depthResult, focusDescription)
                val t3 = SystemClock.elapsedRealtime()

                val accelInfo = backend.acceleratorInfo()
                val analysisMs = t3 - t0
                val detectorThroughput = if (!detResult.isError && analysisMs > 0) {
                    String.format("%.1f FPS local detector throughput", 1000f / analysisMs)
                } else {
                    "unavailable because detection failed"
                }
                val inferenceValue = if (detResult.isError) {
                    "not completed"
                } else {
                    "${detResult.inferenceTimeMs} ms"
                }
                val depthValue = when {
                    !depthEnabled -> "skipped (disabled)"
                    depthSource != "local" -> "skipped (cloud source selected)"
                    else -> "${depthResult?.inferenceTimeMs ?: 0} ms"
                }
                val detectorStatus = if (detResult.isError) {
                    "ERROR: ${detResult.errorMessage ?: "unknown detector error"}"
                } else {
                    "OK"
                }
                val delegation = if (accelInfo.totalOperators > 0) {
                    "${accelInfo.delegatedOperators}/${accelInfo.totalOperators}"
                } else {
                    "not reported by LiteRT"
                }

                // Measured start-to-start cadence: run two captures back-to-back
                val secondCaptureStart = SystemClock.elapsedRealtime()
                val secondThumbnail = WalkingAidImageCapture(context)
                    .captureFreshThumbnail("WALKING_AID_BENCHMARK_2")
                val secondCaptureMs = SystemClock.elapsedRealtime() - secondCaptureStart
                secondThumbnail.file.delete()
                val measuredCadenceMs = captureMs + secondCaptureMs
                val measuredFps = if (measuredCadenceMs > 0) 1000f / measuredCadenceMs else 0f

                """
                    Pipeline Latency Benchmark

                    Input: $inputDescription
                    Detector status: $detectorStatus

                    Capture breakdown:
                      BLE command → photo-ready: ${capturedThumbnail.estimatedExposureAtMs - capturedThumbnail.captureCommandAtMs} ms
                      Settle delay: ${WalkingAidPreferences.getPhotoSettleDelayMs(context)} ms
                      BLE transfer + decode: $captureMs ms total
                      Measured start-to-start cadence (2 captures): ${measuredCadenceMs} ms (${String.format("%.2f fps", measuredFps)})

                    Analysis breakdown:
                      Preprocessing: ${detResult.preprocessTimeMs} ms
                      YOLO inference: $inferenceValue
                      Postprocessing (NMS): ${detResult.postprocessTimeMs} ms
                      Detector call total: ${t1 - t0} ms (${detResult.objects.size} objects)
                      Depth estimation: $depthValue
                      Rule evaluation: ${t3 - t2} ms

                    Detector throughput: $analysisMs ms ($detectorThroughput)
                    Capture-to-decision total: $captureMs + $analysisMs = ${captureMs + analysisMs} ms
                    Effective rate at configured ${WalkingAidPreferences.getCaptureIntervalSeconds(context)}s interval: ${String.format("%.2f fps", 1000f / (captureMs + WalkingAidPreferences.getCaptureIntervalSeconds(context) * 1000f).coerceAtLeast(1f))}
                    Hardware accelerator: ${accelInfo.type}
                    Delegated operators: $delegation
                    Details: ${accelInfo.details}
                """.trimIndent()
            } finally {
                backend.close()
                testBitmap.recycle()
            }
        } finally {
            capturedThumbnail.file.delete()
        }
        report
    }

    override suspend fun runAcquisitionBenchmark(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.appendLine("Thumbnail Acquisition Benchmark (quality 0-5)")
        sb.appendLine("Each level: 2 consecutive captures")
        sb.appendLine("Settle delay: ${WalkingAidPreferences.getPhotoSettleDelayMs(context)} ms")
        sb.appendLine()
        for (quality in 0..5) {
            val times = mutableListOf<Long>()
            var sizeBytes = 0L
            var width = 0
            var height = 0
            repeat(2) {
                val start = SystemClock.elapsedRealtime()
                val thumbnail = WalkingAidImageCapture(context)
                    .captureFreshThumbnailWithQuality("ACQ_BENCH_Q$quality", quality)
                val elapsed = SystemClock.elapsedRealtime() - start
                times += elapsed
                sizeBytes = thumbnail.file.length()
                val bmp = BitmapFactory.decodeFile(thumbnail.file.absolutePath)
                if (bmp != null) {
                    width = bmp.width
                    height = bmp.height
                    bmp.recycle()
                }
                thumbnail.file.delete()
            }
            val avg = times.average().toLong()
            sb.appendLine("Quality $quality: ${avg}ms avg, ${width}x${height}, ${sizeBytes / 1024}KB")
        }
        sb.toString().trimEnd()
    }

    override suspend fun translateFocusToEnglish(original: String, source: String): String =
        withContext(Dispatchers.IO) { WalkingAidFocusTranslator.create(context).translateToEnglish(original, source) }

    override suspend fun fetchCloudModels(): List<WalkingAidModelOption> = withContext(Dispatchers.IO) {
        ProSubscriptionRelayClient.fetchAvailableModels(context).getOrThrow().map { WalkingAidModelOption(it.id, it.label) }
    }

    override fun clearImageHistory() {
        WalkingAidImageStore.clear(context)
        Toast.makeText(context, R.string.walking_aid_clear_history, Toast.LENGTH_SHORT).show()
    }

    override fun showMessage(message: WalkingAidSettingsMessage, detail: String?) {
        val reason = detail ?: context.getString(R.string.compose_unknown_error)
        val (text, length) = when (message) {
            WalkingAidSettingsMessage.LATENCY_TEST_FAILED ->
                context.getString(R.string.compose_walking_latency_error, reason) to Toast.LENGTH_LONG
            WalkingAidSettingsMessage.ACQUISITION_TEST_FAILED ->
                context.getString(R.string.compose_walking_acquisition_error, reason) to Toast.LENGTH_LONG
            WalkingAidSettingsMessage.MODELS_LOAD_FAILED ->
                context.getString(R.string.compose_walking_models_load_failed, reason) to Toast.LENGTH_SHORT
        }
        Toast.makeText(context, text, length).show()
    }
}
