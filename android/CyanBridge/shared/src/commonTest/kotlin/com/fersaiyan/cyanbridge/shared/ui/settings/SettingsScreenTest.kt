package com.fersaiyan.cyanbridge.shared.ui.settings

import com.fersaiyan.cyanbridge.shared.settings.MemoryPrivacyMode
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsScreenTest {

    @Test
    fun defaultUiStateUsesPrivacyFirstDefaults() {
        val state = SettingsUiState()
        assertEquals(false, state.isProSubscribed)
        assertEquals(MemoryPrivacyMode.PRIVATE_LOCAL, state.memoryMode)
        assertEquals(true, state.redactNamesEnabled)
        assertEquals(true, state.transcriptStorageEnabled)
        assertEquals(false, state.visionAssistanceEnabled)
    }
}
