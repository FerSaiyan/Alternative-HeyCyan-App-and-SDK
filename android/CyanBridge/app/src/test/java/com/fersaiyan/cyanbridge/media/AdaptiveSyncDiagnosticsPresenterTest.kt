package com.fersaiyan.cyanbridge.media

import com.fersaiyan.cyanbridge.shared.glasses.AdaptiveSyncStageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveSyncDiagnosticsPresenterTest {
    @Test fun `successful checkpoints remain complete when http variants fail`() {
        var now = 0L
        val session = AdaptiveP2pSyncSession("test", AdaptiveP2pProfile()) { now }
        session.mark(AdaptiveSyncCheckpoint.BLE_READY)
        session.mark(AdaptiveSyncCheckpoint.TRANSFER_EVIDENCE)
        session.mark(AdaptiveSyncCheckpoint.P2P_GROUP_FORMED)
        session.mark(AdaptiveSyncCheckpoint.BLE_IP_RECEIVED, "ip=192.168.49.2")
        now = 2_000L
        session.mark(AdaptiveSyncCheckpoint.HTTP_ROUTE_TRIAL, "route=P2P_NETWORK,warmup_ms=2000")
        session.mark(AdaptiveSyncCheckpoint.HTTP_ROUTE_FAILED, "route=P2P_NETWORK,warmup_ms=2000,reachable=false")
        val state = AdaptiveSyncDiagnosticsPresenter.present(session)
        assertEquals(4, state.stages.count { it.status == AdaptiveSyncStageStatus.COMPLETE })
        assertEquals(AdaptiveSyncStageStatus.ACTIVE, state.stages[4].status)
        assertTrue(state.explanation.contains("next allowed route"))
        assertFalse(state.trials.joinToString().contains("192.168.49.2"))
    }

    @Test fun `a responding http endpoint is held for the next warmup`() {
        val session = AdaptiveP2pSyncSession("test", AdaptiveP2pProfile()) { 0L }
        session.mark(AdaptiveSyncCheckpoint.HTTP_ROUTE_TRIAL, "route=LOCAL_ADDRESS,warmup_ms=1000")
        session.mark(AdaptiveSyncCheckpoint.HTTP_ROUTE_FAILED, "route=LOCAL_ADDRESS,warmup_ms=1000,reachable=true")
        session.mark(AdaptiveSyncCheckpoint.HTTP_WARMUP_WAIT, "warmup_ms=4000,sticky=true")
        val state = AdaptiveSyncDiagnosticsPresenter.present(session)
        assertTrue(state.explanation.contains("Keeping that route"))
        assertTrue(state.trials.any { it.title.contains("P2P-local socket") })
    }

    @Test fun `learning description represents candidate and confirmed route without making claims of success`() {
        val candidate = AdaptiveP2pProfile().withSuccessfulSync(AdaptiveHttpRoute.SYSTEM, 2000L)
        val candidateState = AdaptiveSyncDiagnosticsPresenter.present(
            AdaptiveP2pSyncSession("test", candidate) { 0L },
        )
        assertTrue(candidateState.learnedProfile.contains("1/2"))
        val promoted = candidate.withSuccessfulSync(AdaptiveHttpRoute.SYSTEM, 2000L)
        val confirmedState = AdaptiveSyncDiagnosticsPresenter.present(
            AdaptiveP2pSyncSession("test", promoted) { 0L },
        )
        assertTrue(confirmedState.learnedProfile.contains("Learned route"))
        assertTrue(confirmedState.learnedProfile.contains("2 sync(s)"))
    }

    @Test fun `complete and failed sessions are terminal`() {
        val completed = AdaptiveP2pSyncSession("ok", AdaptiveP2pProfile()) { 0L }
        completed.mark(AdaptiveSyncCheckpoint.MEDIA_CONFIG_COMPLETE)
        completed.mark(AdaptiveSyncCheckpoint.COMPLETE)
        assertTrue(AdaptiveSyncDiagnosticsPresenter.present(completed).isTerminal)

        val failed = AdaptiveP2pSyncSession("error", AdaptiveP2pProfile()) { 0L }
        failed.mark(AdaptiveSyncCheckpoint.BLE_READY)
        failed.mark(AdaptiveSyncCheckpoint.FAILED)
        val snapshot = AdaptiveSyncDiagnosticsPresenter.present(failed)
        assertTrue(snapshot.isTerminal)
        assertEquals(AdaptiveSyncStageStatus.COMPLETE, snapshot.stages[0].status)
        assertEquals(AdaptiveSyncStageStatus.FAILED, snapshot.stages[1].status)
    }

    @Test fun `checkpoint updates notify UI with an immutable snapshot`() {
        var updates = 0
        val session = AdaptiveP2pSyncSession(
            "test", AdaptiveP2pProfile(), clockMs = { 0L }, onUpdate = { updates++ },
        )
        session.mark(AdaptiveSyncCheckpoint.BLE_READY)
        val frozen = session.snapshotEvents()
        session.mark(AdaptiveSyncCheckpoint.TRANSFER_COMMAND_SENT, "attempt=1")
        assertEquals(2, updates)
        assertEquals(1, frozen.size)
        assertEquals(2, session.snapshotEvents().size)
    }
}
