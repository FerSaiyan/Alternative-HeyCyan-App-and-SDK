package com.fersaiyan.cyanbridge.shared.platform

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
 * Continuous on-device speech recognition from the glasses' Bluetooth mic when
 * available (Android plugins use SpeechRecognizer the same way). A phrase is
 * final after [SILENCE_MS] without new words; each phrase restarts the task to
 * stay under SFSpeechRecognizer's per-request duration limit.
 */
@OptIn(ExperimentalForeignApi::class)
class IosSpeechEngine(
    private val onPartial: (String) -> Unit,
    private val onPhrase: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val engine = AVAudioEngine()
    private var recognizer: SFSpeechRecognizer? = null
    private var request: SFSpeechAudioBufferRecognitionRequest? = null
    private var task: SFSpeechRecognitionTask? = null
    private var silenceJob: Job? = null
    private var latestText = ""
    private var running = false
    private var paused = false

    /** Returns an error text, or null when listening started. */
    suspend fun start(): String? {
        if (running) return null
        if (!requestSpeechAuthorization()) return "Speech recognition permission is needed"
        if (!IosChatPlatform.requestMicrophonePermission()) return "Microphone access is needed"
        IosChatPlatform.configureRecordingSession()
        val language = (NSLocale.preferredLanguages.firstOrNull() as? String) ?: "en-US"
        val speechRecognizer = SFSpeechRecognizer(locale = NSLocale(localeIdentifier = language))
        if (!speechRecognizer.isAvailable()) return "Speech recognition is unavailable for $language"
        recognizer = speechRecognizer

        val input = engine.inputNode
        input.installTapOnBus(0u, bufferSize = 1024u, format = input.outputFormatForBus(0u)) { buffer, _ ->
            if (!paused && buffer != null) request?.appendAudioPCMBuffer(buffer)
        }
        engine.prepare()
        if (!engine.startAndReturnError(null)) {
            input.removeTapOnBus(0u)
            return "Could not start the microphone"
        }
        running = true
        startTask()
        return null
    }

    fun stop() {
        if (!running) return
        running = false
        silenceJob?.cancel()
        flushPhrase()
        task?.cancel()
        task = null
        request?.endAudio()
        request = null
        engine.stop()
        engine.inputNode.removeTapOnBus(0u)
    }

    /** Ignores the mic while the app speaks, so replies are not re-recognized. */
    fun setPaused(value: Boolean) {
        paused = value
    }

    private fun startTask() {
        val speechRecognizer = recognizer ?: return
        val recognitionRequest = SFSpeechAudioBufferRecognitionRequest()
        recognitionRequest.shouldReportPartialResults = true
        request = recognitionRequest
        latestText = ""
        task = speechRecognizer.recognitionTaskWithRequest(recognitionRequest) { result, error ->
            scope.launch {
                if (!running) return@launch
                val text = result?.bestTranscription?.formattedString.orEmpty()
                if (text.isNotBlank() && text != latestText) {
                    latestText = text
                    onPartial(text)
                    scheduleSilenceCheck()
                }
                if (result?.isFinal() == true || error != null) restartTask()
            }
        }
    }

    private fun scheduleSilenceCheck() {
        silenceJob?.cancel()
        silenceJob = scope.launch {
            delay(SILENCE_MS)
            restartTask()
        }
    }

    private fun restartTask() {
        silenceJob?.cancel()
        flushPhrase()
        task?.cancel()
        request?.endAudio()
        if (running) startTask()
    }

    private fun flushPhrase() {
        val phrase = latestText.trim()
        latestText = ""
        if (phrase.isNotEmpty()) onPhrase(phrase)
    }

    private suspend fun requestSpeechAuthorization(): Boolean = suspendCancellableCoroutine { continuation ->
        SFSpeechRecognizer.requestAuthorization { status ->
            if (continuation.isActive) {
                continuation.resume(status == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized)
            }
        }
    }

    private companion object {
        const val SILENCE_MS = 2_000L
    }
}
