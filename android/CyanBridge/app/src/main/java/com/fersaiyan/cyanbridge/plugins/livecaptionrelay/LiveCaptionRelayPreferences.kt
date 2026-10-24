package com.fersaiyan.cyanbridge.plugins.livecaptionrelay

import android.content.Context
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs.LiveCaptionRelay
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set

object LiveCaptionRelayPreferences {
    fun isEnabled(context: Context): Boolean = LiveCaptionRelay.enabled[context]

    fun setEnabled(context: Context, enabled: Boolean) {
        LiveCaptionRelay.enabled[context] = enabled
    }

    fun getSourceLanguage(context: Context): String = LiveCaptionRelay.sourceLanguage[context]

    fun setSourceLanguage(context: Context, language: String) {
        LiveCaptionRelay.sourceLanguage[context] = language
    }

    fun getTargetLanguage(context: Context): String = LiveCaptionRelay.targetLanguage[context]

    fun setTargetLanguage(context: Context, language: String) {
        LiveCaptionRelay.targetLanguage[context] = language
    }

    fun isTranslationEnabled(context: Context): Boolean = LiveCaptionRelay.translationEnabled[context]

    fun setTranslationEnabled(context: Context, enabled: Boolean) {
        LiveCaptionRelay.translationEnabled[context] = enabled
    }

    fun getFontSize(context: Context): Int = LiveCaptionRelay.fontSize[context]

    fun setFontSize(context: Context, size: Int) {
        LiveCaptionRelay.fontSize[context] = size
    }

    fun getCaptionPosition(context: Context): String = LiveCaptionRelay.captionPosition[context]

    fun setCaptionPosition(context: Context, position: String) {
        LiveCaptionRelay.captionPosition[context] = position
    }

    fun isShowConfidence(context: Context): Boolean = LiveCaptionRelay.showConfidence[context]

    fun setShowConfidence(context: Context, show: Boolean) {
        LiveCaptionRelay.showConfidence[context] = show
    }

    fun getMaxHistory(context: Context): Int = LiveCaptionRelay.maxHistory[context]

    fun setMaxHistory(context: Context, count: Int) {
        LiveCaptionRelay.maxHistory[context] = count
    }

    fun getCloudModelId(context: Context): String = LiveCaptionRelay.cloudModelId[context]

    fun setCloudModelId(context: Context, modelId: String) {
        LiveCaptionRelay.cloudModelId[context] = modelId
    }

    fun getCustomPrompt(context: Context): String = LiveCaptionRelay.customPrompt[context]

    fun setCustomPrompt(context: Context, prompt: String) {
        LiveCaptionRelay.customPrompt[context] = prompt
    }
}
