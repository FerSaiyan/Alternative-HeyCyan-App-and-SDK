package com.fersaiyan.cyanbridge.ai.live

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiLiveVisionPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    @After
    fun clearPreferences() {
        context.getSharedPreferences("gemini_live", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `default keeps only the initial image`() {
        assertEquals(0, GeminiLiveVisionPreferences.imageDelaySeconds(context))
        assertNull(GeminiLiveVisionPreferences.automaticRefreshIntervalMs(context))
    }

    @Test
    fun `supported cadence is persisted in milliseconds`() {
        assertEquals(2, GeminiLiveVisionPreferences.setImageDelaySeconds(context, 2))
        assertEquals(2_000L, GeminiLiveVisionPreferences.automaticRefreshIntervalMs(context))
    }

    @Test
    fun `legacy cadence migrates to every turn`() {
        assertEquals(2, GeminiLiveVisionPreferences.setImageDelaySeconds(context, 10))
        assertEquals(2, GeminiLiveVisionPreferences.imageDelaySeconds(context))
        assertEquals(2_000L, GeminiLiveVisionPreferences.automaticRefreshIntervalMs(context))
        assertEquals(2, GeminiLiveVisionPreferences.setImageDelaySeconds(context, 5))
        assertEquals(2, GeminiLiveVisionPreferences.imageDelaySeconds(context))
    }

    @Test
    fun `unsupported cadence falls back to only first`() {
        assertEquals(0, GeminiLiveVisionPreferences.setImageDelaySeconds(context, 7))
        assertEquals(0, GeminiLiveVisionPreferences.imageDelaySeconds(context))
    }
}
