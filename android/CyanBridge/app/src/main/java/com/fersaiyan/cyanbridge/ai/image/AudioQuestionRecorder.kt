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
import com.fersaiyan.cyanbridge.audio.VendorPcmMicrophone
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
import java.util.concurrent.LinkedBlockingQueue
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

    /**
     * Records into a session the caller already owns (and closes); its route is left as is.
     * Glasses whose SDK streams the microphone as PCM (Mentra Live) are recorded from that
     * stream instead of an Android input, so no Bluetooth headset route is needed.
     */
    suspend fun record(session: AudioSessionHandle, onsetTimeoutMs: Long, preferredInput: AudioDeviceInfo?): File? =
        withContext(Dispatchers.IO) {
            val file = File.createTempFile("question-", ".wav", File(context.cacheDir, "audio-questions").apply { mkdirs() })
            var completed = false
            var source: SampleSource? = null
            try {
                val vendor = VendorPcmMicrophone.forSelectedGlasses(context)?.takeIf { it.sampleRateHz == SAMPLE_RATE }
                val active = if (vendor != null) VendorSource(vendor) else recorderSource(session, preferredInput)
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

        /** Fills [samples] with what is available; 0 when nothing is yet. */
        fun read(samples: ShortArray): Int

        fun close(session: AudioSessionHandle)
    }

    private class RecorderSource(private val recorder: PcmRecorder) : SampleSource {
        override val label: String get() = "${recorder.routedDevice?.productName} id=${recorder.routedDevice?.id}"

        override fun read(samples: ShortArray): Int = recorder.read(samples, 0, samples.size, blocking = false)

        override fun close(session: AudioSessionHandle) = session.closeRecorder(recorder)
    }

    /** Buffers the PCM the glasses SDK pushes and hands it out in reads. */
    private class VendorSource(private val microphone: VendorPcmMicrophone) : SampleSource {
        private val chunks = LinkedBlockingQueue<ByteArray>()
        private var pending: ByteBuffer? = null

        init {
            microphone.open(TAG) { pcm -> if (chunks.size < MAX_QUEUED_CHUNKS) chunks.offer(pcm) }
        }

        override val label: String get() = "glasses SDK PCM"

        override fun read(samples: ShortArray): Int {
            var count = 0
            while (count < samples.size) {
                val buffer = pending?.takeIf { it.remaining() >= 2 }
                    ?: chunks.poll()?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) }?.also { pending = it }
                    ?: break
                while (count < samples.size && buffer.remaining() >= 2) samples[count++] = buffer.short
            }
            return count
        }

        override fun close(session: AudioSessionHandle) = microphone.close(TAG)

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
