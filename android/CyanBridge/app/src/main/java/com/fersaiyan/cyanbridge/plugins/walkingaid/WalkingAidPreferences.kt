package com.fersaiyan.cyanbridge.plugins.walkingaid

import android.content.Context
import com.fersaiyan.cyanbridge.shared.plugins.walkingaid.WalkingAidSettings
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs.WalkingAid
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import com.fersaiyan.cyanbridge.shared.prefs.store

object WalkingAidPreferences {
    const val MODEL_TYPE_YOLO11 = WalkingAidSettings.MODEL_TYPE_YOLO11
    const val MODEL_TYPE_YOLO_WORLD = WalkingAidSettings.MODEL_TYPE_YOLO_WORLD

    fun isEnabled(context: Context): Boolean = WalkingAid.enabled[context]

    fun getVideoMode(context: Context): WalkingAidVideoMode = WalkingAidSettings.videoMode(WalkingAid.store(context))

    fun setVideoMode(context: Context, mode: WalkingAidVideoMode) {
        WalkingAid.videoMode[context] = mode.name
    }

    fun getMode(context: Context): WalkingAidMode = WalkingAidSettings.mode(WalkingAid.store(context))

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

    fun setDepthCloudModelId(context: Context, modelId: String) {
        WalkingAid.depthCloudModelId[context] = modelId
    }

    fun setStateModelSource(context: Context, source: String) {
        WalkingAid.stateModelSource[context] = source
    }

    fun isTtsEnabled(context: Context): Boolean = WalkingAid.ttsEnabled[context]

    fun isSafetyDisclaimerEnabled(context: Context): Boolean = WalkingAid.safetyDisclaimerEnabled[context]

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
        return WalkingAid.imageDescriptionCloudModelId[context].ifBlank { null }
    }

    /** Returns the cloud model ID for depth estimation, or null if using local source. */
    fun getDepthModelOverride(context: Context): String? {
        val source = getDepthSource(context)
        if (source != "cloud") return null
        return WalkingAid.depthCloudModelId[context].ifBlank { null }
    }

    fun getYoloModelType(context: Context): String = WalkingAid.yoloModelType[context]

    fun setYoloModelType(context: Context, type: String) {
        WalkingAid.yoloModelType[context] = type
    }

    fun getCachedFocusTranslation(context: Context, original: String, source: String): String? =
        WalkingAidSettings.cachedFocusTranslation(WalkingAid.store(context), original, source)

    fun setCachedFocusTranslation(context: Context, original: String, source: String, english: String) =
        WalkingAidSettings.setCachedFocusTranslation(WalkingAid.store(context), original, source, english)

    fun getModelFocusDescription(context: Context): String =
        WalkingAidSettings.modelFocusDescription(WalkingAid.store(context))

    fun getThumbnailQualityLevel(context: Context): Int = WalkingAid.thumbnailQualityLevel[context]

    fun getPhotoSettleDelayMs(context: Context): Long = WalkingAid.photoSettleDelayMs[context].toLong()
}
