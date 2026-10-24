package com.fersaiyan.cyanbridge.ai.live

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeminiLiveModePreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    @After
    fun clearPreferences() {
        context.getSharedPreferences("gemini_live", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `Pro Live defaults to Economy while preserving explicit Private choice`() {
        assertTrue(GeminiLiveModePreferences.isEconomy(context))
        GeminiLiveModePreferences.setEconomy(context, false)
        assertFalse(GeminiLiveModePreferences.isEconomy(context))
    }
}
