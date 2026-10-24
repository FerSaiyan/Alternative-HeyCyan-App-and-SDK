package com.fersaiyan.cyanbridge.plugins.handsfreetranslator

import android.content.Context
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs.HandsFreeTranslator
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set

object HandsFreeTranslatorPreferences {
    fun isEnabled(context: Context): Boolean = HandsFreeTranslator.enabled[context]

    fun setEnabled(context: Context, enabled: Boolean) {
        HandsFreeTranslator.enabled[context] = enabled
    }

    fun getSourceLanguage(context: Context): String = HandsFreeTranslator.sourceLanguage[context]

    fun setSourceLanguage(context: Context, language: String) {
        HandsFreeTranslator.sourceLanguage[context] = language
    }

    fun getTargetLanguage(context: Context): String = HandsFreeTranslator.targetLanguage[context]

    fun setTargetLanguage(context: Context, language: String) {
        HandsFreeTranslator.targetLanguage[context] = language
    }

    fun getTranslationMode(context: Context): String = HandsFreeTranslator.translationMode[context]

    fun setTranslationMode(context: Context, mode: String) {
        HandsFreeTranslator.translationMode[context] = mode
    }

    fun isAutoDetect(context: Context): Boolean = HandsFreeTranslator.autoDetect[context]

    fun setAutoDetect(context: Context, autoDetect: Boolean) {
        HandsFreeTranslator.autoDetect[context] = autoDetect
    }

    fun isSpeakTranslation(context: Context): Boolean = HandsFreeTranslator.speakTranslation[context]

    fun setSpeakTranslation(context: Context, speak: Boolean) {
        HandsFreeTranslator.speakTranslation[context] = speak
    }

    fun isShowConfidence(context: Context): Boolean = HandsFreeTranslator.showConfidence[context]

    fun setShowConfidence(context: Context, show: Boolean) {
        HandsFreeTranslator.showConfidence[context] = show
    }

    fun getMaxHistory(context: Context): Int = HandsFreeTranslator.maxHistory[context]

    fun setMaxHistory(context: Context, count: Int) {
        HandsFreeTranslator.maxHistory[context] = count
    }

    fun getCloudModelId(context: Context): String = HandsFreeTranslator.cloudModelId[context]

    fun setCloudModelId(context: Context, modelId: String) {
        HandsFreeTranslator.cloudModelId[context] = modelId
    }

    fun getCustomPrompt(context: Context): String = HandsFreeTranslator.customPrompt[context]

    fun setCustomPrompt(context: Context, prompt: String) {
        HandsFreeTranslator.customPrompt[context] = prompt
    }
}
