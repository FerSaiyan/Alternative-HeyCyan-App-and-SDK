package com.fersaiyan.cyanbridge.localagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AutomationPrefs
import com.fersaiyan.cyanbridge.localagent.actions.LocalAgentActionManager
import com.fersaiyan.cyanbridge.ui.hasNotificationPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Explicitly enabled, foreground Telegram long-poller. It only dispatches commands from the one
 * configured chat ID and routes task actions through the existing Local Agent approval policy.
 */
class LocalAgentTelegramService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == LocalAgentIntents.ACTION_TELEGRAM_REMOTE_STOP) {
            if (intent.getBooleanExtra(LocalAgentIntents.EXTRA_DISABLE_REMOTE, false)) {
                LocalAgentPrefs.setTelegramRemoteControlEnabled(applicationContext, false)
            }
            stopPolling(status = "Disabled")
            return START_NOT_STICKY
        }

        if (!LocalAgentPrefs.isTelegramRemoteControlEnabled(applicationContext)) {
            updateStatus("Disabled")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!AutomationPrefs.isLocalAgentAutomationEnabled(applicationContext)) {
            updateStatus("Unavailable", "local_agent_automation_disabled")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!LocalAgentPrefs.isTelegramConfigured(applicationContext)) {
            updateStatus("Configuration required", "telegram_configuration_missing")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasNotificationPermission(this)) {
            updateStatus("Waiting for notification permission", "missing_post_notifications")
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        if (pollJob?.isActive != true) {
            startPolling()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        pollJob = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startPolling() {
        updateStatus("Listening")
        pollJob = serviceScope.launch {
            while (isActive) {
                if (!LocalAgentPrefs.isTelegramRemoteControlEnabled(applicationContext)) {
                    updateStatus("Disabled")
                    break
                }
                if (!AutomationPrefs.isLocalAgentAutomationEnabled(applicationContext)) {
                    updateStatus("Unavailable", "local_agent_automation_disabled")
                    break
                }

                val token = LocalAgentPrefs.getTelegramBotToken(applicationContext)
                val allowedChatId = LocalAgentPrefs.getTelegramAllowedChatId(applicationContext)
                if (!LocalAgentTelegramProtocol.isValidBotToken(token) || allowedChatId.isBlank()) {
                    updateStatus("Configuration required", "telegram_configuration_missing")
                    break
                }

                val updates = runCatching {
                    TelegramBotApi.getUpdates(
                        token = token,
                        offset = LocalAgentPrefs.getTelegramUpdateOffset(applicationContext),
                    )
                }.getOrElse {
                    // Never expose the token-bearing endpoint or raw remote response in app state.
                    updateStatus("Telegram connection failed", "telegram_poll_failed")
                    delay(ERROR_RETRY_DELAY_MS)
                    return@getOrElse null
                } ?: continue

                updateStatus("Listening")
                var offsetPersistFailed = false
                for (update in updates) {
                    // Persist first to make duplicate remote control after a process death unlikely.
                    val previousOffset = LocalAgentPrefs.getTelegramUpdateOffset(applicationContext)
                    val nextOffset = LocalAgentTelegramProtocol.nextOffset(update.updateId)
                    if (nextOffset <= previousOffset) continue
                    if (!LocalAgentPrefs.setTelegramUpdateOffset(
                            applicationContext,
                            nextOffset,
                        )
                    ) {
                        LocalAgentPrefs.setTelegramRemoteControlEnabled(applicationContext, false)
                        updateStatus("Stopped", "telegram_offset_persist_failed")
                        offsetPersistFailed = true
                        break
                    }

                    if (!LocalAgentTelegramProtocol.isAllowedChat(allowedChatId, update.chatId)) {
                        // Do not reply to untrusted chats: doing so would disclose that this bot is active.
                        continue
                    }
                    val commandText = update.text ?: continue

                    val response = when (val command = LocalAgentTelegramProtocol.parseCommand(commandText)) {
                        is LocalAgentTelegramProtocol.Command.Task -> handleTask(command.goal)
                        LocalAgentTelegramProtocol.Command.Status -> {
                            "Local Agent status: ${LocalAgentPrefs.getStatus(applicationContext)}."
                        }
                        LocalAgentTelegramProtocol.Command.Stop -> handleStop()
                        LocalAgentTelegramProtocol.Command.ReadScreen -> handleReadScreen()
                        LocalAgentTelegramProtocol.Command.Help,
                        null -> LocalAgentTelegramProtocol.HELP_TEXT
                    }
                    runCatching { TelegramBotApi.sendMessage(token, allowedChatId, response) }
                        .onFailure { updateStatus("Telegram reply failed", "telegram_reply_failed") }
                }
                if (offsetPersistFailed) break
            }

            if (isActive) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun handleTask(goal: String): String {
        deviceUnavailableReply()?.let { return it }
        if (!AutomationPrefs.isLocalAgentAutomationEnabled(applicationContext)) {
            return "Phone control is disabled in CyanBridge settings."
        }
        if (TaskerLocalAgentService.isRunning()) {
            return "A Local Agent task is already running. Use /status or /stop first."
        }
        val result = LocalAgentController.start(applicationContext, goal)
        return if (result.ok) {
            "Task accepted. CyanBridge will require any approvals configured on the phone."
        } else {
            "Task was not started. Check CyanBridge Local Agent settings on the phone."
        }
    }

    private fun handleStop(): String {
        LocalAgentController.stop(applicationContext)
        return "Stop request sent to CyanBridge."
    }

    private suspend fun handleReadScreen(): String {
        deviceUnavailableReply()?.let { return it }
        if (!AutomationPrefs.isLocalAgentAutomationEnabled(applicationContext)) {
            return "Phone control is disabled in CyanBridge settings."
        }
        if (!hasNotificationPermission(this) || !LocalAgentAccessibilityBridge.isConnected()) {
            return "Screen reading is unavailable. Check CyanBridge Accessibility and notification access on the phone."
        }
        val executed = LocalAgentActionManager.processPlannedAction(
            context = applicationContext,
            action = LocalAgentAction.ReadScreenAloud,
            source = "telegram",
        )
        return if (executed) {
            "CyanBridge is reading the current screen aloud."
        } else {
            "Screen reading is waiting for approval in CyanBridge."
        }
    }

    private fun deviceUnavailableReply(): String? {
        val availability = LocalAgentDeviceState.availability(applicationContext)
        if (availability == LocalAgentDeviceState.Availability.READY) return null
        LocalAgentPrefs.setStatus(applicationContext, "Unavailable: ${availability.statusText}")
        LocalAgentPrefs.setLastError(applicationContext, availability.errorCode)
        updateStatus("Phone unavailable", availability.errorCode)
        return "Phone control is unavailable while the phone is locked or inactive. Unlock and wake it first."
    }

    private fun stopPolling(status: String) {
        pollJob?.cancel()
        pollJob = null
        updateStatus(status)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateStatus(status: String, error: String? = null) {
        LocalAgentPrefs.setTelegramStatus(applicationContext, status)
        if (error == null) {
            LocalAgentPrefs.clearTelegramLastError(applicationContext)
        } else {
            LocalAgentPrefs.setTelegramLastError(applicationContext, error)
        }
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
            Intent(LocalAgentIntents.ACTION_TELEGRAM_STATUS_CHANGED)
                .putExtra(LocalAgentIntents.EXTRA_STATUS, status)
                .putExtra(LocalAgentIntents.EXTRA_LAST_ERROR, error ?: "(none)"),
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Local Agent remote control",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows while Telegram remote control is listening"
            },
        )
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LocalAgentTelegramService::class.java).apply {
                action = LocalAgentIntents.ACTION_TELEGRAM_REMOTE_STOP
                putExtra(LocalAgentIntents.EXTRA_DISABLE_REMOTE, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Local Agent remote control")
            .setContentText("Listening only to the configured Telegram chat")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(NotificationCompat.Action.Builder(0, "Stop", stopIntent).build())
            .build()
    }

    private object TelegramBotApi {
        fun getUpdates(token: String, offset: Long): List<LocalAgentTelegramProtocol.Update> {
            val url = URL(
                "https://api.telegram.org/bot$token/getUpdates" +
                    "?offset=${offset.coerceAtLeast(0L)}&timeout=$POLL_TIMEOUT_SECONDS",
            )
            val body = request(url, method = "GET", payload = null)
            return LocalAgentTelegramProtocol.parseUpdates(body)
        }

        fun sendMessage(token: String, chatId: String, text: String) {
            val url = URL("https://api.telegram.org/bot$token/sendMessage")
            val payload = JSONObject()
                .put("chat_id", chatId)
                .put("text", text.take(MAX_REPLY_CHARS))
                .put("disable_web_page_preview", true)
                .toString()
            val response = request(url, method = "POST", payload = payload)
            if (!JSONObject(response).optBoolean("ok", false)) {
                throw IllegalStateException("Telegram API rejected the reply")
            }
        }

        private fun request(url: URL, method: String, payload: String?): String {
            val connection = (url.openConnection() as HttpURLConnection)
            try {
                connection.requestMethod = method
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/json")
                if (payload != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(payload) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = BufferedReader(InputStreamReader(stream ?: connection.inputStream)).use { it.readText() }
                if (code !in 200..299) {
                    throw IllegalStateException("Telegram HTTP $code")
                }
                return body
            } finally {
                connection.disconnect()
            }
        }
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "local_agent_telegram"
        private const val NOTIFICATION_ID = 938
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 35_000
        private const val POLL_TIMEOUT_SECONDS = 25
        private const val ERROR_RETRY_DELAY_MS = 5_000L
        private const val MAX_REPLY_CHARS = 3_500

        fun start(context: Context) {
            val intent = Intent(context, LocalAgentTelegramService::class.java).apply {
                action = LocalAgentIntents.ACTION_TELEGRAM_REMOTE_START
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                LocalAgentPrefs.setTelegramStatus(context, "Unable to start remote control")
                LocalAgentPrefs.setTelegramLastError(context, "telegram_service_start_failed")
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                Intent(context, LocalAgentTelegramService::class.java).apply {
                    action = LocalAgentIntents.ACTION_TELEGRAM_REMOTE_STOP
                },
                )
            }.onFailure {
                LocalAgentPrefs.setTelegramStatus(context, "Unable to stop remote control")
                LocalAgentPrefs.setTelegramLastError(context, "telegram_service_stop_failed")
            }
        }
    }
}
