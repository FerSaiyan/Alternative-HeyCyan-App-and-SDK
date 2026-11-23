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
// Calibration v3 builds (gemma-4-E2B-it-v3_<Vendor>_<SoC>.litertlm, text-only), device-tested on Google Device Run 2026-11-22.
private const val NPU_E2B_V3_REVISION = "2687ca17a52e63cc2860a62fb7ef96a15e873b50"
// v3mm = the v3 NPU text model + Google's Gemma 4 vision/audio sections (no new AOT). SM8550/8650/8850/MT6989
// published 2026-11-23 (rev 35a5e3f3); SM8750 + the 4k-16k builds added the same day (rev 78988cdf, all v3mm files).
private const val NPU_E2B_V3MM_REVISION = "78988cdf3e3f045985062fb685d9c24b1cdcd333"

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
            displayName = "Gemma 4 E2B IT (NPU)",
            family = "gemma",
            engine = "litert",
            format = "litertlm",
            // Resolved per phone by NpuVariantResolver; this page lists every published SoC.
            sourceUrl = null,
            sourcePageUrl = "https://huggingface.co/$NPU_E2B_REPO",
            expectedFilename = "gemma-4-E2B-it-v3mm_<Vendor>_<SoC>.litertlm",
            sha256 = null,
            sizeBytes = 3_493_108_340L,
            quantization = "NPU AOT (QAT INT4/8, INT16 activations)",
            contextSizeDefault = 4096,
            promptTemplateId = "gemma_it",
            minRamGb = 6.0,
            minStorageGb = 4.0,
            shortDescription = "Gemma 4 E2B compiled for this phone's NPU: about 2x faster replies than GPU. 4k context. " +
                "Takes photos and voice questions (the image encoder runs on the GPU).",
            tags = listOf("litert", "gemma4", "npu", "offline"),
            gatedDownload = false,
            licenseTermsNote = "Apache-2.0 (Gemma 4). Community NPU builds; the Tensor G5 file is published by Google (litert-community).",
            enabled = true,
            npuSupported = true,
            npuOnly = true,
            npuVariants = listOf(
                // Calibration v3 (app prompts, 9 languages, 16k long context; text model device-tested 11-13/13 on
                // 2026-11-22) + Google's Gemma 4 vision/audio sections (v3mm, 2026-11-23). Photo + voice questions
                // on Device Run (PhotoQuestionPrefillTest, PromptCacheRouteTest): S23U SM8550, S24U SM8650, S25 SM8750,
                // S26 SM8850.
                NpuVariantSet(
                    repo = NPU_E2B_REPO,
                    filePrefix = "gemma-4-E2B-it-v3mm",
                    revision = NPU_E2B_V3MM_REVISION,
                    files = mapOf(
                        "SM8550" to NpuVariantFile(
                            sha256 = "897ab4448dbf2a55cce257944b5de75866f2fe10c85518ca5ac1e5c168ce805a",
                            sizeBytes = 3_493_108_340L,
                        ),
                        "SM8650" to NpuVariantFile(
                            sha256 = "06fc9d05bfca142e6392bd1fac995781e3ce987b01d403f7536d293300839f44",
                            sizeBytes = 2_643_630_708L,
                        ),
                        // S25: text 12/13, photo 18/20, routes 15-17/22 (Device Run 2026-11-23).
                        "SM8750" to NpuVariantFile(
                            sha256 = "3b6266108cb13cd21e8445de71bb959a44576c5982677dcd8513a542219d1f60",
                            sizeBytes = 2_643_188_340L,
                        ),
                        "SM8850" to NpuVariantFile(
                            sha256 = "48c76e720a40e89de8518239c2adab2241659e10df72d7019b959cde7bc0c432",
                            sizeBytes = 2_646_366_836L,
                        ),
                    ),
                ),
                // MediaTek Dimensity 9300 (Galaxy Tab S10+: text 11/13 on 2026-11-22, photo + voice 2026-11-23).
                NpuVariantSet(
                    repo = NPU_E2B_REPO,
                    filePrefix = "gemma-4-E2B-it-v3mm",
                    revision = NPU_E2B_V3MM_REVISION,
                    vendor = "MediaTek",
                    files = mapOf(
                        "MT6989" to NpuVariantFile(
                            sha256 = "a9af92618d67f3663cf90e4ffbd99ac69de7bdfe23029a5d7f8f7a1f8d44ef00",
                            sizeBytes = 3_305_134_708L,
                        ),
                    ),
                ),
                // Google's Tensor G5 build at the same pinned revision (Pixel 10 reports Build.SOC_MODEL "Tensor G5").
                // This older file loads on the LiteRT 2.3.0 runtime (Device Run Pixel 10, 13/13 prompts, 2026-11-22);
                // the newer one (rev 2e12bfd8, sha 72d05606...) fails there ("Failed to get SB invocation context").
                NpuVariantSet(
                    repo = "litert-community/gemma-4-E2B-it-litert-lm",
                    filePrefix = "gemma-4-E2B-it",
                    revision = "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1",
                    vendor = "Google",
                    files = mapOf(
                        "Tensor G5" to NpuVariantFile(
                            sha256 = "af1082986639ecde7db95d91be6fe54f8b6b458104734c5bafc204e69d6852dc",
                            sizeBytes = 3_113_545_589L,
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
