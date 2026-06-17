package com.heycyan.core.connectivity.p2p;

public final class WifiP2pRetryState {
    private final int maxConnectRetries;
    private final int maxDiscoveryRetries;
    private int connectRetryCount;
    private int discoveryRetryCount;

    public WifiP2pRetryState(int maxConnectRetries, int maxDiscoveryRetries) {
        this.maxConnectRetries = Math.max(0, maxConnectRetries);
        this.maxDiscoveryRetries = Math.max(0, maxDiscoveryRetries);
    }

    public int connectRetryCount() {
        return connectRetryCount;
    }

    public int discoveryRetryCount() {
        return discoveryRetryCount;
    }

    public boolean shouldRetryConnect() {
        if (connectRetryCount < maxConnectRetries) {
            connectRetryCount++;
            return true;
        }
        return false;
    }

    public boolean shouldRetryDiscovery() {
        if (discoveryRetryCount < maxDiscoveryRetries) {
            discoveryRetryCount++;
            return true;
        }
        return false;
    }

    public void reset() {
        connectRetryCount = 0;
        discoveryRetryCount = 0;
    }
}
