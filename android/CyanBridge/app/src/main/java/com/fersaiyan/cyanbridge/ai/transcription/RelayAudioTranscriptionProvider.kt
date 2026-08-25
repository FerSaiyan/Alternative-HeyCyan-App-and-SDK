package com.fersaiyan.cyanbridge.ai.transcription

import android.content.Context
import com.fersaiyan.cyanbridge.ai.router.CliRelayClient
import java.io.File

class RelayAudioTranscriptionProvider(
    private val context: Context,
    private val modelOverride: String? = null,
    override val name: String = "relay_audio",
) : TranscriptionProvider {
    override suspend fun transcribe(audioFile: File, mimeType: String, language: String?): String {
        val languageHint = language?.trim()?.takeIf(String::isNotEmpty)
        val prompt = buildString {
            append("Transcribe this audio accurately. Return only the plain transcript without markdown or commentary.")
            if (languageHint != null) append(" Primary language hint: $languageHint.")
        }
        return CliRelayClient.audioQuery(
            context = context,
            audioPath = audioFile.absolutePath,
            prompt = prompt,
            modelOverride = modelOverride,
        ).getOrThrow().trim()
    }
}
