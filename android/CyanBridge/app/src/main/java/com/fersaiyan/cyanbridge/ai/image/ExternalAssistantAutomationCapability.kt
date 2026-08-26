package com.fersaiyan.cyanbridge.ai.image

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import com.fersaiyan.cyanbridge.tasker.TaskerEnvironmentInspector

data class ExternalAssistantAutomationCapability(
    val target: ImageAutomationTarget,
    val targetPackage: String?,
    val taskerInstalled: Boolean,
    val taskerVersion: String?,
    val taskerAccessibilityEnabled: Boolean,
    val autoInputInstalled: Boolean,
    val autoInputVersion: String?,
    val autoInputAccessibilityEnabled: Boolean,
    val profileCompatible: Boolean,
    val imageShareAvailable: Boolean,
    val phoneLocked: Boolean,
)

object ExternalAssistantAutomationPolicy {
    /**
     * Voice launch is intentionally less coupled than image automation.
     *
     * The Tasker voice path only receives CyanBridge's AI_EVENT and invokes Android's selected
     * assistant. It does not use the versioned AutoInput composer selectors. A stale profile may
     * therefore still provide a perfectly functional voice launch (as reported by real users),
     * so profile-version verification is a diagnostic for voice rather than a hard blocker.
     */
    fun voiceBlockingReason(capability: ExternalAssistantAutomationCapability): String? = when {
        capability.target == ImageAutomationTarget.NONE ->
            "Set Gemini or ChatGPT as your phone's default assistant first."
        capability.targetPackage == null ->
            "Install or update ${capability.target.label} first."
        !capability.taskerInstalled ->
            "Install Tasker and complete Tasker integration setup first."
        capability.phoneLocked ->
            "Unlock your phone before using Tasker assistant automation."
        else -> null
    }

    /**
     * Image automation keeps the strict profile contract because it depends on assistant-specific
     * share/composer behavior and AutoInput selectors that can change between profile versions.
     */
    fun imageBlockingReason(capability: ExternalAssistantAutomationCapability): String? =
        voiceBlockingReason(capability) ?: when {
            !capability.profileCompatible ->
                "Import/update and verify the ${capability.target.label} CyanBridge Tasker profile."
            !capability.autoInputInstalled ->
                "Install AutoInput and complete Tasker integration setup first."
            !capability.autoInputAccessibilityEnabled ->
                "Enable AutoInput accessibility before using external image questions."
            !capability.imageShareAvailable ->
                "${capability.target.label} cannot receive image shares on this phone."
            else -> null
        }
}

object ExternalAssistantAutomationInspector {
    fun inspect(context: Context): ExternalAssistantAutomationCapability {
        val target = ImageAutomationTarget.forDefaultAssistant(DefaultAssistantResolver.packageName(context))
        val targetPackage = target.packageNames.firstOrNull { isPackageInstalled(context, it) }
        val environment = TaskerEnvironmentInspector.inspect(context)
        val importedVersion = target.requiredProfileVersion?.let {
            TaskerImageProfileStore.version(context, target.wireName)
        }
        return ExternalAssistantAutomationCapability(
            target = target,
            targetPackage = targetPackage,
            taskerInstalled = environment.taskerInstalled,
            taskerVersion = environment.taskerVersion,
            taskerAccessibilityEnabled = environment.taskerAccessibilityEnabled,
            autoInputInstalled = environment.autoInputInstalled,
            autoInputVersion = environment.autoInputVersion,
            autoInputAccessibilityEnabled = environment.autoInputAccessibilityEnabled,
            profileCompatible = TaskerImageProfileCompatibility.supports(
                target = target,
                importedTarget = importedVersion?.let { target.wireName } ?: TaskerImageProfileStore.target(context),
                importedVersion = importedVersion ?: TaskerImageProfileStore.version(context),
            ),
            imageShareAvailable = targetPackage?.let { canResolveImageShare(context, it) } == true,
            phoneLocked = isDeviceLocked(context),
        )
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    private fun canResolveImageShare(context: Context, packageName: String): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            setPackage(packageName)
        }
        return intent.resolveActivity(context.packageManager) != null
    }

    private fun isDeviceLocked(context: Context): Boolean {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            keyguard.isDeviceLocked
        } else {
            @Suppress("DEPRECATION")
            keyguard.isKeyguardLocked
        }
    }
}
