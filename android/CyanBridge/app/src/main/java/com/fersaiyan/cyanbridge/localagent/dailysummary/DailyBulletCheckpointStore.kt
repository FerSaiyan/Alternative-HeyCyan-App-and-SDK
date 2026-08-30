package com.fersaiyan.cyanbridge.localagent.dailysummary

import android.content.Context
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import org.json.JSONObject
import java.security.MessageDigest

/** Encrypted, per-event checkpoints prevent expensive bullet calls from repeating after interruption. */
object DailyBulletCheckpointStore {
    data class Record(
        val key: String,
        val tsMs: Long,
        val packageName: String,
        val bullet: String,
    )

    fun eventKey(tsMs: Long, packageName: String, text: String): String {
        val bytes = "$tsMs\n$packageName\n$text".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun load(context: Context, date: String): Map<String, Record> {
        val file = LocalAgentMemoryStore.dailyBulletsFileForDate(context, date)
        return LocalAgentMemoryStore.readText(file).lineSequence().mapNotNull { line ->
            val json = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
            val key = json.optString("key").trim()
            val bullet = json.optString("bullet").trim()
            if (key.isBlank() || bullet.isBlank()) return@mapNotNull null
            key to Record(
                key = key,
                tsMs = json.optLong("ts_ms", 0L),
                packageName = json.optString("package", "?"),
                bullet = bullet,
            )
        }.toMap()
    }

    fun put(context: Context, date: String, record: Record) {
        val current = load(context, date).toMutableMap()
        current[record.key] = record
        val text = current.values.sortedBy { it.tsMs }.joinToString("\n") { item ->
            JSONObject()
                .put("key", item.key)
                .put("ts_ms", item.tsMs)
                .put("package", item.packageName)
                .put("bullet", item.bullet)
                .toString()
        }
        LocalAgentMemoryStore.writeText(
            LocalAgentMemoryStore.dailyBulletsFileForDate(context, date),
            if (text.isBlank()) "" else "$text\n",
        )
    }
}
