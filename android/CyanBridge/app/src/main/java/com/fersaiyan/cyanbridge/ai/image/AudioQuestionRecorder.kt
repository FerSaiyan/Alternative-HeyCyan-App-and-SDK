package com.fersaiyan.cyanbridge.ai.image

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.fersaiyan.cyanbridge.audio.AudioSessionCoordinator
import com.fersaiyan.cyanbridge.audio.AudioSessionGrant
import com.fersaiyan.cyanbridge.audio.AudioSessionHandle
import com.fersaiyan.cyanbridge.audio.PcmRecorder
import com.fersaiyan.cyanbridge.shared.audio.AudioRoutePreference
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionRequest
import com.fersaiyan.cyanbridge.shared.audio.PcmEnergy
import com.fersaiyan.cyanbridge.shared.audio.QuestionEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Owns only PCM capture. The caller owns Bluetooth selection and cue playback.
 *
 * Capture runs as a [AudioSessionRequest.VoiceCapture] session, so Gemini Live and voice plugins
 * pause their microphones while the question is recorded and an ambient probe stops.
 */
class AudioQuestionRecorder(private val context: Context) {
    suspend fun record(onsetTimeoutMs: Long, preferredInput: AudioDeviceInfo? = null): File? {
        check(PcmRecorder.minBufferSize(SAMPLE_RATE) > 0) { "Microphone input is unavailable" }
        val session = when (
            val grant = AudioSessionCoordinator.open(
                context,
                AudioSessionRequest.VoiceCapture(TAG, route = AudioRoutePreference.SYSTEM_DEFAULT),
            )
        ) {
            is AudioSessionGrant.Granted -> grant.handle
            is AudioSessionGrant.Denied -> error("The microphone is busy; please ask again")
        }
        return session.use { record(it, onsetTimeoutMs, preferredInput) }
    }

    /** Records into a session the caller already owns (and closes); its route is left as is. */
    suspend fun record(session: AudioSessionHandle, onsetTimeoutMs: Long, preferredInput: AudioDeviceInfo?): File? =
        withContext(Dispatchers.IO) {
            val file = File.createTempFile("question-", ".wav", File(context.cacheDir, "audio-questions").apply { mkdirs() })
            var completed = false
            var recorder: PcmRecorder? = null
            try {
                val active = try {
                    session.openRecorder(
                        source = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        sampleRateHz = SAMPLE_RATE,
                        bufferBytes = { minBuffer -> maxOf(minBuffer * 2, 2560) },
                        preferredDevice = preferredInput,
                        requirePreferredDevice = true,
                    )
                } catch (failure: PcmRecorder.OpenException) {
                    error(
                        when (failure.failure) {
                            PcmRecorder.Failure.NO_BUFFER -> "Microphone input is unavailable"
                            PcmRecorder.Failure.PREFERRED_DEVICE_REJECTED -> "Could not select glasses microphone"
                            else -> "Microphone could not initialize"
                        },
                    )
                }
                recorder = active
                check(active.start()) { "Microphone could not start" }
                Log.i(TAG, "Capture started input=${active.routedDevice?.productName} id=${active.routedDevice?.id}")
                val endpoint = QuestionEndpoint(onsetTimeoutMs = onsetTimeoutMs)
                val start = SystemClock.elapsedRealtime()
                val samples = ShortArray(320)
                RandomAccessFile(file, "rw").use { output ->
                    output.write(ByteArray(44))
                    var dataBytes = 0
                    while (true) {
                        coroutineContext.ensureActive()
                        // Nonblocking reads let cancellation interrupt even a stalled audio driver.
                        val count = active.read(samples, 0, samples.size, blocking = false)
                        check(count >= 0) { "Microphone read failed ($count)" }
                        val elapsed = SystemClock.elapsedRealtime() - start
                        if (count == 0) {
                            check(elapsed < 120_000) { "Microphone capture stalled" }
                            kotlinx.coroutines.delay(10)
                            continue
                        }
                        val pcm = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
                        repeat(count) { pcm.putShort(samples[it]) }
                        output.write(pcm.array())
                        dataBytes += count * 2
                        val decision = endpoint.advance(
                            voiced = PcmEnergy.rms(samples, 0, count) >= QuestionEndpoint.VOICED_RMS_THRESHOLD,
                            frameMs = (count * 1000L / SAMPLE_RATE).coerceAtLeast(1), elapsedMs = elapsed,
                        )
                        when (decision) {
                            QuestionEndpoint.Decision.CONTINUE -> Unit
                            QuestionEndpoint.Decision.NO_SPEECH -> return@withContext null
                            QuestionEndpoint.Decision.LIMIT_REACHED -> error("Question exceeded the two-minute recording limit; please ask again")
                            QuestionEndpoint.Decision.COMPLETE -> break
                        }
                    }
                    output.seek(0)
                    output.write(wavHeader(dataBytes))
                }
                coroutineContext.ensureActive()
                completed = true
                Log.i(TAG, "Capture complete bytes=${file.length()} input=${active.routedDevice?.productName}")
                file
            } finally {
                recorder?.let(session::closeRecorder)
                if (!completed) file.delete()
            }
        }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val TAG = "AudioQuestionRecorder"
        internal fun wavHeader(dataBytes: Int): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(dataBytes)
        }.array()
    }
}
