package com.fersaiyan.cyanbridge.plugins.walkingaid

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import org.json.JSONArray
import org.json.JSONObject

object WalkingAidImageStore {
    private const val TAG = "WalkingAidImageStore"
    private const val MAX_IN_MEMORY = 10

    private val lock = Any()
    private val recentDescriptions = ArrayDeque<SceneRecord>(MAX_IN_MEMORY)
    private val imageHistory = mutableListOf<WalkingAidImageEntry>()

    private var loaded = false

    fun addRecord(record: SceneRecord, maxHistory: Int) {
        synchronized(lock) {
            if (recentDescriptions.size >= MAX_IN_MEMORY) {
                recentDescriptions.removeFirst()
            }
            recentDescriptions.addLast(record)

            imageHistory.add(
                WalkingAidImageEntry(
                    timestampMs = record.timestampMs,
                    imagePath = record.imagePath,
                    description = record.description,
                )
            )
            val overflow = imageHistory.size - maxHistory.coerceAtLeast(10)
            if (overflow > 0) {
                repeat(overflow) { imageHistory.removeFirstOrNull() }
            }
        }
    }

    fun getRecentDescriptions(count: Int = 5): List<SceneRecord> {
        synchronized(lock) {
            val takeCount = count.coerceIn(1, recentDescriptions.size)
            return recentDescriptions.takeLast(takeCount).toList()
        }
    }

    fun getImageHistory(maxCount: Int): List<WalkingAidImageEntry> {
        synchronized(lock) {
            if (imageHistory.isEmpty()) return emptyList()
            val takeCount = maxCount.coerceIn(1, imageHistory.size)
            return imageHistory.takeLast(takeCount).toList()
        }
    }

    fun enrichRecord(
        context: Context,
        timestampMs: Long,
        description: String? = null,
        depthDescription: String? = null,
        stateDecision: StateDecision? = null,
        maxHistory: Int,
    ) {
        synchronized(lock) {
            val records = recentDescriptions.map { record ->
                if (record.timestampMs != timestampMs) {
                    record
                } else {
                    record.copy(
                        description = description ?: record.description,
                        depthDescription = depthDescription ?: record.depthDescription,
                        stateDecision = stateDecision ?: record.stateDecision,
                    )
                }
            }
            recentDescriptions.clear()
            recentDescriptions.addAll(records)

            val historyIndex = imageHistory.indexOfLast { it.timestampMs == timestampMs }
            if (historyIndex >= 0 && description != null) {
                imageHistory[historyIndex] = imageHistory[historyIndex].copy(description = description)
            }
            persistInternal(context, maxHistory)
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            recentDescriptions.clear()
            imageHistory.clear()
            persistInternal(context, 50)
        }
    }

    private fun persistInternal(context: Context, maxHistory: Int) {
        try {
            val arr = JSONArray()
            val items = imageHistory.takeLast(maxHistory.coerceAtLeast(10))
            for (entry in items) {
                arr.put(
                    JSONObject()
                        .put("timestampMs", entry.timestampMs)
                        .put("imagePath", entry.imagePath)
                        .put("description", entry.description)
                )
            }
            PluginPrefs.WalkingAidImageStore.historyJson[context] = arr.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist image store", e)
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
                val raw = PluginPrefs.WalkingAidImageStore.historyJson[context]
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val entry = WalkingAidImageEntry(
                        timestampMs = obj.optLong("timestampMs", 0L),
                        imagePath = obj.optString("imagePath", ""),
                        description = obj.optString("description", ""),
                    )
                    if (entry.imagePath.isNotBlank()) {
                        imageHistory.add(entry)
                    }
                }
                Log.i(TAG, "Loaded ${imageHistory.size} image history entries")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load image store", e)
            }
        }
    }
}
