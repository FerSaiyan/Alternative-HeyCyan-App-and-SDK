package com.heycyan.core.connectivity.wifi;

import android.text.TextUtils;

public final class SSIDUtils {
    private SSIDUtils() {
    }

    public static String convertToQuotedString(String ssid) {
        if (TextUtils.isEmpty(ssid)) {
            return "";
        }
        int length = ssid.length() - 1;
        return length >= 0 ? (ssid.charAt(0) == '"' && ssid.charAt(length) == '"') ? ssid : "\"" + ssid + "\"" : ssid;
    }
}
