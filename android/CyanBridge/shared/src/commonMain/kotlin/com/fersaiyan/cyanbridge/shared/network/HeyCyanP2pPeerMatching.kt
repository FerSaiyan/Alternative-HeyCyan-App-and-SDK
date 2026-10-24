package com.fersaiyan.cyanbridge.shared.network

/**
 * Identifies the paired HeyCyan glasses among discovered Wi-Fi Direct peers. Peer names and
 * addresses are plain strings so every platform adapter (Android WifiP2pDevice, iOS hotspot
 * SSIDs) applies the same identity rules.
 */
object HeyCyanP2pPeerMatching {
    /** The Wi-Fi Direct name the official app expects: `<base>_<BLE MAC without colons>`. */
    fun officialWifiDirectName(deviceName: String?, deviceAddress: String?): String? {
        val name = deviceName?.trim().orEmpty()
        val address = deviceAddress?.replace(":", "")?.uppercase().orEmpty()
        if (name.isBlank() || address.isBlank()) return null

        val baseName = if ('_' in name) {
            val parts = name.split('_')
            (if (parts.size > 2) parts.last() else parts.first()).take(20)
        } else {
            name
        }
        return "${baseName}_$address"
    }

    /** Official-flow identity: exact official name, or a name ending with the full BLE MAC. */
    fun matchesOfficialPeer(peerName: String?, deviceName: String?, deviceAddress: String?): Boolean {
        val candidate = peerName?.trim().orEmpty()
        val expected = officialWifiDirectName(deviceName, deviceAddress)
        val address = deviceAddress?.replace(":", "").orEmpty()
        return candidate.isNotBlank() &&
            ((!expected.isNullOrBlank() && candidate.equals(expected, ignoreCase = true)) ||
                (address.isNotBlank() && candidate.endsWith(address, ignoreCase = true)))
    }

    /**
     * Identity rule shared by the firmware-update and live-preview links: the official identity,
     * the exact paired BLE name or address, or a peer name ending in `_` plus the last four hex
     * digits of the paired BLE MAC.
     */
    fun matchesPairedGlassesPeer(
        peerName: String?,
        peerAddress: String?,
        pairedName: String?,
        pairedAddress: String?,
    ): Boolean {
        if (matchesOfficialPeer(peerName, pairedName, pairedAddress)) return true
        if (!pairedName.isNullOrBlank() && peerName.equals(pairedName, ignoreCase = true)) return true
        if (!pairedAddress.isNullOrBlank() && peerAddress.equals(pairedAddress, ignoreCase = true)) return true
        val shortSuffix = pairedAddress?.takeLast(5)?.replace(":", "").orEmpty()
        return shortSuffix.length == 4 &&
            peerName?.endsWith("_$shortSuffix", ignoreCase = true) == true
    }

    /** Prefers an available peer, then any matching peer, preserving discovery order. */
    fun <T> selectPreferringAvailable(
        peers: Collection<T>,
        isAvailable: (T) -> Boolean,
        matches: (T) -> Boolean,
    ): T? = peers.firstOrNull { matches(it) && isAvailable(it) } ?: peers.firstOrNull(matches)

    /**
     * Heuristic score for the adaptive flow: 100 for the paired BLE MAC, 80 for HeyCyan naming,
     * 70 for known glasses model families, 30 for a bare hex identifier, -1 otherwise.
     */
    fun likelyGlassesPeerNameStrength(deviceName: String?, bleMacNoColon: String?): Int {
        val name = (deviceName ?: "").uppercase()
        if (name.isBlank()) return -1

        if (!bleMacNoColon.isNullOrBlank() && name.contains(bleMacNoColon)) {
            return 100
        }

        if (
            name.contains("HEYCYAN") ||
            name.contains("CYAN") ||
            name.startsWith("O_") ||
            name.startsWith("Q_")
        ) {
            return 80
        }

        if (GLASSES_PREFIXES.any { name.startsWith(it) || name.contains(it) }) {
            return 70
        }

        if (name.contains("AIMB-") || name.contains("GLASS")) {
            return 70
        }

        // Weak fallback only when nothing else looks like the glasses.
        if (HEX_IDENTIFIER.containsMatchIn(name)) {
            return 30
        }

        return -1
    }

    /** The adaptive flow never connects below this score. */
    const val ADAPTIVE_MIN_PEER_SCORE = 70

    // Known glasses model prefixes/brands from glasses_models.txt
    private val GLASSES_PREFIXES = arrayOf(
        "AIM", "CR-", "SG", "GL-", "ST-AG", "BW-AG", "ABG-", "RG-",
        "ZIC-GLA", "QK-SG", "TF-GL", "HY-G", "NLB", "DM-", "ES-",
        "GS", "BV", "XC", "CG", "WL-", "ID-", "AZBV", "PW-", "GV3",
        "AX01", "ER0S", "VEU", "FIRELENS", "VIBELENS", "AIWR", "ROLBATCH",
        "DPVR", "VIZO", "SMARTVIEW", "KSIX", "VISO", "MD02", "WAGA",
        "BROOKLYN", "KATLOS", "FASTRACK", "HUGUR", "NILOX", "VEYRA",
        "AHENOD", "BOMANLON", "TRUSMI", "FABRIKA", "MICROWEAR",
        "WANDERTH", "PANGBOLIN", "SEEVA", "ASTR", "LENYES", "BLISBOND",
        "MEEEGOU", "NEOSEE", "SOBAST",
    )

    private val HEX_IDENTIFIER = Regex("[A-F0-9]{12}")
}
