package com.fersaiyan.cyanbridge.shared.devices.mentra

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MentraLiveStreamTest {
    private fun problem(url: String) = (MentraStreamUrls.checkBroadcastUrl(url) as MentraBroadcastUrlCheck.Invalid).problem

    @Test
    fun broadcastUrlsNeedASupportedSchemeAHostAndAStreamPath() {
        assertEquals(
            MentraBroadcastUrlCheck.Valid("rtmp://a.rtmp.youtube.com/live2/abcd-1234", MentraStreamProtocol.RTMP),
            MentraStreamUrls.checkBroadcastUrl("  rtmp://a.rtmp.youtube.com/live2/abcd-1234 "),
        )
        assertEquals(
            MentraBroadcastUrlCheck.Valid("rtmps://live.example.com:443/app/key", MentraStreamProtocol.RTMPS),
            MentraStreamUrls.checkBroadcastUrl("rtmps://live.example.com:443/app/key"),
        )
        assertEquals(
            MentraBroadcastUrlCheck.Valid("srt://192.168.1.20:8890?streamid=publish:live/stream", MentraStreamProtocol.SRT),
            MentraStreamUrls.checkBroadcastUrl("srt://192.168.1.20:8890?streamid=publish:live/stream"),
        )
        assertEquals(MentraBroadcastUrlProblem.EMPTY, problem(" "))
        assertEquals(MentraBroadcastUrlProblem.UNSUPPORTED_SCHEME, problem("http://example.com/whip"))
        assertEquals(MentraBroadcastUrlProblem.UNSUPPORTED_SCHEME, problem("example.com/live/key"))
        assertEquals(MentraBroadcastUrlProblem.MISSING_HOST, problem("rtmp:///live/key"))
        assertEquals(MentraBroadcastUrlProblem.LOCAL_HOST, problem("rtmp://localhost/live/key"))
        assertEquals(MentraBroadcastUrlProblem.LOCAL_HOST, problem("rtmp://127.0.0.1:1935/live/key"))
        assertEquals(MentraBroadcastUrlProblem.MISSING_STREAM_PATH, problem("rtmp://example.com/live"))
        assertEquals(MentraBroadcastUrlProblem.MISSING_PORT, problem("srt://example.com?streamid=x"))
    }

    @Test
    fun redactionHidesStreamKeysAndSecrets() {
        assertEquals("rtmp://a.rtmp.youtube.com/live2/****", MentraStreamUrls.redacted("rtmp://a.rtmp.youtube.com/live2/abcd-1234"))
        assertEquals(
            "srt://host:8890?streamid=****&latency=200",
            MentraStreamUrls.redacted("srt://host:8890?streamid=publish:live&latency=200"),
        )
        assertEquals("rtmp://host/app/****", MentraStreamUrls.redacted("rtmp://user:pw@host/app/key"))
        assertEquals("rtmp://10.0.0.2:1935/live/****", MentraStreamUrls.redacted(MentraStreamUrls.previewPublishUrl("10.0.0.2", 1935, "k")))
    }

    @Test
    fun subnetsCompareByPrefix() {
        assertTrue(MentraStreamRoute.sameSubnet("192.168.43.1", "192.168.43.118", 24))
        assertFalse(MentraStreamRoute.sameSubnet("192.168.43.1", "192.168.44.1", 24))
        assertTrue(MentraStreamRoute.sameSubnet("10.1.2.3", "10.200.0.1", 8))
        assertFalse(MentraStreamRoute.sameSubnet("10.1.2.3", "not-an-ip", 24))
        assertFalse(MentraStreamRoute.sameSubnet("10.1.2.3", "10.1.2.4", 0))
        assertNull(MentraStreamRoute.parseIpv4("256.1.1.1"))
        assertEquals(0xC0A8_2B01L, MentraStreamRoute.parseIpv4("192.168.43.1"))
    }

    @Test
    fun streamStatesMapToBroadcastPhases() {
        assertEquals(MentraBroadcastPhase.STARTING, MentraBroadcastPhase.fromStreamState("initializing"))
        assertEquals(MentraBroadcastPhase.LIVE, MentraBroadcastPhase.fromStreamState("streaming"))
        assertEquals(MentraBroadcastPhase.LIVE, MentraBroadcastPhase.fromStreamState("reconnected"))
        assertEquals(MentraBroadcastPhase.RECONNECTING, MentraBroadcastPhase.fromStreamState("reconnecting"))
        assertEquals(MentraBroadcastPhase.RECONNECTING, MentraBroadcastPhase.fromStreamState("error", willRetry = true))
        assertEquals(MentraBroadcastPhase.FAILED, MentraBroadcastPhase.fromStreamState("error"))
        assertEquals(MentraBroadcastPhase.FAILED, MentraBroadcastPhase.fromStreamState("reconnect_failed"))
        assertEquals(MentraBroadcastPhase.STOPPED, MentraBroadcastPhase.fromStreamState("stopped"))
        assertNull(MentraBroadcastPhase.fromStreamState("unknown"))
        assertTrue(MentraBroadcastPhase.RECONNECTING.isActive)
        assertFalse(MentraBroadcastPhase.FAILED.isActive)
    }
}
