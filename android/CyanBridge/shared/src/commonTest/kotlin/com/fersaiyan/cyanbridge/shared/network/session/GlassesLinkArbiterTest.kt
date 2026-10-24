package com.fersaiyan.cyanbridge.shared.network.session

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlassesLinkArbiterTest {
    private val arbiter = GlassesLinkArbiter()

    private fun grant(client: GlassesLinkClient): GlassesLinkClaim {
        val result = arbiter.requestClaim(client)
        assertIs<GlassesLinkClaimResult.Granted>(result)
        assertNull(result.preflight)
        return result.claim
    }

    @Test
    fun onlyOneClaimOwnsTheLinkAndThereIsNoPreemption() {
        val media = grant(GlassesLinkClient.HEYCYAN_MEDIA_SYNC)

        assertEquals(
            GlassesLinkClaimResult.Busy(GlassesLinkClient.HEYCYAN_MEDIA_SYNC),
            arbiter.requestClaim(GlassesLinkClient.HEYCYAN_OTA),
        )
        assertEquals(
            GlassesLinkClaimResult.Busy(GlassesLinkClient.HEYCYAN_MEDIA_SYNC),
            arbiter.requestClaim(GlassesLinkClient.EYEVUE_WIFI),
        )
        // The holder itself cannot stack a second claim either.
        assertIs<GlassesLinkClaimResult.Busy>(arbiter.requestClaim(GlassesLinkClient.HEYCYAN_MEDIA_SYNC))
        assertTrue(arbiter.isActive(media))
    }

    @Test
    fun releasedLinkStaysReservedUntilTeardownIsConfirmed() {
        val preview = grant(GlassesLinkClient.HEYCYAN_LIVE_PREVIEW)
        assertTrue(arbiter.onConnectRequested(preview))
        assertTrue(arbiter.onGroupFormed(preview))
        assertTrue(arbiter.onRouteBound(preview, bound = true))

        val release = assertNotNull(arbiter.release(preview, GlassesLinkReleaseMode.CONFIRM_TEARDOWN))
        val ticket = assertNotNull(release.teardown)
        assertTrue(release.routeWasBound)
        assertFalse(arbiter.isActive(preview))
        assertEquals(GlassesLinkPhase.TEARING_DOWN, arbiter.snapshot.value.phase)
        assertEquals(
            GlassesLinkClaimResult.AwaitingTeardown(GlassesLinkClient.HEYCYAN_LIVE_PREVIEW, quarantined = false),
            arbiter.requestClaim(GlassesLinkClient.HEYCYAN_OTA),
        )

        assertTrue(arbiter.onTeardownConfirmed(ticket))
        assertEquals(GlassesLinkPhase.IDLE, arbiter.snapshot.value.phase)
        grant(GlassesLinkClient.HEYCYAN_OTA)
    }

    @Test
    fun staleClaimsAndTicketsAreInert() {
        val first = grant(GlassesLinkClient.EYEVUE_WIFI)
        assertNotNull(arbiter.release(first, GlassesLinkReleaseMode.NO_GROUP))
        val second = grant(GlassesLinkClient.EYEVUE_WIFI)

        assertNull(arbiter.release(first, GlassesLinkReleaseMode.CONFIRM_TEARDOWN))
        assertFalse(arbiter.onGroupFormed(first))
        assertTrue(arbiter.isActive(second))

        val ticket = assertNotNull(arbiter.release(second, GlassesLinkReleaseMode.CONFIRM_TEARDOWN)?.teardown)
        assertTrue(arbiter.onTeardownConfirmed(ticket))
        assertFalse(arbiter.onTeardownConfirmed(ticket))
        assertNull(arbiter.onTeardownAttemptFailed(ticket, attempt = 1, canUseP2p = true))
    }

    @Test
    fun retryExhaustionQuarantinesAndQuarantineRetryRestartsTheTeardown() {
        val ota = grant(GlassesLinkClient.HEYCYAN_OTA)
        val ticket = assertNotNull(arbiter.release(ota, GlassesLinkReleaseMode.CONFIRM_TEARDOWN)?.teardown)

        assertEquals(TeardownStep.Retry(2, 1_000L), arbiter.onTeardownAttemptFailed(ticket, 1, canUseP2p = true))
        assertEquals(TeardownStep.Retry(3, 1_000L), arbiter.onTeardownAttemptFailed(ticket, 2, canUseP2p = true))
        assertEquals(TeardownStep.Quarantine(5_000L), arbiter.onTeardownAttemptFailed(ticket, 3, canUseP2p = true))
        assertEquals(GlassesLinkPhase.QUARANTINED, arbiter.snapshot.value.phase)
        assertEquals(
            GlassesLinkClaimResult.AwaitingTeardown(GlassesLinkClient.HEYCYAN_OTA, quarantined = true),
            arbiter.requestClaim(GlassesLinkClient.HEYCYAN_MEDIA_SYNC),
        )

        assertTrue(arbiter.retryQuarantined(ticket))
        assertFalse(arbiter.retryQuarantined(ticket))
        assertEquals(GlassesLinkPhase.TEARING_DOWN, arbiter.snapshot.value.phase)
        assertTrue(arbiter.onTeardownConfirmed(ticket))
        assertEquals(GlassesLinkPhase.IDLE, arbiter.snapshot.value.phase)
        assertFalse(arbiter.snapshot.value.staleGroupSuspected)
    }

    @Test
    fun missingPermissionQuarantinesWithoutBurningRetries() {
        val media = grant(GlassesLinkClient.HEYCYAN_MEDIA_SYNC)
        val ticket = assertNotNull(arbiter.release(media, GlassesLinkReleaseMode.CONFIRM_TEARDOWN)?.teardown)
        assertEquals(TeardownStep.Quarantine(5_000L), arbiter.onTeardownAttemptFailed(ticket, 1, canUseP2p = false))
    }

    @Test
    fun bleLossAbandonsHeyCyanLinksButKeepsTheFirmwareTeardown() {
        val media = grant(GlassesLinkClient.HEYCYAN_MEDIA_SYNC)
        assertTrue(arbiter.onRouteBound(media, bound = true))

        assertEquals(BleLossOutcome.NotAffected, arbiter.onBleLost(DeviceClass.EYEVUE))
        assertTrue(arbiter.isActive(media))

        val outcome = arbiter.onBleLost(DeviceClass.HEY_CYAN)
        assertEquals(BleLossOutcome.Abandoned(GlassesLinkClient.HEYCYAN_MEDIA_SYNC, routeWasBound = true, teardown = null), outcome)
        assertFalse(arbiter.isActive(media))
        assertTrue(arbiter.snapshot.value.staleGroupSuspected)

        val otaResult = arbiter.requestClaim(GlassesLinkClient.HEYCYAN_OTA)
        assertIs<GlassesLinkClaimResult.Granted>(otaResult)
        assertNotNull(otaResult.preflight)
        val preflight = otaResult.preflight!!
        assertTrue(arbiter.onTeardownConfirmed(preflight))
        assertTrue(arbiter.isReady(otaResult.claim))

        assertEquals(
            BleLossOutcome.KeptForTeardown(GlassesLinkClient.HEYCYAN_OTA),
            arbiter.onBleLost(DeviceClass.HEY_CYAN),
        )
        assertTrue(arbiter.isActive(otaResult.claim))
    }

    @Test
    fun bleLossDropsARunningTeardownOfAnAbandoningClient() {
        val preview = grant(GlassesLinkClient.HEYCYAN_LIVE_PREVIEW)
        val ticket = assertNotNull(arbiter.release(preview, GlassesLinkReleaseMode.CONFIRM_TEARDOWN)?.teardown)

        val outcome = arbiter.onBleLost(DeviceClass.HEY_CYAN)
        assertIs<BleLossOutcome.Abandoned>(outcome)
        assertEquals(ticket, outcome.teardown)
        assertFalse(arbiter.isCurrent(ticket))
        assertEquals(GlassesLinkPhase.IDLE, arbiter.snapshot.value.phase)
    }

    @Test
    fun vendorLinksIgnoreHeyCyanBluetoothLoss() {
        val tuneBuds = grant(GlassesLinkClient.TUNEBUDS_WIFI_DIRECT)
        assertEquals(BleLossOutcome.NotAffected, arbiter.onBleLost(DeviceClass.HEY_CYAN))
        assertTrue(arbiter.isActive(tuneBuds))
    }

    @Test
    fun preflightIsBestEffortAndNeverQuarantines() {
        val media = grant(GlassesLinkClient.HEYCYAN_MEDIA_SYNC)
        assertNotNull(arbiter.release(media, GlassesLinkReleaseMode.ABANDON))

        val result = arbiter.requestClaim(GlassesLinkClient.HEYCYAN_LIVE_PREVIEW)
        assertIs<GlassesLinkClaimResult.Granted>(result)
        val preflight = assertNotNull(result.preflight)
        assertFalse(arbiter.isReady(result.claim))
        assertTrue(arbiter.isActive(result.claim))
        assertEquals(TeardownKind.PREFLIGHT, arbiter.teardownKind(preflight))

        assertEquals(TeardownStep.Retry(2, 1_000L), arbiter.onTeardownAttemptFailed(preflight, 1, canUseP2p = true))
        assertEquals(TeardownStep.GiveUp, arbiter.onTeardownAttemptFailed(preflight, 3, canUseP2p = true))
        assertTrue(arbiter.isReady(result.claim))
        assertFalse(arbiter.snapshot.value.staleGroupSuspected)
    }

    @Test
    fun releasingDuringPreflightTurnsItIntoAReleaseTeardown() {
        val media = grant(GlassesLinkClient.HEYCYAN_MEDIA_SYNC)
        assertNotNull(arbiter.release(media, GlassesLinkReleaseMode.ABANDON))
        val result = arbiter.requestClaim(GlassesLinkClient.HEYCYAN_OTA) as GlassesLinkClaimResult.Granted
        val preflight = assertNotNull(result.preflight)

        val release = assertNotNull(arbiter.release(result.claim, GlassesLinkReleaseMode.CONFIRM_TEARDOWN))
        assertEquals(preflight, release.teardown)
        assertEquals(TeardownKind.RELEASE, arbiter.teardownKind(preflight))
        assertEquals(TeardownStep.Quarantine(5_000L), arbiter.onTeardownAttemptFailed(preflight, 3, canUseP2p = true))
    }

    @Test
    fun groupLossAndRouteBindingTrackTheReadyClaimOnly() {
        val eyevue = grant(GlassesLinkClient.EYEVUE_WIFI)
        assertTrue(arbiter.onGroupFormed(eyevue))
        assertEquals(GlassesLinkPhase.CONNECTED, arbiter.snapshot.value.phase)
        assertTrue(arbiter.onGroupLost(eyevue))
        assertEquals(GlassesLinkPhase.DISCOVERING, arbiter.snapshot.value.phase)

        val release = assertNotNull(arbiter.release(eyevue, GlassesLinkReleaseMode.NO_GROUP))
        assertFalse(release.routeWasBound)
        assertFalse(arbiter.onRouteBound(eyevue, bound = true))
        assertFalse(arbiter.snapshot.value.staleGroupSuspected)
    }
}
