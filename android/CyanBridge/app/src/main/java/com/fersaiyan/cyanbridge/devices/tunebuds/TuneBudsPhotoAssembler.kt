package com.fersaiyan.cyanbridge.devices.tunebuds

import com.fersaiyan.cyanbridge.glasses.GlassesPhotoValidation
import java.io.ByteArrayOutputStream

/** E3 status 1 = more, 0 = final. Only a JPEG start may open a new stream. */
internal class TuneBudsPhotoAssembler(private val maxBytes: Int = GlassesPhotoValidation.MAX_IMAGE_BYTES) {
    private val bytes = ByteArrayOutputStream()

    @Synchronized fun isAtStart(): Boolean = bytes.size() == 0

    @Synchronized fun append(payload: ByteArray): ByteArray? {
        if (payload.isEmpty()) return null
        val status = payload[0].toInt() and 255
        if (status !in 0..1) { reset(); return null }
        val size = payload.size - 1
        if (bytes.size() == 0 && (size == 0 || payload[1] != 0xFF.toByte())) return null
        if ((bytes.size() == 0 && size >= 2 && payload[2] != 0xD8.toByte()) ||
            (bytes.size() == 1 && size >= 1 && payload[1] != 0xD8.toByte())
        ) { reset(); return null }
        if (bytes.size() + size > maxBytes) { reset(); return null }
        bytes.write(payload, 1, size)
        if (status == 1) return null
        val current = bytes.toByteArray()
        reset()
        return current.takeIf { it.size >= 4 && it[it.lastIndex - 1] == 0xFF.toByte() && it.last() == 0xD9.toByte() }
    }

    @Synchronized fun reset() = bytes.reset()
}
