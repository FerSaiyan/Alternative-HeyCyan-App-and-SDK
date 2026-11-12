package com.fersaiyan.cyanbridge.ai.live

/**
 * Chooses how a photo question goes out: as one ordered `clientContent` turn (image, then text)
 * or as the older realtime frame + [GeminiLiveClient.IMAGE_INGEST_WAIT_MS] wait + text turn.
 *
 * The single turn is used only on a connection whose server announced
 * [GeminiLiveServerEvent.ProxyCapabilities] (CyanBridge's Free/Economy proxy, asked with
 * `client_turns=image`) and only for an image within the announced size. Google's direct
 * Private endpoint and older proxies never announce it, so they keep the wait path.
 *
 * If a connection closes as refused (1008 "Unsupported Live message", or 1007 invalid argument
 * from Google) while a single turn is still unanswered, single turns are switched off for the
 * rest of the process and the app does not ask for them again.
 */
internal class GeminiLiveImageTurns(private val rejection: Rejection = Rejection.PROCESS) {
    /** Process-wide memory of a refused single turn; tests pass their own instance. */
    class Rejection {
        @Volatile
        var rejected: Boolean = false
            internal set

        companion object {
            val PROCESS = Rejection()
        }
    }

    @Volatile private var maxImageBytes: Int = 0
    @Volatile private var awaitingAnswer = false

    /** Whether the next connection should ask the proxy for its capabilities. */
    val requestCapabilities: Boolean get() = !rejection.rejected

    /** A new socket: nothing is known about the server yet. */
    fun onConnecting() {
        maxImageBytes = 0
        awaitingAnswer = false
    }

    fun onCapabilities(event: GeminiLiveServerEvent.ProxyCapabilities) {
        maxImageBytes = if (event.clientImageTurns && event.maxImagesPerTurn >= 1) event.maxImageBytes.coerceAtLeast(0) else 0
    }

    /** True when [imageBytes] and its non-blank prompt can go as one ordered client turn. */
    fun useSingleTurn(imageBytes: Int, hasPrompt: Boolean): Boolean =
        hasPrompt && !rejection.rejected && imageBytes in 1..maxImageBytes

    fun onSingleTurnSent() {
        awaitingAnswer = true
    }

    /** Any model output after a single turn shows the server accepted it. */
    fun onModelResponse() {
        awaitingAnswer = false
    }

    /** Returns true when this close means the server refused the single turn. */
    fun onSocketClosed(code: Int?, reason: String?): Boolean {
        val refused = awaitingAnswer && (
            (code == 1008 && reason.orEmpty().contains("Unsupported Live message", ignoreCase = true)) || code == 1007
            )
        awaitingAnswer = false
        maxImageBytes = 0
        if (refused) rejection.rejected = true
        return refused
    }
}
