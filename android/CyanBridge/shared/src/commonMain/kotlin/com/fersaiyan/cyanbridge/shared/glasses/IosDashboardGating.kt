package com.fersaiyan.cyanbridge.shared.glasses

import com.fersaiyan.cyanbridge.shared.devices.GlassesControlPanel
import com.fersaiyan.cyanbridge.shared.devices.GlassesFamilyProfile

/*
 * The iOS host's dashboard gating. It lives in common code so every build compiles and tests it;
 * only the iOS host (IosAppController) applies it.
 */

/** Vendor panels that are useful without a live link: Meta registration, the Meizu notice, MemoMind's Connect. */
private val PANELS_WITHOUT_LINK = setOf(GlassesControlPanel.META_RAYBAN, GlassesControlPanel.MEIZU_MYVU, GlassesControlPanel.MEMOMIND)

/**
 * The shared family gating ([withFamilyGating]) with the iOS differences:
 * - glasses sections need a live link ([linked]), except the Meta, Meizu and MemoMind cards;
 * - capture settings, wake-word routing, volume, developer tools and firmware only show for
 *   HeyCyan: on iOS only its QCSDK path implements them;
 * - the Local Agent never shows: it runs through Tasker, which iOS does not have;
 * - the device-info label is kept: iOS fills it for HeyCyan and the SDK glasses too;
 * - iOS has no Wi-Fi ADB debugging and no live preview.
 */
internal fun GlassesDashboardUiState.withIosFamilyGating(
    family: GlassesFamilyProfile,
    linked: Boolean,
): GlassesDashboardUiState {
    val gated = withFamilyGating(
        family = family,
        aiWakeWordRoute = aiWakeWordRoute,
        isDebugBuild = false,
        livePreview = null,
    )
    val sections = linked || family.controlPanel in PANELS_WITHOUT_LINK
    val heyCyan = sections && family.controlPanel == GlassesControlPanel.HEY_CYAN
    val deviceInfo = sections && gated.showAdvancedDeviceInfo
    val volume = heyCyan && gated.showAdvancedDeviceVolume
    val imageQuality = sections && gated.showAdvancedImageQuality
    val developerTools = heyCyan && gated.showAdvancedDeveloperTools
    val ota = heyCyan && gated.showAdvancedOta
    return gated.copy(
        deviceInfoLabel = deviceInfoLabel,
        showHeyCyanControls = heyCyan,
        showEyevueControls = sections && gated.showEyevueControls,
        showTuneBudsControls = sections && gated.showTuneBudsControls,
        showMetaRaybanControls = sections && gated.showMetaRaybanControls,
        showMeizuMyvuControls = sections && gated.showMeizuMyvuControls,
        showMemoMindControls = sections && gated.showMemoMindControls,
        showCaptureSettings = heyCyan && gated.showCaptureSettings,
        showAiWakeWordRouting = heyCyan && gated.showAiWakeWordRouting,
        showAdvancedLocalAgent = false,
        showAdvancedDeviceInfo = deviceInfo,
        showAdvancedDeviceVolume = volume,
        showAdvancedImageQuality = imageQuality,
        showAdvancedDeveloperTools = developerTools,
        showAdvancedOta = ota,
        showAdvancedControls = heyCyan || deviceInfo || volume || imageQuality || developerTools || ota,
    )
}
