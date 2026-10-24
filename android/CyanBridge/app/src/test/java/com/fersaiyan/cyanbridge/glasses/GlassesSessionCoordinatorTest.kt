package com.fersaiyan.cyanbridge.glasses

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesSessionCoordinatorTest {
    @After
    fun releaseAnyHeldSession() {
        GlassesSessionCoordinator.clearForDisconnectedDevice()
    }

    @Test
    fun onlyOneExclusiveSessionCanOwnTheVendorSdk() {
        assertTrue(GlassesSessionCoordinator.tryAcquire(GlassesSession.LIVE_PREVIEW))
        assertFalse(GlassesSessionCoordinator.tryAcquire(GlassesSession.MEDIA_SYNC))
        assertFalse(GlassesSessionCoordinator.canRunBackgroundCommand())

        assertTrue(GlassesSessionCoordinator.release(GlassesSession.LIVE_PREVIEW))
        assertTrue(GlassesSessionCoordinator.tryAcquire(GlassesSession.MEDIA_SYNC))
    }

    @Test
    fun wifiAdbDebugExcludesEveryOtherWorkflowAndOneShotCommand() {
        assertTrue(GlassesSessionCoordinator.tryAcquire(GlassesSession.WIFI_ADB_DEBUG))

        assertFalse(GlassesSessionCoordinator.tryAcquire(GlassesSession.MEDIA_SYNC))
        assertFalse(GlassesSessionCoordinator.tryAcquire(GlassesSession.OTA))
        assertFalse(GlassesSessionCoordinator.tryAcquire(GlassesSession.LIVE_PREVIEW))
        assertNull(GlassesSessionCoordinator.tryAcquireBackgroundCommand())

        assertTrue(GlassesSessionCoordinator.release(GlassesSession.WIFI_ADB_DEBUG))
    }

    @Test
    fun onlyTheOwningSessionCanReleaseTheLease() {
        assertTrue(GlassesSessionCoordinator.tryAcquire(GlassesSession.OTA))
        assertFalse(GlassesSessionCoordinator.release(GlassesSession.LIVE_PREVIEW))
        assertTrue(GlassesSessionCoordinator.isOwnedBy(GlassesSession.OTA))

        assertTrue(GlassesSessionCoordinator.release(GlassesSession.OTA))
        assertNull(GlassesSessionCoordinator.currentSession())
    }

    @Test
    fun oneShotCommandBlocksExclusiveWorkflowUntilItsResponseWindowCloses() {
        val permit = GlassesSessionCoordinator.tryAcquireBackgroundCommand()
        assertNotNull(permit)
        val nonNullPermit = requireNotNull(permit)
        try {
            assertFalse(GlassesSessionCoordinator.tryAcquire(GlassesSession.LIVE_PREVIEW))
        } finally {
            GlassesSessionCoordinator.releaseBackgroundCommand(nonNullPermit)
        }
        assertTrue(GlassesSessionCoordinator.tryAcquire(GlassesSession.LIVE_PREVIEW))
    }

    @Test
    fun staleLeaseCannotReleaseSessionAcquiredAfterReconnect() {
        val staleLease = requireNotNull(GlassesSessionCoordinator.tryAcquireLease(GlassesSession.OTA))
        GlassesSessionCoordinator.clearForDisconnectedDevice()
        val currentLease = requireNotNull(GlassesSessionCoordinator.tryAcquireLease(GlassesSession.OTA))

        assertFalse(GlassesSessionCoordinator.release(staleLease))
        assertTrue(GlassesSessionCoordinator.isActive(currentLease))
    }

    @Test
    fun staleBackgroundPermitCannotReleaseCommandAcquiredAfterReconnect() {
        val stalePermit = requireNotNull(GlassesSessionCoordinator.tryAcquireBackgroundCommand())
        GlassesSessionCoordinator.clearForDisconnectedDevice()
        val currentPermit = requireNotNull(GlassesSessionCoordinator.tryAcquireBackgroundCommand())

        GlassesSessionCoordinator.releaseBackgroundCommand(stalePermit)

        assertTrue(GlassesSessionCoordinator.isBackgroundCommandActive(currentPermit))
    }
}
