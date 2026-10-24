package com.fersaiyan.cyanbridge.ai.live

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.agent.ProSubscriptionServerPrefs
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * CyanBridge relay side-channel for a Live session: reservation release, metered usage debit
 * and nonbillable vision diagnostics. Every call is a no-op without a signed-in https relay.
 */
internal class GeminiLiveRelayReporter(
    context: Context,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    /** Blocking; callers run it on [scope]. */
    fun releaseReservation(
        reservationId: String,
        inputAudioMs: Long,
        outputAudioMs: Long,
        imageCount: Int,
    ) {
        val authToken = ProSubscriptionServerPrefs.getApiToken(appContext).trim()
        val base = AiProviderPrefs.getRelayBaseUrl(appContext).trim().trimEnd('/')
        if (authToken.isBlank() || !base.startsWith("https://")) return
        val request = Request.Builder()
            .url("$base/api/pro/live/end")
            .header("Authorization", "Bearer $authToken")
            .post(
                GeminiLiveRelayPayloads.reservationEnd(reservationId, inputAudioMs, outputAudioMs, imageCount)
                    .toRequestBody("application/json".toMediaType()),
            )
            .build()
        runCatching { http.newCall(request).execute().use { } }
            .onFailure { Log.w(TAG, "Could not release Gemini Live reservation", it) }
    }

    /** Snapshots are built now and posted asynchronously with one retry. */
    fun reportVisionDiagnostics(
        config: LiveTokenConfig,
        reportId: String,
        snapshot: GeminiLiveVisionDiagnostics.Snapshot,
    ) {
        val auth = ProSubscriptionServerPrefs.getApiToken(appContext).trim()
        val base = AiProviderPrefs.getRelayBaseUrl(appContext).trim().trimEnd('/')
        if (auth.isBlank() || !base.startsWith("https://")) return
        val body = GeminiLiveRelayPayloads.visionDiagnostics(
            config = config,
            reportId = reportId,
            deviceClass = DeviceProfileStore.selectedClass(appContext).name,
            refreshInitialOnly = GeminiLiveVisionPreferences.imageDelaySeconds(appContext) == 0,
            snapshot = snapshot,
        )
        scope.launch {
            // Snapshots have deterministic revision IDs; retrying cannot double-count or bill.
            repeat(2) { attempt ->
                val ok = runCatching {
                    val request = Request.Builder().url("$base/api/pro/live/vision")
                        .header("Authorization", "Bearer $auth")
                        .post(body.toRequestBody("application/json".toMediaType())).build()
                    http.newCall(request).execute().use { it.isSuccessful }
                }.getOrDefault(false)
                if (ok) return@launch
                if (attempt == 0) delay(1_000)
            }
            Log.w(TAG, "Live vision diagnostics report failed revision=${snapshot.revision}")
        }
    }

    /**
     * Posts either Google's usageMetadata or audio/image deltas for a private reservation.
     * [onQuotaExhausted] runs on the reporting coroutine when the relay rejects with 402.
     */
    fun reportUsage(
        config: LiveTokenConfig,
        usageMetadata: JSONObject?,
        deltaInputMs: Long,
        deltaOutputMs: Long,
        deltaImages: Int,
        onQuotaExhausted: () -> Unit,
    ) {
        val authToken = ProSubscriptionServerPrefs.getApiToken(appContext).trim()
        val base = AiProviderPrefs.getRelayBaseUrl(appContext).trim().trimEnd('/')
        if (authToken.isBlank() || !base.startsWith("https://")) return
        scope.launch {
            try {
                val body = GeminiLiveRelayPayloads.usage(config.reservationId, usageMetadata, deltaInputMs, deltaOutputMs, deltaImages)
                    .toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("$base/api/pro/live/usage")
                    .header("Authorization", "Bearer $authToken")
                    .post(body)
                    .build()
                http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val json = runCatching { JSONObject(raw) }.getOrNull()
                        val err = json?.optString("error") ?: raw
                        if (resp.code == 402 && err.contains("live_quota_exhausted")) {
                            Log.w(TAG, "Live quota exhausted mid-session, stopping")
                            onQuotaExhausted()
                        } else {
                            Log.w(TAG, "Live usage report failed code=${resp.code} err=$err")
                        }
                    } else {
                        Log.d(TAG, "Live usage reported ok deltaInput=$deltaInputMs deltaOutput=$deltaOutputMs images=$deltaImages usage=$usageMetadata")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Live usage report exception", e)
            }
        }
    }

    private companion object {
        const val TAG = "GeminiLiveClient"
    }
}

/** JSON bodies posted to the CyanBridge relay's /api/pro/live endpoints. */
internal object GeminiLiveRelayPayloads {
    fun reservationEnd(reservationId: String, inputAudioMs: Long, outputAudioMs: Long, imageCount: Int): String =
        JSONObject()
            .put("reservation_id", reservationId)
            .put("input_audio_ms", inputAudioMs)
            .put("output_audio_ms", outputAudioMs)
            .put("image_count", imageCount)
            .toString()

    fun usage(
        reservationId: String,
        usageMetadata: JSONObject?,
        deltaInputMs: Long,
        deltaOutputMs: Long,
        deltaImages: Int,
    ): String = JSONObject().apply {
        put("reservation_id", reservationId)
        if (usageMetadata != null) put("usage_metadata", usageMetadata) else {
            put("delta_input_audio_ms", deltaInputMs)
            put("delta_output_audio_ms", deltaOutputMs)
            put("delta_image_count", deltaImages)
            put("input_audio_ms", deltaInputMs)
            put("output_audio_ms", deltaOutputMs)
            put("image_count", deltaImages)
        }
    }.toString()

    fun visionDiagnostics(
        config: LiveTokenConfig,
        reportId: String,
        deviceClass: String,
        refreshInitialOnly: Boolean,
        snapshot: GeminiLiveVisionDiagnostics.Snapshot,
    ): String = JSONObject()
        .put("report_id", reportId)
        .put("session_id", if (config.freeTier || config.economy) reportId else config.reservationId)
        .put("mode", if (config.economy) "economy" else if (config.freeTier) "free" else "private")
        .put("device_class", deviceClass)
        .put("refresh_mode", if (refreshInitialOnly) "initial_only" else "every_turn")
        .put("revision", snapshot.revision)
        .put("capture_attempts", snapshot.captureAttempts)
        .put("captures_completed", snapshot.capturesCompleted)
        .put("capture_failures", snapshot.captureFailures)
        .put("images_queued", snapshot.imagesQueued)
        .put("send_failures", snapshot.sendFailures)
        .put("photo_ready_timeouts", snapshot.photoReadyTimeouts)
        .put("transfer_failures", snapshot.transferFailures)
        .toString()
}
