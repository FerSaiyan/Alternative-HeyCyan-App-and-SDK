package com.fersaiyan.cyanbridge.shared.billing

/** Copy of the "Standard or Max required" dialog that paid-only features show. */
data class SubscriptionGateCopy(
    val title: String,
    val message: String,
    val actionLabel: String,
)

/**
 * The dialog text for [currentPlan] (the server's `currentPlan`). [featureTitle] heads the dialog,
 * [featureSentenceSubject] starts its first sentence and [excludedThing] ends the Cheap-plan note.
 */
fun subscriptionGateCopy(
    currentPlan: String,
    featureTitle: String,
    featureSentenceSubject: String,
    excludedThing: String,
): SubscriptionGateCopy {
    val normalizedPlan = currentPlan.trim().lowercase()
    val currentPlanMessage = when (normalizedPlan) {
        "free_trial" -> "Your current plan is Free Trial."
        "cheap" -> "Your current plan is Cheap. The paid Cheap plan does not include $excludedThing."
        "standard" -> "The server reports your current plan as Standard."
        "max" -> "The server reports your current plan as Max."
        "expired" -> "Your subscription has expired."
        "none" -> "You do not currently have an active subscription."
        "", "unknown" -> null
        else -> "The server reports your current plan as ${friendlyPlanName(normalizedPlan)}."
    }
    val paid = normalizedPlan == "standard" || normalizedPlan == "max"
    return SubscriptionGateCopy(
        title = "$featureTitle: Standard or Max Required",
        message = buildString {
            append("$featureSentenceSubject requires a paid Standard or Max subscription.")
            currentPlanMessage?.let {
                append("\n\n")
                append(it)
            }
            append("\n\n")
            append(if (paid) "Review or refresh your subscription, then try again." else "Upgrade to Standard or Max to continue.")
        },
        actionLabel = if (paid) "Manage subscription" else "View Standard & Max",
    )
}

private fun friendlyPlanName(plan: String): String = plan
    .split('_', '-')
    .filter { it.isNotBlank() }
    .joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }
    .ifBlank { "Unknown" }
