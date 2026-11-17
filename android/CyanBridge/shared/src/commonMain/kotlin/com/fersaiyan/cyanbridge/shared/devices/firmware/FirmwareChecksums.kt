package com.fersaiyan.cyanbridge.shared.devices.firmware

/** The checksums the EyeVue `.ufw` and TuneBuds `.fot` containers carry. */
internal object FirmwareChecksums {
    /** CRC-16/CCITT (poly 0x1021, no reflection); init 0 = XMODEM (Python `binascii.crc_hqx(b, 0)`). */
    fun crc16Ccitt(data: ByteArray, from: Int = 0, to: Int = data.size, init: Int = 0): Int {
        var crc = init and 0xFFFF
        for (i in from until to) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    private val CRC32_TABLE = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    /**
     * Bluetrum `dr_calc_crc32`: reflected CRC-32, init 0xFFFFFFFF and no final XOR
     * (`~zlib.crc32(b)` in the reference packer).
     */
    fun crc32NoFinalXor(data: ByteArray, from: Int, to: Int): Long {
        var crc = -1
        for (i in from until to) crc = CRC32_TABLE[(crc xor data[i].toInt()) and 0xFF] xor (crc ushr 8)
        return crc.toLong() and 0xFFFFFFFFL
    }

    fun u16le(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    fun u32le(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
}

/** Why a firmware file cannot be installed; [message] is shown to the user as is. */
class FirmwareFileException(message: String) : IllegalArgumentException(message)
