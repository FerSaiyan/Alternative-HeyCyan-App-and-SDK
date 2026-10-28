package com.fersaiyan.cyanbridge.shared.ui

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.ScannedDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceBindViewModelTest {
    private fun device(detected: DeviceClass) = ScannedDevice("AA:BB", "Glasses", -50, detected, null, false)

    @Test
    fun consumerGlassesOpenTheManualPickerAndConnectAsTheChosenProtocol() {
        val viewModel = DeviceBindViewModel()
        viewModel.selectDevice(device(DeviceClass.TUNEBUDS))
        assertEquals(DeviceClass.HEY_CYAN, viewModel.selectedClass)

        assertNull(viewModel.confirmManualProtocol(DeviceClass.EYEVUE, canConnect = { false }))
        assertEquals("AA:BB", viewModel.connectingDevice?.macAddress)

        val connection = viewModel.confirmManualProtocol(DeviceClass.EYEVUE, canConnect = { true })
        assertEquals(DeviceClass.EYEVUE, connection?.deviceClass)
        assertNull(viewModel.connectingDevice)
    }

    @Test
    fun theFirstPickerConnectsMetaAsChosenAndFallsBackToHeyCyanForUnknown() {
        val viewModel = DeviceBindViewModel()
        viewModel.selectDevice(device(DeviceClass.META_RAYBAN))
        assertEquals(DeviceClass.META_RAYBAN, viewModel.selectedClass)
        assertEquals(DeviceClass.META_RAYBAN, viewModel.confirmConnection { true }?.deviceClass)

        viewModel.selectDevice(device(DeviceClass.UNKNOWN))
        viewModel.selectDeviceClass(DeviceClass.UNKNOWN)
        assertEquals(DeviceClass.HEY_CYAN, viewModel.confirmConnection { true }?.deviceClass)
        assertNull(viewModel.confirmConnection { true })
    }

    @Test
    fun mentraLiveIsOfferedAndConnectedOnlyForDevicesDetectedAsMentra() {
        val viewModel = DeviceBindViewModel()
        val mentra = device(DeviceClass.MENTRA_LIVE)
        viewModel.selectDevice(mentra)
        assertEquals(DeviceClass.MENTRA_LIVE, viewModel.selectedClass)
        assertEquals(listOf(DeviceClass.MENTRA_LIVE), DeviceBindViewModel.detectedOnlyChoicesFor(mentra))
        assertEquals(emptyList(), DeviceBindViewModel.detectedOnlyChoicesFor(device(DeviceClass.HEY_CYAN)))

        val connection = viewModel.confirmConnection { true }
        assertEquals(DeviceClass.MENTRA_LIVE, connection?.deviceClass)
        assertEquals("AA:BB", connection?.device?.macAddress)
        assertNull(viewModel.connectingDevice)
    }

    @Test
    fun mentraDisplayGlassesAreOfferedOnlyWhenDetected() {
        val g1 = device(DeviceClass.EVEN_G1)
        assertEquals(listOf(DeviceClass.EVEN_G1), DeviceBindViewModel.detectedOnlyChoicesFor(g1))
        // A saved AR99 choice alone never offers AR99: only its advertised project ID does.
        val previouslyAr99 = ScannedDevice("AA:BB", "Glasses", -50, DeviceClass.UNKNOWN, DeviceClass.XINGYI_AR99, true)
        assertEquals(emptyList(), DeviceBindViewModel.detectedOnlyChoicesFor(previouslyAr99))
        val picker = DeviceBindViewModel()
        picker.selectDevice(previouslyAr99)
        assertEquals(DeviceClass.HEY_CYAN, picker.selectedClass)
        picker.selectDeviceClass(DeviceClass.XINGYI_AR99)
        assertNull(picker.confirmConnection { true })

        val viewModel = DeviceBindViewModel()
        viewModel.selectDevice(g1)
        assertEquals(DeviceClass.EVEN_G1, viewModel.confirmConnection { true }?.deviceClass)
    }
}
