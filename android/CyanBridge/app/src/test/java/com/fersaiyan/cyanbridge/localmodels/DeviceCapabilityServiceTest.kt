package com.fersaiyan.cyanbridge.localmodels

import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogRepository
import com.fersaiyan.cyanbridge.localmodels.device.DeviceCapabilityService
import com.fersaiyan.cyanbridge.localmodels.device.DeviceSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilityServiceTest {
    @Test
    fun unsupported_abi_is_blocked() {
        val entry = LocalModelCatalogRepository.findById("qwen3.5-0.8b-q4")!!
        val snapshot = DeviceSnapshot(
            primaryAbi = "armeabi-v7a",
            supportedAbis = listOf("armeabi-v7a"),
            totalRamBytes = 8L * 1024L * 1024L * 1024L,
            freeStorageBytes = 8L * 1024L * 1024L * 1024L,
            cpuCoreCount = 8,
        )

        val result = DeviceCapabilityService.assess(snapshot, entry, requireDownloadHeadroom = true)
        assertFalse(result.supported)
        assertTrue(result.blockers.isNotEmpty())
    }

    @Test
    fun low_storage_is_blocked_for_download() {
        val entry = LocalModelCatalogRepository.findById("qwen3.5-0.8b-q4")!!
        val snapshot = DeviceSnapshot(
            primaryAbi = "arm64-v8a",
            supportedAbis = listOf("arm64-v8a"),
            totalRamBytes = 12L * 1024L * 1024L * 1024L,
            freeStorageBytes = 400L * 1024L * 1024L,
            cpuCoreCount = 8,
        )

        val result = DeviceCapabilityService.assess(snapshot, entry, requireDownloadHeadroom = true)
        assertFalse(result.supported)
        assertTrue(result.blockers.any { it.contains("free storage", ignoreCase = true) })
    }

    @Test
    fun gemma_e2b_is_supported_at_six_gb() {
        val entry = LocalModelCatalogRepository.findById("gemma4-e2b-it-litert")!!
        val snapshot = DeviceSnapshot(
            primaryAbi = "arm64-v8a",
            supportedAbis = listOf("arm64-v8a"),
            totalRamBytes = 6L * 1024L * 1024L * 1024L,
            freeStorageBytes = 8L * 1024L * 1024L * 1024L,
            cpuCoreCount = 8,
        )

        val result = DeviceCapabilityService.assess(snapshot, entry, requireDownloadHeadroom = true)
        assertTrue(result.supported)
        assertTrue(result.ramSuitable)
    }

    @Test
    fun gemma_e2b_is_blocked_below_six_gb() {
        val entry = LocalModelCatalogRepository.findById("gemma4-e2b-it-litert")!!
        val snapshot = DeviceSnapshot(
            primaryAbi = "arm64-v8a",
            supportedAbis = listOf("arm64-v8a"),
            totalRamBytes = 4L * 1024L * 1024L * 1024L,
            freeStorageBytes = 8L * 1024L * 1024L * 1024L,
            cpuCoreCount = 8,
        )

        val result = DeviceCapabilityService.assess(snapshot, entry, requireDownloadHeadroom = true)
        assertFalse(result.supported)
        assertFalse(result.ramSuitable)
        assertTrue(result.blockers.any { it.contains("RAM unsuitable") })
    }

    @Test
    fun gemma_e4b_still_requires_eight_gb() {
        val entry = LocalModelCatalogRepository.findById("gemma4-e4b-it-litert")!!
        val snapshot = DeviceSnapshot(
            primaryAbi = "arm64-v8a",
            supportedAbis = listOf("arm64-v8a"),
            totalRamBytes = 6L * 1024L * 1024L * 1024L,
            freeStorageBytes = 10L * 1024L * 1024L * 1024L,
            cpuCoreCount = 8,
        )

        val result = DeviceCapabilityService.assess(snapshot, entry, requireDownloadHeadroom = true)
        assertFalse(result.ramSuitable)
        assertFalse(result.supported)
    }

    @Test
    fun compact_qwen_is_supported_at_its_declared_ram_floor() {
        val entry = LocalModelCatalogRepository.findById("qwen3.5-0.8b-q4")!!
        val snapshot = DeviceSnapshot(
            primaryAbi = "arm64-v8a",
            supportedAbis = listOf("arm64-v8a"),
            totalRamBytes = 4L * 1024L * 1024L * 1024L,
            freeStorageBytes = 8L * 1024L * 1024L * 1024L,
            cpuCoreCount = 6,
        )

        val result = DeviceCapabilityService.assess(snapshot, entry, requireDownloadHeadroom = false)
        assertTrue(result.ramSuitable)
        assertTrue(result.supported)
    }

    @Test
    fun total_ram_uses_nominal_device_tiers_instead_of_binary_gib() {
        assertEquals(8.0, DeviceCapabilityService.totalRamGb(7_730_000_000L), 0.0)
        assertEquals(12.0, DeviceCapabilityService.totalRamGb(12_000_000_000L), 0.0)
        assertEquals(8.0, DeviceCapabilityService.totalRamGb(8L * 1024L * 1024L * 1024L), 0.0)
    }
}
