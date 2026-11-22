package com.fersaiyan.cyanbridge.localmodels.engine

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import com.fersaiyan.cyanbridge.BuildConfig
import java.io.File

/**
 * Preflight for the NPU path (docs/NPU_ON_DEVICE.md) on Qualcomm, MediaTek, Google Tensor and Samsung Exynos.
 *
 * - LiteRT-LM Backend.NPU and LiteRT CompiledModel load a `libLiteRtDispatch_<Vendor>.so` from the directory they are
 *   given. LiteRT 2.3.0 takes the FIRST dispatch library it finds there and never checks the SoC (LiteRT issue
 *   #11250), and the app ships all four in nativeLibraryDir. [dispatchDir] therefore builds a per-vendor directory of
 *   symlinks to nativeLibraryDir that holds only this phone's vendor dispatch library (plus the shared runtime and,
 *   for Qualcomm, the QNN libraries and the Hexagon skel). Symlinks keep the libraries executable: Android refuses to
 *   load a copy from app storage.
 * - NPU-compiled models only run on the SoC they were compiled for; their file names carry `_<Vendor>_<SoC>`.
 * Checking this first turns an opaque native failure into a fallback reason.
 */
internal object LiteRtNpuSupport {
    enum class Vendor(val label: String, val dispatchLibrary: String, val fileTag: String) {
        QUALCOMM("Qualcomm", "libLiteRtDispatch_Qualcomm.so", "Qualcomm"),
        MEDIATEK("MediaTek", "libLiteRtDispatch_MediaTek.so", "MediaTek"),
        // fileTag + "_" + socModel: the canonical Tensor SoC id already carries "Tensor" (Google + Tensor_G5).
        GOOGLE_TENSOR("Google Tensor", "libLiteRtDispatch_GoogleTensor.so", "Google"),
        SAMSUNG("Samsung Exynos", "libLiteRtDispatch_Samsung.so", "Samsung"),
    }

    /** Hexagon DSP architecture per Snapdragon SoC (QNN library suffix). */
    private val HEXAGON_BY_SOC = mapOf(
        "SM8350" to "V68", "SA8295" to "V68",
        "SM8450" to "V69", "SM8475" to "V69",
        "SM8550" to "V73", "SA8255" to "V73",
        "SM8650" to "V75",
        "SM8750" to "V79",
        "SM8850" to "V81",
    )

    private const val TAG = "LiteRtNpuSupport"

    /**
     * [socModel] is the canonical SoC id used in model file names and the NPU model manifest (SM8650, MT6989,
     * Tensor_G5, E9965); [modelTag] is `<Vendor>_<SoC>` as in `*_<modelTag>.tflite`.
     */
    data class Status(
        val available: Boolean,
        val socModel: String?,
        val reason: String,
        val vendor: Vendor? = null,
        val hexagon: String? = null,
    ) {
        val modelTag: String? get() = if (vendor != null && socModel != null) "${vendor.fileTag}_$socModel" else null
    }

    /** The NPU vendor of a phone, from Build.SOC_MANUFACTURER / Build.SOC_MODEL. */
    fun vendorFor(manufacturer: String?, socModel: String?): Vendor? {
        val man = manufacturer.orEmpty().lowercase()
        val soc = socModel.orEmpty().trim()
        return when {
            "qti" in man || "qualcomm" in man || Regex("^(SM|SA|QCS)\\d", RegexOption.IGNORE_CASE).containsMatchIn(soc) ->
                Vendor.QUALCOMM
            "mediatek" in man || Regex("^MT\\d", RegexOption.IGNORE_CASE).containsMatchIn(soc) -> Vendor.MEDIATEK
            man == "google" || soc.startsWith("Tensor", ignoreCase = true) -> Vendor.GOOGLE_TENSOR
            "samsung" in man || soc.startsWith("s5e", ignoreCase = true) -> Vendor.SAMSUNG
            else -> null
        }
    }

    /** The SoC id as it appears in model file names: SM8650, MT6989, Tensor_G5, E9965 (from `s5e9965`). */
    fun canonicalSoc(vendor: Vendor, socModel: String): String = when (vendor) {
        Vendor.SAMSUNG -> socModel.trim().replace(Regex("^s5e", RegexOption.IGNORE_CASE), "E").uppercase()
        Vendor.GOOGLE_TENSOR -> Regex("G\\d+", RegexOption.IGNORE_CASE).find(socModel)?.let { "Tensor_" + it.value.uppercase() }
            ?: socModel.trim().replace(Regex("\\s+"), "_")
        // Some vendor builds decorate the id ("MT6989V/CZA", "SM8650-AB"); model files use the bare part number.
        Vendor.MEDIATEK -> Regex("MT\\d{4}", RegexOption.IGNORE_CASE).find(socModel)?.value?.uppercase()
            ?: socModel.trim().uppercase()
        Vendor.QUALCOMM -> Regex("(SM|SA|QCS)\\d{4}", RegexOption.IGNORE_CASE).find(socModel)?.value?.uppercase()
            ?: socModel.trim().uppercase()
    }

    fun status(context: Context): Status {
        if (!BuildConfig.LITERT_NPU_RUNTIME) {
            return Status(false, null, "this build ships no NPU runtime (-PlocalLiteRtNpuAarPath)")
        }
        val rawSoc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null
        val manufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else null
        val vendor = vendorFor(manufacturer, rawSoc)
            ?: return Status(false, rawSoc, "SoC ${rawSoc ?: "unknown (API < 31)"} has no supported NPU")
        val soc = canonicalSoc(vendor, rawSoc.orEmpty())
        return statusFor(vendor, soc, File(context.applicationInfo.nativeLibraryDir), QnnSkels.dir(context))
    }

    /** Pure part of [status], for tests. */
    internal fun statusFor(vendor: Vendor, soc: String, libDir: File, skelDir: File?): Status {
        val required = mutableListOf(vendor.dispatchLibrary)
        var hexagon: String? = null
        when (vendor) {
            Vendor.QUALCOMM -> {
                hexagon = HEXAGON_BY_SOC[soc]
                    ?: return Status(false, soc, "Qualcomm $soc has no supported Hexagon NPU", vendor)
                required += listOf("libQnnHtp.so", "libQnnSystem.so", "libQnnHtp${hexagon}Stub.so")
                if (QnnSkels.find(libDir, skelDir, hexagon) == null) {
                    return Status(false, soc, "the Hexagon $hexagon NPU library for $soc is not downloaded yet", vendor, hexagon)
                }
            }
            // The vendor NPU driver comes with the phone (NeuroPilot, edgetpu, ENN); whether a model exists for this
            // SoC is the catalog's call, and a driver the dispatch library cannot open fails the load (CPU/GPU fallback).
            Vendor.MEDIATEK, Vendor.GOOGLE_TENSOR, Vendor.SAMSUNG -> Unit
        }
        val missing = required.filterNot { File(libDir, it).isFile }
        if (missing.isNotEmpty()) {
            return Status(false, soc, "NPU runtime for $soc is not installed in $libDir: ${missing.joinToString()}", vendor, hexagon)
        }
        val detail = hexagon?.let { " (Hexagon $it)" }.orEmpty()
        return Status(true, soc, "${vendor.label} $soc$detail", vendor, hexagon)
    }

    /**
     * The directory to hand LiteRT as its dispatch / native library directory for [status]'s vendor: symlinks to
     * every library in nativeLibraryDir except the other vendors' dispatch libraries, plus the downloaded Hexagon
     * skel on Qualcomm. Falls back to nativeLibraryDir when the links cannot be made.
     */
    fun dispatchDir(context: Context, status: Status): String {
        val native = File(context.applicationInfo.nativeLibraryDir)
        val vendor = status.vendor ?: return native.path
        return runCatching {
            val dir = File(context.noBackupFilesDir, "npu-dispatch/${vendor.name.lowercase()}")
            dir.mkdirs()
            val wanted = linkTargets(vendor, native, QnnSkels.dir(context), status.hexagon)
            dir.listFiles()?.forEach { link -> if (link.name !in wanted) link.delete() }
            for ((name, target) in wanted) {
                val link = File(dir, name)
                val current = runCatching { Os.readlink(link.path) }.getOrNull()
                if (current == target.path) continue
                link.delete()
                Os.symlink(target.path, link.path)
            }
            dir.path
        }.getOrElse { error ->
            Log.w(TAG, "Per-vendor dispatch directory failed; using nativeLibraryDir", error)
            native.path
        }
    }

    /** name -> target for [dispatchDir]. */
    internal fun linkTargets(vendor: Vendor, native: File, skelDir: File?, hexagon: String?): Map<String, File> {
        val otherDispatch = Vendor.entries.filter { it != vendor }.map { it.dispatchLibrary }.toSet()
        val links = native.listFiles { file -> file.isFile && file.name.endsWith(".so") && file.name !in otherDispatch }
            .orEmpty().associateBy({ it.name }, { it }).toMutableMap()
        if (vendor == Vendor.QUALCOMM && hexagon != null) {
            QnnSkels.find(native, skelDir, hexagon)?.let { links[it.name] = it }
        }
        return links
    }

    /** The vendor and SoC an NPU model file was compiled for, from its name. */
    data class CompiledTarget(val vendor: Vendor, val socModel: String)

    /**
     * Model files compiled for one SoC carry it in their name: `*_Qualcomm_SM8650.litertlm`, Google's
     * `*_qualcomm_sm8750.litertlm`, `*_MediaTek_MT6991_apply_plugin.tflite`, `*_Google_Tensor_G5.litertlm`,
     * `*_Samsung_E9965.tflite`.
     */
    fun compiledTarget(modelPath: String): CompiledTarget? {
        val name = File(modelPath).name
        val patterns = listOf(
            Vendor.QUALCOMM to Regex("""_Qualcomm_((?:SM|SA|QCS)\d{4})""", RegexOption.IGNORE_CASE),
            Vendor.MEDIATEK to Regex("""_MediaTek_(MT\d{4})""", RegexOption.IGNORE_CASE),
            Vendor.GOOGLE_TENSOR to Regex("""_Google_Tensor_(G\d+)""", RegexOption.IGNORE_CASE),
            Vendor.SAMSUNG to Regex("""_Samsung_((?:E|S5E)\d{4})""", RegexOption.IGNORE_CASE),
        )
        for ((vendor, pattern) in patterns) {
            val soc = pattern.find(name)?.groupValues?.get(1) ?: continue
            val canonical = when (vendor) {
                Vendor.GOOGLE_TENSOR -> "Tensor_" + soc.uppercase()
                else -> canonicalSoc(vendor, soc)
            }
            return CompiledTarget(vendor, canonical)
        }
        return null
    }

    /** Back-compat: the SoC a model was compiled for (any vendor). */
    fun compiledFor(modelPath: String): String? = compiledTarget(modelPath)?.socModel
}

/**
 * The Hexagon DSP skel (`libQnnHtpV<arch>Skel.so`, 11-14 MB each) for the Qualcomm NPU. Release builds leave it out of
 * the APK and download only this phone's one (sha256-pinned); debug/test builds may still bundle it in
 * nativeLibraryDir. The DSP loads it through FastRPC from ADSP_LIBRARY_PATH (the dispatch directory, which links it).
 */
internal object QnnSkels {
    fun dir(context: Context): File = File(context.noBackupFilesDir, "npu-runtime/qnn-skel")

    fun fileName(hexagon: String): String = "libQnnHtp${hexagon}Skel.so"

    fun find(native: File, downloaded: File?, hexagon: String): File? =
        listOfNotNull(File(native, fileName(hexagon)), downloaded?.let { File(it, fileName(hexagon)) })
            .firstOrNull { it.isFile }
}
