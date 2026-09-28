package com.fersaiyan.cyanbridge.localagent

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/** Opt-in, debug-only trace for comparing external models against *observed* YouTube states. */
object LocalAgentShadowTrace {
    const val PREFS_NAME = "local_agent_hil_shadow"
    const val PREF_ENABLED = "youtube_shadow_enabled"
    private const val TAG = "JevShadow"

    fun record(context: Context, state: LocalAgentTaskState, observation: LocalAgentObservation,
               built: UiActionCandidateBuilder.Built) {
        if (!BuildConfig.DEBUG || !context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_ENABLED, false) ||
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
