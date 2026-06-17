package com.heycyan.core.connectivity.p2p;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.util.Log;

public class CoreWifiP2pBroadcastReceiver extends BroadcastReceiver {
    private final WifiP2pBroadcastHandler handler;
    private final String tag;

    public CoreWifiP2pBroadcastReceiver(WifiP2pBroadcastHandler handler, String tag) {
        this.handler = handler;
        this.tag = tag;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
            int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
            if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                Log.d(tag, "WiFi P2P is enabled");
                handler.onWifiP2pEnabled();
            } else {
                Log.d(tag, "WiFi P2P is disabled");
                handler.onWifiP2pDisabled();
            }
            return;
        }

        if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
            Log.d(tag, "Peers changed");
            handler.requestPeers();
            return;
        }

        if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
            NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
            boolean connected = networkInfo != null && networkInfo.isConnected();
            Log.d(tag, "Connection state changed: " + connected);

            if (connected) {
                Log.d(tag, "Connected to P2P device");
                handler.requestConnectionInfo();
            } else {
                Log.d(tag, "Disconnected from P2P device");
                handler.onDisconnected();
            }
            return;
        }

        if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
            WifiP2pDevice device = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
            if (device != null) {
                Log.d(tag, "This device changed: " + device.deviceName + " - " + device.status);
                handler.onThisDeviceChanged(device);
            }
        }
    }
}
