package com.fersaiyan.cyanbridge.ota

import java.util.Locale

internal data class FirmwareSubscriptionGateCopy(
    val title: String,
    val message: String,
    val actionLabel: String,
)

internal fun firmwareSubscriptionGateCopy(currentPlan: String): FirmwareSubscriptionGateCopy {
    val normalizedPlan = currentPlan.trim().lowercase(Locale.US)
    val currentPlanMessage = when (normalizedPlan) {
        "free_trial" -> "Your current plan is Free Trial."
        "cheap" -> "Your current plan is Cheap. The paid Cheap plan does not include this firmware."
        "standard" -> "The server reports your current plan as Standard."
        "max" -> "The server reports your current plan as Max."
        "expired" -> "Your subscription has expired."
        "none" -> "You do not currently have an active subscription."
        "", "unknown" -> null
        else -> "The server reports your current plan as ${friendlyFirmwarePlanName(normalizedPlan)}."
    }
    val nextStep = if (normalizedPlan == "standard" || normalizedPlan == "max") {
        "Review or refresh your subscription, then try again."
    } else {
        "Upgrade to Standard or Max to continue."
    }

    return FirmwareSubscriptionGateCopy(
        title = "Patched LED OTA: Standard or Max Required",
        message = buildString {
            append("Patched LED OTA firmware requires a paid Standard or Max subscription.")
            currentPlanMessage?.let {
                append("\n\n")
                append(it)
            }
            append("\n\n")
            append(nextStep)
        },
        actionLabel = if (normalizedPlan == "standard" || normalizedPlan == "max") {
            "Manage subscription"
        } else {
            "View Standard & Max"
        },
    )
}

private fun friendlyFirmwarePlanName(plan: String): String = plan
    .split('_', '-')
    .filter { it.isNotBlank() }
    .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.US) } }
    .ifBlank { "Unknown" }
