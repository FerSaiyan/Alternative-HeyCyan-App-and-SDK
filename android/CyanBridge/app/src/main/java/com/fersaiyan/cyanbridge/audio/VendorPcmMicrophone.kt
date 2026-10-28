package com.fersaiyan.cyanbridge.audio

import android.content.Context
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.memomind.MemoMindManager
import com.fersaiyan.cyanbridge.devices.memomind.MemoMindMicrophone
import com.fersaiyan.cyanbridge.devices.memomind.MemoMindOpusDecoder
import com.fersaiyan.cyanbridge.devices.mentra.MentraLiveManager
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.GlassesAudioInput
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles
import java.util.concurrent.ConcurrentHashMap

/**
 * A glasses microphone that arrives as PCM through the vendor SDK instead of an Android input
 * device (profiles whose questions record [GlassesAudioInput.VENDOR_PCM_STREAM]). Callers still
 * hold an [AudioSessionHandle] for arbitration; this only replaces the recorder.
 */
interface VendorPcmMicrophone {
    /** Sample rate of the 16-bit mono little-endian PCM delivered to listeners. */
    val sampleRateHz: Int

    /**
     * Starts delivering PCM to [onPcm] and returns once the glasses stream (MemoMind: after the
     * recording link started); throws when the glasses cannot stream now.
     */
    suspend fun open(owner: String, onPcm: (ByteArray) -> Unit)

    fun close(owner: String)

    companion object {
        /**
         * The selected glasses' SDK microphone for questions, when their profile records questions
         * from it and it can stream now; null sends the question to the profile's fallback route.
         */
        fun forSelectedGlasses(context: Context): VendorPcmMicrophone? {
            val deviceClass = DeviceProfileStore.selectedClass(context)
            if (GlassesFamilyProfiles.forClass(deviceClass).audio.questionInput != GlassesAudioInput.VENDOR_PCM_STREAM) return null
            return when (deviceClass) {
                DeviceClass.MENTRA_LIVE -> MentraLiveManager.instanceOrNull()?.takeIf { it.ready }?.let(::MentraMicrophone)
                DeviceClass.MEMOMIND -> MemoMindManager.instanceOrNull()
                    ?.takeIf { it.ready.value && it.session.recordingAvailable }
                    ?.let { MemoMindOpusMicrophone(it.microphone) }
                else -> null
            }
        }
    }
}

private class MentraMicrophone(private val manager: MentraLiveManager) : VendorPcmMicrophone {
    override val sampleRateHz: Int = MentraLiveManager.PCM_SAMPLE_RATE_HZ

    override suspend fun open(owner: String, onPcm: (ByteArray) -> Unit) = manager.openMicrophone(owner, onPcm)

    override fun close(owner: String) = manager.closeMicrophone(owner)
}

/** MemoMind's Opus recording link, decoded to 16 kHz PCM, inside the caller's audio session. */
private class MemoMindOpusMicrophone(private val microphone: MemoMindMicrophone) : VendorPcmMicrophone {
    private val captures = ConcurrentHashMap<String, MemoMindMicrophone.Capture>()

    override val sampleRateHz: Int = MemoMindOpusDecoder.SAMPLE_RATE

    override suspend fun open(owner: String, onPcm: (ByteArray) -> Unit) {
        val capture = microphone.capture(onPcm = { pcm -> onPcm(pcm) })
        captures.put(owner, capture)?.close()
        capture.start()
    }

    override fun close(owner: String) {
        captures.remove(owner)?.close()
    }
}
