package com.fersaiyan.cyanbridge.ui.main

import android.widget.Toast
import com.fersaiyan.cyanbridge.localagent.LocalAgentController
import com.fersaiyan.cyanbridge.localagent.LocalAgentPrefs

/** Optional Local Agent controls and status on the main screen. */
internal class AgentControlsUiController(host: MainScreenHost) : MainScreenController(host) {
    fun setupAgentControlsUi() {
        binding.btnAgentStart.text = "Agent status"
        binding.btnAgentStart.setOnClickListener {
            Toast.makeText(activity, "Enter a goal before starting the agent.", Toast.LENGTH_SHORT).show()
            LocalAgentController.requestStatus(activity)
        }

        binding.btnAgentStop.setOnClickListener {
            val res = LocalAgentController.stop(activity)
            if (res.ok) {
                LocalAgentPrefs.setStatus(activity, "Stopping…")
                LocalAgentPrefs.clearLastError(activity)
            } else {
                LocalAgentPrefs.setStatus(activity, "Error")
                LocalAgentPrefs.setLastError(activity, res.error ?: res.userMessage)
            }
            refreshAgentStatusUi()
            Toast.makeText(activity, res.userMessage, Toast.LENGTH_SHORT).show()
            LocalAgentController.requestStatus(activity)
        }

        binding.btnAgentDemo.setOnClickListener {
            Toast.makeText(
                activity,
                "Demo: I will read the screen content through your glasses in 5 seconds…",
                Toast.LENGTH_LONG
            ).show()

            val res = LocalAgentController.demo(activity)
            if (res.ok) {
                LocalAgentPrefs.setStatus(activity, "Running demo…")
                LocalAgentPrefs.clearLastError(activity)
            } else {
                LocalAgentPrefs.setStatus(activity, "Error")
                LocalAgentPrefs.setLastError(activity, res.error ?: res.userMessage)
            }
            refreshAgentStatusUi()
            Toast.makeText(activity, res.userMessage, Toast.LENGTH_SHORT).show()
            LocalAgentController.requestStatus(activity)
        }

        refreshAgentStatusUi()
    }

    fun refreshAgentStatusUi() {
        binding.tvAgentStatus.text = "Status: ${LocalAgentPrefs.getStatus(activity)}"
        binding.tvAgentLastError.text = "Last error: ${LocalAgentPrefs.getLastError(activity)}"
        updateDashboardState { state ->
            state.copy(
                agentStatus = LocalAgentPrefs.getStatus(activity),
                agentLastError = LocalAgentPrefs.getLastError(activity),
            )
        }
    }
}
