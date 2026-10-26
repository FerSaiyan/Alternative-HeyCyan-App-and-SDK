package com.fersaiyan.cyanbridge.audio

import android.content.Context
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.devices.mentra.MentraLiveManager
import com.fersaiyan.cyanbridge.shared.audio.AudioRoutePolicy
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.devices.GlassesAudioOutput
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfiles

/**
 * Glasses whose SDK must be told while the app plays audio to them (profiles with
 * [GlassesAudioOutput.BLUETOOTH_WITH_PLAYBACK_SIGNAL]).
 */
fun interface VendorPlaybackSignal {
    fun setAppAudioPlaying(playing: Boolean)

    companion object {
        /** The selected glasses' playback signal, when their profile needs one and their SDK is running. */
        fun forSelectedGlasses(context: Context): VendorPlaybackSignal? {
            val deviceClass = DeviceProfileStore.selectedClass(context)
            if (!AudioRoutePolicy.needsPlaybackSignal(GlassesFamilyProfiles.forClass(deviceClass).audio)) return null
            return when (deviceClass) {
                DeviceClass.MENTRA_LIVE -> MentraLiveManager.instanceOrNull()?.let { manager ->
                    VendorPlaybackSignal { playing -> manager.setOwnAudioPlaying(playing) }
                }
                else -> null
            }
        }
    }
}
