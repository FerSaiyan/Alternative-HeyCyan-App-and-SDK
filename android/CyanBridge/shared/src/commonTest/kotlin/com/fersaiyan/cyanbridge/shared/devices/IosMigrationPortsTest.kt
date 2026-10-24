package com.fersaiyan.cyanbridge.shared.devices

import com.fersaiyan.cyanbridge.shared.chat.ExternalChatExportParser
import com.fersaiyan.cyanbridge.shared.devices.eyevue.EyevueFrameDecoder
import com.fersaiyan.cyanbridge.shared.devices.eyevue.EyevueProtocol
import com.fersaiyan.cyanbridge.shared.media.OggOpusWrapper
import com.fersaiyan.cyanbridge.shared.plugins.CommunityPluginCatalogParser
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Covers the Android logic ported to commonMain for the iOS host. */
class IosMigrationPortsTest {
    @Test
    fun eyevueDatagramMatchesAndroidFraming() {
        // Battery request: AB 55 | len 00 03 | cmd 0x17 | payload 00 | crc 0x17
        val packet = EyevueProtocol.buildGetBatteryPacket()
        assertContentEquals(byteArrayOf(0xAB.toByte(), 0x55, 0x00, 0x03, 0x17, 0x00, 0x17), packet)
        val frame = EyevueProtocol.parseDatagram(packet)
        assertEquals(EyevueProtocol.CMD_GET_BATTERY, frame.commandId)
    }

    @Test
    fun eyevueDecoderReassemblesSplitNotifications() {
        val battery = EyevueProtocol.buildDatagram(EyevueProtocol.CMD_RECEIVE_BATTERY, byteArrayOf(1, 87))
        val decoder = EyevueFrameDecoder()
        assertTrue(decoder.append(battery.copyOfRange(0, 3)).isEmpty())
        val frames = decoder.append(battery.copyOfRange(3, battery.size))
        val parsed = EyevueProtocol.parseBattery(frames.single())
        assertEquals(87, parsed?.percent)
        assertEquals(true, parsed?.isCharging)
    }

    @Test
    fun eyevueMediaListReadsXmlIndex() {
        val xml = "<LIST><FILE><NAME>IMG_0001.JPG</NAME></FILE><FILE><FPATH>A:\\DCIM\\MOV_0002.mp4</FPATH></FILE></LIST>"
        assertEquals(listOf("IMG_0001.JPG", "MOV_0002.mp4"), EyevueProtocol.parseMediaList(xml))
    }

    @Test
    fun classifierDetectsBy16BitServiceUuidAndName() {
        assertEquals(DeviceClass.EYEVUE, BleDeviceClassifier.guessDeviceClass(null, serviceUuids = listOf("AA12")))
        assertEquals(DeviceClass.HEY_CYAN, BleDeviceClassifier.guessDeviceClass("O_W3"))
        assertEquals(
            DeviceClass.HEY_CYAN,
            BleDeviceClassifier.guessDeviceClass(null, serviceUuids = listOf("FEE7"), heyCyanServiceUuids = listOf("fee7")),
        )
        assertEquals(DeviceClass.UNKNOWN, BleDeviceClassifier.guessDeviceClass("Kitchen speaker"))
    }

    @Test
    fun oggWrapperWrapsFixed40BytePackets() {
        val raw = ByteArray(40 * 10) { (it % 251).toByte() }
        val wrapped = OggOpusWrapper.wrapIfNeeded(raw)
        assertEquals("OggS", wrapped.copyOfRange(0, 4).decodeToString())
        assertTrue(wrapped.decodeToString(throwOnInvalidSequence = false).contains("OpusHead"))
        // Already-wrapped input is returned unchanged.
        assertContentEquals(wrapped, OggOpusWrapper.wrapIfNeeded(wrapped))
    }

    @Test
    fun chatGptAndClaudeExportsParse() {
        val chatGpt = """
            [{"id":"c1","title":"Trip","mapping":{
              "a":{"message":{"author":{"role":"user"},"content":{"parts":["Plan a trip"]},"create_time":1.0}},
              "b":{"message":{"author":{"role":"assistant"},"content":{"parts":["Sure"]},"create_time":2.0}},
              "s":{"message":{"author":{"role":"system"},"content":{"parts":["hidden"]}}}}}]
        """.trimIndent()
        val parsed = ExternalChatExportParser.parse(chatGpt, ExternalChatExportParser.Provider.CHATGPT).single()
        assertEquals("Trip", parsed.title)
        assertEquals(listOf("user", "assistant"), parsed.turns.map { it.role })

        val claude = """[{"uuid":"u1","name":"Recipe","chat_messages":[
            {"sender":"human","text":"Pasta?","created_at":1700000000},
            {"sender":"assistant","content":[{"text":"Boil water"}]}]}]"""
        val conversation = ExternalChatExportParser.parse(claude, ExternalChatExportParser.Provider.CLAUDE).single()
        assertEquals(listOf("Pasta?", "Boil water"), conversation.turns.map { it.text })
        assertEquals(1_700_000_000_000L, conversation.turns.first().timestampMs)
    }

    @Test
    fun communityCatalogReadsNestedAndFlatMetrics() {
        val body = """{"plugins":[{"id":"p1","title":"Morning","downloads":{"weekly":3},"votes_all_time":7}]}"""
        val plugin = CommunityPluginCatalogParser.parse(body).single()
        assertEquals(3, plugin.downloadsWeekly)
        assertEquals(7, plugin.votesAll)
        assertEquals("Unknown", plugin.author)
    }
}
