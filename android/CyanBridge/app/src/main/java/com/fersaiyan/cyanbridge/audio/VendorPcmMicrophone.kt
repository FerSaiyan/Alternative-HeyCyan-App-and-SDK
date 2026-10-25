package com.fersaiyan.cyanbridge.audio

import android.content.Context
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.mentra.MentraLiveManager
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.GlassesAudioInput
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles

/**
 * A glasses microphone that arrives as PCM through the vendor SDK instead of an Android input
 * device (profiles with [GlassesAudioInput.VENDOR_PCM_STREAM]). Callers still hold an
 * [AudioSessionHandle] for arbitration; this only replaces the recorder.
 */
interface VendorPcmMicrophone {
    /** Sample rate of the 16-bit mono little-endian PCM delivered to listeners. */
    val sampleRateHz: Int

    /** Starts delivering PCM to [onPcm]; throws when the glasses cannot stream now. */
    fun open(owner: String, onPcm: (ByteArray) -> Unit)

    fun close(owner: String)

    companion object {
        /** The selected glasses' SDK microphone, when their profile streams PCM and they are ready. */
        fun forSelectedGlasses(context: Context): VendorPcmMicrophone? {
            val deviceClass = DeviceProfileStore.selectedClass(context)
            if (GlassesFamilyProfiles.forClass(deviceClass).audio.input != GlassesAudioInput.VENDOR_PCM_STREAM) return null
            return when (deviceClass) {
                DeviceClass.MENTRA_LIVE -> MentraLiveManager.instanceOrNull()?.takeIf { it.ready }?.let(::MentraMicrophone)
                else -> null
            }
        }
    }
}

private class MentraMicrophone(private val manager: MentraLiveManager) : VendorPcmMicrophone {
    override val sampleRateHz: Int = MentraLiveManager.PCM_SAMPLE_RATE_HZ

    override fun open(owner: String, onPcm: (ByteArray) -> Unit) = manager.openMicrophone(owner, onPcm)

    override fun close(owner: String) = manager.closeMicrophone(owner)
}
