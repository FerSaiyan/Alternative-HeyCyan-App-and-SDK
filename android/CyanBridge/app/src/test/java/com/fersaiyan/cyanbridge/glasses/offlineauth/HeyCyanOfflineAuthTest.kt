package com.fersaiyan.cyanbridge.glasses.offlineauth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit + end-to-end tests for the HeyCyan native offline unlock. No AVD, no device, no server:
 * the state machine runs against [SimulatedHeyCyanGlasses], which models the add.75 firmware gate.
 */
class HeyCyanOfflineAuthTest {

    // ---- Protocol / crypto vectors -----------------------------------------------------------

    @Test
    fun capabilityByteDecodesM02sAsBothRequired() {
        val req = HeyCyanAuthProtocol.parseCapability(HeyCyanAuthProtocol.CAP_M02S_BOTH_REQUIRED)
        assertTrue(req.deviceAuthRequired)
        assertTrue(req.bindRequired)
        assertTrue(req.anyRequired)
    }

    @Test
    fun capabilityByteDecodesPreAuthAsNoneRequired() {
        val req = HeyCyanAuthProtocol.parseCapability(0x00)
        assertFalse(req.deviceAuthRequired)
        assertFalse(req.bindRequired)
        assertFalse(req.anyRequired)
    }

    @Test
    fun defaultLinkKeyMatchesAdd75() {
        val expected = intArrayOf(
            0x06, 0x77, 0x5f, 0x87, 0x91, 0x8d, 0xd4, 0x23,
            0x00, 0x5d, 0xf1, 0xd8, 0xcf, 0x0c, 0x14, 0x2b,
        ).map { it.toByte() }.toByteArray()
        assertArrayEquals(expected, HeyCyanAuthProtocol.DEFAULT_LINK_KEY)
    }

    @Test
    fun symmetricAuthIsDeterministicAndKeyedForEveryCipher() {
        val challenge = ByteArray(16) { it.toByte() }
        // Default = JieLiRcspPlaceholderCipher (NOT AES); plus the optional alternates.
        for (auth in listOf(
            HeyCyanSymmetricAuth(),
            HeyCyanSymmetricAuth(cipher = HeyCyanSymmetricAuth.JieLiRcspPlaceholderCipher),
            HeyCyanSymmetricAuth(cipher = HeyCyanSymmetricAuth.XteaCipher),
            HeyCyanSymmetricAuth(cipher = HeyCyanSymmetricAuth.AesEcbCipher),
        )) {
            val r1 = auth.respondTo(challenge)
            val r2 = auth.respondTo(challenge)
            assertEquals(16, r1.size)
            assertArrayEquals(r1, r2)
            assertTrue(auth.verify(challenge, r1))
            // A wrong key must not verify.
            val wrong = HeyCyanSymmetricAuth(key = ByteArray(16) { 0x11 })
            assertFalse(wrong.verify(challenge, r1))
        }
    }

    // ---- add.78 wire framing (0xBC transport + CRC-16/MODBUS, RCSP) --------------------------

    @Test
    fun bcFrameRoundTripsAndCrcIsVerified() {
        val payload = byteArrayOf(0x02, 0x01, 0x01)
        val frame = HeyCyanAuthProtocol.bcFrame(HeyCyanAuthProtocol.CMD_CAMERA, payload)
        // BC | op | lenLo | lenHi | crcLo | crcHi | payload
        assertEquals(0xBC, frame[0].toInt() and 0xFF)
        assertEquals(HeyCyanAuthProtocol.CMD_CAMERA, frame[1].toInt() and 0xFF)
        assertEquals(payload.size, frame[2].toInt() and 0xFF)
        assertEquals(0, frame[3].toInt() and 0xFF)
        val parsed = HeyCyanAuthProtocol.parseBcFrame(frame)!!
        assertEquals(HeyCyanAuthProtocol.CMD_CAMERA, parsed.opcode)
        assertArrayEquals(payload, parsed.payload)
        // A single flipped payload byte must fail the CRC check.
        val corrupt = frame.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertEquals(null, HeyCyanAuthProtocol.parseBcFrame(corrupt))
    }

    @Test
    fun recipeFramesMatchAdd78() {
        // Capability probe: BC 47 02 00 <crc> 01 00
        val cap = HeyCyanAuthProtocol.capabilityQueryFrame()
        assertEquals(0xBC, cap[0].toInt() and 0xFF)
        assertEquals(0x47, cap[1].toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(0x01, 0x00), HeyCyanAuthProtocol.parseBcFrame(cap)!!.payload)
        // Photo: BC 41 ... 02 01 01
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0x01), HeyCyanAuthProtocol.parseBcFrame(HeyCyanAuthProtocol.photoFrame())!!.payload)
        // Reset-auth verbatim: FE DC BA C0 06 00 02 00 01 EF
        assertArrayEquals(
            intArrayOf(0xFE, 0xDC, 0xBA, 0xC0, 0x06, 0x00, 0x02, 0x00, 0x01, 0xEF).map { it.toByte() }.toByteArray(),
            HeyCyanAuthProtocol.authResetFrame(),
        )
    }

    // ---- End-to-end: pre-auth camera refused -> offline unlock -> camera serves --------------

    @Test
    fun offlineUnlockFlipsCameraGateEndToEnd() = runBlocking {
        val glasses = SimulatedHeyCyanGlasses(userPressesFrontButton = true)

        // Pre-auth: the camera is refused.
        assertFalse("camera must be refused before auth", glasses.requestCameraCapture())

        var prompted = false
        val machine = HeyCyanOfflineAuthStateMachine(glasses)
        val result = machine.unlock().also { /* steps available for diagnostics */ }

        assertEquals(OfflineUnlockResult.SUCCESS, result)
        // Post-unlock: the gate is open and the camera serves — no server, no reflash.
        assertTrue("camera must serve after offline unlock", glasses.requestCameraCapture())
        assertTrue(machine.steps.any { it.startsWith("capability=0xD0") })
        assertTrue(machine.steps.contains("device-auth=ok"))
        assertTrue(machine.steps.contains("bind-prompt-front-button"))
        assertTrue(machine.steps.contains("bind=ok"))
    }

    @Test
    fun bindRejectedWhenUserDoesNotPressFrontButton() = runBlocking {
        val glasses = SimulatedHeyCyanGlasses(userPressesFrontButton = false)
        val result = HeyCyanOfflineAuthStateMachine(glasses).unlock()
        assertEquals(OfflineUnlockResult.BIND_REJECTED, result)
        assertFalse(glasses.requestCameraCapture())
    }

    @Test
    fun boundToAnotherIdIsReportedAndNotForced() = runBlocking {
        val glasses = SimulatedHeyCyanGlasses(
            bindStatus = HeyCyanAuthProtocol.BIND_STATUS_BOUND_OTHER,
            userPressesFrontButton = true,
        )
        val result = HeyCyanOfflineAuthStateMachine(glasses).unlock()
        assertEquals(OfflineUnlockResult.BOUND_TO_OTHER, result)
        assertFalse(glasses.requestCameraCapture())
    }

    @Test
    fun alreadyBoundSelfSucceedsAfterAuth() = runBlocking {
        val glasses = SimulatedHeyCyanGlasses(bindStatus = HeyCyanAuthProtocol.BIND_STATUS_BOUND_SELF)
        val result = HeyCyanOfflineAuthStateMachine(glasses).unlock()
        // Device-auth still runs; bind is already ours.
        assertTrue(result == OfflineUnlockResult.ALREADY_UNLOCKED || result == OfflineUnlockResult.SUCCESS)
        assertTrue(glasses.requestCameraCapture())
    }

    @Test
    fun preAuthUnitNeedsNothing() = runBlocking {
        val glasses = SimulatedHeyCyanGlasses(capabilityByte = 0x00)
        val result = HeyCyanOfflineAuthStateMachine(glasses).unlock()
        assertEquals(OfflineUnlockResult.NOT_REQUIRED, result)
    }

    @Test
    fun wrongKeyFailsDeviceAuth() = runBlocking {
        // Controller uses the wrong key; the glasses verify with the default key and reject.
        val glasses = SimulatedHeyCyanGlasses()
        val machine = HeyCyanOfflineAuthStateMachine(
            glasses,
            symmetricAuth = HeyCyanSymmetricAuth(key = ByteArray(16) { 0x22 }),
        )
        assertEquals(OfflineUnlockResult.AUTH_FAILED, machine.unlock())
        assertFalse(glasses.requestCameraCapture())
    }

    @Test
    fun notConnectedShortCircuits() = runBlocking {
        val glasses = SimulatedHeyCyanGlasses(connected = false)
        assertEquals(OfflineUnlockResult.NOT_CONNECTED, HeyCyanOfflineAuthStateMachine(glasses).unlock())
    }
}
