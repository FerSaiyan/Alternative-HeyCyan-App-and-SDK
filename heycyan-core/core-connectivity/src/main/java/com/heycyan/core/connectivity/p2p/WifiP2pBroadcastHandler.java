package com.heycyan.core.connectivity.p2p;

import android.net.wifi.p2p.WifiP2pDevice;

public interface WifiP2pBroadcastHandler {
    void onWifiP2pEnabled();

    void onWifiP2pDisabled();

    void requestPeers();

    void requestConnectionInfo();

    void onDisconnected();

    void onThisDeviceChanged(WifiP2pDevice device);
}
