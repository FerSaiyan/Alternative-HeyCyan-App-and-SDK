package com.fersaiyan.cyanbridge.ota

import com.fersaiyan.cyanbridge.shared.glasses.OtaSectionUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaSectionUiStateTest {

    @Test
    fun idleWithoutSessionCanStartButNotCancel() {
        assertEquals(
            OtaSectionUiState(
                stateLabel = "Idle",
                detail = "",
                progress = null,
                canStart = true,
                canCancel = false,
            ),
            otaSectionUiState(OtaUiState(), preparationActive = false, sessionActive = false),
        )
    }

    @Test
    fun stateLabelIsSentenceCasedFromTheEnumName() {
        val section = otaSectionUiState(
            OtaUiState(state = OtaState.BLE_DFU_TRANSFERRING, progress = 42),
            preparationActive = false,
            sessionActive = true,
        )
        assertEquals("Ble dfu transferring", section.stateLabel)
        assertEquals(42, section.progress)
    }

    @Test
    fun detailFallsBackToErrorOnlyWhenBlank() {
        assertEquals(
            "boom",
            otaSectionUiState(
                OtaUiState(state = OtaState.FAILED, detail = " ", error = "boom"),
                preparationActive = false,
                sessionActive = false,
            ).detail,
        )
        assertEquals(
            "detail",
            otaSectionUiState(
                OtaUiState(state = OtaState.FAILED, detail = "detail", error = "boom"),
                preparationActive = false,
                sessionActive = false,
            ).detail,
        )
        assertEquals(
            "",
            otaSectionUiState(
                OtaUiState(state = OtaState.FAILED, detail = "", error = null),
                preparationActive = false,
                sessionActive = false,
            ).detail,
        )
    }

    @Test
    fun startRequiresTerminalStateAndNoPreparationOrSession() {
        for (state in listOf(OtaState.IDLE, OtaState.COMPLETE, OtaState.FAILED)) {
            assertTrue(otaSectionUiState(OtaUiState(state = state), false, false).canStart)
            assertFalse(otaSectionUiState(OtaUiState(state = state), true, false).canStart)
            assertFalse(otaSectionUiState(OtaUiState(state = state), false, true).canStart)
        }
        for (state in OtaState.values().filterNot {
            it == OtaState.IDLE || it == OtaState.COMPLETE || it == OtaState.FAILED
        }) {
            assertFalse(otaSectionUiState(OtaUiState(state = state), false, false).canStart)
        }
    }

    @Test
    fun cancelRequiresActivityAndIsHiddenWhileCompleteOrCancelling() {
        for (state in OtaState.values()) {
            assertFalse(otaSectionUiState(OtaUiState(state = state), false, false).canCancel)
            val expected = state != OtaState.COMPLETE && state != OtaState.CANCELLING
            assertEquals(expected, otaSectionUiState(OtaUiState(state = state), true, false).canCancel)
            assertEquals(expected, otaSectionUiState(OtaUiState(state = state), false, true).canCancel)
        }
    }
}
