package com.fersaiyan.cyanbridge.localagent.supervision

import com.fersaiyan.cyanbridge.shared.json.optJsonArray
import com.fersaiyan.cyanbridge.shared.json.optLong
import com.fersaiyan.cyanbridge.shared.json.optString
import com.fersaiyan.cyanbridge.shared.json.parseJsonObject
import kotlinx.serialization.json.JsonArray

data class SupervisionAction(
    val actionId: String,
    val actionType: String,
    val tsMs: Long,
    val date: String?,
    val targetEventIds: List<String>,
    val targetFactId: String?,
)

object SupervisionActionReader {
    fun parseLine(line: String): SupervisionAction? {
        if (line.isBlank()) return null

        return runCatching {
            val obj = parseJsonObject(line)
            val actionId = obj.optString("action_id", "").trim()
            val actionType = obj.optString("action_type", "").trim()
            if (actionId.isBlank() || actionType.isBlank()) return null

            SupervisionAction(
                actionId = actionId,
                actionType = actionType,
                tsMs = obj.optLong("ts_ms", 0L),
                date = obj.optString("date", "").trim().ifBlank { null },
                targetEventIds = obj.optJsonArray("target_event_ids").toStringList(),
                targetFactId = obj.optString("target_fact_id", "").trim().ifBlank { null },
            )
        }.getOrNull()
    }

    private fun JsonArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(size)
        for (i in indices) {
            val value = optString(i, "").trim()
            if (value.isNotBlank()) out.add(value)
        }
        return out
    }
}
