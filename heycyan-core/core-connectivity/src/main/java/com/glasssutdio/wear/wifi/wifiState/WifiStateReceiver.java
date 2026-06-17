package com.glasssutdio.wear.wifi.wifiState;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class WifiStateReceiver extends BroadcastReceiver {
    private final WifiStateCallback wifiStateCallback;

    public WifiStateReceiver(WifiStateCallback callbacks) {
        this.wifiStateCallback = callbacks;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getIntExtra("wifi_state", 0) != 3) {
            return;
        }
        this.wifiStateCallback.onWifiEnabled();
    }
}
