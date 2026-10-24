package com.fersaiyan.cyanbridge.bridge.devices.memomind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Characterizes the MemoMind RFCOMM frames before the encoder moves to shared code.
 * Robolectric supplies Android's org.json, which escapes '/' as "\/".
 * Sequence numbers and default ids are global counters, so the tests read them back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoMindPacketEncoderCharacterizationTest {
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun seq(frame: ByteArray) = frame[4].toInt() and 0xFF

    private fun pushFrame(seq: Int, group: Int, opcode: Int, component: Int, json: String): String {
        val payload = json.toByteArray(Charsets.UTF_8)
        val body = byteArrayOf(
            0xFA.toByte(), 0, 0, 0, seq.toByte(), group.toByte(), opcode.toByte(), 8, 0, 0, 1,
            component.toByte(), 5, 0, (payload.size shr 8).toByte(), payload.size.toByte(),
        ) + payload + byteArrayOf(0)
        val sum = body.fold(0) { acc, b -> (acc + (b.toInt() and 0xFF)) and 0xFFFF }
        val frame = body + byteArrayOf((sum shr 8).toByte(), sum.toByte())
        frame[2] = (frame.size shr 8).toByte()
        frame[3] = frame.size.toByte()
        return frame.hex()
    }

    private fun payloadJson(frame: ByteArray): String = String(frame.copyOfRange(16, frame.size - 3), Charsets.UTF_8)

    @Test
    fun requestsAndSettingsWritesAreByteExact() {
        val info = MemoMindPacketEncoder.encodeDeviceInfoRequest()
        assertEquals("fa000009%02x010201%02x".format(seq(info), (seq(info) + 6) and 0xFF), info.hex())
        val battery = MemoMindPacketEncoder.encodeBatteryRequest()
        assertEquals("fa000009%02x010601%02x".format(seq(battery), (seq(battery) + 10) and 0xFF), battery.hex())

        fun setting(frame: ByteArray, opcode: Int, value: Int) = assertEquals(
            "fa00000e%02x01%02x08000001%02x01%02x".format(
                seq(frame), opcode, value, (0xFA + 0x0E + seq(frame) + 1 + opcode + 8 + 1 + value + 1) and 0xFF,
            ),
            frame.hex(),
        )
        setting(MemoMindPacketEncoder.encodeSetBrightness(42), 0x0B, 10)
        setting(MemoMindPacketEncoder.encodeSetBrightness(-3), 0x0B, 0)
        setting(MemoMindPacketEncoder.encodeSetAutoBrightness(true), 0x0F, 0)
        setting(MemoMindPacketEncoder.encodeSetAutoBrightness(false), 0x0F, 1)
        setting(MemoMindPacketEncoder.encodeSetWakeAngle(75), 0x0C, 60)
        setting(MemoMindPacketEncoder.encodeSetFontSize(0), 0x0E, 1)
    }

    @Test
    fun cardPushesUseVendorJsonEscaping() {
        val schedule = MemoMindPacketEncoder.encodeScheduleItems(
            listOf(
                MemoMindPacketEncoder.ScheduleItem(
                    title = "Ação \"A/B\"\\",
                    body = "line1\nline2\t\u0001",
                    timestamp = "10:00",
                    completed = 1,
                    id = 7,
                ),
                MemoMindPacketEncoder.ScheduleItem(title = "Two", id = 8),
            ),
        )
        assertEquals(
            pushFrame(
                seq(schedule), 2, 8, 9,
                "[{\"id\":7,\"ti\":\"Ação \\\"A\\/B\\\"\\\\\",\"ts\":\"10:00\",\"c\":\"line1\\nline2\\t\\u0001\",\"do\":1}," +
                    "{\"id\":8,\"ti\":\"Two\",\"ts\":\"\",\"c\":\"\",\"do\":0}]",
            ),
            schedule.hex(),
        )

        val calendar = MemoMindPacketEncoder.encodeCalendarItems(
            listOf(MemoMindPacketEncoder.CalendarItem(title = "Dentist", timestamp = "2026-10-24 09:00", body = "Bring card", id = 3)),
        )
        assertEquals(
            pushFrame(seq(calendar), 2, 8, 0x0A, "[{\"id\":3,\"ti\":\"Dentist\",\"ts\":\"2026-10-24 09:00\",\"c\":\"Bring card\"}]"),
            calendar.hex(),
        )

        val news = MemoMindPacketEncoder.encodeNewsItems(listOf(MemoMindPacketEncoder.NewsItem(content = "Rain \u000c", id = 4)))
        assertEquals(
            pushFrame(seq(news), 2, 8, 6, "[{\"c\":\"Rain \\f\",\"id\":4,\"src\":\"CyanBridge\",\"t\":\"\"}]"),
            news.hex(),
        )

        val empty = MemoMindPacketEncoder.encodeEmptySchedule()
        assertEquals(pushFrame(seq(empty), 2, 8, 9, "[]"), empty.hex())
    }

    @Test
    fun textHelpersUseGeneratedIds() {
        val text = MemoMindPacketEncoder.encodeShowText("  hi  ")
        val id = Regex("\"id\":(\\d+)").find(payloadJson(text))!!.groupValues[1].toInt()
        assertTrue(id >= 10_000)
        assertEquals(pushFrame(seq(text), 2, 8, 9, "[{\"id\":$id,\"ti\":\"hi\",\"ts\":\"\",\"c\":\"\",\"do\":0}]"), text.hex())

        val blank = MemoMindPacketEncoder.encodeShowText(" ")
        val blankId = Regex("\"id\":(\\d+)").find(payloadJson(blank))!!.groupValues[1].toInt()
        assertEquals(id + 1, blankId)
        assertEquals("[{\"id\":$blankId,\"ti\":\"CyanBridge\",\"ts\":\"\",\"c\":\"\",\"do\":0}]", payloadJson(blank))

        val lines = MemoMindPacketEncoder.encodeLines(listOf("a", " ", "b"))
        assertEquals(
            "[{\"id\":${id + 2},\"ti\":\"a\",\"ts\":\"\",\"c\":\"\",\"do\":0},{\"id\":${id + 3},\"ti\":\"b\",\"ts\":\"\",\"c\":\"\",\"do\":0}]",
            payloadJson(lines),
        )
        val card = MemoMindPacketEncoder.encodeScheduleCard(" ", "body")
        assertEquals("[{\"id\":${id + 4},\"ti\":\"CyanBridge\",\"ts\":\"\",\"c\":\"body\",\"do\":0}]", payloadJson(card))
    }

    @Test
    fun notificationCarriesAppPackageAndTimestamp() {
        val before = System.currentTimeMillis() / 1000L
        val frame = MemoMindPacketEncoder.encodeNotification(title = "T/1", body = "B")
        val after = System.currentTimeMillis() / 1000L
        val json = payloadJson(frame)
        val match = Regex("^\\{\"id\":(\\d+),\"a\":\"CyanBridge\",\"type\":0,\"ts\":(\\d+),\"c\":\"B\",\"ti\":\"T\\\\/1\"," +
            "\"pkg_name\":\"com.fersaiyan.cyanbridge\"\\}$").find(json)!!
        assertTrue(match.groupValues[2].toLong() in before..after)
        assertEquals(pushFrame(seq(frame), 5, 1, 1, json), frame.hex())
    }

    @Test
    fun extractsResponsePayloads() {
        val json = "{\"battery\":80}".toByteArray()
        val frame = byteArrayOf(0xFA.toByte(), 0, 0, 0, 9, 1, 6, 2, 0, 0, json.size.toByte()) + json + byteArrayOf(0, 0)
        assertEquals("{\"battery\":80}", MemoMindPacketEncoder.extractJsonPayload(frame, 1, 6))
        assertNull(MemoMindPacketEncoder.extractJsonPayload(frame, 1, 2))
        assertNull(MemoMindPacketEncoder.extractJsonPayload(frame.copyOf(frame.size - 1), 1, 6))
        frame[7] = 1
        assertNull(MemoMindPacketEncoder.extractJsonPayload(frame, 1, 6))
    }
}
