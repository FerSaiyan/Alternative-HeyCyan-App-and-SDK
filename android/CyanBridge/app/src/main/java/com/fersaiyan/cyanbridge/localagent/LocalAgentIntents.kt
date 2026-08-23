package com.fersaiyan.cyanbridge.localagent

/**
 * Intent contract between the app UI and the (optional) local agent runtime.
 *
 * The service implementation may not exist in all builds yet; callers must be resilient.
 */
object LocalAgentIntents {
    // Commands (UI -> Service)
    const val ACTION_START = "com.fersaiyan.cyanbridge.localagent.action.START"
    const val ACTION_STOP = "com.fersaiyan.cyanbridge.localagent.action.STOP"
    const val ACTION_DEMO = "com.fersaiyan.cyanbridge.localagent.action.DEMO"
    const val ACTION_READ_SCREEN_ALOUD = "com.fersaiyan.cyanbridge.localagent.action.READ_SCREEN_ALOUD"
    const val ACTION_GET_STATUS = "com.fersaiyan.cyanbridge.localagent.action.GET_STATUS"
    const val ACTION_APPROVAL_REPLY = "com.fersaiyan.cyanbridge.localagent.action.APPROVAL_REPLY"
    const val ACTION_RESUME_AFTER_APPROVAL = "com.fersaiyan.cyanbridge.localagent.action.RESUME_AFTER_APPROVAL"
    const val ACTION_TELEGRAM_REMOTE_START = "com.fersaiyan.cyanbridge.localagent.action.TELEGRAM_REMOTE_START"
    const val ACTION_TELEGRAM_REMOTE_STOP = "com.fersaiyan.cyanbridge.localagent.action.TELEGRAM_REMOTE_STOP"

    // Events (Service -> UI)
    const val ACTION_STATUS_CHANGED = "com.fersaiyan.cyanbridge.localagent.action.STATUS_CHANGED"
    const val ACTION_TELEGRAM_STATUS_CHANGED = "com.fersaiyan.cyanbridge.localagent.action.TELEGRAM_STATUS_CHANGED"

    // Extras
    const val EXTRA_STATUS = "status"
    const val EXTRA_LAST_ERROR = "last_error"
    const val EXTRA_GOAL = "goal"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_IS_TERMINAL = "is_terminal"
    const val EXTRA_USER_MESSAGE = "user_message"
    const val EXTRA_APPROVAL_REPLY = "approval_reply"
    const val EXTRA_DISABLE_REMOTE = "disable_remote"
    const val EXTRA_REJECTED = "rejected"
}
