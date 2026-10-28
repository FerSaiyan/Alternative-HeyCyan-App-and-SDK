package com.fersaiyan.cyanbridge.ai.questions

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.fersaiyan.cyanbridge.ai.AiQuestionForegroundService
import com.fersaiyan.cyanbridge.ai.MicrophoneAccess
import com.fersaiyan.cyanbridge.ai.image.AudioQuestionRecorder
import com.fersaiyan.cyanbridge.ai.image.MicrophoneSilencedException
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
import com.fersaiyan.cyanbridge.audio.VendorPcmMicrophone
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
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * profile records questions from an SDK stream ([AudioRoutePolicy.forQuestion]: Mentra Live PCM,
 * MemoMind's Opus recording link) are recorded from that stream on the DEVICE_SDK route, so no
 * Bluetooth call route is opened next to it and Android's background microphone rules do not
 * apply. The stream starts before the beep so its start-up overlaps the beep and cue; when it
 * cannot start and the profile has another route (MemoMind: HFP), the question is asked there.
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
    /** The selected glasses' SDK microphone when it can stream now (see [VendorPcmMicrophone.forSelectedGlasses]). */
    private val sdkMicrophone: () -> VendorPcmMicrophone? = { VendorPcmMicrophone.forSelectedGlasses(context) },
) : SpokenQuestionMicrophone {
    private val appContext = context.applicationContext
    private val mutex = Mutex()

    /** Starts SDK streams; outlives a question so an abandoned start can finish on its own. */
    private val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The SDK stream could not start, and the profile has another route for questions. */
    private class SdkStreamUnavailableException(cause: Throwable) : Exception(cause.message, cause)

    private class MicrophoneBusyException : Exception(MICROPHONE_BUSY)

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
        val sdk = sdkMicrophone()?.takeIf { it.sampleRateHz == AudioQuestionRecorder.SAMPLE_RATE }
        val recorded = try {
            try {
                record(timeoutMs, voiceOnly, sdk)
            } catch (unavailable: SdkStreamUnavailableException) {
                // Link down, firmware without the stream, or no start: the headset route asks again.
                Log.w("ImageQuestionAudio", "Glasses SDK stream unavailable (${unavailable.message}); using the headset route")
                record(timeoutMs, voiceOnly, sdk = null)
            }
        } catch (busy: MicrophoneBusyException) {
            return@withLock CapturedQuestion(null, false, error = MICROPHONE_BUSY)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("ImageQuestionAudio", "Question capture failed", error)
            return@withLock CapturedQuestion(null, false, error = error.message ?: "Microphone capture failed")
        } ?: return@withLock CapturedQuestion(null, false)
        processRecordedQuestion(recorded, voiceOnly, transcribe)
    }

    /**
     * One attempt on the route [AudioRoutePolicy.forQuestion] picks for [sdk]: session, route,
     * beep, cue, recording, closing beep. Returns null when nobody spoke.
     */
    private suspend fun record(timeoutMs: Long, voiceOnly: Boolean, sdk: VendorPcmMicrophone?): File? {
        val route = AudioRoutePolicy.forQuestion(deviceAudio(), sdkStreamAvailable = sdk != null)
        val session = openSession(route) ?: throw MicrophoneBusyException()
        val stream = sdk?.takeIf { route == AudioRoutePreference.DEVICE_SDK }?.let(AudioQuestionRecorder::SdkMicrophoneStream)
        val begin = SystemClock.elapsedRealtime()
        // Started before the beep: MemoMind's link start (01/2F, RFCOMM, 52 01 00) overlaps beep and cue.
        val streaming = stream?.let { streamScope.async { it.open() } }
        var audioFile: File? = null
        try {
            // Closing the session (also on cancellation) closes the recorder and leaves the shared
            // route; the route is restored only if no other session (a meeting, Live) still uses it.
            session.use {
                val joined = withContext(Dispatchers.Main) {
                    val joined = connectRoute(session)
                    playTone(android.media.ToneGenerator.TONE_PROP_BEEP)
                    if (streaming != null) Log.i("ImageQuestionAudio", "Question beep done at +${SystemClock.elapsedRealtime() - begin} ms")
                    speakCue(voiceOnly)
                    joined
                }
                if (streaming != null) awaitStream(streaming, stream, begin)
                // A phone-microphone recording needs the question's foreground service while no
                // screen of the app is visible; it is normally up by now. SDK streams do not.
                val foreground = if (stream == null) AiQuestionForegroundService.awaitMicrophoneAccess() else null
                if (foreground != null) Log.i("ImageQuestionAudio", "Question foreground microphone=$foreground")
                audioFile = try {
                    AudioQuestionRecorder(appContext).record(session, timeoutMs, joined.inputDevice, stream)
                } catch (silenced: MicrophoneSilencedException) {
                    // Only an Android input (the HFP fallback for MemoMind) can be silenced.
                    Log.w("ImageQuestionAudio", "Question recording silenced by Android foreground=$foreground")
                    // Without a question service (an image follow-up after the answer) it stays "not heard".
                    if (foreground == MicrophoneAccess.NOT_STARTED) null else error(BACKGROUND_MICROPHONE_BLOCKED)
                }
                withContext(Dispatchers.Main) { playTone(android.media.ToneGenerator.TONE_PROP_BEEP2) }
            }
        } catch (cancelled: CancellationException) {
            streaming?.cancel()
            audioFile?.delete()
            throw cancelled
        } catch (error: Exception) {
            audioFile?.delete()
            throw error
        } finally {
            stream?.close()
        }
        return audioFile
    }

    /**
     * Waits for the SDK stream started before the beep, up to [SDK_STREAM_START_TIMEOUT_MS] from
     * [begin]. A failed or late start becomes [SdkStreamUnavailableException] when the profile has
     * another question route; a late start is left to finish (and stop) on its own.
     */
    private suspend fun awaitStream(streaming: Deferred<Unit>, stream: AudioQuestionRecorder.SdkMicrophoneStream, begin: Long) {
        val remaining = (SDK_STREAM_START_TIMEOUT_MS - (SystemClock.elapsedRealtime() - begin)).coerceAtLeast(1)
        val failure: Exception? = try {
            val started = withTimeoutOrNull(remaining) { streaming.await() } != null
            if (started) null else IllegalStateException("The glasses stream did not start within ${SDK_STREAM_START_TIMEOUT_MS / 1000} s")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            error
        }
        val elapsed = SystemClock.elapsedRealtime() - begin
        if (failure == null) {
            Log.i("ImageQuestionAudio", "Question SDK stream ready, recording from +$elapsed ms")
            return
        }
        stream.close()
        Log.w("ImageQuestionAudio", "Question SDK stream failed at +$elapsed ms: ${failure.message}")
        val fallback = AudioRoutePolicy.forQuestion(deviceAudio(), sdkStreamAvailable = false)
        if (fallback == AudioRoutePreference.DEVICE_SDK) throw failure
        throw SdkStreamUnavailableException(failure)
    }

    /** The question's microphone session, or null when another spoken prompt holds the microphone. */
    private fun openSession(route: AudioRoutePreference): AudioSessionHandle? {
        val request = AudioSessionRequest.VoiceCapture(SESSION_OWNER, route = route)
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
        const val BACKGROUND_MICROPHONE_BLOCKED =
            "Android blocked the microphone while CyanBridge was in the background. Open CyanBridge and ask again."

        /**
         * The question's own timing: up to 3 s for the glasses' communication device (or a legacy SCO
         * link), then 500 ms so the beep and cue are not clipped.
         */
        val ROUTE_WAIT = AudioRouteWait(communicationDeviceTimeoutMs = 3_000, legacyScoTimeoutMs = 3_000, settleMs = 500)
        const val CUE_BLUETOOTH_TAIL_MS = 50L

        /** From the stream start (before the beep) to recording; MemoMind's start gives up after ~4 s. */
        const val SDK_STREAM_START_TIMEOUT_MS = 5_000L
    }
}
