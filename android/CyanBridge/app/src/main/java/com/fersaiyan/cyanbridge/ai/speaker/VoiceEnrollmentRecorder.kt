package com.fersaiyan.cyanbridge.ai.speaker

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.fersaiyan.cyanbridge.ai.image.QuestionEndpoint
import com.fersaiyan.cyanbridge.media.autocapture.SpeechActivityDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class RecordedPhrase(val pcm: ShortArray, val inputRoute: String)

/**
 * Records one enrollment phrase into memory. Uses the same source, rate and voiced threshold as
 * AudioQuestionRecorder, so enrollment audio goes through the same capture chain as questions.
 */
class VoiceEnrollmentRecorder {
    /**
     * Returns null when no speech starts within [onsetTimeoutMs]. A phrase ends after
     * [silenceMs] of silence or at [maxMs]. [onLevel] receives a 0..1 loudness for the UI.
     */
    @SuppressLint("MissingPermission") // The enrollment screen requests RECORD_AUDIO first.
    suspend fun record(
        onsetTimeoutMs: Long = 6_000,
        silenceMs: Long = 1_000,
        maxMs: Long = 8_000,
        preferredInput: AudioDeviceInfo? = null,
        onLevel: (Float) -> Unit = {},
    ): RecordedPhrase? = withContext(Dispatchers.IO) {
        val rate = SpeakerMath.SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuffer > 0) { "Microphone input is unavailable" }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer * 2, 2560),
        )
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not initialize" }
            if (preferredInput != null) recorder.setPreferredDevice(preferredInput)
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone could not start" }
            val endpoint = QuestionEndpoint(onsetTimeoutMs = onsetTimeoutMs, silenceMs = silenceMs, resourceLimitMs = maxMs)
            val out = ShortArray((rate * maxMs / 1000).toInt() + SpeakerMath.FRAME_SAMPLES)
            var written = 0
            val frame = ShortArray(SpeakerMath.FRAME_SAMPLES)
            val start = SystemClock.elapsedRealtime()
            loop@ while (true) {
                coroutineContext.ensureActive()
                val count = recorder.read(frame, 0, frame.size, AudioRecord.READ_NON_BLOCKING)
                check(count >= 0) { "Microphone read failed ($count)" }
                val elapsed = SystemClock.elapsedRealtime() - start
                if (count == 0) {
                    check(elapsed < maxMs + 2_000) { "Microphone capture stalled" }
                    delay(10)
                    continue
                }
                val n = count.coerceAtMost(out.size - written)
                frame.copyInto(out, written, 0, n)
                written += n
                val rms = SpeechActivityDetector.rms(frame, 0, count)
                onLevel((rms / 4_000.0).toFloat().coerceIn(0f, 1f))
                when (endpoint.advance(rms >= SpeakerMath.VOICED_RMS, (count * 1000L / rate).coerceAtLeast(1), elapsed)) {
                    QuestionEndpoint.Decision.CONTINUE -> if (written >= out.size) break@loop
                    QuestionEndpoint.Decision.NO_SPEECH -> return@withContext null
                    // For an enrollment phrase the limit just ends the take; quality checks follow.
                    QuestionEndpoint.Decision.LIMIT_REACHED,
                    QuestionEndpoint.Decision.COMPLETE -> break@loop
                }
            }
            RecordedPhrase(out.copyOf(written), routeName(recorder.routedDevice))
        } finally {
            onLevel(0f)
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    private fun routeName(device: AudioDeviceInfo?): String = when (device?.type) {
        null -> "unknown"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "phone_mic"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble_headset"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> "wired_headset"
        else -> "type_${device.type}"
    }
}
