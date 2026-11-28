package com.fersaiyan.cyanbridge.shared.litert

/**
 * The small LiteRT models CyanBridge downloads at run time (Walking Aid YOLO11n, the PP-OCRv6 reader, the WeSpeaker
 * voice filter), as published in the Hugging Face repo Fertroll10/cyanbridge-npu-models. [LiteRtModelCatalog] (generated)
 * lists the CPU/GPU files with their pinned URL, size, SHA-256, tensors and how each platform should run them; the NPU
 * builds stay in the Android app's NpuArtifacts. Platform code supplies a [LiteRtModelInstaller] (download + verify)
 * and a [LiteRtModelRunner] (LiteRT CompiledModel); everything else here is shared.
 */
enum class LiteRtModelKind {
    YOLO11N,
    OCR_DET,
    OCR_REC,
    /** Recognizer dictionary: class 0 = CTC blank, line i = class i, last class = space (see ocr_spec.json). */
    OCR_DICT,
    /** PP-OCRv6 pre/post-processing spec (JSON). */
    OCR_SPEC,
    WESPEAKER,
}

enum class LiteRtAccelerator { GPU, CPU }

/** LiteRT GPU accelerator precision (LiteRtDelegatePrecision: DEFAULT 0, FP16 1, FP32 2, FP16_WITH_FP32_ACCUM 3). */
enum class LiteRtGpuPrecision(val code: Int) { DEFAULT(0), FP16(1), FP32(2), FP16_WITH_FP32_ACCUM(3) }

class LiteRtTensorSpec(val name: String, val shape: List<Int>, val dtype: String) {
    val elements: Int get() = shape.fold(1) { acc, d -> acc * d }
    override fun toString(): String = "$name$shape:$dtype"
}

/** How a platform should run one file, from device runs (e.g. Google Device Run iPhones, 2026-11-27). */
data class LiteRtRunHint(
    val accelerator: LiteRtAccelerator,
    /** GPU precision when [accelerator] is GPU. */
    val gpuPrecision: LiteRtGpuPrecision?,
    /** Where to go when [accelerator] fails to compile or disagrees with the CPU. */
    val fallback: LiteRtAccelerator,
    /** Keep a GPU program cache (cuts later model creation from ~0.1-1 s to ~10-20 ms). */
    val programCache: Boolean,
    /** Lowest cosine vs the CPU seen on device for real inputs (null: not measured). */
    val minCosVsCpu: Double?,
    /** Among several files of one [LiteRtModelKind], the one to download. */
    val preferred: Boolean,
)

data class LiteRtModelFile(
    val kind: LiteRtModelKind,
    val modelId: String,
    /** Path in the repo (also the stable identity of the file). */
    val path: String,
    /** Download URL pinned to [LiteRtModelCatalog.REVISION]. */
    val url: String,
    val sha256: String,
    val bytes: Long,
    /** The file's own tensors in signature order (empty for data files). */
    val inputs: List<LiteRtTensorSpec>,
    val outputs: List<LiteRtTensorSpec>,
    /** WeSpeaker: embeddings of different variants are not comparable; store it with each enrolment. */
    val embeddingVariant: String?,
    /** iOS run info; null for files iOS does not run (or data files, see [iosSupportFile]). */
    val ios: LiteRtRunHint?,
    /** A data file iOS needs next to a model (OCR dictionary, spec). */
    val iosSupportFile: Boolean,
) {
    /** File name on the device: the repo file name prefixed with the first 12 hex of its SHA-256 (a new build never overwrites an old one). */
    val localName: String get() = "${sha256.take(12)}-${path.substringAfterLast('/')}"
    val isModel: Boolean get() = path.endsWith(".tflite")
}

/** Which catalog files a platform uses. */
object LiteRtModelSelection {
    /** The iOS model file for [kind]: the preferred one when several are listed, else the first. */
    fun iosModel(kind: LiteRtModelKind, files: List<LiteRtModelFile> = LiteRtModelCatalog.files): LiteRtModelFile? {
        val candidates = files.filter { it.kind == kind && it.isModel && it.ios != null }
        return candidates.firstOrNull { it.ios?.preferred == true } ?: candidates.firstOrNull()
    }

    /** Everything an iOS install needs: one model per kind plus the support files. */
    fun iosFiles(files: List<LiteRtModelFile> = LiteRtModelCatalog.files): List<LiteRtModelFile> {
        val models = LiteRtModelKind.entries.mapNotNull { iosModel(it, files) }
        val support = files.filter { it.iosSupportFile }
        return models + support
    }
}
