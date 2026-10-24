package com.fersaiyan.cyanbridge.plugins.handsfreetranslator

data class TranslationEntry(
    val timestampMs: Long,
    val originalText: String,
    val translatedText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val confidence: Float,
)

data class TranslationPreset(
    val id: String,
    val name: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val phrases: List<String>,
)
