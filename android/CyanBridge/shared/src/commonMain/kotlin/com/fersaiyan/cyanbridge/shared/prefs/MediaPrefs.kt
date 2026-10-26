package com.fersaiyan.cyanbridge.shared.prefs

/** Glasses media, capture and media sync. */
object MediaPrefs {
    object GlassesMedia : PrefsFile("glasses_media") {
        val videoRecording = boolean("video_recording", false)
    }

    object AutoAudioCapture : PrefsFile("auto_audio_capture_prefs") {
        val enabled = boolean("enabled", false)
        val successfulLoops = int("successful_loops", 0)
        val loopsPerSync = int("loops_per_sync", 12).clamped(1, 96)
        val visualNotesEnabled = boolean("visual_notes_enabled", false)
        val speechExtendEnabled = boolean("speech_extend_enabled", true)
        val pausedForMeeting = boolean("paused_meeting", false)
        val pausedForVideo = boolean("paused_video", false)
        val pauseUntilMs = long("pause_until_ms", 0L)
        val lastPauseReason = string("last_pause_reason", "")
    }

    /** Lets capture screens recover "is recording" after the process is recreated. */
    object MeetingCapture : PrefsFile("meeting_capture") {
        val isRecording = boolean("is_recording", false)
        val startAtMs = optionalLong("start_at_ms")
        val source = optionalString("source")
        val audioPath = optionalString("audio_path")
        val deviceClass = optionalString("device_class")
    }

    object SyncWarnings : PrefsFile("media_sync_warnings") {
        val officialHeyCyanWarningSuppressed = boolean("official_heycyan_warning_suppressed", false)
    }

    /** `*` is a hashed phone + glasses profile key. */
    object AdaptiveP2pProfiles : PrefsFile("adaptive_p2p_sync_profiles") {
        val preferredRoute = optionalStringFamily("*.preferred_route")
        val preferredWarmupMs = optionalLongFamily("*.preferred_warmup_ms")
        val candidateRoute = optionalStringFamily("*.candidate_route")
        val candidateWarmupMs = optionalLongFamily("*.candidate_warmup_ms")
        val candidateSuccesses = intFamily("*.candidate_successes", 0)
        val completedSyncs = intFamily("*.completed_syncs", 0)
        val consecutiveFailures = intFamily("*.consecutive_failures", 0)
    }

    /** MoYoung W620 file-transfer hotspot credentials. */
    object MoyoungW620 : PrefsFile("moyoung_w620") {
        val fileWifiSsid = string("file_wifi_ssid", "Glass-01")
        val fileWifiPassword = string("file_wifi_password", "12345678")
    }

    /** The image question handed to Gemini/ChatGPT through Tasker. */
    object ExternalImageAutomation : PrefsFile("external_image_automation") {
        val session = optionalString("session")
        val token = optionalString("token")
        val path = optionalString("path")
        val uri = optionalString("uri")
        val question = string("question", "")
        val source = optionalString("source")
        val stage = optionalString("stage")
        val error = optionalString("error")
        val followUpPromptShown = boolean("follow_up_prompt_shown", false)
    }

    /** Tasker profile handshakes; `*` is the lowercase assistant target. */
    object TaskerImageProfile : PrefsFile("tasker_image_profile") {
        val target = optionalString("target")
        val version = optionalString("version")
        val pendingToken = optionalString("pending_token")
        val verifiedAt = longFamily("verified_at_*", 0L)
        val versionFor = optionalStringFamily("version_*")
    }

    /** The user's Mentra Live broadcast destination. Secure: RTMP URLs carry the stream key. */
    object MentraBroadcast : PrefsFile("mentra_broadcast", secure = true) {
        val streamUrl = string("stream_url", "")
    }

    val files: List<PrefsFile> = listOf(
        GlassesMedia, AutoAudioCapture, MeetingCapture, SyncWarnings, AdaptiveP2pProfiles, MoyoungW620,
        ExternalImageAutomation, TaskerImageProfile, MentraBroadcast,
    )
}
