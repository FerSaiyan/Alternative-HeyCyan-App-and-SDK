package com.fersaiyan.cyanbridge.shared.glasses

enum class MetaPairingIssueAction {
    INSTALL_META_AI,
    OPEN_PAIRING,
}

data class MetaPairingIssue(
    val title: String,
    val message: String,
    val primaryLabel: String,
    val action: MetaPairingIssueAction,
)

fun resolveMetaPairingIssue(
    metaAiInstalled: Boolean,
    lastError: String?,
    setupGuidance: String?,
): MetaPairingIssue? {
    val error = lastError?.takeIf { it.isNotBlank() } ?: return null
    val normalized = error.lowercase()
    return when {
        !metaAiInstalled || ("meta ai" in normalized && "not installed" in normalized) -> MetaPairingIssue(
            title = "Meta AI is required",
            message = "Install Meta AI, pair your glasses there, and then return to CyanBridge to authorize camera access.",
            primaryLabel = "Install Meta AI",
            action = MetaPairingIssueAction.INSTALL_META_AI,
        )
        "permission" in normalized || "denied" in normalized -> MetaPairingIssue(
            title = "Permission needed",
            message = setupGuidance
                ?: "CyanBridge could not continue because a required Android or Meta glasses permission is missing.",
            primaryLabel = "Try again",
            action = MetaPairingIssueAction.OPEN_PAIRING,
        )
        "bluetooth" in normalized || "device" in normalized || "registration required" in normalized ||
            "registration is required" in normalized || "unavailable" in normalized ||
            "not connected" in normalized -> MetaPairingIssue(
            title = "Meta glasses are not ready",
            message = setupGuidance
                ?: "Open Meta AI and confirm that the glasses are powered, unfolded, nearby, and connected, then try again.",
            primaryLabel = "Try again",
            action = MetaPairingIssueAction.OPEN_PAIRING,
        )
        else -> MetaPairingIssue(
            title = "We could not finish Meta pairing",
            message = setupGuidance
                ?: "CyanBridge received an unexpected response from Meta Wearables. Review the pairing steps and try again.",
            primaryLabel = "Try again",
            action = MetaPairingIssueAction.OPEN_PAIRING,
        )
    }
}
