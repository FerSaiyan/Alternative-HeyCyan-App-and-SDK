package com.fersaiyan.cyanbridge.ai.live

import android.content.Context
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Feeds visual context only while Gemini Live itself is active.
 * Other Pro models/providers never instantiate or call this controller.
 *
 * Mutable scheduling state is owned by [scope] on the main dispatcher. Audio activity can arrive
 * from an IO coroutine, so it is dispatched onto this scope before touching that state.
 */
class GeminiLiveVisionController(
    context: Context,
    private val client: GeminiLiveClient,
    private val onStatus: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val captureInProgress = AtomicBoolean(false)

    @Volatile
    private var active = false
    private var userSpeaking = false
    @Volatile
    private var lastAutomaticStillMs = 0L
    private var stillJob: Job? = null

    val deviceClass: DeviceClass
        get() = DeviceProfileStore.selectedClass(appContext)

    val capabilities: GeminiLiveVisionCapabilities
        get() = GeminiLiveVisionPolicy.forDevice(deviceClass)

    private val automaticRefreshIntervalMs: Long?
        get() = GeminiLiveVisionPreferences.automaticRefreshIntervalMs(appContext)

    fun start() {
        if (active) return
        active = true
        if (automaticRefreshIntervalMs == null) {
            onStatus("Glasses vision: only the initial image; manual AI-photo button remains available")
            return
        }
        when (capabilities.mode) {
            GeminiLiveVisionCapabilities.Mode.OPPORTUNISTIC_STILL -> {
                onStatus("Glasses vision: fresh stills on eligible speech turns")
            }
            GeminiLiveVisionCapabilities.Mode.MANUAL_STILL -> {
                onStatus("Glasses vision: manual still images")
            }
            GeminiLiveVisionCapabilities.Mode.NONE -> {
                onStatus("Glasses vision: no compatible live camera source detected")
            }
        }
    }

    fun stop() {
        active = false
        userSpeaking = false
        stillJob?.cancel()
        stillJob = null
        client.cancelDeferredVisualContext()
        captureInProgress.set(false)
    }

    fun close() {
        stop()
        scope.cancel()
    }

    fun onSpeechActivity(speaking: Boolean) {
        scope.launch {
            if (!active) return@launch
            val changedToSpeaking = speaking && !userSpeaking
            userSpeaking = speaking
            if (!changedToSpeaking) return@launch

            when (capabilities.mode) {
                GeminiLiveVisionCapabilities.Mode.OPPORTUNISTIC_STILL -> maybeCaptureAutomaticStill()
                else -> Unit
            }
        }
    }

    fun onVisualContextSent() {
        scope.launch {
            val now = System.currentTimeMillis()
            lastAutomaticStillMs = now
        }
    }

    private fun maybeCaptureAutomaticStill() {
        val now = System.currentTimeMillis()
        if (!GeminiLiveVisionPolicy.shouldCaptureAutomaticStill(
                capabilities = capabilities,
                nowMs = now,
                lastAutomaticStillMs = lastAutomaticStillMs,
                captureInProgress = captureInProgress.get(),
                refreshIntervalMs = automaticRefreshIntervalMs,
            )
        ) return
        if (!captureInProgress.compareAndSet(false, true)) return

        // HeyCyan makes an audible shutter sound when the capture command is issued. Start the
        // cooldown on the attempt, not on successful transfer, so a BLE/thumbnail failure cannot
        // make the next utterance immediately trigger another shutter.
        lastAutomaticStillMs = now
        client.deferAudioForVisualContext()
        stillJob = scope.launch {
            try {
                onStatus("Glasses vision: capturing a fresh still while you speak")
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        GeminiLiveGlassesImageCapture(appContext).capture(
                            ImageQuestionPreferences.thumbnailQuality(appContext),
                        )
                    }
                }
                result.onSuccess { jpeg ->
                    if (active) {
                        client.sendVideoFrame(jpeg)
                        onStatus("Glasses vision: fresh still sent")
                    }
                }.onFailure { error ->
                    if (active) onStatus("Glasses vision: ${error.message ?: "automatic still unavailable"}")
                }
            } finally {
                captureInProgress.set(false)
                if (active) client.releaseAudioAfterVisualContext()
                else client.cancelDeferredVisualContext()
            }
        }
    }

}
