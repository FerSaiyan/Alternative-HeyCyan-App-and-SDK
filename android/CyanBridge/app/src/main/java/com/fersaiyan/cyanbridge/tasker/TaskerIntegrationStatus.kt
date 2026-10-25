package com.fersaiyan.cyanbridge.tasker

/** Shared, user-facing readiness state for Tasker-powered CyanBridge features. */
data class TaskerIntegrationStatus(
    val taskerInstalled: Boolean = false,
    val taskerVersion: String? = null,
    val autoInputInstalled: Boolean = false,
    val autoInputVersion: String? = null,
    val taskerAccessibilityEnabled: Boolean = false,
    val autoInputAccessibilityEnabled: Boolean = false,
    val integrations: List<IntegrationState> = emptyList(),
    val nextAction: String? = null,
) {
    val automationEnvironmentReady: Boolean
        get() = taskerInstalled &&
            autoInputInstalled &&
            taskerAccessibilityEnabled &&
            autoInputAccessibilityEnabled
}

typealias IntegrationHealth = com.fersaiyan.cyanbridge.shared.automation.IntegrationHealth
typealias IntegrationState = com.fersaiyan.cyanbridge.shared.automation.IntegrationState

/** Pure compatibility logic shared by UI, policy and unit tests. */
object TaskerProfileVersionClassifier {
    fun classify(
        expectedTarget: String,
        expectedVersion: String,
        reportedTarget: String?,
        reportedVersion: String?,
    ): IntegrationHealth = when {
        reportedTarget.isNullOrBlank() || reportedVersion.isNullOrBlank() -> IntegrationHealth.NEEDS_SETUP
        reportedTarget != expectedTarget -> IntegrationHealth.WRONG_PROFILE
        reportedVersion != expectedVersion -> IntegrationHealth.OUTDATED
        else -> IntegrationHealth.READY
    }
}
