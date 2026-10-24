package com.fersaiyan.cyanbridge.localagent

import android.content.Context
import com.fersaiyan.cyanbridge.shared.prefs.AgentPrefs
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.remove
import com.fersaiyan.cyanbridge.shared.prefs.set
import org.json.JSONArray
import org.json.JSONObject

/** Local, bounded task metadata. Screen content and action payloads are intentionally excluded. */
object LocalAgentTaskHistory {
    data class Entry(
        val goal: String,
        val status: String,
        val stepCount: Int,
        val usedSavedSkill: Boolean,
        val createdAtMs: Long = System.currentTimeMillis(),
    )

    private const val MAX_ENTRIES = 20

    fun record(context: Context, entry: Entry) {
        val entries = load(context).toMutableList()
        entries += entry.copy(
            goal = entry.goal.trim().take(MAX_GOAL_CHARS),
            status = entry.status.trim().ifBlank { "Unknown" }.take(MAX_STATUS_CHARS),
            stepCount = entry.stepCount.coerceAtLeast(0),
        )
        save(context, entries.takeLast(MAX_ENTRIES))
    }

    fun recent(context: Context, limit: Int = 5): List<Entry> {
        return load(context).takeLast(limit.coerceIn(1, MAX_ENTRIES)).asReversed()
    }

    fun clear(context: Context) {
        AgentPrefs.TaskHistory.entriesJson.remove(context)
    }

    private fun load(context: Context): List<Entry> {
        val raw = AgentPrefs.TaskHistory.entriesJson[context]
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val goal = item.optString("goal", "").trim()
                if (goal.isBlank()) continue
                add(
                    Entry(
                        goal = goal,
                        status = item.optString("status", "Unknown"),
                        stepCount = item.optInt("step_count", 0).coerceAtLeast(0),
                        usedSavedSkill = item.optBoolean("used_saved_skill", false),
                        createdAtMs = item.optLong("created_at_ms", 0L),
                    ),
                )
            }
        }
    }

    private fun save(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("goal", entry.goal)
                    .put("status", entry.status)
                    .put("step_count", entry.stepCount)
                    .put("used_saved_skill", entry.usedSavedSkill)
                    .put("created_at_ms", entry.createdAtMs),
            )
        }
        AgentPrefs.TaskHistory.entriesJson[context] = array.toString()
    }

    private const val MAX_GOAL_CHARS = 240
    private const val MAX_STATUS_CHARS = 80
}
