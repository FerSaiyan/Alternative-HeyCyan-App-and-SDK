package com.fersaiyan.cyanbridge.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CommunityPluginPrefsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearShortcutPreference() {
        context.getSharedPreferences("community_plugins", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun restoreEmptyShortcutPreference() {
        context.getSharedPreferences("community_plugins", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun shortcutCanBeSelectedAndCleared() {
        CommunityPluginPrefs.setGlassesTabShortcutEnabled(context, NativePluginIds.MEETING_SPARK_NOTES, true)
        assertEquals(
            NativePluginIds.MEETING_SPARK_NOTES,
            CommunityPluginPrefs.getGlassesTabShortcutPluginId(context),
        )

        CommunityPluginPrefs.setGlassesTabShortcutEnabled(context, NativePluginIds.MEETING_SPARK_NOTES, false)
        assertNull(CommunityPluginPrefs.getGlassesTabShortcutPluginId(context))
    }
}
