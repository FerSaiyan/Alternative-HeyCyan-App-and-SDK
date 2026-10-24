package com.fersaiyan.cyanbridge.devices.eyevue

import org.junit.Assert.assertEquals
import org.junit.Test

class EyevueMediaProfileTest {
    @Test
    fun liveProfilesMatchVendorModelFamilies() {
        val sk = EyevueMediaProfile.fromProject("SK01")
        assertEquals(EyevueWifiMode.AP, sk.mode)
        assertEquals("http://192.168.1.254/?custom=1&cmd=3001&par=1", sk.liveControlUrl)
        assertEquals("rtsp://192.168.1.254/xxx.mov", sk.liveStreamUrl)

        val tSeries = EyevueMediaProfile.fromProject("T01")
        assertEquals(EyevueWifiMode.AP, tSeries.mode)
        assertEquals(null, tSeries.liveControlUrl)
        assertEquals("rtsp://192.168.169.1/h264", tSeries.liveStreamUrl)

        val other = EyevueMediaProfile.fromProject("EV01")
        assertEquals(EyevueWifiMode.P2P, other.mode)
        assertEquals("rtsp://192.168.49.207/xxx.mov", other.liveStreamUrl)
    }

    @Test
    fun releaseLivePreviewSupportsAndroidTenAndNewer() {
        assertEquals(false, EyevueLivePreviewPolicy.isSupported(28))
        assertEquals(true, EyevueLivePreviewPolicy.isSupported(29))
        assertEquals(true, EyevueLivePreviewPolicy.isSupported(36))
    }
}
