package com.fersaiyan.cyanbridge.localagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal const val LOCAL_AGENT_SERVICE_LOG_TAG = "LocalAgentService"

/**
 * Foreground service that hosts the local agent's observe -> plan -> act loop.
 *
 * The service owns the Android lifecycle, the foreground notification and the intent API. The
 * loop itself lives in [LocalAgentTaskLoop], start/stop/approval handling in [LocalAgentRuntime],
 * the demo and read-screen-aloud commands in [LocalAgentScreenReader] and spoken output in
 * [LocalAgentSpeechOutput].
 *
 * When an action requires user approval, the loop pauses and waits for
 * [LocalAgentIntents.ACTION_RESUME_AFTER_APPROVAL] to continue.
 */
class LocalAgentService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val session = LocalAgentSession(running = runningState, lastError = lastError)

    private var runtimeInitialized = false
    private var deviceStateReceiverRegistered = false

    private val speech by lazy {
        LocalAgentSpeechOutput(
            isDeviceReady = { LocalAgentDeviceState.isReady(applicationContext) },
            currentLocale = { LocalAgentLanguage.currentLocale(applicationContext) },
            engineFactory = { onInit -> AndroidLocalAgentTtsEngine(applicationContext, onInit) },
        )
    }
    private val device by lazy { AndroidLocalAgentDevice(this) }
    private val settings by lazy { AndroidLocalAgentSettings(applicationContext) }
    private val memory by lazy { AndroidLocalAgentTaskMemory(applicationContext) }
    private val statusReporter by lazy { LocalAgentStatusBroadcaster(applicationContext) }

    private val host = object : LocalAgentServiceHost {
        override fun startForeground(content: String) {
            this@LocalAgentService.startForeground(NOTIFICATION_ID, buildNotification(content = content))
        }

        override fun updateNotification(content: String) {
            runCatching {
                notificationManager.notify(NOTIFICATION_ID, buildNotification(content = content))
            }
        }

        override fun stopForegroundAndSelf() {
            this@LocalAgentService.stopForeground(STOP_FOREGROUND_REMOVE)
            this@LocalAgentService.stopSelf()
        }

        override fun stopSelf() {
            this@LocalAgentService.stopSelf()
        }
    }

    private val screenReader by lazy {
        LocalAgentScreenReader(
            session = session,
            device = device,
            memory = memory,
            status = statusReporter,
            speech = speech,
            host = host,
            scope = serviceScope,
        )
    }

    private val runtime by lazy {
        LocalAgentRuntime(
            session = session,
            device = device,
            settings = settings,
            status = statusReporter,
            speech = speech,
            host = host,
            scope = serviceScope,
            taskLoop = LocalAgentTaskLoop(
                session = session,
                device = device,
                settings = settings,
                memory = memory,
                status = statusReporter,
                speech = speech,
                host = host,
                plannerProvider = {
                    val brain = brainRef.get()
                    LocalAgentTaskLoop.Planner { taskState, observation ->
                        brain.next(applicationContext, taskState, observation)
                    }
                },
            ),
            screenReader = screenReader,
            stepEngineFactory = { executor ->
                val engine = LocalAgentStepEngine(
                    context = applicationContext,
                    executor = executor,
                )
                LocalAgentTaskLoop.ActionBatchRunner { actions -> engine.execute(actions) }
            },
        )
    }

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private val deviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                runtime.stopForUnavailableDevice()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // New contract (UI -> Service)
            LocalAgentIntents.ACTION_START -> {
                ensureRuntimeInitialized()
                runtime.startLoop(intent.getStringExtra(LocalAgentIntents.EXTRA_GOAL))
            }
            LocalAgentIntents.ACTION_STOP -> runtime.stopLoop(reason = "user")
            LocalAgentIntents.ACTION_DEMO -> {
                ensureRuntimeInitialized()
                screenReader.runDemo()
            }
            LocalAgentIntents.ACTION_READ_SCREEN_ALOUD -> {
                ensureRuntimeInitialized()
                screenReader.readCurrentScreenAloud()
            }
            LocalAgentIntents.ACTION_GET_STATUS -> {
                statusReporter.emitStatus()
                if (!runtime.isBusy()) stopSelfResult(startId)
            }
            LocalAgentIntents.ACTION_RESUME_AFTER_APPROVAL -> runtime.resumeAfterApproval(
                rejected = intent.getBooleanExtra(LocalAgentIntents.EXTRA_REJECTED, false)
            )

            // Back-compat (older internal actions)
            ACTION_START_LEGACY -> runtime.stopLoop(reason = "legacy_goalless_start")
            ACTION_STOP_LEGACY -> runtime.stopLoop(reason = "user")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runtime.stopLoop(reason = "service_destroy")
        if (deviceStateReceiverRegistered) {
            runCatching { unregisterReceiver(deviceStateReceiver) }
            deviceStateReceiverRegistered = false
        }
        session.rejectPendingApproval()
        serviceScope.cancel()
        speech.shutdown()
        super.onDestroy()
    }

    private fun ensureRuntimeInitialized() {
        if (runtimeInitialized) return
        runtimeInitialized = true
        speech.initialize()
        LocalAgentMemoryStore.ensureSeedFiles(applicationContext)
        ContextCompat.registerReceiver(
            this,
            deviceStateReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        deviceStateReceiverRegistered = true
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Local agent",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when the local agent loop is running"
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(content: String): Notification {
        val target = if (content == LocalAgentTaskLoop.WAITING_FOR_APPROVAL_NOTIFICATION) {
            com.fersaiyan.cyanbridge.ui.localagent.PendingActionsActivity::class.java
        } else {
            MainActivity::class.java
        }
        val openIntent = Intent(this, target)
        val openPi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, LocalAgentService::class.java).apply { action = LocalAgentIntents.ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Local agent")
            .setContentText(content)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(
                NotificationCompat.Action.Builder(
                    0,
                    "Stop",
                    stopPi
                ).build()
            )
            .build()
    }

    companion object {
        // Legacy action names (kept to avoid breaking any old entrypoints).
        private const val ACTION_START_LEGACY = "com.fersaiyan.cyanbridge.action.LOCAL_AGENT_START"
        private const val ACTION_STOP_LEGACY = "com.fersaiyan.cyanbridge.action.LOCAL_AGENT_STOP"

        private const val NOTIFICATION_CHANNEL_ID = "local_agent"
        private const val NOTIFICATION_ID = 937

        private val runningState = AtomicBoolean(false)
        private val lastError = AtomicReference<String?>(null)

        private val brainRef: AtomicReference<LocalAgentBrain> = AtomicReference(RemoteUiControlLocalAgentBrain())

        fun setBrain(brain: LocalAgentBrain) {
            brainRef.set(brain)
        }

        fun getLastError(): String? = lastError.get()

        fun isRunning(): Boolean = runningState.get()

        fun start(context: Context) {
            context.startService(
                Intent(context, LocalAgentService::class.java).apply { action = LocalAgentIntents.ACTION_GET_STATUS }
            )
        }

        fun start(context: Context, goal: String?) {
            val trimmedGoal = goal?.trim().orEmpty()
            if (trimmedGoal.isBlank()) {
                context.startService(
                    Intent(context, LocalAgentService::class.java).apply { action = LocalAgentIntents.ACTION_GET_STATUS }
                )
                return
            }
            val intent = Intent(context, LocalAgentService::class.java).apply { action = LocalAgentIntents.ACTION_START }
            intent.putExtra(LocalAgentIntents.EXTRA_GOAL, trimmedGoal)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LocalAgentService::class.java).apply { action = LocalAgentIntents.ACTION_STOP }
            context.startService(intent)
        }

        fun readScreenAloud(context: Context) {
            val intent = Intent(context, LocalAgentService::class.java).apply {
                action = LocalAgentIntents.ACTION_READ_SCREEN_ALOUD
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
