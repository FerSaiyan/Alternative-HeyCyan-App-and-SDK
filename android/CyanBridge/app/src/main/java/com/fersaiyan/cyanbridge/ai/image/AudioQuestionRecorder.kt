package com.fersaiyan.cyanbridge.ai.image

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.fersaiyan.cyanbridge.media.autocapture.SpeechActivityDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/** Owns only PCM capture. The caller owns Bluetooth selection and cue playback. */
class AudioQuestionRecorder(private val context: Context) {
    suspend fun record(onsetTimeoutMs: Long, preferredInput: AudioDeviceInfo? = null): File? =
        withContext(Dispatchers.IO) {
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minBuffer > 0) { "Microphone input is unavailable" }
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer * 2, 2560),
            )
            val file = File.createTempFile("question-", ".wav", File(context.cacheDir, "audio-questions").apply { mkdirs() })
            var completed = false
            try {
                check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not initialize" }
                if (preferredInput != null) check(recorder.setPreferredDevice(preferredInput)) { "Could not select glasses microphone" }
                recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone could not start" }
                Log.i(TAG, "Capture started input=${recorder.routedDevice?.productName} id=${recorder.routedDevice?.id}")
                val endpoint = QuestionEndpoint(onsetTimeoutMs = onsetTimeoutMs)
                val start = SystemClock.elapsedRealtime()
                val samples = ShortArray(320)
                RandomAccessFile(file, "rw").use { output ->
                    output.write(ByteArray(44))
                    var dataBytes = 0
                    while (true) {
                        coroutineContext.ensureActive()
                        // Nonblocking reads let cancellation interrupt even a stalled audio driver.
                        val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
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
                            voiced = SpeechActivityDetector.rms(samples, 0, count) >= 250.0,
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
                Log.i(TAG, "Capture complete bytes=${file.length()} input=${recorder.routedDevice?.productName}")
                file
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
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
