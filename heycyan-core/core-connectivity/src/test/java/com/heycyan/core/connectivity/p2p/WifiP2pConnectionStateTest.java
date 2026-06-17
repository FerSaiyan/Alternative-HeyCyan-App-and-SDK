package com.heycyan.core.connectivity.p2p;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WifiP2pConnectionStateTest {

    @Test
    public void defaults_areDisconnectedAndIdle() {
        WifiP2pConnectionState state = new WifiP2pConnectionState();

        assertFalse(state.isConnected());
        assertFalse(state.isConnecting());
    }

    @Test
    public void markConnectionInfoAvailable_updatesFlags() {
        WifiP2pConnectionState state = new WifiP2pConnectionState();
        state.setConnecting(true);

        state.markConnectionInfoAvailable(true);

        assertTrue(state.isConnected());
        assertFalse(state.isConnecting());
    }

    @Test
    public void markConnectRequestFailed_clearsConnectingOnly() {
        WifiP2pConnectionState state = new WifiP2pConnectionState();
        state.setConnected(true);
        state.setConnecting(true);

        state.markConnectRequestFailed();

        assertTrue(state.isConnected());
        assertFalse(state.isConnecting());
    }

    @Test
    public void markDisconnected_andReset_clearBothFlags() {
        WifiP2pConnectionState state = new WifiP2pConnectionState();
        state.setConnected(true);
        state.setConnecting(true);

        state.markDisconnected();
        assertFalse(state.isConnected());
        assertFalse(state.isConnecting());

        state.setConnected(true);
        state.setConnecting(true);
        state.reset();
        assertFalse(state.isConnected());
        assertFalse(state.isConnecting());
    }
}
