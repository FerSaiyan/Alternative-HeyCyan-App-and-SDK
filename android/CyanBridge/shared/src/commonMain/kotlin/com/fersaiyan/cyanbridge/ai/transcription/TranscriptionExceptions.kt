package com.fersaiyan.cyanbridge.ai.transcription

class TranscriptionHttpException(
    val code: Int,
    val body: String?
) : Exception("HTTP $code")
