package com.fersaiyan.cyanbridge.localagent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.ai.AiQuestionForegroundService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Hands-free approval voice loop for Local Agent high-risk actions.
 *
 * Production input is Android speech recognition using the active Bluetooth/phone microphone.
 * The same dedicated microphone foreground-service lifecycle used by glasses AI questions keeps
 * the spoken confirmation alive while CyanBridge is not visible. Other trusted CyanBridge
 * surfaces can feed equivalent transcribed replies through [submitExternalReply]; HIL uses that
 * same path so it can test the conversation deterministically without pretending an emulator
 * microphone heard synthetic speech.
 */
class LocalAgentApprovalVoiceSession(
    context: Context,
    private val onStatus: (String) -> Unit,
) : Closeable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val externalReplies = Channel<String>(Channel.UNLIMITED)
    private val ttsReady = CompletableDeferred<Boolean>()
    private val tts = TextToSpeech(appContext) { status ->
        if (!ttsReady.isCompleted) ttsReady.complete(status == TextToSpeech.SUCCESS)
    }

    fun submitExternalReply(reply: String) {
        val trimmed = reply.trim()
        if (trimmed.isNotBlank()) externalReplies.trySend(trimmed)
    }

    suspend fun askAndListen(prompt: String, timeoutMs: Long = DEFAULT_LISTEN_TIMEOUT_MS): String? {
        AiQuestionForegroundService.start(appContext, "Waiting for spoken Local Agent approval")
        return try {
            LocalAgentPrefs.setLastApprovalVoicePrompt(appContext, prompt)
            LocalAgentPrefs.clearLastApprovalVoiceReply(appContext)
            onStatus("Speaking voice confirmation")
            speakAndAwait(prompt)
            onStatus("Listening for confirmation")

            val speechAvailable = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED && SpeechRecognizer.isRecognitionAvailable(appContext)

            val reply = if (!speechAvailable) {
                withTimeoutOrNull(timeoutMs) { externalReplies.receive() }
            } else {
                coroutineScope {
                    val speech = async { recognizeOnce(timeoutMs) }
                    val first = select<String?> {
                        externalReplies.onReceive { it }
                        speech.onAwait { it }
                    }
                    if (!speech.isCompleted) speech.cancel()
                    if (first != null) first else withTimeoutOrNull(timeoutMs) { externalReplies.receive() }
                }
            }

            reply?.trim()?.takeIf { it.isNotBlank() }?.also {
                LocalAgentPrefs.setLastApprovalVoiceReply(appContext, it)
            }
            reply
        } finally {
            AiQuestionForegroundService.stop(appContext)
        }
    }

    private suspend fun speakAndAwait(text: String) {
        val ready = withTimeoutOrNull(TTS_INIT_TIMEOUT_MS) { ttsReady.await() } == true
        if (!ready) return
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val id = "local_agent_approval_${UUID.randomUUID()}"
                tts.language = Locale.getDefault()
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == id && continuation.isActive) continuation.resume(Unit)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == id && continuation.isActive) continuation.resume(Unit)
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == id && continuation.isActive) continuation.resume(Unit)
                    }
                })
                val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
                if (result != TextToSpeech.SUCCESS && continuation.isActive) continuation.resume(Unit)
                continuation.invokeOnCancellation { tts.stop() }
            }
        }
    }

    private suspend fun recognizeOnce(timeoutMs: Long): String? = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val route = prepareVoiceRoute(audioManager)
            val recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(appContext) }.getOrNull()
            if (recognizer == null) {
                restoreVoiceRoute(audioManager, route)
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            var finished = false
            val timeout = Runnable {
                if (!finished) {
                    finished = true
                    runCatching { recognizer.cancel() }
                    runCatching { recognizer.destroy() }
                    restoreVoiceRoute(audioManager, route)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            mainHandler.postDelayed(timeout, timeoutMs)

            fun finish(value: String?) {
                if (finished) return
                finished = true
                mainHandler.removeCallbacks(timeout)
                runCatching { recognizer.destroy() }
                restoreVoiceRoute(audioManager, route)
                if (continuation.isActive) continuation.resume(value?.trim()?.takeIf { it.isNotBlank() })
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onError(error: Int) = finish(null)
                override fun onResults(results: Bundle?) {
                    finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            val languageTag = Locale.getDefault().toLanguageTag()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
            }
            runCatching { recognizer.startListening(intent) }.onFailure { finish(null) }

            continuation.invokeOnCancellation {
                mainHandler.removeCallbacks(timeout)
                runCatching { recognizer.cancel() }
                runCatching { recognizer.destroy() }
                restoreVoiceRoute(audioManager, route)
            }
        }
    }

    private data class VoiceRoute(
        val previousMode: Int,
        val previousCommunicationDevice: AudioDeviceInfo?,
        val legacyScoStarted: Boolean,
    )

    private fun prepareVoiceRoute(audioManager: AudioManager): VoiceRoute {
        val previousMode = audioManager.mode
        val previousCommunicationDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice
        } else null
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        var legacyScoStarted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val bluetooth = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            if (bluetooth != null) runCatching { audioManager.setCommunicationDevice(bluetooth) }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
                legacyScoStarted = true
            }
        }
        return VoiceRoute(previousMode, previousCommunicationDevice, legacyScoStarted)
    }

    private fun restoreVoiceRoute(audioManager: AudioManager, route: VoiceRoute) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val previous = route.previousCommunicationDevice
                if (previous != null) audioManager.setCommunicationDevice(previous)
                else audioManager.clearCommunicationDevice()
            }
        } else if (route.legacyScoStarted) {
            @Suppress("DEPRECATION")
            runCatching {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
            }
        }
        audioManager.mode = route.previousMode
    }

    override fun close() {
        externalReplies.close()
        AiQuestionForegroundService.stop(appContext)
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }

    companion object {
        const val DEFAULT_LISTEN_TIMEOUT_MS = 30_000L
        private const val TTS_INIT_TIMEOUT_MS = 5_000L
    }
}
