package com.fersaiyan.cyanbridge.shared.devices.firmware

import com.fersaiyan.cyanbridge.shared.devices.firmware.FirmwareChecksums.crc16Ccitt
import com.fersaiyan.cyanbridge.shared.devices.firmware.FirmwareChecksums.u16le
import com.fersaiyan.cyanbridge.shared.devices.firmware.FirmwareChecksums.u32le

/** What [EyevueUfw.inspect] read from a valid EyeVue `.ufw`. */
data class UfwInspection(
    /** The chip the container names (`AC701N` on every EyeVue build). */
    val chip: String,
    /**
     * The EyeVue model code the firmware reports to the phone (vendor cmd 100, e.g. `S3CY02`),
     * read from the application; null when this build stores it in a form we do not recognise.
     */
    val modelCode: String?,
)

/**
 * The EyeVue (JieLi BR28) `.ufw` checks, the same ones the owner's builder
 * (firmware_dump `eyevue_fw_builder_add175.py`, `Ufw` / `Image` / `verify`) runs, and the ones
 * the glasses' own update code checks first (header CRC, chip):
 * - the 0x40-byte header, de-obfuscated with the JieLi LFSR keystream (key 0xFFFF): header CRC
 *   over [2, 0x40), file size, record-list CRC over the raw records, chip `AC701N`, `JLUFW` trailer;
 * - the `flash.bin` record's CRC over its raw payload;
 * - the application area (`app_area_head`, SFC-decrypted): its header CRC and data CRC over
 *   [0x2420, end of app).
 */
object EyevueUfw {
    const val CHIP = "AC701N"
    private const val HEADER = 0x40
    private const val RECORD = 0x50
    private const val APP_DIR = 0x2400
    private const val CHIP_KEY = 0x7DF0
    private const val BASE_DIV4 = 0xF701

    fun inspect(raw: ByteArray): UfwInspection {
        if (raw.size < APP_DIR + 64) fail("The file is too small to be EyeVue firmware.")
        val header = lfsrXor(raw, 0, HEADER, 0xFFFF)
        val headerCrc = u16le(header, 0)
        val listCrc = u16le(header, 2)
        val fileSize = u32le(header, 4)
        val records = u16le(header, 8)
        if (fileSize != raw.size.toLong() || crc16Ccitt(header, 2, HEADER) != headerCrc) {
            fail("This is not an EyeVue firmware file (.ufw header check failed).")
        }
        if (raw.copyOfRange(raw.size - 16, raw.size - 11).decodeToString() != "JLUFW") {
            fail("The firmware file is incomplete (the JLUFW end marker is missing).")
        }
        if (records !in 1..64 || HEADER + RECORD * records > raw.size) fail("The firmware file's record list is damaged.")
        if (crc16Ccitt(raw, HEADER, HEADER + RECORD * records) != listCrc) fail("The firmware file's record list is damaged (CRC).")
        val chip = header.copyOfRange(0x10, 0x20).takeWhile { it.toInt() != 0 }.toByteArray().decodeToString()
        if (chip != CHIP) fail("This firmware is for chip $chip, not the EyeVue $CHIP.")

        var flash: IntArray? = null
        for (i in 0 until records) {
            val record = lfsrXor(raw, HEADER + RECORD * i, RECORD, 0xFFFF)
            val name = record.copyOfRange(0x40, 0x50).takeWhile { it.toInt() != 0 }.toByteArray().decodeToString()
            if (name == "flash.bin") {
                flash = intArrayOf(u32le(record, 8).toInt(), u32le(record, 12).toInt(), u16le(record, 4))
            }
        }
        val (flashOff, flashLen, flashCrc) = flash ?: fail("The firmware file has no flash image.")
        if (flashOff < HEADER || flashLen <= 0 || flashOff.toLong() + flashLen > raw.size) fail("The firmware file's flash image is out of range.")
        if (crc16Ccitt(raw, flashOff, flashOff + flashLen) != flashCrc) fail("The firmware file is damaged (flash image CRC).")
        val flashEnd = flashOff + flashLen

        val head = sfcDecrypt(raw, APP_DIR, APP_DIR + 32)
        val headName = head.copyOfRange(16, 32).takeWhile { it.toInt() != 0 }.toByteArray().decodeToString()
        if (headName != "app_area_head" || crc16Ccitt(head, 2, 32) != u16le(head, 0)) {
            fail("The firmware file's application header is damaged.")
        }
        val appEnd = APP_DIR + u32le(head, 8).toInt()
        if (appEnd <= APP_DIR + 32 || appEnd > flashEnd) fail("The firmware file's application size is out of range.")
        val app = sfcDecrypt(raw, APP_DIR, appEnd)
        if (crc16Ccitt(app, 0x20, app.size) != u16le(head, 2)) fail("The firmware file is damaged (application CRC).")
        return UfwInspection(chip = chip, modelCode = modelCode(app))
    }

    /**
     * The model code the application sends in its cmd 100 reply: an `S3` immediate followed by
     * the 4-character customer immediate (`40 E0 'S' '3' C1 FF 'C' 'Y' '0' '2'`), found in the
     * S3CY02 and S3S100 builds. Some builds encode short codes differently; those return null.
     */
    internal fun modelCode(app: ByteArray): String? {
        val found = mutableSetOf<String>()
        for (i in 0..app.size - 10) {
            if (app[i] != 0x40.toByte() || app[i + 1] != 0xE0.toByte() || app[i + 4] != 0xC1.toByte() || app[i + 5] != 0xFF.toByte()) continue
            val chars = (i + 2 until i + 10).filter { it < i + 4 || it >= i + 6 }.map { (app[it].toInt() and 0xFF).toChar() }
            if (chars.all { it.isLetterOrDigit() && it.code < 128 || it == ' ' } && chars[0].isLetter()) {
                found += chars.joinToString("").replace(" ", "").uppercase()
            }
        }
        return found.singleOrNull()
    }

    /** The JieLi LFSR keystream (CRC-16/CCITT shift register) XORed over [len] bytes at [off]. */
    private fun lfsrXor(raw: ByteArray, off: Int, len: Int, key: Int): ByteArray {
        val out = ByteArray(len)
        var k = key and 0xFFFF
        for (i in 0 until len) {
            out[i] = (raw[off + i].toInt() xor (k and 0xFF)).toByte()
            k = ((k shl 1) and 0xFFFF) xor (if (k and 0x8000 != 0) 0x1021 else 0)
        }
        return out
    }

    /** SFC decryption of [from, to): 32-byte blocks, each keyed by its file offset. */
    private fun sfcDecrypt(raw: ByteArray, from: Int, to: Int): ByteArray {
        val out = ByteArray(to - from)
        var block = from
        while (block < to) {
            var k = (CHIP_KEY xor ((BASE_DIV4 + (block shr 2)) and 0xFFFF)) and 0xFFFF
            for (i in block until minOf(block + 32, to)) {
                out[i - from] = (raw[i].toInt() xor (k and 0xFF)).toByte()
                k = ((k shl 1) and 0xFFFF) xor (if (k and 0x8000 != 0) 0x1021 else 0)
            }
            block += 32
        }
        return out
    }

    private fun fail(message: String): Nothing = throw FirmwareFileException(message)
}
