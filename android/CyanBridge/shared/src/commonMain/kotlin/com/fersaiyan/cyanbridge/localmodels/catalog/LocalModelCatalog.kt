package com.fersaiyan.cyanbridge.localmodels.catalog

data class LocalModelCatalogEntry(
    val id: String,
    val displayName: String,
    val family: String,
    val providerType: String = "local",
    val engine: String = "llama",
    val format: String = "gguf",
    val sourceUrl: String?,
    val sourcePageUrl: String?,
    val expectedFilename: String,
    val sha256: String?,
    val sizeBytes: Long,
    val quantization: String,
    val contextSizeDefault: Int,
    val promptTemplateId: String,
    val minRamGb: Double,
    val minStorageGb: Double,
    val shortDescription: String,
    val tags: List<String>,
    val gatedDownload: Boolean,
    val licenseTermsNote: String,
    val enabled: Boolean,
    val npuSupported: Boolean = false,
    val npuOnly: Boolean = false,
    val comingSoon: Boolean = false,
)

object LocalModelCatalogRepository {
    val curatedModels: List<LocalModelCatalogEntry> = listOf(
        LocalModelCatalogEntry(
            id = "gemma4-e2b-it-litert",
            displayName = "Gemma 4 E2B IT (LiteRT-LM)",
            family = "gemma",
            engine = "litert",
            format = "litertlm",
            sourceUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            sourcePageUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
            expectedFilename = "gemma-4-E2B-it.litertlm",
            sha256 = null,
            sizeBytes = 2_580_000_000L,
            quantization = "MIXED (2/4/8-bit)",
            contextSizeDefault = 4096,
            promptTemplateId = "gemma_it",
            minRamGb = 6.0,
            minStorageGb = 4.5,
            shortDescription = "Gemma 4 LiteRT-LM package with multimodal support and optional MTP acceleration when packaged with a drafter.",
            tags = listOf("litert", "gemma4", "multimodal", "offline", "starter"),
            gatedDownload = false,
            licenseTermsNote = "Use under Gemma model license terms.",
            enabled = true,
        ),
        LocalModelCatalogEntry(
            id = "gemma4-e4b-it-litert",
            displayName = "Gemma 4 E4B IT (LiteRT-LM)",
            family = "gemma",
            engine = "litert",
            format = "litertlm",
            sourceUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            sourcePageUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm",
            expectedFilename = "gemma-4-E4B-it.litertlm",
            sha256 = null,
            sizeBytes = 3_650_000_000L,
            quantization = "MIXED (4/8-bit)",
            contextSizeDefault = 4096,
            promptTemplateId = "gemma_it",
            minRamGb = 8.0,
            minStorageGb = 6.5,
            shortDescription = "Gemma 4 E4B LiteRT-LM package for higher quality on high-memory devices.",
            tags = listOf("litert", "gemma4", "multimodal", "quality", "offline"),
            gatedDownload = false,
            licenseTermsNote = "Use under Gemma model license terms.",
            enabled = true,
        ),
        LocalModelCatalogEntry(
            id = "qwen3.5-0.8b-q4",
            displayName = "Qwen3.5 0.8B (Q4_0)",
            family = "qwen",
            engine = "llama",
            format = "gguf",
            sourceUrl = "https://huggingface.co/ggml-org/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_0.gguf",
            sourcePageUrl = "https://huggingface.co/ggml-org/Qwen3.5-0.8B-GGUF",
            expectedFilename = "Qwen3.5-0.8B-Q4_0.gguf",
            sha256 = null,
            sizeBytes = 563_000_000L,
            quantization = "Q4_0",
            contextSizeDefault = 4096,
            promptTemplateId = "qwen_chat",
            minRamGb = 4.0,
            // Download headroom is checked separately; loading only needs working-file space.
            // Keep this low enough for the 6 GB 16 KB-page emulator smoke-test environment.
            minStorageGb = 0.35,
            shortDescription = "Compact current Qwen 3.5 model for fast llama.cpp text replies. The upstream model is multimodal, but CyanBridge's llama.cpp vision path is not enabled yet.",
            tags = listOf("qwen3.5", "fast", "offline", "starter"),
            gatedDownload = false,
            licenseTermsNote = "Apache-2.0. See the Qwen and ggml-org model cards for details.",
            enabled = true,
        ),
        LocalModelCatalogEntry(
            id = "gemma4-e2b-npu-coming-soon",
            displayName = "Gemma 4 E2B (NPU - Coming Soon)",
            family = "gemma",
            engine = "litert",
            format = "litertlm",
            sourceUrl = null,
            sourcePageUrl = "https://huggingface.co/litert-community",
            expectedFilename = "gemma-4-E2B-npu.litertlm",
            sha256 = null,
            sizeBytes = 2_400_000_000L,
            quantization = "NPU AOT (QNN/INT4)",
            contextSizeDefault = 4096,
            promptTemplateId = "gemma_it",
            minRamGb = 6.0,
            minStorageGb = 4.0,
            shortDescription = "Snapdragon NPU Ahead-of-Time (AOT) compiled package. Coming soon for supported Snapdragon devices.",
            tags = listOf("npu", "gemma4", "coming_soon"),
            gatedDownload = false,
            licenseTermsNote = "Use under Gemma model license terms.",
            enabled = false,
            npuSupported = true,
            npuOnly = true,
            comingSoon = true,
        ),
    )

    fun findById(id: String?): LocalModelCatalogEntry? {
        if (id.isNullOrBlank()) return null
        return curatedModels.firstOrNull { it.id == id }
    }

    fun recommendedStarterForRam(ramGb: Double): LocalModelCatalogEntry? =
        curatedModels.firstOrNull { entry ->
            entry.enabled &&
                !entry.comingSoon &&
                "starter" in entry.tags &&
                ramGb >= entry.minRamGb
        }
}
