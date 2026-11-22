package com.fersaiyan.cyanbridge.network

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.core.location.LocationManagerCompat

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

    /**
     * The phone's Wi-Fi side for media-sync support reports: Wi-Fi on, connected to a router and on which band,
     * hotspot, location services, Wi-Fi Direct support. Each value is "unknown" when Android does not tell us.
     * Added after log_mwqq8cyw, where the glasses had Wi-Fi Direct up and the phone never saw it.
     */
    @Suppress("DEPRECATION")
    fun phoneWifiState(context: Context): Map<String, String> {
        val app = context.applicationContext
        val wifi = wifiManager(app)
        fun guess(block: () -> Any?): String = runCatching { block()?.toString() ?: "unknown" }.getOrDefault("unknown")
        val wifiNetworkFrequency = runCatching {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.allNetworks.firstNotNullOfOrNull { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@firstNotNullOfOrNull null
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) caps.transportInfo as? WifiInfo else wifi?.connectionInfo
                info?.frequency ?: -1
            }
        }.getOrNull()
        return linkedMapOf(
            "phone_wifi_enabled" to guess { wifi?.isWifiEnabled },
            "phone_wifi_connected" to (if (wifiNetworkFrequency == null) "false" else "true"),
            "phone_wifi_band" to when {
                wifiNetworkFrequency == null || wifiNetworkFrequency <= 0 -> ""
                wifiNetworkFrequency < 3000 -> "2.4 GHz"
                wifiNetworkFrequency < 5925 -> "5 GHz"
                else -> "6 GHz"
            },
            "phone_hotspot_on" to guess { wifi?.javaClass?.getMethod("isWifiApEnabled")?.invoke(wifi) },
            "phone_location_services_on" to guess {
                (app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
                    ?.let { LocationManagerCompat.isLocationEnabled(it) }
            },
            "phone_wifi_direct_supported" to guess {
                app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
            },
        )
    }
}
