package com.fersaiyan.cyanbridge.ai.router

import android.content.Context
import com.fersaiyan.cyanbridge.ai.search.WebSearchEngine
import com.fersaiyan.cyanbridge.shared.prefs.AssistantPrefs.QuestionRouting
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import com.fersaiyan.cyanbridge.shared.settings.QuestionRoutingMode

/** Question routing mode for glasses voice questions (QuestionRoutingMode); the decision model is the default. */
object QuestionRoutingPrefs {
    fun mode(context: Context): QuestionRoutingMode =
        QuestionRoutingMode.fromRaw(QuestionRouting.mode[context])

    fun setMode(context: Context, mode: QuestionRoutingMode) {
        QuestionRouting.mode[context] = mode.name
    }

    /** Engine the `[search]` route reads in an off-screen WebView (ai/search/WebSearchAssist.kt). */
    fun webSearchEngine(context: Context): WebSearchEngine =
        QuestionRouting.webSearchEngine[context]
            ?.let { raw -> WebSearchEngine.entries.firstOrNull { it.name == raw } }
            ?: WebSearchEngine.GOOGLE

    fun setWebSearchEngine(context: Context, engine: WebSearchEngine) {
        QuestionRouting.webSearchEngine[context] = engine.name
    }
}
