package com.fersaiyan.cyanbridge.shared.devices

import com.fersaiyan.cyanbridge.bridge.core.GlassesCapability
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlassesFamilyProfileTest {
    private fun classesWith(capability: GlassesCapability): Set<DeviceClass> =
        GlassesFamilyProfiles.all.filter { it.supports(capability) }.map { it.deviceClass }.toSet()

    @Test
    fun everyDeviceClassHasExactlyOneProfile() {
        assertEquals(DeviceClass.entries, GlassesFamilyProfiles.all.map { it.deviceClass })
        DeviceClass.entries.forEach { assertEquals(it, GlassesFamilyProfiles.forClass(it).deviceClass) }
        assertEquals(DeviceClass.UNKNOWN, GlassesFamilyProfiles.forProfile(null).deviceClass)
    }

    @Test
    fun cameraAvailabilityMatchesTheShippedPluginGating() {
        assertEquals(
            setOf(
                DeviceClass.HEY_CYAN,
                DeviceClass.EYEVUE,
                DeviceClass.TUNEBUDS,
                DeviceClass.META_RAYBAN,
                DeviceClass.MENTRA_LIVE,
                DeviceClass.UNKNOWN,
            ),
            GlassesFamilyProfiles.all.filter { it.hasCamera }.map { it.deviceClass }.toSet(),
        )
        assertEquals(
            "Selected device profile (Meizu MYVU) has no camera.",
            GlassesFamilyProfiles.meizuMyvu.cameraUnavailableReason,
        )
        assertEquals(
            "Selected device profile (Earbuds / Audio-only glasses) has no camera.",
            GlassesFamilyProfiles.genericAudio.cameraUnavailableReason,
        )
        assertEquals(
            "The MoYoung / W620 adapter supports media sync, but direct camera capture is not validated yet.",
            GlassesFamilyProfiles.moyoungW620.cameraUnavailableReason,
        )
        listOf(GlassesFamilyProfiles.heyCyan, GlassesFamilyProfiles.eyevue, GlassesFamilyProfiles.unknown)
            .forEach { assertNull(it.cameraUnavailableReason) }
    }

    @Test
    fun capabilityOwnersMatchTheShippedHandlers() {
        assertEquals(
            setOf(DeviceClass.HEY_CYAN, DeviceClass.EYEVUE, DeviceClass.TUNEBUDS),
            classesWith(GlassesCapability.LIVE_PREVIEW),
        )
        assertEquals(
            setOf(DeviceClass.HEY_CYAN, DeviceClass.EYEVUE, DeviceClass.TUNEBUDS, DeviceClass.MENTRA_LIVE),
            classesWith(GlassesCapability.AI_PHOTO_CAPTURE),
        )
        assertEquals(setOf(DeviceClass.HEY_CYAN, DeviceClass.EYEVUE), classesWith(GlassesCapability.WAKE_WORD))
        assertEquals(
            setOf(DeviceClass.HEY_CYAN, DeviceClass.TUNEBUDS),
            classesWith(GlassesCapability.IMAGE_QUALITY_SELECTION),
        )
        assertEquals(setOf(DeviceClass.EYEVUE), classesWith(GlassesCapability.WEARING_DETECTION))
        assertEquals(setOf(DeviceClass.HEY_CYAN), classesWith(GlassesCapability.WIFI_ADB_DEBUG))
        assertEquals(
            setOf(
                DeviceClass.HEY_CYAN, DeviceClass.EYEVUE, DeviceClass.TUNEBUDS, DeviceClass.MOYOUNG_W620,
                DeviceClass.MENTRA_LIVE,
            ),
            classesWith(GlassesCapability.MEDIA_SYNC),
        )
    }

    @Test
    fun onlyTuneBudsLimitsItsDashboardToDeclaredCapabilities() {
        assertEquals(
            listOf(DeviceClass.TUNEBUDS),
            GlassesFamilyProfiles.all.filter { it.dashboardActionsLimitedToCapabilities }.map { it.deviceClass },
        )
    }

    @Test
    fun metaRejectsHeyCyanSdkControlsWithTheShippedMessage() {
        assertEquals(
            listOf(DeviceClass.META_RAYBAN),
            GlassesFamilyProfiles.all.filter { it.heyCyanSdkControlsRejection != null }.map { it.deviceClass },
        )
        assertEquals(
            "Battery status is unavailable for Meta Ray-Ban. Use the DAT controls instead.",
            GlassesFamilyProfiles.metaRayban.heyCyanSdkControlsRejection!!.message("Battery status"),
        )
        assertEquals(
            listOf(DeviceClass.META_RAYBAN),
            GlassesFamilyProfiles.all.filter { it.voiceQueriesBypassCameraSession }.map { it.deviceClass },
        )
    }

    @Test
    fun unsupportedNativePluginsKeepTheirMessages() {
        val myvu = "This plugin requires a camera or HeyCyan onboard media. MYVU supports display and microphone plugins only."
        assertEquals(
            mapOf(
                NativePluginIds.AUTO_AUDIO to myvu,
                NativePluginIds.VISUAL_DIARY to myvu,
                NativePluginIds.WALKING_AID to myvu,
            ),
            GlassesFamilyProfiles.meizuMyvu.unsupportedNativePlugins,
        )
        assertEquals(
            mapOf(NativePluginIds.AUTO_AUDIO to "Auto Audio records HeyCyan onboard files and is unavailable for Meta Ray-Ban."),
            GlassesFamilyProfiles.metaRayban.unsupportedNativePlugins,
        )
        assertEquals(
            setOf(NativePluginIds.AUTO_AUDIO, NativePluginIds.VISUAL_DIARY, NativePluginIds.WALKING_AID),
            GlassesFamilyProfiles.memoMind.unsupportedNativePlugins.keys,
        )
        val withMessages = setOf(DeviceClass.MEIZU_MYVU, DeviceClass.META_RAYBAN, DeviceClass.MEMOMIND)
        GlassesFamilyProfiles.all
            .filter { it.deviceClass !in withMessages }
            .forEach { assertTrue(it.unsupportedNativePlugins.isEmpty(), "${it.deviceClass}") }
    }

    @Test
    fun audioRoutingHints() {
        assertEquals(GlassesAudioInput.VENDOR_PCM_STREAM, GlassesFamilyProfiles.mentraLive.audio.input)
        assertEquals(GlassesAudioOutput.BLUETOOTH_WITH_PLAYBACK_SIGNAL, GlassesFamilyProfiles.mentraLive.audio.output)
        GlassesFamilyProfiles.all.forEach { profile ->
            assertEquals(profile.supports(GlassesCapability.MICROPHONE_AUDIO), profile.audio.hasMicrophone, "${profile.deviceClass}")
            assertEquals(profile.supports(GlassesCapability.SPEAKER_AUDIO), profile.audio.hasSpeaker, "${profile.deviceClass}")
        }
    }

    @Test
    fun onlyTuneBudsDropsActionsOutsideItsCapabilities() {
        val ota = com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardAction.CancelOta
        GlassesFamilyProfiles.all.forEach { profile ->
            assertEquals(
                profile.deviceClass != DeviceClass.TUNEBUDS,
                GlassesManagerGating.isActionSupported(profile, ota),
                "${profile.deviceClass}",
            )
        }
    }
}
