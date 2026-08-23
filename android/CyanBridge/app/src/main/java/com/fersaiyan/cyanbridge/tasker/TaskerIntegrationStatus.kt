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

enum class IntegrationHealth {
    READY,
    NEEDS_SETUP,
    OUTDATED,
    WRONG_PROFILE,
    NOT_SELECTED,
    ENVIRONMENT_BLOCKED,
    NOT_VERSIONED,
}

data class IntegrationState(
    val id: String,
    val name: String,
    val installedVersion: String? = null,
    val requiredVersion: String? = null,
    val health: IntegrationHealth = IntegrationHealth.NEEDS_SETUP,
    val detail: String = "",
    val actionHint: String? = null,
) {
    val ready: Boolean get() = health == IntegrationHealth.READY
}

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
