package com.fersaiyan.cyanbridge.ui.main

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.fersaiyan.cyanbridge.agent.LocalModelsConfigureActivity
import com.fersaiyan.cyanbridge.agent.ProSubscriptionActivity
import com.fersaiyan.cyanbridge.ai.image.ExternalAssistantAutomationInspector
import com.fersaiyan.cyanbridge.ai.image.ExternalAssistantAutomationPolicy
import com.fersaiyan.cyanbridge.ai.image.ExternalAssistantAutomationSetupActivity
import com.fersaiyan.cyanbridge.ai.router.AssistantSetupDestination
import com.fersaiyan.cyanbridge.ai.router.AssistantTestKind
import com.fersaiyan.cyanbridge.ai.router.AssistantTestReadiness
import com.fersaiyan.cyanbridge.shared.ai.GlassesAssistantRoute

/** Before a voice or image test question, sends the user to any setup the current route needs. */
internal class AssistantSetupGate(
    private val activity: AppCompatActivity,
    private val currentAssistantRoute: () -> GlassesAssistantRoute,
) {
    fun showAssistantSetupIfNeeded(kind: AssistantTestKind): Boolean {
        val issue = AssistantTestReadiness.blockingIssue(activity, currentAssistantRoute(), kind)
        if (issue != null) {
            AlertDialog.Builder(activity)
                .setTitle(issue.title)
                .setMessage(issue.message)
                .setNegativeButton("Not now", null)
                .setPositiveButton(issue.actionLabel) { _, _ ->
                    val destination = when (issue.destination) {
                        AssistantSetupDestination.LOCAL_MODELS -> LocalModelsConfigureActivity::class.java
                        AssistantSetupDestination.PRO_SUBSCRIPTION -> ProSubscriptionActivity::class.java
                    }
                    activity.startActivity(Intent(activity, destination))
                }
                .show()
            return true
        }
        if (currentAssistantRoute() == GlassesAssistantRoute.TASKER_EXTERNAL_UI) {
            val reason = taskerBlockingReason(activity, kind)
            if (reason != null) {
                AlertDialog.Builder(activity)
                    .setTitle("Tasker setup required")
                    .setMessage(reason)
                    .setNegativeButton("Not now", null)
                    .setPositiveButton("Open setup") { _, _ ->
                        activity.startActivity(Intent(activity, ExternalAssistantAutomationSetupActivity::class.java))
                    }
                    .show()
                return true
            }
        }
        return false
    }
}

private fun taskerBlockingReason(context: Context, kind: AssistantTestKind): String? {
    val capability = ExternalAssistantAutomationInspector.inspect(context)
    return when (kind) {
        AssistantTestKind.VOICE -> ExternalAssistantAutomationPolicy.voiceBlockingReason(capability)
        AssistantTestKind.IMAGE -> ExternalAssistantAutomationPolicy.imageBlockingReason(capability)
    }
}

/**
 * What [AssistantSetupGate] would show, as one message, for a question started without the main
 * screen (a glasses button in the background); null when nothing blocks it.
 */
internal fun assistantSetupBlockingMessage(context: Context, route: GlassesAssistantRoute, kind: AssistantTestKind): String? {
    AssistantTestReadiness.blockingIssue(context, route, kind)?.let { return it.message }
    return if (route == GlassesAssistantRoute.TASKER_EXTERNAL_UI) taskerBlockingReason(context, kind) else null
}
