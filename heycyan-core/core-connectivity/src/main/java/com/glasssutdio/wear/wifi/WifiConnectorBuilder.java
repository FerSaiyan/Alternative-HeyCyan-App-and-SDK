package com.glasssutdio.wear.wifi;

import com.glasssutdio.wear.wifi.wifiConnect.ConnectionScanResultsListener;
import com.glasssutdio.wear.wifi.wifiConnect.ConnectionSuccessListener;
import com.glasssutdio.wear.wifi.wifiDisconnect.DisconnectionSuccessListener;
import com.glasssutdio.wear.wifi.wifiRemove.RemoveSuccessListener;
import com.glasssutdio.wear.wifi.wifiScan.ScanResultsListener;
import com.glasssutdio.wear.wifi.wifiState.WifiStateListener;
import com.glasssutdio.wear.wifi.wifiWps.ConnectionWpsListener;

public interface WifiConnectorBuilder {

    interface WifiSuccessListener {
        WifiConnectorBuilder onConnectionResult(ConnectionSuccessListener successListener);

        WifiSuccessListener setTimeout(long timeOutMillis);
    }

    interface WifiUtilsBuilder {
        void cancelAutoConnect();

        WifiSuccessListener connectWith(String ssid);

        WifiSuccessListener connectWith(String ssid, String password);

        WifiSuccessListener connectWith(String ssid, String password, TypeEnum type);

        WifiSuccessListener connectWith(String ssid, String bssid, String password);

        WifiSuccessListener connectWithScanResult(String password, ConnectionScanResultsListener connectionScanResultsListener);

        WifiWpsSuccessListener connectWithWps(String bssid, String password);

        void disableWifi();

        void disconnect(DisconnectionSuccessListener disconnectionSuccessListener);

        @Deprecated
        void disconnectFrom(String ssid, DisconnectionSuccessListener disconnectionSuccessListener);

        void enableWifi();

        void enableWifi(WifiStateListener wifiStateListener);

        boolean isWifiConnected();

        boolean isWifiConnected(String ssid);

        WifiUtilsBuilder patternMatch();

        void remove(String ssid, RemoveSuccessListener removeSuccessListener);

        WifiConnectorBuilder scanWifi(ScanResultsListener scanResultsListener);
    }

    interface WifiWpsSuccessListener {
        WifiConnectorBuilder onConnectionWpsResult(ConnectionWpsListener successListener);

        WifiWpsSuccessListener setWpsTimeout(long timeOutMillis);
    }

    void start();
}
