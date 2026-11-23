package com.fersaiyan.cyanbridge.ai.live

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidLiveVideoCapture
import com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidVideoMode
import com.fersaiyan.cyanbridge.plugins.walkingaid.vision.VisionFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
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

    /** Live video mode: the newest glasses frame, replaced as frames arrive; taken (owned) when sent. */
    private val latestStreamFrame = AtomicReference<VisionFrame?>(null)
    private var streamJob: Job? = null
    @Volatile private var streamFailed = false

    /** Shared with the physical photo button so two captures never run on the glasses at once. */
    val captureLock = Mutex()
    @Volatile private var lastManualImageMs = 0L
    private var budgetNoticeShown = false
    /** 8x8 grey signature of the last automatic image Gemini received ([sceneChanged]). */
    private var lastSentSignature: IntArray? = null

    val deviceClass: DeviceClass
        get() = DeviceProfileStore.selectedClass(appContext)

    val capabilities: GeminiLiveVisionCapabilities
        get() = GeminiLiveVisionPolicy.forDevice(deviceClass)

    private val automaticRefreshIntervalMs: Long?
        get() = GeminiLiveVisionPreferences.automaticRefreshIntervalMs(appContext)

    fun start() {
        if (active) return
        active = true
        val streamMode = liveVideoMode()
        if (streamMode != null) {
            startGlassesStream(streamMode)
            return
        }
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
        streamJob?.cancel()
        streamJob = null
        streamFailed = false
        latestStreamFrame.getAndSet(null)?.bitmap?.recycle()
        lastSentSignature = null
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

            if (streamJob != null && !streamFailed) {
                sendLatestStreamFrame()
                return@launch
            }
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

    /** The physical photo button sent an image: automatic images skip the next few seconds. */
    fun onManualImageSent() {
        lastManualImageMs = System.currentTimeMillis()
        onVisualContextSent()
    }

    private fun liveVideoMode(): WalkingAidVideoMode? {
        if (!GeminiLiveVisionPreferences.liveVideoEnabled(appContext)) return null
        return when (deviceClass) {
            DeviceClass.HEY_CYAN -> WalkingAidVideoMode.HEYCYAN_VIDEO
            DeviceClass.EYEVUE -> WalkingAidVideoMode.EYEVUE_VIDEO
            DeviceClass.MENTRA_LIVE -> WalkingAidVideoMode.MENTRA_VIDEO
            else -> null // Meta keeps its DAT stream path (LIVE_FRAMES); others fall back to stills.
        }
    }

    /** Keeps the glasses' video open for the session; only the newest frame is kept. */
    private fun startGlassesStream(mode: WalkingAidVideoMode) {
        streamFailed = false
        onStatus("Glasses vision: starting live video; the newest frame goes to Gemini when you speak")
        streamJob = scope.launch {
            try {
                WalkingAidLiveVideoCapture(appContext, featureName = "Gemini Live").collect(mode) { frame ->
                    if (!active) {
                        frame.bitmap.recycle()
                        return@collect
                    }
                    latestStreamFrame.getAndSet(frame)?.bitmap?.recycle()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                streamFailed = true
                latestStreamFrame.getAndSet(null)?.bitmap?.recycle()
                Log.w(TAG, "Live video unavailable; using photos on speech instead", error)
                if (active) onStatus("Glasses vision: live video unavailable (${error.message}); photos on speech instead")
            }
        }
    }

    private fun sendLatestStreamFrame() {
        val frame = latestStreamFrame.getAndSet(null)
        val now = System.currentTimeMillis()
        if (frame == null || now - frame.timestampMs > STREAM_FRAME_MAX_AGE_MS) {
            frame?.bitmap?.recycle()
            // The video holds the glasses (no stills while it runs); it is still starting or stalled.
            onStatus("Glasses vision: live video not delivering frames yet")
            return
        }
        if (!automaticImageAllowed() || encodingInProgress.get()) {
            frame.bitmap.recycle()
            return
        }
        val signature = frame.bitmap.signature()
        if (!sceneChanged(signature)) {
            frame.bitmap.recycle()
            onStatus("Glasses vision: same view as the last image; not resent")
            return
        }
        encodingInProgress.set(true)
        frameJob = scope.launch {
            try {
                val jpeg = withContext(Dispatchers.Default) { frame.bitmap.toGeminiLiveJpeg() }
                if (active && jpeg.isNotEmpty() && client.sendVideoFrame(jpeg)) {
                    lastSentSignature = signature
                    lastVideoFrameSentMs = System.currentTimeMillis()
                    onStatus("Glasses vision: newest live frame sent")
                }
            } finally {
                frame.bitmap.recycle()
                encodingInProgress.set(false)
            }
        }
    }

    /**
     * The physical photo button while live video runs (the video holds the glasses, so no photo can
     * be taken): sends the newest frame at once. False when there is no live frame to send.
     */
    fun sendNewestFrameForButton(): Boolean {
        if (!active || streamJob == null || streamFailed) return false
        val frame = latestStreamFrame.getAndSet(null) ?: return false
        if (System.currentTimeMillis() - frame.timestampMs > STREAM_FRAME_MAX_AGE_MS || client.remainingVisualInputs() <= 0) {
            frame.bitmap.recycle()
            return false
        }
        val signature = frame.bitmap.signature()
        scope.launch {
            try {
                val jpeg = withContext(Dispatchers.Default) { frame.bitmap.toGeminiLiveJpeg() }
                if (active && jpeg.isNotEmpty() && client.sendVideoFrame(jpeg)) {
                    lastSentSignature = signature
                    onManualImageSent()
                    onStatus("Glasses vision: photo button sent the newest live frame")
                }
            } finally {
                frame.bitmap.recycle()
            }
        }
        return true
    }

    /** Free/Economy sessions allow ten images; keep [RESERVED_FOR_MANUAL] for the photo button. */
    private fun automaticImageAllowed(): Boolean {
        if (System.currentTimeMillis() - lastManualImageMs < MANUAL_IMAGE_DEDUPE_MS) return false
        if (client.remainingVisualInputs() <= RESERVED_FOR_MANUAL) {
            if (!budgetNoticeShown) onStatus("Glasses vision: image budget kept for the photo button")
            budgetNoticeShown = true
            return false
        }
        return true
    }

    private fun sceneChanged(signature: IntArray): Boolean {
        val previous = lastSentSignature ?: return true
        val meanDiff = signature.indices.sumOf { abs(signature[it] - previous[it]) } / signature.size
        return meanDiff >= SCENE_CHANGE_THRESHOLD
    }

    private fun Bitmap.signature(): IntArray {
        val small = Bitmap.createScaledBitmap(this, SIGNATURE_EDGE, SIGNATURE_EDGE, true)
        val pixels = IntArray(SIGNATURE_EDGE * SIGNATURE_EDGE)
        small.getPixels(pixels, 0, SIGNATURE_EDGE, 0, 0, SIGNATURE_EDGE, SIGNATURE_EDGE)
        if (small !== this) small.recycle()
        return IntArray(pixels.size) { i ->
            val p = pixels[i]
            (((p shr 16) and 0xff) * 3 + ((p shr 8) and 0xff) * 6 + (p and 0xff)) / 10
        }
    }

    private fun maybeCaptureAutomaticStill() {
        val now = System.currentTimeMillis()
        if (!automaticImageAllowed()) return
        if (!GeminiLiveVisionPolicy.shouldCaptureAutomaticStill(
                capabilities = capabilities,
                nowMs = now,
                lastAutomaticStillMs = lastAutomaticStillMs,
                captureInProgress = captureInProgress.get(),
                refreshIntervalMs = automaticRefreshIntervalMs,
            )
        ) return
        if (!captureLock.tryLock()) return // The photo button is capturing; that image covers this turn.
        if (!captureInProgress.compareAndSet(false, true)) {
            captureLock.unlock()
            return
        }

        // HeyCyan makes an audible shutter sound when the capture command is issued. Start the
        // cooldown on the attempt, not on successful transfer, so a BLE/thumbnail failure cannot
        // make the next utterance immediately trigger another shutter.
        lastAutomaticStillMs = now
        client.deferAudioForVisualContext()
        stillJob = scope.launch {
            client.recordVisionCaptureStarted()
            var captured = false
            var captureError: Throwable? = null
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
                    captured = true
                    if (active) {
                        if (client.sendVideoFrame(jpeg)) onStatus("Glasses vision: fresh still queued")
                        else onStatus("Glasses vision: image was not queued; Live connection is not ready")
                    }
                }.onFailure { error ->
                    captureError = error
                    if (active) onStatus("Glasses vision: ${error.message ?: "automatic still unavailable"}")
                }
            } finally {
                client.recordVisionCaptureFinished(captured, captureError)
                captureInProgress.set(false)
                captureLock.unlock()
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
        if (!due || !automaticImageAllowed()) return
        val signature = frame.signature()
        if (!sceneChanged(signature)) {
            metaFramePendingForSpeechWindow = false
            return
        }
        if (!encodingInProgress.compareAndSet(false, true)) return
        metaFramePendingForSpeechWindow = false

        frameJob = scope.launch {
            val jpeg = withContext(Dispatchers.Default) { frame.toGeminiLiveJpeg() }
            if (active && userSpeaking && jpeg.isNotEmpty()) {
                if (client.sendVideoFrame(jpeg)) {
                    lastVideoFrameSentMs = System.currentTimeMillis()
                    lastSentSignature = signature
                }
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
        const val TAG = "GeminiLiveVision"
        const val MAX_FRAME_EDGE_PX = 768
        const val JPEG_QUALITY = 72
        /** A live frame older than this is stale (stream stalled); a still is taken instead. */
        const val STREAM_FRAME_MAX_AGE_MS = 3_000L
        const val RESERVED_FOR_MANUAL = 2
        const val MANUAL_IMAGE_DEDUPE_MS = 4_000L
        const val SIGNATURE_EDGE = 8
        /** Mean grey-level change (0-255) over the 8x8 signature that counts as a new view. */
        const val SCENE_CHANGE_THRESHOLD = 6
    }
}
