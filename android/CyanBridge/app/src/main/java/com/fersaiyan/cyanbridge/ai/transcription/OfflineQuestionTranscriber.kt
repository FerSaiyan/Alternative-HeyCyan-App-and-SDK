package com.fersaiyan.cyanbridge.ai.transcription

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale

/** Transcribes our recorded PCM using only Android's explicit on-device service. */
class OfflineQuestionTranscriber(private val context: Context) {
    suspend fun transcribe(wav: File, languageTag: String): String? {
        // Older APIs cannot reliably inject the exact recording and own endpointing.
        if (Build.VERSION.SDK_INT < 33) return null
        return try {
            withTimeout((wav.length() - 44).coerceAtLeast(0) * 1000 / 32_000 + 30_000) { transcribeSupported(wav, languageTag) }
        } catch (cancelled: CancellationException) {
            if (cancelled is kotlinx.coroutines.TimeoutCancellationException) {
                Log.w(TAG, "On-device transcription timed out; retaining audio for model")
                null
            } else throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "On-device transcription unavailable; retaining audio for model", error)
            null
        }
    }

    @RequiresApi(33)
    private suspend fun transcribeSupported(wav: File, languageTag: String): String? = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return@withContext null
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            }
            val support = CompletableDeferred<RecognitionSupport?>()
            recognizer.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(result: RecognitionSupport) { support.complete(result) }
                override fun onError(error: Int) { support.complete(null) }
            })
            // Do not let a provider that omits support callbacks block audio fallback.
            val installed = kotlinx.coroutines.withTimeoutOrNull(3_000) { support.await() }
                ?.installedOnDeviceLanguages ?: return@withContext null
            val locale = selectInstalledLocale(languageTag, installed) ?: return@withContext null
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            val result = CompletableDeferred<String?>()
            val segments = mutableListOf<String>()
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    Log.w(TAG, "On-device recognition failed code=$error locale=$locale")
                    result.complete(null)
                }
                override fun onResults(results: Bundle?) {
                    result.complete(results.firstText()?.takeIf { it.isNotBlank() } ?: segments.joinToString(" ").takeIf { it.isNotBlank() })
                }
                override fun onSegmentResults(segmentResults: Bundle) { segmentResults.firstText()?.let(segments::add) }
                override fun onEndOfSegmentedSession() { result.complete(segments.joinToString(" ").takeIf { it.isNotBlank() }) }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            coroutineScope {
                recognizer.startListening(intent)
                val writer = launch(Dispatchers.IO) {
                    try {
                        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                            wav.inputStream().use { input ->
                                // Only our canonical PCM16 WAV files are accepted here.
                                check(input.readNBytes(44).size == 44) { "Invalid question WAV" }
                                val buffer = ByteArray(640)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    // Preserve real-time pacing for services that do streaming endpointing.
                                    kotlinx.coroutines.delay(count * 1000L / 32_000)
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { result.complete(null) }
                }
                try { result.await()?.trim()?.takeIf { it.isNotBlank() } }
                finally {
                    runCatching { pipe[0].close() }
                    runCatching { pipe[1].close() }
                    writer.cancel()
                }
            }
        } finally {
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
            withContext(NonCancellable + Dispatchers.Main) { recognizer.destroy() }
        }
    }

    private fun Bundle?.firstText(): String? = this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

    companion object {
        private const val TAG = "OfflineQuestionSTT"
        internal fun selectInstalledLocale(requested: String, installed: List<String>): String? {
            val locale = Locale.forLanguageTag(requested)
            installed.firstOrNull { it.equals(requested, ignoreCase = true) }?.let { return it }
            val sameLanguage = installed.filter { Locale.forLanguageTag(it).language == locale.language }
            if (locale.country.isNotBlank()) return null // don't silently change an explicitly selected regional dialect
            return sameLanguage.firstOrNull { it.equals(Locale.getDefault().toLanguageTag(), true) }
                ?: sameLanguage.firstOrNull()
        }
    }
}
