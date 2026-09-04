package com.fersaiyan.cyanbridge.ai.live

import android.content.Context
import android.graphics.Bitmap
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.metarayban.MetaRaybanManager
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import java.io.ByteArrayOutputStream
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
    private val encodingInProgress = AtomicBoolean(false)

    @Volatile
    private var active = false
    private var userSpeaking = false
    @Volatile
    private var lastAutomaticStillMs = 0L
    private var lastVideoFrameSentMs = 0L
    private var metaFramePendingForSpeechWindow = false
    private var latestMetaFrame: Bitmap? = null
    private var stillJob: Job? = null
    private var frameJob: Job? = null
    private var metaManager: MetaRaybanManager? = null
    private var startedMetaSession = false
    private var startedMetaStream = false

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
            GeminiLiveVisionCapabilities.Mode.LIVE_FRAMES -> startMetaLiveFrames()
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
        metaFramePendingForSpeechWindow = false
        stillJob?.cancel()
        stillJob = null
        client.cancelDeferredVisualContext()
        frameJob?.cancel()
        frameJob = null
        captureInProgress.set(false)
        encodingInProgress.set(false)
        latestMetaFrame = null

        metaManager?.let { manager ->
            if (startedMetaStream) manager.stopStreaming()
            if (startedMetaSession) manager.stopSession()
        }
        startedMetaStream = false
        startedMetaSession = false
        metaManager = null
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
                GeminiLiveVisionCapabilities.Mode.LIVE_FRAMES -> {
                    val now = System.currentTimeMillis()
                    val due = GeminiLiveVisionPolicy.shouldSendVideoFrame(
                        capabilities = capabilities,
                        userSpeaking = true,
                        nowMs = now,
                        lastFrameSentMs = lastVideoFrameSentMs,
                        encodingInProgress = encodingInProgress.get(),
                        refreshIntervalMs = automaticRefreshIntervalMs,
                    )
                    if (due) {
                        metaFramePendingForSpeechWindow = true
                        maybeSendLatestMetaFrame()
                    }
                }
                GeminiLiveVisionCapabilities.Mode.OPPORTUNISTIC_STILL -> maybeCaptureAutomaticStill()
                else -> Unit
            }
        }
    }

    fun onVisualContextSent() {
        scope.launch {
            val now = System.currentTimeMillis()
            lastAutomaticStillMs = now
            lastVideoFrameSentMs = now
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

    private fun startMetaLiveFrames() {
        val manager = MetaRaybanManager.getInstance(appContext)
        metaManager = manager
        manager.initialize()

        if (manager.isStreaming.value) {
            // MetaRaybanManager currently owns one frame callback for its stream, so stealing an
            // already-active stream would break the feature that started it.
            onStatus("Meta camera is already in use; Gemini Live video sampling is paused")
            return
        }

        manager.checkCameraPermission(
            onGranted = {
                if (!active) return@checkCameraPermission
                val hadSession = manager.deviceSessionState.value.name == "STARTED"
                manager.startSession(
                    onSuccess = {
                        if (!active) return@startSession
                        startedMetaSession = !hadSession
                        manager.startStreaming(
                            onFrame = { frame -> onMetaFrame(frame) },
                            onSuccess = {
                                if (!active) return@startStreaming
                                startedMetaStream = true
                                onStatus("Meta camera live: one fresh frame on each eligible speech turn")
                            },
                            onError = { message ->
                                if (active) onStatus("Meta live camera unavailable: $message")
                            },
                        )
                    },
                    onError = { message ->
                        if (active) onStatus("Meta camera session unavailable: $message")
                    },
                )
            },
            onRequestNeeded = {
                if (active) onStatus("Meta camera permission is required before Live vision can start")
            },
            onError = { message ->
                if (active) onStatus("Meta camera permission check failed: $message")
            },
        )
    }

    private fun onMetaFrame(bitmap: Bitmap) {
        if (!active) return
        latestMetaFrame = bitmap
        if (metaFramePendingForSpeechWindow) maybeSendLatestMetaFrame()
    }

    private fun maybeSendLatestMetaFrame() {
        val frame = latestMetaFrame ?: return
        val now = System.currentTimeMillis()
        val due = GeminiLiveVisionPolicy.shouldSendVideoFrame(
            capabilities = capabilities,
            userSpeaking = userSpeaking,
            nowMs = now,
            lastFrameSentMs = lastVideoFrameSentMs,
            encodingInProgress = encodingInProgress.get(),
            refreshIntervalMs = automaticRefreshIntervalMs,
        )
        if (!due || !encodingInProgress.compareAndSet(false, true)) return
        metaFramePendingForSpeechWindow = false
        lastVideoFrameSentMs = now

        frameJob = scope.launch {
            val jpeg = withContext(Dispatchers.Default) { frame.toGeminiLiveJpeg() }
            if (active && userSpeaking && jpeg.isNotEmpty()) {
                client.sendVideoFrame(jpeg)
            }
            encodingInProgress.set(false)
        }
    }

    private fun Bitmap.toGeminiLiveJpeg(): ByteArray {
        val longest = maxOf(width, height)
        val target = if (longest > MAX_FRAME_EDGE_PX) {
            val scale = MAX_FRAME_EDGE_PX.toFloat() / longest.toFloat()
            Bitmap.createScaledBitmap(
                this,
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            this
        }
        return ByteArrayOutputStream().use { output ->
            target.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            if (target !== this) target.recycle()
            output.toByteArray()
        }
    }

    private companion object {
        const val MAX_FRAME_EDGE_PX = 768
        const val JPEG_QUALITY = 72
    }
}
