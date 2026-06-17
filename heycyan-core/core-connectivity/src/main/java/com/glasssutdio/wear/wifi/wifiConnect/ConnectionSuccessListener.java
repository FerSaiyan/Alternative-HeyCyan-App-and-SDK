package com.glasssutdio.wear.wifi.wifiConnect;

public interface ConnectionSuccessListener {
    void failed(ConnectionErrorCode errorCode);

    void success();
}
