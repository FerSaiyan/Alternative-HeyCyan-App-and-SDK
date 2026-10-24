package com.fersaiyan.cyanbridge.plugins.walkingaid

import android.content.Context
import com.fersaiyan.cyanbridge.shared.platform.PlatformPreferences
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs.WalkingAid
import com.fersaiyan.cyanbridge.shared.prefs.edit
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import com.fersaiyan.cyanbridge.shared.prefs.store

object WalkingAidPreferences {
    const val MODEL_TYPE_YOLO11 = "yolo11"
    const val MODEL_TYPE_YOLO_WORLD = "yolo_world"

    fun isEnabled(context: Context): Boolean = WalkingAid.enabled[context]

    fun getVideoMode(context: Context): WalkingAidVideoMode =
        WalkingAidVideoMode.entries.firstOrNull { it.name == WalkingAid.videoMode[context] }
            ?: WalkingAidVideoMode.PERIODIC_PHOTOS

    fun setVideoMode(context: Context, mode: WalkingAidVideoMode) {
        WalkingAid.videoMode[context] = mode.name
    }

    fun getMode(context: Context): WalkingAidMode =
        WalkingAidMode.entries.firstOrNull { it.name == WalkingAid.mode[context] } ?: WalkingAidMode.WALKING

    fun setMode(context: Context, mode: WalkingAidMode) {
        WalkingAid.mode[context] = mode.name
    }

    fun getSpeechRate(context: Context): Float = WalkingAid.speechRate[context]

    fun setSpeechRate(context: Context, rate: Float) {
        WalkingAid.speechRate[context] = rate
    }

    fun getAnnouncementPauseMs(context: Context): Long = WalkingAid.announcementPauseMs[context]

    fun setAnnouncementPauseMs(context: Context, pauseMs: Long) {
        WalkingAid.announcementPauseMs[context] = pauseMs
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        WalkingAid.enabled[context] = enabled
    }

    fun getCaptureIntervalSeconds(context: Context): Int = WalkingAid.captureIntervalSeconds[context]

    fun setCaptureIntervalSeconds(context: Context, seconds: Int) {
        WalkingAid.captureIntervalSeconds[context] = seconds
    }

    fun getImageDescriptionSource(context: Context): String = WalkingAid.imageDescriptionSource[context]

    fun setImageDescriptionSource(context: Context, source: String) {
        WalkingAid.imageDescriptionSource[context] = source
    }

    fun getImageDescriptionCloudModelId(context: Context): String = WalkingAid.imageDescriptionCloudModelId[context]

    fun setImageDescriptionCloudModelId(context: Context, modelId: String) {
        WalkingAid.imageDescriptionCloudModelId[context] = modelId
    }

    fun isDepthEnabled(context: Context): Boolean = WalkingAid.depthEnabled[context]

    fun setDepthEnabled(context: Context, enabled: Boolean) {
        WalkingAid.depthEnabled[context] = enabled
    }

    fun getDepthSource(context: Context): String = WalkingAid.depthSource[context]

    fun setDepthSource(context: Context, source: String) {
        WalkingAid.depthSource[context] = source
    }

    fun getDepthCloudModelId(context: Context): String = WalkingAid.depthCloudModelId[context]

    fun setDepthCloudModelId(context: Context, modelId: String) {
        WalkingAid.depthCloudModelId[context] = modelId
    }

    fun getStateModelSource(context: Context): String = WalkingAid.stateModelSource[context]

    fun setStateModelSource(context: Context, source: String) {
        WalkingAid.stateModelSource[context] = source
    }

    fun isTtsEnabled(context: Context): Boolean = WalkingAid.ttsEnabled[context]

    fun setTtsEnabled(context: Context, enabled: Boolean) {
        WalkingAid.ttsEnabled[context] = enabled
    }

    fun isSafetyDisclaimerEnabled(context: Context): Boolean = WalkingAid.safetyDisclaimerEnabled[context]

    fun setSafetyDisclaimerEnabled(context: Context, enabled: Boolean) {
        WalkingAid.safetyDisclaimerEnabled[context] = enabled
    }

    fun getImageHistoryMaxCount(context: Context): Int = WalkingAid.imageHistoryMaxCount[context]

    fun setImageHistoryMaxCount(context: Context, count: Int) {
        WalkingAid.imageHistoryMaxCount[context] = count
    }

    fun getCustomPrompt(context: Context): String = WalkingAid.customPrompt[context]

    fun setCustomPrompt(context: Context, prompt: String) {
        WalkingAid.customPrompt[context] = prompt
    }

    /** Returns the cloud model ID for image description, or null if using local source. */
    fun getImageDescriptionModelOverride(context: Context): String? {
        val source = getImageDescriptionSource(context)
        if (source != "cloud") return null
        return getImageDescriptionCloudModelId(context).ifBlank { null }
    }

    /** Returns the cloud model ID for depth estimation, or null if using local source. */
    fun getDepthModelOverride(context: Context): String? {
        val source = getDepthSource(context)
        if (source != "cloud") return null
        return getDepthCloudModelId(context).ifBlank { null }
    }

    /** Returns true if either image description or depth use cloud source. */
    fun usesCloudSource(context: Context): Boolean =
        getImageDescriptionSource(context) == "cloud" || getDepthSource(context) == "cloud"

    fun shouldUseCloud(context: Context): Boolean = usesCloudSource(context)

    fun getYoloModelType(context: Context): String = WalkingAid.yoloModelType[context]

    fun setYoloModelType(context: Context, type: String) {
        WalkingAid.yoloModelType[context] = type
    }

    fun getFocusDescription(context: Context): String =
        WalkingAid.focusDescription[context] ?: WalkingAid.watchlistTerms[context]

    fun setFocusDescription(context: Context, description: String) {
        val normalized = description.trim().take(500)
        val translatedOriginal = WalkingAid.focusTranslationOriginal[context]
        WalkingAid.edit(context) {
            this[WalkingAid.focusDescription] = normalized
            if (translatedOriginal != normalized) clearFocusTranslation()
        }
    }

    fun getCachedFocusTranslation(context: Context, original: String, source: String): String? {
        val preferences = WalkingAid.store(context)
        if (preferences[WalkingAid.focusTranslationOriginal] != original.trim().take(500)) return null
        if (!preferences[WalkingAid.focusTranslationSource].equals(source, ignoreCase = true)) return null
        return preferences[WalkingAid.focusTranslationEnglish]?.takeIf { it.isNotBlank() }
    }

    fun setCachedFocusTranslation(context: Context, original: String, source: String, english: String) {
        val normalizedOriginal = original.trim().take(500)
        WalkingAid.edit(context) {
            this[WalkingAid.focusDescription] = normalizedOriginal
            this[WalkingAid.stateModelSource] = source
            this[WalkingAid.focusTranslationOriginal] = normalizedOriginal
            this[WalkingAid.focusTranslationSource] = source
            this[WalkingAid.focusTranslationEnglish] = english.trim()
        }
    }

    private fun PlatformPreferences.clearFocusTranslation() {
        WalkingAid.focusTranslationOriginal.remove(this)
        WalkingAid.focusTranslationSource.remove(this)
        WalkingAid.focusTranslationEnglish.remove(this)
    }

    fun getModelFocusDescription(context: Context): String {
        val original = getFocusDescription(context).trim()
        val source = getStateModelSource(context)
        return getCachedFocusTranslation(context, original, source) ?: original
    }

    fun getThumbnailQualityLevel(context: Context): Int = WalkingAid.thumbnailQualityLevel[context]

    fun setThumbnailQualityLevel(context: Context, level: Int) {
        WalkingAid.thumbnailQualityLevel[context] = level
    }

    fun getPhotoSettleDelayMs(context: Context): Long = WalkingAid.photoSettleDelayMs[context].toLong()

    fun setPhotoSettleDelayMs(context: Context, delayMs: Int) {
        WalkingAid.photoSettleDelayMs[context] = delayMs
    }

    fun getWatchlistTerms(context: Context): List<String> =
        WalkingAidFocusMapper.resolve(getFocusDescription(context))

    fun setWatchlistTerms(context: Context, terms: List<String>) {
        val csv = terms.map { it.trim() }.filter { it.isNotBlank() }.joinToString(",")
        WalkingAid.edit(context) {
            this[WalkingAid.watchlistTerms] = csv
            this[WalkingAid.focusDescription] = csv
        }
    }
}
