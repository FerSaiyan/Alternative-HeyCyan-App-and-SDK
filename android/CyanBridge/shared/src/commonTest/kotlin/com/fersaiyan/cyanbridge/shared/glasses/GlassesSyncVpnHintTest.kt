package com.fersaiyan.cyanbridge.shared.glasses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlassesSyncVpnHintTest {
    @Test
    fun noHintWithoutAVpnCoveringTheApp() {
        assertNull(GlassesSyncVpnHint.message(SyncVpnState(coversApp = false), glassesReached = true))
    }

    @Test
    fun noHintBeforeTheWifiDirectLinkCameUp() {
        assertNull(GlassesSyncVpnHint.message(SyncVpnState(coversApp = true), glassesReached = false))
    }

    @Test
    fun vpnIsNamedAsTheLikelyCauseWithTheFix() {
        val message = GlassesSyncVpnHint.message(SyncVpnState(coversApp = true), glassesReached = true)
        assertEquals(true, message?.startsWith("Your VPN is most likely blocking the connection to the glasses."))
        assertTrue(message!!.contains("Pause the VPN while syncing"))
        assertTrue(message.contains("exclude CyanBridge"))
    }
}
