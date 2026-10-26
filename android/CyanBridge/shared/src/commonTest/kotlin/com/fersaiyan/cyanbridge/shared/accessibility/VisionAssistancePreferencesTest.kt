package com.fersaiyan.cyanbridge.shared.accessibility

import com.fersaiyan.cyanbridge.shared.platform.createPlatformPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisionAssistancePreferencesTest {

    private fun freshPreferences(name: String) = createPlatformPreferences("vision_assistance_test_$name").also { it.clear() }

    @Test
    fun startsUnsetAndDisabled() {
        val prefs = freshPreferences("unset")
        assertEquals(VisionAssistanceChoice.UNSET, VisionAssistancePreferences.choice(prefs))
        assertFalse(VisionAssistancePreferences.isEnabled(prefs))
    }

    @Test
    fun offersOnlyToScreenReaderUsersWhoHaveNotAnswered() {
        val prefs = freshPreferences("offer")
        assertFalse(VisionAssistancePreferences.shouldOffer(prefs, screenReaderRunning = false))
        assertTrue(VisionAssistancePreferences.shouldOffer(prefs, screenReaderRunning = true))

        VisionAssistancePreferences.setEnabled(prefs, false)
        assertEquals(VisionAssistanceChoice.DISABLED, VisionAssistancePreferences.choice(prefs))
        assertFalse(VisionAssistancePreferences.shouldOffer(prefs, screenReaderRunning = true))
    }

    @Test
    fun settingsToggleRoundTrips() {
        val prefs = freshPreferences("toggle")
        VisionAssistancePreferences.setEnabled(prefs, true)
        assertTrue(VisionAssistancePreferences.isEnabled(prefs))
        assertFalse(VisionAssistancePreferences.shouldOffer(prefs, screenReaderRunning = true))
        VisionAssistancePreferences.setEnabled(prefs, false)
        assertFalse(VisionAssistancePreferences.isEnabled(prefs))
    }

    @Test
    fun unknownStoredValueFallsBackToUnset() {
        assertEquals(VisionAssistanceChoice.UNSET, VisionAssistanceChoice.fromRaw("garbage"))
        assertEquals(VisionAssistanceChoice.ENABLED, VisionAssistanceChoice.fromRaw(" enabled "))
    }
}
