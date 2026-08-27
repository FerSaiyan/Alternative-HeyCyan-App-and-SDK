package com.fersaiyan.cyanbridge.ai.vision

import android.content.Context
import com.fersaiyan.cyanbridge.ai.image.ImageThumbnailQuality
import com.fersaiyan.cyanbridge.ui.localization.AppLanguagePreferences
import java.util.Locale

object ImageQuestionPreferences {
    private const val PREFS = "image_questions"
    private const val KEY_DEFAULT_QUESTION = "default_question"
    private const val KEY_THUMBNAIL_QUALITY = "thumbnail_quality"
    private const val KEY_LEGACY_MIGRATION_COMPLETE = "legacy_vision_profile_migrated"
    private const val LEGACY_PREFS = "vision_profile"
    private const val LEGACY_CUSTOM_INSTRUCTIONS = "custom_instructions"
    private const val MAX_DEFAULT_QUESTION_CHARS = 1_500

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context): ImageQuestionSettings {
        val languageTag = currentAppLanguageTag(context)
        val storedQuestion = migrateLegacyQuestionIfNeeded(context)
        return ImageQuestionSettings(
            appLanguageTag = languageTag,
            defaultQuestion = storedQuestion ?: ImageQuestionDefaults.questionForLanguage(languageTag),
            usesBuiltInDefault = storedQuestion == null,
        )
    }

    fun setDefaultQuestion(context: Context, question: String) {
        // Settings persist on every keystroke. Preserve a trailing space while the user is
        // typing; the prompt resolver trims the completed question before it reaches a model.
        val normalized = question.take(MAX_DEFAULT_QUESTION_CHARS)
        preferences(context).edit()
            .putString(KEY_DEFAULT_QUESTION, normalized)
            .putBoolean(KEY_LEGACY_MIGRATION_COMPLETE, true)
            .apply()
    }

    fun resetDefaultQuestion(context: Context) {
        preferences(context).edit()
            .remove(KEY_DEFAULT_QUESTION)
            // Do not re-import the old profile after a user explicitly resets this field.
            .putBoolean(KEY_LEGACY_MIGRATION_COMPLETE, true)
            .apply()
    }

    fun thumbnailQuality(context: Context): ImageThumbnailQuality {
        val prefs = preferences(context)
        if (!prefs.contains(KEY_THUMBNAIL_QUALITY)) {
            prefs.edit()
                .putInt(KEY_THUMBNAIL_QUALITY, ImageThumbnailQuality.CLEARER.sdkValue)
                .apply()
        }
        val storedValue = prefs.getInt(
            KEY_THUMBNAIL_QUALITY,
            ImageThumbnailQuality.CLEARER.sdkValue,
        )
        return ImageThumbnailQuality.entries.firstOrNull { it.sdkValue == storedValue }
            ?: ImageThumbnailQuality.CLEARER
    }

    fun setThumbnailQuality(context: Context, sdkValue: Int): ImageThumbnailQuality {
        val quality = ImageThumbnailQuality.entries.firstOrNull { it.sdkValue == sdkValue }
            ?: ImageThumbnailQuality.CLEARER
        preferences(context).edit()
            .putInt(KEY_THUMBNAIL_QUALITY, quality.sdkValue)
            .apply()
        return quality
    }

    private fun migrateLegacyQuestionIfNeeded(context: Context): String? {
        val prefs = preferences(context)
        if (prefs.contains(KEY_DEFAULT_QUESTION)) {
            return prefs.getString(KEY_DEFAULT_QUESTION, "")
                ?.take(MAX_DEFAULT_QUESTION_CHARS)
        }
        if (prefs.getBoolean(KEY_LEGACY_MIGRATION_COMPLETE, false)) return null

        val legacy = context.applicationContext
            .getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .getString(LEGACY_CUSTOM_INSTRUCTIONS, null)
            ?.trim()
            ?.take(MAX_DEFAULT_QUESTION_CHARS)
            ?.takeIf { it.isNotBlank() }

        prefs.edit()
            .putBoolean(KEY_LEGACY_MIGRATION_COMPLETE, true)
            .apply {
                if (legacy != null) putString(KEY_DEFAULT_QUESTION, legacy)
            }
            .apply()

        // Keep the legacy value readable until this migration has been persisted.
        return legacy
    }

    private fun currentAppLanguageTag(context: Context): String {
        val selectedTag = AppLanguagePreferences.selected(context).languageTag
        if (selectedTag.isNotBlank()) return selectedTag
        return context.resources.configuration.locales[0]
            ?.toLanguageTag()
            ?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().toLanguageTag()
    }
}
