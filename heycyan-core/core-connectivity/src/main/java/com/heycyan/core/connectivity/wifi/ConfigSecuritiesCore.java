package com.heycyan.core.connectivity.wifi;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ConfigSecuritiesCore {
    public static final String SECURITY_EAP = "EAP";
    public static final String SECURITY_NONE = "OPEN";
    public static final String SECURITY_PSK = "PSK";
    public static final String SECURITY_WEP = "WEP";

    private ConfigSecuritiesCore() {
    }

    public static void setupSecurity(WifiConfiguration config, String security, String password) {
        config.allowedAuthAlgorithms.clear();
        config.allowedGroupCiphers.clear();
        config.allowedKeyManagement.clear();
        config.allowedPairwiseCiphers.clear();
        config.allowedProtocols.clear();
        WifiUtils.wifiLog("Setting up security " + security);
        switch (security) {
            case SECURITY_EAP:
                config.allowedProtocols.set(1);
                config.allowedProtocols.set(0);
                config.allowedGroupCiphers.set(0);
                config.allowedGroupCiphers.set(1);
                config.allowedGroupCiphers.set(2);
                config.allowedGroupCiphers.set(3);
                config.allowedPairwiseCiphers.set(1);
                config.allowedPairwiseCiphers.set(2);
                config.allowedKeyManagement.set(2);
                config.allowedKeyManagement.set(3);
                config.preSharedKey = SSIDUtils.convertToQuotedString(password);
                break;
            case SECURITY_PSK:
                config.allowedProtocols.set(1);
                config.allowedProtocols.set(0);
                config.allowedKeyManagement.set(1);
                config.allowedPairwiseCiphers.set(2);
                config.allowedPairwiseCiphers.set(1);
                config.allowedGroupCiphers.set(0);
                config.allowedGroupCiphers.set(1);
                config.allowedGroupCiphers.set(3);
                config.allowedGroupCiphers.set(2);
                if (password.matches("[0-9A-Fa-f]{64}")) {
                    config.preSharedKey = password;
                } else {
                    config.preSharedKey = SSIDUtils.convertToQuotedString(password);
                }
                break;
            case SECURITY_WEP:
                config.allowedKeyManagement.set(0);
                config.allowedProtocols.set(1);
                config.allowedProtocols.set(0);
                config.allowedAuthAlgorithms.set(0);
                config.allowedAuthAlgorithms.set(1);
                config.allowedPairwiseCiphers.set(2);
                config.allowedPairwiseCiphers.set(1);
                config.allowedGroupCiphers.set(0);
                config.allowedGroupCiphers.set(1);
                if (ConnectorUtilsCore.isHexWepKey(password)) {
                    config.wepKeys[0] = password;
                } else {
                    config.wepKeys[0] = SSIDUtils.convertToQuotedString(password);
                }
                break;
            case SECURITY_NONE:
                config.allowedKeyManagement.set(0);
                config.allowedProtocols.set(1);
                config.allowedProtocols.set(0);
                config.allowedPairwiseCiphers.set(2);
                config.allowedPairwiseCiphers.set(1);
                config.allowedGroupCiphers.set(0);
                config.allowedGroupCiphers.set(1);
                config.allowedGroupCiphers.set(3);
                config.allowedGroupCiphers.set(2);
                break;
            default:
                WifiUtils.wifiLog("Invalid security type: " + security);
                break;
        }
    }

    public static void setupSecurityHidden(WifiConfiguration config, String security, String password) {
        config.hiddenSSID = true;
        setupSecurity(config, security, password);
    }

    public static void setupWifiNetworkSpecifierSecurities(WifiNetworkSpecifier.Builder builder, String security, String password) {
        WifiUtils.wifiLog("Setting up WifiNetworkSpecifier.Builder " + security);
        switch (security) {
            case SECURITY_EAP:
            case SECURITY_PSK:
                builder.setWpa2Passphrase(password);
                break;
            case SECURITY_WEP:
            case SECURITY_NONE:
                break;
            default:
                WifiUtils.wifiLog("Invalid security type: " + security);
                break;
        }
    }

    public static WifiConfiguration getWifiConfiguration(WifiManager wifiMgr, WifiConfiguration configToFind) {
        String ssid = configToFind.SSID;
        if (ssid != null && !ssid.isEmpty()) {
            String bssid = configToFind.BSSID != null ? configToFind.BSSID : "";
            String security = getSecurity(configToFind);
            List<WifiConfiguration> configuredNetworks = wifiMgr.getConfiguredNetworks();
            if (configuredNetworks == null) {
                WifiUtils.wifiLog("NULL configs");
                return null;
            }
            for (WifiConfiguration config : configuredNetworks) {
                if ((bssid.equals(config.BSSID) || ssid.equals(config.SSID)) && Objects.equals(security, getSecurity(config))) {
                    return config;
                }
            }
            WifiUtils.wifiLog("Couldn't find " + ssid);
        }
        return null;
    }

    public static WifiConfiguration getWifiConfiguration(WifiManager wifiManager, String ssid) {
        String quoted = SSIDUtils.convertToQuotedString(ssid);
        for (WifiConfiguration config : wifiManager.getConfiguredNetworks()) {
            if (config.SSID != null && config.SSID.equals(quoted)) {
                return config;
            }
        }
        return null;
    }

    public static WifiConfiguration getWifiConfiguration(WifiManager wifiManager, ScanResult scanResult) {
        if (scanResult.BSSID != null && scanResult.SSID != null && !scanResult.SSID.isEmpty() && !scanResult.BSSID.isEmpty()) {
            String quotedSsid = SSIDUtils.convertToQuotedString(scanResult.SSID);
            String bssid = scanResult.BSSID;
            String security = getSecurity(scanResult);
            List<WifiConfiguration> configuredNetworks = wifiManager.getConfiguredNetworks();
            if (configuredNetworks == null) {
                return null;
            }
            for (WifiConfiguration config : configuredNetworks) {
                if ((bssid.equals(config.BSSID) || quotedSsid.equals(config.SSID)) && Objects.equals(security, getSecurity(config))) {
                    return config;
                }
            }
        }
        return null;
    }

    public static String getSecurity(WifiConfiguration config) {
        ArrayList<String> securityTypes = new ArrayList<>();
        String security = SECURITY_NONE;
        if (config.allowedKeyManagement.get(0)) {
            if (config.wepKeys[0] != null) {
                security = SECURITY_WEP;
            }
            securityTypes.add(security);
        }
        if (config.allowedKeyManagement.get(2) || config.allowedKeyManagement.get(3)) {
            security = SECURITY_EAP;
            securityTypes.add(SECURITY_EAP);
        }
        if (config.allowedKeyManagement.get(1)) {
            security = SECURITY_PSK;
            securityTypes.add(SECURITY_PSK);
        }
        WifiUtils.wifiLog("Got Security Via WifiConfiguration " + securityTypes);
        return security;
    }

    public static String getSecurity(ScanResult result) {
        String security = result.capabilities.contains(SECURITY_WEP) ? SECURITY_WEP : SECURITY_NONE;
        if (result.capabilities.contains(SECURITY_PSK)) {
            security = SECURITY_PSK;
        }
        if (result.capabilities.contains(SECURITY_EAP)) {
            security = SECURITY_EAP;
        }
        WifiUtils.wifiLog("ScanResult capabilities " + result.capabilities);
        WifiUtils.wifiLog("Got security via ScanResult " + security);
        return security;
    }

    public static String getSecurity(String result) {
        String security = result.contains(SECURITY_WEP) ? SECURITY_WEP : SECURITY_NONE;
        if (result.contains(SECURITY_PSK)) {
            security = SECURITY_PSK;
        }
        return result.contains(SECURITY_EAP) ? SECURITY_EAP : security;
    }

    public static String getSecurityPrettyPlusWps(ScanResult scanResult) {
        if (scanResult == null) {
            return "";
        }
        String security = getSecurity(scanResult);
        return scanResult.capabilities.contains("WPS") ? security + ", WPS" : security;
    }
}
