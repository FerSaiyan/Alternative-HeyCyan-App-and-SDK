package com.fersaiyan.cyanbridge.ai.live

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPromptResolver
import com.fersaiyan.cyanbridge.audio.TtsSpeaker
import com.fersaiyan.cyanbridge.ui.localization.AppLanguagePreferences
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Foreground service that owns a Gemini Live session so it survives the
 * 15 min Vercel limit, screen off, and lock screen. The UI is just a
 * low-importance ongoing notification with a Stop action - no dedicated
 * full-screen activity is required.
 *
 * Other Pro models / local / tasker keep their existing AiQuestionForegroundService
 * 2 min path. Only Live uses this service.
 */
class GeminiLiveForegroundService : Service(), GeminiLiveClient.Listener {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wakeLock: PowerManager.WakeLock
    private var client: GeminiLiveClient? = null
    private var visionController: GeminiLiveVisionController? = null
    private var idleStopJob: Job? = null // Existing absolute maximum duration
    private var responseIdleJob: Job? = null // POST_RESPONSE_IDLE_MS after Gemini completes its response
    private var stopping = false
    private var sessionStarted = false
    private var tickerJob: Job? = null
    private var startedAtMs = 0L
    private var isListening = false
    private var visionStatus = "Glasses vision: waiting"
    private var currentDetail = "Connecting to Gemini Live"
    private val questionTurns = LiveQuestionTurnQueue()
    /** One sender in arrival order, so an image and its prompt never interleave with the next question. */
    private val questionTurnDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val hardwareInProgress = AtomicBoolean(false)
    private var hardwareRegistered = false
    private var announcementTts: TtsSpeaker? = null
    private var terminalAnnouncementIssued = false
    private val hardwareHandler: (ManualGlassesPhoto) -> Unit = { photo ->
        serviceScope.launch(Dispatchers.Main) { captureHardwareImageQuestion(photo) }
    }

    /** A tool call that ends Live once Gemini has finished its spoken reply ([onModelTurnComplete]). */
    private var pendingEndAction: (() -> Unit)? = null
    private var pendingEndTimeout: Job? = null

    private val powerManager by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val notificationManager by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }

    override fun onCreate() {
        super.onCreate()
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:gemini-live")
            .apply { setReferenceCounted(false) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Bump from LOW (previous installs) to DEFAULT so the ongoing session is visible
            // in the status bar on Samsung One UI - user reported no notification with LOW.
            runCatching {
                val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
                if (existing != null && existing.importance != NotificationManager.IMPORTANCE_DEFAULT) {
                    notificationManager.deleteNotificationChannel(CHANNEL_ID)
                }
            }
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Gemini Live", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Continuous Gemini Live voice and vision session"
                    setShowBadge(false)
                }
            )
        }
        client = GeminiLiveClient(this, this, tokenProvider = GeminiLiveDebugEndpoint.tokenProvider(this))
        announcementTts = TtsSpeaker(applicationContext, TAG)
        visionController = GeminiLiveVisionController(this, client!!) { message ->
            visionStatus = message
            updateNotification()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stop requested via notification")
                stopLive()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val turn = LiveQuestionTurn(
                    imagePath = intent.getStringExtra(EXTRA_INITIAL_IMAGE_PATH)?.takeIf { it.isNotBlank() },
                    prompt = intent.getStringExtra(EXTRA_INITIAL_PROMPT)?.takeIf { it.isNotBlank() },
                )
                when (LiveStartDecision.of(sessionStarted, stopping, turn)) {
                    LiveStartDecision.ADD_TO_SESSION -> {
                        // A photo question while Live runs belongs to this session.
                        Log.i(TAG, "Adding a photo question to the running Live session prompt=${turn.prompt != null}")
                        cancelResponseIdleTimeout("new photo question")
                        questionTurns.offer(turn)?.let { sendQuestionTurns(listOf(it)) }
                        return START_NOT_STICKY
                    }
                    LiveStartDecision.IGNORE -> {
                        Log.i(TAG, "Ignoring duplicate Live start while an existing session is active or stopping")
                        return START_NOT_STICKY
                    }
                    LiveStartDecision.START_SESSION -> Unit
                }
                sessionStarted = true
                sessionRunning = true
                val language = intent.getStringExtra(EXTRA_LANGUAGE)?.takeIf { it.isNotBlank() }
                    ?: AppLanguagePreferences.selected(this).languageTag.ifBlank { Locale.getDefault().toLanguageTag() }
                questionTurns.reset()
                questionTurns.offer(turn)
                // Base image question without strict "Answer only in X" - Live's systemInstruction handles language permissively
                // (97 languages, switch on request). Single-shot paths keep the strict Resolver.
                val imagePrompt = intent.getStringExtra(EXTRA_IMAGE_PROMPT)?.takeIf { it.isNotBlank() }
                    ?: ImageQuestionPromptResolver.baseQuestion(
                        settings = ImageQuestionPreferences.get(this),
                        userQuestion = null,
                    )

                // Foreground must be started within ~5s on Android 14+
                if (!startForegroundWithStatus("Connecting to Gemini Live")) {
                    stopLive()
                    return START_NOT_STICKY
                }
                if (wakeLock.isHeld) wakeLock.release()
                wakeLock.acquire(MAX_WORK_DURATION_MS)
                scheduleAutoStop()

                // Vision + client start
                visionController?.start()
                // Reset per-session flags
                terminalAnnouncementIssued = false
                isListening = false
                currentDetail = if (intent.getBooleanExtra(EXTRA_USE_RELAY, false)) "Connecting to CyanBridge Live relay" else "Connecting to Gemini Live"
                updateNotification()
                client?.start(language, imagePrompt)
                Log.i(TAG, "Live service started language=$language initialImage=${turn.hasImage} useRelay=${intent.getBooleanExtra(EXTRA_USE_RELAY, false)}")
                return START_NOT_STICKY
            }
        }
        // System restart with no intent - stop
        if (intent == null) {
            stopLive()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15+ limits dataSync foreground services to 6 h per 24 h. When the limit is
     * reached the service must stop within seconds, or the app crashes with
     * ForegroundServiceDidNotStopInTimeException (Play vitals, 2.3.0).
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground-service time limit reached (type=$fgsType); stopping")
        stopLive()
    }

    override fun onDestroy() {
        sessionRunning = false
        idleStopJob?.cancel()
        responseIdleJob?.cancel()
        tickerJob?.cancel()
        unregisterHardwareButton()
        visionController?.close()
        client?.close()
        announcementTts?.shutdown()
        announcementTts = null
        if (wakeLock.isHeld) wakeLock.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleAutoStop() {
        idleStopJob?.cancel()
        idleStopJob = serviceScope.launch {
            delay(MAX_WORK_DURATION_MS)
            Log.w(TAG, "Live max duration reached, stopping")
            terminalAnnouncementIssued = true
            speakAnnouncement(GeminiLiveAnnouncement.SESSION_ENDED)
            delay(6_000L)
            stopLive()
        }
    }

    private fun cancelResponseIdleTimeout(reason: String) {
        if (responseIdleJob != null) Log.d(TAG, "Live idle timeout cancelled: $reason")
        responseIdleJob?.cancel()
        responseIdleJob = null
    }

    private fun scheduleResponseIdleTimeout() {
        cancelResponseIdleTimeout("model turn completed")
        if (stopping || !sessionStarted) return
        responseIdleJob = serviceScope.launch {
            delay(GeminiLiveSessionPolicy.POST_RESPONSE_IDLE_MS)
            if (!stopping && sessionStarted) {
                Log.i(TAG, "Closing Live after ${GeminiLiveSessionPolicy.POST_RESPONSE_IDLE_MS / 1_000L} seconds without interaction following Gemini response")
                stopLive()
            }
        }
        Log.i(TAG, "${GeminiLiveSessionPolicy.POST_RESPONSE_IDLE_MS / 1_000L}-second post-response idle timeout armed")
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            while (isListening) {
                updateNotification()
                delay(1000L)
            }
        }
    }

    private fun startForegroundWithStatus(detail: String): Boolean {
        currentDetail = detail
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, GeminiLiveForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val elapsed = if (startedAtMs > 0) {
            val sec = (System.currentTimeMillis() - startedAtMs) / 1000
            String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60)
        } else ""

        val contentText = buildString {
            append(detail)
            if (elapsed.isNotEmpty()) append(" • $elapsed")
            // visionStatus is short, keep notification readable but include it
            if (visionStatus.isNotBlank() && !visionStatus.contains("waiting", ignoreCase = true)) {
                append(" • $visionStatus")
            }
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Gemini Live • CyanBridge")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText + if (isListening) "\nMicrophone: on  $visionStatus" else "\nMicrophone: off"))
            .setContentIntent(openIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(NotificationCompat.Action.Builder(0, "Stop", stopIntent).build())
            .build()

        return runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    else -> 0
                }
            )
        }.onFailure { Log.e(TAG, "Unable to start live foreground service", it) }.isSuccess
    }

    private fun updateNotification() {
        startForegroundWithStatus(currentDetail)
    }

    private fun stopLive() {
        if (stopping) return
        stopping = true
        sessionStarted = false
        sessionRunning = false
        idleStopJob?.cancel()
        cancelResponseIdleTimeout("session stopped")
        pendingEndTimeout?.cancel()
        pendingEndAction = null
        tickerJob?.cancel()
        unregisterHardwareButton()
        visionController?.stop()
        client?.stop()
        startedAtMs = 0L
        isListening = false
        if (wakeLock.isHeld) wakeLock.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        // Clear stored image path after stop so a restart does not resend stale file
        // The file itself remains in cache for debugging but is not reused.
    }

    private fun registerHardwareButton() {
        if (hardwareRegistered) return
        GeminiLiveImageButtonRouter.register(com.fersaiyan.cyanbridge.devices.DeviceProfileStore.selectedClass(this), hardwareHandler)
        GeminiLiveImageButtonRouter.liveVideoButtonHandler = { visionController?.sendNewestFrameForButton() == true }
        hardwareRegistered = true
    }

    private fun unregisterHardwareButton() {
        if (!hardwareRegistered) return
        GeminiLiveImageButtonRouter.liveVideoButtonHandler = null
        GeminiLiveImageButtonRouter.unregister(hardwareHandler)
        hardwareRegistered = false
    }

    private fun captureHardwareImageQuestion(photo: ManualGlassesPhoto) {
        if (!isListening) { photo.acquisition?.cancel(); return }
        val sessionClient = client ?: run { photo.acquisition?.cancel(); return }
        cancelResponseIdleTimeout("manual glasses image request")
        if (!hardwareInProgress.compareAndSet(false, true)) { photo.acquisition?.cancel(); return }
        // The user usually asks right after pressing: hold that turn's end until the photo is in.
        sessionClient.deferAudioForVisualContext()
        val captureLock = visionController?.captureLock
        serviceScope.launch {
            // Waits for an automatic still already talking to the glasses; never two at once.
            captureLock?.lock()
            sessionClient.recordVisionCaptureStarted()
            var captured = false
            var captureError: Throwable? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching { GeminiLiveGlassesImageCapture(this@GeminiLiveForegroundService).captureFromHardwareButton(photo) }
                }
                result.onSuccess { image ->
                    captured = true
                    if (sessionClient.sendImage(image)) {
                        visionController?.onManualImageSent()
                        visionStatus = "Glasses vision: manual AI-photo queued"
                        currentDetail = "Image queued for Gemini Live"
                    } else {
                        visionStatus = "Glasses vision: image was not queued"
                        currentDetail = "Live connection is not ready for an image"
                    }
                    if (client === sessionClient && isListening) updateNotification()
                }.onFailure { error ->
                    captureError = error
                    Log.w(TAG, "Hardware image capture failed", error)
                    if (client === sessionClient && isListening) {
                        currentDetail = error.message ?: "Glasses image capture failed"
                        updateNotification()
                    }
                }
            } finally {
                sessionClient.recordVisionCaptureFinished(captured, captureError)
                photo.acquisition?.cancel()
                captureLock?.unlock()
                hardwareInProgress.set(false)
                sessionClient.releaseAudioAfterVisualContext()
            }
        }
    }

    // GeminiLiveClient.Listener

    override fun onStateChanged(state: GeminiLiveState, detail: String) {
        serviceScope.launch(Dispatchers.Main) {
            currentDetail = detail.ifBlank { state.name.lowercase().replaceFirstChar(Char::uppercase) }
            val listening = state == GeminiLiveState.LISTENING
            isListening = listening
            // A LISTENING update queued behind the stop (e.g. Gemini's goodbye turn) must not
            // re-open the glasses camera after the session ended.
            if (listening && !stopping) {
                visionController?.start()
                if (startedAtMs == 0L) {
                    startedAtMs = System.currentTimeMillis()
                    startTicker()
                }
                registerHardwareButton()
            }
            if (state == GeminiLiveState.STOPPED) {
                // A client-side stop (including free/economy cap) must destroy the service
                // rather than leaving its foreground notification and microphone state alive.
                stopLive()
                return@launch
            }
            if (state == GeminiLiveState.ERROR) {
                Log.w(TAG, "Live reached error state: $detail; releasing microphone immediately")
                // Do not retain an AudioRecord for ten more seconds while presenting an error.
                stopLive()
                return@launch
            }
            updateNotification()
        }
    }

    override fun onInterrupted() {
        currentDetail = "Gemini was interrupted. Listening for you."
        updateNotification()
    }

    override fun onNetworkChanged(available: Boolean) {
        currentDetail = if (available) "Network: connected" else "Network: lost, reconnecting"
        updateNotification()
    }

    override fun onUserSpeechActivity(active: Boolean) {
        visionController?.onSpeechActivity(active)
        // The local detector only cancels an existing inactivity timer; it never
        // finalizes a Gemini speech turn (server VAD remains the sole turn authority).
        if (active) serviceScope.launch(Dispatchers.Main) {
            cancelResponseIdleTimeout("microphone activity")
        }
    }

    override fun onModelOutputStarted() {
        serviceScope.launch(Dispatchers.Main) { cancelResponseIdleTimeout("Gemini responding") }
    }

    override fun onModelTurnComplete() {
        serviceScope.launch(Dispatchers.Main) {
            if (!runPendingEndAction("goodbye spoken")) scheduleResponseIdleTimeout()
        }
    }

    /** [GeminiLiveTools] this session can honour; phone tasks only when the Local Agent is set up. */
    override fun liveTools(): List<String> = buildList {
        add(GeminiLiveTools.END_SESSION)
        if (com.fersaiyan.cyanbridge.localagent.LocalAgentController.unavailableReason(this@GeminiLiveForegroundService) == null) {
            add(GeminiLiveTools.PHONE_TASK)
        }
    }

    override fun onToolCall(id: String, name: String, args: JSONObject) {
        serviceScope.launch(Dispatchers.Main) {
            val sessionClient = client ?: return@launch
            when (name) {
                GeminiLiveTools.END_SESSION -> {
                    sessionClient.sendToolResponse(id, name, JSONObject().put("result", "ending"))
                    endAfterReply { stopLive() }
                }
                GeminiLiveTools.PHONE_TASK -> {
                    val task = args.optString(GeminiLiveTools.PHONE_TASK_ARG).trim()
                    val unavailable = com.fersaiyan.cyanbridge.localagent.LocalAgentController.unavailableReason(this@GeminiLiveForegroundService)
                    when {
                        task.isEmpty() -> sessionClient.sendToolResponse(
                            id, name, JSONObject().put("result", "error").put("message", "No task was given; ask the user what to do."),
                        )
                        unavailable != null -> sessionClient.sendToolResponse(
                            id, name, JSONObject().put("result", "unavailable").put("message", unavailable.userMessage),
                        )
                        else -> {
                            sessionClient.sendToolResponse(
                                id, name,
                                JSONObject().put("result", "handing_over")
                                    .put("message", "Tell the user in one short sentence that the phone agent is taking over; this conversation then ends."),
                            )
                            // The agent asks for voice confirmations, so it needs the microphone Live holds.
                            endAfterReply {
                                stopLive()
                                runCatching { com.fersaiyan.cyanbridge.localagent.LocalAgentController.start(this@GeminiLiveForegroundService, task) }
                                    .onFailure { Log.w(TAG, "Local Agent handoff failed", it) }
                            }
                        }
                    }
                }
                else -> sessionClient.sendToolResponse(id, name, JSONObject().put("result", "error").put("message", "Unknown tool"))
            }
        }
    }

    /** Runs [action] when Gemini's reply to the tool call has been spoken, or after a short timeout. */
    private fun endAfterReply(action: () -> Unit) {
        pendingEndAction = action
        pendingEndTimeout?.cancel()
        pendingEndTimeout = serviceScope.launch {
            delay(END_AFTER_REPLY_TIMEOUT_MS)
            runPendingEndAction("no reply after the tool call")
        }
    }

    private fun runPendingEndAction(reason: String): Boolean {
        val action = pendingEndAction ?: return false
        pendingEndAction = null
        pendingEndTimeout?.cancel()
        pendingEndTimeout = null
        Log.i(TAG, "Ending Live after a tool call: $reason")
        action()
        return true
    }

    override fun onTranscription(input: Boolean, text: String) {
        if (input && text.isNotBlank()) serviceScope.launch(Dispatchers.Main) {
            cancelResponseIdleTimeout("user transcription")
        }
        if (text.isNotBlank()) {
            Log.d(TAG, "${if (input) "User" else "Gemini"} transcription chars=${text.length}")
        }
    }

    override fun onSetupComplete() {
        sendQuestionTurns(questionTurns.onSetupComplete())
    }

    private fun sendQuestionTurns(turns: List<LiveQuestionTurn>) {
        if (turns.isEmpty()) return
        serviceScope.launch(questionTurnDispatcher) {
            for (turn in turns) {
                Log.i(TAG, "Sending Live question turn image=${turn.hasImage} prompt=${turn.prompt != null}")
                val image = turn.imagePath?.let { path -> runCatching { File(path).readBytes() }.getOrNull() }
                if (image != null && image.isNotEmpty()) {
                    // The frame first, the prompt once it has landed (see GeminiLiveClient.sendImageQuestion).
                    if (client?.sendImageQuestion(image, turn.prompt) == true) visionController?.onVisualContextSent()
                } else {
                    if (turn.imagePath != null) Log.w(TAG, "Live question image could not be read")
                    turn.prompt?.let { client?.sendTextTurn(it) }
                }
            }
        }
    }

    override fun onAnnouncement(kind: GeminiLiveAnnouncement) {
        serviceScope.launch(Dispatchers.Main) {
            if (kind in setOf(
                    GeminiLiveAnnouncement.IMAGE_LIMIT_REACHED,
                    GeminiLiveAnnouncement.SESSION_ENDED,
                    GeminiLiveAnnouncement.FREE_DAILY_LIMIT_REACHED,
                    GeminiLiveAnnouncement.FREE_BUSY,
                    GeminiLiveAnnouncement.GENERIC_FAILURE,
                )
            ) {
                terminalAnnouncementIssued = true
            }
            speakAnnouncement(kind)
        }
    }

    private fun speakAnnouncement(kind: GeminiLiveAnnouncement) {
        // Before the engine is ready only the latest announcement is kept.
        announcementTts?.whenReady { engine ->
            val languageTag = AppLanguagePreferences.selected(this).languageTag
                .ifBlank { Locale.getDefault().toLanguageTag() }
            engine.setLanguage(Locale.forLanguageTag(languageTag))
            engine.useVoiceCommunicationAudio()
            engine.speak(
                GeminiLiveAnnouncementMessages.text(kind, languageTag),
                "gemini_live_announcement_${System.nanoTime()}",
            )
        }
    }

    companion object {
        private const val TAG = "GeminiLiveService"
        private const val CHANNEL_ID = "gemini_live"
        private const val NOTIFICATION_ID = 7044
        private const val MAX_WORK_DURATION_MS = 15L * 60L * 1000L
        /** Gemini speaks a goodbye / hand-over line before Live ends; never wait longer than this. */
        private const val END_AFTER_REPLY_TIMEOUT_MS = 8_000L
        private const val ACTION_START = "com.fersaiyan.cyanbridge.action.GEMINI_LIVE_START"
        private const val ACTION_STOP = "com.fersaiyan.cyanbridge.action.GEMINI_LIVE_STOP"
        private const val EXTRA_LANGUAGE = "language"
        private const val EXTRA_IMAGE_PROMPT = "image_prompt"
        private const val EXTRA_INITIAL_IMAGE_PATH = "initial_image_path"
        private const val EXTRA_INITIAL_PROMPT = "initial_prompt"
        private const val EXTRA_USE_RELAY = "use_relay"

        /** True from an accepted session start until that session stops (one service per process). */
        @Volatile
        var sessionRunning: Boolean = false
            private set

        fun start(
            context: Context,
            language: String,
            imagePrompt: String,
            initialImagePath: String? = null,
            initialPrompt: String? = null,
            useRelay: Boolean = false,
        ) {
            val intent = Intent(context, GeminiLiveForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LANGUAGE, language)
                .putExtra(EXTRA_IMAGE_PROMPT, imagePrompt)
                .putExtra(EXTRA_USE_RELAY, useRelay)
            if (!initialImagePath.isNullOrBlank()) intent.putExtra(EXTRA_INITIAL_IMAGE_PATH, initialImagePath)
            if (!initialPrompt.isNullOrBlank()) intent.putExtra(EXTRA_INITIAL_PROMPT, initialPrompt)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "Unable to start Live service", it) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, GeminiLiveForegroundService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure {
                    Log.w(TAG, "Unable to deliver Live stop command; stopping service directly", it)
                    context.stopService(Intent(context, GeminiLiveForegroundService::class.java))
                }
        }

        fun isRunning(context: Context): Boolean {
            // Best-effort via ActivityManager; not reliable on Android 14+ but useful for debugging
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            return am?.getRunningServices(Integer.MAX_VALUE)?.any { it.service.className == GeminiLiveForegroundService::class.java.name } == true
        }
    }
}
