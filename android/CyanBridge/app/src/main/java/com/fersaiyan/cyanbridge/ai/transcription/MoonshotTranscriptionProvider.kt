package com.fersaiyan.cyanbridge.ai.transcription

import android.content.Context
import com.fersaiyan.cyanbridge.BuildConfig
import java.io.File

/**
 * OpenAI-compatible audio transcription provider configured for Moonshot/Kimi-style endpoints.
 *
 * Endpoint and key can be overridden through [TranscriptionEndpointPrefs].
 */
class MoonshotTranscriptionProvider(
    private val context: Context,
    private val defaultModel: String = "whisper-1",
) : TranscriptionProvider {

    override val name: String = "moonshot"

    override suspend fun transcribe(audioFile: File, mimeType: String, language: String?): String {
        val endpoint = TranscriptionEndpointPrefs.getEndpointUrl(context)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MOONSHOT_TRANSCRIBE_ENDPOINT

        val apiKey = TranscriptionEndpointPrefs.getApiKey(context)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.OPENAI_API_KEY.trim()

        if (apiKey.isBlank()) {
            throw IllegalStateException(
                "Moonshot cloud transcription is not configured. Use Gemma LiteRT local transcription or configure cloud credentials.",
            )
        }

        return OpenAIWhisperTranscriptionProvider(
            apiKey = apiKey,
            endpointUrl = endpoint,
            model = defaultModel,
        ).transcribe(
            audioFile = audioFile,
            mimeType = mimeType,
            language = language,
        )
    }

    companion object {
        private const val DEFAULT_MOONSHOT_TRANSCRIBE_ENDPOINT = "https://api.moonshot.ai/v1/audio/transcriptions"
    }
}
