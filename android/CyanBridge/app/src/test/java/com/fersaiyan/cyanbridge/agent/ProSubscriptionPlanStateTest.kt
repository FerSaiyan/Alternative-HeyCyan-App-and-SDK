package com.fersaiyan.cyanbridge.agent

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
class ProSubscriptionPlanStateTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        listOf("pro_subscription_prefs", "pro_subscription_server_prefs").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun intent(plan: String? = null) =
        AppRoute.PRO_SUBSCRIPTION.intent(context).apply {
            plan?.let { putExtra(ProSubscriptionDestination.EXTRA_INITIAL_PLAN, it) }
        }

    private val ActivityController<MainActivity>.screen: ProSubscriptionDestination
        get() = get().navigator.topScreen as ProSubscriptionDestination

    @Test
    fun requestedPlanFromIntentIsSelected() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent(" MAX ")).create()

        assertEquals("max", controller.screen.state.selectedPlan)
    }

    @Test
    fun selectedPlanSurvivesRecreation() {
        // onResume (which publishes the restored plan) reads keystore-backed prefs that
        // Robolectric cannot provide, so check the restored selection via the saved state.
        val first = Robolectric.buildActivity(MainActivity::class.java, intent("cheap")).create()
        val saved = Bundle()
        first.saveInstanceState(saved)

        // The restored back stack keeps its own screens and intents, whatever intent starts the new Activity.
        val second = Robolectric.buildActivity(MainActivity::class.java, intent())
            .create(saved)
        assertEquals(AppRoute.PRO_SUBSCRIPTION, second.get().navigator.top?.route)

        second.start().restoreInstanceState(saved)
        val resaved = Bundle()
        second.saveInstanceState(resaved)

        assertEquals("cheap", resaved.topScreenState()?.getString("checked_plan"))
    }

    /** The Pro screen's own saved state inside MainActivity's saved back stack. */
    @Suppress("DEPRECATION")
    private fun Bundle.topScreenState(): Bundle? {
        val entries = getBundle("cyanbridge:nav")?.let { nav ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) nav.getParcelableArrayList("entries", Bundle::class.java)
            else nav.getParcelableArrayList("entries")
        }
        return entries?.lastOrNull()?.getBundle("state")
    }
}
