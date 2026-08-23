package com.fersaiyan.cyanbridge.ai.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalAssistantAutomationPolicyTest {
    @Test
    fun readyGeminiCapabilitySupportsVoiceAndImages() {
        val capability = readyCapability(ImageAutomationTarget.GEMINI)

        assertNull(ExternalAssistantAutomationPolicy.voiceBlockingReason(capability))
        assertNull(ExternalAssistantAutomationPolicy.imageBlockingReason(capability))
    }

    @Test
    fun readyChatGptCapabilitySupportsVoiceAndImages() {
        val capability = readyCapability(ImageAutomationTarget.CHATGPT)

        assertNull(ExternalAssistantAutomationPolicy.voiceBlockingReason(capability))
        assertNull(ExternalAssistantAutomationPolicy.imageBlockingReason(capability))
    }

    @Test
    fun unsupportedDefaultAssistantIsRejected() {
        val capability = readyCapability(ImageAutomationTarget.NONE).copy(targetPackage = null)

        assertEquals(
            "Set Gemini or ChatGPT as your phone's default assistant first.",
            ExternalAssistantAutomationPolicy.imageBlockingReason(capability),
        )
    }

    @Test
    fun lockedPhoneFailsImmediately() {
        val capability = readyCapability(ImageAutomationTarget.GEMINI).copy(phoneLocked = true)

        assertEquals(
            "Unlock your phone before using Tasker assistant automation.",
            ExternalAssistantAutomationPolicy.voiceBlockingReason(capability),
        )
    }

    @Test
    fun taskerAccessibilityIsDiagnosedButDoesNotBlockAssistantProfileThatDoesNotUseWin() {
        val capability = readyCapability(ImageAutomationTarget.GEMINI).copy(taskerAccessibilityEnabled = false)

        assertNull(ExternalAssistantAutomationPolicy.voiceBlockingReason(capability))
        assertNull(ExternalAssistantAutomationPolicy.imageBlockingReason(capability))
    }

    @Test
    fun imageRequiresAutoInputButVoiceDoesNot() {
        val capability = readyCapability(ImageAutomationTarget.GEMINI).copy(
            autoInputInstalled = false,
            autoInputAccessibilityEnabled = false,
        )

        assertNull(ExternalAssistantAutomationPolicy.voiceBlockingReason(capability))
        assertEquals(
            "Install AutoInput and complete Tasker integration setup first.",
            ExternalAssistantAutomationPolicy.imageBlockingReason(capability),
        )
    }

    private fun readyCapability(target: ImageAutomationTarget) = ExternalAssistantAutomationCapability(
        target = target,
        targetPackage = target.packageNames.firstOrNull(),
        taskerInstalled = true,
        taskerVersion = "6.6.20",
        taskerAccessibilityEnabled = true,
        autoInputInstalled = true,
        autoInputVersion = "3.0.12",
        autoInputAccessibilityEnabled = true,
        profileCompatible = true,
        imageShareAvailable = true,
        phoneLocked = false,
    )
}
