package com.fersaiyan.cyanbridge.devices.meizumyvu

import com.myvu.client.service.ConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeizuMyvuConnectionPolicyTest {
    @Test
    fun `BLE ready does not enable features before the app relay is ready`() {
        assertFalse(MeizuMyvuConnectionPolicy.isFeatureReady(ConnectionState.READY, relayReady = false))
        assertTrue(MeizuMyvuConnectionPolicy.isFeatureReady(ConnectionState.READY, relayReady = true))
        assertFalse(MeizuMyvuConnectionPolicy.isFeatureReady(ConnectionState.SESSION, relayReady = true))
    }

    @Test
    fun `outer reconnect does not race the upstream reconnect backoff`() {
        val failedAt = 10_000L

        assertFalse(
            MeizuMyvuConnectionPolicy.shouldRequestConnection(
                ConnectionState.FAILED,
                failedAt,
                failedAt + MeizuMyvuConnectionPolicy.EXTERNAL_RETRY_GRACE_MS - 1,
            ),
        )
        assertTrue(
            MeizuMyvuConnectionPolicy.shouldRequestConnection(
                ConnectionState.FAILED,
                failedAt,
                failedAt + MeizuMyvuConnectionPolicy.EXTERNAL_RETRY_GRACE_MS,
            ),
        )
    }

    @Test
    fun `idle starts immediately while active states do not restart`() {
        assertTrue(MeizuMyvuConnectionPolicy.shouldRequestConnection(ConnectionState.IDLE, 0, 0))
        assertFalse(MeizuMyvuConnectionPolicy.shouldRequestConnection(ConnectionState.CONNECTING, 0, Long.MAX_VALUE))
        assertFalse(MeizuMyvuConnectionPolicy.shouldRequestConnection(ConnectionState.PAIRING, 0, Long.MAX_VALUE))
        assertFalse(MeizuMyvuConnectionPolicy.shouldRequestConnection(ConnectionState.SESSION, 0, Long.MAX_VALUE))
        assertFalse(MeizuMyvuConnectionPolicy.shouldRequestConnection(ConnectionState.READY, 0, Long.MAX_VALUE))
    }

    @Test
    fun `HeyCyan glasses set to MYVU are recognised and not retried automatically`() {
        // log_mwqmez5k (M02S_CA12), shortened: no StarryNet channel, HeyCyan's command characteristics present.
        val heyCyan = "BLE no known StarryNet channel pair on this device; characteristics=[0000ae01-0000-1000-8000-" +
            "00805f9b34fb, 6e400002-b5a3-f393-e0a9-e50e24dcca9e, de5bf72a-d711-4e47-af26-65e3012a5dc7, " +
            "de5bf729-d711-4e47-af26-65e3012a5dc7]"
        assertTrue(MeizuMyvuConnectionPolicy.looksLikeHeyCyan(heyCyan))
        assertFalse(MeizuMyvuConnectionPolicy.looksLikeHeyCyan("BLE no known StarryNet channel pair on this device; characteristics=[00002a00-0000-1000-8000-00805f9b34fb]"))
        assertFalse(MeizuMyvuConnectionPolicy.looksLikeHeyCyan("BLE init burst did not complete"))
        assertFalse(MeizuMyvuConnectionPolicy.looksLikeHeyCyan(null))

        assertFalse(MeizuMyvuConnectionPolicy.shouldRequestConnection(ConnectionState.FAILED, 0, Long.MAX_VALUE, wrongGlasses = true))
        assertFalse(MeizuMyvuConnectionPolicy.shouldRequestConnection(ConnectionState.IDLE, 0, Long.MAX_VALUE, wrongGlasses = true))
        assertTrue(MeizuMyvuConnectionPolicy.shouldRequestConnection(ConnectionState.IDLE, 0, Long.MAX_VALUE, wrongGlasses = false))
    }
}
