package com.fersaiyan.cyanbridge.shared.ui

import androidx.compose.runtime.Composable
import com.fersaiyan.cyanbridge.shared.appearance.AccentProfile
import com.fersaiyan.cyanbridge.shared.appearance.ThemeMode
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.generated.resources.accent_cyan
import com.fersaiyan.cyanbridge.shared.generated.resources.accent_lavender
import com.fersaiyan.cyanbridge.shared.generated.resources.accent_mint
import com.fersaiyan.cyanbridge.shared.generated.resources.accent_peach
import com.fersaiyan.cyanbridge.shared.generated.resources.accent_rose
import com.fersaiyan.cyanbridge.shared.generated.resources.accent_sky
import com.fersaiyan.cyanbridge.shared.generated.resources.Res
import com.fersaiyan.cyanbridge.shared.generated.resources.device_class_eye_vue
import com.fersaiyan.cyanbridge.shared.generated.resources.device_class_generic_audio
import com.fersaiyan.cyanbridge.shared.generated.resources.device_class_heycyan
import com.fersaiyan.cyanbridge.shared.generated.resources.device_class_meizu_myvu
import com.fersaiyan.cyanbridge.shared.generated.resources.device_class_meta_rayban
import com.fersaiyan.cyanbridge.shared.generated.resources.device_class_tunebuds
import com.fersaiyan.cyanbridge.shared.generated.resources.device_class_unknown
import com.fersaiyan.cyanbridge.shared.generated.resources.destination_chats_subtitle
import com.fersaiyan.cyanbridge.shared.generated.resources.destination_glasses_subtitle
import com.fersaiyan.cyanbridge.shared.generated.resources.destination_media_subtitle
import com.fersaiyan.cyanbridge.shared.generated.resources.destination_plugins_subtitle
import com.fersaiyan.cyanbridge.shared.generated.resources.destination_settings_subtitle
import com.fersaiyan.cyanbridge.shared.generated.resources.memory_confidential_cloud
import com.fersaiyan.cyanbridge.shared.generated.resources.memory_confidential_cloud_description
import com.fersaiyan.cyanbridge.shared.generated.resources.memory_encrypted_sync
import com.fersaiyan.cyanbridge.shared.generated.resources.memory_encrypted_sync_description
import com.fersaiyan.cyanbridge.shared.generated.resources.memory_fast_cloud
import com.fersaiyan.cyanbridge.shared.generated.resources.memory_fast_cloud_description
import com.fersaiyan.cyanbridge.shared.generated.resources.memory_private_local
import com.fersaiyan.cyanbridge.shared.generated.resources.memory_private_local_description
import com.fersaiyan.cyanbridge.shared.generated.resources.nav_chats
import com.fersaiyan.cyanbridge.shared.generated.resources.nav_glasses
import com.fersaiyan.cyanbridge.shared.generated.resources.nav_media
import com.fersaiyan.cyanbridge.shared.generated.resources.nav_plugins
import com.fersaiyan.cyanbridge.shared.generated.resources.nav_settings
import com.fersaiyan.cyanbridge.shared.generated.resources.ota_source_debug
import com.fersaiyan.cyanbridge.shared.generated.resources.ota_source_debug_description
import com.fersaiyan.cyanbridge.shared.generated.resources.ota_source_personal
import com.fersaiyan.cyanbridge.shared.generated.resources.ota_source_personal_description
import com.fersaiyan.cyanbridge.shared.generated.resources.ota_source_stealth
import com.fersaiyan.cyanbridge.shared.generated.resources.ota_source_stealth_description
import com.fersaiyan.cyanbridge.shared.generated.resources.ota_target_ble
import com.fersaiyan.cyanbridge.shared.generated.resources.ota_target_ble_description
import com.fersaiyan.cyanbridge.shared.generated.resources.ota_target_wifi
import com.fersaiyan.cyanbridge.shared.generated.resources.ota_target_wifi_description
import com.fersaiyan.cyanbridge.shared.generated.resources.plugin_category_accessibility
import com.fersaiyan.cyanbridge.shared.generated.resources.plugin_category_language
import com.fersaiyan.cyanbridge.shared.generated.resources.plugin_category_mobility
import com.fersaiyan.cyanbridge.shared.generated.resources.plugin_category_operations
import com.fersaiyan.cyanbridge.shared.generated.resources.plugin_category_other
import com.fersaiyan.cyanbridge.shared.generated.resources.plugin_category_planner
import com.fersaiyan.cyanbridge.shared.generated.resources.plugin_category_productivity
import com.fersaiyan.cyanbridge.shared.generated.resources.provider_local
import com.fersaiyan.cyanbridge.shared.generated.resources.provider_pro
import com.fersaiyan.cyanbridge.shared.generated.resources.provider_tasker
import com.fersaiyan.cyanbridge.shared.generated.resources.sync_flow_custom_description
import com.fersaiyan.cyanbridge.shared.generated.resources.sync_flow_custom_title
import com.fersaiyan.cyanbridge.shared.generated.resources.sync_flow_official_description
import com.fersaiyan.cyanbridge.shared.generated.resources.sync_flow_official_title
import com.fersaiyan.cyanbridge.shared.generated.resources.theme_dark
import com.fersaiyan.cyanbridge.shared.generated.resources.theme_follow_system
import com.fersaiyan.cyanbridge.shared.generated.resources.theme_light
import com.fersaiyan.cyanbridge.shared.glasses.GlassesSyncFlow
import com.fersaiyan.cyanbridge.shared.glasses.OtaFirmwareSource
import com.fersaiyan.cyanbridge.shared.glasses.OtaTargetSelection
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.settings.AgentProviderType
import com.fersaiyan.cyanbridge.shared.settings.MemoryPrivacyMode
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalResourceApi::class)
@Composable
fun localizedDestinationLabel(destination: AppDestination): String = stringResource(
    when (destination) {
        AppDestination.GLASSES -> Res.string.nav_glasses
        AppDestination.CHATS -> Res.string.nav_chats
        AppDestination.MEDIA -> Res.string.nav_media
        AppDestination.PLUGINS -> Res.string.nav_plugins
        AppDestination.SETTINGS -> Res.string.nav_settings
    },
)

@OptIn(ExperimentalResourceApi::class)
@Composable
fun localizedDestinationSubtitle(destination: AppDestination): String = stringResource(
    when (destination) {
        AppDestination.GLASSES -> Res.string.destination_glasses_subtitle
        AppDestination.CHATS -> Res.string.destination_chats_subtitle
        AppDestination.MEDIA -> Res.string.destination_media_subtitle
        AppDestination.PLUGINS -> Res.string.destination_plugins_subtitle
        AppDestination.SETTINGS -> Res.string.destination_settings_subtitle
    },
)

@OptIn(ExperimentalResourceApi::class)
@Composable
fun localizedDeviceClass(deviceClass: DeviceClass): String = stringResource(
    when (deviceClass) {
        DeviceClass.HEY_CYAN -> Res.string.device_class_heycyan
        DeviceClass.EYEVUE -> Res.string.device_class_eye_vue
        DeviceClass.TUNEBUDS -> Res.string.device_class_tunebuds
        DeviceClass.META_RAYBAN -> Res.string.device_class_meta_rayban
        DeviceClass.MEIZU_MYVU -> Res.string.device_class_meizu_myvu
        DeviceClass.GENERIC_AUDIO -> Res.string.device_class_generic_audio
        DeviceClass.UNKNOWN -> Res.string.device_class_unknown
    },
)

@OptIn(ExperimentalResourceApi::class)
@Composable
fun localizedProviderLabel(provider: AgentProviderType): String = stringResource(
    when (provider) {
        AgentProviderType.TASKER -> Res.string.provider_tasker
        AgentProviderType.LOCAL_AGENT -> Res.string.provider_local
        AgentProviderType.PRO_SUBSCRIPTION -> Res.string.provider_pro
    },
)

@OptIn(ExperimentalResourceApi::class)
@Composable
fun localizedThemeMode(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> Res.string.theme_follow_system
        ThemeMode.LIGHT -> Res.string.theme_light
        ThemeMode.DARK -> Res.string.theme_dark
    },
)

@OptIn(ExperimentalResourceApi::class)
@Composable
fun localizedAccentProfile(profile: AccentProfile): String = stringResource(
    when (profile.id) {
        "rose" -> Res.string.accent_rose
        "mint" -> Res.string.accent_mint
        "lavender" -> Res.string.accent_lavender
        "peach" -> Res.string.accent_peach
        "sky" -> Res.string.accent_sky
        else -> Res.string.accent_cyan
    },
)

@OptIn(ExperimentalResourceApi::class)
@Composable
fun localizedMemoryModeTitle(mode: MemoryPrivacyMode): String = stringResource(
    when (mode) {
        MemoryPrivacyMode.PRIVATE_LOCAL -> Res.string.memory_private_local
        MemoryPrivacyMode.ENCRYPTED_SYNC -> Res.string.memory_encrypted_sync
        MemoryPrivacyMode.FAST_CLOUD_MEMORY -> Res.string.memory_fast_cloud
        MemoryPrivacyMode.CONFIDENTIAL_CLOUD_BETA -> Res.string.memory_confidential_cloud
    },
)

@OptIn(ExperimentalResourceApi::class)
@Composable
fun localizedMemoryModeDescription(mode: MemoryPrivacyMode): String = stringResource(
    when (mode) {
        MemoryPrivacyMode.PRIVATE_LOCAL -> Res.string.memory_private_local_description
        MemoryPrivacyMode.ENCRYPTED_SYNC -> Res.string.memory_encrypted_sync_description
        MemoryPrivacyMode.FAST_CLOUD_MEMORY -> Res.string.memory_fast_cloud_description
        MemoryPrivacyMode.CONFIDENTIAL_CLOUD_BETA -> Res.string.memory_confidential_cloud_description
    },
)

@OptIn(ExperimentalResourceApi::class)
@Composable
fun localizedSyncFlowLabel(flow: GlassesSyncFlow): String = stringResource(
    when (flow) {
        GlassesSyncFlow.OFFICIAL_HEYCYAN -> Res.string.sync_flow_official_title
        GlassesSyncFlow.CUSTOM -> Res.string.sync_flow_custom_title
    },
)

@OptIn(ExperimentalResourceApi::class)
@Composable
fun localizedSyncFlowDescription(flow: GlassesSyncFlow): String = stringResource(
    when (flow) {
        GlassesSyncFlow.OFFICIAL_HEYCYAN -> Res.string.sync_flow_official_description
        GlassesSyncFlow.CUSTOM -> Res.string.sync_flow_custom_description
    },
)

@OptIn(ExperimentalResourceApi::class)
@Composable
fun localizedOtaTargetLabel(target: OtaTargetSelection): String = stringResource(
    when (target) {
        OtaTargetSelection.V821_WIFI -> Res.string.ota_target_wifi
        OtaTargetSelection.JIELI_BLE -> Res.string.ota_target_ble
    },
)

@OptIn(ExperimentalResourceApi::class)
@Composable
fun localizedOtaTargetDescription(target: OtaTargetSelection): String = stringResource(
    when (target) {
        OtaTargetSelection.V821_WIFI -> Res.string.ota_target_wifi_description
        OtaTargetSelection.JIELI_BLE -> Res.string.ota_target_ble_description
    },
)

@OptIn(ExperimentalResourceApi::class)
@Composable
fun localizedOtaSourceLabel(source: OtaFirmwareSource): String = stringResource(
    when (source) {
        OtaFirmwareSource.PERSONAL_FILE -> Res.string.ota_source_personal
        OtaFirmwareSource.STEALTH_CATALOG -> Res.string.ota_source_stealth
        OtaFirmwareSource.DEBUG_CATALOG -> Res.string.ota_source_debug
    },
)

@OptIn(ExperimentalResourceApi::class)
@Composable
fun localizedOtaSourceDescription(source: OtaFirmwareSource): String = stringResource(
    when (source) {
        OtaFirmwareSource.PERSONAL_FILE -> Res.string.ota_source_personal_description
        OtaFirmwareSource.STEALTH_CATALOG -> Res.string.ota_source_stealth_description
        OtaFirmwareSource.DEBUG_CATALOG -> Res.string.ota_source_debug_description
    },
)

@OptIn(ExperimentalResourceApi::class)
@Composable
fun localizedPluginCategory(category: String): String = stringResource(
    when (category.trim().lowercase()) {
        "productivity" -> Res.string.plugin_category_productivity
        "accessibility" -> Res.string.plugin_category_accessibility
        "planner" -> Res.string.plugin_category_planner
        "mobility" -> Res.string.plugin_category_mobility
        "operations" -> Res.string.plugin_category_operations
        "language" -> Res.string.plugin_category_language
        else -> Res.string.plugin_category_other
    },
)
