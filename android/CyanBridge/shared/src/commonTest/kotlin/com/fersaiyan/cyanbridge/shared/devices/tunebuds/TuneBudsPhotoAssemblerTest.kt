package com.fersaiyan.cyanbridge.shared.devices.tunebuds

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TuneBudsPhotoAssemblerTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 7, 0xFF.toByte(), 0xD9.toByte())

    @Test fun chunksAndFinalPayloadAssembleExactly() {
        val assembler = TuneBudsPhotoAssembler()
        assertNull(assembler.append(byteArrayOf(1) + jpeg.take(2).toByteArray()))
        assertContentEquals(jpeg, assembler.append(byteArrayOf(0) + jpeg.drop(2).toByteArray()))
        assertTrue(assembler.isAtStart())
    }

    @Test fun splitJpegHeaderWorksWhileOrphanTailsAndTruncationAreDiscarded() {
        val assembler = TuneBudsPhotoAssembler()
        assertNull(assembler.append(byteArrayOf(0, 7, 0xFF.toByte(), 0xD9.toByte())))
        assertNull(assembler.append(byteArrayOf(1, 0xFF.toByte())))
        assertContentEquals(jpeg, assembler.append(byteArrayOf(0) + jpeg.drop(1).toByteArray()))
        assertNull(assembler.append(byteArrayOf(0, 0xFF.toByte(), 0xD8.toByte(), 7)))
        assertContentEquals(jpeg, assembler.append(byteArrayOf(0) + jpeg))
    }

    @Test fun overflowErrorAndExplicitCancellationResetAllowNextPhoto() {
        val assembler = TuneBudsPhotoAssembler(5)
        assertNull(assembler.append(byteArrayOf(1) + jpeg))
        assertNull(assembler.append(byteArrayOf(0, 7)))
        assertTrue(assembler.isAtStart())
        assertContentEquals(jpeg, assembler.append(byteArrayOf(0) + jpeg))
        assembler.append(byteArrayOf(1) + jpeg.take(2).toByteArray())
        assembler.append(byteArrayOf(3))
        assertTrue(assembler.isAtStart())
        assembler.append(byteArrayOf(1) + jpeg.take(2).toByteArray())
        assembler.reset()
        assertNull(assembler.append(byteArrayOf(0) + jpeg.drop(2).toByteArray()))
        assertContentEquals(jpeg, assembler.append(byteArrayOf(0) + jpeg))
    }
}
