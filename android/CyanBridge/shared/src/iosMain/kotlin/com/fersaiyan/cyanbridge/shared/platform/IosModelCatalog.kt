package com.fersaiyan.cyanbridge.shared.platform

import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogEntry
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogRepository
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Model catalog for iOS: Android's curated list (LocalModelCatalogRepository, minus NPU-only
 * packages) followed by more GGUF / LiteRT-LM models that fit phones, from tiny test models
 * to 4B-parameter ones for 6–8 GB iPhones.
 */
internal object IosModelCatalog {
    val curated: List<LocalModelCatalogEntry> by lazy {
        LocalModelCatalogRepository.curatedModels.filter { it.enabled && !it.npuOnly && !it.comingSoon } + extraModels
    }

    private val extraModels = listOf(
        gguf("smollm2-135m-q8", "SmolLM2 135M Instruct (Q8_0)", "smollm", "bartowski/SmolLM2-135M-Instruct-GGUF",
            "SmolLM2-135M-Instruct-Q8_0.gguf", 144_811_360, "Q8_0", 2.0,
            "Tiny test model. Loads in seconds; English only and weak answers.", listOf("test", "fast"), "Apache-2.0."),
        gguf("smollm2-360m-q8", "SmolLM2 360M Instruct (Q8_0)", "smollm", "bartowski/SmolLM2-360M-Instruct-GGUF",
            "SmolLM2-360M-Instruct-Q8_0.gguf", 390_000_000, "Q8_0", 2.0,
            "Small and fast English chat model.", listOf("fast"), "Apache-2.0."),
        gguf("gemma3-270m-q8", "Gemma 3 270M IT (Q8_0)", "gemma", "ggml-org/gemma-3-270m-it-GGUF",
            "gemma-3-270m-it-Q8_0.gguf", 290_000_000, "Q8_0", 2.0,
            "Google's smallest Gemma 3. Very fast, simple answers.", listOf("fast"), "Use under Gemma model license terms."),
        LocalModelCatalogEntry(
            id = "qwen3-0.6b-litert", displayName = "Qwen3 0.6B (LiteRT-LM, int4)", family = "qwen", engine = "litert",
            format = "litertlm",
            sourceUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/qwen3_0_6b_mixed_int4.litertlm",
            sourcePageUrl = "https://huggingface.co/litert-community/Qwen3-0.6B",
            expectedFilename = "qwen3_0_6b_mixed_int4.litertlm", sha256 = null, sizeBytes = 497_516_544,
            quantization = "int4", contextSizeDefault = 2048, promptTemplateId = "qwen_chat", minRamGb = 3.0,
            minStorageGb = 0.6, shortDescription = "Fast LiteRT-LM model; may reason before answering.",
            tags = listOf("litert", "fast"), gatedDownload = false, licenseTermsNote = "Apache-2.0.", enabled = true,
        ),
        gguf("qwen25-05b-q4", "Qwen2.5 0.5B Instruct (Q4_K_M)", "qwen", "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
            "qwen2.5-0.5b-instruct-q4_k_m.gguf", 491_400_032, "Q4_K_M", 3.0,
            "Small multilingual chat model; understands Portuguese.", listOf("fast", "multilingual"), "Apache-2.0."),
        gguf("llama32-1b-q4", "Llama 3.2 1B Instruct (Q4_K_M)", "llama", "bartowski/Llama-3.2-1B-Instruct-GGUF",
            "Llama-3.2-1B-Instruct-Q4_K_M.gguf", 810_000_000, "Q4_K_M", 3.0,
            "Meta's compact assistant model. Good balance for 4 GB iPhones.", listOf("balanced"),
            "Llama 3.2 Community License."),
        gguf("gemma3-1b-q4", "Gemma 3 1B IT (Q4_K_M)", "gemma", "unsloth/gemma-3-1b-it-GGUF",
            "gemma-3-1b-it-Q4_K_M.gguf", 810_000_000, "Q4_K_M", 3.0,
            "Gemma 3 1B; multilingual and quick on recent iPhones.", listOf("balanced", "multilingual"),
            "Use under Gemma model license terms."),
        gguf("smollm2-1.7b-q4", "SmolLM2 1.7B Instruct (Q4_K_M)", "smollm", "bartowski/SmolLM2-1.7B-Instruct-GGUF",
            "SmolLM2-1.7B-Instruct-Q4_K_M.gguf", 1_060_000_000, "Q4_K_M", 4.0,
            "Larger SmolLM2 with better English answers.", listOf("balanced"), "Apache-2.0."),
        gguf("qwen3-1.7b-q4", "Qwen3 1.7B (Q4_K_M)", "qwen", "unsloth/Qwen3-1.7B-GGUF",
            "Qwen3-1.7B-Q4_K_M.gguf", 1_110_000_000, "Q4_K_M", 4.0,
            "Strong small multilingual model with reasoning.", listOf("quality", "multilingual"), "Apache-2.0."),
        LocalModelCatalogEntry(
            id = "qwen25-1.5b-litert", displayName = "Qwen2.5 1.5B Instruct (LiteRT-LM, q8)", family = "qwen",
            engine = "litert", format = "litertlm",
            sourceUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/" +
                "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            sourcePageUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct",
            expectedFilename = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm", sha256 = null,
            sizeBytes = 1_600_000_000, quantization = "q8", contextSizeDefault = 4096, promptTemplateId = "qwen_chat",
            minRamGb = 4.0, minStorageGb = 2.0, shortDescription = "Multilingual Qwen2.5 on LiteRT-LM.",
            tags = listOf("litert", "multilingual"), gatedDownload = false, licenseTermsNote = "Apache-2.0.", enabled = true,
        ),
        gguf("llama32-3b-q4", "Llama 3.2 3B Instruct (Q4_K_M)", "llama", "bartowski/Llama-3.2-3B-Instruct-GGUF",
            "Llama-3.2-3B-Instruct-Q4_K_M.gguf", 2_020_000_000, "Q4_K_M", 6.0,
            "Better answers; needs a Pro iPhone.", listOf("quality"), "Llama 3.2 Community License."),
        gguf("gemma3-4b-q4", "Gemma 3 4B IT (Q4_K_M)", "gemma", "unsloth/gemma-3-4b-it-GGUF",
            "gemma-3-4b-it-Q4_K_M.gguf", 2_490_000_000, "Q4_K_M", 6.0,
            "High-quality multilingual Gemma 3 (text only here).", listOf("quality", "multilingual"),
            "Use under Gemma model license terms."),
        gguf("qwen3-4b-q4", "Qwen3 4B (Q4_K_M)", "qwen", "unsloth/Qwen3-4B-GGUF",
            "Qwen3-4B-Q4_K_M.gguf", 2_500_000_000, "Q4_K_M", 6.0,
            "Best small Qwen for reasoning and Portuguese; needs 6–8 GB RAM.", listOf("quality", "multilingual"),
            "Apache-2.0."),
        gguf("phi4-mini-q4", "Phi-4 mini Instruct (Q4_K_M)", "phi", "unsloth/Phi-4-mini-instruct-GGUF",
            "Phi-4-mini-instruct-Q4_K_M.gguf", 2_490_000_000, "Q4_K_M", 6.0,
            "Microsoft's 3.8B model, strong at reasoning and English.", listOf("quality"), "MIT."),
    )

    private fun gguf(
        id: String,
        name: String,
        family: String,
        repo: String,
        file: String,
        size: Long,
        quantization: String,
        minRamGb: Double,
        description: String,
        tags: List<String>,
        license: String,
    ) = LocalModelCatalogEntry(
        id = id, displayName = name, family = family, engine = "llama", format = "gguf",
        sourceUrl = "https://huggingface.co/$repo/resolve/main/$file", sourcePageUrl = "https://huggingface.co/$repo",
        expectedFilename = file, sha256 = null, sizeBytes = size, quantization = quantization,
        contextSizeDefault = 4096, promptTemplateId = "auto", minRamGb = minRamGb,
        minStorageGb = size / 1_000_000_000.0 + 0.35, shortDescription = description, tags = tags,
        gatedDownload = false, licenseTermsNote = license, enabled = true,
    )

    fun matches(entry: LocalModelCatalogEntry, query: String): Boolean {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val haystack = "${entry.displayName} ${entry.family} ${entry.expectedFilename} ${entry.tags.joinToString(" ")}".lowercase()
        return words.all { it in haystack }
    }
}

/** Same checks and wording as Android's DeviceCapabilityService. */
@OptIn(ExperimentalForeignApi::class)
internal object IosDeviceCapability {
    data class Assessment(val blockers: List<String>, val warnings: List<String>) {
        val supported: Boolean get() = blockers.isEmpty()
    }

    private val COMMON_DEVICE_RAM_GB = doubleArrayOf(1.0, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0, 12.0, 16.0, 18.0, 24.0, 32.0)
    private const val GIB = 1024.0 * 1024.0 * 1024.0

    /** The simulator reports the Mac's memory, so it is treated as an 8 GB Pro Max-class iPhone. */
    val isSimulator: Boolean by lazy { NSProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] != null }

    /** Nominal RAM tier (a 4 GB iPhone reports a little less than 4 GB). */
    val ramGb: Double by lazy {
        if (isSimulator) return@lazy SIMULATED_RAM_GB
        val decimalGb = NSProcessInfo.processInfo.physicalMemory.toDouble() / 1_000_000_000.0
        COMMON_DEVICE_RAM_GB.minBy { abs(it - decimalGb) }
    }

    private const val SIMULATED_RAM_GB = 8.0

    private fun freeStorageBytes(): Long =
        (NSFileManager.defaultManager.attributesOfFileSystemForPath(NSHomeDirectory(), null)?.get(NSFileSystemFreeSize) as? NSNumber)
            ?.longLongValue ?: Long.MAX_VALUE

    fun assess(entry: LocalModelCatalogEntry): Assessment {
        val blockers = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (ramGb < entry.minRamGb) {
            blockers += "RAM unsuitable: this model needs at least ${format(entry.minRamGb, 1)} GB, " +
                "but this device has ${format(ramGb, 1)} GB."
        }
        val freeGb = freeStorageBytes() / GIB
        val requiredGb = entry.sizeBytes / GIB + 0.35
        if (freeGb < requiredGb) {
            blockers += "Not enough free storage. Need about ${format(requiredGb, 2)} GB."
        } else if (freeGb < entry.minStorageGb) {
            warnings += "Storage is close to the recommended minimum (${format(freeGb, 2)} GB free)."
        }
        if (NSProcessInfo.processInfo.activeProcessorCount.toInt() <= 4 &&
            ("quality" in entry.tags || entry.minRamGb >= 8.0)
        ) {
            warnings += "This model may feel slow on low core-count CPUs."
        }
        return Assessment(blockers, warnings)
    }

    fun format(value: Double, decimals: Int): String {
        var factor = 1
        repeat(decimals) { factor *= 10 }
        val scaled = (value * factor).roundToInt()
        val whole = scaled / factor
        val fraction = (scaled % factor).toString().padStart(decimals, '0')
        return if (decimals == 0) "$whole" else "$whole.$fraction"
    }
}

/**
 * Name search on Hugging Face for GGUF and LiteRT-LM repositories. Each result is reduced to
 * one recommended file (Q4_K_M first for GGUF; the generic package for LiteRT-LM).
 */
internal object IosHuggingFaceSearch {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = PlatformHttpClient()
    private const val API = "https://huggingface.co/api/models"
    private val QUANT_PRIORITY = listOf("Q4_K_M", "Q4_K_XL", "Q4_0", "Q4_K_S", "IQ4_XS", "IQ4_NL", "Q5_K_M", "Q8_0")
    /** Helper files that are not a chat model: vision projectors, MTP drafters, draft models. */
    private val AUXILIARY_FILE = Regex("(?i)(mmproj|(^|[-_.])mtp[-_.]|draft|assistant)")
    private val LITERT_DEVICE_SPECIFIC = listOf("tensor", "intel", "qualcomm", "mediatek", "web", "f32", "npu")

    suspend fun search(query: String, token: String): List<LocalModelCatalogEntry> = coroutineScope {
        val encoded = encode(query.trim())
        val headers = authHeaders(token)
        val ggufRepos = async { repoIds("$API?search=$encoded&filter=gguf&sort=downloads&direction=-1&limit=12", headers) }
        val litertRepos = async {
            repoIds("$API?search=$encoded&author=litert-community&sort=downloads&direction=-1&limit=6", headers)
        }
        val repos = (litertRepos.await() + ggufRepos.await()).distinct().take(14)
        repos.map { repo -> async { runCatching { entryFor(repo, headers) }.getOrNull() } }
            .awaitAll()
            .filterNotNull()
    }

    private suspend fun repoIds(url: String, headers: Map<String, String>): List<String> {
        val response = httpClient.get(url, headers)
        check(response.isSuccessful) { "Hugging Face returned ${response.statusCode}" }
        return json.parseToJsonElement(response.body).jsonArray.mapNotNull {
            (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
        }
    }

    private suspend fun entryFor(repo: String, headers: Map<String, String>): LocalModelCatalogEntry? {
        val response = httpClient.get("$API/$repo?blobs=true", headers)
        if (!response.isSuccessful) return null
        val info = json.parseToJsonElement(response.body).jsonObject
        val gatedValue = info["gated"]?.jsonPrimitive
        val gated = gatedValue?.booleanOrNull ?: (gatedValue?.contentOrNull?.let { it != "false" } ?: false)
        val files = (info["siblings"] as? JsonArray).orEmpty().mapNotNull { sibling ->
            val obj = sibling as? JsonObject ?: return@mapNotNull null
            val name = obj["rfilename"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L
            name to size
        }.filter { (name, size) ->
            size > 0 && !name.contains('/') && !AUXILIARY_FILE.containsMatchIn(name) &&
                !Regex("-\\d{5}-of-\\d{5}").containsMatchIn(name) &&
                (name.endsWith(".gguf", ignoreCase = true) || name.endsWith(".litertlm", ignoreCase = true))
        }
        val (file, size) = pickFile(files) ?: return null
        val isLiteRt = file.endsWith(".litertlm", ignoreCase = true)
        val quantization = QUANT_PRIORITY.firstOrNull { file.contains(it, ignoreCase = true) }
            ?: Regex("(?i)(int4|int8|q8|q4|f16|bf16)").find(file)?.value ?: if (isLiteRt) "LiteRT-LM" else "GGUF"
        val sizeGb = size / 1_000_000_000.0
        val minRam = listOf(2.0, 3.0, 4.0, 6.0, 8.0, 12.0, 16.0, 24.0).firstOrNull { it >= sizeGb * 1.2 + 1.5 } ?: 32.0
        val downloads = info["downloads"]?.jsonPrimitive?.longOrNull ?: 0L
        return LocalModelCatalogEntry(
            id = "hf:$repo/$file",
            displayName = "${repo.substringAfter('/')} ($quantization)",
            family = repo.substringBefore('/'),
            engine = if (isLiteRt) "litert" else "llama",
            format = if (isLiteRt) "litertlm" else "gguf",
            sourceUrl = "https://huggingface.co/$repo/resolve/main/$file",
            sourcePageUrl = "https://huggingface.co/$repo",
            expectedFilename = file,
            sha256 = null,
            sizeBytes = size,
            quantization = quantization,
            contextSizeDefault = 4096,
            promptTemplateId = "auto",
            minRamGb = minRam,
            minStorageGb = sizeGb + 0.35,
            shortDescription = "From $repo · $downloads downloads. RAM need is estimated from the file size.",
            tags = listOf("huggingface"),
            gatedDownload = gated,
            licenseTermsNote = "Check the license on the model page.",
            enabled = true,
        )
    }

    private fun pickFile(files: List<Pair<String, Long>>): Pair<String, Long>? {
        val litert = files.filter { it.first.endsWith(".litertlm", ignoreCase = true) }
        if (litert.isNotEmpty()) {
            val generic = litert.filter { (name, _) -> LITERT_DEVICE_SPECIFIC.none { name.contains(it, ignoreCase = true) } }
            return (generic.ifEmpty { litert }).minBy { it.second }
        }
        QUANT_PRIORITY.forEach { quant ->
            files.filter { it.first.contains(quant, ignoreCase = true) }.minByOrNull { it.second }?.let { return it }
        }
        return files.minByOrNull { it.second }
    }

    fun authHeaders(token: String): Map<String, String> =
        if (token.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${token.trim()}")

    private fun encode(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val c = byte.toInt().toChar()
            if (c.isLetterOrDigit() && byte >= 0 || c in "-_.~") append(c)
            else append('%').append(((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1).uppercase())
        }
    }
}
