package com.fersaiyan.cyanbridge.shared.glasses

import com.fersaiyan.cyanbridge.shared.customization.CustomizationFamily
import com.fersaiyan.cyanbridge.shared.devices.firmware.FamilyOtaBlocker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FamilyOtaMachineTest {
    private fun run(start: FamilyOtaUiState, vararg events: FamilyOtaEvent) =
        events.fold(start) { state, event -> FamilyOtaMachine.reduce(state, event) }

    private val idle = FamilyOtaUiState(CustomizationFamily.EYEVUE, currentVersion = "1.3.5")

    @Test
    fun officialUpdateRunsThroughToSuccess() {
        val offer = FamilyOtaOffer("1.3.6", includesWifiPart = true)
        val checked = run(idle, FamilyOtaEvent.CheckStarted, FamilyOtaEvent.CheckFinished(offer))
        assertEquals(FamilyOtaStage.UPDATE_AVAILABLE, checked.stage)
        assertTrue(checked.canStart)
        val done = run(
            checked,
            FamilyOtaEvent.Preparing("read"),
            FamilyOtaEvent.Downloading(40),
            FamilyOtaEvent.Validating,
            FamilyOtaEvent.Installing(10, cancelable = false),
            FamilyOtaEvent.Reconnecting("restart"),
            FamilyOtaEvent.Installing(90, cancelable = false),
            FamilyOtaEvent.Succeeded("1.3.6", "ok"),
        )
        assertEquals(FamilyOtaStage.SUCCEEDED, done.stage)
        assertEquals("1.3.6", done.currentVersion)
        assertEquals(null, done.offer)
        assertEquals(FamilyOtaStage.IDLE, run(done, FamilyOtaEvent.Dismissed).stage)
    }

    @Test
    fun cancelRulesAndLateEvents() {
        val preparing = run(idle, FamilyOtaEvent.Preparing("read"))
        assertTrue(preparing.canCancel)
        assertFalse(preparing.canStart)
        val singleBank = run(preparing, FamilyOtaEvent.Installing(30, cancelable = false))
        assertFalse(singleBank.canCancel)
        assertTrue(run(preparing, FamilyOtaEvent.Installing(30, cancelable = true)).canCancel)
        assertFalse(run(singleBank, FamilyOtaEvent.Reconnecting("restart")).canCancel)

        // Nothing restarts or rewrites a running update; nothing moves a finished one.
        assertEquals(singleBank, run(singleBank, FamilyOtaEvent.CheckStarted, FamilyOtaEvent.Preparing("again"), FamilyOtaEvent.Dismissed))
        val cancelled = run(preparing, FamilyOtaEvent.Cancelled("stopped"))
        assertEquals(
            cancelled,
            run(cancelled, FamilyOtaEvent.Installing(50, true), FamilyOtaEvent.Succeeded("9", "late"), FamilyOtaEvent.CheckFinished(null)),
        )
    }

    @Test
    fun blockersDisableStartButNotTheRunningUpdate() {
        val blocked = run(idle, FamilyOtaEvent.Refreshed(batteryPercent = 20, blockers = setOf(FamilyOtaBlocker.BATTERY_LOW)))
        assertFalse(blocked.canStart)
        assertEquals("1.3.5", blocked.currentVersion)
        assertEquals(FamilyOtaStage.IDLE, blocked.stage)
        val upToDate = run(idle, FamilyOtaEvent.CheckStarted, FamilyOtaEvent.CheckFinished(null))
        assertEquals(FamilyOtaStage.UP_TO_DATE, upToDate.stage)
    }
}
