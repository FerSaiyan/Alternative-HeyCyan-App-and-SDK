package com.fersaiyan.cyanbridge.localmodels.engine

import android.content.Context
import android.os.Build
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogEntry
import com.fersaiyan.cyanbridge.localmodels.catalog.NpuVariantResolver

/** Picks this phone's per-SoC NPU file for catalog entries with `npuVariants` (docs/NPU_ON_DEVICE.md). */
object NpuCatalogVariants {
    data class Resolution(val entry: LocalModelCatalogEntry?, val reason: String?)

    fun forDevice(context: Context, entry: LocalModelCatalogEntry): Resolution {
        if (entry.npuVariants == null) return Resolution(entry, null)
        if (!BuildConfig.LITERT_NPU_RUNTIME) return Resolution(null, "This app build has no NPU runtime.")
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null
        val resolved = NpuVariantResolver.resolve(entry, soc)
            ?: return Resolution(null, "No NPU build for this processor (${soc ?: "unknown"}) yet.")
        val npu = LiteRtNpuSupport.status(context)
        return if (npu.available) Resolution(resolved, null) else Resolution(null, npu.reason)
    }
}
