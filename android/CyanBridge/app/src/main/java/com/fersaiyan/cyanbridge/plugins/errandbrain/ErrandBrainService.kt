package com.fersaiyan.cyanbridge.plugins.errandbrain

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.fersaiyan.cyanbridge.ai.router.CliRelayClient
import com.fersaiyan.cyanbridge.bridge.core.DisplayCommand
import com.fersaiyan.cyanbridge.bridge.core.GlassesBridge
import com.fersaiyan.cyanbridge.plugins.PluginVoiceRecognizer
import com.fersaiyan.cyanbridge.plugins.startPluginVoiceForeground
import com.fersaiyan.cyanbridge.plugins.startPluginVoiceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Turns recognized speech from the phone or connected glasses mic into tasks and timed reminders. */
class ErrandBrainService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val errandStore = ErrandBrainStore()
    private val processingVoiceNote = AtomicBoolean(false)
    private var voiceRecognizer: PluginVoiceRecognizer? = null

    override fun onCreate() {
        super.onCreate()
        ErrandBrainNotificationHelper.ensureChannel(this)
        errandStore.load(this)
        errandStore.getReminders(ErrandBrainPreferences.getMaxHistory(this))
            .filter { !it.isTriggered && it.reminderTime > System.currentTimeMillis() }
            .forEach { ErrandBrainReminderScheduler.schedule(this, it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startListening()
            ACTION_STOP -> stopListening()
            ACTION_ADD_TASK -> intent.getStringExtra(EXTRA_TASK_TITLE)?.let(::addTaskFromVoice)
            ACTION_ADD_REMINDER -> {
                val title = intent.getStringExtra(EXTRA_REMINDER_TITLE)
                val reminderTime = intent.getLongExtra(EXTRA_REMINDER_TIME, 0L)
                if (!title.isNullOrBlank() && reminderTime > System.currentTimeMillis()) {
                    addReminder(title, reminderTime)
                }
            }
            null -> stopSelf()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        voiceRecognizer?.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun startListening() {
        if (voiceRecognizer != null) return
        if (!startPluginVoiceForeground(
                service = this,
                notificationId = ErrandBrainNotificationHelper.NOTIFICATION_ID,
                notification = ErrandBrainNotificationHelper.buildNotification(this, "Starting Errand Brain..."),
            )
        ) {
            Log.w(TAG, "Missing microphone or notification permission")
            stopSelf()
            return
        }

        val recognizer = PluginVoiceRecognizer(
            context = this,
            languageTag = null,
            onPartialText = { partial ->
                ErrandBrainNotificationHelper.updateNotification(
                    this,
                    "Listening: ${partial.take(NOTIFICATION_TEXT_LIMIT)}",
                )
            },
            onFinalText = ::processVoiceNote,
            onError = { message ->
                Log.w(TAG, message)
                ErrandBrainNotificationHelper.updateNotification(this, message)
            },
        )
        if (!recognizer.start()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        voiceRecognizer = recognizer
        ErrandBrainNotificationHelper.updateNotification(this, "Listening for tasks and reminders...")
    }

    private fun stopListening() {
        voiceRecognizer?.stop()
        voiceRecognizer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun processVoiceNote(voiceNote: String) {
        if (ErrandBrainPreferences.isReminderEnabled(this)) {
            parseRelativeReminder(voiceNote)?.let { (title, reminderTime) ->
                addReminder(title, reminderTime)
                return
            }
        }
        if (!ErrandBrainPreferences.isVoiceCommands(this) ||
            !ErrandBrainPreferences.isAutoCreateTasks(this)
        ) {
            ErrandBrainNotificationHelper.updateNotification(this, "Heard: ${voiceNote.take(NOTIFICATION_TEXT_LIMIT)}")
            return
        }
        addTaskFromVoice(voiceNote)
    }

    private fun addTaskFromVoice(voiceNote: String) {
        if (!processingVoiceNote.compareAndSet(false, true)) return
        scope.launch {
            try {
                val task = parseTaskFromVoice(voiceNote)
                if (task != null) {
                    errandStore.addTask(task, ErrandBrainPreferences.getMaxHistory(this@ErrandBrainService))
                    errandStore.persist(this@ErrandBrainService, ErrandBrainPreferences.getMaxHistory(this@ErrandBrainService))
                    ErrandBrainNotificationHelper.updateNotification(
                        this@ErrandBrainService,
                        "Task added: ${task.title.take(NOTIFICATION_TEXT_LIMIT)}",
                    )
                    GlassesBridge.showCard(DisplayCommand.Card("Task added", task.title))
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to create task from voice", error)
                ErrandBrainNotificationHelper.updateNotification(
                    this@ErrandBrainService,
                    "Task creation failed. Check your AI connection.",
                )
            } finally {
                processingVoiceNote.set(false)
            }
        }
    }

    private fun addReminder(title: String, reminderTime: Long) {
        val reminder = ReminderEntry(
            id = UUID.randomUUID().toString(),
            timestampMs = System.currentTimeMillis(),
            title = title,
            description = "",
            reminderTime = reminderTime,
            isTriggered = false,
        )
        errandStore.addReminder(reminder, ErrandBrainPreferences.getMaxHistory(this))
        errandStore.persist(this, ErrandBrainPreferences.getMaxHistory(this))
        ErrandBrainReminderScheduler.schedule(this, reminder)
        ErrandBrainNotificationHelper.updateNotification(
            this,
            "Reminder set: ${title.take(NOTIFICATION_TEXT_LIMIT)}",
        )
        scope.launch {
            GlassesBridge.showCard(DisplayCommand.Card("Reminder set", title))
        }
    }

    private suspend fun parseTaskFromVoice(voiceNote: String): TaskEntry? {
        val defaultPriority = ErrandBrainPreferences.getDefaultPriority(this)
        val defaultCategory = ErrandBrainPreferences.getDefaultCategory(this)
        val customPrompt = ErrandBrainPreferences.getCustomPrompt(this)
        val prompt = buildString {
            append("Turn this spoken note into one concise task. ")
            append("Default priority: $defaultPriority. Default category: $defaultCategory. ")
            append("Return the task title first, followed by optional details. Note: \"$voiceNote\". ")
            if (customPrompt.isNotBlank()) append("Additional instructions: $customPrompt")
        }
        return CliRelayClient.chat(
            context = this,
            chatId = "errand_brain_${System.currentTimeMillis()}",
            prompt = prompt,
            messages = listOf(mapOf("role" to "user", "content" to prompt)),
            modelOverride = ErrandBrainPreferences.getCloudModelId(this),
        ).fold(
            onSuccess = { response ->
                TaskEntry(
                    id = UUID.randomUUID().toString(),
                    timestampMs = System.currentTimeMillis(),
                    title = extractTaskTitle(response, voiceNote),
                    description = extractTaskDescription(response),
                    isCompleted = false,
                    priority = extractPriority(response, defaultPriority),
                    dueDate = null,
                    category = extractCategory(response, defaultCategory),
                )
            },
            onFailure = { error ->
                Log.e(TAG, "Task parsing request failed", error)
                null
            },
        )
    }

    private fun parseRelativeReminder(voiceNote: String): Pair<String, Long>? {
        val match = RELATIVE_REMINDER.find(voiceNote) ?: return null
        val quantity = match.groupValues[1].toLongOrNull() ?: return null
        val unit = match.groupValues[2].lowercase()
        val title = match.groupValues[3].trim().takeIf { it.isNotBlank() } ?: return null
        val multiplier = if (unit.startsWith("hour")) HOUR_MS else MINUTE_MS
        return title to (System.currentTimeMillis() + quantity * multiplier)
    }

    private fun extractTaskTitle(response: String, fallback: String): String =
        response.lineSequence().firstOrNull { it.isNotBlank() }?.take(100) ?: fallback.take(100)

    private fun extractTaskDescription(response: String): String =
        response.lineSequence().drop(1).joinToString(" ").take(500)

    private fun extractPriority(response: String, defaultPriority: String): TaskPriority = when {
        response.contains("urgent", ignoreCase = true) -> TaskPriority.URGENT
        response.contains("high", ignoreCase = true) -> TaskPriority.HIGH
        response.contains("low", ignoreCase = true) -> TaskPriority.LOW
        else -> when (defaultPriority) {
            "urgent" -> TaskPriority.URGENT
            "high" -> TaskPriority.HIGH
            "low" -> TaskPriority.LOW
            else -> TaskPriority.MEDIUM
        }
    }

    private fun extractCategory(response: String, defaultCategory: String): String = when {
        response.contains("work", ignoreCase = true) -> "work"
        response.contains("shopping", ignoreCase = true) -> "shopping"
        response.contains("health", ignoreCase = true) -> "health"
        response.contains("finance", ignoreCase = true) -> "finance"
        response.contains("personal", ignoreCase = true) -> "personal"
        else -> defaultCategory
    }

    companion object {
        private const val TAG = "ErrandBrain"
        private const val NOTIFICATION_TEXT_LIMIT = 100
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 60 * MINUTE_MS
        private val RELATIVE_REMINDER = Regex(
            """(?i)^\s*remind(?: me)? in (\d+)\s*(minute|minutes|hour|hours)\s*(?:to )?(.+)$""",
        )

        const val ACTION_START = "com.fersaiyan.cyanbridge.ACTION_START_ERRAND"
        const val ACTION_STOP = "com.fersaiyan.cyanbridge.ACTION_STOP_ERRAND"
        const val ACTION_ADD_TASK = "com.fersaiyan.cyanbridge.ACTION_ADD_TASK"
        const val ACTION_ADD_REMINDER = "com.fersaiyan.cyanbridge.ACTION_ADD_REMINDER"
        const val EXTRA_TASK_TITLE = "task_title"
        const val EXTRA_REMINDER_TITLE = "reminder_title"
        const val EXTRA_REMINDER_TIME = "reminder_time"

        fun start(context: Context) {
            startPluginVoiceService(
                context,
                Intent(context, ErrandBrainService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ErrandBrainService::class.java).setAction(ACTION_STOP),
            )
        }

        fun addTask(context: Context, taskTitle: String) {
            context.startService(
                Intent(context, ErrandBrainService::class.java)
                    .setAction(ACTION_ADD_TASK)
                    .putExtra(EXTRA_TASK_TITLE, taskTitle),
            )
        }

        fun addReminder(context: Context, reminderTitle: String, reminderTime: Long) {
            context.startService(
                Intent(context, ErrandBrainService::class.java)
                    .setAction(ACTION_ADD_REMINDER)
                    .putExtra(EXTRA_REMINDER_TITLE, reminderTitle)
                    .putExtra(EXTRA_REMINDER_TIME, reminderTime),
            )
        }
    }
}
