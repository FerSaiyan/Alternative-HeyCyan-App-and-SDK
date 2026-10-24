package com.fersaiyan.cyanbridge.shared.platform

/**
 * Platform hooks for the shared chat and notes destinations. The iOS host
 * installs them; Android keeps its own Activities and leaves them unset.
 */
object SharedChatHooks {
    /** Thread to open when the chats tab first appears (screenshot harness / deep links). */
    var initialThreadId: String? = null

    /** Returns a JPEG chosen by the user, or null when cancelled. */
    var pickImage: (suspend () -> ByteArray?)? = null

    /** Starts microphone capture; false when permission is denied. */
    var startAudioRecording: (suspend () -> Boolean)? = null

    /** Stops capture and returns the recording encoded as [audioMimeType]. */
    var stopAudioRecording: (suspend () -> ByteArray?)? = null
    var audioMimeType: String = "audio/mp4"

    var copyText: ((String) -> Unit)? = null
    var shareText: ((String) -> Unit)? = null
}
