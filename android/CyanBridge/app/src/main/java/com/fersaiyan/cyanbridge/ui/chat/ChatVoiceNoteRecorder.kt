package com.fersaiyan.cyanbridge.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.audio.AudioSessionCoordinator
import com.fersaiyan.cyanbridge.audio.AudioSessionHandle
import com.fersaiyan.cyanbridge.audio.PcmRecorder
import com.fersaiyan.cyanbridge.shared.audio.AudioRoutePreference
import com.fersaiyan.cyanbridge.shared.audio.AudioSessionRequest
import com.fersaiyan.cyanbridge.shared.ui.chat.ChatVoiceRecorder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Records a 16 kHz mono WAV voice note for the next chat message through the app's audio session. */
internal class ChatVoiceNoteRecorder(private val context: Context) : ChatVoiceRecorder {
    private var audioRecorder: PcmRecorder? = null
    private var audioSession: AudioSessionHandle? = null
    private var worker: Thread? = null
    private var filePath: String? = null

    @Volatile
    private var recording = false

    override fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override suspend fun start(): Boolean {
        val outFile = File(chatAttachmentDir(context), "voice_${System.currentTimeMillis()}.wav")
        filePath = outFile.absolutePath
        return try {
            // A voice note is a one-shot capture: Live and voice plugins pause while it records.
            val session = AudioSessionCoordinator.tryOpen(
                context,
                AudioSessionRequest.VoiceCapture("ChatThreadActivity", route = AudioRoutePreference.SYSTEM_DEFAULT),
            ) ?: throw IllegalStateException("Microphone is in use")
            audioSession = session
            val recorder = session.openRecorder(
                source = MediaRecorder.AudioSource.MIC,
                sampleRateHz = SAMPLE_RATE_HZ,
                bufferBytes = { it.coerceAtLeast(SAMPLE_RATE_HZ) },
            )
            audioRecorder = recorder
            recording = true
            worker = Thread { capture(recorder, outFile) }.apply {
                name = "liteRt-audio-recorder"
                isDaemon = true
                start()
            }
            true
        } catch (_: Throwable) {
            audioRecorder = null
            audioSession?.close()
            audioSession = null
            worker = null
            recording = false
            filePath = null
            false
        }
    }

    private fun capture(recorder: PcmRecorder, outFile: File) {
        var pcmBytesWritten = 0L
        val ioBuffer = ByteArray(recorder.minBufferBytes.coerceAtLeast(2048))
        runCatching {
            FileOutputStream(outFile).use { output ->
                output.write(ByteArray(WAV_HEADER_BYTES))
                recorder.start()
                while (recording) {
                    val read = recorder.read(ioBuffer, 0, ioBuffer.size)
                    if (read > 0) {
                        output.write(ioBuffer, 0, read)
                        pcmBytesWritten += read.toLong()
                    } else if (read == AudioRecord.ERROR_BAD_VALUE || read == AudioRecord.ERROR_INVALID_OPERATION) {
                        throw IllegalStateException("Audio read failed: $read")
                    }
                }
            }
        }.onFailure {
            runCatching { outFile.delete() }
        }
        recorder.close()
        if (outFile.exists() && pcmBytesWritten > 0L) {
            runCatching { writeWavHeader(outFile, pcmBytesWritten) }
                .onFailure { runCatching { outFile.delete() } }
        }
    }

    override suspend fun stop(keep: Boolean): String? {
        val recorder = audioRecorder
        audioRecorder = null
        val thread = worker
        worker = null
        val path = filePath
        filePath = null
        recording = false
        if (thread != null && thread.isAlive) runCatching { thread.join(1200L) }
        recorder?.close()
        audioSession?.close()
        audioSession = null

        val audioFile = path?.let(::File) ?: return null
        if (keep && audioFile.exists() && audioFile.length() > WAV_HEADER_BYTES) return path
        runCatching { audioFile.delete() }
        return null
    }

    override fun release() {
        recording = false
        audioRecorder?.close()
        audioRecorder = null
        audioSession?.close()
        audioSession = null
        worker = null
        filePath = null
    }

    private fun writeWavHeader(file: File, pcmDataBytes: Long) {
        val channelCount = 1
        val bitsPerSample = 16
        val safeDataBytes = pcmDataBytes.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val riffChunkSize = (36L + safeDataBytes).coerceIn(36L, Int.MAX_VALUE.toLong()).toInt()
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(riffChunkSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(channelCount.toShort())
            putInt(SAMPLE_RATE_HZ)
            putInt(SAMPLE_RATE_HZ * channelCount * bitsPerSample / 8)
            putShort((channelCount * bitsPerSample / 8).toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(safeDataBytes)
        }
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header.array())
        }
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val WAV_HEADER_BYTES = 44
    }
}

/** Cache folder for chat attachments (picked images and voice notes). */
internal fun chatAttachmentDir(context: Context): File =
    File(context.cacheDir, "chat_attachments").apply { if (!exists()) mkdirs() }
