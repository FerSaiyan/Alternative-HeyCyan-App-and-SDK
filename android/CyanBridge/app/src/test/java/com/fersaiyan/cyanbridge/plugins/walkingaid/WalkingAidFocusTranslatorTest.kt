package com.fersaiyan.cyanbridge.plugins.walkingaid

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WalkingAidFocusTranslatorTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("walking_aid_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun savedTranslationIsReusedWithoutAnotherCompletion() = runBlocking {
        var calls = 0
        val translator = WalkingAidFocusTranslator(context) { _, _ ->
            calls++
            "Watch for cars"
        }

        val translated = translator.translateToEnglish("Cuidado com carros", "cloud")
        WalkingAidPreferences.setCachedFocusTranslation(
            context,
            original = "Cuidado com carros",
            source = "cloud",
            english = translated,
        )
        assertEquals("Watch for cars", translator.translateToEnglish("Cuidado com carros", "cloud"))
        assertEquals(1, calls)
    }

    @Test
    fun translationCleanupRejectsInvalidOutput() {
        assertTrue(
            WalkingAidFocusTranslator.sanitizeTranslation("English: watch for dogs", "ignored")
                .contains("watch for dogs"),
        )
        runCatching {
            WalkingAidFocusTranslator.sanitizeTranslation("   ", "watch for dogs")
        }.onSuccess {
            throw AssertionError("Blank translation should fail")
        }
    }
}
