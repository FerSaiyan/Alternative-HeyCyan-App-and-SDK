package com.fersaiyan.cyanbridge.shared.audio

/**
 * Decodes a stream of raw Opus packets (no Ogg container) to mono PCM16LE at [sampleRateHz].
 * One instance per capture; not thread-safe. The packet framing (MemoMind's recording records)
 * is parsed in shared code; only this step is platform code (Android: `MediaCodec`).
 */
interface OpusPacketDecoder {
    val sampleRateHz: Int

    /** Prepares a fresh decoder; call before the first [decode]. */
    fun start()

    /** Decodes one packet and returns the PCM that became available (may be empty at first). */
    fun decode(packet: ByteArray): ByteArray

    fun release()
}
