package com.fersaiyan.cyanbridge.shared.devices.firmware

import com.fersaiyan.cyanbridge.shared.devices.firmware.FirmwareChecksums.crc16Ccitt
import com.fersaiyan.cyanbridge.shared.devices.firmware.FirmwareChecksums.crc32NoFinalXor
import com.fersaiyan.cyanbridge.shared.devices.firmware.FirmwareChecksums.u16le
import com.fersaiyan.cyanbridge.shared.devices.firmware.FirmwareChecksums.u32le

/** What [TuneBudsFot.inspect] read from a valid TuneBuds `.fot`. */
data class FotInspection(val chip: String)

/**
 * The TuneBuds (Bluetrum) `.fot` container checks the glasses' own `ota_pack_analyze` runs and the
 * owner's packer verifies (firmware_dump `tb_repack_add176.py verify_fot`, outer part):
 * `POT\0` magic, total length, the header CRC16 at 0xFE, the chip TLV (`QUEN`) and the
 * `ota_user_bin` TLV, the payload/trailer layout and their CRC32s. The compressed image inside
 * is checked by the glasses block by block during the update.
 */
object TuneBudsFot {
    const val CHIP = "QUEN"
    private const val POT_MAGIC = 0x00544F50L
    private const val CHIP_TAG = 0xCFC6CEC9L
    private const val USER_BIN_TAG = 0xC1D4C1C4L

    fun inspect(raw: ByteArray): FotInspection {
        if (raw.size < 0x200 || u32le(raw, 0) != POT_MAGIC) fail("This is not a TuneBuds firmware file (.fot).")
        if (u32le(raw, 4) + 8 != raw.size.toLong()) fail("The firmware file is incomplete (length mismatch).")
        if (crc16Ccitt(raw, 0, 0xFE, init = 0xFFFF) != u16le(raw, 0xFE)) fail("The firmware file's header is damaged (CRC).")
        val chip = raw.copyOfRange(0x10, 0x14).decodeToString()
        if (u32le(raw, 0x08) != CHIP_TAG || u32le(raw, 0x54) != USER_BIN_TAG) fail("The firmware file's header is not a TuneBuds update.")
        if (chip != CHIP) fail("This firmware is for chip $chip, not the TuneBuds $CHIP.")
        val payloadStart = u32le(raw, 0x5C)
        val payloadSize = u32le(raw, 0x60)
        val trailerStart = u32le(raw, 0x74)
        val trailerSize = u32le(raw, 0x78)
        if (trailerStart != payloadStart + payloadSize || trailerStart + trailerSize != raw.size.toLong() || payloadSize % 512 != 0L) {
            fail("The firmware file's layout is damaged.")
        }
        if (crc32NoFinalXor(raw, payloadStart.toInt(), trailerStart.toInt()) != u32le(raw, 0x88)) {
            fail("The firmware file is damaged (payload CRC).")
        }
        if (crc32NoFinalXor(raw, trailerStart.toInt(), raw.size) != u32le(raw, 0x7C)) {
            fail("The firmware file is damaged (trailer CRC).")
        }
        return FotInspection(chip)
    }

    private fun fail(message: String): Nothing = throw FirmwareFileException(message)
}
