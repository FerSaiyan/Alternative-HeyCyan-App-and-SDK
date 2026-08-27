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
            message = "Install or update Meta AI, pair your glasses there, and then return to CyanBridge. If this still fails, use Send logs so the developer can inspect the DAT state.",
            primaryLabel = "Install Meta AI",
            action = MetaPairingIssueAction.INSTALL_META_AI,
        )
        "firmware update" in normalized || "device_update_required" in normalized -> MetaPairingIssue(
            title = "Glasses update required",
            message = "Meta DAT reports that the glasses firmware must be updated. Update the glasses in Meta AI, reconnect them, and try again.",
            primaryLabel = "Try again",
            action = MetaPairingIssueAction.OPEN_PAIRING,
        )
        "sdk update" in normalized || "sdk_update_required" in normalized -> MetaPairingIssue(
            title = "Meta DAT update required",
            message = "The installed glasses firmware requires a newer Meta DAT SDK than this CyanBridge build provides. Update CyanBridge when a newer build is available; you can also send logs so the exact versions are recorded.",
            primaryLabel = "Try again",
            action = MetaPairingIssueAction.OPEN_PAIRING,
        )
        "release channel" in normalized || "eligible" in normalized ||
            "registeredsnapps" in normalized || "issessionverified" in normalized -> MetaPairingIssue(
            title = "Meta authorization is incomplete",
            message = setupGuidance
                ?: "Meta knows about the app registration, but the glasses are not eligible for this CyanBridge build yet. Confirm the correct Meta account, release channel or Developer Mode, then try again.",
            primaryLabel = "Try again",
            action = MetaPairingIssueAction.OPEN_PAIRING,
        )
        "permission" in normalized || "denied" in normalized -> MetaPairingIssue(
            title = "Permission needed",
            message = setupGuidance
                ?: "CyanBridge could not continue because a required Android or Meta glasses permission is missing. Grant the requested permission and try again.",
            primaryLabel = "Try again",
            action = MetaPairingIssueAction.OPEN_PAIRING,
        )
        "no eligible device" in normalized || "no compatible meta wearable" in normalized ||
            "no dat device" in normalized || "device unavailable" in normalized ||
            "powered off or disconnected" in normalized || "dat cannot see" in normalized ||
            "bluetooth" in normalized || "device" in normalized || "registration required" in normalized ||
            "registration is required" in normalized || "unavailable" in normalized ||
            "not connected" in normalized -> MetaPairingIssue(
            title = "Meta glasses are not ready",
            message = setupGuidance
                ?: "Open Meta AI and confirm that the glasses are powered, unfolded, nearby, and connected there. Return to CyanBridge and try again. If DAT still cannot see them, use Send logs.",
            primaryLabel = "Try again",
            action = MetaPairingIssueAction.OPEN_PAIRING,
        )
        "callback" in normalized || "deep link" in normalized || "opening the link" in normalized -> MetaPairingIssue(
            title = "Meta registration did not return correctly",
            message = "The Meta AI authorization flow did not return cleanly to CyanBridge. Re-open Meta AI, retry registration, and use Send logs if it happens again.",
            primaryLabel = "Try again",
            action = MetaPairingIssueAction.OPEN_PAIRING,
        )
        else -> MetaPairingIssue(
            title = "We could not finish Meta pairing",
            message = setupGuidance
                ?: "CyanBridge received an unexpected response from Meta Wearables. Review the pairing steps, try again, and use Send logs if the problem repeats.",
            primaryLabel = "Try again",
            action = MetaPairingIssueAction.OPEN_PAIRING,
        )
    }
}
