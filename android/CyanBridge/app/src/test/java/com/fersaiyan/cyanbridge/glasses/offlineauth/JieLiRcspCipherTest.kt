package com.fersaiyan.cyanbridge.glasses.offlineauth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pins [JieLiRcspCipher] (key schedule + core, verify direction) to known outputs for a few self-chosen inputs, so a
 * change to the tables or the round structure fails here. The full validation against vectors taken from the glasses
 * lives in the private firmware repo.
 */
class JieLiRcspCipherTest {
    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val sequentialKey = hex("000102030405060708090a0b0c0d0e0f")
    private val defaultKey = hex("06775f87918dd423005df1d8cf0c142b")

    @Test
    fun knownAnswers() {
        assertArrayEquals(
            hex("f769b436c7fb7c0c822e24bb2b2555d3"),
            JieLiRcspCipher.transform(sequentialKey, hex("00000000000000000000000000000000")),
        )
        assertArrayEquals(
            hex("cfa88ece38337fd94983f077fb6afc59"),
            JieLiRcspCipher.transform(sequentialKey, hex("ffeeddccbbaa99887766554433221100")),
        )
        assertArrayEquals(
            hex("ef1379578cb2b234159720cf8ec48eb2"),
            JieLiRcspCipher.transform(defaultKey, hex("43796142726964676520746573742031")), // "CyaBridge test 1"
        )
    }

    @Test
    fun scheduleShapeAndKeySensitivity() {
        assertEquals(272, JieLiRcspCipher.keySchedule(sequentialKey).size)
        val block = hex("00112233445566778899aabbccddeeff")
        val a = JieLiRcspCipher.transform(sequentialKey, block)
        val b = JieLiRcspCipher.transform(sequentialKey.copyOf().also { it[0] = 1 }, block)
        assertFalse(a.contentEquals(b))
        assertArrayEquals(a, JieLiRcspCipher.transform(sequentialKey, block))
    }
}
