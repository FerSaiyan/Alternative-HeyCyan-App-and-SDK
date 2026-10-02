package com.fersaiyan.cyanbridge.plugins.walkingaid

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalkingAidVideoSourceButtonsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun eyevueButtonSelectsVideoAndHidesMeta() {
        var selected = WalkingAidVideoMode.PERIODIC_PHOTOS
        compose.setContent {
            MaterialTheme {
                WalkingAidVideoSourceButtons(selected, DeviceClass.EYEVUE, 34, true) { selected = it }
            }
        }
        compose.onNodeWithText("Meta Ray-Ban continuous video").assertDoesNotExist()
        compose.onNodeWithText("EyeVue continuous video").performClick()
        compose.runOnIdle { assertEquals(WalkingAidVideoMode.EYEVUE_VIDEO, selected) }
    }

    @Test fun heyCyanButtonSelectsContinuousVideo() {
        var selected = WalkingAidVideoMode.PERIODIC_PHOTOS
        compose.setContent {
            MaterialTheme {
                WalkingAidVideoSourceButtons(selected, DeviceClass.HEY_CYAN, 34, true) { selected = it }
            }
        }
        compose.onNodeWithText("EyeVue continuous video").assertDoesNotExist()
        compose.onNodeWithText("Meta Ray-Ban continuous video").assertDoesNotExist()
        compose.onNodeWithText("HeyCyan continuous video").performClick()
        compose.runOnIdle { assertEquals(WalkingAidVideoMode.HEYCYAN_VIDEO, selected) }
    }

    @Test fun metaButtonSelectsVideoAndPhotosCanBeRestored() {
        var selected = WalkingAidVideoMode.PERIODIC_PHOTOS
        compose.setContent {
            MaterialTheme {
                WalkingAidVideoSourceButtons(selected, DeviceClass.META_RAYBAN, 34, true) { selected = it }
            }
        }
        compose.onNodeWithText("EyeVue continuous video").assertDoesNotExist()
        compose.onNodeWithText("Meta Ray-Ban continuous video").performClick()
        compose.runOnIdle { assertEquals(WalkingAidVideoMode.META_VIDEO, selected) }
        compose.onNodeWithText("Periodic photos").performClick()
        compose.runOnIdle { assertEquals(WalkingAidVideoMode.PERIODIC_PHOTOS, selected) }
    }

    @Test fun otherGlassesDoNotExposeVideoControls() {
        compose.setContent {
            MaterialTheme {
                WalkingAidVideoSourceButtons(WalkingAidVideoMode.PERIODIC_PHOTOS, DeviceClass.UNKNOWN, 34, true) {}
            }
        }
        compose.onNodeWithText("HeyCyan continuous video").assertDoesNotExist()
        compose.onNodeWithText("EyeVue continuous video").assertDoesNotExist()
        compose.onNodeWithText("Meta Ray-Ban continuous video").assertDoesNotExist()
    }

    @Test fun runningSessionPreventsChangingTheCameraSource() {
        compose.setContent {
            MaterialTheme {
                WalkingAidVideoSourceButtons(WalkingAidVideoMode.META_VIDEO, DeviceClass.META_RAYBAN, 34, false) {}
            }
        }
        compose.onNodeWithText("Meta Ray-Ban continuous video").assertIsNotEnabled()
        compose.onNodeWithText("Periodic photos").assertIsNotEnabled()
    }
}
