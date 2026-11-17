package com.fersaiyan.cyanbridge.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.shared.glasses.GlassesSyncFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Only the Adaptive flow writes `adaptive_p2p_sync_profiles`; Custom and HeyCyan never do. */
@RunWith(RobolectricTestRunner::class)
class AdaptiveProfileLearningTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val profiles = context.getSharedPreferences("adaptive_p2p_sync_profiles", Context.MODE_PRIVATE)

    private fun finishedSession() = AdaptiveP2pSyncSession(
        profileKey = "phone-and-glasses",
        profile = AdaptiveP2pProfile(),
        clockMs = { 0L },
    ).apply { noteHttpSuccess(AdaptiveHttpRoute.LOCAL_ADDRESS, 2_000L) }

    @Test
    fun `custom and heycyan syncs never write adaptive profiles`() {
        profiles.edit().clear().commit()
        listOf(GlassesSyncFlow.CUSTOM, GlassesSyncFlow.OFFICIAL_HEYCYAN).forEach { flow ->
            val session = finishedSession()
            assertNull(AdaptiveProfileLearning.recordSuccess(context, flow, session, AdaptiveHttpRoute.SYSTEM, 1_000L))
            assertNull(AdaptiveProfileLearning.recordFailure(context, flow, session, "timed out"))
        }
        assertTrue(profiles.all.toString(), profiles.all.isEmpty())

        assertNotNull(AdaptiveProfileLearning.recordSuccess(context, GlassesSyncFlow.ADAPTIVE, finishedSession(), null, null))
        assertEquals(AdaptiveHttpRoute.LOCAL_ADDRESS, AdaptiveP2pProfileStore.load(context, "phone-and-glasses").candidateRoute)
        profiles.edit().clear().commit()
    }

    @Test
    fun `an address found by the scan is learned and dropped once the reported one answers`() {
        profiles.edit().clear().commit()
        fun session(ip: String, source: AdaptiveIpSource) = AdaptiveP2pSyncSession("k", AdaptiveP2pProfile(), { 0L })
            .apply { noteHttpSuccess(AdaptiveHttpRoute.P2P_NETWORK, 1_000L, ip, source) }

        AdaptiveProfileLearning.recordSuccess(context, GlassesSyncFlow.ADAPTIVE, session("192.168.49.77", AdaptiveIpSource.SCAN), null, null)
        assertEquals("192.168.49.77", AdaptiveP2pProfileStore.load(context, "k").learnedHttpIp)
        AdaptiveProfileLearning.recordSuccess(context, GlassesSyncFlow.ADAPTIVE, session("192.168.49.77", AdaptiveIpSource.LEARNED), null, null)
        assertEquals("192.168.49.77", AdaptiveP2pProfileStore.load(context, "k").learnedHttpIp)
        AdaptiveProfileLearning.recordSuccess(context, GlassesSyncFlow.ADAPTIVE, session("192.168.49.79", AdaptiveIpSource.REPORTED), null, null)
        assertNull(AdaptiveP2pProfileStore.load(context, "k").learnedHttpIp)
        profiles.edit().clear().commit()
    }
}
