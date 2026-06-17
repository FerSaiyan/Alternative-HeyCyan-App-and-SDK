package com.glasssutdio.wear.wifi.wifiRemove;

public interface RemoveSuccessListener {
    void failed(RemoveErrorCode errorCode);

    void success();
}
