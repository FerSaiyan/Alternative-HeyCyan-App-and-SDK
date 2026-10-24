package com.fersaiyan.cyanbridge.ai.image

import android.content.Context
import com.fersaiyan.cyanbridge.shared.prefs.MediaPrefs.ExternalImageAutomation as Automation
import com.fersaiyan.cyanbridge.shared.prefs.edit
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import com.fersaiyan.cyanbridge.shared.prefs.store
import java.util.UUID

data class ExternalImageAutomationSession(
    val sessionId: String,
    val callbackToken: String,
    val imagePath: String,
    val imageUri: String,
    val question: String,
    val source: ImageQuestionSource,
    val state: ExternalImageAutomationState,
    val followUpPromptShown: Boolean,
)

object ExternalImageAutomationIntents {
    const val TASKER_PACKAGE = "net.dinglisch.android.taskerm"
    const val AUTO_INPUT_PACKAGE = "com.joaomgcd.autoinput"
    const val GEMINI_PACKAGE = "com.google.android.googlequicksearchbox"
    const val GEMINI_ALTERNATE_PACKAGE = "com.google.android.apps.bard"
    const val CHATGPT_PACKAGE = "com.openai.chatgpt"

    const val EXTRA_STATUS = "status"
    const val EXTRA_ERROR = "error"
    const val EXTRA_PROFILE_TARGET = "profile_target"
    const val EXTRA_PROFILE_VERSION = "profile_version"
    const val EXTRA_PROFILE_TOKEN = "profile_token"

    fun statusAction(packageName: String): String = "$packageName.AI_IMAGE_STATUS"
    fun profileAction(packageName: String): String = "$packageName.AI_IMAGE_PROFILE"
    fun internalStatusAction(packageName: String): String = "$packageName.AI_IMAGE_STATUS_UPDATED"
}

/** Persists enough state to diagnose callbacks after Gemini has taken the foreground. */
object ExternalImageAutomationStore {
    fun begin(
        context: Context,
        imagePath: String,
        imageUri: String,
        question: String,
        source: ImageQuestionSource,
    ): ExternalImageAutomationSession {
        val session = ExternalImageAutomationSession(
            sessionId = UUID.randomUUID().toString(),
            callbackToken = UUID.randomUUID().toString(),
            imagePath = imagePath,
            imageUri = imageUri,
            question = question,
            source = source,
            state = ExternalImageAutomationState(ExternalImageAutomationStage.IMAGE_STARTED),
            followUpPromptShown = false,
        )
        write(context, session)
        return session
    }

    fun current(context: Context): ExternalImageAutomationSession? {
        val values = Automation.store(context)
        val sessionId = values[Automation.session] ?: return null
        val token = values[Automation.token] ?: return null
        val path = values[Automation.path] ?: return null
        val uri = values[Automation.uri] ?: return null
        val source = ImageQuestionSource.entries.firstOrNull {
            it.wireName == values[Automation.source]
        } ?: return null
        val stage = ExternalImageAutomationStage.fromWireName(values[Automation.stage])
            ?: ExternalImageAutomationStage.IDLE
        return ExternalImageAutomationSession(
            sessionId = sessionId,
            callbackToken = token,
            imagePath = path,
            imageUri = uri,
            question = values[Automation.question],
            source = source,
            state = ExternalImageAutomationState(stage, values[Automation.error]),
            followUpPromptShown = values[Automation.followUpPromptShown],
        )
    }

    fun acceptsCallback(context: Context, sessionId: String?, callbackToken: String?): Boolean {
        val current = current(context) ?: return false
        return current.sessionId == sessionId && current.callbackToken == callbackToken
    }

    fun recordCallback(
        context: Context,
        stage: ExternalImageAutomationStage,
        error: String?,
    ): ExternalImageAutomationSession? {
        val current = current(context) ?: return null
        val nextState = ExternalImageAutomationStateMachine.transition(current.state, stage, error)
        if (nextState == current.state) return current
        return current.copy(state = nextState).also { write(context, it) }
    }

    fun recordLocalStage(
        context: Context,
        stage: ExternalImageAutomationStage,
        error: String? = null,
    ): ExternalImageAutomationSession? = recordCallback(context, stage, error)

    fun markFollowUpPromptShown(context: Context) {
        val current = current(context) ?: return
        if (!current.followUpPromptShown) write(context, current.copy(followUpPromptShown = true))
    }

    private fun write(context: Context, session: ExternalImageAutomationSession) {
        Automation.edit(context) {
            this[Automation.session] = session.sessionId
            this[Automation.token] = session.callbackToken
            this[Automation.path] = session.imagePath
            this[Automation.uri] = session.imageUri
            this[Automation.question] = session.question
            this[Automation.source] = session.source.wireName
            this[Automation.stage] = session.state.stage.wireName
            this[Automation.error] = session.state.error
            this[Automation.followUpPromptShown] = session.followUpPromptShown
        }
    }
}
