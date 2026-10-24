package com.fersaiyan.cyanbridge.shared.network.session

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class GlassesNetworkSessionCoreTest {
    private class FakeDriver(
        /** The group disappears when removeGroup is called for this time (null: never). */
        var clearsOnRemoveCall: Int? = 1,
        /** null: the platform never answers removeGroup. */
        var removeCallbackResult: Boolean? = true,
        var canUse: Boolean = true,
        var tracked: Boolean? = null,
    ) : P2pGroupDriver {
        var removeCalls = 0
        var groupFormed = true
        var finished = 0

        override fun removeGroup(onResult: (accepted: Boolean) -> Unit) {
            removeCalls++
            clearsOnRemoveCall?.let { if (removeCalls >= it) groupFormed = false }
            removeCallbackResult?.let(onResult)
        }

        override fun requestGroupState(onState: (formed: Boolean) -> Unit) = onState(groupFormed)
        override fun trackedLinkIdle(): Boolean? = tracked
        override fun canUseP2p(): Boolean = canUse
        override fun onTeardownFinished() {
            finished++
        }
    }

    private fun TestScope.core() = GlassesNetworkSessionCore(backgroundScope)

    private fun GlassesNetworkSessionCore.grant(client: GlassesLinkClient): GlassesLinkClaim {
        val result = tryClaim(client)
        assertIs<GlassesLinkClaimResult.Granted>(result)
        return result.claim
    }

    @Test
    fun confirmsOnTheFirstAttemptWhenTheGroupDisappears() = runTest {
        val core = core()
        val driver = FakeDriver()
        val events = mutableListOf<GlassesLinkTeardownEvent>()
        val claim = core.grant(GlassesLinkClient.HEYCYAN_LIVE_PREVIEW)

        assertNotNull(core.release(claim, GlassesLinkReleaseMode.CONFIRM_TEARDOWN, driver) { events += it })
        runCurrent()
        assertEquals(GlassesLinkPhase.TEARING_DOWN, core.snapshot.value.phase)
        advanceTimeBy(260)

        assertEquals(listOf<GlassesLinkTeardownEvent>(GlassesLinkTeardownEvent.Confirmed), events)
        assertEquals(GlassesLinkPhase.IDLE, core.snapshot.value.phase)
        assertEquals(1, driver.removeCalls)
        assertEquals(1, driver.finished)
    }

    @Test
    fun retriesThenQuarantinesThenRecoversOnThePeriodicRetry() = runTest {
        val core = core()
        val driver = FakeDriver(clearsOnRemoveCall = 4)
        val events = mutableListOf<GlassesLinkTeardownEvent>()
        val claim = core.grant(GlassesLinkClient.HEYCYAN_MEDIA_SYNC)
        core.release(claim, GlassesLinkReleaseMode.CONFIRM_TEARDOWN, driver) { events += it }
        runCurrent()

        // Three attempts: each polls for 5 s, with 1 s between attempts.
        advanceTimeBy(5_000 + 1_000 + 5_000 + 1_000 + 4_900)
        assertEquals(3, driver.removeCalls)
        assertTrue(events.isEmpty())
        advanceTimeBy(200)
        assertEquals(listOf<GlassesLinkTeardownEvent>(GlassesLinkTeardownEvent.Quarantined(5_000)), events)
        assertEquals(GlassesLinkPhase.QUARANTINED, core.snapshot.value.phase)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(4, driver.removeCalls)
        advanceTimeBy(260)
        assertEquals(GlassesLinkTeardownEvent.Confirmed, events.last())
        assertEquals(GlassesLinkPhase.IDLE, core.snapshot.value.phase)
        assertEquals(1, driver.finished)
    }

    @Test
    fun missingRemoveCallbackFallsBackToTheActionTimeout() = runTest {
        val core = core()
        val driver = FakeDriver(removeCallbackResult = null)
        val events = mutableListOf<GlassesLinkTeardownEvent>()
        val claim = core.grant(GlassesLinkClient.HEYCYAN_OTA)
        core.release(claim, GlassesLinkReleaseMode.CONFIRM_TEARDOWN, driver) { events += it }

        advanceTimeBy(4_900)
        assertTrue(events.isEmpty())
        advanceTimeBy(400)
        assertEquals(listOf<GlassesLinkTeardownEvent>(GlassesLinkTeardownEvent.Confirmed), events)
    }

    @Test
    fun trackedIdleStateConfirmsLikeTheLegacyHeyCyanCheck() = runTest {
        val core = core()
        val driver = FakeDriver(clearsOnRemoveCall = null, tracked = true)
        val events = mutableListOf<GlassesLinkTeardownEvent>()
        val claim = core.grant(GlassesLinkClient.HEYCYAN_MEDIA_SYNC)
        core.release(claim, GlassesLinkReleaseMode.CONFIRM_TEARDOWN, driver) { events += it }
        advanceTimeBy(260)
        assertEquals(listOf<GlassesLinkTeardownEvent>(GlassesLinkTeardownEvent.Confirmed), events)
    }

    @Test
    fun disconnectSignalConfirmsWithoutWaitingForThePoll() = runTest {
        val core = core()
        val driver = FakeDriver(clearsOnRemoveCall = null, removeCallbackResult = null)
        val events = mutableListOf<GlassesLinkTeardownEvent>()
        val claim = core.grant(GlassesLinkClient.HEYCYAN_LIVE_PREVIEW)
        core.release(claim, GlassesLinkReleaseMode.CONFIRM_TEARDOWN, driver) { events += it }
        runCurrent()

        core.signalGroupGone()
        runCurrent()
        assertEquals(listOf<GlassesLinkTeardownEvent>(GlassesLinkTeardownEvent.Confirmed), events)
        advanceTimeBy(30_000)
        assertEquals(1, driver.removeCalls)
    }

    @Test
    fun bluetoothReconnectRetriesAQuarantinedTeardownImmediately() = runTest {
        val core = core()
        val driver = FakeDriver(clearsOnRemoveCall = null)
        val events = mutableListOf<GlassesLinkTeardownEvent>()
        val claim = core.grant(GlassesLinkClient.HEYCYAN_OTA)
        core.release(claim, GlassesLinkReleaseMode.CONFIRM_TEARDOWN, driver) { events += it }
        advanceTimeBy(17_100)
        assertEquals(GlassesLinkPhase.QUARANTINED, core.snapshot.value.phase)

        core.onBluetoothConnected(DeviceClass.EYEVUE)
        runCurrent()
        assertEquals(3, driver.removeCalls)

        driver.clearsOnRemoveCall = 4
        core.onBluetoothConnected(DeviceClass.HEY_CYAN)
        runCurrent()
        assertEquals(4, driver.removeCalls)
        advanceTimeBy(260)
        assertEquals(GlassesLinkTeardownEvent.Confirmed, events.last())
    }

    @Test
    fun bluetoothLossAbandonsTheTeardownOfAnAbandoningClient() = runTest {
        val core = core()
        val driver = FakeDriver(clearsOnRemoveCall = null)
        val events = mutableListOf<GlassesLinkTeardownEvent>()
        val claim = core.grant(GlassesLinkClient.HEYCYAN_MEDIA_SYNC)
        core.release(claim, GlassesLinkReleaseMode.CONFIRM_TEARDOWN, driver) { events += it }
        advanceTimeBy(1_000)

        val outcome = core.onBluetoothDisconnected(DeviceClass.HEY_CYAN)
        assertIs<BleLossOutcome.Abandoned>(outcome)
        runCurrent()
        assertEquals(listOf<GlassesLinkTeardownEvent>(GlassesLinkTeardownEvent.Abandoned), events)
        assertEquals(1, driver.finished)
        assertTrue(core.snapshot.value.staleGroupSuspected)

        advanceTimeBy(60_000)
        assertEquals(1, driver.removeCalls)
    }

    @Test
    fun firmwareTeardownSurvivesBluetoothLoss() = runTest {
        val core = core()
        val driver = FakeDriver(clearsOnRemoveCall = 2)
        val events = mutableListOf<GlassesLinkTeardownEvent>()
        val claim = core.grant(GlassesLinkClient.HEYCYAN_OTA)
        core.release(claim, GlassesLinkReleaseMode.CONFIRM_TEARDOWN, driver) { events += it }
        runCurrent()

        assertEquals(
            BleLossOutcome.KeptForTeardown(GlassesLinkClient.HEYCYAN_OTA),
            core.onBluetoothDisconnected(DeviceClass.HEY_CYAN),
        )
        advanceTimeBy(6_300)
        assertEquals(listOf<GlassesLinkTeardownEvent>(GlassesLinkTeardownEvent.Confirmed), events)
        assertEquals(2, driver.removeCalls)
    }

    @Test
    fun aNewClaimWaitsForThePreviousOwnersTeardown() = runTest {
        val core = core()
        val driver = FakeDriver(clearsOnRemoveCall = 2)
        val media = core.grant(GlassesLinkClient.HEYCYAN_MEDIA_SYNC)
        core.release(media, GlassesLinkReleaseMode.CONFIRM_TEARDOWN, driver)

        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            core.awaitClaim(GlassesLinkClient.HEYCYAN_OTA, preflightDriver = { null })
        }
        advanceTimeBy(6_000)
        assertFalse(waiting.isCompleted)
        advanceTimeBy(400)
        val result = waiting.await()
        assertIs<GlassesLinkClaimResult.Granted>(result)
        assertTrue(core.arbiter.isReady(result.claim))
    }

    @Test
    fun aClaimStopsWaitingForAQuarantinedLinkAfterItsBudget() = runTest {
        val core = core()
        val driver = FakeDriver(clearsOnRemoveCall = null)
        val ota = core.grant(GlassesLinkClient.HEYCYAN_OTA)
        core.release(ota, GlassesLinkReleaseMode.CONFIRM_TEARDOWN, driver)

        val waiting = async { core.awaitClaim(GlassesLinkClient.EYEVUE_WIFI, preflightDriver = { null }) }
        advanceTimeBy(15_100)
        assertEquals(
            GlassesLinkClaimResult.AwaitingTeardown(GlassesLinkClient.HEYCYAN_OTA, quarantined = false),
            waiting.await(),
        )
    }

    @Test
    fun concurrentClaimsHaveExactlyOneWinner() = runTest {
        val core = core()
        val first = async { core.awaitClaim(GlassesLinkClient.HEYCYAN_LIVE_PREVIEW, preflightDriver = { null }) }
        val second = async { core.awaitClaim(GlassesLinkClient.EYEVUE_WIFI, preflightDriver = { null }) }
        val results = listOf(first.await(), second.await())

        assertEquals(1, results.count { it is GlassesLinkClaimResult.Granted })
        assertEquals(1, results.count { it is GlassesLinkClaimResult.Busy })
    }

    @Test
    fun preflightRemovesAStaleGroupBeforeHandingOverTheLink() = runTest {
        val core = core()
        val media = core.grant(GlassesLinkClient.HEYCYAN_MEDIA_SYNC)
        core.onBluetoothDisconnected(DeviceClass.HEY_CYAN)
        assertFalse(core.isActive(media))

        val driver = FakeDriver()
        val result = core.awaitClaim(GlassesLinkClient.HEYCYAN_LIVE_PREVIEW, preflightDriver = { driver })
        assertIs<GlassesLinkClaimResult.Granted>(result)
        assertNull(result.preflight)
        assertEquals(1, driver.removeCalls)
        assertTrue(core.arbiter.isReady(result.claim))
        assertFalse(core.snapshot.value.staleGroupSuspected)
    }

    @Test
    fun cancellingAClaimDuringPreflightKeepsTheTeardownGoing() = runTest {
        val core = core()
        core.grant(GlassesLinkClient.HEYCYAN_MEDIA_SYNC)
        core.onBluetoothDisconnected(DeviceClass.HEY_CYAN)

        val driver = FakeDriver(clearsOnRemoveCall = null)
        val waiting = async { core.awaitClaim(GlassesLinkClient.HEYCYAN_OTA, preflightDriver = { driver }) }
        advanceTimeBy(1_000)
        waiting.cancel()
        runCurrent()

        assertEquals(GlassesLinkPhase.TEARING_DOWN, core.snapshot.value.phase)
        assertIs<GlassesLinkClaimResult.AwaitingTeardown>(core.tryClaim(GlassesLinkClient.EYEVUE_WIFI))
        advanceTimeBy(17_000)
        // A released link quarantines rather than giving up like a preflight would.
        assertEquals(GlassesLinkPhase.QUARANTINED, core.snapshot.value.phase)
    }

    @Test
    fun releaseAndAwaitReportsConfirmationOrQuarantine() = runTest {
        val core = core()
        val confirmedDriver = FakeDriver()
        val first = core.grant(GlassesLinkClient.TUNEBUDS_WIFI_DIRECT)
        val confirmed = async { core.releaseAndAwait(first, confirmedDriver) }
        advanceTimeBy(300)
        assertTrue(confirmed.await())

        val stuckDriver = FakeDriver(clearsOnRemoveCall = null)
        val second = core.grant(GlassesLinkClient.TUNEBUDS_WIFI_DIRECT)
        val quarantined = async { core.releaseAndAwait(second, stuckDriver) }
        advanceTimeBy(17_100)
        assertFalse(quarantined.await())
        assertEquals(GlassesLinkPhase.QUARANTINED, core.snapshot.value.phase)
        assertEquals(0, stuckDriver.finished)
    }

    @Test
    fun releaseWithoutADriverNeedsNoTeardown() = runTest {
        val core = core()
        val events = mutableListOf<GlassesLinkTeardownEvent>()
        val eyevue = core.grant(GlassesLinkClient.EYEVUE_WIFI)
        assertNotNull(core.release(eyevue, GlassesLinkReleaseMode.CONFIRM_TEARDOWN, driver = null) { events += it })
        assertEquals(listOf<GlassesLinkTeardownEvent>(GlassesLinkTeardownEvent.Confirmed), events)
        assertEquals(GlassesLinkPhase.IDLE, core.snapshot.value.phase)
        assertNull(core.release(eyevue, GlassesLinkReleaseMode.ABANDON))
    }
}
