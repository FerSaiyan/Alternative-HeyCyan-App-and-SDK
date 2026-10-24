package com.fersaiyan.cyanbridge.plugins.livecaptionrelay

data class CaptionEntry(
    val timestampMs: Long,
    val originalText: String,
    val translatedText: String?,
    val sourceLanguage: String,
    val targetLanguage: String?,
    val confidence: Float,
)
