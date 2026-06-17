package com.heycyan.core.connectivity.p2p;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WifiP2pRetryStateTest {

    @Test
    public void retryLimits_respectedAndCounted() {
        WifiP2pRetryState state = new WifiP2pRetryState(1, 1);

        assertTrue(state.shouldRetryConnect());
        assertEquals(1, state.connectRetryCount());
        assertFalse(state.shouldRetryConnect());
        assertEquals(1, state.connectRetryCount());

        assertTrue(state.shouldRetryDiscovery());
        assertEquals(1, state.discoveryRetryCount());
        assertFalse(state.shouldRetryDiscovery());
        assertEquals(1, state.discoveryRetryCount());
    }

    @Test
    public void reset_restoresCountersAndAllowsRetryAgain() {
        WifiP2pRetryState state = new WifiP2pRetryState(1, 1);

        state.shouldRetryConnect();
        state.shouldRetryDiscovery();
        state.reset();

        assertEquals(0, state.connectRetryCount());
        assertEquals(0, state.discoveryRetryCount());
        assertTrue(state.shouldRetryConnect());
        assertTrue(state.shouldRetryDiscovery());
    }

    @Test
    public void negativeLimits_areClampedToZero() {
        WifiP2pRetryState state = new WifiP2pRetryState(-1, -3);

        assertFalse(state.shouldRetryConnect());
        assertFalse(state.shouldRetryDiscovery());
        assertEquals(0, state.connectRetryCount());
        assertEquals(0, state.discoveryRetryCount());
    }
}
