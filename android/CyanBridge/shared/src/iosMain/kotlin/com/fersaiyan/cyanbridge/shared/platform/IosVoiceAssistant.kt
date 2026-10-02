package com.fersaiyan.cyanbridge.shared.platform

import com.fersaiyan.cyanbridge.shared.speech.StreamingSpeechChunker
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioEngine
import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import kotlin.coroutines.resume

/**
 * Spoken questions from the glasses (Android: captureOptionalImageQuestionFromBluetoothMic).
 * Speaks the cue ("Pergunte."), then recognizes speech live from the Bluetooth mic: it gives up
 * when nothing is said within the initial window and ends after [COMPLETE_SILENCE_MS] of quiet.
 */
@OptIn(ExperimentalForeignApi::class)
object IosVoiceQuestion {
    private const val TAG = "IosVoiceQuestion"
    /** Android: EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS. */
    private const val COMPLETE_SILENCE_MS = 2_000L
    private const val MAX_QUESTION_MS = 20_000L
    /** Sentinel from [listen] when the audio engine could not start. */
    private const val MIC_FAILED = "\u0000mic-failed"

    /** Returns the question, or null when the user stayed silent. */
    suspend fun ask(initialTimeoutMs: Long): String? {
        if (!requestSpeechAuthorization() || !IosChatPlatform.requestMicrophonePermission()) {
            PlatformLogger.w(TAG, "Speech recognition or microphone permission missing")
            return null
        }
        if (IosChatPlatform.configureRecordingSession() != "iPhone microphone") IosChatPlatform.awaitBluetoothInput()
        IosChatPlatform.speak(IosChatPlatform.questionCue())
        val language = (NSLocale.preferredLanguages.firstOrNull() as? String) ?: "en-US"
        val recognizer = SFSpeechRecognizer(locale = NSLocale(localeIdentifier = language))
        if (!recognizer.isAvailable()) {
            PlatformLogger.w(TAG, "Speech recognition unavailable for $language")
            return null
        }
        // Right after the glasses spoke, the Bluetooth input can still be switching and the
        // engine fails to start; retry with a fresh engine.
        var question: String? = null
        for (attempt in 1..3) {
            val result = listen(recognizer, initialTimeoutMs)
            if (result != MIC_FAILED) {
                question = result
                break
            }
            PlatformLogger.w(TAG, "Microphone did not start (attempt $attempt)")
            delay(500L)
        }
        PlatformLogger.i(TAG, "Question via ${IosChatPlatform.currentInputName() ?: "?"}: ${question ?: "(silence)"}")
        return question
    }

    private suspend fun listen(recognizer: SFSpeechRecognizer, initialTimeoutMs: Long): String? =
        suspendCancellableCoroutine { continuation ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val engine = AVAudioEngine()
            val request = SFSpeechAudioBufferRecognitionRequest()
            request.shouldReportPartialResults = true
            if (recognizer.supportsOnDeviceRecognition) request.requiresOnDeviceRecognition = true
            var task: SFSpeechRecognitionTask? = null
            var latest = ""
            var silenceJob: Job? = null
            var finished = false

            fun finish() {
                if (finished) return
                finished = true
                silenceJob?.cancel()
                engine.stop()
                engine.inputNode.removeTapOnBus(0u)
                request.endAudio()
                task?.cancel()
                scope.launch {
                    if (continuation.isActive) continuation.resume(latest.trim().takeIf { it.isNotEmpty() })
                }
            }

            val input = engine.inputNode
            input.installTapOnBus(0u, bufferSize = 1024u, format = input.outputFormatForBus(0u)) { buffer, _ ->
                if (buffer != null) request.appendAudioPCMBuffer(buffer)
            }
            engine.prepare()
            if (!engine.startAndReturnError(null)) {
                input.removeTapOnBus(0u)
                continuation.resume(MIC_FAILED)
                return@suspendCancellableCoroutine
            }
            task = recognizer.recognitionTaskWithRequest(request) { result, error ->
                scope.launch {
                    if (finished) return@launch
                    val text = result?.bestTranscription?.formattedString.orEmpty()
                    if (text.isNotBlank() && text != latest) {
                        latest = text
                        silenceJob?.cancel()
                        silenceJob = scope.launch {
                            delay(COMPLETE_SILENCE_MS)
                            finish()
                        }
                    }
                    if (result?.isFinal() == true || error != null) finish()
                }
            }
            scope.launch {
                delay(initialTimeoutMs)
                if (latest.isBlank()) finish()
            }
            scope.launch {
                delay(MAX_QUESTION_MS)
                finish()
            }
            continuation.invokeOnCancellation { scope.launch { finish() } }
        }

    private suspend fun requestSpeechAuthorization(): Boolean = suspendCancellableCoroutine { continuation ->
        SFSpeechRecognizer.requestAuthorization { status ->
            if (continuation.isActive) {
                continuation.resume(status == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized)
            }
        }
    }
}

/**
 * Speaks an LLM reply while it is generated (Android: StreamingSpeechSessionManager): the
 * shared [StreamingSpeechChunker] picks the chunks and AVSpeechSynthesizer queues them.
 */
class IosStreamingSpeech(private val onFirstChunk: () -> Unit = {}) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var idleJob: Job? = null
    private var started = false
    private val chunker = StreamingSpeechChunker { chunk ->
        if (!started) {
            started = true
            onFirstChunk()
        }
        IosChatPlatform.enqueueSpeech(chunk)
    }

    /** Appends a token delta from the model. */
    fun append(delta: String) {
        chunker.append(delta)
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(
                if (chunker.isFirstChunkPending) StreamingSpeechChunker.FIRST_IDLE_FLUSH_MS
                else StreamingSpeechChunker.NORMAL_IDLE_FLUSH_MS,
            )
            chunker.flushIdle()
        }
    }

    /** Speaks whatever is left and waits until the glasses finished talking. */
    suspend fun finish() {
        idleJob?.cancel()
        chunker.finish()
        IosChatPlatform.awaitSpeechDone()
    }

    fun cancel() {
        idleJob?.cancel()
        chunker.reset()
        IosChatPlatform.stopSpeaking()
    }
}
