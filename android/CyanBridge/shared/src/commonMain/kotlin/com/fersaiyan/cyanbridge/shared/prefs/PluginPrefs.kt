package com.fersaiyan.cyanbridge.shared.prefs

private const val DEFAULT_PLUGIN_CLOUD_MODEL = "deepseek/deepseek-v4-flash"
private const val WALKING_AID_CLOUD_MODEL = "cyanbridge/walking-aid-vision"

/** Community plugin switches and each native plugin's settings and history. */
object PluginPrefs {
    object Community : PrefsFile("community_plugins") {
        val taskerAssistantEnabled = boolean("tasker_assistant_enabled", false)
        /** Older name of [taskerAssistantEnabled]; its value is the default until that is saved. */
        val legacyGeminiChatGptImageAutomation = boolean("gemini_chatgpt_image_automation", false)
        val glassesTabShortcutPlugin = optionalString("glasses_tab_shortcut_plugin")
        /** `*` is the native plugin id. */
        val nativePluginEnabled = booleanFamily("native_plugin_enabled_*", false)
        /** Experimental Mentra miniapp hosting on the Plugins page (off until the user turns it on). */
        val mentraMiniappsEnabled = boolean("mentra_miniapps_enabled", false)
    }

    object VisualDiary : PrefsFile("visual_diary_prefs") {
        val enabled = boolean("enabled", false)
        val intervalMinutes = int("interval_minutes", 15).clamped(1, 240)
        val customPrompt = string("custom_prompt", "").prompt(1_500)
        val lastError = string("last_error", "")
    }

    object WalkingAid : PrefsFile("walking_aid_prefs") {
        val enabled = boolean("enabled", false)
        val videoMode = optionalString("video_mode")
        val mode = optionalString("mode")
        val speechRate = float("speech_rate", 1f).map(::normalizedRate, ::normalizedRate)
        val announcementPauseMs = long("announcement_pause_ms", 1_000L).clamped(0L, 5_000L)
        val captureIntervalSeconds = int("capture_interval_seconds", 5).clamped(2, 60)
        val imageDescriptionSource = string("image_description_source", "local")
        val imageDescriptionCloudModelId = string("image_description_cloud_model_id", WALKING_AID_CLOUD_MODEL)
        val depthEnabled = boolean("depth_enabled", true)
        val depthSource = string("depth_source", "cloud")
        val depthCloudModelId = string("depth_cloud_model_id", WALKING_AID_CLOUD_MODEL)
        val stateModelSource = string("state_model_source", "local")
        val ttsEnabled = boolean("tts_enabled", true)
        val safetyDisclaimerEnabled = boolean("safety_disclaimer_enabled", true)
        val imageHistoryMaxCount = int("image_history_max_count", 50).clamped(10, 200)
        val customPrompt = string("custom_prompt", "").prompt(1_500)
        val yoloModelType = string("yolo_model_type", "yolo11")
        /** Older comma-separated focus list; [focusDescription] replaces it. */
        val watchlistTerms = string("watchlist_terms", "")
        val focusDescription = optionalString("focus_description")
        val focusTranslationOriginal = optionalString("focus_translation_original")
        val focusTranslationSource = optionalString("focus_translation_source")
        val focusTranslationEnglish = optionalString("focus_translation_english")
        val thumbnailQualityLevel = int("thumbnail_quality_level", 3).clamped(0, 5)
        val photoSettleDelayMs = int("photo_settle_delay_ms", 250).clamped(0, 500)

        private fun normalizedRate(rate: Float) = if (rate.isFinite()) rate.coerceIn(0.5f, 2f) else 1f
    }

    object ErrandBrain : PrefsFile("errand_brain_prefs") {
        val enabled = boolean("enabled", false)
        val autoCreateTasks = boolean("auto_create_tasks", true)
        val voiceCommands = boolean("voice_commands", true)
        val reminderEnabled = boolean("reminder_enabled", true)
        val reminderIntervalMinutes = int("reminder_interval_minutes", 30).clamped(5, 120)
        val defaultPriority = string("default_priority", "medium")
        val defaultCategory = string("default_category", "personal")
        val cloudModelId = string("cloud_model_id", DEFAULT_PLUGIN_CLOUD_MODEL)
        val maxHistory = int("max_history", 200).clamped(50, 1000)
        val customPrompt = string("custom_prompt", "").prompt(1_000)
    }

    object HandsFreeTranslator : PrefsFile("hands_free_translator_prefs") {
        val enabled = boolean("enabled", false)
        val sourceLanguage = string("source_language", "en")
        val targetLanguage = string("target_language", "es")
        val translationMode = string("translation_mode", "real_time")
        val autoDetect = boolean("auto_detect", true)
        val speakTranslation = boolean("speak_translation", true)
        val showConfidence = boolean("show_confidence", false)
        val maxHistory = int("max_history", 100).clamped(50, 500)
        val cloudModelId = string("cloud_model_id", DEFAULT_PLUGIN_CLOUD_MODEL)
        val customPrompt = string("custom_prompt", "").prompt(1_000)
    }

    object LiveCaptionRelay : PrefsFile("live_caption_relay_prefs") {
        val enabled = boolean("enabled", false)
        val sourceLanguage = string("source_language", "en")
        val targetLanguage = string("target_language", "es")
        val translationEnabled = boolean("translation_enabled", false)
        val fontSize = int("font_size", 24).clamped(12, 48)
        val captionPosition = string("caption_position", "bottom")
        val showConfidence = boolean("show_confidence", false)
        val maxHistory = int("max_history", 100).clamped(50, 500)
        val cloudModelId = string("cloud_model_id", DEFAULT_PLUGIN_CLOUD_MODEL)
        val customPrompt = string("custom_prompt", "").prompt(1_000)
    }

    object MeetingSparkNotes : PrefsFile("meeting_spark_notes_prefs") {
        val enabled = boolean("enabled", false)
        val autoRecord = boolean("auto_record", true)
        val summaryStyle = string("summary_style", "concise")
        val includeParticipants = boolean("include_participants", true)
        val includeActionItems = boolean("include_action_items", true)
        val includeTimestamps = boolean("include_timestamps", true)
        val cloudModelId = string("cloud_model_id", DEFAULT_PLUGIN_CLOUD_MODEL)
        val maxHistory = int("max_history", 50).clamped(10, 200)
        val customPrompt = string("custom_prompt", "").prompt(1_500)
        val lastSummarizedSessionId = long("last_summarized_session_id", 0L)
        val lastSavedNoteId = long("last_saved_note_id", 0L)
    }

    object ErrandBrainStore : PrefsFile("errand_brain_store") {
        val tasksJson = string("tasks_json", "[]")
        val remindersJson = string("reminders_json", "[]")
    }

    object HandsFreeTranslatorStore : PrefsFile("hands_free_translator_store") {
        val translationsJson = string("translations_json", "[]")
        val presetsJson = string("presets_json", "[]")
    }

    object LiveCaptionRelayStore : PrefsFile("live_caption_relay_store") {
        val captionsJson = string("captions_json", "[]")
    }

    object MeetingSparkNotesStore : PrefsFile("meeting_spark_notes_store") {
        val summariesJson = string("summaries_json", "[]")
    }

    object WalkingAidImageStore : PrefsFile("walking_aid_image_store") {
        val historyJson = string("history_json", "[]")
    }

    val files: List<PrefsFile> = listOf(
        Community, VisualDiary, WalkingAid, ErrandBrain, HandsFreeTranslator, LiveCaptionRelay, MeetingSparkNotes,
        ErrandBrainStore, HandsFreeTranslatorStore, LiveCaptionRelayStore, MeetingSparkNotesStore, WalkingAidImageStore,
    )
}
