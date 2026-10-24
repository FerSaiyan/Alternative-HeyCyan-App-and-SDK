package com.fersaiyan.cyanbridge.localagent

/**
 * Minimal action model for the local agent step engine.
 *
 * Actions are expected to be provided as JSON objects with a "type" field.
 * Example:
 *   [{"type":"sleep","ms":250},{"type":"global_back"}]
 */
sealed interface LocalAgentAction {
    data class Wait(val ms: Long) : LocalAgentAction

    data object GlobalBack : LocalAgentAction
    data object GlobalHome : LocalAgentAction

    /** Best-effort: find a node with matching visible text and click it. */
    data class ClickText(val text: String) : LocalAgentAction

    /** Gesture-based absolute tap. */
    data class ClickCoord(val x: Int, val y: Int) : LocalAgentAction

    /** Best-effort: set text into a focused (or first editable) field. */
    data class TypeText(
        val text: String,
        val hint: String? = null,
    ) : LocalAgentAction

    /** Submits the focused editor through the IME when the app exposes one. */
    data object PressEnter : LocalAgentAction

    data class Scroll(val direction: Direction) : LocalAgentAction

    /** Launches an app using the package manager. */
    data class OpenApp(val appName: String) : LocalAgentAction

    /** Terminal logical action; not executed by Accessibility. */
    data class Finish(val message: String? = null) : LocalAgentAction

    /** Arbitrary swipe gesture from (startX,startY) to (endX,endY). */
    data class Swipe(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        val durationMs: Long = 300L,
    ) : LocalAgentAction

    /** Long press at coordinates. */
    data class LongPress(
        val x: Int,
        val y: Int,
        val durationMs: Long = 1000L,
    ) : LocalAgentAction

    /** Open the notification shade. */
    data object OpenNotifications : LocalAgentAction

    /** Open the recent apps screen. */
    data object OpenRecents : LocalAgentAction

    /** Open the dialer with a phone number; no CALL_PHONE permission is required. */
    data class MakeCall(val number: String) : LocalAgentAction

    /** Send an SMS via ACTION_SENDTO (smsto:). */
    data class SendSms(
        val number: String,
        val message: String,
    ) : LocalAgentAction

    /** Set an alarm via ACTION_SET_ALARM. */
    data class SetAlarm(
        val hour: Int,
        val minute: Int,
        val label: String? = null,
    ) : LocalAgentAction

    /** Open the contacts app. */
    data object OpenContacts : LocalAgentAction

    /** Toggle Wi-Fi on/off. */
    data object ToggleWifi : LocalAgentAction

    /** Toggle Bluetooth on/off. */
    data object ToggleBluetooth : LocalAgentAction

    /** Toggle flashlight on/off. */
    data object ToggleFlashlight : LocalAgentAction

    /** Send an email via ACTION_SENDTO mailto: (requires user confirmation in email app). */
    data class SendEmail(
        val to: String,
        val subject: String,
        val body: String
    ) : LocalAgentAction

    /** Reads bounded visible screen text using the phone's current TTS output route. */
    data object ReadScreenAloud : LocalAgentAction

    enum class Direction {
        UP,
        DOWN,
    }
}
