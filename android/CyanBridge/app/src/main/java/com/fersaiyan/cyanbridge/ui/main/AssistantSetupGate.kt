package com.fersaiyan.cyanbridge.ui.main

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
import com.fersaiyan.cyanbridge.ai.router.GlassesAssistantRoute

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
            val capability = ExternalAssistantAutomationInspector.inspect(activity)
            val reason = when (kind) {
                AssistantTestKind.VOICE -> ExternalAssistantAutomationPolicy.voiceBlockingReason(capability)
                AssistantTestKind.IMAGE -> ExternalAssistantAutomationPolicy.imageBlockingReason(capability)
            }
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
