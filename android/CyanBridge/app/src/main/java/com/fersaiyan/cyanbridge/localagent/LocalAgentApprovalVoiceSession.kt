package com.fersaiyan.cyanbridge.localagent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.ai.AiQuestionForegroundService
import com.fersaiyan.cyanbridge.audio.AudioSessionCoordinator
import com.fersaiyan.cyanbridge.audio.TtsSpeaker
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionRequest
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
    private val tts = TtsSpeaker(appContext, TAG)

    fun submitExternalReply(reply: String) {
        val trimmed = reply.trim()
        if (trimmed.isNotBlank()) externalReplies.trySend(trimmed)
    }

    suspend fun askAndListen(prompt: String, timeoutMs: Long = DEFAULT_LISTEN_TIMEOUT_MS): String? {
        AiQuestionForegroundService.start(appContext, "Waiting for spoken Local Agent approval")
        return try {
            LocalAgentPrefs.setLastApprovalVoicePrompt(appContext, prompt)
            // Preserve the last received answer while speaking a clarification.
            // The service clears it once when a *new* pending action begins.
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
        if (!tts.awaitReady(TTS_INIT_TIMEOUT_MS)) return
        withContext(Dispatchers.Main.immediate) {
            tts.setLanguage(Locale.getDefault())
            tts.speakAndAwait(
                text = text,
                utteranceId = "local_agent_approval_${UUID.randomUUID()}",
                readyTimeoutMs = 0L,
                params = Bundle(),
            )
        }
    }

    private suspend fun recognizeOnce(timeoutMs: Long): String? = withContext(Dispatchers.Main.immediate) {
        val session = AudioSessionCoordinator.tryOpen(appContext, AudioSessionRequest.VoiceCapture(TAG))
            ?: return@withContext null
        try {
            session.connectRoute()
            recognizeWithRoute(timeoutMs)
        } finally {
            session.close()
        }
    }

    private suspend fun recognizeWithRoute(timeoutMs: Long): String? =
        suspendCancellableCoroutine { continuation ->
            val recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(appContext) }.getOrNull()
            if (recognizer == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            var finished = false
            val timeout = Runnable {
                if (!finished) {
                    finished = true
                    runCatching { recognizer.cancel() }
                    runCatching { recognizer.destroy() }
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            mainHandler.postDelayed(timeout, timeoutMs)

            fun finish(value: String?) {
                if (finished) return
                finished = true
                mainHandler.removeCallbacks(timeout)
                runCatching { recognizer.destroy() }
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
            }
        }

    override fun close() {
        externalReplies.close()
        AiQuestionForegroundService.stop(appContext)
        tts.shutdown()
    }

    companion object {
        const val DEFAULT_LISTEN_TIMEOUT_MS = 30_000L
        private const val TTS_INIT_TIMEOUT_MS = 5_000L
        private const val TAG = "LocalAgentApprovalVoice"
    }
}
