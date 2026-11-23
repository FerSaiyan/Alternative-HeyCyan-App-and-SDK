package com.fersaiyan.cyanbridge.ai.live

import org.json.JSONArray
import org.json.JSONObject

/**
 * Function tools Gemini Live may call. The relay declares them in the setup it constrains into the
 * session (website `lib/gemini-live.ts` GEMINI_LIVE_TOOL_DECLARATIONS); the app only names the ones
 * it handles. [declarations] mirrors the relay for setups the client builds itself (no relay setup).
 */
internal object GeminiLiveTools {
    const val END_SESSION = "end_session"
    const val PHONE_TASK = "phone_task"
    const val PHONE_TASK_ARG = "task"

    /** Query parameter / body field carrying the requested tool names. */
    const val REQUEST_FIELD = "tools"

    fun declarations(names: List<String>): JSONArray? {
        val functions = JSONArray()
        if (END_SESSION in names) functions.put(
            JSONObject()
                .put("name", END_SESSION)
                .put(
                    "description",
                    "End this Live conversation. Call it only when the user clearly asks to stop or end the conversation, " +
                        "or says goodbye to end it. Never call it for questions that only mention stopping or arriving. " +
                        "Say a short goodbye before calling it.",
                ),
        )
        if (PHONE_TASK in names) functions.put(
            JSONObject()
                .put("name", PHONE_TASK)
                .put(
                    "description",
                    "Ask the user's phone agent to operate the phone or an app for them (open an app, play music, send a message, " +
                        "set an alarm). Use it only when the user asks you to do something on their phone, not for questions about " +
                        "how to do it. The agent asks the user to confirm calls, messages, email and alarms.",
                )
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "OBJECT")
                        .put(
                            "properties",
                            JSONObject().put(
                                PHONE_TASK_ARG,
                                JSONObject().put("type", "STRING").put("description", "The task in the user's words, complete enough to act on."),
                            ),
                        )
                        .put("required", JSONArray().put(PHONE_TASK_ARG)),
                ),
        )
        return if (functions.length() == 0) null else JSONArray().put(JSONObject().put("functionDeclarations", functions))
    }
}
