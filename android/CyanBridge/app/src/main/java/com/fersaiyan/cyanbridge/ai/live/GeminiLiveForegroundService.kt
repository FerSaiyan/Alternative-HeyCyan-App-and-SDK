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
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionRoute
import com.fersaiyan.cyanbridge.ui.localization.AppLanguagePreferences
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var idleStopJob: Job? = null
    private var tickerJob: Job? = null
    private var startedAtMs = 0L
    private var isListening = false
    private var visionStatus = "Glasses vision: waiting"
    private var currentDetail = "Connecting to Gemini Live"
    private var initialImagePath: String? = null
    private var initialPrompt: String? = null
    private var initialTurnSent = false
    private val hardwareInProgress = AtomicBoolean(false)
    private var hardwareRegistered = false
    private val hardwareHandler: () -> Unit = { captureHardwareImageQuestion() }

    private val powerManager by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val notificationManager by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }

    override fun onCreate() {
        super.onCreate()
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:gemini-live")
            .apply { setReferenceCounted(false) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Gemini Live", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Continuous Gemini Live voice and vision session"
                    setShowBadge(false)
                }
            )
        }
        client = GeminiLiveClient(this, this)
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
                val language = intent.getStringExtra(EXTRA_LANGUAGE)?.takeIf { it.isNotBlank() }
                    ?: AppLanguagePreferences.selected(this).languageTag.ifBlank { Locale.getDefault().toLanguageTag() }
                initialImagePath = intent.getStringExtra(EXTRA_INITIAL_IMAGE_PATH)?.takeIf { it.isNotBlank() }
                initialPrompt = intent.getStringExtra(EXTRA_INITIAL_PROMPT)?.takeIf { it.isNotBlank() }
                val imagePrompt = intent.getStringExtra(EXTRA_IMAGE_PROMPT)?.takeIf { it.isNotBlank() }
                    ?: ImageQuestionPromptResolver.resolve(
                        settings = ImageQuestionPreferences.get(this),
                        userQuestion = null,
                    ).forRoute(ImageQuestionRoute.PRO_RELAY)

                // Foreground must be started within ~5s on Android 14+
                startForegroundWithStatus("Connecting to Gemini Live")
                if (wakeLock.isHeld) wakeLock.release()
                wakeLock.acquire(MAX_WORK_DURATION_MS)
                scheduleAutoStop()

                // Vision + client start
                visionController?.start()
                // Reset per-session flags
                initialTurnSent = false
                isListening = false
                currentDetail = if (intent.getBooleanExtra(EXTRA_USE_RELAY, false)) "Connecting to CyanBridge Live relay" else "Connecting to Gemini Live"
                updateNotification()
                client?.start(language, imagePrompt)
                Log.i(TAG, "Live service started language=$language initialImage=${initialImagePath != null} useRelay=${intent.getBooleanExtra(EXTRA_USE_RELAY, false)}")
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

    override fun onDestroy() {
        idleStopJob?.cancel()
        tickerJob?.cancel()
        unregisterHardwareButton()
        visionController?.close()
        client?.close()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleAutoStop() {
        idleStopJob?.cancel()
        idleStopJob = serviceScope.launch {
            delay(MAX_WORK_DURATION_MS)
            Log.w(TAG, "Live max duration reached, stopping")
            stopLive()
        }
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

    private fun startForegroundWithStatus(detail: String) {
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

        runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    else -> 0
                }
            )
        }.onFailure { Log.e(TAG, "Unable to start live foreground service", it) }
    }

    private fun updateNotification() {
        startForegroundWithStatus(currentDetail)
    }

    private fun stopLive() {
        idleStopJob?.cancel()
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
        GeminiLiveImageButtonRouter.register(hardwareHandler)
        hardwareRegistered = true
    }

    private fun unregisterHardwareButton() {
        if (!hardwareRegistered) return
        GeminiLiveImageButtonRouter.unregister(hardwareHandler)
        hardwareRegistered = false
    }

    private fun captureHardwareImageQuestion() {
        if (!isListening) return
        if (!hardwareInProgress.compareAndSet(false, true)) return
        serviceScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { GeminiLiveGlassesImageCapture(this@GeminiLiveForegroundService).captureFromHardwareButton() }
            }
            result.onSuccess { image ->
                client?.sendImage(image)
                visionController?.onVisualContextSent()
                visionStatus = "Glasses vision: manual AI-photo sent"
                currentDetail = "Image sent to Gemini Live"
                updateNotification()
            }.onFailure { error ->
                Log.w(TAG, "Hardware image capture failed", error)
                currentDetail = error.message ?: "Glasses image capture failed"
                updateNotification()
            }
            hardwareInProgress.set(false)
        }
    }

    // GeminiLiveClient.Listener

    override fun onStateChanged(state: GeminiLiveState, detail: String) {
        serviceScope.launch(Dispatchers.Main) {
            currentDetail = detail.ifBlank { state.name.lowercase().replaceFirstChar(Char::uppercase) }
            val listening = state == GeminiLiveState.LISTENING
            isListening = listening
            if (listening) {
                visionController?.start()
                if (startedAtMs == 0L) {
                    startedAtMs = System.currentTimeMillis()
                    startTicker()
                }
                registerHardwareButton()
            }
            if (state == GeminiLiveState.STOPPED || state == GeminiLiveState.ERROR) {
                visionController?.stop()
                startedAtMs = 0L
                isListening = false
                tickerJob?.cancel()
                unregisterHardwareButton()
                if (state == GeminiLiveState.ERROR) {
                    // Keep notification briefly so user sees error, then stop
                    updateNotification()
                    delay(2500L)
                    stopLive()
                    return@launch
                }
            }
            updateNotification()
        }
    }

    override fun onInterrupted() {
        visionController?.onServerDetectedUserInterruption()
        currentDetail = "Gemini was interrupted. Listening for you."
        updateNotification()
    }

    override fun onNetworkChanged(available: Boolean) {
        currentDetail = if (available) "Network: connected" else "Network: lost, reconnecting"
        updateNotification()
    }

    override fun onUserSpeechActivity(active: Boolean) {
        visionController?.onSpeechActivity(active)
    }

    override fun onTranscription(input: Boolean, text: String) {
        if (text.isNotBlank()) {
            Log.d(TAG, "${if (input) "User" else "Gemini"} transcription: $text")
        }
    }

    override fun onSetupComplete() {
        if (initialTurnSent) return
        initialTurnSent = true
        Log.i(TAG, "Sending initial Live turn image=${initialImagePath != null} prompt=${initialPrompt != null}")
        initialImagePath?.let { path ->
            serviceScope.launch(Dispatchers.IO) {
                val image = runCatching { File(path).readBytes() }.getOrNull()
                if (image != null && image.isNotEmpty()) {
                    client?.sendImage(image)
                    visionController?.onVisualContextSent()
                }
                initialPrompt?.let { client?.sendTextTurn(it) }
            }
        } ?: initialPrompt?.let { client?.sendTextTurn(it) }
    }

    companion object {
        private const val TAG = "GeminiLiveService"
        private const val CHANNEL_ID = "gemini_live"
        private const val NOTIFICATION_ID = 7044
        private const val MAX_WORK_DURATION_MS = 15L * 60L * 1000L
        private const val ACTION_START = "com.fersaiyan.cyanbridge.action.GEMINI_LIVE_START"
        private const val ACTION_STOP = "com.fersaiyan.cyanbridge.action.GEMINI_LIVE_STOP"
        private const val EXTRA_LANGUAGE = "language"
        private const val EXTRA_IMAGE_PROMPT = "image_prompt"
        private const val EXTRA_INITIAL_IMAGE_PATH = "initial_image_path"
        private const val EXTRA_INITIAL_PROMPT = "initial_prompt"
        private const val EXTRA_USE_RELAY = "use_relay"

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
                .onFailure { Log.w(TAG, "Unable to stop Live service", it) }
            // Also try direct stopService as fallback for pre-O
            runCatching { context.stopService(Intent(context, GeminiLiveForegroundService::class.java)) }
        }

        fun isRunning(context: Context): Boolean {
            // Best-effort via ActivityManager; not reliable on Android 14+ but useful for debugging
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            return am?.getRunningServices(Integer.MAX_VALUE)?.any { it.service.className == GeminiLiveForegroundService::class.java.name } == true
        }
    }
}
