package com.fersaiyan.cyanbridge.hil.audio

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Helper to copy deterministic audio fixtures from androidTest assets to app-private files.
 *
 * Audio files are stored in app/src/androidTest/assets/audio/ and are small synthetic wavs
 * (<1 MB) so the APK stays lean and git history stays clean. Each file has a .txt sidecar
 * with the expected transcript for hermetic tests.
 */
object TestAudioAssets {
    const val TRANSLATOR_WAV = "audio/translator_fr_en_8s.wav"
    const val TRANSLATOR_TXT = "audio/translator_fr_en_8s.txt"
    const val MEETING_WAV = "audio/meeting_pitch_25s.wav"
    const val MEETING_M4A = "audio/meeting_pitch_25s.m4a"
    const val MEETING_TXT = "audio/meeting_pitch_25s.txt"
    const val GEMINI_WAV = "audio/gemini_live_hello_5s.wav"
    const val GEMINI_PCM = "audio/gemini_live_hello_5s.pcm16"
    const val GEMINI_TXT = "audio/gemini_live_hello_5s.txt"

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val testContext get() = instrumentation.context
    private val targetContext get() = instrumentation.targetContext

    fun assetExists(name: String): Boolean = runCatching {
        testContext.assets.open(name).close()
        true
    }.getOrDefault(false)

    fun readTranscript(assetTxt: String): String =
        testContext.assets.open(assetTxt).bufferedReader().readText().trim()

    fun readPcm16(assetPcm: String): ByteArray =
        testContext.assets.open(assetPcm).readBytes()

    fun readWavBytes(assetWav: String): ByteArray =
        testContext.assets.open(assetWav).readBytes()

    fun copyAssetToCache(assetName: String, fileName: String? = null): File {
        val out = File(targetContext.cacheDir, fileName ?: File(assetName).name)
        testContext.assets.open(assetName).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        return out
    }

    fun copyAssetToFiles(assetName: String, fileName: String? = null): File {
        val out = File(targetContext.filesDir, fileName ?: File(assetName).name)
        testContext.assets.open(assetName).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        return out
    }

    fun wavToPcm16Shorts(wavBytes: ByteArray): ShortArray {
        // Parse WAV properly: find "data" chunk instead of assuming 44-byte header (ffmpeg may add extra fmt/List chunks).
        if (wavBytes.size <= 44) return ShortArray(0)
        var dataOffset = -1
        var dataSize = -1
        var i = 12
        while (i + 8 <= wavBytes.size) {
            val chunkId = String(wavBytes.copyOfRange(i, i + 4), Charsets.US_ASCII)
            val chunkSize = (wavBytes[i + 4].toInt() and 0xFF) or
                ((wavBytes[i + 5].toInt() and 0xFF) shl 8) or
                ((wavBytes[i + 6].toInt() and 0xFF) shl 16) or
                ((wavBytes[i + 7].toInt() and 0xFF) shl 24)
            if (chunkId == "data") {
                dataOffset = i + 8
                dataSize = chunkSize
                break
            }
            i += 8 + chunkSize + (chunkSize % 2)
        }
        if (dataOffset == -1) {
            // Fallback to 44
            dataOffset = 44
            dataSize = wavBytes.size - 44
        }
        val end = (dataOffset + dataSize).coerceAtMost(wavBytes.size)
        val pcm = wavBytes.copyOfRange(dataOffset, end)
        val shorts = ShortArray(pcm.size / 2)
        for (idx in shorts.indices) {
            val lo = pcm[idx * 2].toInt() and 0xFF
            val hi = pcm[idx * 2 + 1].toInt()
            shorts[idx] = ((hi shl 8) or lo).toShort()
        }
        return shorts
    }

    fun targetContext(): Context = targetContext
}
