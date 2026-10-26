package com.fersaiyan.cyanbridge.shared.settings

enum class AgentProviderType {
    TASKER,
    LOCAL_AGENT,
    PRO_SUBSCRIPTION;

    val label: String
        get() = when (this) {
            TASKER -> "Tasker"
            LOCAL_AGENT -> "Local"
            PRO_SUBSCRIPTION -> "Pro"
        }
}

/**
 * How a glasses voice question is handled. SMART (default): the question is recorded and a model that
 * hears audio decides from it whether to answer, look with the camera, act on the phone (local agent),
 * start Live or ignore it. CLASSIC: on-device transcription first, then the configured button route and
 * the transcript router. SHADOW: classic, logging the old decision-model shadow. Models that cannot hear
 * audio always use the classic path.
 */
enum class QuestionRoutingMode {
    CLASSIC,
    SHADOW,
    SMART;

    companion object {
        fun fromRaw(raw: String?): QuestionRoutingMode =
            // The decision model is the default; Classic (STT + transcript router) must be chosen.
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: SMART
    }
}

enum class CaptureSource {
    BLUETOOTH_MIC,
    PHONE_MIC;
}

enum class MemoryPrivacyMode(
    val title: String,
    val description: String,
) {
    PRIVATE_LOCAL(
        title = "Private Local",
        description = "All memory, indexes, and retrieval stay on-device.",
    ),
    ENCRYPTED_SYNC(
        title = "Encrypted Sync",
        description = "Client-side encrypted sync payloads are prepared locally. Backend pending.",
    ),
    FAST_CLOUD_MEMORY(
        title = "Fast Cloud Memory",
        description = "Future cloud memory mode. Unavailable until backend exists.",
    ),
    CONFIDENTIAL_CLOUD_BETA(
        title = "Confidential Cloud Beta",
        description = "Future confidential cloud mode. Unavailable until backend exists.",
    );

    companion object {
        fun fromRaw(raw: String?): MemoryPrivacyMode {
            val normalized = raw?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.name == normalized } ?: PRIVATE_LOCAL
        }
    }
}

enum class MemorySourceType {
    EXPLICIT_USER_FACT,
    AUTO_DAILY_FACT,
    SCREEN_OCR,
    DERIVED_SUMMARY,
    IMPORTED_TEXT,
    SYSTEM_NOTE,
}

enum class SettingsSection {
    AI_AUTOMATION,
    MEMORY_PRIVACY,
    TRANSCRIPTS,
    DATA,
    ACCESSIBILITY,
    SUPPORT,
    FAQ,
}
