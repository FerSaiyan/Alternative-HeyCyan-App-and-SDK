package com.fersaiyan.cyanbridge.ai.transcription

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelRequestPriority
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelRuntime
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Audio transcription with local LiteRT Gemma models.
 *
 * For long meetings, this provider is expected to receive chunks from the transcription service.
 */
class GemmaLiteRtTranscriptionProvider(
    private val context: Context,
    private val localModelsProvider: LocalModelsProvider = LocalModelsProvider(),
) : TranscriptionProvider {

    override val name: String = "gemma_litert"

    override suspend fun transcribe(audioFile: File, mimeType: String, language: String?): String {
        requireGemmaLiteRtSelected()

        val prepared = ensureLiteRtCompatibleAudio(audioFile)

        return try {
            val raw = localModelsProvider.streamChat(
                context = context,
                messages = listOf(
                    mapOf(
                        "role" to "User",
                        "content" to buildTranscriptionPrompt(language),
                    ),
                ),
                imagePaths = emptyList(),
                audioPath = prepared.absolutePath,
                requestPriority = LocalModelRequestPriority.HIGH,
            ).trim()
            if (isLocalGenerationFallbackResponse(raw)) {
                ""
            } else {
                raw.sanitizeTranscript()
            }
        } finally {
            if (prepared.absolutePath != audioFile.absolutePath) {
                runCatching { prepared.delete() }
            }
        }
    }

    private fun requireGemmaLiteRtSelected() {
        val selected = LocalModelStorageRepository.resolveSelectedModel(context)
            ?: throw IllegalStateException("No local model selected. Install/select a Gemma LiteRT model first.")
        val settings = LocalModelSettingsRepository.getForModel(context, selected.id)
        if (settings.modelRuntime != LocalModelRuntime.LITERT) {
            throw IllegalStateException("Gemma transcription requires Local Runtime = LiteRT.")
        }
        val hint = "${selected.displayName} ${selected.catalogId.orEmpty()}".lowercase(Locale.US)
        if (!hint.contains("gemma")) {
            throw IllegalStateException("Selected local model is not Gemma. Please select a Gemma 4 LiteRT model.")
        }
    }

    private fun buildTranscriptionPrompt(language: String?): String {
        val langLine = language?.takeIf { it.isNotBlank() }?.let {
            "Primary language hint: $it."
        } ?: ""
        return buildString {
            appendLine("Transcribe the attached audio accurately.")
            appendLine("Return only plain transcript text.")
            appendLine("Do not summarize, do not add commentary, do not add markdown.")
            appendLine("If there is no intelligible speech, return exactly: [NO_SPEECH]")
            appendLine("Keep line breaks natural for paragraph pauses.")
            if (langLine.isNotBlank()) appendLine(langLine)
        }.trim()
    }

    private fun ensureLiteRtCompatibleAudio(input: File): File {
        val ext = input.extension.lowercase(Locale.US)
        if (ext in SUPPORTED_AUDIO_EXTENSIONS) return input
        return transcodeToWavMono(input)
    }

    private fun transcodeToWavMono(input: File): File {
        val extractor = MediaExtractor()
        extractor.setDataSource(input.absolutePath)

        var trackIndex = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) {
                trackIndex = i
                inputFormat = f
                break
            }
        }
        if (trackIndex < 0 || inputFormat == null) {
            extractor.release()
            throw IllegalStateException("No audio track found for Gemma transcription")
        }

        extractor.selectTrack(trackIndex)
        val codecMime = inputFormat.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm"
        val decoder = MediaCodec.createDecoderByType(codecMime)
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()

        var outputSampleRate = runCatching { inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(16_000)
        var outputChannels = runCatching { inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(1)
        var outputPcmEncoding = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && inputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            inputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            2
        }

        val pcm = ByteArrayOutputStream(256 * 1024)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuffer = decoder.getInputBuffer(inIndex)
                        if (inBuffer != null) {
                            val sampleSize = extractor.readSampleData(inBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                when (val outIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = decoder.outputFormat
                        if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            outputSampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            outputChannels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outputPcmEncoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    else -> {
                        if (outIndex >= 0) {
                            val outBuffer = decoder.getOutputBuffer(outIndex)
                            if (outBuffer != null && info.size > 0) {
                                outBuffer.position(info.offset)
                                outBuffer.limit(info.offset + info.size)
                                val shorts = when (outputPcmEncoding) {
                                    4 -> floatBufferToShorts(outBuffer)
                                    else -> bufferToShortsLE(outBuffer)
                                }
                                val mono = downmixToMono(shorts, outputChannels)
                                pcm.write(shortsToBytesLE(mono))
                            }
                            decoder.releaseOutputBuffer(outIndex, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            }
        } finally {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { extractor.release() }
        }

        val outDir = File(context.cacheDir, "gemma_transcribe_audio").apply { mkdirs() }
        val wavFile = File(outDir, "${input.nameWithoutExtension}_${System.currentTimeMillis()}.wav")
        val rawPcmBytes = pcm.toByteArray()
        val compactedSamples = SilenceCompactor.compactMonoPcm(
            samples = bytesToShortsLE(rawPcmBytes),
            sampleRateHz = outputSampleRate.coerceAtLeast(8_000),
        )
        val pcmBytes = shortsToBytesLE(compactedSamples)
        writeWavFile(
            wavFile = wavFile,
            pcmBytes = pcmBytes,
            sampleRateHz = outputSampleRate.coerceAtLeast(8_000),
            channels = 1,
            bitsPerSample = 16,
        )
        return wavFile
    }

    private fun writeWavFile(
        wavFile: File,
        pcmBytes: ByteArray,
        sampleRateHz: Int,
        channels: Int,
        bitsPerSample: Int,
    ) {
        val dataBytes = pcmBytes.size
        val byteRate = sampleRateHz * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val riffChunkSize = 36 + dataBytes

        val header = ByteBuffer.allocate(44)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(riffChunkSize)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1)
                putShort(channels.toShort())
                putInt(sampleRateHz)
                putInt(byteRate)
                putShort(blockAlign.toShort())
                putShort(bitsPerSample.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataBytes)
            }

        FileOutputStream(wavFile).use { out ->
            out.write(header.array())
            out.write(pcmBytes)
        }
    }

    private fun bufferToShortsLE(buf: ByteBuffer): ShortArray {
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        return bytesToShortsLE(bytes)
    }

    private fun bytesToShortsLE(bytes: ByteArray): ShortArray {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = ShortArray(bytes.size / 2)
        bb.asShortBuffer().get(out)
        return out
    }

    private fun floatBufferToShorts(buf: ByteBuffer): ShortArray {
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(bytes.size / 4)
        bb.asFloatBuffer().get(floats)
        val out = ShortArray(floats.size)
        for (i in floats.indices) {
            out[i] = (floats[i].coerceIn(-1f, 1f) * Short.MAX_VALUE)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return out
    }

    private fun downmixToMono(interleaved: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return interleaved
        val frameCount = interleaved.size / channels
        if (frameCount <= 0) return shortArrayOf()
        val out = ShortArray(frameCount)
        var read = 0
        for (f in 0 until frameCount) {
            var sum = 0
            for (c in 0 until channels) {
                sum += interleaved[read++].toInt()
            }
            out[f] = (sum / channels)
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return out
    }

    private fun shortsToBytesLE(samples: ShortArray): ByteArray {
        val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bb.putShort(it) }
        return bb.array()
    }

    private fun String.sanitizeTranscript(): String {
        val text = trim()
        if (text.equals("[NO_SPEECH]", ignoreCase = true)) return ""
        if (text.equals("NO_SPEECH", ignoreCase = true)) return ""

        val unfenced = if (text.startsWith("```")) {
            text
                .removePrefix("```")
                .removePrefix("text")
                .removePrefix("markdown")
                .trim()
                .removeSuffix("```")
                .trim()
        } else {
            text
        }

        return unfenced
    }

    private fun isLocalGenerationFallbackResponse(raw: String): Boolean {
        val text = raw.trim().lowercase(Locale.US)
        if (text.isBlank()) return true
        return text.contains("i couldn't generate a reply yet") ||
            text.contains("please try once more") ||
            text.contains("local model was reloaded")
    }

    companion object {
        private val SUPPORTED_AUDIO_EXTENSIONS = setOf("wav", "mp3", "flac")
    }
}
