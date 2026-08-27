package com.fersaiyan.cyanbridge.privacy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivacyPrefsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PrivacyPrefs.clear(context)
    }

    @After
    fun tearDown() {
        PrivacyPrefs.clear(context)
    }

    @Test
    fun transcriptStorageDefaultsToEnabled() {
        assertTrue(PrivacyPrefs.isTranscriptStorageEnabled(context))
    }

    @Test
    fun explicitOptOutRemainsDisabled() {
        PrivacyPrefs.setTranscriptStorageEnabled(context, false)

        assertFalse(PrivacyPrefs.isTranscriptStorageEnabled(context))
    }
}
