package com.fersaiyan.cyanbridge.localagent.dailyfacts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DailyBulletsSettingsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("daily_bullets_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    @Test
    fun defaultPromptIsAvailableWithoutLegacySettings() {
        assertEquals(DailyBulletsSettings.DEFAULT_BULLET_PROMPT, DailyBulletsSettings.getBulletPrompt(context))
    }

    @Test
    fun restoringDefaultPromptPersistsItForTheNativePlugin() {
        DailyBulletsSettings.setCustomBulletPrompt(context, "Custom prompt")
        DailyBulletsSettings.restoreDefaultBulletPrompt(context)

        assertEquals(DailyBulletsSettings.DEFAULT_BULLET_PROMPT, DailyBulletsSettings.getCustomBulletPrompt(context))
        assertEquals(DailyBulletsSettings.DEFAULT_BULLET_PROMPT, DailyBulletsSettings.getBulletPrompt(context))
    }
}
