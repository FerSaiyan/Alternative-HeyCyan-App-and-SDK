package com.fersaiyan.cyanbridge.hil

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/** Checks a persistent lab AVD's real cloud entitlement without exposing credentials or spending quota. */
@RunWith(AndroidJUnit4::class)
class ProPlannerReadinessHilTest {
    @Test fun accountIsServerVerifiedAndReadyForCloudPlanning() {
        HilTestSupport.requireVerifiedProPlanner(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
    }
}
