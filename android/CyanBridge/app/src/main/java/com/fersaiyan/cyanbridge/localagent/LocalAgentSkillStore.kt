package com.fersaiyan.cyanbridge.localagent

import android.content.Context
import com.fersaiyan.cyanbridge.localagent.actions.LocalAgentActionManager
import com.fersaiyan.cyanbridge.shared.prefs.AgentPrefs
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.remove
import com.fersaiyan.cyanbridge.shared.prefs.set
import org.json.JSONArray
import org.json.JSONObject

/**
 * Replays only previously successful low-risk navigation sequences for an exact user goal.
 * Higher-risk actions still go through the normal model and approval path.
 */
object LocalAgentSkillStore {
    data class Skill(
        val normalizedGoal: String,
        val actions: List<LocalAgentAction>,
        val createdAtMs: Long,
        val replayFailures: Int = 0,
    )

    private const val MAX_SKILLS = 12
    private const val MAX_REPLAY_FAILURES = 2

    fun findExact(context: Context, goal: String): Skill? {
        val normalized = normalizeGoal(goal)
        if (normalized.isBlank()) return null
        return load(context).lastOrNull { it.normalizedGoal == normalized && it.actions.isNotEmpty() }
    }

    fun recordSuccessful(context: Context, goal: String, actions: List<LocalAgentAction>) {
        val normalized = normalizeGoal(goal)
        val replayable = actions.filterNot { it is LocalAgentAction.Finish }
        if (normalized.isBlank() || replayable.isEmpty() || replayable.any(::isUnsafeToReplay)) return

        val skills = load(context).filterNot { it.normalizedGoal == normalized }.toMutableList()
        skills += Skill(
            normalizedGoal = normalized,
            actions = replayable,
            createdAtMs = System.currentTimeMillis(),
        )
        save(context, skills.takeLast(MAX_SKILLS))
    }

    fun recordReplayFailure(context: Context, goal: String) {
        val normalized = normalizeGoal(goal)
        if (normalized.isBlank()) return
        val updated = load(context).mapNotNull { skill ->
            if (skill.normalizedGoal != normalized) return@mapNotNull skill
            val failures = skill.replayFailures + 1
            if (failures >= MAX_REPLAY_FAILURES) null else skill.copy(replayFailures = failures)
        }
        save(context, updated)
    }

    fun clear(context: Context) {
        AgentPrefs.Skills.skillsJson.remove(context)
    }

    private fun isUnsafeToReplay(action: LocalAgentAction): Boolean {
        return LocalAgentActionManager.classifyRisk(action) != LocalAgentActionManager.Risk.LOW
    }

    private fun load(context: Context): List<Skill> {
        val raw = AgentPrefs.Skills.skillsJson[context]
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val goal = item.optString("goal", "").trim()
                val encodedActions = item.optJSONArray("actions") ?: continue
                val actions = buildList {
                    for (actionIndex in 0 until encodedActions.length()) {
                        val encoded = encodedActions.optString(actionIndex, "")
                        LocalAgentActionParser.parseList(encoded).singleOrNull()?.let(::add)
                    }
                }
                if (goal.isBlank() || actions.isEmpty() || actions.any(::isUnsafeToReplay)) continue
                add(
                    Skill(
                        normalizedGoal = goal,
                        actions = actions,
                        createdAtMs = item.optLong("created_at_ms", 0L),
                        replayFailures = item.optInt("replay_failures", 0).coerceAtLeast(0),
                    ),
                )
            }
        }
    }

    private fun save(context: Context, skills: List<Skill>) {
        val array = JSONArray()
        skills.forEach { skill ->
            val actions = JSONArray()
            skill.actions.forEach { actions.put(LocalAgentActionManager.serializeAction(it)) }
            array.put(
                JSONObject()
                    .put("goal", skill.normalizedGoal)
                    .put("actions", actions)
                    .put("created_at_ms", skill.createdAtMs)
                    .put("replay_failures", skill.replayFailures),
            )
        }
        AgentPrefs.Skills.skillsJson[context] = array.toString()
    }

    private fun normalizeGoal(goal: String): String =
        goal.trim().lowercase().replace(Regex("\\s+"), " ").take(MAX_GOAL_CHARS)

    private const val MAX_GOAL_CHARS = 240
}
