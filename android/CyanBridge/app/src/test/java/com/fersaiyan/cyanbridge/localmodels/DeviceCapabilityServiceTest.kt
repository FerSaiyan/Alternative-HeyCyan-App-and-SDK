package com.fersaiyan.cyanbridge.localmodels

import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogRepository
import com.fersaiyan.cyanbridge.localmodels.device.DeviceCapabilityService
import com.fersaiyan.cyanbridge.localmodels.device.DeviceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilityServiceTest {
    private val gib = 1024L * 1024L * 1024L

    private fun assess(
        modelId: String,
        ramBytes: Long,
        storageBytes: Long = 8 * gib,
        abi: String = "arm64-v8a",
        requireDownloadHeadroom: Boolean = true,
    ) = DeviceCapabilityService.assess(
        DeviceSnapshot(
            primaryAbi = abi,
            supportedAbis = listOf(abi),
            totalRamBytes = ramBytes,
            freeStorageBytes = storageBytes,
            cpuCoreCount = 8,
        ),
        LocalModelCatalogRepository.findById(modelId)!!,
        requireDownloadHeadroom = requireDownloadHeadroom,
    )

    @Test
    fun ram_floors_gate_each_model() {
        // model id, RAM in GiB, expected ramSuitable/supported
        listOf(
            Triple("gemma4-e2b-it-litert", 6L, true),
            Triple("gemma4-e2b-it-litert", 4L, false),
            Triple("gemma4-e4b-it-litert", 6L, false),
            Triple("qwen3.5-0.8b-q4", 4L, true),
        ).forEach { (model, ramGb, expected) ->
            val result = assess(model, ramGb * gib, storageBytes = 10 * gib, requireDownloadHeadroom = model != "qwen3.5-0.8b-q4")
            assertEquals("$model at $ramGb GB", expected, result.ramSuitable)
            assertEquals("$model at $ramGb GB", expected, result.supported)
            if (!expected) assertTrue(result.blockers.any { it.contains("RAM unsuitable") })
        }
    }

    @Test
    fun unsupported_abi_and_low_storage_are_blocked() {
        val abi = assess("qwen3.5-0.8b-q4", 8 * gib, abi = "armeabi-v7a")
        assertFalse(abi.supported)
        assertTrue(abi.blockers.isNotEmpty())

        val storage = assess("qwen3.5-0.8b-q4", 12 * gib, storageBytes = 400L * 1024L * 1024L)
        assertFalse(storage.supported)
        assertTrue(storage.blockers.any { it.contains("free storage", ignoreCase = true) })
    }

    @Test
    fun total_ram_uses_nominal_device_tiers_instead_of_binary_gib() {
        assertEquals(8.0, DeviceCapabilityService.totalRamGb(7_730_000_000L), 0.0)
        assertEquals(12.0, DeviceCapabilityService.totalRamGb(12_000_000_000L), 0.0)
        assertEquals(8.0, DeviceCapabilityService.totalRamGb(8L * gib), 0.0)
    }
}
