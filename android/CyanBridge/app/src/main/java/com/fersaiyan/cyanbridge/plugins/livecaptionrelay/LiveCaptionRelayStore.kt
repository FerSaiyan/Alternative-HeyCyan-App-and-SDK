package com.fersaiyan.cyanbridge.plugins.livecaptionrelay

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import org.json.JSONArray
import org.json.JSONObject

class LiveCaptionRelayStore {
    private val TAG = "LiveCaptionRelayStore"

    private val lock = Any()
    private val captions = mutableListOf<CaptionEntry>()
    private var loaded = false

    fun addCaption(caption: CaptionEntry, maxHistory: Int) {
        synchronized(lock) {
            captions.add(caption)
            val overflow = captions.size - maxHistory.coerceAtLeast(50)
            if (overflow > 0) {
                repeat(overflow) { captions.removeFirstOrNull() }
            }
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            captions.clear()
            persistInternal(context, 100)
        }
    }

    private fun persistInternal(context: Context, maxHistory: Int) {
        try {
            val arr = JSONArray()
            val items = captions.takeLast(maxHistory.coerceAtLeast(50))
            for (caption in items) {
                arr.put(
                    JSONObject()
                        .put("timestampMs", caption.timestampMs)
                        .put("originalText", caption.originalText)
                        .put("translatedText", caption.translatedText ?: JSONObject.NULL)
                        .put("sourceLanguage", caption.sourceLanguage)
                        .put("targetLanguage", caption.targetLanguage ?: JSONObject.NULL)
                        .put("confidence", caption.confidence.toDouble())
                )
            }
            PluginPrefs.LiveCaptionRelayStore.captionsJson[context] = arr.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist captions", e)
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
                val raw = PluginPrefs.LiveCaptionRelayStore.captionsJson[context]
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val caption = CaptionEntry(
                        timestampMs = obj.optLong("timestampMs", 0L),
                        originalText = obj.optString("originalText", ""),
                        translatedText = obj.optString("translatedText").takeIf { it.isNotBlank() },
                        sourceLanguage = obj.optString("sourceLanguage", "en"),
                        targetLanguage = obj.optString("targetLanguage").takeIf { it.isNotBlank() },
                        confidence = obj.optDouble("confidence", 0.8).toFloat(),
                    )
                    if (caption.originalText.isNotBlank()) {
                        captions.add(caption)
                    }
                }
                Log.i(TAG, "Loaded ${captions.size} captions")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load captions", e)
            }
        }
    }
}
