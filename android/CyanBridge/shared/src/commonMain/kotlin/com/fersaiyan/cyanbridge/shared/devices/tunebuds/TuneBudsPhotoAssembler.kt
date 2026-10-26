package com.fersaiyan.cyanbridge.shared.devices.tunebuds

import com.fersaiyan.cyanbridge.shared.devices.GlassesPhotoLimits
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** E3 status 1 = more, 0 = final. Only a JPEG start may open a new stream. */
class TuneBudsPhotoAssembler(private val maxBytes: Int = GlassesPhotoLimits.MAX_IMAGE_BYTES) {
    private val lock = SynchronizedObject()
    private var bytes = ByteArray(0)

    fun isAtStart(): Boolean = synchronized(lock) { bytes.isEmpty() }

    fun append(payload: ByteArray): ByteArray? {
        return synchronized(lock) {
            if (payload.isEmpty()) return null
            val status = payload[0].toInt() and 255
            if (status !in 0..1) { reset(); return null }
            val size = payload.size - 1
            if (bytes.isEmpty() && (size == 0 || payload[1] != 0xFF.toByte())) return null
            if ((bytes.isEmpty() && size >= 2 && payload[2] != 0xD8.toByte()) ||
                (bytes.size == 1 && size >= 1 && payload[1] != 0xD8.toByte())
            ) { reset(); return null }
            if (bytes.size + size > maxBytes) { reset(); return null }
            bytes += payload.copyOfRange(1, payload.size)
            if (status == 1) return null
            val current = bytes
            reset()
            current.takeIf { it.size >= 4 && it[it.lastIndex - 1] == 0xFF.toByte() && it.last() == 0xD9.toByte() }
        }
    }

    fun reset() {
        synchronized(lock) {
            bytes = ByteArray(0)
        }
    }
}
