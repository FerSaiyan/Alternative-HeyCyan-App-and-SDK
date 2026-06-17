package com.glasssutdio.wear.wifi.wifiConnect;

public interface WifiConnectionCallback {
    void errorConnect(ConnectionErrorCode connectionErrorCode);

    void successfulConnect();
}
