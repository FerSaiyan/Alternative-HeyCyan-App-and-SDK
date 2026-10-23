package com.fersaiyan.cyanbridge.plugins.walkingaid

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.fragment.app.FragmentActivity
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCapturePrefs
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCaptureService
import com.fersaiyan.cyanbridge.audio.MeetingCapturePrefs
import com.fersaiyan.cyanbridge.ai.router.CliRelayClient
import com.fersaiyan.cyanbridge.plugins.walkingaid.vision.DepthResult
import com.fersaiyan.cyanbridge.plugins.walkingaid.vision.DetectionResult
import com.fersaiyan.cyanbridge.plugins.walkingaid.vision.LiteRtVisionBackend
import com.fersaiyan.cyanbridge.plugins.walkingaid.vision.VisionBackend
import com.fersaiyan.cyanbridge.plugins.walkingaid.vision.VisionFrame
import com.fersaiyan.cyanbridge.ui.ensureNotificationPermission
import com.fersaiyan.cyanbridge.ui.hasNotificationPermission
import com.oudmon.ble.base.bluetooth.BleOperateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

import com.fersaiyan.cyanbridge.devices.DeviceCapabilityHelper

class WalkingAidService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var captureLoopJob: Job? = null
    private var visionWorkerJob: Job? = null
    private var enrichmentJob: Job? = null
    private var cleanupJob: Job? = null

    private var visionBackend: VisionBackend? = null
    private val imageCapture by lazy { WalkingAidImageCapture(this) }
    private val motionEstimator by lazy { WalkingAidCameraMotionEstimator(this) }
    private val hazardTracker = WalkingAidHazardTracker()
    private val depthMutex = Mutex()
    private val latestFrameSequence = AtomicLong(0L)
    private val lastCaptureStartAtMs = AtomicLong(0L)
    private val lastMeasuredCaptureMs = AtomicLong(0L)
    private val lastMeasuredAnalysisMs = AtomicLong(0L)

    // "Latest frame wins" decoupled communication channel
    private val frameChannel = Channel<VisionFrame>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var runtimeInitialized = false
    private var safetyDisclaimerSpoken = false
    private var wasAutoAudioEnabled = false

    override fun onCreate() {
        super.onCreate()
        WalkingAidNotificationHelper.ensureChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopLoop(reason = "user")
            return START_NOT_STICKY
        }

        val shouldStart = action == ACTION_START ||
            (action == null && WalkingAidPreferences.isEnabled(this))
        if (!shouldStart) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // A service launched with startForegroundService() must promote itself before
        // any model, history, TTS, device, or readiness initialization can block.
        if (!startForegroundSafely("Walking Aid is starting...")) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!DeviceCapabilityHelper.hasCamera(this)) {
            Log.w(TAG, "Stopping WalkingAidService: selected device profile has no camera")
            return rejectStart(startId)
        }
        val readiness = WalkingAidReadinessChecker.checkReadiness(this)
        if (!readiness.isReady) {
            Log.w(TAG, "Stopping WalkingAidService: model readiness check failed: ${readiness.missingDetails}")
            return rejectStart(startId)
        }
        initializeRuntimeIfNeeded()
        startLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        stopLoop(reason = "destroy")
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }
    }

    private fun initializeRuntimeIfNeeded() {
        if (runtimeInitialized) return
        WalkingAidImageStore.load(this)
        WalkingAidWarningEngine.reset()
        initTts()
        runtimeInitialized = true
    }

    private fun rejectStart(startId: Int): Int {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun canPostNotifications(): Boolean {
        return hasNotificationPermission(this)
    }

    private fun startForegroundSafely(content: String): Boolean {
        return runCatching {
            val notif = WalkingAidNotificationHelper.buildNotification(
                this, content, WalkingAidPreferences.getCaptureIntervalSeconds(this)
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    WalkingAidNotificationHelper.NOTIFICATION_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(WalkingAidNotificationHelper.NOTIFICATION_ID, notif)
            }
        }.onFailure {
            Log.e(TAG, "startForeground failed: ${it.message}")
        }.isSuccess
    }

    private fun startLoop() {
        if (cleanupJob?.isActive == true) {
            Log.w(TAG, "Walking Aid is still shutting down; ignoring start request")
            return
        }
        if (RUNNING.getAndSet(true)) {
            Log.i(TAG, "Already running")
            return
        }
        WalkingAidNotificationHelper.updateNotification(
            this,
            "Walking Aid active — starting LiteRT Vision Engine...",
            WalkingAidPreferences.getCaptureIntervalSeconds(this),
        )

        if (!BleOperateManager.getInstance().isConnected) {
            Log.w(TAG, "Glasses not connected")
            showToast(this, getString(com.fersaiyan.cyanbridge.R.string.walking_aid_not_connected))
            RUNNING.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (MeetingCapturePrefs.getState(this).isRecording) {
            Log.w(TAG, "Meeting capture is active")
            showToast(this, getString(com.fersaiyan.cyanbridge.R.string.walking_aid_meeting_active))
            RUNNING.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        wasAutoAudioEnabled = AutoAudioCapturePrefs.isEnabled(this)
        if (wasAutoAudioEnabled) {
            AutoAudioCapturePrefs.setEnabled(this, false)
            val pauseIntent = Intent(this, AutoAudioCaptureService::class.java)
                .setAction(AutoAudioCaptureService.ACTION_STOP)
            startService(pauseIntent)
        }

        safetyDisclaimerSpoken = false
        hazardTracker.reset()
        latestFrameSequence.set(0L)
        lastCaptureStartAtMs.set(0L)
        lastMeasuredCaptureMs.set(0L)
        lastMeasuredAnalysisMs.set(0L)
        if (!motionEstimator.start()) {
            Log.w(TAG, "Rotation-vector sensor unavailable; temporal tracking will run without camera-motion compensation")
        }
        if (WalkingAidPreferences.isSafetyDisclaimerEnabled(this)) {
            speakTts("Safety notice: check path carefully. ")
            safetyDisclaimerSpoken = true
        }

        // Initialize LiteRT Vision Backend (NPU -> GPU -> CPU hierarchy)
        visionBackend = LiteRtVisionBackend(this)
        val accelInfo = visionBackend?.acceleratorInfo()
        Log.i(TAG, "Vision engine active: ${accelInfo?.details}")

        // 1. Launch dedicated Vision Worker (decoupled from camera acquisition rate)
        visionWorkerJob = scope.launch {
            var frameCount = 0
            for (frame in frameChannel) {
                if (!isActive) break
                val backend = visionBackend ?: continue
                val frameSequence = latestFrameSequence.incrementAndGet()
                enrichmentJob?.cancel()

                val imageSource = WalkingAidPreferences.getImageDescriptionSource(this@WalkingAidService)
                val depthSource = WalkingAidPreferences.getDepthSource(this@WalkingAidService)
                val customInstructions = WalkingAidPreferences.getCustomPrompt(this@WalkingAidService)
                val focusDescription = WalkingAidPreferences.getModelFocusDescription(this@WalkingAidService)
                val promptSuffix = customInstructions.takeIf { it.isNotBlank() }
                    ?.let { "\nAdditional user instructions: $it" }
                    .orEmpty()
                val focusSuffix = focusDescription.takeIf { it.isNotBlank() }
                    ?.let { "\nThe user especially wants help noticing: $it" }
                    .orEmpty()

                // The critical path is always local: detect, track, evaluate, and speak immediately.
                val rawDetection = backend.detect(frame)
                val cameraMotion = motionEstimator.motionForFrame(frame.bitmap, frame.estimatedExposureAtMs)
                val tracking = if (rawDetection.isError) {
                    HazardTrackingResult(emptyList(), emptyList())
                } else {
                    hazardTracker.update(rawDetection.objects, frame.estimatedExposureAtMs, cameraMotion)
                }
                WalkingAidWarningEngine.clearTrackCooldowns(tracking.clearedTrackIds)
                val detectionResult = rawDetection.copy(
                    objects = tracking.objects,
                    clearedTrackIds = tracking.clearedTrackIds,
                )
                val warningDecision = WalkingAidWarningEngine.evaluate(
                    detection = detectionResult,
                    depth = null,
                    focusDescription = focusDescription,
                    frameTimestampMs = frame.timestampMs,
                )
                if (warningDecision.shouldWarn && isFrameCurrent(frameSequence, frame)) {
                    speakWarning(warningDecision.message)
                }

                // Store historical entry for GUI thumbnail playback and Q&A
                val descText = if (detectionResult.isError) {
                    "System Error: ${detectionResult.errorMessage ?: "Vision backend error"}"
                } else if (detectionResult.objects.isNotEmpty()) {
                    "Detected: " + detectionResult.objects.joinToString(", ") { "${it.label} (${it.position})" }
                } else {
                    "No supported hazards detected"
                }

                if (detectionResult.isError) {
                    val errDetail = detectionResult.errorMessage ?: "Vision model error"
                    Log.e(TAG, "WalkingAid detection error: $errDetail")
                    val intervalSec = WalkingAidPreferences.getCaptureIntervalSeconds(this@WalkingAidService)
                    WalkingAidNotificationHelper.updateNotification(
                        this@WalkingAidService,
                        "Vision Error: $errDetail",
                        intervalSec,
                    )
                }

                val record = SceneRecord(
                    timestampMs = frame.timestampMs,
                    imagePath = frame.sourcePath ?: "",
                    description = descText,
                    depthDescription = null,
                    stateDecision = if (detectionResult.isError || warningDecision.shouldWarn) StateDecision.WARN else StateDecision.SKIP,
                )
                val maxHistory = WalkingAidPreferences.getImageHistoryMaxCount(this@WalkingAidService)
                WalkingAidImageStore.addRecord(record, maxHistory)
                WalkingAidImageStore.persist(this@WalkingAidService, maxHistory)

                // Update real-time telemetry notification
                val accel = backend.acceleratorInfo()
                val analysisMs = detectionResult.totalTimeMs
                lastMeasuredAnalysisMs.set(analysisMs)
                val measuredCaptureMs = lastMeasuredCaptureMs.get()
                val captureToDecisionMs = measuredCaptureMs + analysisMs
                val measuredCadenceMs = lastCaptureStartAtMs.get().let { startMs ->
                    if (startMs > 0) System.currentTimeMillis() - startMs else measuredCaptureMs
                }
                val measuredFps = if (measuredCadenceMs > 0) 1000f / measuredCadenceMs else 0f
                val statusMsg = "LiteRT (${accel.type.name.take(3)}): ${detectionResult.objects.size} obj, " +
                    "${analysisMs}ms analyze, ${captureToDecisionMs}ms total, " +
                    String.format("%.2f fps cadence", measuredFps)
                WalkingAidNotificationHelper.updateNotification(this@WalkingAidService, statusMsg, (measuredCadenceMs / 1000).toInt().coerceAtLeast(1))

                // Depth and cloud context are cancellable enrichments and never delay local TTS.
                val processedFrameCount = frameCount
                enrichmentJob = scope.launch {
                    if (WalkingAidPreferences.isDepthEnabled(this@WalkingAidService)) {
                        launch {
                            enrichDepth(
                                frame = frame,
                                frameSequence = frameSequence,
                                frameCount = processedFrameCount,
                                detectionResult = detectionResult,
                                backend = backend,
                                depthSource = depthSource,
                                focusDescription = focusDescription,
                                promptSuffix = promptSuffix,
                                focusSuffix = focusSuffix,
                            )
                        }
                    }
                    if (imageSource == "cloud") {
                        launch {
                            enrichCloudDescription(
                                frame = frame,
                                frameSequence = frameSequence,
                                promptSuffix = promptSuffix,
                                focusSuffix = focusSuffix,
                            )
                        }
                    }
                }

                frameCount++
            }
        }

        // 2. Launch Camera Capture Loop
        captureLoopJob = scope.launch {
            var captureIndex = 0
            while (isActive && WalkingAidPreferences.isEnabled(this@WalkingAidService)) {
                val intervalMs = WalkingAidPreferences.getCaptureIntervalSeconds(this@WalkingAidService) * 1000L
                val captureStartMs = System.currentTimeMillis()

                if (!BleOperateManager.getInstance().isConnected) {
                    Log.w(TAG, "Glasses disconnected during loop; waiting...")
                    WalkingAidNotificationHelper.updateNotification(
                        this@WalkingAidService,
                        "Waiting for glasses connection...",
                        (intervalMs / 1000).toInt(),
                    )
                    delay(5_000)
                    continue
                }

                lastCaptureStartAtMs.set(captureStartMs)
                val capturedThumbnail = captureThumbnail(captureIndex)
                val captureElapsedMs = System.currentTimeMillis() - captureStartMs
                lastMeasuredCaptureMs.set(captureElapsedMs)
                if (capturedThumbnail != null && capturedThumbnail.file.exists()) {
                    val imageFile = capturedThumbnail.file
                    val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                    if (bitmap != null) {
                        val frame = VisionFrame(
                            bitmap = bitmap,
                            timestampMs = capturedThumbnail.captureCommandAtMs,
                            captureCommandAtMs = capturedThumbnail.captureCommandAtMs,
                            estimatedExposureAtMs = capturedThumbnail.estimatedExposureAtMs,
                            receivedAtMs = capturedThumbnail.receivedAtMs,
                            captureIndex = captureIndex,
                            sourcePath = imageFile.absolutePath,
                        )
                        frameChannel.trySend(frame)
                    }
                } else {
                    Log.w(TAG, "No thumbnail captured, retrying after interval")
                }

                captureIndex++
                // Start-to-start cadence: wait only the remainder of the configured interval
                // after capture time. If capture exceeded the interval, start next immediately.
                val remainingMs = intervalMs - captureElapsedMs
                if (remainingMs > 0) delay(remainingMs)
            }
            stopLoop(reason = "loop_end")
        }
    }

    private suspend fun enrichDepth(
        frame: VisionFrame,
        frameSequence: Long,
        frameCount: Int,
        detectionResult: DetectionResult,
        backend: VisionBackend,
        depthSource: String,
        focusDescription: String,
        promptSuffix: String,
        focusSuffix: String,
    ) {
        if (!isFrameCurrent(frameSequence, frame)) return
        val depthResult = if (depthSource == "cloud") {
            val imagePath = frame.sourcePath?.takeIf { it.isNotBlank() && File(it).exists() } ?: return
            val modelOverride = WalkingAidPreferences.getDepthModelOverride(this)
            val prompt = "عمق نسبی، سطح زمین، پله‌ها، جدول‌ها و پرتگاه‌ها را در این تصویر برای کمک‌یار پیاده‌روی بررسی کن.$focusSuffix$promptSuffix"
            val reply = CliRelayClient.imageQuery(this, imagePath, prompt, modelOverride = modelOverride)
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: return
            DepthResult(
                relativeDepthSummary = reply,
                groundDiscontinuityDetected = listOf("step", "curb", "drop", "hole", "stair")
                    .any { reply.contains(it, ignoreCase = true) },
                closestRegion = "center",
            )
        } else {
            val shouldEstimate = frameCount % 3 == 0 || detectionResult.objects.any { it.approaching }
            if (!shouldEstimate) return
            depthMutex.withLock {
                if (!isFrameCurrent(frameSequence, frame)) return
                backend.estimateDepth(frame)
            } ?: return
        }

        if (!isFrameCurrent(frameSequence, frame)) return
        val depthDecision = WalkingAidWarningEngine.evaluate(
            detection = DetectionResult(objects = emptyList()),
            depth = depthResult,
            focusDescription = focusDescription,
            frameTimestampMs = frame.timestampMs,
        )
        val maxHistory = WalkingAidPreferences.getImageHistoryMaxCount(this)
        WalkingAidImageStore.enrichRecord(
            context = this,
            timestampMs = frame.timestampMs,
            depthDescription = depthResult.relativeDepthSummary,
            stateDecision = if (depthDecision.shouldWarn) StateDecision.WARN else null,
            maxHistory = maxHistory,
        )
        if (depthDecision.shouldWarn && isFrameCurrent(frameSequence, frame)) {
            speakWarning(depthDecision.message)
        }
    }

    private suspend fun enrichCloudDescription(
        frame: VisionFrame,
        frameSequence: Long,
        promptSuffix: String,
        focusSuffix: String,
    ) {
        val imagePath = frame.sourcePath?.takeIf { it.isNotBlank() && File(it).exists() } ?: return
        if (!isFrameCurrent(frameSequence, frame)) return
        val modelOverride = WalkingAidPreferences.getImageDescriptionModelOverride(this)
        val prompt = "موانع، وسایل نقلیه، افراد و خطرهای مسیر قابل مشاهده را دقیق و کوتاه توصیف کن.$focusSuffix$promptSuffix"
        val reply = CliRelayClient.imageQuery(this, imagePath, prompt, modelOverride = modelOverride)
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return
        if (!isFrameCurrent(frameSequence, frame)) return
        val maxHistory = WalkingAidPreferences.getImageHistoryMaxCount(this)
        WalkingAidImageStore.enrichRecord(
            context = this,
            timestampMs = frame.timestampMs,
            description = reply,
            maxHistory = maxHistory,
        )
    }

    private fun isFrameCurrent(frameSequence: Long, frame: VisionFrame): Boolean {
        val ageMs = (System.currentTimeMillis() - frame.timestampMs).coerceAtLeast(0L)
        return latestFrameSequence.get() == frameSequence &&
            ageMs <= WalkingAidWarningEngine.MAX_WARNING_FRAME_AGE_MS
    }

    @Synchronized
    private fun speakWarning(message: String) {
        if (message.isBlank() || !WalkingAidPreferences.isTtsEnabled(this)) return
        speakTts(message)
    }

    private suspend fun captureThumbnail(index: Int): WalkingAidImageCapture.CapturedThumbnail? {
        return runCatching {
            imageCapture.captureFreshThumbnail("WALKING_AID_THUMB_$index")
        }.onFailure { error ->
            Log.e(TAG, "Fresh thumbnail capture failed: ${error.message}", error)
            WalkingAidNotificationHelper.updateNotification(
                this,
                "Thumbnail capture failed — retrying...",
                WalkingAidPreferences.getCaptureIntervalSeconds(this),
            )
        }.getOrNull()
    }

    private fun speakTts(text: String) {
        if (!ttsReady || tts == null) {
            Log.w(TAG, "TTS not ready")
            return
        }
        val locale = Locale.forLanguageTag(ImageQuestionPreferences.get(this).appLanguageTag)
        tts?.language = locale

        val utteranceId = "walking_aid_${System.currentTimeMillis()}"
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(uttId: String?) {}
            override fun onDone(uttId: String?) {}
            @Deprecated("Deprecated in Java")
            override fun onError(uttId: String?) {}
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun stopLoop(reason: String) {
        if (!RUNNING.getAndSet(false)) {
            if (cleanupJob?.isActive != true) stopSelf()
            return
        }
        Log.i(TAG, "Stopping: $reason")
        val jobs = listOfNotNull(captureLoopJob, visionWorkerJob, enrichmentJob)
        jobs.forEach(Job::cancel)
        captureLoopJob = null
        visionWorkerJob = null
        enrichmentJob = null

        motionEstimator.stop()
        hazardTracker.reset()
        latestFrameSequence.set(0L)

        cleanupJob = scope.launch {
            jobs.joinAll()
            visionBackend?.close()
            visionBackend = null

            if (wasAutoAudioEnabled) {
                AutoAudioCapturePrefs.setEnabled(this@WalkingAidService, true)
                val resumeIntent = Intent(this@WalkingAidService, AutoAudioCaptureService::class.java)
                    .setAction(AutoAudioCaptureService.ACTION_START)
                startService(resumeIntent)
            }

            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            cleanupJob = null
            stopSelf()
        }
    }

    private fun showToast(context: Context, message: String) {
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "WalkingAidService"
        const val ACTION_START = "com.fersaiyan.cyanbridge.action.WALKING_AID_START"
        const val ACTION_STOP = "com.fersaiyan.cyanbridge.action.WALKING_AID_STOP"

        private val RUNNING = AtomicBoolean(false)

        fun start(context: Context) {
            if (!hasNotificationPermission(context) && context is FragmentActivity) {
                ensureNotificationPermission(context, "Walking Aid") { }
            }
            WalkingAidPreferences.setEnabled(context, true)
            val intent = Intent(context, WalkingAidService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            WalkingAidPreferences.setEnabled(context, false)
            val intent = Intent(context, WalkingAidService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun isRunning(): Boolean = RUNNING.get()
    }
}
