package com.fersaiyan.cyanbridge.glasses.offlineauth

/**
 * A device response to an auth/bind command frame: the opcode it answers and its raw payload.
 * Payload meaning depends on the exchange (capability byte-14, a 16-byte challenge, a bind result…).
 */
data class AuthResponse(val opcode: Int, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is AuthResponse && opcode == other.opcode && payload.contentEquals(other.payload))

    override fun hashCode(): Int = 31 * opcode + payload.contentHashCode()
}

/**
 * The seam between the pure offline-auth state machine and whatever actually moves bytes to the
 * glasses. The production implementation drives the vendor SDK (`LargeDataHandler.glassesControl`);
 * the tests drive an in-JVM simulated post-auth unit. Keeping the state machine behind this seam is
 * what lets the full unlock be validated end-to-end with no device and no server.
 */
interface GlassesAuthTransport {

    /** False short-circuits the whole flow to [OfflineUnlockResult.NOT_CONNECTED]. */
    fun isConnected(): Boolean

    /**
     * Sends one command [frame] and returns the device's response. Implementations must throw on a
     * transport failure/timeout (the state machine maps that to [OfflineUnlockResult.TRANSPORT_ERROR]).
     */
    suspend fun send(frame: ByteArray): AuthResponse

    /**
     * UX hook fired right before the ~20 s front-button bind window opens, so the user is told to
     * press the glasses' front button (the real, non-forgeable consent step). Default: no-op.
     */
    fun onPromptFrontButton() {}
}
