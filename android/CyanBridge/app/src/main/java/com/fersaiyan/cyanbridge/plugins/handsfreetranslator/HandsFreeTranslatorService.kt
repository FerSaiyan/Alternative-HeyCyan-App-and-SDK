package com.fersaiyan.cyanbridge.plugins.handsfreetranslator

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.speech.tts.TextToSpeech
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
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Translates recognized speech from the phone or a connected Bluetooth glasses microphone. */
class HandsFreeTranslatorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val translatorStore = HandsFreeTranslatorStore()
    private val translating = AtomicBoolean(false)
    private var voiceRecognizer: PluginVoiceRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate() {
        super.onCreate()
        HandsFreeTranslatorNotificationHelper.ensureChannel(this)
        translatorStore.load(this)
        tts = TextToSpeech(this) { status -> ttsReady = status == TextToSpeech.SUCCESS }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTranslation()
            ACTION_STOP -> stopTranslation()
            ACTION_TRANSLATE_PHRASE -> intent.getStringExtra(EXTRA_PHRASE)?.let(::translatePhrase)
            null -> if (HandsFreeTranslatorPreferences.isEnabled(this)) startTranslation() else stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        voiceRecognizer?.stop()
        tts?.stop()
        tts?.shutdown()
        tts = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startTranslation() {
        if (voiceRecognizer != null) return
        if (!startPluginVoiceForeground(
                service = this,
                notificationId = HandsFreeTranslatorNotificationHelper.NOTIFICATION_ID,
                notification = HandsFreeTranslatorNotificationHelper.buildNotification(this, "Starting translator..."),
            )
        ) {
            Log.w(TAG, "Missing microphone or notification permission")
            stopSelf()
            return
        }

        val languageTag = HandsFreeTranslatorPreferences
            .getSourceLanguage(this)
            .takeIf { !HandsFreeTranslatorPreferences.isAutoDetect(this) }
        val recognizer = PluginVoiceRecognizer(
            context = this,
            languageTag = languageTag,
            onPartialText = { partial ->
                HandsFreeTranslatorNotificationHelper.updateNotification(
                    this,
                    "Listening: ${partial.take(NOTIFICATION_TEXT_LIMIT)}",
                )
            },
            onFinalText = ::translatePhrase,
            onError = { message ->
                Log.w(TAG, message)
                HandsFreeTranslatorNotificationHelper.updateNotification(this, message)
            },
        )
        if (!recognizer.start()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        voiceRecognizer = recognizer
        HandsFreeTranslatorNotificationHelper.updateNotification(this, "Listening for speech to translate...")
    }

    private fun stopTranslation() {
        voiceRecognizer?.stop()
        voiceRecognizer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun translatePhrase(phrase: String) {
        if (!translating.compareAndSet(false, true)) return
        scope.launch {
            try {
                val sourceLanguage = HandsFreeTranslatorPreferences.getSourceLanguage(this@HandsFreeTranslatorService)
                val targetLanguage = HandsFreeTranslatorPreferences.getTargetLanguage(this@HandsFreeTranslatorService)
                val autoDetect = HandsFreeTranslatorPreferences.isAutoDetect(this@HandsFreeTranslatorService)
                val translation = generateTranslation(phrase, sourceLanguage, targetLanguage, autoDetect)
                if (translation != null) {
                    translatorStore.addTranslation(
                        translation,
                        HandsFreeTranslatorPreferences.getMaxHistory(this@HandsFreeTranslatorService),
                    )
                    translatorStore.persist(
                        this@HandsFreeTranslatorService,
                        HandsFreeTranslatorPreferences.getMaxHistory(this@HandsFreeTranslatorService),
                    )
                    if (HandsFreeTranslatorPreferences.isSpeakTranslation(this@HandsFreeTranslatorService) && ttsReady) {
                        speakTranslation(translation.translatedText, translation.targetLanguage)
                    }
                    HandsFreeTranslatorNotificationHelper.updateNotification(
                        this@HandsFreeTranslatorService,
                        "Translation: ${translation.translatedText.take(NOTIFICATION_TEXT_LIMIT)}",
                    )
                    GlassesBridge.showCard(
                        DisplayCommand.Card(
                            title = "Translation",
                            body = translation.translatedText,
                        ),
                    )
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to translate speech", error)
                HandsFreeTranslatorNotificationHelper.updateNotification(
                    this@HandsFreeTranslatorService,
                    "Translation failed. Check your AI connection.",
                )
            } finally {
                translating.set(false)
            }
        }
    }

    private suspend fun generateTranslation(
        phrase: String,
        sourceLanguage: String,
        targetLanguage: String,
        autoDetect: Boolean,
    ): TranslationEntry? {
        // RAG profile NONE: translation uses only the current phrase and language settings.
        val customPrompt = HandsFreeTranslatorPreferences.getCustomPrompt(this)
        val prompt = buildString {
            append("Translate the following speech. ")
            if (autoDetect) append("Auto-detect its source language. ") else append("Source language: $sourceLanguage. ")
            append("Target language: $targetLanguage. ")
            append("Return only the translation. Speech: \"$phrase\". ")
            if (customPrompt.isNotBlank()) append("Additional instructions: $customPrompt")
        }
        return CliRelayClient.chat(
            context = this,
            chatId = "translator_${System.currentTimeMillis()}",
            prompt = prompt,
            messages = listOf(mapOf("role" to "user", "content" to prompt)),
            modelOverride = HandsFreeTranslatorPreferences.getCloudModelId(this),
        ).fold(
            onSuccess = { response ->
                TranslationEntry(
                    timestampMs = System.currentTimeMillis(),
                    originalText = phrase,
                    translatedText = response.trim(),
                    sourceLanguage = if (autoDetect) detectLanguage(phrase) else sourceLanguage,
                    targetLanguage = targetLanguage,
                    confidence = 1f,
                )
            },
            onFailure = { error ->
                Log.e(TAG, "Translation request failed", error)
                null
            },
        )
    }

    private fun detectLanguage(text: String): String = when {
        text.matches(Regex(".*[\\u4e00-\\u9fff].*")) -> "zh"
        text.matches(Regex(".*[\\u3040-\\u309f\\u30a0-\\u30ff].*")) -> "ja"
        text.matches(Regex(".*[\\uac00-\\ud7af].*")) -> "ko"
        else -> "en"
    }

    private fun speakTranslation(text: String, language: String) {
        tts?.language = when (language) {
            "en" -> Locale.US
            "es" -> Locale("es", "ES")
            "fr" -> Locale.FRANCE
            "de" -> Locale.GERMANY
            "it" -> Locale.ITALY
            "pt" -> Locale("pt", "BR")
            "zh" -> Locale.CHINA
            "ja" -> Locale.JAPAN
            "ko" -> Locale.KOREA
            else -> Locale.US
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "translation_utterance")
    }

    companion object {
        private const val TAG = "HandsFreeTranslator"
        private const val NOTIFICATION_TEXT_LIMIT = 100

        const val ACTION_START = "com.fersaiyan.cyanbridge.ACTION_START_TRANSLATOR"
        const val ACTION_STOP = "com.fersaiyan.cyanbridge.ACTION_STOP_TRANSLATOR"
        const val ACTION_TRANSLATE_PHRASE = "com.fersaiyan.cyanbridge.ACTION_TRANSLATE_PHRASE"
        const val EXTRA_PHRASE = "phrase"

        fun start(context: Context) {
            startPluginVoiceService(
                context,
                Intent(context, HandsFreeTranslatorService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, HandsFreeTranslatorService::class.java).setAction(ACTION_STOP),
            )
        }

        fun translate(context: Context, phrase: String) {
            startPluginVoiceService(
                context,
                Intent(context, HandsFreeTranslatorService::class.java)
                    .setAction(ACTION_TRANSLATE_PHRASE)
                    .putExtra(EXTRA_PHRASE, phrase),
            )
        }
    }
}
