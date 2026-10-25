package com.fersaiyan.cyanbridge.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
class ProSubscriptionSettingsActivityTest {

    @Test
    fun inactivePlanClosesTheScreenWithToast() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ProSubscriptionPrefs.clearEntitlement(context)

        val activity = Robolectric.buildActivity(ProSubscriptionSettingsActivity::class.java).create().get()

        assertTrue(activity.isFinishing)
        assertEquals("No active Pro plan found.", ShadowToast.getTextOfLatestToast())
    }
}
