package com.heycyan.core.connectivity.p2p;

public final class WifiP2pConnectionState {
    private boolean connected;
    private boolean connecting;

    public boolean isConnected() {
        return connected;
    }

    public boolean isConnecting() {
        return connecting;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public void setConnecting(boolean connecting) {
        this.connecting = connecting;
    }

    public void markConnectRequestFailed() {
        connecting = false;
    }

    public void markConnectionInfoAvailable(boolean groupFormed) {
        connecting = false;
        connected = groupFormed;
    }

    public void markDisconnected() {
        connecting = false;
        connected = false;
    }

    public void reset() {
        connecting = false;
        connected = false;
    }
}
