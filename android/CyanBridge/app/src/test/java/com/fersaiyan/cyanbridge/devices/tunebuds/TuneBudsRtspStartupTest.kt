package com.fersaiyan.cyanbridge.devices.tunebuds

import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsFrame
import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsFrameType
import com.fersaiyan.cyanbridge.shared.devices.tunebuds.TuneBudsProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TuneBudsRtspStartupTest {
    private fun notification(command: Int, payload: ByteArray) =
        TuneBudsFrame(0, command, TuneBudsFrameType.NOTIFICATION, payload)
    private fun response(command: Int, status: Int) =
        TuneBudsFrame(0, command, TuneBudsFrameType.RESPONSE, byteArrayOf(status.toByte()))

    @Test fun immediateUrlBeforeResponseIsKeptAndVendorWifiQualityPayloadsAreSent() = runTest {
        val frames = MutableSharedFlow<TuneBudsFrame>()
        val calls = mutableListOf<Pair<Int, ByteArray>>()
        val url = "rtsp://10.230.84.141:8554/camera%20live?profile=1"
        val startup = TuneBudsRtspStartup(frames) { command, payload ->
            calls += command to payload
            if (command == TuneBudsProtocol.CMD_START_RTSP) {
                frames.emit(notification(command, url.toByteArray()))
            }
            response(command, 0)
        }
        assertEquals(url, startup.open("Phone hotspot", "password", 0, quality = 75))
        assertEquals(listOf(0xE6, 0xE8), calls.map { it.first })
        assertArrayEquals(TuneBudsProtocol.buildWifiPayload(0, "Phone hotspot", "password", 0), calls[0].second)
        assertArrayEquals(byteArrayOf(6, 5, 10), calls[1].second)
        assertEquals(TuneBudsRtspEndpoint("10.230.84.141", 8554, "/camera%20live?profile=1"), TuneBudsRtspEndpoint.parse(url))
        assertEquals(0, frames.subscriptionCount.value)
    }

    @Test fun knownBusyStatusesRetryWithoutReconfiguringWifi() = runTest {
        val frames = MutableSharedFlow<TuneBudsFrame>()
        val statuses = ArrayDeque(listOf(1, 3, 5, 0))
        var wifiRequests = 0
        val startup = TuneBudsRtspStartup(frames) { command, _ ->
            if (command == 0xE6) { wifiRequests++; response(command, 0) }
            else {
                val status = statuses.removeFirst()
                if (status == 0) frames.emit(notification(0xE8, "rtsp://10.0.0.2/testH264VideoStreamer".toByteArray()))
                response(command, status)
            }
        }
        assertEquals("rtsp://10.0.0.2/testH264VideoStreamer", startup.open("ssid", "password", 0))
        assertEquals(1, wifiRequests)
        assertEquals(3_000L, testScheduler.currentTime)
        assertEquals(554, TuneBudsRtspEndpoint.parse("rtsp://10.0.0.2/testH264VideoStreamer").port)
    }

    @Test fun unsupportedAndUnknownStatusesFailWithoutRetriesOrDanglingSubscribers() = runTest {
        for (status in listOf(4, 6, 9)) {
            val frames = MutableSharedFlow<TuneBudsFrame>()
            var starts = 0
            val startup = TuneBudsRtspStartup(frames) { command, _ ->
                if (command == 0xE8) starts++
                response(command, if (command == 0xE8) status else 0)
            }
            try { startup.open("ssid", "password", 0); fail("Must reject status $status") }
            catch (expected: IOException) { assertTrue(expected.message.orEmpty().contains(if (status == 6) "support" else "$status")) }
            assertEquals(1, starts)
            assertEquals(0, frames.subscriptionCount.value)
        }
    }

    @Test fun missingEndpointTimesOutAndWifiFailureInterruptsEndpointWait() = runTest {
        val frames = MutableSharedFlow<TuneBudsFrame>()
        val missing = TuneBudsRtspStartup(frames) { command, _ -> response(command, 0) }
        try { missing.open("ssid", "password", 0, timeoutMs = 500); fail("Must time out") }
        catch (expected: CancellationException) { }
        assertEquals(0, frames.subscriptionCount.value)
        val wifiFailed = TuneBudsRtspStartup(frames) { command, _ ->
            if (command == 0xE8) frames.emit(notification(0xEC, byteArrayOf(4)))
            response(command, 0)
        }
        try { wifiFailed.open("ssid", "password", 0); fail("Must fail Wi-Fi startup") }
        catch (expected: IOException) { assertTrue(expected.message.orEmpty().contains("Wi-Fi")) }
        assertEquals(0, frames.subscriptionCount.value)
    }

    @Test fun endpointMustBeARealRtspAddress() {
        for (url in listOf("", "http://10.0.0.2/files/", "rtsp:///no-host", "rtsp://10.0.0.2:0/live", "rtsp://10.0.0.2:70000/live", "rtsp://10.0.0.2/live#fragment")) {
            try { TuneBudsRtspEndpoint.parse(url); fail("Must reject $url") }
            catch (expected: IOException) { }
        }
    }
}
