package com.fersaiyan.cyanbridge.shared.media

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Byte-exact Ogg/Opus pages too long to inline, pinned by their SHA-256. */
class OggOpusWrapperDigestTest {
    private fun packet(size: Int, seed: Int): ByteArray =
        ByteArray(size) { k -> ((seed * 31 + k * 7) and 0xFF).toByte() }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun lacingBoundariesAndNegativeSerialsAreByteExact() {
        val ogg = OggOpusWrapper.buildOggOpusFromPackets(
            listOf(packet(255, 4), packet(510, 5), packet(1, 6), packet(256, 7)),
            packetDurationMs = 20,
            serial = 0xDEADBEEF.toInt(),
        )
        assertEquals(1158, ogg.size)
        assertEquals("334d62a13ab93f14dce57baf7cb05c97b53bf78c3d373d3dbe3257e870abccd2", ogg.sha256())
    }

    @Test
    fun splitsLongRecordingsAcrossPagesAtTheSegmentLimit() {
        val ogg = OggOpusWrapper.buildOggOpusFromPackets(
            List(600) { packet(40, it) },
            packetDurationMs = 40,
            serial = 42,
        )
        assertEquals(24782, ogg.size)
        assertEquals("1bdb667c954f797ec27ebdb02e3858152220f7a8779be99830eebac5422883de", ogg.sha256())
    }
}
