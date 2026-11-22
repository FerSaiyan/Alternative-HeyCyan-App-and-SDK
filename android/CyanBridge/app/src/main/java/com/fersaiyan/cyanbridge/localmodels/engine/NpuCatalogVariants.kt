package com.fersaiyan.cyanbridge.localmodels.engine

import android.content.Context
import android.os.Build
import android.util.Log
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogEntry
import com.fersaiyan.cyanbridge.localmodels.catalog.NpuVariantResolver

/** Picks this phone's per-SoC NPU file for catalog entries with `npuVariants` (docs/NPU_ON_DEVICE.md). */
object NpuCatalogVariants {
    data class Resolution(val entry: LocalModelCatalogEntry?, val reason: String?)

    /**
     * Debug builds only: `adb shell setprop debug.cyanbridge.npu_download_test 1` lets the catalog resolve and download
     * this phone's per-SoC NPU file without the NPU runtime (an x86_64 emulator with a faked `ro.soc.model` cannot carry
     * the arm64 Qualcomm libraries). Loading stays refused by [LiteRtNpuSupport]. Release builds never read it.
     */
    private fun downloadTestMode(): Boolean {
        if (!BuildConfig.DEBUG) return false
        val now = android.os.SystemClock.elapsedRealtime()
        cachedTestMode?.let { (value, at) -> if (now - at < TEST_MODE_CACHE_MS) return value }
        val value = try {
            val process = ProcessBuilder("getprop", DOWNLOAD_TEST_PROPERTY).redirectErrorStream(true).start()
            val read = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor()
            read == "1"
        } catch (_: Exception) {
            false
        }
        cachedTestMode = value to now
        return value
    }

    /** The catalog screen resolves entries several times a second while a download runs. */
    @Volatile private var cachedTestMode: Pair<Boolean, Long>? = null
    private const val TEST_MODE_CACHE_MS = 5_000L

    fun forDevice(context: Context, entry: LocalModelCatalogEntry): Resolution {
        if (entry.npuVariants.isEmpty()) return Resolution(entry, null)
        val testMode = downloadTestMode()
        if (!BuildConfig.LITERT_NPU_RUNTIME && !testMode) return Resolution(null, "This app build has no NPU runtime.")
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null
        val resolved = NpuVariantResolver.resolve(entry, soc)
            ?: return Resolution(null, "No NPU build for this processor (${soc ?: "unknown"}) yet.")
        if (testMode) {
            Log.w(TAG, "NPU download test mode: ${entry.id} -> ${resolved.expectedFilename} for SoC $soc, runtime check skipped")
            return Resolution(resolved, null)
        }
        val npu = LiteRtNpuSupport.status(context)
        // A missing Hexagon skel is fetched together with the model (ModelDownloadForegroundService).
        return if (npu.available || npu.skelDownloadable) Resolution(resolved, null) else Resolution(null, npu.reason)
    }

    private const val TAG = "NpuCatalogVariants"
    private const val DOWNLOAD_TEST_PROPERTY = "debug.cyanbridge.npu_download_test"
}
