package com.fersaiyan.cyanbridge.plugins.walkingaid

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WalkingAidSceneFormatterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    @Config(qualifiers = "en")
    fun formatsEnglishCountsAndDirections() {
        assertEquals("person ahead.", WalkingAidSceneFormatter.format(context, ExploreObjectGroup("person", "center", 1)))
        assertEquals("2 people on your left.", WalkingAidSceneFormatter.format(context, ExploreObjectGroup("person", "left", 2)))
    }

    @Test
    @Config(qualifiers = "pt-rBR")
    fun usesAppLanguageForNounsAndAnnouncementTemplate() {
        val phrase = WalkingAidSceneFormatter.format(context, ExploreObjectGroup("person", "center", 2))
        assertFalse(phrase.contains("people"))
        assertFalse(phrase.contains("ahead"))
        assertTrue(phrase.contains("pessoas"))
    }

    @Test
    @Config(qualifiers = "en")
    fun selectedAppLanguageOverridesPhoneLanguage() {
        context.getSharedPreferences("app_language", Context.MODE_PRIVATE).edit()
            .putString("selected_language", "PORTUGUESE_BRAZIL").apply()
        val phrase = WalkingAidSceneFormatter.format(context, ExploreObjectGroup("person", "center", 2))
        assertTrue(phrase.contains("pessoas"))
        assertFalse(phrase.contains("ahead"))
    }

    @Test
    @Config(qualifiers = "ru")
    fun russianUsesCountSpecificPluralForms() {
        val resource = WalkingAidSceneFormatter.labelResource("person")
        assertEquals("человек", context.resources.getQuantityString(resource, 1))
        assertEquals("человека", context.resources.getQuantityString(resource, 2))
        assertEquals("человек", context.resources.getQuantityString(resource, 5))
    }

    @Test
    fun unknownLabelsUseTranslatedGenericObject() {
        assertEquals(R.plurals.walking_aid_object_unknown, WalkingAidSceneFormatter.labelResource("unsupported"))
    }
}
