package com.fersaiyan.cyanbridge.ui.appearance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.shared.appearance.AccentProfiles
import com.fersaiyan.cyanbridge.shared.appearance.AppearanceSettings
import com.fersaiyan.cyanbridge.shared.appearance.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppearancePreferencesTest {
    private lateinit var preferences: AppearancePreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferences = AppearancePreferences(context)
        preferences.reset()
    }

    @Test
    fun defaultsAreStableAndAccessible() {
        assertEquals(AppearanceSettings(), preferences.load())
    }

    @Test
    fun savesAllAppearanceChoices() {
        val expected = AppearanceSettings(
            themeMode = ThemeMode.DARK,
            accentProfileId = "lavender",
            useDynamicColor = true,
            highContrast = true,
        )

        preferences.save(expected)

        assertEquals(expected, preferences.load())
    }
}
