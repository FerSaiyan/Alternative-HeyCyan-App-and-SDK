package com.fersaiyan.cyanbridge.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
class ProSubscriptionSettingsDestinationTest {

    @Test
    fun inactivePlanClosesTheScreenWithToast() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ProSubscriptionPrefs.clearEntitlement(context)

        val activity = Robolectric.buildActivity(MainActivity::class.java, AppRoute.PRO_SUBSCRIPTION_SETTINGS.intent(context))
            .create().get()

        assertTrue(activity.navigator.entries.none { it.route == AppRoute.PRO_SUBSCRIPTION_SETTINGS })
        assertEquals("No active Pro plan found.", ShadowToast.getTextOfLatestToast())
    }
}
