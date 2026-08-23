package com.fersaiyan.cyanbridge.tasker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskerIntegrationStatusTest {
    @Test
    fun classifierDistinguishesCurrentOutdatedWrongAndMissingProfiles() {
        assertEquals(
            IntegrationHealth.READY,
            TaskerProfileVersionClassifier.classify("gemini", "gemini-v3", "gemini", "gemini-v3"),
        )
        assertEquals(
            IntegrationHealth.OUTDATED,
            TaskerProfileVersionClassifier.classify("gemini", "gemini-v3", "gemini", "gemini-v2"),
        )
        assertEquals(
            IntegrationHealth.WRONG_PROFILE,
            TaskerProfileVersionClassifier.classify("gemini", "gemini-v3", "chatgpt", "chatgpt-v1"),
        )
        assertEquals(
            IntegrationHealth.NEEDS_SETUP,
            TaskerProfileVersionClassifier.classify("gemini", "gemini-v3", null, null),
        )
    }

    @Test
    fun accessibilityParserRecognizesTaskerAndAutoInputServices() {
        val packages = TaskerEnvironmentInspector.enabledAccessibilityPackages(
            "net.dinglisch.android.taskerm/net.dinglisch.android.taskerm.MyAccessibilityService:" +
                "com.joaomgcd.autoinput/com.joaomgcd.autoinput.service.ServiceAccessibilityV2",
        )

        assertTrue("net.dinglisch.android.taskerm" in packages)
        assertTrue("com.joaomgcd.autoinput" in packages)
    }

    @Test
    fun environmentReadyRequiresBothAppsAndBothAccessibilityServices() {
        val ready = TaskerIntegrationStatus(
            taskerInstalled = true,
            autoInputInstalled = true,
            taskerAccessibilityEnabled = true,
            autoInputAccessibilityEnabled = true,
        )
        val missingTaskerAccessibility = ready.copy(taskerAccessibilityEnabled = false)

        assertTrue(ready.automationEnvironmentReady)
        assertEquals(false, missingTaskerAccessibility.automationEnvironmentReady)
    }
}
