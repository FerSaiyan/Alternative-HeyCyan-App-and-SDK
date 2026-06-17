package com.heycyan.core.connectivity.p2p;

import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import java.util.List;

public interface DirectActionListener extends WifiP2pManager.ChannelListener {
    void onConnectionInfoAvailable(WifiP2pInfo wifiP2pInfo);

    void onDisconnection();

    void onPeersAvailable(List<? extends WifiP2pDevice> devices);

    void onSelfDeviceAvailable(WifiP2pDevice device);

    void wifiP2pEnabled(boolean enabled);
}
