package com.fersaiyan.cyanbridge.network

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager

/**
 * The manifest declares android.hardware.wifi as optional (Google Play), so CyanBridge also installs
 * on devices without Wi-Fi. Media sync and live preview need it; they report [NO_WIFI_MESSAGE]
 * instead of crashing on a missing system service.
 */
object DeviceWifi {
    const val NO_WIFI_MESSAGE = "This device has no Wi-Fi, which glasses media sync and live preview need."

    fun wifiManager(context: Context): WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun p2pManager(context: Context): WifiP2pManager? =
        context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
}
