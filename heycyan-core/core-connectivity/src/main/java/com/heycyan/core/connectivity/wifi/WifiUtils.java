package com.heycyan.core.connectivity.wifi;

import android.util.Log;

public final class WifiUtils {
    private static final String TAG = "WifiUtils";

    private WifiUtils() {
    }

    public static void wifiLog(String message) {
        Log.d(TAG, message);
    }
}
