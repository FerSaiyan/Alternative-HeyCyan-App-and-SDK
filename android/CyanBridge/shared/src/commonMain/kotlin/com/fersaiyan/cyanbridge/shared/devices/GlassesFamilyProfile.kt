package com.fersaiyan.cyanbridge.shared.devices

import com.fersaiyan.cyanbridge.bridge.core.GlassesCapability
import com.fersaiyan.cyanbridge.shared.plugins.NativePluginIds

/** Vendor-specific dashboard panel a glasses family adds next to the shared sections. */
enum class GlassesControlPanel {
    NONE,
    HEY_CYAN,
    EYEVUE,
    TUNEBUDS,
    META_RAYBAN,
    MEIZU_MYVU,
    MEMOMIND,
}

/** Where the selected glasses deliver microphone audio to the phone. */
enum class GlassesAudioInput {
    /** The glasses expose no microphone. */
    NONE,

    /** The phone captures the glasses microphone through the Bluetooth headset (SCO/LE) route. */
    BLUETOOTH_HEADSET,

    /** The vendor SDK streams PCM; opening the Bluetooth headset route in parallel duplicates audio. */
    VENDOR_PCM_STREAM,
}

/** How phone audio reaches the glasses speaker. */
enum class GlassesAudioOutput {
    NONE,

    /** Standard Bluetooth audio route. */
    BLUETOOTH,

    /** Bluetooth audio route, and the vendor SDK must be told when the app is playing audio. */
    BLUETOOTH_WITH_PLAYBACK_SIGNAL,
}

/** Device-side audio capability: what the glasses offer and how the host should route it. */
data class GlassesAudioProfile(
    val input: GlassesAudioInput,
    val output: GlassesAudioOutput,
) {
    val hasMicrophone: Boolean get() = input != GlassesAudioInput.NONE
    val hasSpeaker: Boolean get() = output != GlassesAudioOutput.NONE
}

/**
 * Reason shown when a HeyCyan SDK-only control is used with a family whose transport cannot
 * serve it.
 */
data class HeyCyanSdkControlsRejection(
    val familyName: String,
    val hint: String,
) {
    fun message(feature: String): String = "$feature is unavailable for $familyName. $hint"
}

/**
 * Declarative description of one glasses family: which capabilities it supports and which
 * vendor-specific affordances it adds. The dashboard gating, plugin availability and the
 * platform hosts read this instead of branching on [DeviceClass].
 */
data class GlassesFamilyProfile(
    val deviceClass: DeviceClass,
    val capabilities: Set<GlassesCapability>,
    val controlPanel: GlassesControlPanel = GlassesControlPanel.NONE,
    val audio: GlassesAudioProfile = GlassesAudioProfile(GlassesAudioInput.NONE, GlassesAudioOutput.NONE),
    /**
     * When true, dashboard actions whose capability the family does not declare are dropped
     * before they reach a handler (used for families whose remaining handlers are unvalidated).
     */
    val dashboardActionsLimitedToCapabilities: Boolean = false,
    /** Non-null when HeyCyan SDK-only controls must be refused for this family. */
    val heyCyanSdkControlsRejection: HeyCyanSdkControlsRejection? = null,
    /**
     * True when voice queries use the phone's audio route and may run while the family's own
     * exclusive camera session holds the glasses connection.
     */
    val voiceQueriesBypassCameraSession: Boolean = false,
    /** Shown when camera plugins are unavailable for this family; null when not explained. */
    val cameraUnavailableReason: String? = null,
    /** Native plugins this family cannot run, with the message shown when one is started. */
    val unsupportedNativePlugins: Map<String, String> = emptyMap(),
    /**
     * Assistant answers also appear on the glasses display (through the display bridge) while they
     * are spoken. Needs [GlassesCapability.TEXT_DISPLAY].
     */
    val showsAssistantAnswers: Boolean = false,
) {
    fun supports(capability: GlassesCapability): Boolean = capability in capabilities

    val hasCamera: Boolean get() = supports(GlassesCapability.PHOTO_CAPTURE)
}

/** The capability profile of every supported glasses family. */
object GlassesFamilyProfiles {
    private val bluetoothHeadsetAudio = GlassesAudioProfile(
        input = GlassesAudioInput.BLUETOOTH_HEADSET,
        output = GlassesAudioOutput.BLUETOOTH,
    )

    val heyCyan = GlassesFamilyProfile(
        deviceClass = DeviceClass.HEY_CYAN,
        capabilities = setOf(
            GlassesCapability.PHOTO_CAPTURE,
            GlassesCapability.AI_PHOTO_CAPTURE,
            GlassesCapability.VIDEO_RECORDING,
            GlassesCapability.ONBOARD_AUDIO_RECORDING,
            GlassesCapability.MEDIA_COUNT,
            GlassesCapability.MEDIA_SYNC,
            GlassesCapability.LIVE_PREVIEW,
            GlassesCapability.BATTERY_STATUS,
            GlassesCapability.STORAGE_STATUS,
            GlassesCapability.DEVICE_INFO,
            GlassesCapability.TIME_SYNC,
            GlassesCapability.VOLUME_STATUS,
            GlassesCapability.IMAGE_QUALITY_SELECTION,
            GlassesCapability.RECORDING_SETTINGS,
            GlassesCapability.WAKE_WORD,
            GlassesCapability.DEVELOPER_TOOLS,
            GlassesCapability.FIRMWARE_UPDATE,
            GlassesCapability.WIFI_ADB_DEBUG,
            GlassesCapability.BUTTON_INPUT,
            GlassesCapability.MICROPHONE_AUDIO,
            GlassesCapability.SPEAKER_AUDIO,
        ),
        controlPanel = GlassesControlPanel.HEY_CYAN,
        audio = bluetoothHeadsetAudio,
    )

    val eyevue = GlassesFamilyProfile(
        deviceClass = DeviceClass.EYEVUE,
        capabilities = setOf(
            GlassesCapability.PHOTO_CAPTURE,
            GlassesCapability.AI_PHOTO_CAPTURE,
            GlassesCapability.VIDEO_RECORDING,
            GlassesCapability.ONBOARD_AUDIO_RECORDING,
            GlassesCapability.MEDIA_COUNT,
            GlassesCapability.MEDIA_SYNC,
            GlassesCapability.LIVE_PREVIEW,
            GlassesCapability.BATTERY_STATUS,
            GlassesCapability.STORAGE_STATUS,
            GlassesCapability.DEVICE_INFO,
            GlassesCapability.TIME_SYNC,
            GlassesCapability.VOLUME_STATUS,
            // Eyevue AI capture currently has one verified path: 0x31 high-quality over AA15.
            // Its 0x30 mode does not return the AI-photo stream, so exposing six quality
            // choices would be misleading. Add IMAGE_QUALITY_SELECTION if additional Eyevue
            // quality commands are identified and validated in the future.
            GlassesCapability.RECORDING_SETTINGS,
            GlassesCapability.WEARING_DETECTION,
            GlassesCapability.WAKE_WORD,
            GlassesCapability.DEVELOPER_TOOLS,
            GlassesCapability.FIRMWARE_UPDATE,
            GlassesCapability.BUTTON_INPUT,
            GlassesCapability.MICROPHONE_AUDIO,
            GlassesCapability.SPEAKER_AUDIO,
        ),
        controlPanel = GlassesControlPanel.EYEVUE,
        audio = bluetoothHeadsetAudio,
    )

    val tuneBuds = GlassesFamilyProfile(
        deviceClass = DeviceClass.TUNEBUDS,
        capabilities = setOf(
            GlassesCapability.PHOTO_CAPTURE,
            GlassesCapability.AI_PHOTO_CAPTURE,
            GlassesCapability.VIDEO_RECORDING,
            GlassesCapability.ONBOARD_AUDIO_RECORDING,
            GlassesCapability.MEDIA_COUNT,
            GlassesCapability.MEDIA_SYNC,
            GlassesCapability.LIVE_PREVIEW,
            GlassesCapability.BATTERY_STATUS,
            GlassesCapability.STORAGE_STATUS,
            GlassesCapability.DEVICE_INFO,
            GlassesCapability.TIME_SYNC,
            GlassesCapability.IMAGE_QUALITY_SELECTION,
            GlassesCapability.BUTTON_INPUT,
            GlassesCapability.MICROPHONE_AUDIO,
            GlassesCapability.SPEAKER_AUDIO,
        ),
        controlPanel = GlassesControlPanel.TUNEBUDS,
        audio = bluetoothHeadsetAudio,
        dashboardActionsLimitedToCapabilities = true,
    )

    val moyoungW620 = GlassesFamilyProfile(
        deviceClass = DeviceClass.MOYOUNG_W620,
        capabilities = setOf(
            GlassesCapability.MEDIA_COUNT,
            GlassesCapability.MEDIA_SYNC,
            GlassesCapability.BATTERY_STATUS,
            GlassesCapability.STORAGE_STATUS,
        ),
        cameraUnavailableReason =
            "The MoYoung / W620 adapter supports media sync, but direct camera capture is not validated yet.",
    )

    val metaRayban = GlassesFamilyProfile(
        deviceClass = DeviceClass.META_RAYBAN,
        capabilities = setOf(
            GlassesCapability.PHOTO_CAPTURE,
            GlassesCapability.MICROPHONE_AUDIO,
            GlassesCapability.SPEAKER_AUDIO,
        ),
        controlPanel = GlassesControlPanel.META_RAYBAN,
        audio = bluetoothHeadsetAudio,
        heyCyanSdkControlsRejection = HeyCyanSdkControlsRejection(
            familyName = "Meta Ray-Ban",
            hint = "Use the DAT controls instead.",
        ),
        voiceQueriesBypassCameraSession = true,
        unsupportedNativePlugins = mapOf(
            NativePluginIds.AUTO_AUDIO to "Auto Audio records HeyCyan onboard files and is unavailable for Meta Ray-Ban.",
        ),
    )

    /**
     * Mentra Live over the Mentra Bluetooth SDK. Media sync downloads the glasses gallery over
     * their Wi-Fi hotspot. Firmware updates run the SDK's own OTA flow on a dedicated screen
     * ([GlassesCapability.SDK_FIRMWARE_UPDATE]), never the HeyCyan OTA section. Live preview:
     * the glasses publish RTMP to an ingest on the phone; broadcast: the glasses publish to the
     * user's own RTMP/SRT server ([GlassesCapability.LIVE_BROADCAST]).
     */
    val mentraLive = GlassesFamilyProfile(
        deviceClass = DeviceClass.MENTRA_LIVE,
        capabilities = setOf(
            GlassesCapability.MICROPHONE_AUDIO,
            GlassesCapability.SPEAKER_AUDIO,
            GlassesCapability.PHOTO_CAPTURE,
            GlassesCapability.AI_PHOTO_CAPTURE,
            GlassesCapability.VIDEO_RECORDING,
            GlassesCapability.MEDIA_COUNT,
            GlassesCapability.MEDIA_SYNC,
            GlassesCapability.BUTTON_INPUT,
            GlassesCapability.BATTERY_STATUS,
            GlassesCapability.DEVICE_INFO,
            GlassesCapability.TIME_SYNC,
            GlassesCapability.VOLUME_STATUS,
            GlassesCapability.SDK_FIRMWARE_UPDATE,
            GlassesCapability.LIVE_PREVIEW,
            GlassesCapability.LIVE_BROADCAST,
        ),
        audio = GlassesAudioProfile(
            input = GlassesAudioInput.VENDOR_PCM_STREAM,
            output = GlassesAudioOutput.BLUETOOTH_WITH_PLAYBACK_SIGNAL,
        ),
    )

    private const val MEIZU_MYVU_PLUGIN_MESSAGE =
        "This plugin requires a camera or HeyCyan onboard media. MYVU supports display and microphone plugins only."

    val meizuMyvu = GlassesFamilyProfile(
        deviceClass = DeviceClass.MEIZU_MYVU,
        capabilities = setOf(
            GlassesCapability.TEXT_DISPLAY,
            GlassesCapability.LINE_DISPLAY,
            GlassesCapability.CARD_DISPLAY,
            GlassesCapability.CLEAR_DISPLAY,
            GlassesCapability.BATTERY_STATUS,
            GlassesCapability.BRIGHTNESS_CONTROL,
            GlassesCapability.MICROPHONE_AUDIO,
            GlassesCapability.SPEAKER_AUDIO,
            GlassesCapability.NOTIFICATIONS,
        ),
        controlPanel = GlassesControlPanel.MEIZU_MYVU,
        audio = bluetoothHeadsetAudio,
        cameraUnavailableReason = "Selected device profile (Meizu MYVU) has no camera.",
        unsupportedNativePlugins = mapOf(
            NativePluginIds.AUTO_AUDIO to MEIZU_MYVU_PLUGIN_MESSAGE,
            NativePluginIds.VISUAL_DIARY to MEIZU_MYVU_PLUGIN_MESSAGE,
            NativePluginIds.WALKING_AID to MEIZU_MYVU_PLUGIN_MESSAGE,
        ),
    )

    private const val MEMOMIND_PLUGIN_MESSAGE =
        "This plugin requires a camera or HeyCyan onboard media. MemoMind supports display and microphone plugins only."

    /**
     * MemoMind glasses over the published GM protocol: native HUD screens (notification cards,
     * teleprompter, Web Bridge drawing), battery and brightness, Web Bridge button/head input,
     * HFP microphone and speaker, and an Opus microphone stream on the recording link.
     */
    val memoMind = GlassesFamilyProfile(
        deviceClass = DeviceClass.MEMOMIND,
        capabilities = setOf(
            GlassesCapability.TEXT_DISPLAY,
            GlassesCapability.LINE_DISPLAY,
            GlassesCapability.CARD_DISPLAY,
            GlassesCapability.CLEAR_DISPLAY,
            GlassesCapability.NOTIFICATIONS,
            GlassesCapability.BATTERY_STATUS,
            GlassesCapability.BRIGHTNESS_CONTROL,
            GlassesCapability.BUTTON_INPUT,
            GlassesCapability.HEAD_GESTURE_INPUT,
            GlassesCapability.MICROPHONE_AUDIO,
            GlassesCapability.SPEAKER_AUDIO,
        ),
        controlPanel = GlassesControlPanel.MEMOMIND,
        // Voice questions use the HFP route like other headsets; the Opus stream is opt-in.
        audio = bluetoothHeadsetAudio,
        showsAssistantAnswers = true,
        cameraUnavailableReason = "Selected device profile (MemoMind) has no camera.",
        unsupportedNativePlugins = mapOf(
            NativePluginIds.AUTO_AUDIO to MEMOMIND_PLUGIN_MESSAGE,
            NativePluginIds.VISUAL_DIARY to MEMOMIND_PLUGIN_MESSAGE,
            NativePluginIds.WALKING_AID to MEMOMIND_PLUGIN_MESSAGE,
        ),
    )

    val genericAudio = GlassesFamilyProfile(
        deviceClass = DeviceClass.GENERIC_AUDIO,
        capabilities = setOf(
            GlassesCapability.MICROPHONE_AUDIO,
            GlassesCapability.SPEAKER_AUDIO,
        ),
        audio = bluetoothHeadsetAudio,
        cameraUnavailableReason = "Selected device profile (Earbuds / Audio-only glasses) has no camera.",
    )

    /**
     * No class detected and none chosen. The camera stays available because unclassified
     * glasses are most often HeyCyan hardware, but no device-specific dashboard sections show.
     */
    val unknown = GlassesFamilyProfile(
        deviceClass = DeviceClass.UNKNOWN,
        capabilities = setOf(GlassesCapability.PHOTO_CAPTURE),
    )

    val all: List<GlassesFamilyProfile> = listOf(
        heyCyan,
        eyevue,
        tuneBuds,
        moyoungW620,
        metaRayban,
        mentraLive,
        meizuMyvu,
        memoMind,
        genericAudio,
        unknown,
    )

    private val byClass: Map<DeviceClass, GlassesFamilyProfile> = all.associateBy { it.deviceClass }

    fun forClass(deviceClass: DeviceClass): GlassesFamilyProfile = byClass.getValue(deviceClass)

    fun forProfile(profile: DeviceProfile?): GlassesFamilyProfile =
        forClass(profile?.selectedClass ?: DeviceClass.UNKNOWN)
}
