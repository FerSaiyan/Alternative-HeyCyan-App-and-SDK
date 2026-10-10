package com.fersaiyan.cyanbridge.ota

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeyCyanLiveP2pConnectionStateTest {
    @Test
    fun `startup disconnect and invited peer cannot trigger a duplicate connect`() {
        val state = HeyCyanLiveP2pConnectionState()
        assertTrue(state.beginConnect())
        assertFalse(state.onDisconnected())
        state.onConnectionInfo(false)
        assertFalse(state.beginConnect())
        state.onConnectionInfo(true)
        assertFalse(state.beginConnect())
    }

    @Test
    fun `disconnect of an established group is a real loss`() {
        val state = HeyCyanLiveP2pConnectionState()
        assertTrue(state.beginConnect())
        state.onConnectionInfo(true)
        assertTrue(state.onDisconnected())
        assertFalse(state.onDisconnected())
    }

    @Test
    fun `a new session can connect after a startup failure`() {
        val state = HeyCyanLiveP2pConnectionState()
        assertTrue(state.beginConnect())
        assertFalse(state.onDisconnected())
        state.reset()
        assertTrue(state.beginConnect())
    }
}
