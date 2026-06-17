package com.heycyan.core.connectivity.p2p;

import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import java.util.Collection;

public interface WifiP2pCallback {
    void onWifiP2pEnabled();

    void onWifiP2pDisabled();

    void onPeersChanged(Collection<WifiP2pDevice> peers);

    void onThisDeviceChanged(WifiP2pDevice device);

    void onConnected(WifiP2pInfo info);

    void onDisconnected();

    void onPeerDiscoveryStarted();

    void onPeerDiscoveryFailed(int reason);

    void onConnectRequestSent();

    void onConnectRequestFailed(int reason);

    void connecting();

    void cancelConnect();

    void cancelConnectFail(int reason);

    void retryAlsoFailed();
}
