package com.fersaiyan.cyanbridge.localmodels.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogEntry
import kotlin.math.abs

data class DeviceSnapshot(
    val primaryAbi: String,
    val supportedAbis: List<String>,
    val totalRamBytes: Long,
    val freeStorageBytes: Long,
    val cpuCoreCount: Int,
)

data class DeviceCapabilityAssessment(
    val supported: Boolean,
    val blockers: List<String>,
    val warnings: List<String>,
    val ramGb: Double = 0.0,
    val ramSuitable: Boolean = true,
)

object DeviceCapabilityService {
    private const val GIB = 1024.0 * 1024.0 * 1024.0
    private const val DECIMAL_GB = 1_000_000_000.0
    private val COMMON_DEVICE_RAM_GB = doubleArrayOf(
        1.0, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0, 10.0, 12.0, 16.0, 18.0, 20.0, 24.0, 32.0, 48.0, 64.0,
    )

    fun snapshot(context: Context): DeviceSnapshot {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(mem)

        val statFs = StatFs(context.filesDir.absolutePath)

        return DeviceSnapshot(
            primaryAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            totalRamBytes = mem.totalMem,
            freeStorageBytes = statFs.availableBytes,
            cpuCoreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
        )
    }

    fun assess(
        snapshot: DeviceSnapshot,
        entry: LocalModelCatalogEntry,
        requireDownloadHeadroom: Boolean,
    ): DeviceCapabilityAssessment {
        val blockers = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val abiSupported = snapshot.supportedAbis.any { it == "arm64-v8a" || it == "x86_64" }
        if (!abiSupported) {
            blockers += "This device ABI is not supported by the current local llama.cpp binding."
        }

        val ramGb = totalRamGb(snapshot.totalRamBytes)
        val ramSuitable = ramGb >= entry.minRamGb
        if (!ramSuitable) {
            blockers += "RAM unsuitable: this model needs at least ${String.format("%.1f", entry.minRamGb)} GB, but this device has ${String.format("%.1f", ramGb)} GB."
        }

        val freeGb = snapshot.freeStorageBytes / GIB
        val requiredForDownloadGb = if (requireDownloadHeadroom) {
            (entry.sizeBytes / GIB) + 0.35
        } else {
            entry.minStorageGb
        }
        if (freeGb < requiredForDownloadGb) {
            blockers += "Not enough free storage. Need about ${String.format("%.2f", requiredForDownloadGb)} GB."
        } else if (freeGb < entry.minStorageGb) {
            warnings += "Storage is close to the recommended minimum (${String.format("%.2f", freeGb)} GB free)."
        }

        if (snapshot.cpuCoreCount <= 4 && (entry.tags.contains("quality") || entry.minRamGb >= 8.0)) {
            warnings += "This model may feel slow on low core-count CPUs."
        }

        return DeviceCapabilityAssessment(
            supported = blockers.isEmpty(),
            blockers = blockers,
            warnings = warnings,
            ramGb = ramGb,
            ramSuitable = ramSuitable,
        )
    }

    /**
     * Android reports RAM available to the kernel, while device specifications use nominal
     * decimal GB and may include a small reserved region. Map that value to common device tiers
     * so an 8 GB or 12 GB phone is not presented as a 7.2 GiB or 11.2 GiB device.
     */
    fun totalRamGb(totalRamBytes: Long): Double {
        if (totalRamBytes <= 0L) return 0.0
        val decimalGb = totalRamBytes / DECIMAL_GB
        return COMMON_DEVICE_RAM_GB.minBy { tier -> abs(tier - decimalGb) }
    }
}
