package com.fersaiyan.cyanbridge.ui.navigation

object Routes {
    const val WELCOME = "welcome"
    const val BATTERY_OPT = "battery_optimization"
    const val CHAT = "chat"
    const val CHAT_THREAD = "chat_thread/{chatId}"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val PRO = "pro"
    const val PRO_SETTINGS = "pro_settings"
    const val PLUGINS = "plugins"
    const val RECORDINGS = "recordings"
    const val NOTES = "notes"
    const val NOTE_DETAIL = "note_detail/{noteId}"
    const val LOCAL_MODELS = "local_models"
    const val DAILY_FACTS = "daily_facts"
    const val DAILY_SUMMARY = "daily_summary"
    const val APP_BLACKLIST = "app_blacklist"
    const val SCREEN_CAPTURES = "screen_captures"
    const val PENDING_ACTIONS = "pending_actions"
    const val TRANSCRIPTION_DEBUG = "transcription_debug"

    fun chatThread(chatId: String) = "chat_thread/$chatId"
    fun noteDetail(noteId: String) = "note_detail/$noteId"
}
