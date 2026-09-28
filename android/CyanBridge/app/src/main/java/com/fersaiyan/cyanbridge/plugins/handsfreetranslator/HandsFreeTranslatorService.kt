package com.fersaiyan.cyanbridge.plugins.handsfreetranslator

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.speech.tts.UtteranceProgressListener
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
import java.util.concurrent.atomic.AtomicReference

/** Translates recognized speech from the phone or a connected Bluetooth glasses microphone. */
class HandsFreeTranslatorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val translatorStore = HandsFreeTranslatorStore()
    private val translating = AtomicBoolean(false)
    private var voiceRecognizer: PluginVoiceRecognizer? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingSpeech = AtomicReference<Pair<String, String>?>(null)
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    override fun onCreate() {
        super.onCreate()
        HandsFreeTranslatorNotificationHelper.ensureChannel(this)
        translatorStore.load(this)
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            Log.i(TAG, "Translation TTS initialization successful=$ttsReady")
            if (ttsReady) pendingSpeech.getAndSet(null)?.let { (text, language) ->
                mainHandler.post { speakTranslation(text, language) }
            } else {
                pendingSpeech.set(null)
                HandsFreeTranslatorNotificationHelper.updateNotification(
                    this, "Translator active; spoken output unavailable (TTS initialization failed)",
                )
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { Log.d(TAG, "Translation speech started") }
            override fun onDone(utteranceId: String?) { Log.d(TAG, "Translation speech finished") }
            @Deprecated("Use onError with code")
            override fun onError(utteranceId: String?) {
                Log.w(TAG, "Translation speech synthesis failed")
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "Translation speech synthesis failed errorCode=$errorCode")
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTranslation()
            ACTION_STOP -> stopTranslation()
            ACTION_TRANSLATE_PHRASE -> intent.getStringExtra(EXTRA_PHRASE)?.let(::translatePhrase)
            null -> stopSelf()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        voiceRecognizer?.stop()
        pendingSpeech.set(null)
        mainHandler.removeCallbacksAndMessages(null)
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
                    if (HandsFreeTranslatorPreferences.isSpeakTranslation(this@HandsFreeTranslatorService)) {
                        val utterance = translation.translatedText to translation.targetLanguage
                        if (ttsReady) {
                            mainHandler.post { speakTranslation(utterance.first, utterance.second) }
                        } else {
                            // A translation can finish before Android binds the TTS engine.
                            // Keep the newest phrase instead of silently showing text only.
                            pendingSpeech.set(utterance)
                            Log.i(TAG, "Translation speech queued until TTS initialization")
                        }
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
        if (!ttsReady) {
            pendingSpeech.set(text to language)
            return
        }
        val engine = tts ?: return
        val locale = when (language) {
            "en" -> Locale.US
            "es" -> Locale("es", "ES")
            "fr" -> Locale.FRANCE
            "de" -> Locale.GERMANY
            "it" -> Locale.ITALY
            "pt" -> Locale("pt", "BR")
            "zh" -> Locale.CHINA
            "ja" -> Locale.JAPAN
            "ko" -> Locale.KOREA
            else -> Locale.forLanguageTag(language).takeIf { it.language.isNotBlank() } ?: Locale.US
        }
        val languageResult = engine.setLanguage(locale)
        if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            Log.w(TAG, "Translation TTS language unavailable tag=$language")
            HandsFreeTranslatorNotificationHelper.updateNotification(
                this, "Translation shown; install a TTS voice for $language to hear it",
            )
            return
        }

        // PluginVoiceRecognizer already negotiates the Bluetooth microphone's
        // communication device; route synthesized speech with matching audio attributes.
        // If the glasses expose only A2DP, use the system's current media output route.
        val communicationDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice
        } else null
        val hasBluetoothCommunication = communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                communicationDevice?.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        val bluetoothMediaConnected = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
        }
        val usage = if (hasBluetoothCommunication || !bluetoothMediaConnected) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION
        } else AudioAttributes.USAGE_MEDIA
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(usage)
                .build(),
        )
        Log.i(
            TAG,
            "Translation output usage=$usage communicationDevice=${communicationDevice?.type} " +
                "bluetoothMediaConnected=$bluetoothMediaConnected language=$language",
        )
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "translation_utterance")
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Translation TTS speak() failed result=$result")
            HandsFreeTranslatorNotificationHelper.updateNotification(
                this, "Translation shown; speech playback failed on the selected audio route",
            )
        }
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
