package com.fersaiyan.cyanbridge.tasker

/**
 * Shared status model for all Tasker based integrations.
 *
 * This keeps Gemini, ChatGPT, AutoDiary and local agent integrations from
 * implementing their own permission/error detection independently.
 */
data class TaskerIntegrationStatus(
    val taskerInstalled: Boolean = false,
    val autoInputInstalled: Boolean = false,
    val taskerAccessibilityEnabled: Boolean = false,
    val autoInputAccessibilityEnabled: Boolean = false,
    val integrations: List<IntegrationState> = emptyList()
)

data class IntegrationState(
    val name: String,
    val installedVersion: String? = null,
    val requiredVersion: String? = null,
    val enabled: Boolean = false,
    val lastError: String? = null
)
