package com.fersaiyan.cyanbridge.plugins.settings

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.ui.nav.AppRoute
import com.fersaiyan.cyanbridge.ui.nav.NavScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/** Name of the catalog preferences file written by [com.fersaiyan.cyanbridge.ui.CommunityPluginPrefs]. */
const val COMMUNITY_PLUGIN_PREFS = "community_plugins"

fun Context.clearPreferenceFiles(vararg names: String) {
    names.forEach { name ->
        getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }
}

/**
 * Opens [route] in the single Activity (onCreate only, without attaching or composing its window)
 * and returns its screen, as building the screen's own Activity used to.
 */
internal inline fun <reified S : NavScreen> createSettingsScreen(route: AppRoute): S {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val activity = Robolectric.buildActivity(MainActivity::class.java, route.intent(context)).create().get()
    return activity.navigator.topScreen as S
}

fun NavScreen.nextStartedServiceIntent(): Intent? = shadowOf(activity).nextStartedService

fun NavScreen.nextStoppedServiceIntent(): Intent? = shadowOf(activity).nextStoppedService

fun assertServiceIntent(intent: Intent?, serviceClass: Class<*>, action: String?) {
    assertNotNull("expected a service intent for ${serviceClass.simpleName}", intent)
    assertEquals(serviceClass.name, intent!!.component?.className)
    assertEquals(action, intent.action)
}
