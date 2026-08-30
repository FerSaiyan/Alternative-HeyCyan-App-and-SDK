package com.fersaiyan.cyanbridge.localagent.daily

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NightlyEnrichmentPrefsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("nightly_enrichment", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `notification can only be claimed after ready and only once`() {
        val date = "2026-08-29"
        assertFalse(NightlyEnrichmentPrefs.claimNotification(context, date))

        NightlyEnrichmentPrefs.markReady(context, date, count = 3)
        assertTrue(NightlyEnrichmentPrefs.claimNotification(context, date))
        assertFalse(NightlyEnrichmentPrefs.claimNotification(context, date))
    }

    @Test
    fun `reviewed batch is no longer ready`() {
        val date = "2026-08-29"
        NightlyEnrichmentPrefs.markReady(context, date, count = 1)
        NightlyEnrichmentPrefs.markReviewed(context, date)

        assertFalse(NightlyEnrichmentPrefs.isReady(context, date))
    }
}
