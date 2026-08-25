package com.fersaiyan.cyanbridge.localmodels.settings

import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogEntry

enum class LocalModelPerformanceProfile(val label: String) {
    FAST("Fast"),
    BALANCED("Balanced"),
    HIGH_QUALITY("High quality"),
}

enum class LocalComputeBackend(val label: String) {
    CPU("CPU"),
    GPU("GPU"),
    NPU_EXPERIMENTAL("NPU (Experimental)"),
}

enum class LocalModelRuntime(val label: String) {
    LLAMA_CPP("llama.cpp"),
    LITERT("LiteRT"),
    REMOTE_OPENAI("Remote (OpenAI-compatible)"),
}

data class LocalGenerationSettings(
    val profile: LocalModelPerformanceProfile,
    val temperature: Double,
    val topP: Double,
    val topK: Int,
    val maxTokens: Int,
    val repetitionPenalty: Double,
    val contextSize: Int,
    val seed: Int,
    val systemPromptOverride: String,
    val templateOverrideId: String?,
    val experimentalStructuredJson: Boolean,
    val computeBackend: LocalComputeBackend,
    val cpuThreads: Int,
    val gpuLayers: Int,
    val modelRuntime: LocalModelRuntime,
) {
    companion object {
        const val MIN_MAX_TOKENS = 32
        const val MAX_MAX_TOKENS = 8192
        const val DEFAULT_MAX_OUTPUT_TOKENS = 4096
        const val MIN_CONTEXT_SIZE = 1024
        const val MAX_CONTEXT_SIZE = 32768

        fun defaultCpuThreads(): Int {
            return Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        }

        fun recommendedProfileFor(entry: LocalModelCatalogEntry?): LocalModelPerformanceProfile {
            return when (entry?.id) {
                "gemma4-e4b-it-litert" -> LocalModelPerformanceProfile.HIGH_QUALITY
                "gemma4-e2b-it-litert",
                "qwen2.5-0.5b-instruct-q4" -> LocalModelPerformanceProfile.FAST
                else -> LocalModelPerformanceProfile.BALANCED
            }
        }

        fun defaultsFor(
            entry: LocalModelCatalogEntry?,
            profile: LocalModelPerformanceProfile,
        ): LocalGenerationSettings {
            val baseCtx = entry?.contextSizeDefault ?: 4096
            val defaultRuntime = when (entry?.engine?.lowercase()) {
                "litert" -> LocalModelRuntime.LITERT
                else -> LocalModelRuntime.LLAMA_CPP
            }
            return when (profile) {
                LocalModelPerformanceProfile.FAST -> LocalGenerationSettings(
                    profile = profile,
                    temperature = 0.6,
                    topP = 0.9,
                    topK = 24,
                    maxTokens = DEFAULT_MAX_OUTPUT_TOKENS,
                    repetitionPenalty = 1.05,
                    contextSize = (baseCtx / 2).coerceAtLeast(2048),
                    seed = -1,
                    systemPromptOverride = "",
                    templateOverrideId = null,
                    experimentalStructuredJson = false,
                    computeBackend = LocalComputeBackend.GPU,
                    cpuThreads = defaultCpuThreads(),
                    gpuLayers = -1,
                    modelRuntime = defaultRuntime,
                )

                LocalModelPerformanceProfile.BALANCED -> LocalGenerationSettings(
                    profile = profile,
                    temperature = 0.7,
                    topP = 0.92,
                    topK = 40,
                    maxTokens = DEFAULT_MAX_OUTPUT_TOKENS,
                    repetitionPenalty = 1.1,
                    contextSize = baseCtx,
                    seed = -1,
                    systemPromptOverride = "",
                    templateOverrideId = null,
                    experimentalStructuredJson = false,
                    computeBackend = LocalComputeBackend.GPU,
                    cpuThreads = defaultCpuThreads(),
                    gpuLayers = -1,
                    modelRuntime = defaultRuntime,
                )

                LocalModelPerformanceProfile.HIGH_QUALITY -> LocalGenerationSettings(
                    profile = profile,
                    temperature = 0.75,
                    topP = 0.95,
                    topK = 64,
                    maxTokens = DEFAULT_MAX_OUTPUT_TOKENS,
                    repetitionPenalty = 1.12,
                    contextSize = (baseCtx + 1024).coerceAtMost(MAX_CONTEXT_SIZE),
                    seed = -1,
                    systemPromptOverride = "",
                    templateOverrideId = null,
                    experimentalStructuredJson = false,
                    computeBackend = LocalComputeBackend.GPU,
                    cpuThreads = defaultCpuThreads(),
                    gpuLayers = -1,
                    modelRuntime = defaultRuntime,
                )
            }
        }
    }
}
