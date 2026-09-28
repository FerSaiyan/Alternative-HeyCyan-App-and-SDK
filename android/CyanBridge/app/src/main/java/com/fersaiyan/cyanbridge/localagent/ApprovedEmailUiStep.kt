package com.fersaiyan.cyanbridge.localagent

/** Continues a previously approved email without ever preparing or approving a second send. */
internal object ApprovedEmailUiStep {
    const val CHOOSE_GMAIL_NOTE = "approved email chooser Gmail selection"
    const val SEND_TAP_NOTE = "approved Gmail UI Send tap"
    private const val GMAIL_PACKAGE = "com.google.android.gm"

    fun next(state: LocalAgentTaskState, observation: LocalAgentObservation): LocalAgentBrainOutput {
        if (!state.emailSendApproved) return LocalAgentBrainOutput()
        val nodes = observation.screenSnapshot?.nodes.orEmpty()
        fun label(node: LocalAgentScreenNode) = node.text.ifBlank { node.contentDescription }.trim()
        fun click(node: LocalAgentScreenNode, note: String) = LocalAgentBrainOutput(
            actions = listOf(LocalAgentAction.ClickCoord(node.bounds.centerX, node.bounds.centerY)),
            note = note,
        )

        if (observation.packageName == "android") {
            // Handle older imported Tasker profiles that may still launch a
            // resolver. The current SENDTO profile should open Gmail directly.
            // A goal beginning "Open Chrome" must not steal focus back here.
            if (!state.emailChooserGmailSelected) {
                nodes.firstOrNull { label(it).equals("Gmail", ignoreCase = true) }
                    ?.let { return click(it, CHOOSE_GMAIL_NOTE) }
            } else {
                nodes.firstOrNull { label(it).equals("Just once", ignoreCase = true) }
                    ?.let { return click(it, "approved Gmail chooser confirmation") }
            }
        }

        if (observation.packageName == GMAIL_PACKAGE) {
            val visible = buildString {
                append(observation.screenText.orEmpty())
                nodes.forEach { append(' '); append(label(it)) }
            }
            val subject = Regex("(?i)\\bexact\\s+subject\\s+'([^']+)'")
                .find(state.goal)?.groupValues?.get(1).orEmpty()
            val recipient = Regex("(?i)\\bemail\\s+to\\s+([A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,})")
                .find(state.goal)?.groupValues?.get(1).orEmpty()
            val matchesDraft = subject.isNotBlank() && recipient.isNotBlank() &&
                visible.contains(subject, ignoreCase = true) && visible.contains(recipient, ignoreCase = true)
            val send = nodes.firstOrNull { label(it).equals("Send", ignoreCase = true) }
            if (!state.emailUiSendAttempted && matchesDraft && send != null) {
                return click(send, SEND_TAP_NOTE)
            }
            if (state.emailUiSendAttempted && send == null &&
                matchesDraft && Regex("(?i)\\bmessage sent\\b").containsMatchIn(visible)) {
                return LocalAgentBrainOutput(
                    actions = listOf(LocalAgentAction.Finish("Observed the sent email in Gmail.")),
                    note = "Gmail sent confirmation observed after approved UI Send",
                    isComplete = true,
                )
            }
        }

        return LocalAgentBrainOutput(actions = listOf(LocalAgentAction.Wait(1_000L)),
            note = "waiting for approved email UI or independently observed sent state")
    }
}
