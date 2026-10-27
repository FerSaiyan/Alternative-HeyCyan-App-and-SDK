package com.fersaiyan.cyanbridge.ai

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
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelPreloadPolicy
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps an already-started glasses voice or image question alive while MainActivity is stopped.
 * It also opportunistically wakes the selected local model at the beginning of a local question so
 * expensive model initialization can overlap the listening/capture phase instead of delaying the
 * answer after the user's input is already complete.
 *
 * The `microphone` type lets the question record while the app is not visible, but Android grants
 * it only to a service started while the app has a visible activity (the "while-in-use" rule). A
 * question started from the background (a glasses button with no screen) gets refused: Android 12+
 * may refuse to start the service at all, and Android 14+ refuses the `microphone` type, in which
 * case the service runs with its other types so it does not crash for never entering the
 * foreground. [microphoneAccess] reports which happened.
 */
class AiQuestionForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var idleStopJob: Job? = null
    private var modelPreloadJob: Job? = null
    private lateinit var wakeLock: PowerManager.WakeLock
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:ai-question")
            .apply { setReferenceCounted(false) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AI questions", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopQuestionWork()
            return START_NOT_STICKY
        }

        if (intent == null) {
            stopQuestionWork()
            return START_NOT_STICKY
        }

        val status = intent.getStringExtra(EXTRA_STATUS).orEmpty().ifBlank { "Processing glasses question" }
        startForegroundSafely(status)
        if (startHandled()) {
            // The question ended before the service started: it may stop only now it is in the foreground.
            stopQuestionWork()
            return START_NOT_STICKY
        }
        if (wakeLock.isHeld) wakeLock.release()
        wakeLock.acquire(MAX_WORK_DURATION_MS)
        preloadSelectedLocalModelIfUseful()
        idleStopJob?.cancel()
        idleStopJob = serviceScope.launch {
            delay(MAX_WORK_DURATION_MS)
            Log.w(TAG, "AI question wake lock expired")
            if (wakeLock.isHeld) wakeLock.release()
            stopQuestionWork()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // A question that already asked for the next service keeps waiting for it.
        microphone.update { if (it == MicrophoneAccess.STARTING) it else MicrophoneAccess.NOT_STARTED }
        idleStopJob?.cancel()
        modelPreloadJob?.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun preloadSelectedLocalModelIfUseful() {
        if (modelPreloadJob?.isActive == true) return

        val assistantMode = LocalAgentPrefs.getGlassesAssistantMode(this)
        val providerType = LocalAgentPrefs.getProviderType(this)
        val remoteActive = RemoteOpenAiPrefs.isActive(this)
        val hasSelectedModel = LocalModelStorageRepository.resolveSelectedModel(this) != null
        if (!LocalModelPreloadPolicy.shouldPreload(
                assistantMode = assistantMode,
                providerType = providerType,
                remoteOpenAiActive = remoteActive,
                hasSelectedModel = hasSelectedModel,
            )
        ) {
            return
        }

        modelPreloadJob = serviceScope.launch {
            val startedAt = System.currentTimeMillis()
            runCatching {
                LocalModelsProvider().prepareSelectedModel(this@AiQuestionForegroundService)
            }.onSuccess { details ->
                if (details != null) {
                    Log.i(
                        TAG,
                        "Local model prepared during input phase in ${System.currentTimeMillis() - startedAt}ms " +
                            "backend=${details.activeBackend}",
                    )
                }
            }.onFailure { error ->
                // Preloading is an optimization only. The normal request path will retry/loading and
                // surface any real error to the user when inference actually begins.
                Log.w(TAG, "Early local-model preparation failed; normal request path will retry", error)
            }
        }
    }

    private fun startForegroundSafely(status: String) {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("CyanBridge AI question")
            .setContentText(status)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        fun start(types: Int) = runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (start(0).onFailure { Log.e(TAG, "Unable to start AI question foreground service", it) }.isSuccess) foreground = true
            publish(if (foreground) MicrophoneAccess.GRANTED else MicrophoneAccess.REFUSED)
            return
        }
        val withoutMicrophone = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        val withMicrophone = start(withoutMicrophone or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        when {
            withMicrophone.isSuccess -> {
                foreground = true
                publish(MicrophoneAccess.GRANTED)
            }
            // Already in the foreground (a second question step): it keeps the types it has.
            foreground -> {
                Log.w(TAG, "AI question foreground service update refused; keeping the running one", withMicrophone.exceptionOrNull())
                microphone.compareAndSet(MicrophoneAccess.STARTING, MicrophoneAccess.REFUSED)
            }
            else -> {
                Log.w(TAG, "Microphone foreground service refused (app not visible); the question may not hear the phone microphone", withMicrophone.exceptionOrNull())
                start(withoutMicrophone)
                    .onSuccess { foreground = true }
                    .onFailure { Log.e(TAG, "Unable to start AI question foreground service", it) }
                publish(MicrophoneAccess.REFUSED)
            }
        }
    }

    private fun stopQuestionWork() {
        idleStopJob?.cancel()
        modelPreloadJob?.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val TAG = "AiQuestionForeground"
        private const val CHANNEL_ID = "ai_question_work"
        private const val NOTIFICATION_ID = 7043
        private const val MAX_WORK_DURATION_MS = 2L * 60L * 1000L
        private const val ACTION_START = "com.fersaiyan.cyanbridge.action.AI_QUESTION_START"
        private const val ACTION_STOP = "com.fersaiyan.cyanbridge.action.AI_QUESTION_STOP"
        private const val EXTRA_STATUS = "status"

        private val microphone = MutableStateFlow(MicrophoneAccess.NOT_STARTED)

        // Android kills the app when a service started with startForegroundService() is stopped before
        // it calls startForeground(), so a stop that arrives first waits for onStartCommand.
        private val startLock = Any()
        private var startPending = false
        private var stopPending = false

        /** Marks the pending start as handled; true when a stop arrived meanwhile. */
        private fun startHandled(): Boolean = synchronized(startLock) {
            val stop = stopPending
            startPending = false
            stopPending = false
            stop
        }

        private fun publish(access: MicrophoneAccess) {
            microphone.value = access
        }

        fun start(context: Context, status: String) {
            val intent = Intent(context, AiQuestionForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_STATUS, status)
            // A service that already holds the microphone keeps it; otherwise onStartCommand reports again.
            microphone.update { if (it == MicrophoneAccess.GRANTED) it else MicrophoneAccess.STARTING }
            synchronized(startLock) {
                startPending = true
                stopPending = false
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure {
                    synchronized(startLock) { startPending = false }
                    // Android 12+: a background app may not start a foreground service at all.
                    Log.w(TAG, "Unable to request AI question foreground service", it)
                    microphone.compareAndSet(MicrophoneAccess.STARTING, MicrophoneAccess.REFUSED)
                }
        }

        fun stop(context: Context) {
            microphone.value = MicrophoneAccess.NOT_STARTED
            synchronized(startLock) {
                if (startPending) {
                    stopPending = true
                    return
                }
            }
            context.stopService(Intent(context, AiQuestionForegroundService::class.java))
        }

        /**
         * Whether the question's foreground service holds the microphone, waiting up to [timeoutMs]
         * for a service that is still starting. [MicrophoneAccess.NOT_STARTED] when no question
         * asked for it (for example an image follow-up after the answer ended the service).
         */
        suspend fun awaitMicrophoneAccess(timeoutMs: Long = MICROPHONE_WAIT_MS): MicrophoneAccess =
            withTimeoutOrNull(timeoutMs) { microphone.first { it != MicrophoneAccess.STARTING } }
                ?: MicrophoneAccess.STARTING

        private const val MICROPHONE_WAIT_MS = 1_000L
    }
}

/** What the question's foreground service got from Android. */
enum class MicrophoneAccess {
    /** No question asked for the service (or it stopped). */
    NOT_STARTED,

    /** Requested; the service has not reported yet. */
    STARTING,

    /** Running with the `microphone` type (before Android 10: running). */
    GRANTED,

    /** Not started, or started without the `microphone` type: the app was not visible. */
    REFUSED,
}
