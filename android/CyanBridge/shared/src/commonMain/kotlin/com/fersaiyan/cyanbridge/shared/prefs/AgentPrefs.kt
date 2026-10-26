package com.fersaiyan.cyanbridge.shared.prefs

/** Local agent, on-device models and their run state. */
object AgentPrefs {
    /** Android facade: agent/LocalAgentPrefs. */
    object LocalAgent : PrefsFile("local_agent_prefs") {
        val providerType = optionalString("provider_type")
        /** GlassesAssistantMode name, same on both apps; unset means the custom AI provider (local or Pro models). */
        val glassesAssistantMode = string(
            "glasses_assistant_mode",
            "CUSTOM_AI_PROVIDER",
            iosFile = IOS_GLASSES_SUITE,
            iosKey = "assistant_mode",
        )
        val requireConfirmation = boolean("require_confirmation", true)
        val maxSteps = int("max_steps", 8).clamped(1, 200)
        val automationEnabled = boolean("automation_enabled", false)
        val embeddingDecisionEnabled = boolean("embedding_decision_enabled", false)
        val embeddingModelPath = string("embedding_model_path", "")
        val embeddingMarginThreshold = float("embedding_margin_threshold", 0.10f)

        /** Legacy Accessibility capture switch; Tasker AutoDiary uses [taskerAutoDiaryEnabled]. */
        val autoCaptureEnabled = boolean("auto_capture_enabled", false)
        val taskerAutoDiaryEnabled = boolean("tasker_auto_diary_enabled", false)
        val captureIntervalMin = int("capture_interval_min", 10).clamped(1, 24 * 60)
        val captureBlacklist = optionalStringSet("capture_blacklist")
        val hideSystemApps = boolean("hide_system_apps", true)
        val dailyFactsReminderEnabled = boolean("daily_facts_reminder_enabled", true)
        val dailySummaryAutoRefreshHours = int("daily_summary_auto_refresh_hours", 3).clamped(1, 24)

        val status = string("status", "Unknown")
        val lastError = string("last_error", "(none)")
        val lastApprovalVoicePrompt = string("last_approval_voice_prompt", "")
        val lastApprovalVoiceReply = string("last_approval_voice_reply", "")
        val lastContextInjectionDebug = string("last_context_injection_debug", "")
        val lastContextInjectionAtMs = long("last_context_injection_at_ms", 0L)
        val requireActionConfirmation = boolean("require_action_confirmation", true)
        val autoExecuteLowRisk = boolean("auto_execute_low_risk", true)
        val whatsAppNotificationReadAloud = boolean("whatsapp_notification_read_aloud", false)

        /** Last planner media status; screenshots are opted in from the Tasker profile. */
        val screenshotStatus = string("screenshot_status", "Text-only planning")
        val shizukuFallbackEnabled = boolean("shizuku_fallback_enabled", false)
        val shizukuStatus = string("shizuku_status", "Disabled")

        /** On-device runtime toggle and model id. */
        val enabled = boolean("enabled", false)
        val modelId = optionalString("model_id")
    }

    object LocalAgentSecrets : PrefsFile("local_agent_secrets", secure = true)

    /** Debug-only trace switch read by LocalAgentShadowTrace. */
    object ShadowTrace : PrefsFile("local_agent_hil_shadow") {
        val youtubeShadowEnabled = boolean("youtube_shadow_enabled", false)
    }

    object TaskHistory : PrefsFile("local_agent_task_history") {
        val entriesJson = string("entries_json", "[]")
    }

    object Skills : PrefsFile("local_agent_skills") {
        val skillsJson = string("skills_json", "[]")
    }

    object LocalModels : PrefsFile("local_models_prefs") {
        val modelPath = string("model_path", "")
        /** Also the fallback compute backend for models saved before per-model settings. */
        val useGpu = boolean("use_gpu", false)
        val maxTokens = int("max_tokens", 4096)
        val temperature = float("temperature", 0.7f)
        val lastBenchmark = string("last_benchmark", "")
    }

    object LocalModelSettings : PrefsFile("local_model_settings") {
        val settingsByModel = string("settings_by_model", "{}")
        val huggingFaceToken = string("hf_token", "")
    }

    object LocalModelRegistry : PrefsFile("local_models_registry") {
        val installedModels = string("installed_models", "[]")
        val selectedModelId = optionalString("selected_model_id")
    }

    /** Projector GGUF path per installed model id. */
    object LocalModelProjectors : PrefsFile("local_model_projectors") {
        val pathByModelId = optionalStringFamily("*")
    }

    /** `*` is the sanitized model id; benchmark keys use `<model>_<backend>_benchmark`. */
    object LocalModelMtp : PrefsFile("local_model_mtp_settings") {
        val mode = optionalStringFamily("*_mode")
        val benchmarkSignature = optionalStringFamily("*_signature")
        val benchmarkOffTps = longFamily("*_off_tps", 0L)
        val benchmarkOnTps = optionalLongFamily("*_on_tps")
        val benchmarkOffTtft = longFamily("*_off_ttft", -1L)
        val benchmarkOnTtft = longFamily("*_on_ttft", -1L)
        val benchmarkRecommend = booleanFamily("*_recommend", true)
        val benchmarkOnFailure = optionalStringFamily("*_on_failure")
    }

    val files: List<PrefsFile> = listOf(
        LocalAgent, LocalAgentSecrets, ShadowTrace, TaskHistory, Skills,
        LocalModels, LocalModelSettings, LocalModelRegistry, LocalModelProjectors, LocalModelMtp,
    )
}
