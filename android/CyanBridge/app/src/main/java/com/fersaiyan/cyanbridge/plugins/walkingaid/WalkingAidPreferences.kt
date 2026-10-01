package com.fersaiyan.cyanbridge.plugins.walkingaid

import android.content.Context

object WalkingAidPreferences {
    private const val PREFS = "walking_aid_prefs"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_VIDEO_MODE = "video_mode"
    private const val KEY_CAPTURE_INTERVAL_SECONDS = "capture_interval_seconds"
    private const val KEY_IMAGE_DESCRIPTION_SOURCE = "image_description_source"
    private const val KEY_IMAGE_DESCRIPTION_CLOUD_MODEL_ID = "image_description_cloud_model_id"
    private const val KEY_DEPTH_ENABLED = "depth_enabled"
    private const val KEY_DEPTH_SOURCE = "depth_source"
    private const val KEY_DEPTH_CLOUD_MODEL_ID = "depth_cloud_model_id"
    private const val KEY_STATE_MODEL_SOURCE = "state_model_source"
    private const val KEY_TTS_ENABLED = "tts_enabled"
    private const val KEY_SAFETY_DISCLAIMER_ENABLED = "safety_disclaimer_enabled"
    private const val KEY_IMAGE_HISTORY_MAX_COUNT = "image_history_max_count"
    private const val KEY_CUSTOM_PROMPT = "custom_prompt"
    private const val KEY_YOLO_MODEL_TYPE = "yolo_model_type"
    private const val KEY_WATCHLIST_TERMS = "watchlist_terms"
    private const val KEY_FOCUS_DESCRIPTION = "focus_description"
    private const val KEY_FOCUS_TRANSLATION_ORIGINAL = "focus_translation_original"
    private const val KEY_FOCUS_TRANSLATION_SOURCE = "focus_translation_source"
    private const val KEY_FOCUS_TRANSLATION_ENGLISH = "focus_translation_english"
    private const val KEY_THUMBNAIL_QUALITY_LEVEL = "thumbnail_quality_level"
    private const val KEY_PHOTO_SETTLE_DELAY_MS = "photo_settle_delay_ms"
    private const val MAX_CUSTOM_PROMPT_CHARS = 1_500

    private const val DEFAULT_ENABLED = false
    private const val DEFAULT_CAPTURE_INTERVAL_SECONDS = 5
    private const val DEFAULT_IMAGE_DESCRIPTION_SOURCE = "local"
    private const val DEFAULT_IMAGE_DESCRIPTION_CLOUD_MODEL_ID = "cyanbridge/walking-aid-vision"
    private const val DEFAULT_DEPTH_ENABLED = true
    private const val DEFAULT_DEPTH_SOURCE = "cloud"
    private const val DEFAULT_DEPTH_CLOUD_MODEL_ID = "cyanbridge/walking-aid-vision"
    private const val DEFAULT_STATE_MODEL_SOURCE = "local"
    private const val DEFAULT_TTS_ENABLED = true
    private const val DEFAULT_SAFETY_DISCLAIMER_ENABLED = true
    private const val DEFAULT_IMAGE_HISTORY_MAX_COUNT = 50
    const val MODEL_TYPE_YOLO11 = "yolo11"
    const val MODEL_TYPE_YOLO_WORLD = "yolo_world"
    private const val DEFAULT_YOLO_MODEL_TYPE = MODEL_TYPE_YOLO11

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun getVideoMode(context: Context): WalkingAidVideoMode =
        WalkingAidVideoMode.entries.firstOrNull { it.name == prefs(context).getString(KEY_VIDEO_MODE, null) }
            ?: WalkingAidVideoMode.PERIODIC_PHOTOS

    fun setVideoMode(context: Context, mode: WalkingAidVideoMode) {
        prefs(context).edit().putString(KEY_VIDEO_MODE, mode.name).apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getCaptureIntervalSeconds(context: Context): Int =
        prefs(context).getInt(KEY_CAPTURE_INTERVAL_SECONDS, DEFAULT_CAPTURE_INTERVAL_SECONDS)
            .coerceIn(2, 60)

    fun setCaptureIntervalSeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_CAPTURE_INTERVAL_SECONDS, seconds.coerceIn(2, 60)).apply()
    }

    fun getImageDescriptionSource(context: Context): String =
        prefs(context).getString(KEY_IMAGE_DESCRIPTION_SOURCE, DEFAULT_IMAGE_DESCRIPTION_SOURCE).orEmpty()

    fun setImageDescriptionSource(context: Context, source: String) {
        prefs(context).edit().putString(KEY_IMAGE_DESCRIPTION_SOURCE, source).apply()
    }

    fun getImageDescriptionCloudModelId(context: Context): String =
        prefs(context).getString(KEY_IMAGE_DESCRIPTION_CLOUD_MODEL_ID, DEFAULT_IMAGE_DESCRIPTION_CLOUD_MODEL_ID).orEmpty()

    fun setImageDescriptionCloudModelId(context: Context, modelId: String) {
        prefs(context).edit().putString(KEY_IMAGE_DESCRIPTION_CLOUD_MODEL_ID, modelId).apply()
    }

    fun isDepthEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEPTH_ENABLED, DEFAULT_DEPTH_ENABLED)

    fun setDepthEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DEPTH_ENABLED, enabled).apply()
    }

    fun getDepthSource(context: Context): String =
        prefs(context).getString(KEY_DEPTH_SOURCE, DEFAULT_DEPTH_SOURCE).orEmpty()

    fun setDepthSource(context: Context, source: String) {
        prefs(context).edit().putString(KEY_DEPTH_SOURCE, source).apply()
    }

    fun getDepthCloudModelId(context: Context): String =
        prefs(context).getString(KEY_DEPTH_CLOUD_MODEL_ID, DEFAULT_DEPTH_CLOUD_MODEL_ID).orEmpty()

    fun setDepthCloudModelId(context: Context, modelId: String) {
        prefs(context).edit().putString(KEY_DEPTH_CLOUD_MODEL_ID, modelId).apply()
    }

    fun getStateModelSource(context: Context): String =
        prefs(context).getString(KEY_STATE_MODEL_SOURCE, DEFAULT_STATE_MODEL_SOURCE).orEmpty()

    fun setStateModelSource(context: Context, source: String) {
        prefs(context).edit().putString(KEY_STATE_MODEL_SOURCE, source).apply()
    }

    fun isTtsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TTS_ENABLED, DEFAULT_TTS_ENABLED)

    fun setTtsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TTS_ENABLED, enabled).apply()
    }

    fun isSafetyDisclaimerEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SAFETY_DISCLAIMER_ENABLED, DEFAULT_SAFETY_DISCLAIMER_ENABLED)

    fun setSafetyDisclaimerEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SAFETY_DISCLAIMER_ENABLED, enabled).apply()
    }

    fun getImageHistoryMaxCount(context: Context): Int =
        prefs(context).getInt(KEY_IMAGE_HISTORY_MAX_COUNT, DEFAULT_IMAGE_HISTORY_MAX_COUNT)
            .coerceIn(10, 200)

    fun setImageHistoryMaxCount(context: Context, count: Int) {
        prefs(context).edit().putInt(KEY_IMAGE_HISTORY_MAX_COUNT, count.coerceIn(10, 200)).apply()
    }

    fun getCustomPrompt(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_PROMPT, "").orEmpty()
            .take(MAX_CUSTOM_PROMPT_CHARS)

    fun setCustomPrompt(context: Context, prompt: String) {
        prefs(context).edit().putString(KEY_CUSTOM_PROMPT, prompt.trim().take(MAX_CUSTOM_PROMPT_CHARS)).apply()
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

    fun getYoloModelType(context: Context): String =
        prefs(context).getString(KEY_YOLO_MODEL_TYPE, DEFAULT_YOLO_MODEL_TYPE) ?: DEFAULT_YOLO_MODEL_TYPE

    fun setYoloModelType(context: Context, type: String) {
        prefs(context).edit().putString(KEY_YOLO_MODEL_TYPE, type).apply()
    }

    fun getFocusDescription(context: Context): String =
        prefs(context).getString(KEY_FOCUS_DESCRIPTION, null)
            ?: prefs(context).getString(KEY_WATCHLIST_TERMS, "").orEmpty()

    fun setFocusDescription(context: Context, description: String) {
        val normalized = description.trim().take(500)
        val preferences = prefs(context)
        val edit = preferences.edit().putString(KEY_FOCUS_DESCRIPTION, normalized)
        if (preferences.getString(KEY_FOCUS_TRANSLATION_ORIGINAL, null) != normalized) {
            edit.remove(KEY_FOCUS_TRANSLATION_ORIGINAL)
                .remove(KEY_FOCUS_TRANSLATION_SOURCE)
                .remove(KEY_FOCUS_TRANSLATION_ENGLISH)
        }
        edit.apply()
    }

    fun getCachedFocusTranslation(context: Context, original: String, source: String): String? {
        val preferences = prefs(context)
        if (preferences.getString(KEY_FOCUS_TRANSLATION_ORIGINAL, null) != original.trim().take(500)) return null
        if (!preferences.getString(KEY_FOCUS_TRANSLATION_SOURCE, null).equals(source, ignoreCase = true)) return null
        return preferences.getString(KEY_FOCUS_TRANSLATION_ENGLISH, null)?.takeIf { it.isNotBlank() }
    }

    fun setCachedFocusTranslation(context: Context, original: String, source: String, english: String) {
        val normalizedOriginal = original.trim().take(500)
        prefs(context).edit()
            .putString(KEY_FOCUS_DESCRIPTION, normalizedOriginal)
            .putString(KEY_STATE_MODEL_SOURCE, source)
            .putString(KEY_FOCUS_TRANSLATION_ORIGINAL, normalizedOriginal)
            .putString(KEY_FOCUS_TRANSLATION_SOURCE, source)
            .putString(KEY_FOCUS_TRANSLATION_ENGLISH, english.trim())
            .apply()
    }

    fun getModelFocusDescription(context: Context): String {
        val original = getFocusDescription(context).trim()
        val source = getStateModelSource(context)
        return getCachedFocusTranslation(context, original, source) ?: original
    }

    fun getThumbnailQualityLevel(context: Context): Int =
        prefs(context).getInt(KEY_THUMBNAIL_QUALITY_LEVEL, 3)
            .coerceIn(0, 5)

    fun setThumbnailQualityLevel(context: Context, level: Int) {
        prefs(context).edit().putInt(KEY_THUMBNAIL_QUALITY_LEVEL, level.coerceIn(0, 5)).apply()
    }

    fun getPhotoSettleDelayMs(context: Context): Long =
        prefs(context).getInt(KEY_PHOTO_SETTLE_DELAY_MS, 250)
            .coerceIn(0, 500).toLong()

    fun setPhotoSettleDelayMs(context: Context, delayMs: Int) {
        prefs(context).edit().putInt(KEY_PHOTO_SETTLE_DELAY_MS, delayMs.coerceIn(0, 500)).apply()
    }

    fun getWatchlistTerms(context: Context): List<String> =
        WalkingAidFocusMapper.resolve(getFocusDescription(context))

    fun setWatchlistTerms(context: Context, terms: List<String>) {
        val csv = terms.map { it.trim() }.filter { it.isNotBlank() }.joinToString(",")
        prefs(context).edit()
            .putString(KEY_WATCHLIST_TERMS, csv)
            .putString(KEY_FOCUS_DESCRIPTION, csv)
            .apply()
    }
}
