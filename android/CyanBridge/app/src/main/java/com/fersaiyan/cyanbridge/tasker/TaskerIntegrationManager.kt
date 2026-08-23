package com.fersaiyan.cyanbridge.tasker

import android.content.Context
import android.provider.Settings
import com.fersaiyan.cyanbridge.ai.image.DefaultAssistantResolver
import com.fersaiyan.cyanbridge.ai.image.ExternalImageAutomationIntents
import com.fersaiyan.cyanbridge.ai.image.ImageAutomationTarget
import com.fersaiyan.cyanbridge.ai.image.TaskerImageProfileStore

/** Package/accessibility checks that do not depend on any one CyanBridge feature. */
data class TaskerEnvironmentStatus(
    val taskerInstalled: Boolean,
    val taskerVersion: String?,
    val autoInputInstalled: Boolean,
    val autoInputVersion: String?,
    val taskerAccessibilityEnabled: Boolean,
    val autoInputAccessibilityEnabled: Boolean,
)

object TaskerEnvironmentInspector {
    fun inspect(context: Context): TaskerEnvironmentStatus {
        val enabledPackages = enabledAccessibilityPackages(
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty(),
        )
        val taskerVersion = packageVersion(context, ExternalImageAutomationIntents.TASKER_PACKAGE)
        val autoInputVersion = packageVersion(context, ExternalImageAutomationIntents.AUTO_INPUT_PACKAGE)
        return TaskerEnvironmentStatus(
            taskerInstalled = taskerVersion != null,
            taskerVersion = taskerVersion,
            autoInputInstalled = autoInputVersion != null,
            autoInputVersion = autoInputVersion,
            taskerAccessibilityEnabled = ExternalImageAutomationIntents.TASKER_PACKAGE in enabledPackages,
            autoInputAccessibilityEnabled = ExternalImageAutomationIntents.AUTO_INPUT_PACKAGE in enabledPackages,
        )
    }

    /** Pure parser so this contract remains testable in local JVM unit tests. */
    internal fun enabledAccessibilityPackages(flattened: String): Set<String> = flattened
        .split(':')
        .mapNotNull { component ->
            component.substringBefore('/').trim().takeIf(String::isNotBlank)
        }
        .toSet()

    private fun packageVersion(context: Context, packageName: String): String? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, 0).versionName ?: "installed"
    }.getOrNull()
}

/**
 * Single source of truth for the Settings diagnostics UI.
 *
 * Gemini/ChatGPT have a real versioned handshake. The Local Agent, AutoDiary and Visual Diary
 * projects intentionally report environment readiness only until those projects gain their own
 * version-response contract; the UI never pretends that merely having Tasker installed proves a
 * particular project version is imported.
 */
object TaskerIntegrationManager {
    const val LOCAL_AGENT_ID = "local_agent"
    const val AUTO_DIARY_ID = "auto_diary"
    const val VISUAL_DIARY_ID = "visual_diary"

    fun inspect(context: Context): TaskerIntegrationStatus {
        val environment = TaskerEnvironmentInspector.inspect(context)
        val defaultTarget = ImageAutomationTarget.forDefaultAssistant(DefaultAssistantResolver.packageName(context))
        val integrations = buildList {
            add(assistantState(context, ImageAutomationTarget.GEMINI, defaultTarget, environment))
            add(assistantState(context, ImageAutomationTarget.CHATGPT, defaultTarget, environment))
            add(autoInputProjectState(LOCAL_AGENT_ID, "Local Agent", environment))
            add(autoInputProjectState(AUTO_DIARY_ID, "AutoDiary", environment))
            add(
                IntegrationState(
                    id = VISUAL_DIARY_ID,
                    name = "Visual Diary",
                    health = if (environment.taskerInstalled) IntegrationHealth.NOT_VERSIONED else IntegrationHealth.ENVIRONMENT_BLOCKED,
                    detail = if (environment.taskerInstalled) {
                        "Tasker is ready to schedule Visual Diary. This project does not expose a version handshake yet; re-import the current profile from Plugins if scheduling fails."
                    } else {
                        "Tasker is not installed."
                    },
                    actionHint = "Current profile is available from Plugins.",
                ),
            )
        }
        return TaskerIntegrationStatus(
            taskerInstalled = environment.taskerInstalled,
            taskerVersion = environment.taskerVersion,
            autoInputInstalled = environment.autoInputInstalled,
            autoInputVersion = environment.autoInputVersion,
            taskerAccessibilityEnabled = environment.taskerAccessibilityEnabled,
            autoInputAccessibilityEnabled = environment.autoInputAccessibilityEnabled,
            integrations = integrations,
            nextAction = nextAction(defaultTarget, environment, integrations),
        )
    }

    private fun assistantState(
        context: Context,
        target: ImageAutomationTarget,
        defaultTarget: ImageAutomationTarget,
        environment: TaskerEnvironmentStatus,
    ): IntegrationState {
        val required = target.requiredProfileVersion ?: return IntegrationState(
            id = target.wireName,
            name = target.label,
            health = IntegrationHealth.NOT_SELECTED,
        )
        val perTargetVersion = TaskerImageProfileStore.version(context, target.wireName)
        val legacyTarget = TaskerImageProfileStore.target(context)
        val legacyVersion = TaskerImageProfileStore.version(context)
        val reportedTarget = when {
            perTargetVersion != null -> target.wireName
            legacyTarget != null -> legacyTarget
            else -> null
        }
        val reportedVersion = perTargetVersion ?: legacyVersion
        var health = TaskerProfileVersionClassifier.classify(
            expectedTarget = target.wireName,
            expectedVersion = required,
            reportedTarget = reportedTarget,
            reportedVersion = reportedVersion,
        )
        if (!environment.taskerInstalled) {
            health = IntegrationHealth.ENVIRONMENT_BLOCKED
        } else if (target != defaultTarget && perTargetVersion == null) {
            // Do not label an unselected assistant "wrong profile" just because the other assistant
            // was the most recently verified legacy value.
            health = IntegrationHealth.NOT_SELECTED
        }
        val detail = when (health) {
            IntegrationHealth.READY -> "Verified $reportedVersion profile."
            IntegrationHealth.OUTDATED -> "Verified $reportedVersion, but CyanBridge requires $required. Import the current profile and verify again."
            IntegrationHealth.WRONG_PROFILE -> "The last verified profile belongs to ${reportedTarget ?: "another assistant"}. Verify the ${target.label} profile."
            IntegrationHealth.NOT_SELECTED -> "Not currently selected as Android's default assistant. You can still import its profile now."
            IntegrationHealth.ENVIRONMENT_BLOCKED -> "Tasker is required before this profile can be verified."
            IntegrationHealth.NEEDS_SETUP -> if (target == defaultTarget) {
                "No current $required handshake. If you imported an older Tasker_AI profile, update it and verify again."
            } else {
                "Profile has not been verified on this device."
            }
            IntegrationHealth.NOT_VERSIONED -> "Profile does not expose a version handshake."
        }
        return IntegrationState(
            id = target.wireName,
            name = target.label,
            installedVersion = perTargetVersion ?: legacyVersion.takeIf { legacyTarget == target.wireName },
            requiredVersion = required,
            health = health,
            detail = detail,
            actionHint = if (health == IntegrationHealth.OUTDATED || health == IntegrationHealth.NEEDS_SETUP) {
                "Import/update profile"
            } else null,
        )
    }

    private fun autoInputProjectState(
        id: String,
        name: String,
        environment: TaskerEnvironmentStatus,
    ): IntegrationState {
        val ready = environment.taskerInstalled &&
            environment.autoInputInstalled &&
            environment.taskerAccessibilityEnabled &&
            environment.autoInputAccessibilityEnabled
        return IntegrationState(
            id = id,
            name = name,
            health = if (ready) IntegrationHealth.NOT_VERSIONED else IntegrationHealth.ENVIRONMENT_BLOCKED,
            detail = if (ready) {
                "Tasker + AutoInput environment is ready. This project does not expose a version handshake yet; re-import the current project from Plugins if execution fails."
            } else {
                "Tasker, AutoInput, and both accessibility services must be ready."
            },
            actionHint = "Current profile is available from Plugins.",
        )
    }

    private fun nextAction(
        defaultTarget: ImageAutomationTarget,
        environment: TaskerEnvironmentStatus,
        integrations: List<IntegrationState>,
    ): String? = when {
        !environment.taskerInstalled -> "Install Tasker."
        !environment.autoInputInstalled -> "Install AutoInput (and restore its AutoApps entitlement if required)."
        !environment.taskerAccessibilityEnabled -> "Enable Tasker's Accessibility Access through Tasker's own disclosure flow."
        !environment.autoInputAccessibilityEnabled -> "Enable AutoInput Accessibility Access."
        defaultTarget == ImageAutomationTarget.NONE -> "Choose Gemini or ChatGPT as Android's default assistant to configure image questions."
        else -> integrations.firstOrNull { it.id == defaultTarget.wireName && !it.ready }?.detail
    }
}
