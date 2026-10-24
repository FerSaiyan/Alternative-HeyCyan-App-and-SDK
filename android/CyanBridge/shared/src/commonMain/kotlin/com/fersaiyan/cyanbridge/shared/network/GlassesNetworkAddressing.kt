package com.fersaiyan.cyanbridge.shared.network

/**
 * IPv4, subnet and interface rules for the phone <-> glasses local link (Wi-Fi Direct group,
 * glasses access point or phone hotspot). Platform adapters supply the raw interface names and
 * addresses; every decision about which route belongs to the glasses lives here.
 */
object GlassesNetworkAddressing {
    /** Android's Wi-Fi Direct group subnet; the group owner is usually `192.168.49.1`. */
    const val WIFI_DIRECT_SUBNET_PREFIX = "192.168.49."
    const val WIFI_DIRECT_GROUP_OWNER_IP = "192.168.49.1"

    /** Returns the four octets of a dotted IPv4 literal, or null when it is not one. */
    fun parseIpv4(ip: String?): IntArray? {
        val parts = ip?.trim()?.split('.') ?: return null
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for ((index, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
            val value = part.toInt()
            if (value !in 0..255) return null
            octets[index] = value
        }
        return octets
    }

    fun isIpv4(ip: String?): Boolean = parseIpv4(ip) != null

    /** "a.b.c." for a four-part dotted address; the octets are not validated. */
    fun prefix24(ip: String?): String? {
        if (ip.isNullOrBlank()) return null
        val parts = ip.split(".")
        if (parts.size != 4) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}."
    }

    /** Distinct /24 prefixes of every known glasses address, in priority order. */
    fun subnetHints(vararg ips: String?): List<String> = ips.mapNotNull(::prefix24).distinct()

    fun isP2pInterfaceName(name: String?): Boolean {
        val value = name.orEmpty()
        return value.contains("p2p", ignoreCase = true) || value.contains("wfd", ignoreCase = true)
    }

    /**
     * A non-VPN Wi-Fi network belongs to the glasses link when its interface is a Wi-Fi Direct
     * interface, it carries a Wi-Fi Direct group address, or it shares a subnet with an address
     * the glasses reported. Ordinary Wi-Fi without any of those signals is rejected.
     */
    fun isVerifiedP2pNetwork(
        interfaceName: String?,
        addresses: Collection<String>,
        subnetPrefixes: Collection<String>,
    ): Boolean {
        return isP2pInterfaceName(interfaceName) ||
            addresses.any { it.startsWith(WIFI_DIRECT_SUBNET_PREFIX) } ||
            subnetPrefixes.any { prefix -> addresses.any { it.startsWith(prefix) } }
    }

    /**
     * Chooses the phone's own IPv4 address on the glasses subnet (used as the OTA HTTP host).
     * [candidates] are `(interfaceName, address)` pairs; Wi-Fi Direct interfaces win ties.
     */
    fun selectLocalP2pIpv4(
        glassesIp: String,
        candidates: List<Pair<String, String>>,
    ): String? {
        val octets = glassesIp.split('.')
        if (octets.size != 4 || octets.any { it.toIntOrNull() !in 0..255 }) return null
        val subnetPrefix = octets.take(3).joinToString(".") + "."
        val onGlassesSubnet = candidates.filter { (_, ip) ->
            ip != glassesIp && ip.startsWith(subnetPrefix)
        }
        return onGlassesSubnet.firstOrNull { (name, _) -> isP2pInterfaceName(name) }?.second
            ?: onGlassesSubnet.firstOrNull()?.second
    }

    /**
     * When the phone is the Wi-Fi Direct group owner, `192.168.49.1` is the phone itself and
     * must never be used as the glasses HTTP target.
     */
    fun isPhoneGroupOwnerAddress(ip: String?, phoneIsGroupOwner: Boolean?): Boolean =
        phoneIsGroupOwner == true && ip == WIFI_DIRECT_GROUP_OWNER_IP

    /** Device-reported glasses addresses usable as an HTTP target, in priority order. */
    fun glassesHttpTargets(
        reportedIps: List<String?>,
        phoneIsGroupOwner: Boolean?,
    ): List<String> = reportedIps
        .filterNotNull()
        .filter { it.isNotBlank() && !isPhoneGroupOwnerAddress(it, phoneIsGroupOwner) }
        .distinct()
}
