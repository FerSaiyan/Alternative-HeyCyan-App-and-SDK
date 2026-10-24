package com.fersaiyan.cyanbridge.shared.prefs

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Installed apps hold these names: a renamed file or key, or a changed type or default,
 * silently resets or crashes users' saved settings. Change this list only for a new key.
 */
class PrefsInventoryTest {
    @Test
    fun everyFileKeyTypeAndDefaultIsPinned() {
        assertEquals(PINNED, AllPrefs.files.flatMap(::describe).joinToString("\n"))
    }

    @Test
    fun fileNamesAndKeysAreDeclaredOnce() {
        assertEquals(AllPrefs.files.map { it.name }.distinct(), AllPrefs.files.map { it.name })
        AllPrefs.files.forEach { file ->
            val keys = file.entries.map { (it as? Pref<*>)?.key ?: (it as PrefFamily<*>).pattern }
            assertEquals(keys.distinct(), keys, file.name)
        }
    }

    private fun describe(file: PrefsFile): List<String> {
        val name = file.name + if (file.secure) " (secure)" else ""
        if (file.entries.isEmpty()) return listOf("$name: no keys")
        return file.entries.map { entry ->
            when (entry) {
                is Pref<*> -> "$name/${entry.key} ${entry.type} ${show(entry.default)}" +
                    (entry.iosLocation?.let { " ios=$it" } ?: "")
                is PrefFamily<*> -> "$name/${entry.pattern} ${entry.type} ${show(entry.default)}"
                else -> error("Unknown entry $entry")
            }
        }
    }

    private fun show(value: Any?): String = if (value is String) "\"$value\"" else value.toString()

    private companion object {
        val PINNED = """
        cyanbridge_prefs/onboarding_completed BOOLEAN false
        cyanbridge_prefs/onboarding_model_id STRING null
        cyanbridge_prefs/battery_opt_guide_completed BOOLEAN false
        cyanbridge_prefs/battery_opt_guide_suppress BOOLEAN false
        cyanbridge_prefs/battery_opt_guide_remind_after LONG 0
        cyanbridge_onboarding/welcome_done BOOLEAN false
        appearance_settings/theme_mode STRING "SYSTEM" ios=cyanbridge_appearance/theme_mode
        appearance_settings/accent_profile STRING "cyan" ios=cyanbridge_appearance/accent_profile
        appearance_settings/dynamic_color BOOLEAN false ios=cyanbridge_appearance/dynamic_color
        appearance_settings/high_contrast BOOLEAN false ios=cyanbridge_appearance/high_contrast
        chat_appearance_prefs/user_bubble_color INT null
        chat_appearance_prefs/assistant_bubble_color INT null
        chat_appearance_prefs/chat_wallpaper_uri STRING ""
        cyanbridge_chat_appearance/user_bubble INT -1
        cyanbridge_chat_appearance/assistant_bubble INT -1
        cyanbridge_chat_appearance/wallpaper BOOLEAN false
        cyanbridge_note_tags/* STRING ""
        cyanbridge_shared_settings/provider_type STRING "PRO_SUBSCRIPTION"
        cyanbridge_shared_settings/memory_mode STRING "PRIVATE_LOCAL"
        cyanbridge_shared_settings/sync_explicit BOOLEAN true
        cyanbridge_shared_settings/sync_daily BOOLEAN true
        cyanbridge_shared_settings/sync_ocr BOOLEAN false
        cyanbridge_shared_settings/sync_derived BOOLEAN false
        cyanbridge_shared_settings/vault_locked BOOLEAN false
        cyanbridge_shared_settings/transcript_storage BOOLEAN true
        cyanbridge_shared_settings/redact_names BOOLEAN true
        cyanbridge_shared_settings/full_transcript_exports BOOLEAN false
        cyanbridge_shared_settings/default_image_question STRING ""
        cyanbridge_product_analytics/sharing_enabled BOOLEAN true
        cyanbridge_product_analytics/sharing_choice_made BOOLEAN false
        cyanbridge_product_analytics/last_heartbeat_day STRING ""
        cyanbridge_product_analytics/acquisition_complete BOOLEAN false
        cyanbridge_product_analytics/pending_acquisition STRING ""
        device_profile/last_mac STRING null
        device_profile/last_name STRING null
        device_profile/last_detected_class STRING null
        device_profile/last_selected_class STRING null
        device_profile/last_user_overridden BOOLEAN false
        device_profile/override_* STRING null
        version_check/last_version_check_time LONG 0
        version_check/reminded_version STRING ""
        relay_server/base_url STRING null
        meta_permission_requests/* BOOLEAN false
        settings_sections/* BOOLEAN true
        local_models_sections/section_* BOOLEAN false
        ai_provider_prefs/provider STRING "cli_relay"
        ai_provider_prefs/relay_base_url STRING "https://cyanbridge.vercel.app"
        ai_provider_prefs/relay_backend STRING "gemini"
        ai_wake_word/route STRING null ios=cyanbridge_ios_glasses/ai_wake_word_route
        image_questions/default_question STRING null
        image_questions/thumbnail_quality INT 4 ios=cyanbridge_ios_glasses/image_thumbnail_quality
        image_questions/legacy_vision_profile_migrated BOOLEAN false
        vision_profile/custom_instructions STRING null
        app_language/selected_language STRING null ios=cyanbridge_app_language/selected_language
        gemini_live/pro_economy BOOLEAN true
        gemini_live/live_image_delay INT 0
        transcription_endpoint/endpoint_url STRING null
        transcription_endpoint/api_key STRING null
        remote_openai_prefs/base_url STRING "" ios=cyanbridge_remote_openai/base_url
        remote_openai_prefs/api_key STRING ""
        remote_openai_prefs/model STRING "" ios=cyanbridge_remote_openai/model
        remote_openai_prefs/enabled BOOLEAN false ios=cyanbridge_remote_openai/enabled
        remote_openai_prefs/studio_bridge_enabled BOOLEAN false
        remote_openai_secrets (secure)/api_key STRING ""
        local_agent_prefs/provider_type STRING null
        local_agent_prefs/glasses_assistant_mode STRING null ios=cyanbridge_ios_glasses/assistant_mode
        local_agent_prefs/require_confirmation BOOLEAN true
        local_agent_prefs/max_steps INT 8
        local_agent_prefs/automation_enabled BOOLEAN false
        local_agent_prefs/embedding_decision_enabled BOOLEAN false
        local_agent_prefs/embedding_model_path STRING ""
        local_agent_prefs/embedding_margin_threshold FLOAT 0.1
        local_agent_prefs/auto_capture_enabled BOOLEAN false
        local_agent_prefs/tasker_auto_diary_enabled BOOLEAN false
        local_agent_prefs/capture_interval_min INT 10
        local_agent_prefs/capture_blacklist STRING_SET null
        local_agent_prefs/hide_system_apps BOOLEAN true
        local_agent_prefs/daily_facts_reminder_enabled BOOLEAN true
        local_agent_prefs/daily_summary_auto_refresh_hours INT 3
        local_agent_prefs/status STRING "Unknown"
        local_agent_prefs/last_error STRING "(none)"
        local_agent_prefs/last_approval_voice_prompt STRING ""
        local_agent_prefs/last_approval_voice_reply STRING ""
        local_agent_prefs/last_context_injection_debug STRING ""
        local_agent_prefs/last_context_injection_at_ms LONG 0
        local_agent_prefs/require_action_confirmation BOOLEAN true
        local_agent_prefs/auto_execute_low_risk BOOLEAN true
        local_agent_prefs/whatsapp_notification_read_aloud BOOLEAN false
        local_agent_prefs/telegram_remote_enabled BOOLEAN false
        local_agent_prefs/telegram_allowed_chat_id STRING ""
        local_agent_prefs/telegram_update_offset LONG 0
        local_agent_prefs/telegram_status STRING "Disabled"
        local_agent_prefs/telegram_last_error STRING "(none)"
        local_agent_prefs/screenshot_planning_enabled BOOLEAN false
        local_agent_prefs/remote_screenshot_upload_enabled BOOLEAN false
        local_agent_prefs/screenshot_status STRING "Text-only planning"
        local_agent_prefs/shizuku_fallback_enabled BOOLEAN false
        local_agent_prefs/shizuku_status STRING "Disabled"
        local_agent_prefs/enabled BOOLEAN false
        local_agent_prefs/model_id STRING null
        local_agent_secrets (secure)/telegram_bot_token STRING ""
        local_agent_hil_shadow/youtube_shadow_enabled BOOLEAN false
        local_agent_task_history/entries_json STRING "[]"
        local_agent_skills/skills_json STRING "[]"
        local_models_prefs/model_path STRING ""
        local_models_prefs/use_gpu BOOLEAN false
        local_models_prefs/max_tokens INT 4096
        local_models_prefs/temperature FLOAT 0.7
        local_models_prefs/last_benchmark STRING ""
        local_model_settings/settings_by_model STRING "{}"
        local_model_settings/hf_token STRING ""
        local_models_registry/installed_models STRING "[]"
        local_models_registry/selected_model_id STRING null
        local_model_projectors/* STRING null
        local_model_mtp_settings/*_mode STRING null
        local_model_mtp_settings/*_signature STRING null
        local_model_mtp_settings/*_off_tps LONG 0
        local_model_mtp_settings/*_on_tps LONG null
        local_model_mtp_settings/*_off_ttft LONG -1
        local_model_mtp_settings/*_on_ttft LONG -1
        local_model_mtp_settings/*_recommend BOOLEAN true
        local_model_mtp_settings/*_on_failure STRING null
        pro_subscription_prefs/is_subscribed BOOLEAN false
        pro_subscription_prefs/plan STRING null
        pro_subscription_prefs/expires_at LONG 0
        pro_subscription_prefs/purchase_token STRING null
        pro_subscription_prefs/provider STRING "mock"
        pro_subscription_prefs/last_verified_at LONG 0
        pro_subscription_ai_prefs/requests_model STRING "auto"
        pro_subscription_ai_prefs/questions_model STRING null
        pro_subscription_ai_prefs/tasks_model STRING "auto"
        pro_subscription_ai_prefs/system_prompt STRING null
        pro_subscription_settings/cloud_sync BOOLEAN true
        pro_subscription_settings/priority_support BOOLEAN true
        pro_subscription_settings/plugin_rewards BOOLEAN true
        pro_subscription_settings/early_access_devices BOOLEAN true
        pro_subscription_settings/backup_frequency_idx INT 1
        pro_subscription_settings/support_channel_idx INT 0
        pro_subscription_server_prefs/verify_url STRING ""
        pro_subscription_server_prefs/api_token STRING ""
        pro_subscription_server_prefs/account_email STRING ""
        pro_subscription_server_prefs/verified_account_email STRING ""
        pro_subscription_server_prefs/legacy_credentials_cleared BOOLEAN false
        pro_subscription_server_secrets (secure)/api_token STRING ""
        pro_subscription_server_secrets (secure)/web_callback_result STRING ""
        pro_subscription_server_secrets (secure)/web_callback_purpose STRING ""
        pro_subscription_server_secrets (secure)/web_callback_expires_at_ms LONG 0
        pending_legacy_checkout_prefs: no keys
        pending_donation_prefs/invoice_url STRING ""
        pending_donation_prefs/status_url STRING ""
        pending_donation_prefs/amount STRING ""
        pending_donation_prefs/awaiting_return BOOLEAN false
        pending_donation_prefs/api_token STRING null
        pending_donation_prefs/email STRING null
        pending_donation_prefs/subscription_id STRING null
        pending_donation_secrets (secure)/invoice_url STRING ""
        pending_donation_secrets (secure)/status_url STRING ""
        pending_donation_secrets (secure)/amount STRING ""
        pro_feature_feedback/installation_id STRING ""
        memory_mode_prefs/mode STRING "PRIVATE_LOCAL"
        memory_mode_prefs/ocr_retention_days INT 7
        memory_mode_prefs/ocr_capture_enabled BOOLEAN true
        memory_mode_prefs/sync_explicit BOOLEAN true
        memory_mode_prefs/sync_daily BOOLEAN true
        memory_mode_prefs/sync_ocr BOOLEAN false
        memory_mode_prefs/sync_derived BOOLEAN false
        memory_vault_lock_state/locked BOOLEAN false
        memory_vault_lock_state/locked_at LONG 0
        memory_vault_lock_state/last_unlocked_at LONG 0
        memory_vault_keys/master_device_wrapped STRING null
        memory_vault_keys/passphrase_salt STRING null
        memory_vault_keys/master_pass_wrapped STRING null
        memory_vault_keys/requires_passphrase BOOLEAN false
        privacy_settings/transcript_storage_enabled BOOLEAN true
        privacy_settings/redact_names_enabled BOOLEAN true
        privacy_settings/include_full_transcription_in_exports BOOLEAN false
        cyanbridge_transcripts: no keys
        chat_memory_prefs/auto_save_daily_facts BOOLEAN true
        chat_memory_prefs/extract_user_fact_candidates BOOLEAN true
        chat_memory_prefs/last_extract_at_ms LONG 0
        daily_summary_prefs/last_generated_ts_* LONG 0
        daily_summary_prefs/last_capture_processed_ts_* LONG 0
        daily_summary_run_history/runs_json STRING "[]"
        daily_summary_bullet_run_history/runs_json STRING "[]"
        daily_bullets_settings/custom_review_prompt STRING ""
        daily_bullets_settings/custom_summary_prompt STRING ""
        daily_bullets_settings/custom_bullet_prompt STRING ""
        daily_bullets_settings/max_tokens_per_bullet INT 0
        daily_facts_review_threads/thread_map_json STRING "{}"
        nightly_enrichment/*_status STRING "COLLECTING"
        nightly_enrichment/*_count INT 0
        nightly_enrichment/*_notified BOOLEAN false
        knowledge_integrations/obsidian_tree_uri STRING null
        knowledge_integrations/obsidian_root_document_id STRING null
        knowledge_integrations/obsidian_display_name STRING null
        knowledge_integrations/import_inbox_tree_uri STRING null
        knowledge_integrations/auto_sync BOOLEAN true
        knowledge_integrations/last_sync_ms LONG 0
        knowledge_integrations/last_summary STRING ""
        knowledge_integrations/allow_cloud_enrichment BOOLEAN false
        glasses_media/video_recording BOOLEAN false
        auto_audio_capture_prefs/enabled BOOLEAN false
        auto_audio_capture_prefs/successful_loops INT 0
        auto_audio_capture_prefs/loops_per_sync INT 12
        auto_audio_capture_prefs/visual_notes_enabled BOOLEAN false
        auto_audio_capture_prefs/speech_extend_enabled BOOLEAN true
        auto_audio_capture_prefs/paused_meeting BOOLEAN false
        auto_audio_capture_prefs/paused_video BOOLEAN false
        auto_audio_capture_prefs/pause_until_ms LONG 0
        auto_audio_capture_prefs/last_pause_reason STRING ""
        meeting_capture/is_recording BOOLEAN false
        meeting_capture/start_at_ms LONG null
        meeting_capture/source STRING null
        meeting_capture/audio_path STRING null
        meeting_capture/device_class STRING null
        media_sync_warnings/official_heycyan_warning_suppressed BOOLEAN false
        adaptive_p2p_sync_profiles/*.preferred_route STRING null
        adaptive_p2p_sync_profiles/*.preferred_warmup_ms LONG null
        adaptive_p2p_sync_profiles/*.candidate_route STRING null
        adaptive_p2p_sync_profiles/*.candidate_warmup_ms LONG null
        adaptive_p2p_sync_profiles/*.candidate_successes INT 0
        adaptive_p2p_sync_profiles/*.completed_syncs INT 0
        adaptive_p2p_sync_profiles/*.consecutive_failures INT 0
        moyoung_w620/file_wifi_ssid STRING "Glass-01"
        moyoung_w620/file_wifi_password STRING "12345678"
        external_image_automation/session STRING null
        external_image_automation/token STRING null
        external_image_automation/path STRING null
        external_image_automation/uri STRING null
        external_image_automation/question STRING ""
        external_image_automation/source STRING null
        external_image_automation/stage STRING null
        external_image_automation/error STRING null
        external_image_automation/follow_up_prompt_shown BOOLEAN false
        tasker_image_profile/target STRING null
        tasker_image_profile/version STRING null
        tasker_image_profile/pending_token STRING null
        tasker_image_profile/verified_at_* LONG 0
        tasker_image_profile/version_* STRING null
        community_plugins/tasker_assistant_enabled BOOLEAN false
        community_plugins/gemini_chatgpt_image_automation BOOLEAN false
        community_plugins/glasses_tab_shortcut_plugin STRING null
        community_plugins/native_plugin_enabled_* BOOLEAN false
        visual_diary_prefs/enabled BOOLEAN false
        visual_diary_prefs/interval_minutes INT 15
        visual_diary_prefs/custom_prompt STRING ""
        visual_diary_prefs/last_error STRING ""
        walking_aid_prefs/enabled BOOLEAN false
        walking_aid_prefs/video_mode STRING null
        walking_aid_prefs/mode STRING null
        walking_aid_prefs/speech_rate FLOAT 1.0
        walking_aid_prefs/announcement_pause_ms LONG 1000
        walking_aid_prefs/capture_interval_seconds INT 5
        walking_aid_prefs/image_description_source STRING "local"
        walking_aid_prefs/image_description_cloud_model_id STRING "cyanbridge/walking-aid-vision"
        walking_aid_prefs/depth_enabled BOOLEAN true
        walking_aid_prefs/depth_source STRING "cloud"
        walking_aid_prefs/depth_cloud_model_id STRING "cyanbridge/walking-aid-vision"
        walking_aid_prefs/state_model_source STRING "local"
        walking_aid_prefs/tts_enabled BOOLEAN true
        walking_aid_prefs/safety_disclaimer_enabled BOOLEAN true
        walking_aid_prefs/image_history_max_count INT 50
        walking_aid_prefs/custom_prompt STRING ""
        walking_aid_prefs/yolo_model_type STRING "yolo11"
        walking_aid_prefs/watchlist_terms STRING ""
        walking_aid_prefs/focus_description STRING null
        walking_aid_prefs/focus_translation_original STRING null
        walking_aid_prefs/focus_translation_source STRING null
        walking_aid_prefs/focus_translation_english STRING null
        walking_aid_prefs/thumbnail_quality_level INT 3
        walking_aid_prefs/photo_settle_delay_ms INT 250
        errand_brain_prefs/enabled BOOLEAN false
        errand_brain_prefs/auto_create_tasks BOOLEAN true
        errand_brain_prefs/voice_commands BOOLEAN true
        errand_brain_prefs/reminder_enabled BOOLEAN true
        errand_brain_prefs/reminder_interval_minutes INT 30
        errand_brain_prefs/default_priority STRING "medium"
        errand_brain_prefs/default_category STRING "personal"
        errand_brain_prefs/cloud_model_id STRING "deepseek/deepseek-v4-flash"
        errand_brain_prefs/max_history INT 200
        errand_brain_prefs/custom_prompt STRING ""
        hands_free_translator_prefs/enabled BOOLEAN false
        hands_free_translator_prefs/source_language STRING "en"
        hands_free_translator_prefs/target_language STRING "es"
        hands_free_translator_prefs/translation_mode STRING "real_time"
        hands_free_translator_prefs/auto_detect BOOLEAN true
        hands_free_translator_prefs/speak_translation BOOLEAN true
        hands_free_translator_prefs/show_confidence BOOLEAN false
        hands_free_translator_prefs/max_history INT 100
        hands_free_translator_prefs/cloud_model_id STRING "deepseek/deepseek-v4-flash"
        hands_free_translator_prefs/custom_prompt STRING ""
        live_caption_relay_prefs/enabled BOOLEAN false
        live_caption_relay_prefs/source_language STRING "en"
        live_caption_relay_prefs/target_language STRING "es"
        live_caption_relay_prefs/translation_enabled BOOLEAN false
        live_caption_relay_prefs/font_size INT 24
        live_caption_relay_prefs/caption_position STRING "bottom"
        live_caption_relay_prefs/show_confidence BOOLEAN false
        live_caption_relay_prefs/max_history INT 100
        live_caption_relay_prefs/cloud_model_id STRING "deepseek/deepseek-v4-flash"
        live_caption_relay_prefs/custom_prompt STRING ""
        meeting_spark_notes_prefs/enabled BOOLEAN false
        meeting_spark_notes_prefs/auto_record BOOLEAN true
        meeting_spark_notes_prefs/summary_style STRING "concise"
        meeting_spark_notes_prefs/include_participants BOOLEAN true
        meeting_spark_notes_prefs/include_action_items BOOLEAN true
        meeting_spark_notes_prefs/include_timestamps BOOLEAN true
        meeting_spark_notes_prefs/cloud_model_id STRING "deepseek/deepseek-v4-flash"
        meeting_spark_notes_prefs/max_history INT 50
        meeting_spark_notes_prefs/custom_prompt STRING ""
        meeting_spark_notes_prefs/last_summarized_session_id LONG 0
        meeting_spark_notes_prefs/last_saved_note_id LONG 0
        errand_brain_store/tasks_json STRING "[]"
        errand_brain_store/reminders_json STRING "[]"
        hands_free_translator_store/translations_json STRING "[]"
        hands_free_translator_store/presets_json STRING "[]"
        live_caption_relay_store/captions_json STRING "[]"
        meeting_spark_notes_store/summaries_json STRING "[]"
        walking_aid_image_store/history_json STRING "[]"
        """.trimIndent()
    }
}
