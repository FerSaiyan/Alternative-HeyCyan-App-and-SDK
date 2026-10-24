package com.fersaiyan.cyanbridge.ai.transcription

import com.fersaiyan.cyanbridge.ai.transcription.retry.RetryPolicy
import java.io.File
import java.io.IOException

class RetryingTranscriptionProvider(
    private val delegate: TranscriptionProvider,
    private val policy: RetryPolicy = RetryPolicy(),
) : TranscriptionProvider {

    override val name: String = delegate.name

    override suspend fun transcribe(audioFile: File, mimeType: String, language: String?): String =
        policy.execute(::isRetryable) { delegate.transcribe(audioFile, mimeType, language) }

    private fun isRetryable(t: Throwable): Boolean {
        return when (t) {
            is IOException -> true
            is TranscriptionHttpException -> {
                t.code == 408 || t.code == 429 || (t.code in 500..599)
            }
            else -> false
        }
    }
}
