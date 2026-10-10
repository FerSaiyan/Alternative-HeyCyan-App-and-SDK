package com.fersaiyan.cyanbridge.localmodels.engine

import android.content.Context
import android.os.Build
import com.fersaiyan.cyanbridge.BuildConfig
import java.io.File

/**
 * Preflight for the Qualcomm NPU path (docs/NPU_ON_DEVICE.md). LiteRT-LM Backend.NPU and
 * LiteRT CompiledModel both load libLiteRtDispatch_Qualcomm.so and the QNN runtime from
 * nativeLibraryDir, and NPU-compiled models only run on the SoC they were compiled for.
 * Checking this first turns an opaque native failure into a fallback reason.
 */
internal object LiteRtNpuSupport {
    /** Hexagon DSP architecture per Snapdragon SoC (QNN library suffix). */
    private val HEXAGON_BY_SOC = mapOf(
        "SM8450" to "V69",
        "SM8550" to "V73",
        "SM8650" to "V75",
        "SM8750" to "V79",
        "SM8850" to "V81",
    )

    data class Status(val available: Boolean, val socModel: String?, val reason: String)

    fun status(context: Context): Status {
        if (!BuildConfig.LITERT_NPU_RUNTIME) {
            return Status(false, null, "this build ships no NPU runtime (-PlocalLiteRtNpuAarPath)")
        }
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null
        val hexagon = soc?.let { HEXAGON_BY_SOC[it] }
            ?: return Status(false, soc, "SoC ${soc ?: "unknown (API < 31)"} has no supported Qualcomm NPU")
        val libDir = File(context.applicationInfo.nativeLibraryDir)
        val missing = listOf(
            "libLiteRtDispatch_Qualcomm.so",
            "libQnnHtp.so",
            "libQnnSystem.so",
            "libQnnHtp${hexagon}Stub.so",
            "libQnnHtp${hexagon}Skel.so",
        ).filterNot { File(libDir, it).isFile }
        if (missing.isNotEmpty()) {
            return Status(false, soc, "NPU runtime for $soc is not installed in $libDir: ${missing.joinToString()}")
        }
        return Status(true, soc, "Qualcomm $soc (Hexagon $hexagon)")
    }

    /**
     * Model files compiled for one SoC carry its name, e.g. `*_Qualcomm_SM8650.litertlm` or
     * Google's `*_qualcomm_sm8750.litertlm`.
     */
    fun compiledFor(modelPath: String): String? =
        Regex("""_Qualcomm_(SM\d{4})""", RegexOption.IGNORE_CASE).find(File(modelPath).name)
            ?.groupValues?.get(1)?.uppercase()
}
