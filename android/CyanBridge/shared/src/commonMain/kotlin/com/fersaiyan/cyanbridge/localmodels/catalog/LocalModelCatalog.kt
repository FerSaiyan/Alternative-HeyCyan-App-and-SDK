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
    /** Per-SoC NPU builds, first match wins; resolve with [NpuVariantResolver] before downloading. */
    val npuVariants: List<NpuVariantSet> = emptyList(),
)

/**
 * NPU builds compiled per SoC and hosted in a Hugging Face repo as
 * `<repo>/resolve/<revision>/<filePrefix>_<vendor>_<SoC>.<format>`: `_Qualcomm_SM8650`, `_MediaTek_MT6989`,
 * `_Google_Tensor_G5`, `_Samsung_E9965`. Google's litert-community Snapdragon files use lowercase
 * (`<filePrefix>_qualcomm_<soc>`) when [lowercaseSoc] is set. [files] maps each published SoC model
 * (`Build.SOC_MODEL`, matched case-insensitively) to its file; [socFileNames] gives the file's SoC token when it
 * differs from `Build.SOC_MODEL` (default: spaces become underscores, so `Tensor G5` -> `Tensor_G5`). The SoC stays in
 * the installed file name, which the runtime checks against the phone before loading.
 */
data class NpuVariantSet(
    val repo: String,
    val filePrefix: String,
    val files: Map<String, NpuVariantFile>,
    /** Commit to download from, so a later upload cannot change what a checksum pins. */
    val revision: String = "main",
    val lowercaseSoc: Boolean = false,
    /** Vendor token of the file name: Qualcomm, MediaTek, Google or Samsung. */
    val vendor: String = "Qualcomm",
    val socFileNames: Map<String, String> = emptyMap(),
)

data class NpuVariantFile(val sha256: String?, val sizeBytes: Long)

object NpuVariantResolver {
    fun fileName(entry: LocalModelCatalogEntry, set: NpuVariantSet, socModel: String): String {
        val key = set.files.keys.firstOrNull { it.equals(socModel.trim(), ignoreCase = true) } ?: socModel.trim()
        val soc = set.socFileNames[key] ?: key.replace(' ', '_')
        return if (set.lowercaseSoc) "${set.filePrefix}_${set.vendor.lowercase()}_${soc.lowercase()}.${entry.format}"
        else "${set.filePrefix}_${set.vendor}_$soc.${entry.format}"
    }

    /**
     * The entry to download on a phone with [socModel]: itself when it has no per-SoC
     * variants, the SoC-specific file when one is published, or null when this SoC has none.
     */
    fun resolve(entry: LocalModelCatalogEntry, socModel: String?): LocalModelCatalogEntry? {
        if (entry.npuVariants.isEmpty()) return entry
        val soc = socModel?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val set = entry.npuVariants.firstOrNull { s -> s.files.keys.any { it.equals(soc, ignoreCase = true) } } ?: return null
        val file = set.files.entries.first { it.key.equals(soc, ignoreCase = true) }.value
        val name = fileName(entry, set, soc)
        return entry.copy(
            sourceUrl = "https://huggingface.co/${set.repo}/resolve/${set.revision}/$name",
            sourcePageUrl = "https://huggingface.co/${set.repo}",
            expectedFilename = name,
            sha256 = file.sha256,
            sizeBytes = file.sizeBytes,
        )
    }
}

private const val NPU_E2B_REPO = "Fertroll10/gemma-4-E2B-it-litert-lm-npu"
private const val NPU_E2B_REVISION = "2f5d066822497f5561b2d86a55bf9e94bd9b65bc"

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
            id = "gemma4-e2b-it-npu",
            displayName = "Gemma 4 E2B IT (Snapdragon NPU)",
            family = "gemma",
            engine = "litert",
            format = "litertlm",
            // Resolved per phone by NpuVariantResolver; this page lists every published SoC.
            sourceUrl = null,
            sourcePageUrl = "https://huggingface.co/$NPU_E2B_REPO",
            expectedFilename = "gemma-4-E2B-it_Qualcomm_<SoC>.litertlm",
            sha256 = null,
            sizeBytes = 3_016_294_400L,
            quantization = "NPU AOT (QAT INT4/8, INT16 activations)",
            contextSizeDefault = 4096,
            promptTemplateId = "gemma_it",
            minRamGb = 6.0,
            minStorageGb = 4.0,
            shortDescription = "Gemma 4 E2B compiled for this phone's Snapdragon NPU: about 2x faster replies than GPU. " +
                "4k context. Snapdragon 8 Gen 3 build is text-only for now; Snapdragon 8 Elite " +
                "(Google's build) also takes images and audio.",
            tags = listOf("litert", "gemma4", "npu", "offline"),
            gatedDownload = false,
            licenseTermsNote = "Apache-2.0 (Gemma 4). Community NPU build; SM8750 file published by Google (litert-community).",
            enabled = true,
            npuSupported = true,
            npuOnly = true,
            npuVariants = listOf(
                // Compiled and validated on a Galaxy Z Fold6 (docs/NPU_ON_DEVICE.md).
                NpuVariantSet(
                    repo = NPU_E2B_REPO,
                    filePrefix = "gemma-4-E2B-it",
                    revision = NPU_E2B_REVISION,
                    files = mapOf(
                        "SM8650" to NpuVariantFile(
                            sha256 = "2f2e0e64bb56d592f520e379e2b4456e8f02f78c67fe3c42c0c0871f73a3beba",
                            sizeBytes = 2_311_186_656L,
                        ),
                    ),
                ),
                // Google's own NPU build (text, image, audio); not yet run on an SM8750 phone.
                NpuVariantSet(
                    repo = "litert-community/gemma-4-E2B-it-litert-lm",
                    filePrefix = "gemma-4-E2B-it",
                    revision = "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1",
                    lowercaseSoc = true,
                    files = mapOf(
                        "SM8750" to NpuVariantFile(
                            sha256 = "41dd675fbe735b6029012b5576a5716bac614fd8156de0128db4c9dff3cebd4e",
                            sizeBytes = 3_016_294_400L,
                        ),
                    ),
                ),
                // Google's Tensor build (Pixel 10 reports Build.SOC_MODEL "Tensor G5"). Our own Tensor compile fails
                // in the Tensor compiler (INTERNAL), so Pixels use Google's file.
                NpuVariantSet(
                    repo = "litert-community/gemma-4-E2B-it-litert-lm",
                    filePrefix = "gemma-4-E2B-it",
                    revision = "2e12bfd869b2962984f97a1f2a26807316d534cc",
                    vendor = "Google",
                    files = mapOf(
                        "Tensor G5" to NpuVariantFile(
                            sha256 = "72d056066f34362adcecf6b76995de67ad899f6f33dfe9079593c1acd2984191",
                            sizeBytes = 3_206_299_658L,
                        ),
                    ),
                ),
            ),
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
