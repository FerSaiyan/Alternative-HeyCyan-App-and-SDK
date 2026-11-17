package com.fersaiyan.cyanbridge.shared.customization

import kotlinx.coroutines.Deferred

/** One glasses-microphone take, decoded to 16 kHz mono PCM16LE. */
interface GlassesMicCapture {
    /**
     * Opens the glasses microphone and records until [maxMs], until [stop] completes, or until the
     * glasses stop streaming; always closes the microphone again. [onPcm] receives decoded chunks
     * for the level meter. Throws when the glasses send no audio.
     */
    suspend fun record(maxMs: Long, stop: Deferred<Unit>, onPcm: (ByteArray) -> Unit): ByteArray
}

sealed class GlassesDetection {
    data class Found(val glasses: GlassesFirmwareInfo) : GlassesDetection()
    data class Missing(val message: String) : GlassesDetection()
}

data class PickedDocument(val name: String, val mimeType: String?, val bytes: ByteArray)

data class HttpResult(val status: Int, val body: String) {
    val ok: Boolean get() = status in 200..299
}

/** What the customization card needs from the platform: relay HTTP, files, the glasses. */
interface CustomizationPlatform {
    /** The locally verified Pro plan id (`standard`, `max`, `cheap`, ...). */
    fun plan(): String

    /** Finds the connected glasses and reads their base firmware versions. */
    suspend fun detectGlasses(): GlassesDetection

    /** A relay API call with the account token; [path] starts with `/api/`. */
    suspend fun api(method: String, path: String, body: String? = null): HttpResult

    /**
     * A relay API call with the account token and a raw byte body ([bytes], sent with a fixed
     * length as [contentType], plus [headers]); used for the asset uploads.
     */
    suspend fun apiBytes(method: String, path: String, contentType: String, headers: Map<String, String>, bytes: ByteArray): HttpResult

    fun sha256Hex(bytes: ByteArray): String

    /** Reads a document the user picked ([ref] is the platform's document reference). */
    suspend fun readDocument(ref: String): PickedDocument

    fun micCapture(family: CustomizationFamily): GlassesMicCapture

    /**
     * Installs the emailed custom firmware [ref] on [glasses] through that family's update path.
     * [progress] receives status lines. Returns the final message; throws with a readable message
     * on failure.
     */
    suspend fun installFirmware(ref: String, glasses: GlassesFirmwareInfo, progress: (String) -> Unit): String
}
