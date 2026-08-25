package com.fersaiyan.cyanbridge.localmodels.settings

import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogEntry

/**
 * Kept only for backwards compatibility with settings saved by older CyanBridge builds.
 * Performance profiles are no longer exposed and no longer change generation behavior.
 */
enum class LocalModelPerformanceProfile(val label: String) {
    FAST("Fast"),
    BALANCED("Balanced"),
    HIGH_QUALITY("High quality"),
}

enum class LocalComputeBackend(val label: String) {
    GPU("GPU"),
    CPU("CPU"),
    NPU_EXPERIMENTAL("NPU"),
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

        const val DEFAULT_TEMPERATURE = 0.7
        const val DEFAULT_TOP_P = 0.92
        const val DEFAULT_TOP_K = 40
        const val DEFAULT_REPETITION_PENALTY = 1.1

        /**
         * Default prompt for interactive local models. It remains user-editable per model.
         */
        const val DEFAULT_SYSTEM_PROMPT =
            "You are CyanBridge's assistant. Answer the user's request directly. " +
                "For spoken responses, begin with a short, self-contained sentence of at most 8 words " +
                "that gives the most useful answer first. Then add concise detail only when helpful. " +
                "Avoid filler, long preambles, and unnecessary formatting unless the user asks for them."

        fun defaultCpuThreads(): Int {
            return Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        }

        /**
         * Compatibility helper for older onboarding/call sites. Since the profile layer has been
         * retired, every model now resolves to the same neutral stored profile and explicit GPU/
         * CPU/NPU controls determine performance.
         */
        fun recommendedProfileFor(entry: LocalModelCatalogEntry?): LocalModelPerformanceProfile {
            return LocalModelPerformanceProfile.BALANCED
        }

        /**
         * The profile argument is accepted only so older stored settings and call sites remain
         * source/binary compatible. It no longer changes quality/performance behavior.
         */
        fun defaultsFor(
            entry: LocalModelCatalogEntry?,
            profile: LocalModelPerformanceProfile = LocalModelPerformanceProfile.BALANCED,
        ): LocalGenerationSettings {
            val baseCtx = entry?.contextSizeDefault ?: 4096
            val defaultRuntime = when (entry?.engine?.lowercase()) {
                "litert" -> LocalModelRuntime.LITERT
                else -> LocalModelRuntime.LLAMA_CPP
            }
            return LocalGenerationSettings(
                profile = LocalModelPerformanceProfile.BALANCED,
                temperature = DEFAULT_TEMPERATURE,
                topP = DEFAULT_TOP_P,
                topK = DEFAULT_TOP_K,
                maxTokens = DEFAULT_MAX_OUTPUT_TOKENS,
                repetitionPenalty = DEFAULT_REPETITION_PENALTY,
                contextSize = baseCtx.coerceIn(MIN_CONTEXT_SIZE, MAX_CONTEXT_SIZE),
                seed = -1,
                systemPromptOverride = DEFAULT_SYSTEM_PROMPT,
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
