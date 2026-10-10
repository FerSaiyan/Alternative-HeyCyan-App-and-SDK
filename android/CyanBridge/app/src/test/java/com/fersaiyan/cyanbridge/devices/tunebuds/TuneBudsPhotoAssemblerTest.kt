package com.fersaiyan.cyanbridge.devices.tunebuds

import org.junit.Assert.*
import org.junit.Test

class TuneBudsPhotoAssemblerTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 7, 0xFF.toByte(), 0xD9.toByte())

    @Test fun `chunks and final payload assemble exactly`() {
        val assembler = TuneBudsPhotoAssembler()
        assertNull(assembler.append(byteArrayOf(1) + jpeg.take(2).toByteArray()))
        assertArrayEquals(jpeg, assembler.append(byteArrayOf(0) + jpeg.drop(2).toByteArray()))
        assertTrue(assembler.isAtStart())
    }

    @Test fun `split JPEG header works while orphan tails and truncation are discarded`() {
        val assembler = TuneBudsPhotoAssembler()
        assertNull(assembler.append(byteArrayOf(0, 7, 0xFF.toByte(), 0xD9.toByte())))
        assertNull(assembler.append(byteArrayOf(1, 0xFF.toByte())))
        assertArrayEquals(jpeg, assembler.append(byteArrayOf(0) + jpeg.drop(1).toByteArray()))
        assertNull(assembler.append(byteArrayOf(0, 0xFF.toByte(), 0xD8.toByte(), 7)))
        assertArrayEquals(jpeg, assembler.append(byteArrayOf(0) + jpeg))
    }

    @Test fun `overflow error and explicit cancellation reset allow next photo`() {
        val assembler = TuneBudsPhotoAssembler(5)
        assertNull(assembler.append(byteArrayOf(1) + jpeg))
        assertNull(assembler.append(byteArrayOf(0, 7)))
        assertTrue(assembler.isAtStart())
        assertArrayEquals(jpeg, assembler.append(byteArrayOf(0) + jpeg))
        assembler.append(byteArrayOf(1) + jpeg.take(2).toByteArray())
        assembler.append(byteArrayOf(3))
        assertTrue(assembler.isAtStart())
        assembler.append(byteArrayOf(1) + jpeg.take(2).toByteArray())
        assembler.reset()
        assertNull(assembler.append(byteArrayOf(0) + jpeg.drop(2).toByteArray()))
        assertArrayEquals(jpeg, assembler.append(byteArrayOf(0) + jpeg))
    }
}
