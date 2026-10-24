package com.fersaiyan.cyanbridge.plugins.meetingsparknotes

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import org.json.JSONArray
import org.json.JSONObject

class MeetingSparkNotesStore {
    private val TAG = "MeetingSparkNotesStore"

    private val lock = Any()
    private val summaries = mutableListOf<MeetingSummary>()
    private var currentAudioBuffer = ByteArray(0)
    private var loaded = false

    fun addSummary(summary: MeetingSummary, maxHistory: Int) {
        synchronized(lock) {
            summaries.add(summary)
            val overflow = summaries.size - maxHistory.coerceAtLeast(10)
            if (overflow > 0) {
                repeat(overflow) { summaries.removeFirstOrNull() }
            }
        }
    }

    fun getSummaries(maxCount: Int): List<MeetingSummary> {
        synchronized(lock) {
            if (summaries.isEmpty()) return emptyList()
            val takeCount = maxCount.coerceIn(1, summaries.size)
            return summaries.takeLast(takeCount).toList()
        }
    }

    fun getCurrentAudioBuffer(): ByteArray {
        synchronized(lock) {
            return currentAudioBuffer.copyOf()
        }
    }

    fun appendAudioBuffer(chunk: ByteArray) {
        synchronized(lock) {
            currentAudioBuffer += chunk
        }
    }

    fun clearAudioBuffer() {
        synchronized(lock) {
            currentAudioBuffer = ByteArray(0)
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            summaries.clear()
            currentAudioBuffer = ByteArray(0)
            persistInternal(context, 50)
        }
    }

    private fun persistInternal(context: Context, maxHistory: Int) {
        try {
            val arr = JSONArray()
            val items = summaries.takeLast(maxHistory.coerceAtLeast(10))
            for (summary in items) {
                arr.put(
                    JSONObject()
                        .put("id", summary.id)
                        .put("timestampMs", summary.timestampMs)
                        .put("title", summary.title)
                        .put("summary", summary.summary)
                        .put("actionItems", JSONArray(summary.actionItems))
                        .put("participants", JSONArray(summary.participants))
                        .put("durationMinutes", summary.durationMinutes)
                        .put("audioPath", summary.audioPath ?: JSONObject.NULL)
                )
            }
            PluginPrefs.MeetingSparkNotesStore.summariesJson[context] = arr.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist summaries", e)
        }
    }

    fun persist(context: Context, maxHistory: Int) {
        persistInternal(context, maxHistory)
    }

    fun load(context: Context) {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            try {
                val raw = PluginPrefs.MeetingSparkNotesStore.summariesJson[context]
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val actionItemsArr = obj.optJSONArray("actionItems") ?: JSONArray()
                    val participantsArr = obj.optJSONArray("participants") ?: JSONArray()

                    val actionItems = (0 until actionItemsArr.length()).mapNotNull {
                        actionItemsArr.optString(it)
                    }
                    val participants = (0 until participantsArr.length()).mapNotNull {
                        participantsArr.optString(it)
                    }

                    val summary = MeetingSummary(
                        id = obj.optString("id", ""),
                        timestampMs = obj.optLong("timestampMs", 0L),
                        title = obj.optString("title", ""),
                        summary = obj.optString("summary", ""),
                        actionItems = actionItems,
                        participants = participants,
                        durationMinutes = obj.optInt("durationMinutes", 0),
                        audioPath = obj.optString("audioPath").takeIf { it.isNotBlank() },
                    )
                    if (summary.id.isNotBlank()) {
                        summaries.add(summary)
                    }
                }
                Log.i(TAG, "Loaded ${summaries.size} meeting summaries")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load summaries", e)
            }
        }
    }
}
