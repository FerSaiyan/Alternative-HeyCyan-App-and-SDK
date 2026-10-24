package com.fersaiyan.cyanbridge.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import java.io.Closeable

/**
 * Mono PCM16 [AudioRecord] lifecycle: buffer sizing, initialization checks, preferred input,
 * start, reads, and an idempotent stop-and-release. Callers keep their own sample rates, sources
 * and read sizes; failures are reported as [Failure] so each feature keeps its own wording.
 */
class PcmRecorder private constructor(
    private val record: AudioRecord,
    val sampleRateHz: Int,
    val bufferSizeBytes: Int,
    /** The platform minimum buffer, for callers that size their reads from it. */
    val minBufferBytes: Int,
) : Closeable {

    enum class Failure { NO_BUFFER, NOT_INITIALIZED, PREFERRED_DEVICE_REJECTED, NOT_STARTED }

    class OpenException(val failure: Failure, val detail: String) : IllegalStateException(detail)

    @Volatile
    private var closed = false

    val routedDevice: AudioDeviceInfo? get() = runCatching { record.routedDevice }.getOrNull()

    val isRecording: Boolean
        get() = !closed && runCatching { record.recordingState == AudioRecord.RECORDSTATE_RECORDING }.getOrDefault(false)

    /** Starts capture; returns false (and reports [Failure.NOT_STARTED] via [startOrThrow]) if it did not. */
    fun start(): Boolean {
        if (closed) return false
        runCatching { record.startRecording() }
        return isRecording
    }

    fun startOrThrow() {
        if (!start()) throw OpenException(Failure.NOT_STARTED, "recordingState=${runCatching { record.recordingState }.getOrNull()}")
    }

    fun read(buffer: ByteArray, offset: Int = 0, size: Int = buffer.size - offset, blocking: Boolean = true): Int =
        record.read(buffer, offset, size, if (blocking) AudioRecord.READ_BLOCKING else AudioRecord.READ_NON_BLOCKING)

    fun read(buffer: ShortArray, offset: Int = 0, size: Int = buffer.size - offset, blocking: Boolean = true): Int =
        record.read(buffer, offset, size, if (blocking) AudioRecord.READ_BLOCKING else AudioRecord.READ_NON_BLOCKING)

    /** Stops capture but keeps the recorder allocated. */
    fun stop() {
        if (!closed) runCatching { record.stop() }
    }

    /** Stops and releases the recorder. Safe to call more than once and from any thread. */
    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        runCatching { record.stop() }
        runCatching { record.release() }
    }

    companion object {
        fun minBufferSize(sampleRateHz: Int): Int =
            AudioRecord.getMinBufferSize(sampleRateHz, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

        /**
         * Creates an initialized recorder. [bufferBytes] maps the platform minimum buffer to the
         * buffer the caller wants. Throws [OpenException]; RECORD_AUDIO must already be granted.
         */
        @SuppressLint("MissingPermission")
        fun open(
            source: Int,
            sampleRateHz: Int,
            bufferBytes: (minBuffer: Int) -> Int,
            preferredDevice: AudioDeviceInfo? = null,
            requirePreferredDevice: Boolean = false,
        ): PcmRecorder {
            val minBuffer = minBufferSize(sampleRateHz)
            if (minBuffer <= 0) throw OpenException(Failure.NO_BUFFER, "AudioRecord.getMinBufferSize returned $minBuffer")
            val size = bufferBytes(minBuffer)
            val record = AudioRecord(source, sampleRateHz, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            val recorder = PcmRecorder(record, sampleRateHz, size, minBuffer)
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                recorder.close()
                throw OpenException(Failure.NOT_INITIALIZED, "AudioRecord not initialized (state=${record.state})")
            }
            if (preferredDevice != null) {
                val selected = runCatching { record.setPreferredDevice(preferredDevice) }.getOrDefault(false)
                if (!selected && requirePreferredDevice) {
                    recorder.close()
                    throw OpenException(Failure.PREFERRED_DEVICE_REJECTED, "Preferred input ${preferredDevice.productName} rejected")
                }
            }
            return recorder
        }
    }
}
