package com.fersaiyan.cyanbridge.shared.plugins.walkingaid

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.platform.PlatformPreferences
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs.WalkingAid
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set

/** Independent of the camera source: the same frames can drive alerts or scene exploration. */
enum class WalkingAidMode {
    WALKING, EXPLORE;
}

enum class WalkingAidVideoMode {
    PERIODIC_PHOTOS, HEYCYAN_VIDEO, EYEVUE_VIDEO, META_VIDEO;

    fun supports(device: DeviceClass, sdk: Int): Boolean = when (this) {
        PERIODIC_PHOTOS -> true
        HEYCYAN_VIDEO -> device == DeviceClass.HEY_CYAN
        EYEVUE_VIDEO -> device == DeviceClass.EYEVUE && sdk >= 29
        META_VIDEO -> device == DeviceClass.META_RAYBAN
    }
}

data class WalkingAidReadinessResult(
    val isReady: Boolean,
    val yoloReady: Boolean,
    val depthReady: Boolean,
    val llmReady: Boolean,
    val requiresProForCloud: Boolean,
    val missingDetails: List<String>,
)

/** The Walking Aid preferences stored in an encoded form: the modes and the translated attention focus. */
object WalkingAidSettings {
    const val MODEL_TYPE_YOLO11 = "yolo11"
    const val MODEL_TYPE_YOLO_WORLD = "yolo_world"
    private const val MAX_FOCUS_CHARS = 500

    fun videoMode(store: PlatformPreferences): WalkingAidVideoMode =
        WalkingAidVideoMode.entries.firstOrNull { it.name == store[WalkingAid.videoMode] }
            ?: WalkingAidVideoMode.PERIODIC_PHOTOS

    fun mode(store: PlatformPreferences): WalkingAidMode =
        WalkingAidMode.entries.firstOrNull { it.name == store[WalkingAid.mode] } ?: WalkingAidMode.WALKING

    /** The user's own words; older installs kept a comma-separated watch list instead. */
    fun focusDescription(store: PlatformPreferences): String =
        store[WalkingAid.focusDescription] ?: store[WalkingAid.watchlistTerms]

    /** Saves the user's words, dropping a cached translation of different words. */
    fun setFocusDescription(store: PlatformPreferences, description: String) {
        val normalized = description.trim().take(MAX_FOCUS_CHARS)
        val translatedOriginal = store[WalkingAid.focusTranslationOriginal]
        store[WalkingAid.focusDescription] = normalized
        if (translatedOriginal != normalized) {
            WalkingAid.focusTranslationOriginal.remove(store)
            WalkingAid.focusTranslationSource.remove(store)
            WalkingAid.focusTranslationEnglish.remove(store)
        }
    }

    fun cachedFocusTranslation(store: PlatformPreferences, original: String, source: String): String? {
        if (store[WalkingAid.focusTranslationOriginal] != original.trim().take(MAX_FOCUS_CHARS)) return null
        if (!store[WalkingAid.focusTranslationSource].equals(source, ignoreCase = true)) return null
        return store[WalkingAid.focusTranslationEnglish]?.takeIf { it.isNotBlank() }
    }

    fun setCachedFocusTranslation(store: PlatformPreferences, original: String, source: String, english: String) {
        val normalizedOriginal = original.trim().take(MAX_FOCUS_CHARS)
        store[WalkingAid.focusDescription] = normalizedOriginal
        store[WalkingAid.stateModelSource] = source
        store[WalkingAid.focusTranslationOriginal] = normalizedOriginal
        store[WalkingAid.focusTranslationSource] = source
        store[WalkingAid.focusTranslationEnglish] = english.trim()
    }

    /** The English focus the detector rules use: the cached translation, else the user's words. */
    fun modelFocusDescription(store: PlatformPreferences): String {
        val original = focusDescription(store).trim()
        return cachedFocusTranslation(store, original, store[WalkingAid.stateModelSource]) ?: original
    }
}
