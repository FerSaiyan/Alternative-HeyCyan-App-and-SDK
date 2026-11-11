package com.fersaiyan.cyanbridge.shared.plugins

data class NativePluginCardData(
    val id: String,
    val title: String,
    val description: String,
    val badge: String,
    val enabled: Boolean,
    val hasSettings: Boolean,
    val isAvailable: Boolean = true,
)

enum class NativePluginShortcutAction {
    START,
    STOP,
    CAPTURE,
    SYNC,
    SUMMARIZE,
}

data class NativePluginShortcutButton(
    val action: NativePluginShortcutAction,
    val label: String,
)

data class NativePluginShortcutUiState(
    val id: String,
    val title: String,
    val description: String,
    val isEnabled: Boolean,
    val buttons: List<NativePluginShortcutButton>,
)

object NativePluginIds {
    const val LOCAL_AGENT = "local_agent"
    const val WALKING_AID = "walking_aid"
    const val MEETING_SPARK_NOTES = "meeting_spark_notes"
    const val LIVE_CAPTION_RELAY = "live_caption_relay"
    const val HANDS_FREE_TRANSLATOR = "hands_free_translator"
    const val ERRAND_BRAIN = "errand_brain"
    const val AUTO_DIARY = "auto_diary"
    const val AUTO_AUDIO = "auto_audio"
    const val VISUAL_DIARY = "visual_diary"
    const val ORAL_LESION_BENCH = "oral_lesion_bench"
}
