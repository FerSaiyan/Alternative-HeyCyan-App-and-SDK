package com.fersaiyan.cyanbridge.audio

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log
import com.fersaiyan.cyanbridge.ai.image.AudioQuestionRecorder
import com.fersaiyan.cyanbridge.ai.transcription.OfflineQuestionTranscriber
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionDefaults
import com.fersaiyan.cyanbridge.shared.assistant.AssistantSpeechStream
import com.fersaiyan.cyanbridge.shared.assistant.AssistantVoice
import com.fersaiyan.cyanbridge.shared.audio.AudioRouteWait
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Android backing for the shared glasses assistant's [AssistantVoice], built on the process audio
 * sessions: [listen] opens a voice-capture session, brings up the glasses' Bluetooth microphone
 * (confirmed, preferring [glassesDeviceName]), speaks the question cue on the call route and
 * records until the question ends; [usePlaybackRoute] leaves the call route so the answer plays
 * as normal speech; [releaseAudio] ends the session. Answers stream through [TtsSpeechStream].
 *
 * Integration points (taken as parameters, owned by MainActivity / the glasses layer):
 * [languageTag] (ImageQuestionPreferences app language), [glassesDeviceName] (the connected glasses'
 * Bluetooth name) and [waitCueText] (the "one moment" phrase, which Android does not have yet).
 */
class AndroidAssistantVoice(
    context: Context,
    private val scope: CoroutineScope,
    private val languageTag: () -> String,
    private val glassesDeviceName: () -> String? = { null },
    private val waitCueText: (languageTag: String) -> String = { "" },
) : AssistantVoice {
    private val appContext = context.applicationContext
    private val tts = TtsSpeaker(appContext, TAG)

    @Volatile
    private var session: AudioSessionHandle? = null

    override suspend fun listen(initialTimeoutMs: Long): String? {
        val handle = session?.takeUnless { it.isClosed }
            ?: AudioSessionCoordinator.tryOpen(appContext, AudioSessionRequest.VoiceCapture(TAG))?.also { session = it }
            ?: run {
                Log.w(TAG, "Microphone is busy; not listening")
                return null
            }
        val route = handle.connectRoute(AudioRouteWait.CONFIRMED, glassesDeviceName(), requireUnambiguous = true)
        if (route.ambiguous || (route.bluetoothCandidatesPresent && !route.bluetoothActive)) {
            Log.w(TAG, "Could not connect the glasses microphone ambiguous=${route.ambiguous}")
            return null
        }
        tts.setLanguage(Locale.forLanguageTag(languageTag()))
        tts.useVoiceCommunicationAudio()
        // The cue never blocks the question for long if the engine does not report back.
        tts.speakAndAwait(questionCue(), "assistant_cue_${System.nanoTime()}", CUE_READY_TIMEOUT_MS, CUE_TIMEOUT_MS)
        delay(CUE_ROUTE_TAIL_MS)
        val recording = AudioQuestionRecorder(appContext).record(handle, initialTimeoutMs, route.inputDevice) ?: return null
        return try {
            OfflineQuestionTranscriber(appContext).transcribe(recording, languageTag())
        } finally {
            recording.delete()
        }
    }

    override fun stopSpeaking() = tts.stop()

    override fun say(text: String) {
        if (text.isBlank()) return
        tts.whenReady { it.speak(text, "assistant_say_${System.nanoTime()}", TextToSpeech.QUEUE_ADD) }
    }

    override fun waitCue(): String = waitCueText(languageTag())

    override fun questionCue(): String = ImageQuestionDefaults.questionCueForLanguage(languageTag())

    override fun usePlaybackRoute() {
        session?.leaveRoute()
        tts.setAudioAttributes(MEDIA_SPEECH)
    }

    override fun releaseAudio() {
        session?.close()
        session = null
    }

    override fun streamingSpeech(beforeFirstChunk: suspend () -> Unit): AssistantSpeechStream =
        TtsSpeechStream(tts, scope, beforeFirstChunk)

    fun shutdown() {
        releaseAudio()
        tts.shutdown()
    }

    private companion object {
        const val TAG = "AndroidAssistantVoice"
        const val CUE_READY_TIMEOUT_MS = 3_000L
        const val CUE_TIMEOUT_MS = 2_000L
        const val CUE_ROUTE_TAIL_MS = 50L
        val MEDIA_SPEECH: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
