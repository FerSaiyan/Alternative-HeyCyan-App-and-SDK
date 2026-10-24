package com.fersaiyan.cyanbridge.ota

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiOtaModeResponseTest {
    @Test
    fun `official ota-ready response is accepted`() {
        assertTrue(isWifiOtaModeReady(dataType = 1, glassWorkType = 5, otaStatus = 1))
    }

    @Test
    fun `busy or unrelated responses are rejected`() {
        assertFalse(isWifiOtaModeReady(dataType = 1, glassWorkType = 5, otaStatus = 0))
        assertFalse(isWifiOtaModeReady(dataType = 1, glassWorkType = 2, otaStatus = 1))
        assertFalse(isWifiOtaModeReady(dataType = 2, glassWorkType = 5, otaStatus = 1))
    }

    @Test
    fun `wifi ota completion requires the official success byte`() {
        assertTrue(isWifiOtaCompleteNotification(notifyType = 0x07, result = 1))
        assertFalse(isWifiOtaCompleteNotification(notifyType = 0x07, result = 0))
        assertFalse(isWifiOtaCompleteNotification(notifyType = 0x07, result = null))
        assertFalse(isWifiOtaCompleteNotification(notifyType = 0x04, result = 1))
    }
}
