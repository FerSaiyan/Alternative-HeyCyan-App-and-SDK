package com.fersaiyan.cyanbridge.ai.router

import android.content.Context
import com.fersaiyan.cyanbridge.ai.search.WebSearchEngine
import com.fersaiyan.cyanbridge.shared.settings.QuestionRoutingMode

/** Question routing mode for glasses voice questions (QuestionRoutingMode); the decision model is the default. */
object QuestionRoutingPrefs {
    private const val PREFS = "question_routing"
    private const val KEY_MODE = "mode"
    private const val KEY_WEB_SEARCH_ENGINE = "web_search_engine"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(context: Context): QuestionRoutingMode =
        QuestionRoutingMode.fromRaw(preferences(context).getString(KEY_MODE, null))

    fun setMode(context: Context, mode: QuestionRoutingMode) {
        preferences(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    /** Engine the `[search]` route reads in an off-screen WebView (ai/search/WebSearchAssist.kt). */
    fun webSearchEngine(context: Context): WebSearchEngine =
        preferences(context).getString(KEY_WEB_SEARCH_ENGINE, null)
            ?.let { raw -> WebSearchEngine.entries.firstOrNull { it.name == raw } }
            ?: WebSearchEngine.GOOGLE

    fun setWebSearchEngine(context: Context, engine: WebSearchEngine) {
        preferences(context).edit().putString(KEY_WEB_SEARCH_ENGINE, engine.name).apply()
    }
}
