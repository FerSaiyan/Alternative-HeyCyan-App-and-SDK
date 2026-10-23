package com.fersaiyan.cyanbridge.lensmoo

import org.junit.Assert.*
import org.junit.Test

class LensMooDashboardStateTest {
    @Test fun runningAssistantIsNotProofOfConnectionAndDoesNotReuseLocalBattery() {
        val result=LensMooDashboardState.build(
            local=LensMooLocalUiState(connected=true,batteryPercent=80),
            service=LensMooServiceUiState(running=true,ready=false,message="Reconnecting"),
            ownerConfigured=true,
        )
        assertFalse(result.lensMoo!!.isConnected)
        assertNull(result.batteryPercent)
        assertEquals("Reconnecting",result.lensMoo!!.status)
    }
    @Test fun serviceBatteryAndBusyStateAreUsedWhenAssistantOwnsConnection() {
        val result=LensMooDashboardState.build(
            LensMooLocalUiState(),LensMooServiceUiState(running=true,ready=true,busy=true,batteryPercent=75),true,
        )
        assertTrue(result.lensMoo!!.assistantRunning)
        assertTrue(result.lensMoo!!.assistantReady)
        assertTrue(result.lensMoo!!.isBusy)
        assertEquals(75,result.batteryPercent)
    }
    @Test fun disconnectedManualSessionNeverShowsOldBattery() {
        val result=LensMooDashboardState.build(LensMooLocalUiState(batteryPercent=80),LensMooServiceUiState(),false)
        assertFalse(result.showBattery)
        assertNull(result.batteryPercent)
        assertFalse(result.lensMoo!!.hasOwnerId)
        assertFalse(result.showMediaSync)
    }
}
