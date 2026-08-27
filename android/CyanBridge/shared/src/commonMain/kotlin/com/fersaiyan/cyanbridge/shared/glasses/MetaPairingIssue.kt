package com.fersaiyan.cyanbridge.shared.glasses

enum class MetaPairingIssueAction {
    INSTALL_META_AI,
    OPEN_PAIRING,
    REQUEST_ACCESS,
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
    metaAccessRequired: Boolean = false,
): MetaPairingIssue? {
    val error = lastError?.takeIf { it.isNotBlank() } ?: return null
    val normalized = error.lowercase()
    return when {
        metaAccessRequired -> MetaPairingIssue(
            title = "Meta access required",
            message = "Meta Ray-Ban access is currently limited to CyanBridge early testers. Your Meta account has not been registered yet. Submit the email associated with your Meta account at:\n\nhttps://cyanbridge.vercel.app/beta\n\nAfter you are invited to the Meta release channel, restart CyanBridge and try pairing again.",
            primaryLabel = "Request access",
            action = MetaPairingIssueAction.REQUEST_ACCESS,
        )
        !metaAiInstalled || ("meta ai" in normalized && "not installed" in normalized) -> MetaPairingIssue(
            title = "Meta AI is required",
            message = "Meta AI is required for Meta Ray-Ban glasses. Please install or update Meta AI and try again.",
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
        "release channel" in normalized ||
            "registeredsnapps" in normalized || "issessionverified" in normalized -> MetaPairingIssue(
            title = "Meta access required",
            message = "Your Meta account is not currently enabled for CyanBridge Meta access. Request access at:\n\nhttps://cyanbridge.vercel.app/beta",
            primaryLabel = "Request access",
            action = MetaPairingIssueAction.REQUEST_ACCESS,
        )
        ("meta dat registration is unavailable" in normalized ||
            "meta wearables is currently unavailable" in normalized) &&
            "developer mode" !in normalized -> MetaPairingIssue(
            title = "Meta Wearables unavailable",
            message = "Meta Wearables is currently unavailable for this account. This may happen if your account has not been added to the CyanBridge Meta testing channel. Request access at:\n\nhttps://cyanbridge.vercel.app/beta",
            primaryLabel = "Request access",
            action = MetaPairingIssueAction.REQUEST_ACCESS,
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
