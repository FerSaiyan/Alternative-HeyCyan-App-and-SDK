package com.fersaiyan.cyanbridge.shared.devices.eyevue

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JieliRcspTest {
    @Test
    fun framesSurviveFragmentationAndGarbage() {
        val packets = listOf(
            RcspPacket(JieliRcsp.OP_E5_BLOCK, byteArrayOf(0, 0, 0x10, 0, 2, 0), sn = 7),
            RcspPacket(JieliRcsp.OP_E5_BLOCK, ByteArray(200) { it.toByte() }, isCommand = false, hasResponse = false, sn = 7),
            RcspPacket(JieliRcsp.OP_DATA, "hi".encodeToByteArray(), hasResponse = false, sn = 3, xmOpcode = 0x22),
        )
        val stream = byteArrayOf(0x00, 0xFE.toByte()) + packets.fold(ByteArray(0)) { acc, p -> acc + JieliRcsp.encode(p) }
        val parser = RcspParser()
        val got = (stream.indices step 5).flatMap { parser.feed(stream.copyOfRange(it, minOf(it + 5, stream.size))) }

        assertEquals(packets.map { listOf(it.opcode, it.isCommand, it.sn, it.xmOpcode) }, got.map { listOf(it.opcode, it.isCommand, it.sn, it.xmOpcode) })
        packets.zip(got).forEach { (sent, received) -> assertContentEquals(sent.payload, received.payload) }
        assertContentEquals(JieliRcsp.AUTH_RESET_CMD, JieliRcsp.encode(RcspPacket(0x06, byteArrayOf(1), sn = 0)))
    }

    @Test
    fun e1MatchesTheVendorAuthLibrary() {
        // Vectors from unicorn emulation of the vendor's libjl_ota_auth.so (add.175).
        mapOf(
            ByteArray(16) to "9e43df377b3a46ce751634667e215de5",
            ByteArray(16) { it.toByte() } to "e5807e887bde07630b4d0e715b3e8991",
            hex("00112233445566778899aabbccddeeff") to "26a69c072324c5e660c28ff912dcd857",
        ).forEach { (rand, expected) -> assertEquals(expected, JieliRcsp.e1(rand).toHex()) }
    }

    @Test
    fun doubleBackupUpdateSendsTheWholeImageAndVerifiesTheNewVersion() {
        val image = ByteArray(1500) { (it * 31).toByte() }
        val device = FakeBr28(image)
        // The card's version read authenticates the link; the install on the same link reuses it
        // (vendor isDeviceAuthPass) instead of sending a second E1 auth.
        val versionRead = RcspOtaSender(ByteArray(0), readInfoOnly = true, random = Random(3))
        versionRead.onConnected()
        pump(versionRead, device)
        assertEquals("ok", versionRead.result)
        assertTrue(versionRead.linkAuthenticated)

        val sender = RcspOtaSender(image, random = Random(7))
        sender.onConnected(linkAuthenticated = versionRead.linkAuthenticated)
        pump(sender, device)
        assertEquals(RcspOtaSender.State.REBOOTING, sender.state)
        assertContentEquals(image, device.received)
        assertEquals(1500L, sender.contentSize)
        assertEquals(1, device.auths)

        device.reboot()
        sender.onReconnected() // a new link authenticates again
        pump(sender, device)

        assertEquals("ok", sender.result)
        assertEquals(2, device.auths)
        assertEquals("1.3.5.0", sender.versionBefore)
        assertEquals("1.3.6.0", sender.info.version)
    }

    @Test
    fun attWritesNeverExceedTheAttributeMaximum() {
        assertEquals(listOf(20, 20, 244, 506, 512, 512), listOf(0, 23, 247, 509, 515, 517).map(JieliRcsp::attWriteSize))
    }

    @Test
    fun aRefusedInquiryStopsTheUpdate() {
        val device = FakeBr28(ByteArray(100), inquiryCode = 3)
        val sender = RcspOtaSender(ByteArray(100), random = Random(1))
        sender.onConnected()
        pump(sender, device)
        assertEquals("error: E2 refused: 3 same version", sender.result)
    }

    private fun pump(sender: RcspOtaSender, device: FakeBr28) {
        repeat(10_000) {
            val out = sender.drainOutbox()
            if (out.isEmpty()) return
            out.forEach { device.onAppBytes(it).forEach(sender::onBytes) }
        }
    }

    /** The BR28 side: auth, target info, E1/E2/E3, then it pulls the image with E8 + E5 requests. */
    private class FakeBr28(private val image: ByteArray, private val inquiryCode: Int = 0) {
        var versionCode = 0x1350
        val received = ByteArray(image.size)
        private var authed = false
        var auths = 0
        private var authStage = 0
        private val myChallenge = byteArrayOf(0) + ByteArray(16) { (it * 7 + 1).toByte() }
        private val parser = RcspParser()
        private var sn = 0x40
        private var next = 0
        private var transferred = false

        fun reboot() {
            authed = false
            authStage = 0
            versionCode = 0x1360
        }

        fun onAppBytes(data: ByteArray): List<ByteArray> {
            if (!authed) return auth(data)
            return parser.feed(data).flatMap(::onPacket)
        }

        private fun auth(data: ByteArray): List<ByteArray> = when {
            data.contentEquals(JieliRcsp.AUTH_RESET_CMD) -> emptyList()
            authStage == 0 && data.size == 17 && data[0].toInt() == 0 -> {
                authStage = 1
                listOf(JieliRcsp.authResponse(data))
            }
            authStage == 1 && data.copyOf(5).contentEquals(JieliRcsp.AUTH_OK) -> {
                authStage = 2
                listOf(myChallenge)
            }
            authStage == 2 && data.contentEquals(JieliRcsp.authResponse(myChallenge)) -> {
                authed = true
                auths++
                listOf(JieliRcsp.AUTH_OK)
            }
            else -> emptyList()
        }

        private fun onPacket(p: RcspPacket): List<ByteArray> {
            if (!p.isCommand) {
                if (p.opcode == JieliRcsp.OP_E5_BLOCK && !transferred) {
                    p.payload.copyInto(received, next - p.payload.size)
                    return listOf(requestNext())
                }
                return emptyList()
            }
            fun respond(payload: ByteArray) = JieliRcsp.encode(RcspPacket(p.opcode, payload, isCommand = false, hasResponse = false, sn = p.sn))
            return when (p.opcode) {
                JieliRcsp.OP_GET_TARGET_INFO -> listOf(
                    respond(byteArrayOf(3, 5, (versionCode shr 8).toByte(), versionCode.toByte(), 5, 8, 1, 0, 0, 0)),
                )
                JieliRcsp.OP_E1_FILE_INFO_OFFSET -> listOf(respond(byteArrayOf(0, 0, 0, 0, 0, 0x40)))
                JieliRcsp.OP_E2_INQUIRE_CAN_UPDATE -> {
                    check(p.payload.contentEquals(image.copyOf(minOf(0x40, image.size))))
                    listOf(respond(byteArrayOf(inquiryCode.toByte())))
                }
                JieliRcsp.OP_E3_ENTER_UPDATE -> listOf(
                    respond(byteArrayOf(0)),
                    command(JieliRcsp.OP_E8_CONTENT_SIZE, byteArrayOf(0, 0, (image.size shr 8).toByte(), image.size.toByte())),
                    requestNext(),
                )
                JieliRcsp.OP_E6_UPDATE_STATUS -> listOf(respond(byteArrayOf(0)))
                JieliRcsp.OP_E7_REBOOT -> listOf(respond(byteArrayOf(0)))
                else -> emptyList()
            }
        }

        private fun requestNext(): ByteArray {
            val n = minOf(400, image.size - next)
            if (n == 0) {
                transferred = true
                return command(JieliRcsp.OP_E5_BLOCK, ByteArray(6))
            }
            val offset = next
            next += n
            return command(JieliRcsp.OP_E5_BLOCK, byteArrayOf(0, 0, (offset shr 8).toByte(), offset.toByte(), (n shr 8).toByte(), n.toByte()))
        }

        private fun command(op: Int, payload: ByteArray): ByteArray {
            sn = (sn + 1) and 0xFF
            return JieliRcsp.encode(RcspPacket(op, payload, sn = sn))
        }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun targetInfoReadsVersionAndUpdateMode() {
        val info = JieliRcsp.parseTargetInfo(byteArrayOf(3, 5, 0x13, 0x60, 5, 8, 0, 1, 0, 0, 2, 9, 1))
        assertEquals("1.3.6.0", info.version)
        assertTrue(info.needBootLoader && !info.doubleBackup && info.mandatory)
    }
}
