package com.fersaiyan.cyanbridge.ai.questions

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.fersaiyan.cyanbridge.ai.image.AudioQuestionRecorder
import com.fersaiyan.cyanbridge.ai.image.GlassesQuestionTestHooks
import com.fersaiyan.cyanbridge.ai.router.QuestionRoutingPrefs
import com.fersaiyan.cyanbridge.ai.speaker.VoiceProfile
import com.fersaiyan.cyanbridge.ai.transcription.OfflineQuestionTranscriber
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionDefaults
import com.fersaiyan.cyanbridge.ai.vision.ImageQuestionPreferences
import com.fersaiyan.cyanbridge.audio.AudioRouteResult
import com.fersaiyan.cyanbridge.audio.AudioSessionCoordinator
import com.fersaiyan.cyanbridge.audio.AudioSessionGrant
import com.fersaiyan.cyanbridge.audio.AudioSessionHandle
import com.fersaiyan.cyanbridge.shared.assistant.CapturedQuestion
import com.fersaiyan.cyanbridge.shared.assistant.SpokenQuestionMicrophone
import com.fersaiyan.cyanbridge.shared.audio.AudioRoutePolicy
import com.fersaiyan.cyanbridge.shared.audio.AudioRoutePreference
import com.fersaiyan.cyanbridge.shared.audio.AudioRouteWait
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionRequest
import com.fersaiyan.cyanbridge.shared.devices.GlassesAudioProfile
import com.fersaiyan.cyanbridge.shared.settings.QuestionRoutingMode
import com.oudmon.ble.base.bluetooth.DeviceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * The glasses microphone for questions: takes a [AudioSessionRequest.VoiceCapture] session from
 * [AudioSessionCoordinator], joins the shared Bluetooth headset route through it, plays the beep and
 * the spoken cue, records until the question ends, then (unless a model hears the recording)
 * transcribes it on the phone. One question records at a time.
 *
 * The session decides who else may use the microphone: Gemini Live and voice plugins pause while
 * the question records, a meeting recording keeps going (and keeps its route), and another spoken
 * prompt (voice enrollment, a spoken approval) refuses the question before any beep. Glasses whose
 * SDK streams microphone PCM (Mentra Live, [AudioRoutePreference.DEVICE_SDK]) are recorded from
 * that stream, so no Bluetooth call route is opened next to it.
 */
internal class QuestionMicrophone(
    context: Context,
    private val speaker: AssistantSpeaker,
    private val scope: () -> CoroutineScope,
    private val languageTag: () -> String,
    private val deviceAudio: () -> GlassesAudioProfile?,
    /** The question was recorded: announce a cold local-model load if one follows. */
    private val onQuestionRecorded: () -> Unit,
    /** A model hears the recording, so no on-device transcript is needed. */
    private val audioGoesToModel: () -> Boolean,
) : SpokenQuestionMicrophone {
    private val appContext = context.applicationContext
    private val mutex = Mutex()

    /** The route a question asks for, after the selected glasses' audio profile. */
    private fun routePreference(): AudioRoutePreference =
        AudioRoutePolicy.forDevice(AudioRoutePreference.COMMUNICATION_BLUETOOTH_PREFERRED, deviceAudio())

    override suspend fun recordQuestion(onsetTimeoutMs: Long): CapturedQuestion =
        capture(onsetTimeoutMs, voiceOnly = true, transcribe = false)

    override fun deleteRecording(audioPath: String) {
        File(audioPath).delete()
    }

    suspend fun capture(
        timeoutMs: Long,
        voiceOnly: Boolean = false,
        /** null: on-device STT only when no model can hear the recording (see processRecordedQuestion). */
        transcribe: Boolean? = null,
    ): CapturedQuestion = mutex.withLock {
        if (GlassesQuestionTestHooks.questionAudioActive) {
            Log.i("ImageQuestionAudio", "Using test question audio instead of the microphone")
            val fixture = GlassesQuestionTestHooks.takeQuestionAudio(appContext.cacheDir)
                ?: return@withLock CapturedQuestion(null, false)
            return@withLock processRecordedQuestion(fixture, voiceOnly, transcribe)
        }
        val session = openSession() ?: return@withLock CapturedQuestion(null, false, error = MICROPHONE_BUSY)
        var audioFile: File? = null
        try {
            // Closing the session (also on cancellation) closes the recorder and leaves the shared
            // route; the route is restored only if no other session (a meeting, Live) still uses it.
            session.use {
                val route = withContext(Dispatchers.Main) {
                    val route = connectRoute(session)
                    playTone(android.media.ToneGenerator.TONE_PROP_BEEP)
                    speakCue(voiceOnly)
                    route
                }
                audioFile = AudioQuestionRecorder(appContext).record(session, timeoutMs, route.inputDevice)
                withContext(Dispatchers.Main) { playTone(android.media.ToneGenerator.TONE_PROP_BEEP2) }
            }
        } catch (cancelled: CancellationException) {
            audioFile?.delete()
            throw cancelled
        } catch (error: Exception) {
            audioFile?.delete()
            Log.w("ImageQuestionAudio", "Question capture failed", error)
            return@withLock CapturedQuestion(null, false, error = error.message ?: "Microphone capture failed")
        }
        val recorded = audioFile ?: return@withLock CapturedQuestion(null, false)
        processRecordedQuestion(recorded, voiceOnly, transcribe)
    }

    /** The question's microphone session, or null when another spoken prompt holds the microphone. */
    private fun openSession(): AudioSessionHandle? {
        val request = AudioSessionRequest.VoiceCapture(SESSION_OWNER, route = routePreference())
        return when (val grant = AudioSessionCoordinator.open(appContext, request)) {
            is AudioSessionGrant.Granted -> grant.handle.takeIf { it.isActive } ?: run {
                grant.handle.close()
                null
            }
            is AudioSessionGrant.Denied -> {
                Log.i("ImageQuestionAudio", "Question refused: ${grant.reason}")
                null
            }
        }
    }

    /**
     * Joins the shared route: the glasses' Bluetooth microphone (by the glasses' name when several
     * headsets are offered), or nothing for glasses with an SDK microphone. Throws when a headset is
     * offered but the glasses' microphone could not be selected.
     */
    private suspend fun connectRoute(session: AudioSessionHandle): AudioRouteResult {
        if (session.request.route == AudioRoutePreference.DEVICE_SDK) {
            Log.i("ImageQuestionAudio", "Question route: glasses SDK microphone, no Bluetooth call route")
            return AudioRouteResult.PHONE
        }
        val glassesName = runCatching { DeviceManager.getInstance().deviceName }.getOrNull()
        val route = session.connectRoute(ROUTE_WAIT, preferredDeviceName = glassesName, requireUnambiguous = true)
        Log.i(
            "ImageQuestionAudio",
            "Question route ready=${route.bluetoothActive} ambiguous=${route.ambiguous} " +
                "candidates=${route.bluetoothCandidatesPresent} input=${route.inputDevice?.productName}",
        )
        check(!route.ambiguous && !(route.bluetoothCandidatesPresent && !route.bluetoothActive)) {
            "Could not connect the glasses microphone"
        }
        return route
    }

    /** Everything after capture: load cue, speaker observation, on-device transcript or the raw audio. */
    private suspend fun processRecordedQuestion(recorded: File, voiceOnly: Boolean, transcribe: Boolean? = null): CapturedQuestion {
        onQuestionRecorded()
        observeQuestionSpeaker(recorded, flow = if (voiceOnly) "voice" else "image")
        // Audio is the default: the model hears the recording directly (faster, works in every
        // language). On-device STT is the fallback for text-only models and non-local routes.
        val useStt = transcribe ?: withContext(Dispatchers.IO) { !audioGoesToModel() }
        if (!useStt) return CapturedQuestion(null, true, recorded.absolutePath)
        return try {
            val text = OfflineQuestionTranscriber(appContext).transcribe(recorded, languageTag())
            if (text != null) {
                recorded.delete()
                Log.i("ImageQuestionAudio", "On-device transcript ready chars=${text.length}")
                CapturedQuestion(text, true)
            } else {
                Log.i("ImageQuestionAudio", "On-device STT failed/unavailable; handing original audio to model")
                CapturedQuestion(null, true, recorded.absolutePath)
            }
        } catch (cancelled: CancellationException) {
            recorded.delete()
            throw cancelled
        }
    }

    /**
     * Speaker-verification foundation: with an enrolled voice profile and a non-classic routing
     * mode, score the question against the wearer's template and log the numbers. Never gates.
     */
    private suspend fun observeQuestionSpeaker(wav: File, flow: String) {
        if (QuestionRoutingPrefs.mode(appContext) == QuestionRoutingMode.CLASSIC) return
        val pcm = withContext(Dispatchers.IO) {
            if (!VoiceProfile.isEnrolled(appContext)) return@withContext null
            runCatching { readWavPcm16(wav) }.getOrNull()
        } ?: return
        scope().launch(Dispatchers.Default) { VoiceProfile.observe(appContext, pcm, flow) }
    }

    /** Reads AudioQuestionRecorder's canonical 44-byte-header PCM16 WAV. */
    private fun readWavPcm16(wav: File): ShortArray {
        val bytes = wav.readBytes()
        require(bytes.size >= 44) { "Truncated WAV" }
        val buffer = java.nio.ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(buffer.remaining()).also { buffer.get(it) }
    }

    private suspend fun playTone(toneType: Int) {
        val tone = android.media.ToneGenerator(AudioManager.STREAM_VOICE_CALL, 90)
        try {
            val played = tone.startTone(toneType, 240)
            Log.i("ImageQuestionAudio", "Image-question tone type=$toneType played=$played")
            delay(300L)
        } finally {
            tone.release()
        }
    }

    private suspend fun speakCue(voiceOnly: Boolean = false) {
        suspendCancellableCoroutine { cont ->
            val completed = AtomicBoolean(false)
            val utteranceId = "image_question_cue_${System.nanoTime()}"
            fun complete(reason: String) {
                if (completed.compareAndSet(false, true)) {
                    speaker.forgetUtterance(utteranceId)
                    Log.i("ImageQuestionAudio", "Image-question cue complete reason=$reason id=$utteranceId")
                    // Keep only enough separation to avoid clipping the route transition. The
                    // reclaimed tail time is added to the initial listening window.
                    scope().launch {
                        delay(CUE_BLUETOOTH_TAIL_MS)
                        if (cont.isActive) {
                            cont.resume(Unit)
                        }
                    }
                }
            }

            val questionSettings = ImageQuestionPreferences.get(appContext)
            val cue = if (voiceOnly) ImageQuestionDefaults.listeningCueForLanguage(questionSettings.appLanguageTag)
                else ImageQuestionDefaults.questionCueForLanguage(questionSettings.appLanguageTag)
            speaker.configureForVoiceCommunication("image-question cue")
            Log.i(
                "ImageQuestionAudio",
                "Queueing image-question cue='$cue' language=${questionSettings.appLanguageTag} " +
                    "attributes=voice_communication/speech ttsReady=${speaker.ready}",
            )
            speaker.speak(
                text = cue,
                languageTag = questionSettings.appLanguageTag,
                utteranceId = utteranceId,
                onDone = { complete("tts callback") },
                streamType = AudioManager.STREAM_VOICE_CALL,
            )
            // Avoid blocking the image question if the system TTS service never returns a callback.
            scope().launch {
                delay(2_000L)
                complete("2s fallback")
            }
            cont.invokeOnCancellation {
                speaker.forgetUtterance(utteranceId)
            }
        }
    }

    private companion object {
        const val SESSION_OWNER = "AudioQuestionRecorder"
        const val MICROPHONE_BUSY = "The microphone is busy; please ask again"

        /**
         * The question's own timing: up to 3 s for the glasses' communication device (or a legacy SCO
         * link), then 500 ms so the beep and cue are not clipped.
         */
        val ROUTE_WAIT = AudioRouteWait(communicationDeviceTimeoutMs = 3_000, legacyScoTimeoutMs = 3_000, settleMs = 500)
        const val CUE_BLUETOOTH_TAIL_MS = 50L
    }
}
