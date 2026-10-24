package com.fersaiyan.cyanbridge.agent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProSubscriptionActivityPlanStateTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        listOf("pro_subscription_prefs", "pro_subscription_server_prefs").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun intent(plan: String? = null) =
        Intent(context, ProSubscriptionActivity::class.java).apply {
            plan?.let { putExtra(ProSubscriptionActivity.EXTRA_INITIAL_PLAN, it) }
        }

    @Test
    fun requestedPlanFromIntentIsSelected() {
        val activity = Robolectric.buildActivity(ProSubscriptionActivity::class.java, intent(" MAX ")).create().get()

        assertEquals("max", activity.state.selectedPlan)
    }

    @Test
    fun selectedPlanSurvivesRecreation() {
        // onResume (which publishes the restored plan) reads keystore-backed prefs that
        // Robolectric cannot provide, so check the restored selection via the saved state.
        val first = Robolectric.buildActivity(ProSubscriptionActivity::class.java, intent("cheap")).create()
        val saved = Bundle()
        first.saveInstanceState(saved)

        val second = Robolectric.buildActivity(ProSubscriptionActivity::class.java, intent())
            .create(saved)
        assertEquals("free_trial", second.get().state.selectedPlan)

        second.start().restoreInstanceState(saved)
        val resaved = Bundle()
        second.saveInstanceState(resaved)

        assertEquals("cheap", resaved.getString("checked_plan"))
    }
}
