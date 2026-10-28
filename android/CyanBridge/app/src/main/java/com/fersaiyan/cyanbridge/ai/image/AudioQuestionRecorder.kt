package com.fersaiyan.cyanbridge.ai.image

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.fersaiyan.cyanbridge.audio.AudioSessionHandle
import com.fersaiyan.cyanbridge.audio.PcmRecorder
import com.fersaiyan.cyanbridge.audio.VendorPcmMicrophone
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionRequest
import com.fersaiyan.cyanbridge.shared.audio.PcmEnergy
import com.fersaiyan.cyanbridge.shared.audio.QuestionEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** No speech started, and Android was feeding the recorder silence instead of the microphone. */
class MicrophoneSilencedException : IllegalStateException("The microphone was silenced by Android")

/**
 * Owns only PCM capture. The caller owns the [AudioSessionRequest.VoiceCapture] session (so Gemini
 * Live and voice plugins pause their microphones while the question is recorded and an ambient
 * probe stops), the Bluetooth route and cue playback.
 */
class AudioQuestionRecorder(private val context: Context) {
    /**
     * Records into a session the caller owns (and closes); its route is left as is. With
     * [sdkStream] (Mentra Live PCM, MemoMind's decoded Opus link) the glasses' SDK stream is
     * recorded instead of an Android input, so no Bluetooth headset route and no phone microphone
     * access are needed; it is closed with the recording.
     */
    suspend fun record(
        session: AudioSessionHandle,
        onsetTimeoutMs: Long,
        preferredInput: AudioDeviceInfo?,
        sdkStream: SdkMicrophoneStream? = null,
    ): File? =
        withContext(Dispatchers.IO) {
            val file = File.createTempFile("question-", ".wav", File(context.cacheDir, "audio-questions").apply { mkdirs() })
            var completed = false
            var source: SampleSource? = null
            try {
                val active = if (sdkStream != null) SdkSource(sdkStream) else recorderSource(session, preferredInput)
                source = active
                Log.i(TAG, "Capture started input=${active.label}")
                val endpoint = QuestionEndpoint(onsetTimeoutMs = onsetTimeoutMs)
                val start = SystemClock.elapsedRealtime()
                val samples = ShortArray(320)
                RandomAccessFile(file, "rw").use { output ->
                    output.write(ByteArray(44))
                    var dataBytes = 0
                    while (true) {
                        coroutineContext.ensureActive()
                        // Nonblocking reads let cancellation interrupt even a stalled audio driver.
                        val count = active.read(samples)
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
                            QuestionEndpoint.Decision.NO_SPEECH -> {
                                // Android fed silence instead of the microphone: nobody could be heard.
                                if (active.silenced) throw MicrophoneSilencedException()
                                return@withContext null
                            }
                            QuestionEndpoint.Decision.LIMIT_REACHED -> error("Question exceeded the two-minute recording limit; please ask again")
                            QuestionEndpoint.Decision.COMPLETE -> break
                        }
                    }
                    output.seek(0)
                    output.write(wavHeader(dataBytes))
                }
                coroutineContext.ensureActive()
                completed = true
                Log.i(TAG, "Capture complete bytes=${file.length()} input=${active.label}")
                file
            } finally {
                source?.close(session)
                if (!completed) file.delete()
            }
        }

    private fun recorderSource(session: AudioSessionHandle, preferredInput: AudioDeviceInfo?): SampleSource {
        val recorder = try {
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
        if (!recorder.start()) {
            session.closeRecorder(recorder)
            error("Microphone could not start")
        }
        return RecorderSource(recorder)
    }

    /** 16 kHz mono PCM16 samples, read without blocking. */
    private interface SampleSource {
        val label: String

        /** Android is feeding silence instead of the microphone (see [PcmRecorder.isSilenced]). */
        val silenced: Boolean get() = false

        /** Fills [samples] with what is available; 0 when nothing is yet. */
        fun read(samples: ShortArray): Int

        fun close(session: AudioSessionHandle)
    }

    private class RecorderSource(private val recorder: PcmRecorder) : SampleSource {
        override val label: String get() = "${recorder.routedDevice?.productName} id=${recorder.routedDevice?.id}"

        override val silenced: Boolean get() = recorder.isSilenced

        override fun read(samples: ShortArray): Int = recorder.read(samples, 0, samples.size, blocking = false)

        override fun close(session: AudioSessionHandle) = session.closeRecorder(recorder)
    }

    /** Hands out the PCM [stream] keeps from now on. */
    private class SdkSource(private val stream: SdkMicrophoneStream) : SampleSource {
        private var pending: ByteBuffer? = null

        init {
            stream.listen()
        }

        override val label: String get() = "glasses SDK PCM"

        override fun read(samples: ShortArray): Int {
            var count = 0
            while (count < samples.size) {
                val buffer = pending?.takeIf { it.remaining() >= 2 }
                    ?: stream.poll()?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) }?.also { pending = it }
                    ?: break
                while (count < samples.size && buffer.remaining() >= 2) samples[count++] = buffer.short
            }
            return count
        }

        override fun close(session: AudioSessionHandle) = stream.close()
    }

    /**
     * A glasses SDK microphone opened ahead of the recording (at the beep), so a slow start
     * (MemoMind's recording link: 01/2F, RFCOMM connect, `52 01 00`) overlaps the beep and cue.
     * PCM is kept only from [listen], when the recording starts, so the cue is not recorded.
     */
    class SdkMicrophoneStream(private val microphone: VendorPcmMicrophone) : Closeable {
        private val chunks = LinkedBlockingQueue<ByteArray>()
        private val closed = AtomicBoolean(false)

        @Volatile private var keeping = false

        /** Starts the glasses stream; returns once it runs, throws when it cannot. */
        suspend fun open() = microphone.open(TAG) { pcm -> if (keeping && chunks.size < MAX_QUEUED_CHUNKS) chunks.offer(pcm) }

        internal fun listen() {
            chunks.clear()
            keeping = true
        }

        internal fun poll(): ByteArray? = chunks.poll()

        override fun close() {
            if (closed.compareAndSet(false, true)) microphone.close(TAG)
        }

        private companion object {
            /** About 20 s of 20 ms packets; older audio is dropped rather than growing without bound. */
            const val MAX_QUEUED_CHUNKS = 1_000
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
