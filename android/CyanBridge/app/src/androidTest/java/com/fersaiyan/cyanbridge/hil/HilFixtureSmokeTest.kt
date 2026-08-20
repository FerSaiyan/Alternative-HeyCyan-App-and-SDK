package com.fersaiyan.cyanbridge.hil

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fersaiyan.cyanbridge.R
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HilFixtureSmokeTest {
    @Test
    fun fixtureExposesStableSelectors() {
        ActivityScenario.launch(HilFixtureActivity::class.java).use {
            onView(withId(R.id.hil_marker))
                .check(matches(withText(HilFixtureActivity.MARKER_TEXT)))
                .check(matches(isDisplayed()))
            onView(withId(R.id.hil_status))
                .check(matches(withText(HilFixtureActivity.STATUS_READY)))
            onView(withId(R.id.hil_click_button))
                .check(matches(withText(HilFixtureActivity.CLICK_BUTTON_TEXT)))
            onView(withId(R.id.hil_input))
                .check(matches(withHint(HilFixtureActivity.INPUT_HINT)))
        }
    }
}
