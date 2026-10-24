package com.fersaiyan.cyanbridge.localagent

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.shared.prefs.AgentPrefs
import com.fersaiyan.cyanbridge.shared.prefs.get
import org.json.JSONArray
import org.json.JSONObject

/** Opt-in, debug-only trace for comparing external models against *observed* YouTube states. */
object LocalAgentShadowTrace {
    private const val TAG = "JevShadow"

    fun record(context: Context, state: LocalAgentTaskState, observation: LocalAgentObservation,
               built: UiActionCandidateBuilder.Built) {
        if (!BuildConfig.DEBUG || !AgentPrefs.ShadowTrace.youtubeShadowEnabled[context] ||
            !state.goal.contains("Linus Tech Tips", ignoreCase = true)) return

        val screen = observation.screenSnapshot?.toCompressedPromptText(state.goal)
            ?: observation.screenText.orEmpty()
        val options = JSONArray()
        built.candidates.forEachIndexed { index, candidate ->
            options.put(JSONObject().put("label", candidate.label)
                .put("description", candidate.description.take(160))
                .put("key", built.keys[index]))
        }
        val payload = JSONObject()
            .put("step", state.stepIndex)
            .put("goal", state.goal)
            .put("package", observation.packageName.orEmpty())
            .put("state", UiActionDecisionState.build(state.goal, observation, screen,
                state.previousActionResult))
            .put("candidates", options)
        Log.i(TAG, "JEV_SHADOW_STATE=$payload")
    }
}
