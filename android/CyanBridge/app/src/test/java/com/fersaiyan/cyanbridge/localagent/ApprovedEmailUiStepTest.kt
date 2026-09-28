package com.fersaiyan.cyanbridge.localagent

import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovedEmailUiStepTest {
    private val goal = "Open Chrome, read the article, then prepare an email to ana@example.com " +
        "with the exact subject 'Article summary 123'. Only after approval use Gmail Send."
    private val state = LocalAgentTaskState(goal, 30, 1L, emailSendApproved = true)

    private fun observed(pkg: String, vararg labels: String): LocalAgentObservation = LocalAgentObservation(
        createdAtMs = 1L, packageName = pkg, screenText = labels.joinToString(" "),
        screenSnapshot = LocalAgentScreenSnapshot(pkg, labels.joinToString(" "),
            labels.mapIndexed { index, text -> LocalAgentScreenNode(
                index = index, depth = 0, text = text, contentDescription = "", className = "Button",
                viewId = "", isClickable = true, isEditable = false, isScrollable = false,
                bounds = LocalAgentNodeBounds(index * 100, 100, index * 100 + 80, 150),
            ) }),
    )

    @Test fun `approved send handles Gmail chooser without reopening Chrome or drafting again`() {
        assertEquals(listOf(LocalAgentAction.Wait(1_000L)),
            ApprovedEmailUiStep.next(state, observed("com.android.chrome", "Article summary 123")).actions)
        assertEquals(listOf(LocalAgentAction.ClickCoord(140, 125)),
            ApprovedEmailUiStep.next(state, observed("android", "Bluetooth", "Gmail", "Nearby Share")).actions)
        assertEquals(listOf(LocalAgentAction.ClickCoord(140, 125)),
            ApprovedEmailUiStep.next(state.copy(emailChooserGmailSelected = true),
                observed("android", "Gmail", "Just once", "Always")).actions)
    }

    @Test fun `Gmail UI Send requires the exact approved draft and only one tap`() {
        val composer = observed("com.google.android.gm", "ana@example.com", "Article summary 123", "Send")
        assertEquals(listOf(LocalAgentAction.ClickCoord(240, 125)), ApprovedEmailUiStep.next(state, composer).actions)
        assertEquals(ApprovedEmailUiStep.SEND_TAP_NOTE, ApprovedEmailUiStep.next(state, composer).note)
        assertEquals(listOf(LocalAgentAction.Wait(1_000L)),
            ApprovedEmailUiStep.next(state, observed("com.google.android.gm", "other@example.com", "Other subject", "Send")).actions)
        assertEquals(listOf(LocalAgentAction.Wait(1_000L)),
            ApprovedEmailUiStep.next(state.copy(emailUiSendAttempted = true), composer).actions)
        assertEquals(listOf(LocalAgentAction.Finish("Observed the sent email in Gmail.")),
            ApprovedEmailUiStep.next(state.copy(emailUiSendAttempted = true),
                observed("com.google.android.gm", "Message sent", "ana@example.com", "Article summary 123")).actions)
    }
}
