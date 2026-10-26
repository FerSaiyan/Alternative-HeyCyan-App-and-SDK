package com.fersaiyan.cyanbridge.ai.router

import android.util.Log
import com.fersaiyan.cyanbridge.ai.decision.AssistantDecisionOptions
import com.fersaiyan.cyanbridge.ai.decision.HeuristicDecisionEngine
import com.fersaiyan.cyanbridge.localmodels.session.LocalChatSessionManager
import kotlinx.coroutines.CancellationException
import java.util.Locale

/**
 * SHADOW mode: logs what the decision model would have chosen for a question that the classic
 * route already handled. Uses only free scorers (regex heuristics) and the already-loaded chat
 * model's label logits (prototype builds), never an extra generation. Logs labels and scores,
 * never the question text.
 */
object QuestionDecisionShadow {
    private const val TAG = "QuestionDecision"

    suspend fun log(text: String, flow: String, classic: AssistantIntent) {
        val heuristic = HeuristicDecisionEngine.classifyAssistantRequest(text)?.let(::label) ?: "none"
        val model = try {
            LocalChatSessionManager.scoreSharedGemmaShadow(text.trim(), AssistantDecisionOptions.candidates())
                ?.let { "${label(it.index)} p=${fmt(it.confidence)} margin=${fmt(it.margin)}" }
                ?: "unavailable"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            "failed:${error.javaClass.simpleName}"
        }
        Log.i(TAG, "QUESTION_DECISION_SHADOW flow=$flow classic=$classic heuristic=$heuristic model=$model")
    }

    private fun label(index: Int) = when (index) {
        0 -> AssistantIntent.ANSWER_QUESTION.name
        1 -> AssistantIntent.ANALYZE_IMAGE.name
        2 -> AssistantIntent.EXECUTE_UI_TASK.name
        else -> AssistantIntent.CLARIFY.name
    }

    private fun fmt(value: Float) = String.format(Locale.US, "%.3f", value)
}
