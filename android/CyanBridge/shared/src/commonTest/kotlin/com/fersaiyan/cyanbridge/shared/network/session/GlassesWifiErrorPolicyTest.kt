package com.fersaiyan.cyanbridge.shared.network.session

import kotlin.test.Test
import kotlin.test.assertEquals

class GlassesWifiErrorPolicyTest {
    @Test
    fun noisy255OnlyMattersToARunningMediaSync() {
        assertEquals(
            GlassesWifiErrorAction.STOP_FOR_RETRY_WITH_DEVICE_RESET,
            GlassesWifiErrorPolicy.decide(GlassesWifiErrorContext.OFFICIAL_MEDIA_SYNC, 255, sessionActive = true),
        )
        assertEquals(
            GlassesWifiErrorAction.DEFER_TO_ADAPTIVE_RECOVERY,
            GlassesWifiErrorPolicy.decide(GlassesWifiErrorContext.ADAPTIVE_MEDIA_SYNC, 255, sessionActive = true),
        )
        assertEquals(
            GlassesWifiErrorAction.IGNORE_OUTSIDE_SESSION,
            GlassesWifiErrorPolicy.decide(GlassesWifiErrorContext.OFFICIAL_MEDIA_SYNC, 255, sessionActive = false),
        )
        assertEquals(
            GlassesWifiErrorAction.IGNORE_OUTSIDE_SESSION,
            GlassesWifiErrorPolicy.decide(GlassesWifiErrorContext.ADAPTIVE_MEDIA_SYNC, 255, sessionActive = false),
        )
        assertEquals(
            GlassesWifiErrorAction.IGNORE,
            GlassesWifiErrorPolicy.decide(GlassesWifiErrorContext.ADAPTIVE_MEDIA_SYNC, 3, sessionActive = true),
        )
        assertEquals(
            GlassesWifiErrorAction.IGNORE,
            GlassesWifiErrorPolicy.decide(GlassesWifiErrorContext.LIVE_PREVIEW, 255, sessionActive = true),
        )
    }

    @Test
    fun firmwareUpdateFailsOnEveryErrorExceptTheNoisyOne() {
        assertEquals(GlassesWifiErrorAction.IGNORE, GlassesWifiErrorPolicy.decide(GlassesWifiErrorContext.OTA, 255, true))
        assertEquals(GlassesWifiErrorAction.FAIL_OTA, GlassesWifiErrorPolicy.decide(GlassesWifiErrorContext.OTA, 0, true))
        assertEquals(GlassesWifiErrorAction.FAIL_OTA, GlassesWifiErrorPolicy.decide(GlassesWifiErrorContext.OTA, 7, false))
    }
}
