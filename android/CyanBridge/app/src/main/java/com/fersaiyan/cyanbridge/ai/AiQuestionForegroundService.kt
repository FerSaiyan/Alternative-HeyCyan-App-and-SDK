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
import kotlinx.coroutines.launch

/**
 * Keeps an already-started glasses voice or image question alive while MainActivity is stopped.
 * It also opportunistically wakes the selected local model at the beginning of a local question so
 * expensive model initialization can overlap the listening/capture phase instead of delaying the
 * answer after the user's input is already complete.
 */
class AiQuestionForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var idleStopJob: Job? = null
    private var modelPreloadJob: Job? = null
    private lateinit var wakeLock: PowerManager.WakeLock

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
        startForegroundSafely(status, isQueryActive = true)
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

    private fun startForegroundSafely(status: String, isQueryActive: Boolean) {
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
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                        if (isQueryActive) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
                } else {
                    0
                },
            )
        }.onFailure { Log.e(TAG, "Unable to start AI question foreground service", it) }
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

        fun start(context: Context, status: String) {
            val intent = Intent(context, AiQuestionForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_STATUS, status)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "Unable to request AI question foreground service", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AiQuestionForegroundService::class.java))
        }
    }
}
