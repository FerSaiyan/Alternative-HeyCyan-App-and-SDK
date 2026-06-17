package com.fersaiyan.cyanbridge.localagent.dailyfacts

import android.content.Context
import org.json.JSONObject

object DailyFactsReviewThreadStore {
    data class Config(
        val date: String,
        val lookbackDays: Int,
    )

    private const val PREFS = "daily_facts_review_threads"
    private const val KEY_MAP = "thread_map_json"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(
        context: Context,
        chatId: String,
        date: String,
        lookbackDays: Int,
    ) {
        val id = chatId.trim()
        val d = date.trim()
        if (id.isBlank() || d.isBlank()) return

        val obj = loadMap(context)
        obj.put(
            id,
            JSONObject()
                .put("date", d)
                .put("lookback_days", lookbackDays.coerceIn(1, 365)),
        )
        prefs(context).edit().putString(KEY_MAP, obj.toString()).apply()
    }

    fun load(context: Context, chatId: String): Config? {
        val id = chatId.trim()
        if (id.isBlank()) return null

        val entry = loadMap(context).optJSONObject(id) ?: return null
        val date = entry.optString("date", "").trim()
        if (date.isBlank()) return null
        val lookback = entry.optInt("lookback_days", 1).coerceIn(1, 365)
        return Config(date = date, lookbackDays = lookback)
    }

    fun remove(context: Context, chatId: String) {
        val id = chatId.trim()
        if (id.isBlank()) return
        val obj = loadMap(context)
        obj.remove(id)
        prefs(context).edit().putString(KEY_MAP, obj.toString()).apply()
    }

    private fun loadMap(context: Context): JSONObject {
        val raw = prefs(context).getString(KEY_MAP, "{}") ?: "{}"
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }
}
