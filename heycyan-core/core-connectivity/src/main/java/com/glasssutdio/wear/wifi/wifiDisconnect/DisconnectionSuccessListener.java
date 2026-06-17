package com.glasssutdio.wear.wifi.wifiDisconnect;

public interface DisconnectionSuccessListener {
    void failed(DisconnectionErrorCode errorCode);

    void success();
}
