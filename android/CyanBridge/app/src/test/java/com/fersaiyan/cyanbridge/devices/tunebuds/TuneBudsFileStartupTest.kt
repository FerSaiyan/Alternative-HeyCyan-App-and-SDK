package com.fersaiyan.cyanbridge.devices.tunebuds

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TuneBudsFileStartupTest {
    private fun notify(command: Int, payload: ByteArray) = TuneBudsFrame(0, command, TuneBudsFrameType.NOTIFICATION, payload)
    private fun reply(command: Int, status: Int) = TuneBudsFrame(0, command, TuneBudsFrameType.RESPONSE, byteArrayOf(status.toByte()))

    @Test fun immediateP2pSignalsAreKeptAndConnectionPrecedesReturningEndpoint() = runTest {
        val frames = MutableSharedFlow<TuneBudsFrame>()
        val commands = mutableListOf<Pair<Int, ByteArray>>()
        val url = "http://192.168.49.82:80/vendor/media/"
        var connected = false
        val startup = TuneBudsFileStartup(frames) { command, payload ->
            commands += command to payload
            if (command == 0xE7) {
                // All notifications arrive before the command reply; order is not fixed.
                frames.emit(notify(0xF2, byteArrayOf(0xFA.toByte(), 0, 0x11, 0x15, 0xA1.toByte(), 0x7B)))
                frames.emit(notify(0xE7, url.toByteArray()))
                frames.emit(notify(0xEC, byteArrayOf(2)))
            }
            reply(command, 0)
        }
        assertEquals(url, startup.open(2, "AiGlassDb_42", "", connectP2p = { address ->
            assertEquals("FA:00:11:15:A1:7B", address)
            connected = true
        }))
        assertTrue(connected)
        assertEquals(listOf(0xE6, 0xE7), commands.map { it.first })
        assertArrayEquals(byteArrayOf(1, 1, 2, 2, 12) + "AiGlassDb_42".toByteArray() + byteArrayOf(3, 0, 4, 1, 0), commands[0].second)
        assertTrue(commands[1].second.isEmpty())
        assertEquals(0, frames.subscriptionCount.value)
    }

    @Test fun allOfficialBusyStatusesRetryWithoutReconfiguringTheHotspot() = runTest {
        val frames = MutableSharedFlow<TuneBudsFrame>()
        val statuses = ArrayDeque(listOf(1, 3, 5, 0))
        var configs = 0
        val startup = TuneBudsFileStartup(frames) { command, _ ->
            if (command == 0xE6) { configs++; reply(command, 0) }
            else {
                val status = statuses.removeFirst()
                if (status == 0) frames.emit(notify(0xE7, "http://10.230.84.141:80/files/".toByteArray()))
                reply(command, status)
            }
        }
        assertEquals("http://10.230.84.141:80/files/", startup.open(0, "AndroidShare_8747", "password"))
        assertEquals(1, configs)
        assertEquals(3_000L, testScheduler.currentTime)
    }

    @Test fun lowBatteryAndUnknownRepliesAreTerminal() = runTest {
        for (status in listOf(2, 4, 6)) {
            val frames = MutableSharedFlow<TuneBudsFrame>()
            var starts = 0
            val startup = TuneBudsFileStartup(frames) { command, _ ->
                if (command == 0xE7) starts++
                reply(command, if (command == 0xE7) status else 0)
            }
            try { startup.open(0, "ssid", "password"); fail("Must fail status $status") }
            catch (expected: IOException) {
                assertTrue(expected.message.orEmpty().contains(if (status == 4) "low battery" else "$status"))
            }
            assertEquals(1, starts)
            assertEquals(0, frames.subscriptionCount.value)
        }
    }

    @Test fun wholeStartupIncludingRequestsHasABoundedDeadline() = runTest {
        val frames = MutableSharedFlow<TuneBudsFrame>()
        val startup = TuneBudsFileStartup(frames) { _, _ -> awaitCancellation() }
        try { startup.open(0, "ssid", "password", timeoutMs = 500); fail("Must time out even during E6") }
        catch (expected: CancellationException) { }
        assertEquals(500L, testScheduler.currentTime)
        assertEquals(0, frames.subscriptionCount.value)
    }

    @Test fun endpointAloneDoesNotEstablishP2pWithoutOpenAndPeerNotifications() = runTest {
        val frames = MutableSharedFlow<TuneBudsFrame>()
        var connects = 0
        val startup = TuneBudsFileStartup(frames) { command, _ ->
            if (command == 0xE7) frames.emit(notify(0xE7, "http://192.168.49.82:80/files/".toByteArray()))
            reply(command, 0)
        }
        try { startup.open(2, "AiGlassDb_1", "", timeoutMs = 500, connectP2p = { connects++ }); fail("Must wait for P2P signals") }
        catch (expected: CancellationException) { }
        assertEquals(0, connects)
        assertEquals(0, frames.subscriptionCount.value)
    }

    @Test fun wifiFailureInterruptsAndroidP2pNegotiation() = runTest {
        val frames = MutableSharedFlow<TuneBudsFrame>()
        val startup = TuneBudsFileStartup(frames) { command, _ ->
            if (command == 0xE7) {
                frames.emit(notify(0xF2, byteArrayOf(1, 2, 3, 4, 5, 6)))
                frames.emit(notify(0xEC, byteArrayOf(2)))
            }
            reply(command, 0)
        }
        try {
            startup.open(2, "AiGlassDb_1", "", connectP2p = {
                frames.emit(notify(0xEC, byteArrayOf(5)))
                awaitCancellation()
            })
            fail("Must interrupt P2P connect")
        } catch (expected: IOException) { assertTrue(expected.message.orEmpty().contains("state 5")) }
        assertEquals(0, frames.subscriptionCount.value)
    }

    @Test fun peersMustMatchVendorMacOrGeneratedSsid() {
        assertTrue(matchesTuneBudsP2pPeer("FA:00:11:15:A1:7B", "AiGlassDb_42", "fa:00:11:15:a1:7b", "different-name"))
        assertTrue(matchesTuneBudsP2pPeer("FA:00:11:15:A1:7B", "AiGlassDb_42", "11:22:33:44:55:66", "AiGlassDb_42"))
        assertFalse(matchesTuneBudsP2pPeer("FA:00:11:15:A1:7B", "AiGlassDb_42", "11:22:33:44:55:66", "other glasses"))
        assertNull(TuneBudsProtocol.parseP2pAddress(byteArrayOf(1, 2, 3)))
    }
}
