package com.fersaiyan.cyanbridge.shared.network

/**
 * HeyCyan BLE device-notify frames that describe the glasses Wi-Fi link. The notify type lives
 * at `loadData[6]`; its payload starts at `loadData[7]`.
 */
sealed interface GlassesWifiNotify {
    /** Unsigned notify type byte (`loadData[6]`). */
    val type: Int

    /** 0x08: the glasses Wi-Fi Direct IPv4 address in bytes 7..10. */
    data class GlassesIp(val ip: String, val octets: IntArray) : GlassesWifiNotify {
        override val type: Int get() = TYPE_GLASSES_IP

        override fun equals(other: Any?): Boolean =
            other is GlassesIp && other.ip == ip && other.octets.contentEquals(octets)

        override fun hashCode(): Int = ip.hashCode()
    }

    /** 0x08 frame without the four address bytes. */
    data class GlassesIpTooShort(val size: Int) : GlassesWifiNotify {
        override val type: Int get() = TYPE_GLASSES_IP
    }

    /** 0x09: Wi-Fi Direct / Wi-Fi error. [raw] is the signed payload byte when present. */
    data class WifiError(val code: Int, val raw: Byte?) : GlassesWifiNotify {
        override val type: Int get() = TYPE_WIFI_ERROR
        val isNoisyVendorError: Boolean get() = code == NOISY_WIFI_ERROR_CODE
    }

    /** 0x04: OTA progress percent and phase (both 0 when absent). */
    data class OtaProgress(val percent: Int, val phase: Int, val size: Int) : GlassesWifiNotify {
        override val type: Int get() = TYPE_OTA_PROGRESS
    }

    /** 0x07: OTA result; the official app accepts only `result == 1`. */
    data class OtaResult(val result: Int?) : GlassesWifiNotify {
        override val type: Int get() = TYPE_OTA_RESULT
        val succeeded: Boolean get() = result == 1
    }

    data class Other(override val type: Int, val size: Int) : GlassesWifiNotify

    companion object {
        const val TYPE_OTA_PROGRESS = 0x04
        const val TYPE_OTA_RESULT = 0x07
        const val TYPE_GLASSES_IP = 0x08
        const val TYPE_WIFI_ERROR = 0x09

        /** Error 255 is a frequent, usually harmless vendor notification. */
        const val NOISY_WIFI_ERROR_CODE = 255

        private const val TYPE_INDEX = 6
        private const val MIN_FRAME_SIZE = 7
        private const val IP_FRAME_SIZE = 11

        /** Null when the frame is too short to carry a notify type. */
        fun parse(load: ByteArray): GlassesWifiNotify? {
            if (load.size < MIN_FRAME_SIZE) return null
            return when (val type = load[TYPE_INDEX].toInt() and 0xFF) {
                TYPE_GLASSES_IP -> if (load.size >= IP_FRAME_SIZE) {
                    val octets = IntArray(4) { index -> load[7 + index].toInt() and 0xFF }
                    GlassesIp(octets.joinToString("."), octets)
                } else {
                    GlassesIpTooShort(load.size)
                }
                TYPE_WIFI_ERROR -> {
                    val raw = load.getOrNull(7)
                    WifiError((raw ?: 0).toInt() and 0xFF, raw)
                }
                TYPE_OTA_PROGRESS -> OtaProgress(
                    percent = (load.getOrNull(7) ?: 0).toInt() and 0xFF,
                    phase = (load.getOrNull(8) ?: 0).toInt() and 0xFF,
                    size = load.size,
                )
                TYPE_OTA_RESULT -> OtaResult(load.getOrNull(7)?.let { it.toInt() and 0xFF })
                else -> Other(type, load.size)
            }
        }
    }
}
