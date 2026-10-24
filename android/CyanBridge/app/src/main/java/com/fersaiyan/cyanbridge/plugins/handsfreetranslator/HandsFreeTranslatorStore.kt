package com.fersaiyan.cyanbridge.plugins.handsfreetranslator

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.shared.prefs.PluginPrefs
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import org.json.JSONArray
import org.json.JSONObject

class HandsFreeTranslatorStore {
    private val TAG = "HandsFreeTranslatorStore"

    private val lock = Any()
    private val translations = mutableListOf<TranslationEntry>()
    private val presets = mutableListOf<TranslationPreset>()
    private var loaded = false

    fun addTranslation(translation: TranslationEntry, maxHistory: Int) {
        synchronized(lock) {
            translations.add(translation)
            val overflow = translations.size - maxHistory.coerceAtLeast(50)
            if (overflow > 0) {
                repeat(overflow) { translations.removeFirstOrNull() }
            }
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            translations.clear()
            persistInternal(context, 100)
        }
    }

    private fun persistInternal(context: Context, maxHistory: Int) {
        try {
            val arr = JSONArray()
            val items = translations.takeLast(maxHistory.coerceAtLeast(50))
            for (translation in items) {
                arr.put(
                    JSONObject()
                        .put("timestampMs", translation.timestampMs)
                        .put("originalText", translation.originalText)
                        .put("translatedText", translation.translatedText)
                        .put("sourceLanguage", translation.sourceLanguage)
                        .put("targetLanguage", translation.targetLanguage)
                        .put("confidence", translation.confidence.toDouble())
                )
            }
            PluginPrefs.HandsFreeTranslatorStore.translationsJson[context] = arr.toString()

            // Persist presets
            val presetsArr = JSONArray()
            for (preset in presets) {
                presetsArr.put(
                    JSONObject()
                        .put("id", preset.id)
                        .put("name", preset.name)
                        .put("sourceLanguage", preset.sourceLanguage)
                        .put("targetLanguage", preset.targetLanguage)
                        .put("phrases", JSONArray(preset.phrases))
                )
            }
            PluginPrefs.HandsFreeTranslatorStore.presetsJson[context] = presetsArr.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist translations", e)
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
                // Load translations
                val raw = PluginPrefs.HandsFreeTranslatorStore.translationsJson[context]
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val translation = TranslationEntry(
                        timestampMs = obj.optLong("timestampMs", 0L),
                        originalText = obj.optString("originalText", ""),
                        translatedText = obj.optString("translatedText", ""),
                        sourceLanguage = obj.optString("sourceLanguage", "en"),
                        targetLanguage = obj.optString("targetLanguage", "es"),
                        confidence = obj.optDouble("confidence", 0.9).toFloat(),
                    )
                    if (translation.originalText.isNotBlank()) {
                        translations.add(translation)
                    }
                }

                // Load presets
                val presetsRaw = PluginPrefs.HandsFreeTranslatorStore.presetsJson[context]
                val presetsArr = JSONArray(presetsRaw)
                for (i in 0 until presetsArr.length()) {
                    val obj = presetsArr.optJSONObject(i) ?: continue
                    val phrasesArr = obj.optJSONArray("phrases") ?: JSONArray()
                    val phrases = (0 until phrasesArr.length()).mapNotNull {
                        phrasesArr.optString(it)
                    }
                    val preset = TranslationPreset(
                        id = obj.optString("id", ""),
                        name = obj.optString("name", ""),
                        sourceLanguage = obj.optString("sourceLanguage", "en"),
                        targetLanguage = obj.optString("targetLanguage", "es"),
                        phrases = phrases,
                    )
                    if (preset.id.isNotBlank()) {
                        presets.add(preset)
                    }
                }

                Log.i(TAG, "Loaded ${translations.size} translations and ${presets.size} presets")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load translations", e)
            }
        }
    }
}
