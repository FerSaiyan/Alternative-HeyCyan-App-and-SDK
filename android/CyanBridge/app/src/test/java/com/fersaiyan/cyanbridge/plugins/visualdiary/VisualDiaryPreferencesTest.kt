package com.fersaiyan.cyanbridge.plugins.visualdiary

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VisualDiaryPreferencesTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("visual_diary_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    @Test
    fun defaultsAreSafe() {
        assertFalse(VisualDiaryPreferences.isEnabled(context))
        assertEquals(15, VisualDiaryPreferences.getIntervalMinutes(context))
        assertEquals("", VisualDiaryPreferences.getCustomPrompt(context))
        assertEquals("", VisualDiaryPreferences.getLastError(context))
    }

    @Test
    fun settingsAreClampedAndPersisted() {
        VisualDiaryPreferences.setEnabled(context, true)
        VisualDiaryPreferences.setIntervalMinutes(context, 999)
        VisualDiaryPreferences.setCustomPrompt(context, "Describe only concrete objects.")
        VisualDiaryPreferences.setLastError(context, "Camera unavailable")

        assertTrue(VisualDiaryPreferences.isEnabled(context))
        assertEquals(240, VisualDiaryPreferences.getIntervalMinutes(context))
        assertEquals("Describe only concrete objects.", VisualDiaryPreferences.getCustomPrompt(context))
        assertEquals("Camera unavailable", VisualDiaryPreferences.getLastError(context))
    }

    @Test
    fun customPromptIsBounded() {
        VisualDiaryPreferences.setCustomPrompt(context, "x".repeat(2_000))

        assertEquals(1_500, VisualDiaryPreferences.getCustomPrompt(context).length)
    }
}
