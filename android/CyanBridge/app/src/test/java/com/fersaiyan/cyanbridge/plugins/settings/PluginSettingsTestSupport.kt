package com.fersaiyan.cyanbridge.plugins.settings

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
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

/** Creates the Activity (onCreate only), without attaching or composing its window. */
inline fun <reified A : AppCompatActivity> createSettingsActivity(): A =
    Robolectric.buildActivity(A::class.java).create().get()

fun AppCompatActivity.nextStartedServiceIntent(): Intent? = shadowOf(this).nextStartedService

fun AppCompatActivity.nextStoppedServiceIntent(): Intent? = shadowOf(this).nextStoppedService

fun AppCompatActivity.nextStartedActivityIntent(): Intent? = shadowOf(this).nextStartedActivity

fun assertServiceIntent(intent: Intent?, serviceClass: Class<*>, action: String?) {
    assertNotNull("expected a service intent for ${serviceClass.simpleName}", intent)
    assertEquals(serviceClass.name, intent!!.component?.className)
    assertEquals(action, intent.action)
}
