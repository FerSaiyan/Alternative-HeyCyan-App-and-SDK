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
}
