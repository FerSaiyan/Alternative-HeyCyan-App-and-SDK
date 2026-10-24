package com.fersaiyan.cyanbridge.shared.prefs

import com.fersaiyan.cyanbridge.shared.settings.MemoryPrivacyMode

/** Memory vault, privacy, daily facts and summaries, knowledge integrations. */
object MemoryPrefs {
    object MemoryMode : PrefsFile("memory_mode_prefs") {
        val mode = string("mode", MemoryPrivacyMode.PRIVATE_LOCAL.name).map(MemoryPrivacyMode::fromRaw) { it.name }
        val ocrRetentionDays = int("ocr_retention_days", 7).clamped(1, 365)
        val ocrCaptureEnabled = boolean("ocr_capture_enabled", true)
        val syncExplicit = boolean("sync_explicit", true)
        val syncDaily = boolean("sync_daily", true)
        val syncOcr = boolean("sync_ocr", false)
        val syncDerived = boolean("sync_derived", false)
    }

    object VaultLock : PrefsFile("memory_vault_lock_state") {
        val locked = boolean("locked", false)
        val lockedAt = long("locked_at", 0L)
        val lastUnlockedAt = long("last_unlocked_at", 0L)
    }

    /** Master key blobs, already wrapped by the Keystore or a passphrase. */
    object VaultKeys : PrefsFile("memory_vault_keys") {
        val masterDeviceWrapped = optionalString("master_device_wrapped")
        val passphraseSalt = optionalString("passphrase_salt")
        val masterPassWrapped = optionalString("master_pass_wrapped")
        val requiresPassphrase = boolean("requires_passphrase", false)
    }

    object Privacy : PrefsFile("privacy_settings") {
        val transcriptStorageEnabled = boolean("transcript_storage_enabled", true)
        val redactNamesEnabled = boolean("redact_names_enabled", true)
        val includeFullTranscriptionInExports = boolean("include_full_transcription_in_exports", false)
    }

    /** Transcripts of old builds; only ever cleared. */
    object LegacyTranscripts : PrefsFile("cyanbridge_transcripts")

    object ChatMemory : PrefsFile("chat_memory_prefs") {
        val autoSaveDailyFacts = boolean("auto_save_daily_facts", true)
        val extractUserFactCandidates = boolean("extract_user_fact_candidates", true)
        val lastExtractAtMs = long("last_extract_at_ms", 0L)
    }

    /** `*` is the summary date. */
    object DailySummary : PrefsFile("daily_summary_prefs") {
        val lastGeneratedAtMs = longFamily("last_generated_ts_*", 0L)
        val lastCaptureProcessedAtMs = longFamily("last_capture_processed_ts_*", 0L)
    }

    object DailySummaryRuns : PrefsFile("daily_summary_run_history") {
        val runsJson = string("runs_json", "[]")
    }

    object DailySummaryBulletRuns : PrefsFile("daily_summary_bullet_run_history") {
        val runsJson = string("runs_json", "[]")
    }

    object DailyBullets : PrefsFile("daily_bullets_settings") {
        val customReviewPrompt = string("custom_review_prompt", "")
        val customSummaryPrompt = string("custom_summary_prompt", "")
        val customBulletPrompt = string("custom_bullet_prompt", "")
        val maxTokensPerBullet = int("max_tokens_per_bullet", 0)
    }

    object DailyFactsReviewThreads : PrefsFile("daily_facts_review_threads") {
        val threadMapJson = string("thread_map_json", "{}")
    }

    /** `*` is the enrichment date. */
    object NightlyEnrichment : PrefsFile("nightly_enrichment") {
        val status = stringFamily("*_status", "COLLECTING")
        val count = intFamily("*_count", 0)
        val notified = booleanFamily("*_notified", false)
    }

    object Knowledge : PrefsFile("knowledge_integrations") {
        val obsidianTreeUri = optionalString("obsidian_tree_uri")
        val obsidianRootDocumentId = optionalString("obsidian_root_document_id")
        val obsidianDisplayName = optionalString("obsidian_display_name")
        val importInboxTreeUri = optionalString("import_inbox_tree_uri")
        val autoSync = boolean("auto_sync", true)
        val lastSyncMs = long("last_sync_ms", 0L)
        val lastSummary = string("last_summary", "")
        val allowCloudEnrichment = boolean("allow_cloud_enrichment", false)
    }

    val files: List<PrefsFile> = listOf(
        MemoryMode, VaultLock, VaultKeys, Privacy, LegacyTranscripts, ChatMemory, DailySummary,
        DailySummaryRuns, DailySummaryBulletRuns, DailyBullets, DailyFactsReviewThreads, NightlyEnrichment, Knowledge,
    )
}
