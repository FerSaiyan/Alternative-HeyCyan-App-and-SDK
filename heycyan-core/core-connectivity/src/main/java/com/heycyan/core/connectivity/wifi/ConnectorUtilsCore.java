package com.heycyan.core.connectivity.wifi;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

public final class ConnectorUtilsCore {
    private static final int MAX_PRIORITY = 99999;

    private ConnectorUtilsCore() {
    }

    public static boolean isHexWepKey(String wepKey) {
        int length = wepKey.length();
        if (length != 10 && length != 26 && length != 58) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            char c = wepKey.charAt(i);
            if ((c < '0' || c > '9') && ((c < 'a' || c > 'f') && (c < 'A' || c > 'F'))) {
                return false;
            }
        }
        return true;
    }

    public static void reEnableNetworkIfPossible(WifiManager wifiMgr, ScanResult scanResult) {
        String bssid;
        if (wifiMgr == null || scanResult == null || (bssid = scanResult.BSSID) == null) {
            return;
        }
        enableNetwork(wifiMgr, bssid, false);
    }

    private static void enableNetwork(WifiManager wifiMgr, String bssid, boolean disableOthers) {
        for (WifiConfiguration config : wifiMgr.getConfiguredNetworks()) {
            String currentBssid = config.BSSID;
            if (currentBssid != null && currentBssid.equals(bssid)) {
                wifiMgr.enableNetwork(config.networkId, disableOthers);
            } else if (disableOthers) {
                wifiMgr.disableNetwork(config.networkId);
            }
        }
    }

    public static void reEnableNetworkIfPossible(WifiManager wifiMgr, String bssid) {
        if (wifiMgr == null || bssid == null) {
            return;
        }
        enableNetwork(wifiMgr, bssid, false);
    }

    public static int getMaxPriority(WifiManager wifiManager) {
        List<WifiConfiguration> configuredNetworks = wifiManager.getConfiguredNetworks();
        int max = 0;
        if (configuredNetworks == null) {
            return 0;
        }
        for (WifiConfiguration config : configuredNetworks) {
            if (config.priority > max) {
                max = config.priority;
            }
        }
        return max;
    }

    public static WifiConfiguration createWifiConfiguration(String security, String ssid, String password) {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = SSIDUtils.convertToQuotedString(ssid);
        ConfigSecuritiesCore.setupSecurity(config, security, password);
        return config;
    }

    public static int saveNetwork(WifiManager wifiManager, WifiConfiguration config) {
        if (wifiManager == null || config == null) {
            return -1;
        }
        WifiConfiguration existing = ConfigSecuritiesCore.getWifiConfiguration(wifiManager, config);
        if (existing != null) {
            if (Build.VERSION.SDK_INT >= 23) {
                return existing.networkId;
            }
            config.networkId = existing.networkId;
            wifiManager.updateNetwork(config);
            return existing.networkId;
        }
        return wifiManager.addNetwork(config);
    }

    public static boolean isAlreadyConnected(WifiManager wifiManager, String bssid) {
        if (wifiManager == null || wifiManager.getConnectionInfo() == null || wifiManager.getConnectionInfo().getBSSID() == null || wifiManager.getConnectionInfo().getIpAddress() == 0 || bssid == null || !Objects.equals(bssid, wifiManager.getConnectionInfo().getBSSID())) {
            return false;
        }
        WifiUtils.wifiLog("Already connected to: " + wifiManager.getConnectionInfo().getSSID() + "  BSSID: " + wifiManager.getConnectionInfo().getBSSID());
        return true;
    }

    public static boolean isAlreadyConnected(WifiManager wifiManager, ScanResult scanResult) {
        if (scanResult == null) {
            return false;
        }
        return isAlreadyConnected(wifiManager, scanResult.BSSID);
    }

    public static void disconnectFromAll(WifiManager wifiManager) {
        if (wifiManager == null) {
            return;
        }
        List<WifiConfiguration> configuredNetworks = wifiManager.getConfiguredNetworks();
        if (configuredNetworks != null) {
            for (WifiConfiguration config : configuredNetworks) {
                wifiManager.disableNetwork(config.networkId);
            }
        }
    }

    public static WifiConfiguration disableOthers(WifiManager wifiManager, WifiConfiguration config) {
        List<WifiConfiguration> configuredNetworks = wifiManager.getConfiguredNetworks();
        if (configuredNetworks != null && config.SSID != null) {
            Iterator<WifiConfiguration> it = configuredNetworks.iterator();
            while (it.hasNext()) {
                WifiConfiguration next = it.next();
                if (!config.SSID.equals(next.SSID)) {
                    wifiManager.disableNetwork(next.networkId);
                }
            }
        }
        return config;
    }
}
