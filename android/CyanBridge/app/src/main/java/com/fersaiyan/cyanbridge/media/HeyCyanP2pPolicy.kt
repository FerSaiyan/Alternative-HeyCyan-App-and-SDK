package com.fersaiyan.cyanbridge.media

import java.util.Locale

internal object HeyCyanP2pPolicy {
    fun transferCommandTimedOut(callbackReceived: Boolean, transferEvidenceReceived: Boolean): Boolean =
        !callbackReceived && !transferEvidenceReceived

    @Suppress("UNUSED_PARAMETER")
    fun resetCallbackAllowsRetry(callbackReceived: Boolean, parsedErrorCode: Int): Boolean =
        callbackReceived

    fun officialWifiDirectName(deviceName: String?, deviceAddress: String?): String? {
        val name = deviceName?.trim().orEmpty()
        val address = deviceAddress?.replace(":", "")?.uppercase(Locale.US).orEmpty()
        if (name.isBlank() || address.isBlank()) return null

        val baseName = if ('_' in name) {
            val parts = name.split('_')
            (if (parts.size > 2) parts.last() else parts.first()).take(20)
        } else {
            name
        }
        return "${baseName}_$address"
    }

    fun matchesOfficialPeer(peerName: String?, deviceName: String?, deviceAddress: String?): Boolean {
        val candidate = peerName?.trim().orEmpty()
        val expected = officialWifiDirectName(deviceName, deviceAddress)
        val address = deviceAddress?.replace(":", "").orEmpty()
        return candidate.isNotBlank() &&
            ((!expected.isNullOrBlank() && candidate.equals(expected, ignoreCase = true)) ||
                (address.isNotBlank() && candidate.endsWith(address, ignoreCase = true)))
    }

    fun isVerifiedP2pNetwork(
        interfaceName: String?,
        addresses: Collection<String>,
        subnetPrefixes: Collection<String>,
    ): Boolean {
        val name = interfaceName.orEmpty()
        return name.contains("p2p", ignoreCase = true) ||
            name.contains("wfd", ignoreCase = true) ||
            addresses.any { it.startsWith("192.168.49.") } ||
            subnetPrefixes.any { prefix -> addresses.any { it.startsWith(prefix) } }
    }
}
