package com.fersaiyan.cyanbridge.plugins.walkingaid

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fersaiyan.cyanbridge.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalkingAidExploreSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var previousMode = WalkingAidMode.WALKING
    private var previousEnabled = false
    private var previousSpeechRate = 1f
    private var previousPauseMs = 1_000L

    @Before
    fun prepare() {
        previousMode = WalkingAidPreferences.getMode(context)
        previousEnabled = WalkingAidPreferences.isEnabled(context)
        previousSpeechRate = WalkingAidPreferences.getSpeechRate(context)
        previousPauseMs = WalkingAidPreferences.getAnnouncementPauseMs(context)
        WalkingAidPreferences.setMode(context, WalkingAidMode.WALKING)
        WalkingAidPreferences.setEnabled(context, false)
    }

    @After
    fun restore() {
        WalkingAidPreferences.setMode(context, previousMode)
        WalkingAidPreferences.setEnabled(context, previousEnabled)
        WalkingAidPreferences.setSpeechRate(context, previousSpeechRate)
        WalkingAidPreferences.setAnnouncementPauseMs(context, previousPauseMs)
    }

    @Test
    fun explorePersistsModeAndExposesPacingInsteadOfWalkingDepthControls() {
        showSettings()
        compose.onNodeWithText(context.getString(R.string.walking_aid_mode_explore)).performClick()
        compose.runOnIdle { assertEquals(WalkingAidMode.EXPLORE, WalkingAidPreferences.getMode(context)) }
        compose.onNodeWithText(context.getString(R.string.walking_aid_depth_estimation)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.walking_aid_speech_rate))
            .performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1.5f) }
        compose.onNodeWithContentDescription(context.getString(R.string.walking_aid_announcement_pause))
            .performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        compose.runOnIdle {
            assertEquals(1.5f, WalkingAidPreferences.getSpeechRate(context), 0.01f)
            assertEquals(2_000L, WalkingAidPreferences.getAnnouncementPauseMs(context))
        }
        compose.onNodeWithText(context.getString(R.string.walking_aid_mode_walking)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(WalkingAidMode.WALKING, WalkingAidPreferences.getMode(context)) }
        compose.onNodeWithText(context.getString(R.string.walking_aid_depth_estimation)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.walking_aid_announcement_pause)).assertDoesNotExist()
    }

    @Test
    fun activeSessionPreventsSwitchingBehavior() {
        WalkingAidPreferences.setEnabled(context, true)
        showSettings()
        compose.onNodeWithText(context.getString(R.string.walking_aid_mode_walking)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.walking_aid_mode_explore)).assertIsNotEnabled()
    }

    private fun showSettings() {
        compose.setContent {
            MaterialTheme {
                WalkingAidSettingsScreen(onBack = {}, onStartService = {}, onStopService = {})
            }
        }
    }
}
