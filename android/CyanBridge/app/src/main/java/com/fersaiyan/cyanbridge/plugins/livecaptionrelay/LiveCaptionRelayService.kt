package com.fersaiyan.cyanbridge.plugins.livecaptionrelay

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
import java.util.concurrent.atomic.AtomicBoolean

/** Provides live phone captions from the selected Android microphone or connected glasses mic. */
class LiveCaptionRelayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val captionStore = LiveCaptionRelayStore()
    private val translating = AtomicBoolean(false)
    private var voiceRecognizer: PluginVoiceRecognizer? = null

    override fun onCreate() {
        super.onCreate()
        LiveCaptionRelayNotificationHelper.ensureChannel(this)
        captionStore.load(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCaptioning()
            ACTION_STOP -> stopCaptioning()
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

    private fun startCaptioning() {
        if (voiceRecognizer != null) return
        if (!startPluginVoiceForeground(
                service = this,
                notificationId = LiveCaptionRelayNotificationHelper.NOTIFICATION_ID,
                notification = LiveCaptionRelayNotificationHelper.buildNotification(this, "Starting live captions..."),
            )
        ) {
            Log.w(TAG, "Missing microphone or notification permission")
            stopSelf()
            return
        }

        val sourceLanguage = LiveCaptionRelayPreferences.getSourceLanguage(this)
        val recognizer = PluginVoiceRecognizer(
            context = this,
            languageTag = sourceLanguage,
            onPartialText = { partial ->
                LiveCaptionRelayNotificationHelper.updateNotification(
                    this,
                    "Listening: ${partial.take(NOTIFICATION_TEXT_LIMIT)}",
                )
            },
            onFinalText = ::saveCaption,
            onError = { message ->
                Log.w(TAG, message)
                LiveCaptionRelayNotificationHelper.updateNotification(this, message)
            },
        )
        if (!recognizer.start()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        voiceRecognizer = recognizer
        LiveCaptionRelayNotificationHelper.updateNotification(this, "Listening for speech...")
    }

    private fun stopCaptioning() {
        voiceRecognizer?.stop()
        voiceRecognizer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun saveCaption(text: String) {
        val sourceLanguage = LiveCaptionRelayPreferences.getSourceLanguage(this)
        if (!LiveCaptionRelayPreferences.isTranslationEnabled(this)) {
            persistCaption(
                CaptionEntry(
                    timestampMs = System.currentTimeMillis(),
                    originalText = text,
                    translatedText = null,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = null,
                    confidence = 0f,
                ),
            )
            return
        }

        if (!translating.compareAndSet(false, true)) {
            persistCaption(
                CaptionEntry(
                    timestampMs = System.currentTimeMillis(),
                    originalText = text,
                    translatedText = null,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = null,
                    confidence = 0f,
                ),
            )
            return
        }
        scope.launch {
            try {
                val targetLanguage = LiveCaptionRelayPreferences.getTargetLanguage(this@LiveCaptionRelayService)
                val translated = translateCaption(text, sourceLanguage, targetLanguage)
                persistCaption(
                    CaptionEntry(
                        timestampMs = System.currentTimeMillis(),
                        originalText = text,
                        translatedText = translated,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        confidence = 0f,
                    ),
                )
            } catch (error: Throwable) {
                Log.e(TAG, "Caption processing failed", error)
                persistCaption(
                    CaptionEntry(
                        timestampMs = System.currentTimeMillis(),
                        originalText = text,
                        translatedText = null,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = null,
                        confidence = 0f,
                    ),
                )
            } finally {
                translating.set(false)
            }
        }
    }

    private fun persistCaption(caption: CaptionEntry) {
        captionStore.addCaption(caption, LiveCaptionRelayPreferences.getMaxHistory(this))
        captionStore.persist(this, LiveCaptionRelayPreferences.getMaxHistory(this))
        val displayText = caption.translatedText ?: caption.originalText
        LiveCaptionRelayNotificationHelper.updateNotification(
            this,
            "Caption: ${displayText.take(NOTIFICATION_TEXT_LIMIT)}",
        )
        scope.launch {
            // MYVU maps this through its on-lens teleprompter; other active
            // adapters can render the same native-plugin result in their format.
            GlassesBridge.showText(DisplayCommand.Text("Caption\n$displayText"))
        }
    }

    private suspend fun translateCaption(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): String? {
        val customPrompt = LiveCaptionRelayPreferences.getCustomPrompt(this)
        val prompt = buildString {
            append("Translate this live caption from $sourceLanguage to $targetLanguage. ")
            append("Return only the translated caption. Caption: \"$text\". ")
            if (customPrompt.isNotBlank()) append("Additional instructions: $customPrompt")
        }
        return CliRelayClient.chat(
            context = this,
            chatId = "live_caption_${System.currentTimeMillis()}",
            prompt = prompt,
            messages = listOf(mapOf("role" to "user", "content" to prompt)),
            modelOverride = LiveCaptionRelayPreferences.getCloudModelId(this),
        ).fold(
            onSuccess = { it.trim().takeIf(String::isNotBlank) },
            onFailure = { error ->
                Log.e(TAG, "Caption translation failed", error)
                null
            },
        )
    }

    companion object {
        private const val TAG = "LiveCaptionRelay"
        private const val NOTIFICATION_TEXT_LIMIT = 100

        const val ACTION_START = "com.fersaiyan.cyanbridge.ACTION_START_CAPTION"
        const val ACTION_STOP = "com.fersaiyan.cyanbridge.ACTION_STOP_CAPTION"

        fun start(context: Context) {
            startPluginVoiceService(
                context,
                Intent(context, LiveCaptionRelayService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LiveCaptionRelayService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
