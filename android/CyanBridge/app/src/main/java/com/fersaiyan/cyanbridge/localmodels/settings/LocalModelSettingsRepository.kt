package com.fersaiyan.cyanbridge.localmodels.settings

import android.content.Context
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogRepository
import com.fersaiyan.cyanbridge.shared.prefs.AgentPrefs
import com.fersaiyan.cyanbridge.shared.prefs.AgentPrefs.LocalModelSettings
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import org.json.JSONObject

object LocalModelSettingsRepository {
    fun getHuggingFaceToken(context: Context): String = LocalModelSettings.huggingFaceToken[context].trim()

    fun setHuggingFaceToken(context: Context, token: String) {
        LocalModelSettings.huggingFaceToken[context] = token.trim()
    }

    fun getForModel(context: Context, modelId: String): LocalGenerationSettings {
        val all = readAllSettings(context)
        val existing = all.optJSONObject(modelId)
        val entry = LocalModelCatalogRepository.findById(modelId)

        val profile = runCatching {
            LocalModelPerformanceProfile.valueOf(existing?.optString("profile").orEmpty())
        }.getOrElse { LocalGenerationSettings.recommendedProfileFor(entry) }

        val defaults = LocalGenerationSettings.defaultsFor(entry, profile)
        if (existing == null) return defaults

        return LocalGenerationSettings(
            profile = profile,
            temperature = existing.optDouble("temperature", defaults.temperature)
                .coerceIn(0.0, 2.0),
            topP = existing.optDouble("top_p", defaults.topP)
                .coerceIn(0.0, 1.0),
            topK = existing.optInt("top_k", defaults.topK)
                .coerceIn(0, 200),
            maxTokens = existing.optInt("max_tokens", defaults.maxTokens)
                .coerceIn(LocalGenerationSettings.MIN_MAX_TOKENS, LocalGenerationSettings.MAX_MAX_TOKENS),
            repetitionPenalty = existing.optDouble("repetition_penalty", defaults.repetitionPenalty)
                .coerceIn(0.8, 2.0),
            contextSize = existing.optInt("context_size", defaults.contextSize)
                .coerceIn(LocalGenerationSettings.MIN_CONTEXT_SIZE, LocalGenerationSettings.MAX_CONTEXT_SIZE),
            seed = existing.optInt("seed", defaults.seed),
            systemPromptOverride = LocalGenerationSettings.migrateDefaultSystemPrompt(
                existing.optString("system_prompt_override", defaults.systemPromptOverride),
            ),
            templateOverrideId = existing.optString("template_override", "").ifBlank { null },
            experimentalStructuredJson = existing.optBoolean(
                "experimental_structured_json",
                defaults.experimentalStructuredJson,
            ),
            computeBackend = runCatching {
                val raw = existing.optString("compute_backend", defaults.computeBackend.name)
                when (raw) {
                    "GPU_EXPERIMENTAL", "GPU" -> LocalComputeBackend.GPU
                    "NPU_EXPERIMENTAL" -> LocalComputeBackend.NPU_EXPERIMENTAL
                    "CPU" -> LocalComputeBackend.CPU
                    else -> LocalComputeBackend.valueOf(raw)
                }
            }.getOrElse {
                if (AgentPrefs.LocalModels.useGpu[context]) LocalComputeBackend.GPU else defaults.computeBackend
            },
            cpuThreads = existing.optInt("cpu_threads", defaults.cpuThreads)
                .coerceIn(1, 16),
            gpuLayers = existing.optInt("gpu_layers", defaults.gpuLayers)
                .coerceIn(-1, 999),
            modelRuntime = runCatching {
                LocalModelRuntime.valueOf(existing.optString("model_runtime", defaults.modelRuntime.name))
            }.getOrElse { defaults.modelRuntime },
        )
    }

    fun saveForModel(context: Context, modelId: String, settings: LocalGenerationSettings) {
        val all = readAllSettings(context)
        all.put(
            modelId,
            JSONObject()
                .put("profile", settings.profile.name)
                .put("temperature", settings.temperature)
                .put("top_p", settings.topP)
                .put("top_k", settings.topK)
                .put("max_tokens", settings.maxTokens)
                .put("repetition_penalty", settings.repetitionPenalty)
                .put("context_size", settings.contextSize)
                .put("seed", settings.seed)
                .put("system_prompt_override", settings.systemPromptOverride)
                .put("template_override", settings.templateOverrideId.orEmpty())
                .put("experimental_structured_json", settings.experimentalStructuredJson)
                .put("compute_backend", settings.computeBackend.name)
                .put("cpu_threads", settings.cpuThreads)
                .put("gpu_layers", settings.gpuLayers)
                .put("model_runtime", settings.modelRuntime.name),
        )
        LocalModelSettings.settingsByModel[context] = all.toString()
    }

    fun clearForModel(context: Context, modelId: String) {
        val all = readAllSettings(context)
        all.remove(modelId)
        LocalModelSettings.settingsByModel[context] = all.toString()
    }

    private fun readAllSettings(context: Context): JSONObject {
        val raw = LocalModelSettings.settingsByModel[context]
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }
}
